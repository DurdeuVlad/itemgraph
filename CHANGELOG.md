# Changelog

All notable changes to ItemGraph will be documented here.

The project follows a simple pre-1.0 development changelog model.

## [Unreleased]

### Added

- **Complete command help/reference**: bare `/itemgraph` or `/ig` and `/ig help [topic]` document every live command with syntax, defaults, permission, asynchronous behavior, evidence semantics, and examples. Player, item-ID, dimension, literal, and topic suggestions are registered, and dispatcher tests cross-check help coverage against the live command tree.
- **Read-only vanilla flow browser**: permission-level-2 `/ig gui item`, `/ig gui player`, and dimension-qualified `/ig gui container` commands open a vanilla six-row chest menu with 45-entry keyset-paginated timelines, provenance/confidence labels, and event/transformation/edge detail views. Menu actions cannot move items.
- **Command-toggled container inspector**: `/ig inspect [on|off|status]` stores per-player server-side mode. While enabled, a supported container right-click opens the read-only flow browser for that exact dimension/position without opening the normal container GUI, consuming the held item, or recording a transfer. The mode clears on logout and server stop.

## [0.2.0] — 2026-09-20

### Summary

ItemGraph 0.2.0 is independent from GriefLogger. It starts and records supported native
NeoForge event/capability evidence without GriefLogger installed. When GriefLogger is present,
its read-only rows remain additive evidence; confirmed cross-source copies share one quantity
capacity, while uncertain pairs remain ambiguous. The hard boot dependency on GriefLogger is
removed.

### Added

- **Native drop and pickup observation** (`ItemEntityEventListener` promoted): successful
  ItemToss/LivingDrops entities write `DROP_ITEM`/`DEATH_DROP` only after
  `ItemEntity.isAddedToLevel()` confirms world insertion; `ItemEntityPickupEvent.Post` writes
  `PICKUP_ITEM`. Canceled tosses record `DROP_CANCELLED` to UNKNOWN, and canceled death drops
  are no-destination `DEATH_DROP_CANCELLED` events; neither is ground movement.
- **Partial-pickup pairing**: `ItemEntityPickupEvent.Pre` records the entity's stack
  count before `Inventory.add()` and a per-tick sweep emits the absorbed delta for
  pickups where Post never fires (NeoForge 21.1.248 gates Post on `add()` returning
  true, which is false for partial absorbs). Post consumes the pending entry so full
  pickups are never double-counted; paired pickups are tagged
  `{"detection":"pre_post_pairing"}` in `raw_data`.
- **Container capability wrapper** (Issue 4): `ContainerCapabilityWrapper` wraps the
  `IItemHandler` capability on the vanilla block/block-entity types NeoForge serves —
  sided-container face rules, double-chest views, hopper cooldowns, and composter
  re-evaluation remain delegated. Registration runs at `EventPriority.HIGHEST` because
  `BlockCapability.getCapability` returns the first non-null provider. Real calls emit
  `CAPABILITY_INSERT`/`CAPABILITY_EXTRACT`; `IItemHandler` does not identify its caller or
  cause, so both player/cause identity and the remote endpoint remain UNKNOWN.
- **Player container session deltas** (Issue 3): player GUI clicks mutate
  `Container` directly and never traverse `IItemHandler`, so player-driven changes are
  measured as fingerprint-level net deltas across `PlayerContainerEvent.Open`/`Close`.
  Rows carry `timestamp_ms`/`timestamp_end_ms` and are not click-time evidence. A zero-net
  withdraw-and-return is not represented and does not prove no interaction occurred.
  Capability deltas are subtracted; multi-viewer sessions produce one `[ambiguous]` row
  with candidates preserved in `raw_data`.
- **`ContainerInteractionTracker`**: Session-watch tracker holding per-container
  baseline totals, open sessions, participants, signed capability deltas, rejected-row
  recovery amounts, and double-chest position aliases.
- **`ContainerCapabilityRegistrar`**: Registers the capability wrapper providers
  on the mod event bus (`RegisterCapabilitiesEvent`, `EventPriority.HIGHEST`).
- **Schema migrations V9+V10**: V9 adds a partial unique index on
  `ig_observations(source_type, timestamp_ms, node_id, fingerprint_id, amount,
  action_type) WHERE source_event_id IS NULL` plus `idx_obs_action_type`. V10
  extends the dedup key with `item_entity_uuid` so re-submitted entity events still
  deduplicate while distinct same-millisecond events (two identical death-drop
  stacks, pile pickups, repeated machine pushes) are no longer collapsed.
- **Schema migration V11**: adds `timestamp_end_ms`, source-group/member and match-check
  tables, and `edge_state`; rebuilds the internal dedup index with `target_node_id` so
  same-time pickups for different recipients survive.
- **Cross-source quantity conservation**: a unique shared ItemEntity UUID and matching event
  details corroborate GriefLogger and ItemGraph rows without duplicating capacity. Uncertain
  pairs remain ambiguous; legacy edges using aliases are superseded without deleting records.
- **Shared-writer serialization**: internal observations, GriefLogger ingestion, and
  correlation transactions serialize on the shared ItemGraph JDBC connection.
- **`DEATH_DROP` action type**: Added to `CorrelationEngine.DROP_ACTIONS` so death drop
  observations participate correctly in ground-bridge correlation.
- **GriefLogger startup log**: ItemGraph now logs `GriefLogger integration: ENABLED` or
  `DISABLED` with reason at `ServerStartingEvent`.

### Changed

- **`neoforge.mods.toml`**: GriefLogger dependency demoted from `type="required"` to
  `type="optional"`. ItemGraph now starts without GriefLogger.
- **`build.gradle`**: `sqlite-jdbc` switched from plain `implementation` to
  `jarJar(implementation(...))` with version range `[3.40.0.0,4.0.0.0)` and preferred
  version `3.46.1.0`. ItemGraph bundles its own SQLite driver. NeoForge JarJar negotiation
  deduplicates with GriefLogger's bundled copy when both are present, eliminating the
  confirmed JPMS split-package crash.
- **`IngestionService`**: GL database unavailable no longer emits a WARN every 60 seconds.
  Logs once at INFO level on first skip; subsequent skips are silent until GL becomes
  available again.
- **`/ig status`**: GriefLogger source reports `ENABLED (database reachable)`,
  `DISABLED (not installed)`, or `DISABLED (mod present but database not found)`; database
  counts/checkpoints and active/superseded edge counts are queried off-thread, alongside
  queue loss and capability queue-rejection counts.
- **`/ig ingest now`**: queues one bounded ingest-and-correlate cycle on the background
  worker instead of doing source reads and candidate search on the server thread.
- **`InternalObservationService.persistBatch`**: Resolves `GROUND`, `CONTAINER`, `PLAYER`,
  `UNKNOWN`, and `ARMOR_STAND` endpoints. `INSERT OR IGNORE` uses the V11 destination-sensitive
  partial index so same-time events for different recipients are not conflated.
- **Version**: `0.1.0` → `0.2.0`.

### Fixed

- JPMS split-package crash when GriefLogger and ItemGraph are both installed (sqlite-jdbc
  conflict: `Modules grieflogger and org.xerial.sqlitejdbc export package org.sqlite`).
- NULL `source_event_id` dedup gap: internal observations could not be deduplicated by the
  existing `(source_type, source_event_id)` unique index because SQLite treats `NULL != NULL`.
- Partial item pickups were silently unobserved: `Inventory.addItem` returns false when
  only part of the stack fit, so `ItemEntityPickupEvent.Post` never fired and no
  `PICKUP_ITEM` row (or vanilla pickup stat) was produced. Resolved via the
  Pre/stack-delta pairing described above (verified live: 1-of-10 partial pickup now
  records exactly 1).
- Dev-run classpath gap: jarJar strips sqlite-jdbc from dev run classpaths, so
  `Class.forName("org.sqlite.JDBC")` only resolved when another installed mod embedded
  it — a standalone dev boot failed DB init with `ClassNotFoundException`. The driver
  is now added to `additionalRuntimeClasspath` only when no mod in `run/mods` already
  embeds sqlite (detected via `META-INF/jarjar|jars/sqlite-jdbc-*.jar` entries; override
  with `-Pitemgraph.devSqliteProvided=`); adding it unconditionally alongside such a
  mod crashes module resolution with a duplicate `org.xerial.sqlitejdbc` module.
  V9 adds a partial unique index covering internal row identity.
- `PICKUP_ITEM` recorded the pre-pickup stack count even when only part of the stack
  moved; it now records `originalStack - currentStack`.
- `ItemTossEvent`/`ItemEntityPickupEvent` handlers now guard `isClientSide` so
  client-side event posts cannot enqueue duplicate observations.
- `CorrelationEngine.findCompetingDrops` now counts `DEATH_DROP` rows when scoring
  drop-side ambiguity (matching `DROP_ACTIONS`).

### Earlier unreleased work

- Phase 10: Production Hardening, Integrity Auditing, and Comprehensive Diagnostics.
  - Off-thread `AuditService` checking core architectural invariants:
    - Quantity conservation ($\sum \text{allocated} \le \text{evidenced capacity}$) across all observations and inferred edges.
    - Strict positivity for quantities on observations, edges, and allocations.
    - Relational integrity: zero orphaned allocations and zero missing edge endpoint nodes.
    - Lifecycle status consistency: validates observation `correlation_status` against active allocations.
  - New administrative command `/ig audit`: dispatches audit analysis asynchronously and reports live invariant status.
  - Diagnostic metrics in `/ig status`: internal queue capacity/throughput, total persisted transformations, active entity tracking counts, and continuity matches.
  - Automated test suite `AuditServiceTest` verifying healthy graphs, conservation violation detection, orphaned allocation detection, and topology validation.
  - Reached 106 automated tests with 100% pass rate.

- Phase 9: Item Transformation Tracking (Renaming, Crafting, Smelting).
  - Schema table `ig_item_transformations`: records item transitions linking source fingerprint to result fingerprint with actor node, transformation type, quantity, timestamp, and details.
  - `TransformationEventListener`: captures NeoForge `AnvilRepairEvent` (renames, repairs), `ItemCraftedEvent` (crafting), and `ItemSmeltedEvent` (smelting).
  - Asynchronous batch persistence via `InternalObservationService` with a memory-bounded queue (10,000 capacity).
  - Chronological transformation surfacing in `TraceQueryService`: item traces now include `[TRANSFORMATION <type> <- <source>]` hops connecting item lineages across identity shifts.

- Phase 8: High-Value Integrations and UX Enhancements.
  - Authoritative `ItemEntity` UUID tracking:
    - Schema migration `V8__HighValueIntegrations` adding `item_entity_uuid` column to `ig_observations`.
    - Memory-bounded, thread-safe `ItemEntityTracker` tracking ground item drops and pickups with coordinate matching and temporal expiration.
    - `ItemEntityEventListener` subscribed to `ItemTossEvent` and `ItemEntityPickupEvent.Post`.
    - Enhanced `CorrelationEngine`: exact `item_entity_uuid` continuity matching yields `0.9990` confidence with narrative explanation citing authoritative Minecraft entity continuity.
  - Armor stand interactions:
    - `ArmorStandEventListener` capturing `PlayerInteractEvent.EntityInteractSpecific` to record `EQUIP_ARMOR_STAND` and `UNEQUIP_ARMOR_STAND` observations.
  - Player and container trace queries:
    - `/ig trace player <playerName> [limit] [sinceMinutes]`
    - `/ig trace container <x> <y> <z> [limit] [sinceMinutes]`
  - Dynamic string and registry query resolution for `/ig trace item <query>` supporting numeric IDs, registry IDs (e.g. `diamond_sword`), and custom names.

- Phase 7: Stack-aware quantity-flow reconstruction and allocation ledger.
  - Migration `V7__QuantityFlowLedger` adding `ig_edge_allocations` table (`edge_id`, `observation_id`, `allocation_role`, `amount`) and explicit observation lifecycle states in `ig_observations.correlation_status` (`PENDING`, `PARTIALLY_ALLOCATED`, `FULLY_ALLOCATED`, `CLOSED_UNRESOLVED`).
  - Stack splitting: supports 1-to-many flows (e.g. drop 64 -> pickup 20 + pickup 44) without per-item UUIDs.
  - Stack merging: supports many-to-1 flows (e.g. drop 20 + drop 30 -> pickup 50).
  - Partial transfers: strict quantity conservation ($\sum \text{allocated} \le \text{evidenced capacity}$) with unrecovered units preserved as residual capacity.
  - Dynamic capacity accounting and window closure semantics: observations with unallocated residual quantity transition to `CLOSED_UNRESOLVED` once the candidate window expires.
  - Atomic single-transaction persistence for inferred edges, evidence citations, allocations, and observation status updates.
  - Enhanced explanation narratives documenting flow classification (`stack split`, `stack merge`, `exact transfer`), allocated units, residuals before/after, candidate counts, and factor breakdown.
  - Automated test suite: `QuantityFlowTest` covering all 16 stack-aware flow scenarios (93 total project tests, 100% passing).
- Phase 6: the three forensic query commands, all gated at permission level 2 like the
  rest of the `/itemgraph` (alias `/ig`) tree.

  ```text
  /ig event   <observationId>
  /ig explain <edgeId>
  /ig trace item <fingerprintId> [limit] [sinceMinutes]
  ```

  `/ig event` prints one raw `ig_observations` row with both endpoints and the item
  fingerprint resolved. `/ig explain` prints one `ig_inferred_edges` row, its stored
  confidence, the scoring narrative written at inference time, and every observation
  cited through `ig_edge_evidence` — the literal implementation of the charter's
  "why does ItemGraph think this transfer happened?" requirement. `/ig trace item`
  merges `ig_observations` and `ig_inferred_edges` into one chronological timeline for
  a single fingerprint.
- OBSERVED/INFERRED labelling convention in all query output. Every line that asserts a
  movement is prefixed with its provenance — `[OBSERVED]` for a single raw evidence row,
  `[INFERRED conf=0.9025]` for a reconstruction, with the confidence printed on every
  inferred line to four decimals. There is no unlabelled movement line anywhere in the
  output. See "Query output: the labelling convention" in `docs/ARCHITECTURE.md`.
- Bounded results: `QueryLimits` caps any requested limit at 100 rows (default 20) and
  caps a single `/ig explain` evidence listing at 50; `QueryWindow` bounds a trace by
  relative minutes. Each side of a trace is queried with `LIMIT applied + 1`, so
  "there is more" is a reported fact rather than silence, and both the cap and the
  truncation are stated in the output instead of being applied quietly.
- Query commands run off the server thread. `QueryDispatcher` runs the SQL and the
  formatting on a dedicated single-threaded `ItemGraph-Query-Worker` — deliberately not
  the ingestion worker, so an incident lookup never queues behind a 60-second
  ingest-then-correlate cycle — and hands the finished lines back with
  `source.getServer().execute(Runnable)` for `sendSuccess`/`sendFailure`. See
  "Query execution: off-thread, reported back on-thread" in `docs/ARCHITECTURE.md`.
- `DatabaseManager.openReadOnlyConnection()`: each query gets its own short-lived
  connection with `PRAGMA query_only = ON`. Reading through the shared writer connection
  would execute inside the ingestion worker's open transaction and could show an admin
  rows that are about to be rolled back. WAL mode (already enabled) makes an independent
  reader both consistent and non-blocking.
- Phase 5: `CorrelationEngine`, which bridges a player's drop to a later player's pickup
  across the ephemeral `GROUND` node and writes an `ig_inferred_edges` row with its two
  supporting observations. This is the only Phase 5 pattern that is genuinely an inference
  — see "Correlation: ground bridging" in `docs/ARCHITECTURE.md` for why the other three
  patterns in `docs/IMPLEMENTATION_PLAN.md` are already single fully-evidenced observations
  after the direction fixes below.
- Deterministic, fully explainable confidence scoring:
  `confidence = 0.95 x proximity(dt) x ambiguity(competing pickups) x ambiguity(competing drops)`,
  where `proximity(dt) = 1 - 0.25 x clamp(dt/window, 0, 1)` and
  `ambiguity(n, gap) = 1/n + (1 - 1/n) x min(0.9, clamp(gap/window, 0, 1))` for `n > 1`, else `1`.
  No model, no tuning, no randomness. Base is 0.95 rather than 1.0 because without the
  `ItemEntity` UUID a ground bridge is always circumstantial evidence.
- Every inferred edge stores an `explanation` naming both players, both observation IDs, the
  block, both timestamps, the candidate counts on each side, and the arithmetic that produced
  the confidence.
- Migration V6 adds `ig_observations.correlated_at` plus correlation lookup indexes. Passes are
  driven by `correlated_at IS NULL` and bounded to 500 observations, so the table is never fully
  scanned. Drops whose window is still open are deliberately deferred rather than written off,
  because the pickup that explains them may not have been ingested yet.
- Correlation is wired into the existing ingestion worker: it runs on the same scheduled
  executor immediately after each ingestion cycle, never on the server thread and never
  concurrently with ingestion.
- `correlation.ground_bridge_max_seconds` config option (default 300s, the vanilla item-entity
  despawn time).
- `/ig status` now reports the correlation window and last-pass diagnostics; `/ig ingest now`
  queues a correlation pass onto the ingestion worker.
- Phase 4: `NodeType` enum (`PLAYER`, `CONTAINER`, `GROUND`, `ARMOR_STAND`, `UNKNOWN`) and
  `NodeManager`, which owns stable get-or-create identity resolution for every node type.
- `GROUND` nodes keyed by dimension + block coordinate, so a drop and the pickup that
  recovers the same stack share a node.
- `UNKNOWN` sentinel node (one per dimension, no coordinates) for endpoints that genuinely
  cannot be determined from GriefLogger evidence.
- `ARMOR_STAND` node identities, resolvable and tested but not yet wired to any event source
  (GriefLogger has no armor stand coverage; deferred to Phase 8).
- Initial project charter and documentation.

### Fixed

- Container-table ingestion wrote *every* row as `container -> player` regardless of the
  action, which is only correct for a withdrawal. Deposits (`ADD_ITEM`, `ADD_ITEM_ENDER`) flow
  `player -> container`, so every deposit already recorded pointed backwards and was
  topologically indistinguishable from a withdrawal — including the MVP chain's final hop
  (`Player B -> Chest B`). `resolveContainerEndpoints` now derives a real (origin, destination)
  pair per action, and migration V5 clears observations and checkpoints so every containers row
  is re-read through the corrected mapping. Existing fingerprints and node identities are
  preserved, and GriefLogger's database is untouched as always.

### Changed

- Item-table ingestion now records a real flow direction per action
  (`node_id` = origin, `target_node_id` = destination) instead of anchoring every row to the
  acting player with no target. See "Observation direction convention" in `docs/ARCHITECTURE.md`.
- Migration V4 clears derived observations and ingestion checkpoints so existing
  direction-less rows are re-ingested through the corrected logic. Item fingerprints and
  existing player/container node identities are preserved.
- Defined ItemGraph as a directed temporal item-flow graph.
- Established separation between raw observations and inferred movement.
- Established GriefLogger as a read-only evidence source.
- Defined initial security, privacy, testing, and performance requirements.
- Defined MVP around named-item and quantity-flow reconstruction.
