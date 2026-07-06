(ns koyomi.operation
  "ScheduleActor — one draft/share operation = one supervised actor run, a
  langgraph-clj StateGraph. Two flows share one auditable graph:

    ingest (record-op):  intake → record → END
        :event/register mechanically records a calendar.model event (from an
        activity's due-at) as a durable ground fact. This is the observe
        charter; always on, never an LLM call, never an outbound invite.

    assess (assess-op):  intake → advise → govern → decide → commit|hold|approval
        schedule-LLM (sealed) proposes a drafted/decorated event, or (for
        :event/share) a pass-through recommendation over the already-
        committed draft; ComplianceGovernor enforces no-actuation / consent /
        tenant-isolation; the phase gate adds caution; sharing an event
        ALWAYS routes to a human (interrupt-before :request-approval).

  Single invariant (the koyomi analog of kekkai's no-data-plane-actuation /
  tayori's no-send-no-publish):
    the actor never shares/invites the ComplianceGovernor would reject, and
    schedule-LLM never actuates directly — committing a draft is data (a
    'casual commit'); only a human approval turns it into an outbound
    share/invite."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [koyomi.coordllm :as coordllm]
            [koyomi.governor :as gov]
            [koyomi.phase :as phase]
            [koyomi.scheduleport :as scheduleport]
            [koyomi.store :as store]))

(defn- request->record
  "Map an ingest request to a store ground-fact record."
  [{:keys [op event value]}]
  (case op
    :event/register {:kind :event :id event :value value}))

(defn- subject [{:keys [event]}] event)

(defn- pending-record
  "The store record a clean/approved assess op commits. :event/draft stores
  the proposal itself; :event/share only flips the already-stored draft's
  :status (the share is the EFFECT, tracked separately by commit-effects!,
  not new draft content)."
  [op proposal event-id]
  (case op
    :event/draft
    {:kind :draft :id event-id
     :value {:content (:content proposal) :confidence (:confidence proposal)
             :cites (:cites proposal) :redactions (:redactions proposal) :status :proposed}}
    :event/share {:kind :draft :id event-id :value {:status :shared}}))

(defn- commit-effects!
  "Perform the op-specific EXTERNAL effect BEFORE anything is written to the
  store — if the ScheduleTarget call throws (network error, distributor
  failure, …), no store mutation and no :committed ledger fact happen, so the
  store never durably claims a share that didn't actually occur.

  `:event/draft` records its content via propose-revision! from `record` (the
  commit about to be written), NOT from the store — the store doesn't have it
  yet at this point. `:event/share` reads the draft to share from the store,
  which is safe: that content was already committed in an EARLIER run
  (`:event/draft`), not this one.

  Returns a map of extra store facts to merge in on success (the
  propose-revision! :proposal-id, so a later :event/share knows the draft was
  proposed against a real target), or nil."
  [scheduleport store {:keys [op event]} record]
  (case op
    :event/draft
    (let [{:keys [proposal-id]} (scheduleport/propose-revision! scheduleport event
                                  (get-in record [:value :content]))]
      {:kind :draft :id event :value {:proposal-id proposal-id}})
    :event/share
    (let [d (store/draft-of store event)]
      (scheduleport/share! scheduleport event (:content d))
      nil)
    nil))

(defn build
  "Compiles a ScheduleActor bound to `store` (any koyomi.store/Store).
  opts: :advisor (default mock), :scheduleport (default mock), :checkpointer
  (default in-mem)."
  [store & [{:keys [advisor scheduleport checkpointer]
             :or   {advisor      (coordllm/mock-advisor)
                    scheduleport (koyomi.scheduleport/mock-scheduleport)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; :phase + (future) authn
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ── ingest path: record a ground fact (observe), no LLM/governor ──
      (g/add-node :record
        (fn [{:keys [request]}]
          (let [rec (request->record request)
                f   {:t :recorded :op (:op request) :subject (subject request)
                     :disposition :record :basis (:kind rec)}]
            (store/record-datom! store rec)
            (store/append-ledger! store f)
            {:disposition :record :audit [f]})))

      ;; ── assess path ──
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (coordllm/-advise advisor store request)]
            {:proposal p :audit [(coordllm/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request proposal]}]
          {:verdict (gov/check request proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)
                subj (subject request)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (gov/hold-fact request verdict)
                         reason (assoc :phase-reason reason :phase ph))]}
              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested :op (:op request) :subject subj
                        :reason (or reason (cond (:high-stakes? verdict) :human-signoff
                                                  (seq (:double-booking verdict)) :double-booking
                                                  :else :low-confidence))
                        :recommendation (:recommendation proposal)
                        :phase ph :confidence (:confidence verdict)}]}
              :commit
              {:disposition :commit :record (pending-record (:op request) proposal subj)}))))

      (g/add-node :request-approval
        (fn [{:keys [request proposal approval]}]
          (let [subj (subject request)]
            (if (= :approved (:status approval))
              {:disposition :commit
               :record (update (pending-record (:op request) proposal subj)
                               :value assoc :approved-by (:by approval))
               :audit [{:t :human-signoff :op (:op request) :subject subj
                        :by (:by approval) :recommendation (:recommendation proposal)}]}
              {:disposition :hold
               :audit [{:t :signoff-rejected :op (:op request) :subject subj
                        :disposition :hold :basis [:human-rejected]}]}))))

      ;; op-specific EXTERNAL effect FIRST, then the record + ledger — a
      ;; thrown effect leaves no trace of a share that never actually happened.
      (g/add-node :commit
        (fn [{:keys [request record]}]
          (let [extra (commit-effects! scheduleport store request record)]
            (store/record-datom! store record)
            (when extra (store/record-datom! store extra))
            (let [f {:t :committed :op (:op request) :subject (subject request)
                     :disposition :commit :basis (get-in record [:value :status] :proposed)}]
              (store/append-ledger! store f)
              {:audit [f]}))))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:koyomi-hold :signoff-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      ;; intake routes ingest vs assess.
      (g/add-conditional-edges :intake
        (fn [{:keys [request]}]
          (if (phase/record-op? (:op request)) :record :advise)))
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit, :escalate :request-approval, :hold)))
      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}] (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :record)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer :interrupt-before #{:request-approval}})))
