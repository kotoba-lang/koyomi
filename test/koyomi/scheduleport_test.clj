(ns koyomi.scheduleport-test
  "ICS generation is the only place koyomi touches RFC 5545 text — an
  unescaped free-text field (e.g. an LLM-proposed event title) is a line-
  injection vector the governor never sees, since koyomi.governor/
  consent-violations only inspects the structured :calendar/attendees
  vector, never rendered ICS text."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [koyomi.scheduleport :as scheduleport]))

(deftest adversarial-title-cannot-inject-a-forged-attendee-line
  (testing "a title carrying an embedded CRLF + a fake ATTENDEE line renders as
            one inert, escaped SUMMARY value — no new ATTENDEE line appears"
    (let [evil-title "Q3 Planning\r\nATTENDEE:mailto:attacker@evil.example"
          event {:calendar/id "ev-inject" :calendar/title evil-title
                 :calendar/start "2026-07-10T09:00:00Z" :calendar/end "2026-07-10T10:00:00Z"
                 :calendar/attendees ["att-alice" "att-bob"]}
          ics (scheduleport/ics-string event)
          lines (str/split ics #"\r\n")]
      (is (not (str/includes? ics "\r\nATTENDEE:mailto:attacker"))
          "the injected CRLF+ATTENDEE text must not survive as a raw, parser-visible line break")
      (let [attendee-lines (filter #(str/starts-with? % "ATTENDEE:") lines)]
        (is (= 2 (count attendee-lines))
            "exactly the 2 real attendees are rendered as ATTENDEE lines — none fabricated from the title")
        (is (not-any? #(str/includes? % "attacker@evil.example") attendee-lines)
            "no ATTENDEE line named the attacker at all — the injection attempt only ever
             shows up inertly inside the escaped SUMMARY value, never as its own property"))
      (let [summary-line (first (filter #(str/starts-with? % "SUMMARY:") lines))]
        (is (some? summary-line))
        (is (str/includes? summary-line "\\nATTENDEE:mailto:attacker@evil.example")
            "the malicious text survives only as an inert, backslash-escaped literal inside SUMMARY")))))

(deftest text-escaping-is-rfc5545-compliant
  (testing "backslash, comma, semicolon, and embedded newlines are escaped per RFC 5545 §3.3.11"
    (let [event {:calendar/id "ev-esc" :calendar/title "Budget, Q3; \"final\" review\\draft"
                 :calendar/start "2026-07-10T09:00:00Z" :calendar/end "2026-07-10T10:00:00Z"
                 :calendar/attendees ["att-alice"]}
          ics (scheduleport/ics-string event)
          lines (str/split ics #"\r\n")
          summary-line (first (filter #(str/starts-with? % "SUMMARY:") lines))]
      (is (= "SUMMARY:Budget\\, Q3\\; \"final\" review\\\\draft" summary-line)))))

(deftest clean-title-is-unaffected
  (testing "a plain title with no reserved characters renders unchanged"
    (let [event {:calendar/id "ev-clean" :calendar/title "Board meeting"
                 :calendar/start "2026-07-10T09:00:00Z" :calendar/end "2026-07-10T10:00:00Z"
                 :calendar/attendees ["att-alice" "att-bob"]}
          ics (scheduleport/ics-string event)
          lines (str/split ics #"\r\n")]
      (is (= "SUMMARY:Board meeting" (first (filter #(str/starts-with? % "SUMMARY:") lines))))
      (is (= 2 (count (filter #(str/starts-with? % "ATTENDEE:") lines)))))))
