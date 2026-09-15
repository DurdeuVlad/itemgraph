# Changelog

All notable changes to ItemGraph will be documented here.

The project follows a simple pre-1.0 development changelog model.

## [Unreleased]

### Added

- Phase 4: `NodeType` enum (`PLAYER`, `CONTAINER`, `GROUND`, `ARMOR_STAND`, `UNKNOWN`) and
  `NodeManager`, which owns stable get-or-create identity resolution for every node type.
- `GROUND` nodes keyed by dimension + block coordinate, so a drop and the pickup that
  recovers the same stack share a node.
- `UNKNOWN` sentinel node (one per dimension, no coordinates) for endpoints that genuinely
  cannot be determined from GriefLogger evidence.
- `ARMOR_STAND` node identities, resolvable and tested but not yet wired to any event source
  (GriefLogger has no armor stand coverage; deferred to Phase 8).
- Initial project charter and documentation.

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
