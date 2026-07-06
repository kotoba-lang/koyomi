(ns koyomi.governor-contract-test
  "The propose→draft-only contract as executable tests — koyomi's analog of
  kekkai's governor_contract_test / tayori's governor_contract_test.
  Invariant: the actor never shares/invites the ComplianceGovernor would
  reject; drafting never auto-actuates; sharing always routes to a human
  regardless of phase."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [koyomi.store :as store]
            [koyomi.scheduleport :as scheduleport]
            [koyomi.coordllm :as coordllm]
            [koyomi.operation :as op]))

(defn- fresh []
  (let [s (store/seed-db) shared (atom {}) distributed (atom [])
        sp (scheduleport/mock-scheduleport shared #(swap! distributed conj %))]
    [s (op/build s {:scheduleport sp}) shared distributed]))

(defn- ctx [phase] {:phase phase})
(defn- run [actor tid req phase] (g/run* actor {:request req :context (ctx phase)} {:thread-id tid}))

(deftest ingest-always-records
  (testing "observe path records a ground fact regardless of phase"
    (let [[s actor] (fresh)
          res (run actor "i" {:op :event/register :event "ev-standup"
                              :value {:calendar/id "ev-standup" :calendar/title "Standup"
                                      :calendar/start "2026-07-13T09:00:00Z" :calendar/end "2026-07-13T09:15:00Z"
                                      :calendar/attendees ["att-alice"] :calendar/links []
                                      :tenant "cloud-itonami"}} 0)]
      (is (= :record (get-in res [:state :disposition])))
      (is (= "Standup" (:calendar/title (store/event s "ev-standup")))))))

(deftest clean-draft-auto-commits-no-human-needed
  (testing "phase 3: a clean+confident draft is data, not actuation — it commits without interrupting"
    (let [[s actor] (fresh)
          res (run actor "d" {:op :event/draft :activity "act-board" :event "ev-board"} 3)]
      (is (not= :interrupted (:status res)) "drafting is not high-stakes when clean")
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :proposed (:status (store/draft-of s "ev-board"))))
      (is (= "cloud-itonami" (:tenant (:content (store/draft-of s "ev-board")))))
      (is (= ["att-alice" "att-bob"] (:calendar/attendees (:content (store/draft-of s "ev-board"))))))))

(deftest no-actuation-invariant
  (testing "a draft proposal that claims it already shared is held"
    (let [[s _] (fresh)
          bad-adv (reify coordllm/Advisor
                    (-advise [_ _ _] {:recommendation :draft
                                      :content {:calendar/id "ev-board" :calendar/attendees ["att-alice" "att-bob"]
                                                :tenant "cloud-itonami"}
                                      :effect :share :summary "x" :rationale "x"
                                      :cites [] :redactions [] :confidence 0.9}))
          actor (op/build s {:advisor bad-adv})
          res (g/run* actor {:request {:op :event/draft :activity "act-board" :event "ev-board"} :context (ctx 3)}
                      {:thread-id "na"})]
      (is (not= :interrupted (:status res)) "hard violations hold directly, no approval offered")
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-actuation} (-> (store/ledger s) last :basis))))))

(deftest tenant-mismatch-is-held
  (testing "a draft that claims a tenant other than the driving activity's repo is a hijack — HOLD"
    (let [[s _] (fresh)
          bad-adv (reify coordllm/Advisor
                    (-advise [_ _ _] {:recommendation :draft
                                      :content {:calendar/id "ev-board" :calendar/attendees ["att-alice"]
                                                :tenant "rogue-tenant"}
                                      :effect :draft :summary "x" :rationale "x"
                                      :cites [] :redactions [] :confidence 0.9}))
          actor (op/build s {:advisor bad-adv})
          res (g/run* actor {:request {:op :event/draft :activity "act-board" :event "ev-board"} :context (ctx 3)}
                      {:thread-id "tm"})]
      (is (not= :interrupted (:status res)))
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tenant-mismatch} (-> (store/ledger s) last :basis))))))

(deftest sharing-always-requires-human-signoff
  (testing "even a clean draft never auto-shares — it interrupts for a human"
    (let [[s actor _shared distributed] (fresh)
          _  (run actor "d2" {:op :event/draft :activity "act-board" :event "ev-board"} 3)
          r1 (run actor "s2" {:op :event/share :activity "act-board" :event "ev-board"} 3)]
      (is (= :interrupted (:status r1)) "sharing is high-stakes → always human")
      (is (empty? @distributed) "nothing distributed before sign-off")
      (let [r2 (g/run* actor {:approval {:status :approved :by "alice"}}
                       {:thread-id "s2" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :shared (:status (store/draft-of s "ev-board"))))
        (is (= 1 (count @distributed)))))))

(deftest consent-blocked-share-is-held-and-unoverridable
  (testing "ev-blocked: the attendee's consent is :blocked → HOLD, never reaches a human"
    (let [[s actor] (fresh)
          _   (run actor "d3" {:op :event/draft :activity "act-board" :event "ev-blocked"} 3)
          res (run actor "s3" {:op :event/share :activity "act-board" :event "ev-blocked"} 3)
          basis (-> (store/ledger s) last :basis)]
      (is (not= :interrupted (:status res)) "hard violations hold directly, no approval offered")
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:consent-blocked} basis)))))

(deftest phase0-disables-assessments
  (let [[s actor] (fresh)
        res (run actor "p0" {:op :event/draft :activity "act-board" :event "ev-board"} 0)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) last :phase-reason)))))

(deftest double-booking-escalates-but-does-not-hold
  (testing "an overlapping event on a shared attendee is a SOFT escalate, not a HARD hold"
    (let [[s actor] (fresh)
          res (run actor "dbl" {:op :event/draft :activity "act-conflict" :event "ev-conflict-new"} 3)]
      (is (= :interrupted (:status res)) "double-booking escalates to a human, it doesn't auto-hold")
      (let [r2 (g/run* actor {:approval {:status :approved :by "alice"}}
                       {:thread-id "dbl" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition]))
            "a human CAN approve past a double-booking (unlike a hard violation)")
        (is (= :proposed (:status (store/draft-of s "ev-conflict-new"))))))))

(deftest first-contact-attendee-forces-escalate-even-at-draft-time
  (testing "a first-contact attendee is not a hard violation but IS high-stakes, even for :event/draft"
    (let [[s actor] (fresh)
          res (run actor "nb" {:op :event/draft :activity "act-board" :event "ev-newbiz"} 3)]
      (is (= :interrupted (:status res)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "alice"}}
                       {:thread-id "nb" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :proposed (:status (store/draft-of s "ev-newbiz"))))))))

(deftest unrecognized-op-is-held
  (testing "fail-closed: an op the governor doesn't recognize is a hard violation, not a silent pass"
    (let [[s actor] (fresh)
          res (run actor "uo" {:op :event/teleport :activity "act-board" :event "ev-board"} 3)]
      (is (not= :interrupted (:status res)))
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unrecognized-op} (-> (store/ledger s) last :basis))))))

(deftest reject-signoff-holds
  (testing "a human rejection records a hold, not a share"
    (let [[s actor _shared distributed] (fresh)
          _  (run actor "d4" {:op :event/draft :activity "act-board" :event "ev-board"} 3)
          _  (run actor "s4" {:op :event/share :activity "act-board" :event "ev-board"} 3)
          r2 (g/run* actor {:approval {:status :rejected :by "alice"}}
                     {:thread-id "s4" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (= :proposed (:status (store/draft-of s "ev-board"))) "draft stays proposed, never flips to shared")
      (is (empty? @distributed)))))
