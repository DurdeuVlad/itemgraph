# Implementation Plan

## Phase 0 — Reconnaissance

Do not begin significant implementation before this phase is complete.

Collect:

- staging environment details
- repository layout
- Git state
- Java version
- NeoForge version
- GriefLogger version
- GriefLogger DB engine and schema
- faction/team mod
- relevant inventory/container mods
- armor stand behavior
- coffer behavior
- sample source events

Deliver a short report with:

1. Environment findings
2. GriefLogger coverage matrix
3. Logging gaps
4. Risks/unknowns
5. Recommended integration strategy

## Phase 1 — Project skeleton

Create:

- NeoForge 1.21.1 project
- `itemgraph` mod ID
- config system
- logging
- command root
- database abstraction
- schema migration framework
- basic `/ig status`

No complex inference yet.

## Phase 2 — Evidence ingestion

Implement:

- read-only GriefLogger adapter
- incremental checkpointing
- source deduplication
- canonical observation model
- minimal supplemental hooks only for verified gaps

Acceptance:

- selected source events appear in ItemGraph's own observation store
- GriefLogger remains unchanged

## Phase 3 — Item canonicalization

Implement:

- item registry ID extraction
- 1.21.1 data-component canonicalization
- deterministic metadata hash
- custom name extraction
- relevant damage/enchantment/trim fields

Document exactly what enters the fingerprint.

Acceptance:

- same semantic item produces same fingerprint
- changed relevant metadata produces expected new state

## Phase 4 — Graph nodes

Implement stable inventory-node identities for:

- players
- block containers
- item entities/ground where possible
- armor stands
- unknown holder

Do not over-generalize modded inventories before real use cases are tested.

## Phase 5 — Basic correlation

Support a narrow set first:

- container remove -> player gain
- player drop -> item entity
- item entity -> player pickup
- player remove -> container add

Use deterministic, explainable scoring.

Acceptance:

- controlled named item path reconstructed correctly

## Phase 6 — Query commands

Implement:

```text
/ig trace item ...
/ig event ...
/ig explain ...
/ig status
```

Add:

- pagination
- bounded time filters
- readable output
- permission checks

### Delivered

```text
/ig event   <observationId>
/ig explain <edgeId>
/ig trace item <query> [limit] [sinceMinutes]
/ig trace player <playerName> [limit] [sinceMinutes]
/ig trace container <x> <y> <z> [limit] [sinceMinutes]
/ig gui item <query> [sinceMinutes]
/ig gui player <playerName> [sinceMinutes]
/ig gui container <dimension> <x> <y> <z> [sinceMinutes]
/ig inspect [on|off|status]
/ig status
```

- `trace item` resolves numeric fingerprint IDs, registry IDs, and custom names; ambiguous
  string queries list candidates rather than choosing a fingerprint silently.
- `trace player` and `trace container` use the same database-backed target lookup as the
  corresponding GUI views. The GUI container target requires an explicit dimension.
- `inspect` stores per-player UUID state only, clears on logout/server stop, and requires
  permission level 2 for command and click. `InspectionListener` cancels a supported
  `RightClickBlock` container interaction at `HIGHEST` priority with `SUCCESS`, opens the
  read-only flow browser, and does not record the click as a transfer.
- Chat traces are row-capped (`limit` default 20, hard cap 100); `/ig explain` evidence is
  capped at 50. `sinceMinutes` is inclusive and uses interval overlap for session observations
  and inferred edges.
- The six-row vanilla flow browser shows up to 45 entries per page, labels observed versus
  inferred movement and confidence, and opens raw event, transformation, or stored
  edge-explanation details.
  Composite keyset pagination includes timestamp, provenance kind, row ID, and source kind.
  All item-movement click paths are rejected; page/detail controls are navigation-only.
- Permission level 2 is required for the command tree, menu validity, and every menu action.
- SQL uses the read-only `QueryDispatcher` worker; page/detail results return to the server
  thread before Minecraft menu state is created or changed. The worker queue accepts at most
  64 waiting queries and rejects overflow rather than accumulating unbounded work.
- `./gradlew clean build` passes 270 tests, 0 failures, 0 skipped (2026-09-25).
- A GriefLogger-present Mineflayer protocol-client GUI staging pass completed 12/12 checks,
  and graphical MC Pilot staging passed with GriefLogger both present and temporarily absent;
  see `docs/TEST_PLAN.md`. The MC Pilot runs used a real non-headless NeoForge 1.21.1 client,
  verified all three `/ig gui` entry points, ambiguity, pagination, observation/
  transformation/inference details, unresolved state, permission revocation, and read-only
  mutation rejection.
- A separate issue-#10 MC Pilot pass verified `/ig inspect` against chest, hopper, furnace,
  and crafting-table controls, including permission loss, reconnect/server-stop cleanup,
  non-empty-container preservation, and no new ItemGraph observations from browser opens.

### Deferred out of Phase 6

- `after` / `before` / `between` filters and click-event links from chat output.
- Dedicated `[AMBIGUOUS]` and `[UNRESOLVED]` line prefixes; source-group ambiguity and UNKNOWN
  endpoints are preserved in the current evidence/details instead.

See `docs/QUERY_MODEL.md` for argument, GUI, and pagination contracts and
`docs/ARCHITECTURE.md` for threading and evidence-boundary rules.

## Phase 7 — Quantity flow

Add stack-aware conservation.

Support:

- split
- merge
- partial transfers

### Delivered

- **Quantity-Allocation Ledger (`ig_edge_allocations`)**:
  Schema migration `V7__QuantityFlowLedger` adding `ig_edge_allocations` table with composite primary key `(edge_id, observation_id, allocation_role)` and indexes, plus backfilling historical Phase 5/6 edges.
- **Explicit Observation Lifecycle (`ig_observations.correlation_status`)**:
  Replaces overloaded binary interpretation of `correlated_at` with explicit states: `PENDING`, `PARTIALLY_ALLOCATED`, `FULLY_ALLOCATED`, and `CLOSED_UNRESOLVED`.
- **Residual Capacity Accounting**:
  Dynamic calculation of remaining capacity ($R_{obs} = Q_{obs} - \sum \text{allocated}$), enabling stack splits (1-to-many), stack merges (many-to-1), partial transfers, and multi-fragment flows with zero manufactured quantity.
- **Explainable Quantity Narratives**:
  `CorrelationEngine` generates detailed explanations recording flow classification (`stack split`, `stack merge`, `exact transfer`), allocated units, residuals before/after, candidate counts, and proximity/ambiguity factor arithmetic.
- **Single-Transaction Atomicity**:
  Inferred edges, evidence links, allocation records, and observation status updates commit in a single atomic transaction.
- **Automated Verification**:
  16 new comprehensive unit tests in `QuantityFlowTest` covering splits, merges, partials, window expiration, over-capacity protections, competing candidate penalties, idempotency, restart simulation, and transaction rollback. All 93 test cases passing.

Acceptance:

- ordinary iron/diamond stack movement reconstructed without item UUIDs while strictly conserving quantity.
- stack splitting, stack merging, and partial transfer scenarios pass with 100% invariant conservation.

## Phase 8 — Missing high-value integrations [COMPLETED]

Implemented:

- Authoritative `ItemEntity` UUID tracking (`ItemEntityTracker`, `ItemEntityEventListener`, schema V8 `item_entity_uuid` column), with drops recorded only after `ItemEntity.isAddedToLevel()` confirms world insertion.
- Unique `ItemEntity` UUID correlation boost: continuity matching assigns 0.9990 confidence only when one spatial/time candidate matches and records the entity-continuity explanation.
- Armor stand equip/unequip supplemental tracking (`ArmorStandEventListener`).
- User experience query extensions:
  - `/ig trace player <playerName>`
  - `/ig trace container <x> <y> <z>`
  - `/ig trace item <query>` (accepts numeric IDs, registry names, and custom names).

Acceptance:

- Item entity continuity verified on live staging server with 0.9990 confidence.
- Armor stand equip/unequip events observed and traceable.
- Player and container timelines reconstructed cleanly.

## Phase 9 — Transformations [COMPLETED]

Implemented:

- Transformation ledger table `ig_item_transformations` (schema V8).
- Supplemental event listener `TransformationEventListener` capturing:
  - Anvil repairs and item renames (`AnvilRepairEvent`).
  - Crafting operations (`ItemCraftedEvent`).
  - Smelting operations (`ItemSmeltedEvent`).
- Asynchronous batch persistence via `InternalObservationService`.
- Trace query lineage integration: `trace item` displays chronological transformation hops (`[TRANSFORMATION <type> <- <source>]`).

Acceptance:

- Anvil item renaming observed, persisted, and surfaced chronologically in live item trace timelines.
- Crafting and smelting operations link input and output fingerprints cleanly.

## Phase 10 — Hardening & Auditing [COMPLETED]

Implemented:

- Offline/off-thread database invariant auditor (`AuditService`).
  - Conservation invariant ($\sum \text{allocated} \le \text{amount}$).
  - Strict quantity positivity ($amount > 0$).
  - Relational graph integrity (no orphaned allocations or broken node endpoints).
  - Lifecycle state consistency (`correlation_status` vs allocations).
- Administrative audit command `/ig audit`.
- Live diagnostic telemetry in `/ig status` (internal queue throughput/drops, capability queue rejections, transformation totals, active tracked entities, continuity matches).
- 234 automated tests pass with 0 failures on the current M5 repair branch (`./gradlew clean build`, 2026-09-24).
- Zero-tamper verification against GriefLogger database (SHA-256 unchanged).
- Full restart persistence verified on live dedicated NeoForge server.

Acceptance:

- `/ig audit` reports `HEALTHY (ALL INVARIANTS SATISFIED)`.
- Live staging server tests passing end-to-end.
- Zero GriefLogger modification across all operational cycles.

## M5 evidence-integrity repair (staging and review pending)

Implemented in the current M5 repair branch:

- V11 destination-sensitive deduplication, `timestamp_end_ms`, cross-source groups/checks,
  and retained `edge_state` for superseded inferences.
- Unique shared ItemEntity UUID matching consolidates confirmed ItemGraph/GriefLogger
  copies; plausible pairs without strong identity remain `SOURCE_AMBIGUOUS` with no capacity.
- Drop ground rows require a confirmed added ItemEntity; canceled attempts never become
  ground movement. Spatial UUID enrichment is unique-only.
- Generic capability rows use UNKNOWN caller/cause labels; queue rejection is excluded from
  player attribution and retried once at session close.
- Container changes are interval-bounded net deltas, not click-time records; zero-net
  out-and-back activity is documented as unobserved.
- Internal persistence, GriefLogger ingestion, and correlation transactions serialize on
  the shared ItemGraph connection. `/ig ingest now` and database-backed `/ig status` are
  asynchronous.

Remaining delivery gates are controlled live movement/queue/cancellation scenarios, independent
adversarial review of the final diff, PR #6 CI/review, and authoritative merge confirmation.
V11 startup migration succeeded on loopback, but player scenarios were not run because the
available safe tools cannot disable the GriefLogger mod without moving its jar. Production and
the existing GriefLogger database remain out of scope for writes.

## M7 — Preview integration API

Issue #9 defined the maintainer-approved public contract in `docs/API.md`; issue #12
implements it as `com.itemgraph.api` `PREVIEW_1`, shipped in the main ItemGraph JAR and
consumed by trusted NeoForge mods through local-JAR `compileOnly` plus a runtime
`itemgraph` mod dependency.

Implemented implementation surface:

- immutable source registration and direct-observation DTOs;
- `(source modId, sourceEventId)` deduplication using the existing non-null source-event
  uniqueness model;
- durable `EXTERNAL_INVENTORY` nodes keyed by `(ownerModId, inventoryId)`, including a
  coordinate-less identity distinct from `UNKNOWN`;
- bounded asynchronous submission and query results with explicit `PERSISTED`,
  `DUPLICATE`, `INVALID_INPUT`, `QUEUE_FULL`, `DATABASE_UNAVAILABLE`, `SHUTDOWN`, and
  `FAILED` outcomes;
- flow DTOs preserving observed/inferred provenance, evidence refs, confidence, stored
  explanations, ambiguity, limits, and truncation;
- an ItemGraph-owned V12 migration for `ig_api_sources` and `ig_nodes.external_key`;
- a separate NeoForge 1.21.1 consumer fixture compiled against the built main JAR.

Staging completed both required modes on dedicated loopback `127.0.0.1:26417`: V12 applied
with GriefLogger present (`PERSISTED / query=AMBIGUOUS`) and ItemGraph ran standalone
with GriefLogger absent (`DUPLICATE / query=AMBIGUOUS`). The `AMBIGUOUS` query result is
expected staging evidence because `minecraft:diamond` has multiple fingerprint
candidates; `run/database.db` remained hash-identical and the GriefLogger JAR was
restored. Remaining issue-#12 delivery gates are final review and PR/CI/merge.

Non-goals for M7 remain JDBC/schema exposure, GriefLogger API access, caller-provided
inference, player-facing authorization, a standalone API artifact, and a stable pre-1.0
API promise.

## Git strategy

Prefer small commits such as:

```text
chore: scaffold itemgraph neoforge project
feat: add observation persistence model
feat: add read-only grief logger adapter
feat: add item fingerprint canonicalization
feat: add basic transfer correlation
feat: add ig trace and event commands
feat: add explainable inferred edges
test: add stack split and merge scenarios
feat: add entity uuid tracking and armor stand listener
feat: add transformation tracking for anvil and crafting
feat: add audit service and ux trace commands
```

Avoid giant mixed commits.

## Definition of MVP complete

MVP is complete when:

- staging demonstrates named-item tracing
- ordinary quantity flow works
- evidence and inference are separately visible
- `/ig explain` is useful
- persistence survives restart
- GriefLogger remains untouched
- performance is acceptable on staging
- Phases 1 through 10 fully implemented and verified

