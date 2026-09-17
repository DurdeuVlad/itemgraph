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

### Observation direction convention

Implemented in Phase 4. Each stored observation is a directed flow of one fingerprint at one timestamp:

```text
node_id  =  ORIGIN       (where the item came FROM)
target_node_id = DESTINATION  (where the item went TO)
```

This is ItemGraph's modeling decision, not something GriefLogger states. GriefLogger's `items` table is player-centric — every row records an action a player performed, naming only the player. Recording that literally (origin = player, destination = unset, for every action) loses direction entirely: a drop and the pickup that recovers the same stack would share no node, leaving correlation nothing to join on.

The implemented node types are `PLAYER`, `CONTAINER`, `GROUND`, `ARMOR_STAND`, and `UNKNOWN` (`com.itemgraph.graph.NodeType`), resolved to stable identities by `com.itemgraph.graph.NodeManager`:

| Source | Action | Origin | Destination |
| --- | --- | --- | --- |
| items | `DROP_ITEM`, `THROW_ITEM`, `SHOOT_ITEM` | player | ground |
| items | `PICKUP_ITEM` | ground | player |
| items | `ADD_ITEM`, `ADD_ITEM_ENDER`, `CRAFT_ITEM` | unknown | player |
| items | `REMOVE_ITEM`, `REMOVE_ITEM_ENDER`, `BREAK_ITEM`, `CONSUME_ITEM` | player | unknown |
| items | unrecognized action id | player | *(none — no direction claimed)* |
| containers | `REMOVE_ITEM`, `REMOVE_ITEM_ENDER` | container | interacting player |
| containers | `ADD_ITEM`, `ADD_ITEM_ENDER` | interacting player | container |
| containers | unrecognized action id | container | interacting player |

The containers-table split was corrected in Phase 5. Phases 2–4 wrote *every* containers row as container → player regardless of the action, which is only correct for a withdrawal. A deposit flows the other way, so every deposit in the database pointed backwards and was topologically indistinguishable from a withdrawal — including the final hop of the MVP chain (`Player B -> Chest B`), which is precisely the hop an admin asking *"where did my item end up"* cares about. Migration V5 clears observations and checkpoints so every containers row is re-read through the corrected mapping; the direction is recomputed from the source action rather than patched in place.

`GROUND` nodes are keyed by dimension plus the block coordinate the logged position floors into, so a drop and a later pickup at the same block resolve to the same node. That shared node is what makes the MVP chain traversable:

```text
Chest A -> Player A -> Ground -> Player B -> Chest B
```

`UNKNOWN` is a real, queryable sentinel node (one per dimension, carrying no coordinates), not a null. It marks an endpoint that genuinely cannot be determined from the source — the materials consumed by a craft, the destination of a consumed item — and keeps *"the item left the player, destination unevidenced"* distinct from *"this row makes no topological claim"*. It is an explicit unresolved, never a guess.

`ARMOR_STAND` identities exist and are tested, but nothing produces them yet: GriefLogger has zero armor stand event coverage (Phase 0 recon), so wiring them needs the Phase 8 supplemental hooks.

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

## Correlation: ground bridging (Phase 5)

Implemented by `com.itemgraph.correlation.CorrelationEngine`.

### Scope decision: only the ground bridge is an inference

`docs/IMPLEMENTATION_PLAN.md` lists four patterns for Phase 5:

1. container remove → player gain
2. player drop → item entity
3. item entity → player pickup
4. player remove → container add

After the Phase 4 item-flow topology and the Phase 5 container-direction fix above, **three of those four are already a single fully-evidenced `ig_observations` row**:

| Plan pattern | Reality after direction fixes |
| --- | --- |
| container remove → player gain | one row, `CONTAINER -> PLAYER` |
| player remove → container add | one row, `PLAYER -> CONTAINER` |
| player drop → item entity | one row, `PLAYER -> GROUND` |
| item entity → player pickup | **not a single row** — see below |

Re-deriving those three as "inferred edges" would blur the evidence/inference boundary the project exists to preserve: `ig_observations` *is* the observed evidence, and `ig_inferred_edges` is specifically for claims no single row evidences. Emitting an inference with a confidence score for a fact GriefLogger states outright would misrepresent directly observed data as reconstruction.

Only the fourth link genuinely needs inference, and only because `GROUND` is an **ephemeral node**. A drop names the player who put an item there; a pickup names the player who took one away; **nothing in GriefLogger states they are the same item** — the `ItemEntity` UUID is not logged (Phase 0 recon). Connecting a drop to a pickup is therefore a claim about two separate observations: it needs a confidence, it needs an explanation, and it is what this engine produces. Every long-lived node (container, player) keeps its own identity across observations, so no bridging is required for them.

### Matching rules

For a `DROP_ITEM` / `THROW_ITEM` / `SHOOT_ITEM` observation, a candidate `PICKUP_ITEM` must:

- originate at the **same `GROUND` node** (same dimension + block),
- carry the **same `fingerprint_id`** (exact canonical metadata equality),
- have **available quantity capacity** — Phase 7 replaces binary matching with a durable quantity-allocation ledger (`ig_edge_allocations`), supporting stack splits, stack merges, and partial transfers with strict quantity conservation ($\sum \text{allocated} \le \text{evidenced capacity}$).
- be **strictly later** than the drop and within the configured window (`correlation.ground_bridge_max_seconds`, default 300s) — a later observation may explain an earlier event, never the reverse,
- have **remaining unallocated residual capacity** ($R_{pickup} = \text{amount} - \sum \text{allocated} > 0$). A pickup may receive allocations across multiple drops (many-to-1 merge), but total allocated quantity can never exceed the evidenced pickup amount.

The temporally closest admissible pickup with available residual capacity is selected. Competing candidates are not discarded silently — they reduce confidence.

### Quantity-flow ledger and lifecycle (Phase 7)

Phase 7 implements stack-aware reconstruction without per-item UUIDs:

1. **Durable Ledger (`ig_edge_allocations`)**:
   Tracks the exact quantity attributed from/to each observation:
   - `edge_id`: reference to `ig_inferred_edges.id`
   - `observation_id`: reference to `ig_observations.id`
   - `allocation_role`: `'SOURCE'` (for the drop) or `'DESTINATION'` (for the pickup)
   - `amount`: the allocated quantity units
   - `PRIMARY KEY(edge_id, observation_id, allocation_role)`

2. **Residual Capacity Accounting**:
   For any observation $O$ with evidenced quantity $Q_O$:
   $$R_O = Q_O - \sum_{\text{allocations}} \text{amount}$$
   For a candidate match between drop $D$ and pickup $P$, the edge amount is:
   $$\text{alloc} = \min(R_D, R_P)$$
   This guarantees strict quantity conservation without manufacturing items.

3. **Explicit Forensic Lifecycle (`correlation_status`)**:
   Replaces the overloaded binary interpretation of `correlated_at`:
   - `PENDING`: unallocated, candidate window still open.
   - `PARTIALLY_ALLOCATED`: partially consumed, window still open for subsequent split/merge matches.
   - `FULLY_ALLOCATED`: all evidenced quantity accounted for ($R = 0$).
   - `CLOSED_UNRESOLVED`: window expired with unallocated residual quantity ($R > 0$).

4. **Multi-fragment bridge resolution**:
   A single drop can split across multiple pickups (`stack split`); multiple drops can merge into a single pickup (`stack merge`); partial unrecovered quantities are preserved as residual until the time window closes. All edges, evidence links, allocation records, and observation status transitions commit in a single atomic database transaction.

### Confidence formula

Deterministic and fully reproducible from the stored observations. No model, no tuning, no randomness; the same inputs always produce the same number.

```text
confidence = round4( BASE x proximity(dt) x ambiguity(nPickups, forwardGap)
                                          x ambiguity(nDrops,   reverseGap) )

BASE              = 0.95
window            = ground_bridge_max_seconds x 1000

proximity(dt)     = 1 - 0.25 x clamp(dt / window, 0, 1)

ambiguity(n, gap) = 1                                                    if n <= 1
                  = 1/n + (1 - 1/n) x min(0.9, clamp(gap / window, 0, 1)) if n >  1

dt          = pickup.timestamp - drop.timestamp
nPickups    = admissible pickups for this drop
forwardGap  = runner-up pickup timestamp - chosen pickup timestamp   (0 if nPickups == 1)
nDrops      = admissible drops for the chosen pickup (including this one)
reverseGap  = |nearest competing drop timestamp - this drop timestamp| (0 if nDrops == 1)
```

Each factor is a documented evidentiary statement:

- **BASE = 0.95** — the ceiling for a perfect, unopposed match. Not 1.0, and never 1.0: without the `ItemEntity` UUID this is always circumstantial evidence (same place, same item, same amount, right order), so certainty is not available. The headroom is reserved for Phase 8, where a supplemental hook can log the entity UUID and support a genuinely stronger claim.
- **proximity** — an instant pickup scores 1.0; a pickup at the far edge of the window scores 0.75. Linear in elapsed time, so the penalty scales with the configured window instead of being a magic constant. A long delay weakens but does not refute the link, hence the modest 0.25 weight.
- **ambiguity** — with `n` equally admissible candidates and no discriminator, the honest prior in favour of the chosen one is `1/n`. Temporal proximity *is* a discriminator, so the score recovers toward 1.0 in proportion to how far the runner-up is, measured as a fraction of the window. Recovery is capped at 0.9: a demonstrated alternative explanation never fully disappears, so an ambiguous match can never score as high as an unambiguous one.
- The **reverse direction uses the identical function** — if several drops could equally explain the chosen pickup, that ambiguity is just as real and reduces confidence just as much.

Worked example (300s window): A drops 1 netherite chestplate, B picks it up 60s later, nothing competes.
`0.95 x (1 - 0.25 x 0.2) x 1 x 1 = 0.95 x 0.95 = 0.9025`.

Add a second, unrelated pickup of an identical stack 60s after B's, and the same bridge becomes
`0.95 x 0.95 x (0.5 + 0.5 x 0.2) = 0.5415`.

The exact factor values, candidate counts, both observation IDs, both player names, the block, both timestamps and the arithmetic are written into the edge's `explanation` column, so an admin asking *"why does ItemGraph think this transfer happened?"* gets a complete answer without re-running the engine.

### Incrementality and scheduling

`ig_observations.correlated_at` (migration V6, nullable epoch-millis) is the bookkeeping column. Every pass is driven by `correlated_at IS NULL` and bounded by `MAX_OBSERVATIONS_PER_PASS = 500`; the table is never fully scanned.

- A **pickup** is stamped as soon as it is seen — it is matched from the drop side, and candidate lookup deliberately ignores `correlated_at`, so a drop ingested later can still cite an already-stamped pickup.
- A **drop** is stamped once it produces an edge, or once its window has closed with no match (a recorded negative result).
- A drop whose window is **still open is left pending on purpose**: the pickup that explains it may simply not have been ingested yet — ingestion runs every 60s while the window is minutes long. That deferral is what stops the engine from permanently writing off drops purely for arriving near a cycle boundary.

Correlation runs on the **existing ingestion worker thread**, from the same scheduled executor, immediately after each ingestion cycle completes (`IngestionService.runIngestionSafely`). That gives newly ingested observations a correlation pass without waiting an extra cycle, and guarantees correlation never overlaps ingestion on the shared connection. `/ig ingest now` queues a pass onto that worker rather than running one inline, because candidate search must never happen on the server thread. GriefLogger's database is not touched by correlation at all: it reads and writes only ItemGraph's own tables, and each accepted bridge writes the edge, its two evidence rows and the drop's stamp in a single transaction, so an edge can never exist without its evidence.

## Query execution: off-thread, reported back on-thread (Phase 6)

Two engineering rules collide at the command layer and both have to hold at once:

- a historical query is a multi-join read over `ig_observations` and
  `ig_inferred_edges`, and **database scans must never run on the server thread**;
- `CommandSourceStack.sendSuccess` / `sendFailure` reach player and entity state, and
  **thread-unsafe Minecraft state must never be touched from a worker thread**.

`com.itemgraph.command.QueryDispatcher` is the seam:

```text
server thread            ItemGraph-Query-Worker           server thread
-------------            ----------------------           -------------
parse arguments     ->   open read-only connection    ->   sendSuccess /
resolve the window       run SQL                           sendFailure
dispatch, return 1       format to List<String>
```

### Marshalling back

The hand-back is `source.getServer().execute(Runnable)`. `MinecraftServer` extends
`ReentrantBlockableEventLoop<TickTask>`, so `execute` appends the task to the server's
pending-task queue and the next tick drains it on the server thread. This is the same
mechanism NeoForge's `enqueueWork` uses for parallel-dispatch events.

One caveat is handled explicitly: `MinecraftServer.scheduleExecutables()` returns false
once the server is stopping, and `BlockableEventLoop.execute` then runs the task *inline
on the calling thread* rather than queueing it. `QueryDispatcher` therefore checks
`server.isStopped()` (and `ServerPlayer.hasDisconnected()`) before sending anything.

### Prior art

This pattern was checked against real mods before being adopted rather than derived from
first principles:

- **BigGlobe** (`builderb0y.bigglobe.commands.AsyncCommand`) runs a long search on its own
  daemon thread and reports results with `this.source.getServer().execute(() -> ... sendSuccess ...)`,
  guarded by an `isValid()` check that tests `getServer().isStopped()` and
  `ServerPlayer.hasDisconnected()` — including in its uncaught-exception handler.
- **ChronoVault** (`io.github.catt1eyaa.chronovault.command.ChronoVaultCommands`) drives a
  `CompletableFuture` and marshals both progress and completion back with
  `future.whenComplete((result, throwable) -> source.getServer().execute(...))`.

ItemGraph matches both: `CompletableFuture` + `whenComplete` + `getServer().execute(...)`,
with the same liveness guards. The only deliberate deviation is the executor — the mods
above spawn a thread per invocation, whereas ItemGraph uses one shared single-threaded
executor so that concurrent admin queries serialise instead of opening an unbounded number
of SQLite readers.

### Why not the ingestion worker

The ingestion worker runs a 60-second ingest-then-correlate cycle. Queueing an admin's
lookup behind it would mean an incident query that answers a minute late, or not until the
current cycle finishes. Command latency is kept independent of the background pipeline.

### Why a separate database connection

`DatabaseManager.getConnection()` hands out the one connection the ingestion worker writes
through, in explicit transactions. A query issued on another thread against that same
connection would execute *inside* the writer's open transaction and could read rows that
are about to be rolled back — an admin could be shown an observation that never existed.
`DatabaseManager.openReadOnlyConnection()` opens a short-lived independent connection with
`PRAGMA query_only = ON`; WAL mode (set at initialisation) means that reader sees a
consistent committed snapshot and never blocks the writer.

### Return value

A query handler returns Brigadier's success code as soon as the query is *accepted*. The
real answer cannot be known synchronously without doing on-thread SQL, which is the thing
being avoided. The distinction is visible to the admin in the output itself.

## Query output: the labelling convention (Phase 6)

The charter forbids presenting inference as direct evidence. In query output that is
enforced per line, not by a caveat printed once at the top:

```text
[OBSERVED]              one raw ig_observations row; directly evidenced
[INFERRED conf=0.9025]  one ig_inferred_edges row; reconstructed, with its stored score
```

Rules that hold everywhere in `QueryFormatter`:

- **No unlabelled movement line exists.** Every line asserting that an item moved is
  prefixed with its provenance, provenance first so it is read before the claim.
- **Confidence is printed on every inferred line**, to four decimals — enough to reproduce
  the engine's rounded score exactly.
- `ObservationDetail.kindLabel()` is the constant `OBSERVED` and
  `EdgeExplanation.kindLabel()` the constant `INFERRED`. They are not fields, so the
  display layer cannot relabel evidence as inference.
- **The stored explanation is read, never recomputed.** Regenerating the scoring narrative
  at display time would let the displayed reasoning drift from the confidence actually
  stored on the row, describing an inference ItemGraph never made.
- **A trace shows the bridge *and* the observations underneath it.** For a ground bridge,
  the drop (`player -> GROUND`), the bridge (`player -> player`) and the pickup
  (`GROUND -> player`) all appear. Hiding the observations would hide the evidence; hiding
  the bridge would hide the claim.
- **Dangling references render as `no such row`, not as a dropped row.** The shared
  projection uses LEFT joins even where the schema declares the foreign key NOT NULL: a
  missing row in an evidence listing is far more dangerous than an ugly one.
- **An edge citing no evidence says so** (`NONE RECORDED - ... cannot be justified`) rather
  than rendering an empty section that reads as though it scrolled off.
- **Timestamps are UTC** (`yyyy-MM-dd HH:mm:ss UTC`). An incident report gets compared
  against server logs and GriefLogger rows stored as epoch millis; a timezone-dependent
  rendering would make two copies of the same output disagree.

Output is sent with `broadcastToOps = false`. The graph names players, containers and
coordinates — see `docs/SECURITY_AND_PERMISSIONS.md`.

## Quantity-Flow Allocation Ledger (Phase 7)

ItemGraph reconstructs stack splits, stack merges, and partial transfers without assigning synthetic UUIDs to individual items.

Instead, it maintains an explicit allocation ledger:

```sql
CREATE TABLE IF NOT EXISTS ig_edge_allocations (
    edge_id INTEGER NOT NULL REFERENCES ig_inferred_edges(id) ON DELETE CASCADE,
    observation_id INTEGER NOT NULL REFERENCES ig_observations(id),
    allocation_role TEXT NOT NULL,  -- 'SOURCE' or 'DESTINATION'
    amount INTEGER NOT NULL,
    PRIMARY KEY(edge_id, observation_id, allocation_role)
);
```

### Forensic Lifecycle (`correlation_status`)
Observations track their allocation progress:
- `PENDING`: newly ingested, candidate window remains open.
- `PARTIALLY_ALLOCATED`: partially consumed by inferred edges, window still open for subsequent transfers.
- `FULLY_ALLOCATED`: 100% of evidenced item quantity has been accounted for by edges ($R = 0$).
- `CLOSED_UNRESOLVED`: candidate correlation window expired while residual unallocated quantity remained ($R > 0$).

### Dynamic Capacity Accounting
$$\text{allocation} = \min(R_{\text{drop}}, R_{\text{pickup}})$$
Every allocation is strictly bounded by evidenced capacity, enforcing quantity conservation ($\sum \text{allocated} \le \text{evidenced capacity}$).

## High-Value Integrations & Entity Continuity (Phase 8)

### ItemEntity Continuity Tracking
Authoritative Minecraft `ItemEntity` UUIDs are tracked at the time of ground toss and pickup.
- Tracked via `ItemEntityTracker` and `ItemEntityEventListener` subscribed to `ItemTossEvent` and `ItemEntityPickupEvent.Post`.
- Persisted in `ig_observations.item_entity_uuid` (schema V8).
- When a drop and pickup share an exact `ItemEntity` UUID, the correlation engine boosts confidence to `0.9990` and documents direct entity continuity in the scoring explanation.

### Armor Stand Tracking
- `ArmorStandEventListener` captures `PlayerInteractEvent.EntityInteractSpecific` to record `EQUIP_ARMOR_STAND` and `UNEQUIP_ARMOR_STAND` observations on armor stand container nodes.

### Expanded Query UX
- `/ig trace player <playerName>`: reconstructs all item transfers, container events, and ground movements involving a player.
- `/ig trace container <x> <y> <z>`: reconstructs item ingress and egress for a container at coordinates.
- `/ig trace item <query>`: resolves string queries by numeric ID, item registry ID, or custom name.

## Transformation Lineage (Phase 9)

Item transformations (identity shifts) are tracked in `ig_item_transformations`:
- Records transitions linking source fingerprint to result fingerprint (`ANVIL_RENAME`, `CRAFTING`, `SMELTING`).
- Captured via `TransformationEventListener` (`AnvilRepairEvent`, `ItemCraftedEvent`, `ItemSmeltedEvent`).
- Persisted asynchronously via `InternalObservationService` with a memory-bounded queue (10,000 capacity).
- Surfaced chronologically in item trace timelines as `[TRANSFORMATION <type> <- <source>]` hops.

## Database Invariant Auditing & Diagnostics (Phase 10)

`AuditService` enforces ItemGraph's core invariants asynchronously:
1. **Quantity Conservation**: $\sum \text{allocated} \le \text{evidenced capacity}$ across all observations and inferred edges.
2. **Positivity**: quantities on observations, edges, and allocations are strictly positive ($amount > 0$).
3. **Relational Graph Integrity**: zero orphaned allocations and zero missing edge endpoint nodes.
4. **Lifecycle State Consistency**: validates observation `correlation_status` against active allocations.

The `/ig audit` command runs this engine on the query worker and outputs a comprehensive integrity report. Diagnostic counters in `/ig status` report internal queue throughput, transformation totals, active tracked entities, and continuity matches.

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
