(ns koyomi.scheduleport
  "ScheduleTarget port — the ONLY place an event actually leaves the building.
  A schedule-LLM proposal is data (a `:draft` record) until a human approves
  sharing it; `share!` is called exactly once, after that approval, by
  `koyomi.operation`'s commit step. `calendar.model` has ZERO ICS-export or
  sharing concept (verified — grepping ics|ical|share|publish across it is a
  zero hit), so koyomi owns building the ICS string itself here, then hands
  it to an injected Distributor fn for actual invite delivery (same
  injection shape as kekkai/tayori's ports). `mock-scheduleport` is the
  default — a deterministic in-memory target so the actor is runnable and
  testable with no network/creds."
  (:require [clojure.string :as str]))

(defprotocol ScheduleTarget
  (fetch-event [st event-id] "the event's last-shared content, or nil")
  (propose-revision! [st event-id content]
    "record `content` (a calendar.model event EDN) as a proposed revision —
    not yet shared. Returns a value to be recorded onto the draft (e.g.
    {:proposal-id ...}).")
  (share! [st event-id content]
    "build an ICS string from `content` and hand it (+ event id + attendees)
    to the target's injected distributor for actual invite delivery — the
    actuation. Only ever called after human approval."))

;; ───────────────────────── ICS generation (koyomi-owned) ─────────────────────────

(defn- ics-date
  "ISO-8601 extended (\"2026-07-10T09:00:00Z\") → ICS basic
  (\"20260710T090000Z\") — literally stripping the '-'/':' separators; both
  calendar.model's stored strings and RFC 5545's DATE-TIME are UTC 'Z' forms,
  so no timezone/date-library math is needed."
  [iso]
  (str/replace (or iso "") #"[-:]" ""))

(defn ics-string
  "A minimal RFC 5545 VCALENDAR/VEVENT string built from a calendar.model
  event EDN (:calendar/id/title/start/end/attendees). calendar.model itself
  has no export concept — this is koyomi's own, and it is the only place in
  the actor that touches ICS."
  [{:calendar/keys [id title start end attendees] :as _event}]
  (str/join "\r\n"
    (concat
      ["BEGIN:VCALENDAR" "VERSION:2.0" "PRODID:-//kotoba-lang//koyomi//EN"
       "BEGIN:VEVENT"
       (str "UID:" id "@koyomi.kotoba-lang")
       (str "SUMMARY:" title)
       (str "DTSTART:" (ics-date start))
       (str "DTEND:" (ics-date end))]
      (map #(str "ATTENDEE:mailto:" %) attendees)
      ["END:VEVENT" "END:VCALENDAR" ""])))

;; ───────────────────────── mock (default, runnable offline) ─────────────────────────

(defn mock-scheduleport
  "A deterministic in-memory ScheduleTarget: `shared` is an atom of
  {event-id -> {:event-id :ics :attendees}} so tests/sim can assert on what
  WOULD have gone out, without any network call. `distributor` is the
  injected fn `share!` calls with that same map for actual invite delivery —
  default is a no-op (nothing beyond recording into `shared`)."
  ([] (mock-scheduleport (atom {}) (fn [_] nil)))
  ([shared] (mock-scheduleport shared (fn [_] nil)))
  ([shared distributor]
   (reify ScheduleTarget
     (fetch-event [_ event-id] (get @shared event-id))
     (propose-revision! [_ event-id _content] {:proposal-id (str "koyomi/" event-id)})
     (share! [_ event-id content]
       (let [rec {:event-id event-id :ics (ics-string content)
                  :attendees (:calendar/attendees content)}]
         (distributor rec)
         (swap! shared assoc event-id rec)
         rec)))))
