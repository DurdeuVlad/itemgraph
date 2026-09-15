# Architecture

## Purpose

ItemGraph reconstructs plausible movement of Minecraft items and stack quantities through inventories over time.

The architecture is designed around four requirements:

1. Preserve authoritative raw evidence.
2. Avoid assigning artificial identity to ordinary items.
3. Make inference deterministic and explainable.
4. Keep runtime impact low enough for a live server.

## Conceptual model

ItemGraph is a **directed temporal multigraph**.

### Nodes

A node represents a location or inventory capable of containing items.

Candidate node classes:

- Player inventory
- Player ender chest
- Block container
- Entity inventory
- Armor stand
- Ground/item entity
- Modded inventory
- Faction storage
- Unknown/unresolved holder

A node is not necessarily permanent. Some are long-lived, such as a chest at fixed coordinates. Others are ephemeral, such as a ground item entity.

### Observations

An observation is a direct fact from a trusted source.

Examples:

- Chest lost 1 iron chestplate.
- Player inventory gained 1 iron chestplate.
- Player dropped 3 diamonds.
- Item entity was picked up.
- Armor stand lost a helmet.
- Item was renamed at an anvil.

Observations never claim more than the source actually proves.

### Inferred edges

An inferred edge represents a plausible transfer or transformation derived from observations.

Example:

```text
Observation 184:
  Chest A: -1 named iron chestplate at 14:31:08.012

Observation 185:
  Alice: +1 identical iron chestplate at 14:31:08.047
```

Possible inference:

```text
Chest A -> Alice
confidence: 0.998
evidence: [184, 185]
```

The edge remains an inference.

## Layered architecture

```text
+------------------------------------------------------+
|                    Query / UX Layer                  |
| /ig trace | /ig event | /ig explain | /ig status    |
+-----------------------------+------------------------+
                              |
+-----------------------------v------------------------+
|              Correlation / Inference Layer           |
| candidate matching | scoring | path reconstruction   |
+-----------------------------+------------------------+
                              |
+-----------------------------v------------------------+
|                  Evidence / Graph Layer              |
| observations | nodes | inferred edges | fingerprints |
+-----------------------------+------------------------+
                              |
+-----------------------------v------------------------+
|                Evidence Ingestion Layer              |
| GriefLogger reader | NeoForge hooks | adapters       |
+-----------------------------+------------------------+
                              |
+-----------------------------v------------------------+
|                       Sources                        |
| GriefLogger DB | Minecraft events | mod integrations |
+------------------------------------------------------+
```

## Data ownership

### GriefLogger

GriefLogger is an external read-only evidence source.

ItemGraph must not:

- modify its schema
- alter rows
- depend on undocumented writable behavior
- use it as ItemGraph's own storage

### ItemGraph

ItemGraph owns:

- supplemental observations
- canonical fingerprints
- source import checkpoints
- graph nodes
- inferred edges
- explanation records
- schema migrations
- operational metrics

## Suggested persistence model

A relational database is appropriate for the MVP because the workload includes:

- indexed event queries
- time-window searches
- item fingerprint filtering
- joins by player/container
- durable append-oriented storage

SQLite is a reasonable MVP option if staging measurements show acceptable write and query performance. The architecture should not make SQLite impossible to replace.

Suggested logical tables:

```text
schema_version
source_checkpoint
observation
inventory_node
item_fingerprint
observation_item
inferred_edge
inferred_edge_evidence
```

Derived graph edges should be rebuildable where practical.

## Threading model

### Main thread

Only perform operations that require Minecraft state and are safe/fast:

- capture event parameters
- serialize minimal immutable event payloads
- enqueue work

### Worker thread(s)

Perform:

- canonicalization
- DB writes
- GriefLogger ingestion
- historical queries
- candidate search
- path reconstruction

### Rule

Never access thread-unsafe Minecraft world/player/container state from arbitrary worker threads.

## Ingestion strategy

The initial implementation should determine whether GriefLogger can be consumed through:

1. stable public API,
2. stable database schema,
3. safe export interface,
4. another supported integration surface.

Database ingestion should use incremental checkpoints rather than repeated full scans.

## Reconstruction stages

A trace request should conceptually pass through:

1. Parse query.
2. Resolve item filter.
3. Fetch candidate observations in a bounded time window.
4. Group observations by compatible item fingerprint.
5. Build temporally possible candidate transitions.
6. Enforce quantity constraints.
7. Score candidate transitions.
8. Construct one or more plausible paths.
9. Mark ambiguous/unresolved areas.
10. Return both the graph and its evidence.

## Performance constraints

Forbidden patterns:

- full-table query on main thread
- whole-world inventory scanning
- per-tick inventory snapshots
- unbounded in-memory queues
- synchronous path reconstruction during gameplay
- repeated deserialization of unchanged metadata where caching would suffice

## Future extensions

The architecture should leave room for:

- crafting transformations
- smithing
- repairs
- enchanting
- container-within-container tracking
- hopper/automation flow
- faction-aware visibility
- richer graph UI
- exportable moderation case reports
