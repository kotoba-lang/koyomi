(ns koyomi.store-contract-test
  "Store contract against both backends — proving MemStore ≡ DatomicStore makes
  'swap the SSoT for Datomic / kotoba-server' a config change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [koyomi.store :as store]))

(defn- backends [] [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "cloud-itonami" (:repo (store/activity s "act-board"))))
      (is (= :known (:consent (store/contact s "att-alice"))))
      (is (= :blocked (:consent (store/contact s "att-blocked"))))
      (is (true? (:first-contact? (store/contact s "att-newbiz"))))
      (is (= "Board meeting" (:calendar/title (store/event s "ev-board"))))
      (is (= ["att-alice" "att-bob"] (:calendar/attendees (store/event s "ev-board"))))
      (is (= "cloud-itonami" (:tenant (store/event s "ev-board"))))
      (is (= 5 (count (store/all-events s))))
      (is (nil? (store/event s "ev-missing")))
      (is (nil? (store/activity s "act-missing"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (store/record-datom! s {:kind :draft :id "ev-board" :value {:content {:calendar/id "ev-board"} :status :proposed}})
      (is (= :proposed (:status (store/draft-of s "ev-board"))))
      (store/record-datom! s {:kind :draft :id "ev-board" :value {:status :shared}})
      (is (= :shared (:status (store/draft-of s "ev-board"))) "merge updates status")
      (is (some? (:content (store/draft-of s "ev-board"))) "merge preserves other fields")
      (store/record-datom! s {:kind :event :id "ev-board" :value {:calendar/title "Board meeting (revised)"}})
      (is (= "Board meeting (revised)" (:calendar/title (store/event s "ev-board"))))
      (store/append-ledger! s {:op :a :disposition :record})
      (store/append-ledger! s {:op :b :disposition :commit})
      (is (= [:record :commit] (mapv :disposition (store/ledger s)))))))

(deftest datomic-empty-store-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/event s "nope")))
    (is (= [] (store/all-events s)))
    (store/record-datom! s {:kind :event :id "x"
                            :value {:calendar/id "x" :calendar/title "t"
                                    :calendar/start "2026-01-01T00:00:00Z" :calendar/end "2026-01-01T01:00:00Z"
                                    :calendar/attendees [] :calendar/links [] :tenant "t"}})
    (is (= "t" (:calendar/title (store/event s "x"))))))
