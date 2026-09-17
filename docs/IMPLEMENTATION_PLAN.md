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
/ig trace item <fingerprintId> [limit] [sinceMinutes]
/ig status
```

- Bounded output: `limit` defaults to 20 and is capped at 100 (`QueryLimits`), the
  `/ig explain` evidence listing is capped at 50, and both truncation and capping are
  stated in the output rather than applied quietly.
- Bounded time filter: `sinceMinutes` on `/ig trace item` (`QueryWindow`), containment for
  observations and overlap for time-spanning inferred edges.
- Readable output with a per-line OBSERVED/INFERRED provenance label and explicit
  confidence on every inferred line (`QueryFormatter`).
- Permission level 2 on the whole tree, unchanged from Phase 1.
- Queries run off the server thread on a dedicated worker and report back via
  `MinecraftServer.execute` (`QueryDispatcher`), reading through their own read-only
  connection so they never read inside the ingestion worker's open transaction.

### Deferred out of Phase 6

- Resolving a registry id or custom name to a fingerprint from the command line;
  `/ig trace item` takes a fingerprint id, surfaced in `/ig event` output.
- `/ig trace player`, `/ig trace container`, `/ig inspect`.
- `after` / `before` / `between` filters; next/previous pagination actions.
- Clickable `ClickEvent` links and metadata hover text — cross-references are plain text.
- `[AMBIGUOUS]` and `[UNRESOLVED]` markers; ambiguity currently shows as reduced
  confidence plus the candidate counts inside the stored explanation.

See `docs/QUERY_MODEL.md` for the exact argument table and
`docs/ARCHITECTURE.md` for the threading and labelling rules.

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

- Authoritative `ItemEntity` UUID tracking (`ItemEntityTracker`, `ItemEntityEventListener`, schema V8 `item_entity_uuid` column).
- Exact `ItemEntity` UUID correlation boost: continuity matching assigns 0.9990 confidence and records authoritative Minecraft entity continuity explanation.
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
- Live diagnostic telemetry in `/ig status` (internal queue size/throughput, transformation totals, active tracked entities, continuity matches).
- 106 automated tests passing with 0 failures.
- Zero-tamper verification against GriefLogger database (SHA-256 unchanged).
- Full restart persistence verified on live dedicated NeoForge server.

Acceptance:

- `/ig audit` reports `HEALTHY (ALL INVARIANTS SATISFIED)`.
- Live staging server tests passing end-to-end.
- Zero GriefLogger modification across all operational cycles.

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

