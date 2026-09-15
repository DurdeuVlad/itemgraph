# Changelog

All notable changes to ItemGraph will be documented here.

The project follows a simple pre-1.0 development changelog model.

## [Unreleased]

### Added

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
