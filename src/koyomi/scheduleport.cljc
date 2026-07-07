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
  testable with no network/creds.

  `slack-scheduleport` below is one such opt-in Distributor (Slack
  `chat.postMessage`, alongside a separately-landing Resend-email one) —
  still not a replacement for mock-scheduleport, just another
  `distributor` a caller may inject into it. See README's 'Slack
  Distributor (owner setup required)' section for what the human owner
  still has to do (register a Slack app, obtain a bot token) before it is
  usable; no live Slack call is made anywhere in this repo, including its
  test suite."
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

(defn- ics-escape-text
  "RFC 5545 §3.3.11 TEXT value escaping. Every free-text field rendered into
  the ICS (SUMMARY, and any DESCRIPTION-equivalent) MUST go through this —
  otherwise a raw comma/semicolon/backslash is ambiguous against the spec's
  grammar, and worse, a raw embedded CR/LF in a TEXT value is not just
  invalid: real ICS parsers read it as the start of a brand-new property
  line, letting free text (e.g. an LLM-proposed event title) forge an
  arbitrary property — concretely, a title of
  \"Q3 Planning\\r\\nATTENDEE:mailto:attacker@evil.example\" would otherwise
  render as two distinct ICS lines, the second a fabricated ATTENDEE the
  consent governor never inspected (it only checks the structured
  :calendar/attendees vector, never rendered ICS text).

  Order matters: escape literal backslashes FIRST (so the backslashes this
  fn itself inserts below aren't re-escaped), then `,` and `;`, then
  normalize any CRLF/CR/LF down to a single logical line-break and encode it
  as the spec's literal two-character escape `\\n` (never a raw CR/LF)."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "," "\\,")
      (str/replace ";" "\\;")
      (str/replace #"\r\n|\r" "\n")
      (str/replace "\n" "\\n")))

(defn ics-string
  "A minimal RFC 5545 VCALENDAR/VEVENT string built from a calendar.model
  event EDN (:calendar/id/title/start/end/attendees). calendar.model itself
  has no export concept — this is koyomi's own, and it is the only place in
  the actor that touches ICS.

  ATTENDEE lines are built ONLY from the structured `attendees` vector — the
  same :calendar/attendees the governor's consent-violations/tenant-
  violations already censored — never by concatenating attendee data through
  the free-text path SUMMARY uses; escaping SUMMARY (via ics-escape-text)
  independently guarantees free text can never spill into a new property
  line, so the attendee count/identity here is exactly what was governed,
  never more."
  [{:calendar/keys [id title start end attendees] :as _event}]
  (str/join "\r\n"
    (concat
      ["BEGIN:VCALENDAR" "VERSION:2.0" "PRODID:-//kotoba-lang//koyomi//EN"
       "BEGIN:VEVENT"
       (str "UID:" (ics-escape-text id) "@koyomi.kotoba-lang")
       (str "SUMMARY:" (ics-escape-text title))
       (str "DTSTART:" (ics-date start))
       (str "DTEND:" (ics-date end))]
      (map #(str "ATTENDEE:mailto:" (ics-escape-text %)) attendees)
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

;; ───────────────────────── Slack (opt-in, real chat.postMessage) ─────────────────────────
;;
;; Mirrors tayori.channel.slack's already-real Slack Web API request shape
;; (`Authorization: Bearer <bot-token>`, JSON POST body) — koyomi only ever
;; needs the write side (`chat.postMessage`), not tayori's read side
;; (`conversations.history`) for reply-drafting. Untested against a live
;; workspace (no bot token exists yet — see README); the request-building
;; itself is covered by test/koyomi/scheduleport_test.clj with an injected
;; fake :http-fn, never a real network call.

#?(:clj
(defn- slack-jvm-http-fn
  "Real java.net.http POST — {:url :method :headers :body} -> {:status
  :body}, the same convention as cloudflare.client/jvm-http-fn and the
  :http-fn tayori.channel.slack expects (JVM-only default; a cljs/SCI/WASM
  host must inject its own :http-fn)."
  [{:keys [url method headers body]}]
  (let [builder (reduce-kv (fn [^java.net.http.HttpRequest$Builder b k v] (.header b k v))
                           (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                           headers)
        request (-> (case (or method :post)
                      :post (.POST builder (java.net.http.HttpRequest$BodyPublishers/ofString (or body "")))
                      :get  (.GET builder))
                    .build)
        resp    (.send (java.net.http.HttpClient/newHttpClient) request
                       (java.net.http.HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)})))

(defn- json-string-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\r\n" "\\n")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\n")
      (str/replace "\t" "\\t")))

(defn- default-json-write
  "Minimal flat {k v} -> JSON object string encoder — sufficient for
  chat.postMessage's {:channel :text} payload (both plain strings, no
  nesting), so this file adds no JSON library dependency. A caller wanting
  a richer payload (e.g. `blocks`) should inject a real :json-write (e.g.
  `clojure.data.json/write-str`) instead."
  [m]
  (str "{" (str/join "," (map (fn [[k v]] (str "\"" (name k) "\":\"" (json-string-escape v) "\"")) m)) "}"))

(defn- ics-summary-title
  "Best-effort recovery of the human-readable event title from an already-
  built ICS string's SUMMARY line, for a friendlier Slack notification text
  only — `share!`'s distributor record carries `:ics`/`:attendees`, not the
  original calendar.model content, so this is the only place a title is
  available at all. Reverses `ics-escape-text`'s escaping (never used for
  anything security-relevant — the governor already inspected the
  structured :calendar/attendees, never this rendered text). Falls back to
  nil — the caller uses the event id instead — if no SUMMARY line parses."
  [ics]
  (when-let [m (re-find #"(?m)^SUMMARY:(.*)$" (or ics ""))]
    (-> (second m)
        (str/replace "\\n" " ")
        (str/replace "\\," ",")
        (str/replace "\\;" ";")
        (str/replace "\\\\" "\\"))))

(defn slack-scheduleport
  "A Slack `chat.postMessage` Distributor for `mock-scheduleport`'s
  `distributor` slot — an opt-in alternative to the default no-op,
  alongside (not replacing) a Resend-email Distributor landing separately.
  Usage: `(mock-scheduleport (atom {}) (slack-scheduleport {:token \"xoxb-...\" :channel \"C0123...\"}))`.

  `:token` (Slack bot token) and `:channel` (target channel id) are
  owner-supplied constructor params — see README's 'Slack Distributor
  (owner setup required)' section; NEVER hardcoded or env-guessed here.

  Posts exactly one `chat.postMessage` per `share!` call: a short text
  notification (the event's title, recovered from the ICS SUMMARY line,
  + the attendee count) — never the ICS bytes themselves. The full .ics
  invite is what actually needs to reach attendees' calendars, which is a
  distinct concern from a Slack heads-up notification; wiring an .ics
  upload into Slack would need `files.upload`, a separate, more complex
  multipart endpoint, and is a deliberate follow-up (see README), not a
  half-implemented guess here.

  `:http-fn` / `:json-write` are injected for testability (default: a real
  java.net.http POST / the minimal encoder above) — no live Slack call
  happens anywhere in this repo's automated test suite (there is no bot
  token to call with yet)."
  [{:keys [token channel http-fn json-write]}]
  (let [http-fn    (or http-fn
                       #?(:clj slack-jvm-http-fn
                          :cljs (fn [_] (throw (ex-info "slack-scheduleport: no :http-fn injected and no default HTTP transport on this host (JVM default is the built-in java.net.http POST; a cljs/SCI/WASM host must inject its own :http-fn)" {})))))
        json-write (or json-write default-json-write)]
    (fn [{:keys [event-id ics attendees]}]
      (let [title (or (ics-summary-title ics) (str "event " event-id))
            n     (count attendees)
            text  (str "Event shared: \"" title "\" (" n (if (= 1 n) " attendee)" " attendees)"))]
        (http-fn {:url "https://slack.com/api/chat.postMessage"
                  :method :post
                  :headers {"Authorization" (str "Bearer " token)
                            "Content-Type" "application/json; charset=utf-8"}
                  :body (json-write {:channel channel :text text})})))))
