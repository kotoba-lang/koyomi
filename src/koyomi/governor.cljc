(ns koyomi.governor
  "ComplianceGovernor — the independent censor that earns schedule-LLM the
  right to *propose* a draft. The LLM has no notion of attendee consent state,
  tenant boundaries, or the no-actuation charter, so this MUST be a separate
  system (rules over the store's ground facts) able to *reject* a proposal
  and fall back to HOLD — the koyomi analog of kekkai's TailnetGovernor /
  tayori's ComplianceGovernor.

  The actor is **propose → draft only**. It never shares/invites anyone;
  sharing is ALWAYS routed to a human (the koyomi analog of tayori's
  always-human `:reply/send` / kekkai's exit-route approval). Below, HARD
  invariants force HOLD (a human cannot approve past a proposal that claims
  to have already shared, a share to a consent-blocked attendee, or an event
  whose tenant doesn't match the activity driving it); a clean share still
  routes to a human (high-stakes).

  HARD invariants:
    :event/draft
      1. No-actuation    — proposal :effect must be :draft, never :share (a
                           control-plane record, never an outbound invite).
      2. Tenant-isolation — the proposed content's :tenant must equal the
                           tenant derived from the driving activity's :repo.
    :event/share
      1. Consent-required — EVERY attendee on the event's content must not be
                           :consent :blocked (not just the first). A
                           :first-contact? attendee is NOT a hard violation
                           (mirrors tayori's contact model exactly) but IS
                           high-stakes below.
      2. Tenant-isolation — same check, over the already-committed draft's
                           content (defense-in-depth: the same sanity check
                           applies at both the draft and the share gate).
    (any other op) — an unrecognized :op is itself a hard violation
                     (fail-closed: a not-yet-wired op must never silently
                     pass as clean).
  SOFT:
    Confidence floor → escalate.
    Double-booking — an existing event that overlaps `content`'s interval AND
      shares an attendee or a linked resource (`calendar.model/overlaps?`) →
      escalate, never hard (concurrent events across shared attendees/
      resources can be entirely legitimate; a human should still take a look).
    `:event/share` is ALWAYS high-stakes → human, at every phase. Any
      :first-contact? attendee on the event also forces high-stakes (even at
      :event/draft) — mirrors tayori's contact model, applied to invitees
      instead of message recipients."
  (:require [clojure.set :as set]
            [calendar.model :as cal]
            [koyomi.store :as store]))

(def confidence-floor 0.6)

;; ───────────────────────── invariant checks ─────────────────────────

(defn- actuation-violations [proposal]
  (when (not= :draft (:effect proposal))
    [{:rule :no-actuation
      :detail (str "propose→draft のみ(実共有は人間承認後の share! のみが行う)。effect="
                   (:effect proposal))}]))

(defn- tenant-of-activity [st activity-id] (:repo (store/activity st activity-id)))

(defn- tenant-violations [st activity-id content]
  (when content
    (let [expected (tenant-of-activity st activity-id)
          actual   (:tenant content)]
      (when (and expected (not= expected actual))
        [{:rule :tenant-mismatch
          :detail (str "event tenant " actual " は活動 " activity-id " の repo " expected " と不一致")}]))))

(defn- attendee-contacts [st content]
  (->> (:calendar/attendees content) (map #(store/contact st %)) (remove nil?)))

(defn- consent-violations [st content]
  (->> (attendee-contacts st content)
       (filter #(= :blocked (:consent %)))
       (mapv (fn [c] {:rule :consent-blocked :detail (str (:attendee c) " は共有ブロック対象")}))))

(defn- high-stakes-attendees [st content]
  (filter :first-contact? (attendee-contacts st content)))

(defn- double-booking-conflicts
  "Existing events (excluding event-id itself) that overlap `content`'s
  interval AND share an attendee or a linked resource — a SOFT escalate
  signal, never hard."
  [st event-id content]
  (->> (store/all-events st)
       (remove #(= event-id (:calendar/id %)))
       (filter (fn [ev]
                 (and (cal/overlaps? content ev)
                      (or (seq (set/intersection (set (:calendar/attendees content)) (set (:calendar/attendees ev))))
                          (seq (set/intersection (set (:calendar/links content)) (set (:calendar/links ev))))))))
       vec))

(defn- content-of [request proposal st]
  (case (:op request)
    :event/draft (:content proposal)
    :event/share (:content (store/draft-of st (:event request)))
    nil))

(defn check
  "Censors a schedule-LLM proposal for a koyomi op. Returns
   {:ok? :violations :confidence :hard? :escalate? :high-stakes? :double-booking}.

   Hard violations force HOLD and cannot be overridden. Sharing an event is
   high-stakes → human sign-off even when clean; so is any event with a
   first-contact? attendee."
  [request proposal st]
  (let [op      (:op request)
        content (content-of request proposal st)
        hard    (vec (case op
                       :event/draft
                       (concat (actuation-violations proposal)
                               (tenant-violations st (:activity request) content))
                       :event/share
                       (concat (consent-violations st content)
                               (tenant-violations st (:activity request) content))
                       [{:rule :unrecognized-op :detail (str "未対応 op: " op)}]))
        conf    (:confidence proposal 0.0)
        low?    (< conf confidence-floor)
        dbl     (when content (double-booking-conflicts st (:event request) content))
        stakes? (or (= :event/share op) (boolean (seq (high-stakes-attendees st content))))
        hard?   (boolean (seq hard))]
    {:ok?            (and (not hard?) (not low?) (not stakes?) (empty? dbl))
     :violations     hard
     :confidence     conf
     :hard?          hard?
     :escalate?      (and (not hard?) (or low? stakes? (seq dbl)))
     :high-stakes?   stakes?
     :double-booking dbl}))

(defn hold-fact [request verdict]
  {:t :koyomi-hold :op (:op request) :subject (:event request)
   :disposition :hold :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict) :confidence (:confidence verdict)})
