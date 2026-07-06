# koyomi

暦 — a **schedule-sharing control plane**: a schedule-LLM ⊣
ComplianceGovernor StateGraph that drafts calendar events (title/agenda over
a mechanically-registered time/attendee slot) and shares them with
attendees, but never invites anyone itself. The actor is **propose → draft
only**: a draft commits as data (a *casual commit* — phase-gated
auto-approval is fine, it's just proposed content sitting there for review);
actually sharing an event (sending invites) is **always a human call**,
regardless of phase.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime —
the same pattern as [`kekkai`](../kekkai) (coord-LLM ⊣ TailnetGovernor) and
[`tayori`](../tayori) (reply-LLM ⊣ ComplianceGovernor). Here it is
**schedule-LLM ⊣ ComplianceGovernor**. The event content itself is
[`kotoba-lang/calendar`](../calendar)'s `calendar.model` EDN — a pure,
portable event model with ZERO ICS-export/consent/governor concept — held
verbatim; koyomi owns the ICS generation and the compliance layer on top.

> Charter: **(G1)** propose → draft only, no direct actuation — the actor
> writes proposed event content, a human turns it into an outbound invite;
> **(G2)** sharing an event is **always a human call** (high-stakes),
> independent of rollout phase; **(G3)** kotoba-native — activity/contact/
> event facts are durable EAVT ground facts, drafts are transient until
> committed; **(G4)** koyomi builds the ICS itself — `calendar.model` has no
> export concept, so `koyomi.scheduleport` owns the RFC 5545 string and
> hands it to an injected Distributor for actual invite delivery.

## The core contract

```
activity/contact/event facts
        │  ingest = durable ground facts (observe; always on)
        ▼
   ┌─────────────┐  proposal: draft /   ┌────────────────────┐
   │ schedule-LLM │  share               │ ComplianceGovernor │  (independent system)
   │ (sealed)     │ ───────────────────▶ │  no-actuation ·     │
   └─────────────┘  + cited facts        │  consent ·          │
                                         │  tenant-isolation    │
                                         └─────────┬──────────┘
                            commit ◀────────────────┼──────────▶ hold (consent-
                     (draft: casual            escalate       blocked / tenant-
                      commit, auto ok              │           mismatch / claims-
                      at phase≥2;                  ▼           already-shared;
                      share: ALWAYS            人間 承認       un-overridable)
                      here) ─────────▶     (share は phase に
                                            関わらず常に人間)
```

**The actor never shares/invites the ComplianceGovernor would reject, and
schedule-LLM never actuates directly.** HARD invariants force **hold** (a
human cannot approve past a proposal that claims to have already shared, a
share to a consent-blocked attendee, or an event whose tenant doesn't match
the activity driving it); a clean share still routes to a human. A
double-booking (an existing event overlapping the same attendee/resource,
via `calendar.model/overlaps?`) is a SOFT escalate, not a hold — concurrent
events can be entirely legitimate, but a human should still take a look.

## Run

```bash
clojure -M:dev:run     # drive: draft → share through the actor
clojure -M:dev:test    # the propose-only contract + store parity + CACAO crypto
clojure -M:lint        # clj-kondo (errors fail)
```

Demo: register an event from an activity's due-at (observe → facts) → draft
a clean board-meeting event (phase 3 → clean → auto-commits, no interrupt) →
share it (**always** human sign-off, even though clean) → draft an event
with a consent-blocked attendee → share it (**HARD HOLD**, un-overridable) →
draft an event that double-books an existing one on a shared attendee
(**SOFT escalate**, a human can still approve it) → phase-0 disables
drafting entirely → prints the schedule-sharing audit ledger → swaps to
`DatomicStore` with identical results.

## Layout

| File | Role |
|---|---|
| `src/koyomi/model.cljc` | unified `draft`/`contact` shapes (the koyomi analog of tayori's contact model, applied to calendar attendees) |
| `src/koyomi/store.cljc` | **Store** protocol — `MemStore` ‖ `DatomicStore` (`langchain.db`, swappable to Datomic Local / kotoba-server) + append-only **schedule-sharing audit ledger** |
| `src/koyomi/coordllm.cljc` | **schedule-LLM Advisor** — `mock-advisor` ‖ `llm-advisor` (`langchain.model`); draft/share proposals |
| `src/koyomi/governor.cljc` | **ComplianceGovernor** — no-actuation · consent-required · tenant-isolation · double-booking (soft) · high-stakes |
| `src/koyomi/phase.cljc` | **Phase 0→3** — ingest-only → assisted → assisted-draft → supervised (sharing always human) |
| `src/koyomi/operation.cljc` | **ScheduleActor** — langgraph StateGraph; ingest vs assess flows |
| `src/koyomi/scheduleport.cljc` | **ScheduleTarget** port (`fetch-event`/`propose-revision!`/`share!`) + koyomi-owned RFC 5545 ICS builder + `mock-scheduleport` |
| `src/koyomi/cacao.clj` | agent-side **CACAO self-mint** (JVM Ed25519 + did:key + CBOR; per-actor key) |
| `src/koyomi/kotoba.clj` | wire `DatomicStore` to a kotoba-server pod (kotobase.net XRPC) |
| `src/koyomi/query.cljc` | pure status lookups (`draft-status`/`shared?`) for callers that don't want to run the actor |
| `src/koyomi/cli.clj` | minimal JVM entrypoint for a status read against an EDN-seeded MemStore |
| `src/koyomi/sim.cljc` | demo driver |
| `test/koyomi/*_test.clj` | propose-only contract · store parity (Mem≡Datomic) · CACAO |

## ScheduleTarget → real backend (injection)

`calendar.model` has zero ICS-export or sharing concept (verified — grepping
`ics|ical|share|publish` across it is a zero hit), so `koyomi.scheduleport`
owns building the RFC 5545 string itself; a real `share!` implementation
would still call an injected **Distributor** fn (email/calendar-invite API)
for actual delivery, same injection shape as `tayori.channel`/`tayori.docport`.
`mock-scheduleport` is the runnable, deterministic default — it records what
WOULD have gone out (`{:event-id :ics :attendees}`) into an atom and hands
that same map to the injected distributor (a no-op by default).

```clojure
;; actor issues its own key, self-mints CACAO (same pattern as kekkai/tayori)
(require '[koyomi.kotoba :as k] '[koyomi.cacao :as cacao] '[clojure.data.json :as json])
(def me    (cacao/load-or-create-identity! ".koyomi/identity.edn"))
(def store (k/kotoba-store {:url "https://kotobase.net"
                            :json-write json/write-str
                            :json-read #(json/read-str % :key-fn keyword)
                            :identity me}))

;; a real schedule-LLM + a real ScheduleTarget (an actual Distributor injected)
(require '[langchain.model :as model] '[koyomi.operation :as op]
         '[koyomi.coordllm :as c] '[koyomi.scheduleport :as sp])
(op/build store
  {:advisor (c/llm-advisor (model/anthropic-model {:api-key … :http-fn … :json-write … :json-read …}))
   :scheduleport (sp/mock-scheduleport (atom {}) my-real-invite-sending-fn)})
```

An unparseable/hallucinating LLM response falls to confidence 0 / noop, and
**ComplianceGovernor always hold/escalates** it (no path from a malformed
LLM response to an actual share).

## cloud-itonami consumption

See `90-docs/adr/2607062010-kotoba-lang-koyomi-schedule-actor.md`. Add
`io.github.kotoba-lang/koyomi {:local/root "../../kotoba-lang/koyomi"}` to
`deps.edn` for in-process use. `cloud_itonami.workspace`'s projection layer
translates an `:itonami.effect/kind :calendar/schedule-event` into a koyomi
`:event/draft` request, and `:event/share`'s human approval rides on
cloud-itonami's existing approval flow (ADR-0005) — that wiring is tracked
as a separate follow-up, out of scope here.

## Status

Scaffold + runnable. Store is `:db-api` driven — `MemStore ≡
DatomicStore(langchain.db) ≡ kotoba-store(kotobase.net)` on the same
contract. CACAO self-issuance is offline-verified. `koyomi.scheduleport`'s
real Distributor binding (an actual email/calendar-invite API) is
structurally complete but **live-untested** — same known state kekkai/tayori
ship in; `mock-scheduleport` is the runnable, deterministic default.
