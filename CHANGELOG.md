# Changelog

All notable changes to ItemGraph will be documented here.

The project follows a simple pre-1.0 development changelog model.

## [Unreleased]

### Added

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
