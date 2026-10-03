# Architecture

## Purpose

ItemGraph reconstructs plausible movement of Minecraft items and stack quantities through inventories over time.

## Loader boundary (0.3.2)

The Gradle build is a multi-project build with this dependency direction:

```text
fabric adapter ─┐
                ├──> common runtime ───> core
neoforge adapter┘
```

- `core/` contains Java-only domain records (`CanonicalItem`, `CorrelationResult`, and `NodeType`) and loader-neutral ports. It must not import Minecraft, Brigadier, Fabric, NeoForge, SQLite, or JDBC packages. `verifyCoreArchitecture` enforces that source boundary.
- `common/` contains code shared by both mod jars. It compiles against Minecraft 1.21.1 with Mojang mappings and may use game APIs, but it must not import Fabric or NeoForge APIs. `verifySharedLoaderBoundary` enforces that boundary. ItemGraph-owned SQLite/MySQL/MariaDB persistence and migrations are shared runtime components here. The optional GriefLogger read-only migration adapter is disabled by default.
- `fabric/` owns Fabric metadata, config-file loading, Fabric Loader discovery, Fabric server lifecycle/command registration, and its Modrinth loader metadata. Its standard jar includes SQLite and MariaDB Connector/J as nested Fabric jars; its compatible jar replaces the metadata, requires GriefLogger, and strips only the nested SQLite jar.
- `neoforge/` owns `@Mod`, NeoForge config, NeoForge event listeners, NeoForge metadata, and Jar-in-Jar packaging. Its standard jar bundles SQLite and MariaDB Connector/J; its compatible jar requires GriefLogger, keeps MariaDB Connector/J, and omits the Jar-in-Jar SQLite module.

`RuntimeInformationPort` is declared in `core` and is implemented by both loader composition roots. `ItemGraphCommands` and API validation use this port for the mod version and installed-mod lookup instead of calling either loader's discovery API. Both adapters initialize the shared runtime with platform-owned config paths, server registry access, and server lifecycle callbacks.

The adapters expose different event APIs but both now have native audit capture. NeoForge
adds native container, item-entity, armor-stand, transformation, and item-action listeners;
Fabric adds its supported session, chat, block, death, command-dispatch, fresh-projectile,
completed-consumption, durability-break, transformation-result, and container-inspection callbacks.
NeoForge command callbacks and the Fabric `CommandsMixin` are stored as
`COMMAND_ATTEMPT` because both hooks run before command execution. This matches
GriefLogger's documented command-attempt behavior and avoids inventing a success result.

Issue #33 adds a separate staff-private evidence path at vanilla's item mutation
boundaries. Both loaders hook `GiveCommand.giveItem`,
`ClearInventoryCommands.clearInventory`, the four `ItemCommands` block/entity
set/modify methods, the `getBlockItem` and `getEntityItem` source readers used by
`/item ... from`, and `ServerGamePacketListenerImpl.handleSetCreativeModeSlot`.
The hooks snapshot only the addressed player inventory or selected block/entity
slots, then compare canonical item fingerprints and stack counts after the method
returns. `/give` additionally records only accepted overflow item entities. A
command callback remains an attempt; slot deltas are authoritative observations.
For non-player entity slots where ItemGraph has no supported graph endpoint, the
change is retained as an unresolved staff audit event and does not create a graph
edge. Fabric records creative destruction from `PlayerBlockBreakEvents.AFTER`.
NeoForge's `BlockEvent.BreakEvent` runs before block removal, so the NeoForge
adapter pairs that callback with the boolean returned by
`ServerPlayerGameMode.destroyBlock`; this narrowly scoped mixin is required to
record the actual result consistently across loaders.
Placement is confirmed after `BlockItem.place` returns and bounded before/after
state snapshots show the placed block. NeoForge's cancellable
`BlockEvent.EntityPlaceEvent` is not treated as completion because a later
listener can still cancel it. The NeoForge BlockItem return wrapper mirrors the
Fabric post-return capture and preserves the original result if capture fails.
Both adapters use invocation-local snapshots, so nested modded placements do
not overwrite their callers' state.
The typed command attempt carries the mutation event ID; confirmed slot deltas
and the completion outcome reuse that ID, while the outcome also records the
attempt event ID. `/item ... from block/entity` records the copied-from endpoint,
slot, and canonical stack on the target evidence; a copy leaves the source slot
unchanged and the target delta remains explicit creation. Nested `/execute as`
records the original command issuer in actor fields and a differing effective
entity in separate execution-context fields. A positive command return without
a captured delta and an exception after a captured mutation are unresolved
outcomes. Generic command history suppresses `/execute` text to avoid retaining
nested item-command arguments. Vanilla item commands require permission level 2;
the root attempt records the issuer's permission outcome before execution.
NeoForge-only inventory hooks remain an explicit platform coverage boundary in
`docs/GRIEFLOGGER_PARITY.md`; Fabric `BlockItemMixin` captures completed BlockItem
placements, `LivingEntityMixin` captures completed eat/drink uses, `ItemStackMixin` captures
durability breaks at the shrink boundary. Both loaders use a `ProjectileMixin` at
`Projectile.shootFromRotation` HEAD for the GriefLogger-compatible quantity row. The attempt
UUID remains in raw data and is projected into `source_event_id` for retry-safe persistence.
If two UUIDs share the same 63-bit projection, persistence checks `event_id` against
both `ig_observations` and `ig_audit_events`, then derives a deterministic salted
projection from the producer UUID. Paired rows use the same remapped ID even when
their raw payload details differ; a non-null source ID without a parseable UUID
payload fails persistence explicitly instead of being silently ignored.
Their `ServerLevel.addFreshEntity` return callbacks retain accepted-spawn evidence only when
the server reports `true`, without a second quantity row. Fabric
result-slot mixins capture crafting, smelting, and anvil transformations
into the shared ledger. Both loaders
share the read-only `FlowBrowserService` for coordinate inspection; GriefLogger ingestion
remains optional and read-only on both loaders.

The root `build` task runs both loader builds, both jar verifiers, and the core/shared boundary checks. Release files use explicit `fabric` or `neoforge` classifiers, with `grieflogger-compatible` appended only to the temporary coexistence jars that require GriefLogger `1.2.10-1.21.1`.

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
- Player ender chest (`EXTERNAL_INVENTORY`, durable key `minecraft:ender_chest/<player UUID>`)
- Block container
- Entity inventory
- Armor stand
- Ground/item entity
- Modded inventory
- Faction storage
- Unknown/unresolved holder

A node is not necessarily permanent. Some are long-lived, such as a chest at fixed coordinates. Others are ephemeral, such as a ground item entity.

### Native audit events

`ig_observations` is reserved for item quantity-flow evidence. Native block,
session, chat, command, entity, and projectile-use audit records are stored in the separate
`ig_audit_events` ledger so they cannot be mistaken for item transfers. The
ledger uses the same bounded asynchronous persistence service and read-only query
dispatcher as item evidence. NeoForge consume and durability-break events remain
quantity-flow observations because they carry an observed item identity and amount.
NeoForge and Fabric projectile spawn callbacks record only the spawned projectile type,
source item ID, and location as audit evidence: they do not assert that one item left an
inventory or that the projectile reached the ground. This preserves quantity
conservation for Infinity bows, multishot, and throwable items without a matching
inventory delta.

Schema V19 adds `ig_audit_event_supersessions` for native block-interaction rows and
`ig_grieflogger_row_supersessions` for imported GriefLogger rows. A non-canceled block
break links earlier interactions at the same dimension and target cell(s) to the
retained break evidence using `BLOCK_REMOVED_AT_TARGET`. These are visibility
supersessions, not evidence deletion or a claim that inventory moved. The inspector
omits superseded interactions; ordinary lookup retains them and displays the linked
break evidence ID and reason. The persistence worker creates the links in the same
ItemGraph transaction that writes the break event; loader callbacks only enqueue
immutable target positions and never access SQL. Imported supersession identity uses
a SHA-256 source-key digest; it retains the complete source key as `LONGTEXT` and
uses an indexed 191-code-point prefix plus byte-exact full-key comparison for lookup,
so imported keys are not truncated, prefix collisions do not merge rows, and
case-insensitive MySQL/MariaDB collations cannot merge case-distinct source keys.

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

The implemented node types are `PLAYER`, `CONTAINER`, `GROUND`, `ARMOR_STAND`, and `UNKNOWN` (`com.itemgraph.graph.NodeType`), resolved to stable identities by `com.itemgraph.graph.NodeManager`.
The endpoint table below applies to quantity rows imported from GriefLogger's
`items` and `containers` tables. Native projectile spawn callbacks are stored in
`ig_audit_events` and make no origin, destination, or quantity claim:

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

`ARMOR_STAND` identities exist and are tested. ItemGraph records server-side armor-stand results from the `ArmorStand.interactAt` override and inherited `Entity.interact` fallback hooks: consuming results become `INTERACT_ENTITY_COMPLETED`, `FAIL` becomes `INTERACT_ENTITY_DENIED`, and a fallback `Entity.interact` `PASS` becomes `INTERACT_ENTITY_UNRESOLVED` with a method-boundary reason. This records returned method results; it does not claim that equipment or quantity changed. Attempt details may carry the held stack's registry ID, count, and canonical fingerprint, without copying component values.

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

For an imported GriefLogger `DROP_ITEM` / `THROW_ITEM` / `SHOOT_ITEM` observation,
a candidate `PICKUP_ITEM` must:

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

Correlation runs on the **existing ingestion worker thread**, from the same scheduled executor, after each worker cycle (`IngestionService.runIngestionSafely`). The optional GriefLogger source sync runs before correlation only when explicitly enabled. Before candidate search, cross-source matching checks at most 500 unchecked ground observations; `loadPendingGroundObservations` only admits checked rows. `/ig ingest now` queues one complete migration sync-and-correlate cycle with `requestIngestionAsync`; it does not read GriefLogger or search candidates on the server thread. Internal persistence, enabled GriefLogger batch writes, and correlation transactions synchronize on the shared ItemGraph JDBC connection, preventing `autoCommit`/commit state interleaving. GriefLogger's database is not touched by correlation at all: accepted bridges write the edge, corroborating evidence rows and quantity allocations to ItemGraph's own tables in a single transaction.

## Query execution: off-thread, reported back on-thread (Phase 6)

Two engineering rules collide at the command layer and both have to hold at once:

- a historical query is a bounded merge over native audit, observation,
  transformation, normalized GriefLogger event, and inferred-edge tables, and
  **database scans must never run on the server thread**;
- `CommandSourceStack.sendSuccess` / `sendFailure` reach player and entity state, and
  **thread-unsafe Minecraft state must never be touched from a worker thread**.

`com.itemgraph.command.QueryDispatcher` is the seam:

```text
server thread            ItemGraph-Query-Worker           server thread
-------------            ----------------------           -------------
parse arguments     ->   open read-only connection    ->   sendSuccess /
resolve the window       run SQL                           sendFailure
dispatch, return 1       format lines or TracePage          open/update menu
```

### Marshalling back

The hand-back is `source.getServer().execute(Runnable)`. `MinecraftServer` extends
`ReentrantBlockableEventLoop<TickTask>`, so `execute` appends the task to the server's
pending-task queue and the next tick drains it on the server thread. This is the same
mechanism NeoForge's `enqueueWork` uses for parallel-dispatch events.

One caveat is handled explicitly: `MinecraftServer.scheduleExecutables()` returns false
once the server is stopping, and `BlockableEventLoop.execute` then runs the task *inline
on the calling thread* rather than queueing it. Both query-delivery paths capture the server
thread before submission and compare `Thread.currentThread()` with that captured thread before
touching a command source, player, or menu. On the server thread, `canStillReport` then rejects
stopped servers and disconnected players.

`QueryDispatcher.dispatchData` uses the same read-only worker boundary for GUI page/detail
DTOs. Its callback opens or updates `FlowBrowserMenu` only on the server thread. That menu
uses vanilla `MenuType.GENERIC_9x6`; its server-side click handler never delegates item
movement to `ChestMenu`, and every GUI action rechecks permission level 2.

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
executor with at most 64 waiting queries. This serializes read-only JDBC work and returns
an explicit queue-full failure instead of growing pending work without bound. Every statement
receives a five-second JDBC query timeout and is tracked so dispatcher cancellation can call
`Statement.cancel()`. SQLite additionally uses Xerial's cross-thread database interrupt on the
dedicated connection and a per-connection `ProgressHandler` to abort during VM execution,
covering the attach-to-statement race. SQLite documents
[`sqlite3_interrupt`](https://www.sqlite.org/c3ref/interrupt.html) as safe from a different
thread. An interrupted RCON caller has its interrupt flag restored.

Vanilla `DedicatedServer.runCommand` executes RCON commands on the server thread and returns
the RCON response buffer immediately afterward. Entity-less server-thread sources therefore
receive a synchronous “query accepted” response; completed query lines are written to the
server log instead of a response buffer that has already been returned.

Player-originated queries have the same five-second per-statement timeout and cancellation
behavior, but no total wall-clock deadline across a callback that executes several statements.
A JDBC driver that does not honor statement timeout or cancellation can still occupy the single
worker; the 64-entry queue remains bounded and rejects additional requests.

### Preview mod-integration API worker

`com.itemgraph.api.ItemGraphServiceImpl` exposes the approved `PREVIEW_1` API in the
main mod JAR. It uses one bounded worker (capacity 1,024) for source registration and
raw `DirectObservation` persistence, and a second bounded worker (capacity 64) for
read-only API queries. Saturated submissions return `QUEUE_FULL`; saturated
registrations return `FAILED`/`QUEUE_FULL` because the approved registration enum has no
`QUEUE_FULL` status; saturated queries return `QueryStatus.QUEUE_FULL`. Neither worker
touches Minecraft thread-unsafe state. `ItemGraphApiLifecycle` stops both workers with
the database on `ServerStoppingEvent`.

API submissions never create inferred edges or caller-controlled confidence. They enter
the same `InternalObservationService`/SQLite path as raw `EXTERNAL_API` evidence and
deduplicate on `(source_mod_id, source_event_id)`. `ApiQueryBridge` reuses the existing
read-only trace/explain services and maps internal rows to immutable API DTOs, preserving
observed/transformation/inferred provenance and opaque evidence URIs.

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
- `CORROBORATING`: raw row belongs to a confirmed cross-source group but contributes no independent quantity capacity.
- `SOURCE_AMBIGUOUS`: raw row has a plausible cross-source duplicate without unique identity; it is withheld from correlation.

### Dynamic Capacity Accounting
$$\text{allocation} = \min(R_{\text{drop}}, R_{\text{pickup}})$$
Every allocation is strictly bounded by evidenced capacity, enforcing quantity conservation ($\sum \text{allocated} \le \text{evidenced capacity}$).

## High-Value Integrations & Entity Continuity (Phase 8)

### ItemEntity Continuity Tracking
Authoritative Minecraft `ItemEntity` UUIDs are tracked only after the entity is confirmed in the level.
- `ItemEntityEventListener` records successful toss/death drops after `ItemEntity.isAddedToLevel()` becomes true, and records pickups from `ItemEntityPickupEvent.Post` or a verified partial stack delta.
- A canceled toss is `DROP_CANCELLED` to UNKNOWN; canceled death drops are `DEATH_DROP_CANCELLED` attempt evidence with no destination. Neither is a ground transfer.
- `ItemEntityTracker.findMatchingDropEntity` returns an exact UUID only when one candidate fits the spatial/time query.
- Persisted in `ig_observations.item_entity_uuid` (schema V8).
- When a drop and pickup share an exact unique `ItemEntity` UUID, correlation boosts confidence to `0.9990` and documents direct entity continuity in the scoring explanation.

### Armor Stand Tracking
- NeoForge and Fabric retain entity interaction attempts with the target UUID when available. Their `ArmorStandInteractionMixin` hooks the `ArmorStand.interactAt` override at `RETURN`, while `EntityInteractionMixin` hooks inherited `Entity.interact` at `RETURN`, storing consuming results as `INTERACT_ENTITY_COMPLETED`, `FAIL` as `INTERACT_ENTITY_DENIED`, and a generic-method `PASS` as `INTERACT_ENTITY_UNRESOLVED`. The mixin is necessary because NeoForge's documented interaction pipeline calls `EntityInteractSpecific` before `Entity#interactAt`, and Fabric's `UseEntityCallback` is pre-use; neither callback proves the method result. NeoForge retains canceled specific and generic callbacks. Fabric decorates the aggregate `UseEntityCallback` invoker at event creation: it snapshots the actor, target, position, hand, and held-item fingerprint before listeners execute, invokes the original aggregate exactly once, records its final non-`PASS` result, and returns that result unchanged. This captures short-circuits before or after ItemGraph's listener without reordering or replaying listeners. Fabric metadata requires the exact API version used to verify this initializer hook. Runtime player-interaction replay remains unverified. Attempt rows store held stack registry ID, count, and canonical fingerprint, not raw component values. The earlier pre-use listener that projected armor-stand clicks into equip/unequip quantity observations was removed because the callback did not prove a transfer occurred. No quantity or equipment-slot transition is inferred from the returned result.

## Native Container & Ground Observation (M5, 0.2.0)

ItemGraph records its own `ITEMGRAPH_INTERNAL` observations via
`InternalObservationService` (bounded
10,000-entry async queue, batch-persisted with `INSERT OR IGNORE`). V11 adds a
destination-sensitive internal dedup index, interval end times, edge state, and derived
cross-source source groups. Raw observations remain unchanged; a confirmed group has one
canonical capacity row and corroborating source rows, while a merely compatible signature
is marked ambiguous and contributes no independent capacity. If a legacy inferred edge
used an alias or ambiguous row for allocation, it is marked superseded rather than deleted.

Each immutable queued observation, transformation, and audit event carries an
`ingest_event_uuid`. Schema V18 stores it in a unique column on its ledger. The worker
reuses the same record on retry, so a commit that succeeded before its acknowledgement
was lost becomes an ignored duplicate on replay rather than a second quantity or audit
row. Producer `source_event_id` remains separate and continues to represent source-level
identity.

The native worker limits each transformation write and shutdown flush to the configured
`ingestion.max_batch_size`. Failed transformation batches return to the same bounded queue
with exponential backoff; the status queue count includes in-flight transformations. A
requeue overflow or shutdown write failure increments the evidence-loss counter and logs
the number of dropped records. Legacy migrations V3–V5 copy the original observation
fields, referenced fingerprint values, and raw payload into
`ig_legacy_observation_evidence` before clearing endpoints written under obsolete
topology rules. That archive is retained but excluded from live
graph queries.

For MySQL/MariaDB, the worker sends `Connection.isValid(5)` at the configured
`operations.database_heartbeat_interval_ms` interval (default 30 seconds) so
idle network connections receive a protocol keepalive. The call runs on the
ItemGraph worker and is serialized with writes on the shared connection. SQLite
does not send heartbeats.

### Cross-source equivalence (V11)

`ObservationEquivalenceService` processes up to 500 unchecked ground observations per
correlation pass using a fingerprint/action/actor/time-bounded candidate query, recording
checked rows in `ig_observation_match_checks`. A pair is
confirmed only when it has one unique counterpart with the same non-null `item_entity_uuid`,
action family, fingerprint, amount, actor, and a timestamp difference of at most 250 ms.
The corroborating source row remains raw evidence and is attached to inferred edges; only the
canonical row contributes quantity capacity. A compatible signature without shared entity
identity becomes an `AMBIGUOUS` group with no inference capacity.

Groups and member roles live in `ig_observation_groups` and
`ig_observation_group_members`; they do not merge or delete source rows. If an existing edge
used a corroborating or ambiguous row for an allocation, it is retained with
`edge_state=SUPERSEDED_SOURCE_DUPLICATE` or `edge_state=SUPERSEDED_SOURCE_AMBIGUITY`, excluded
from active traces and capacity totals, and visible through `/ig explain <edgeId>` as a
superseded inference. Migration V20 applies the same lifecycle to dependent edges built from
legacy pre-use armor-stand callbacks, using `SUPERSEDED_UNVERIFIED_EVIDENCE`; its source rows
remain inspectable as unresolved evidence but are omitted from current flow traces. Correlation,
GriefLogger ingestion, and internal observation writes serialize transactions on the shared
ItemGraph JDBC connection.

### Ground movement
- `ItemTossEvent` and `LivingDropsEvent` create bounded pending-drop entries. The
  listener records `DROP_ITEM`/`DEATH_DROP` as player → GROUND only after the
  `ItemEntity` reports `isAddedToLevel()`. A canceled toss is `DROP_CANCELLED` to UNKNOWN;
  a canceled death-drop is `DEATH_DROP_CANCELLED` with no destination. Neither claims
  ground movement or receives an `item_entity_uuid`.
- `ItemEntityPickupEvent.Post` → `PICKUP_ITEM` (GROUND → player) with the *actually picked
  up* quantity (`originalStack - currentStack`, so partial pickups never inflate quantity).
  The `ItemEntityTracker` returns an exact UUID only when the spatial/time match is unique.
- **Partial-pickup gap (NeoForge 21.1.248)**: `ItemEntity.playerTouch` gates `Post`
  on `Inventory.add()` returning true, but `Inventory.addItem` returns false when
  only part of the stack fit — a partial pickup absorbs items yet fires no Post
  (and no vanilla pickup stat). `ItemEntityPickupEvent.Pre` therefore records a
  pending attempt (entity, player, pre-add count); a `ServerTickEvent.Post` sweep
  reads the entity's live stack — a reduced count on a still-alive entity is an
  absorbed partial and emits `PICKUP_ITEM` with the exact delta, tagged
  `{"detection":"pre_post_pairing"}` in `raw_data`. A fired Post consumes the
  pending entry so full pickups are never double-counted; entries on removed
  entities are dropped without emitting (merge/despawn indistinguishable from
  absorb) and all entries expire after 1s under a 512-entry bound.
- Fabric normal player drops and pickups use server-only hooks in
  `ServerPlayerMixin`, `ServerLevelMixin`, and `ItemEntityMixin`. A returned
  drop entity is recorded only when `ServerLevel.addFreshEntity` returns true,
  with its returned stack count and UUID. Pickup records the exact before/after
  stack-count delta, including partial absorption, and maps the ground endpoint
  through the same `GROUND` persistence branch. Drops observed while
  `ServerPlayer.isDeadOrDying()` are labeled `DEATH_DROP`; custom death-event
  additions and automated non-player item movement remain unimplemented until a
  loader-native hook can prove their source and destination.

### Automated container transfers (Issue 4)
- `ContainerCapabilityRegistrar` registers `ContainerCapabilityWrapper` providers
  for `Capabilities.ItemHandler.BLOCK` at `EventPriority.HIGHEST`. Priority matters:
  `BlockCapability.getCapability` returns the first non-null provider in
  registration order, and NeoForge's own vanilla providers register at normal
  priority — the wrapper must land earlier or it is never invoked.
- Each provider wraps the *same* handler vanilla would return, so interception is
  observation-only: `SidedInvWrapper` for `WorldlyContainer` types (face rules
  preserved), `ChestBlock.getContainer` merged view for chests (double chests
  intact), `VanillaHopperItemHandler` for hoppers (cooldown semantics preserved),
  `ForwardingItemHandler` for the composter. Types vanilla does not serve (lectern,
  ender chest) are not registered — adding a capability would create automation
  behaviour rather than observe it.
- Every real (`simulate=false`) insert/extract emits `CAPABILITY_INSERT` /
  `CAPABILITY_EXTRACT` anchored to the observed container. An `IItemHandler` call
  identifies neither caller nor cause, so the other endpoint is the per-level UNKNOWN
  node and the row does not claim hopper/automation provenance.
- The wrapper records signed capability deltas even when the bounded observation queue
  rejects the raw row. At session close, rejected quantities are retried once as
  coalesced UNKNOWN-caller evidence; if the queue is still full, the retry is counted as
  dropped and never attributed to a player. `/ig status` exposes both counters.

### Modded automation adapter boundary (Issue 34)
- `AutomationEndpoint.blockInventory` creates a durable opaque external-inventory ID
  from owner mod ID, dimension, block position, slot policy, and exposed side. The ID
  remains stable across restart and does not publish coordinates as the endpoint
  identifier. `lastKnownLocation` is null by default.
- `AutomationTransferAdapter.reportCommittedTransfer` accepts both endpoint identities,
  exact native requested/moved amounts, source event ID, source mod ID, and slot indexes.
  It rejects impossible quantities (`movedAmount > requestedAmount`) and suppresses
  simulations, rollbacks, and zero movement. Partial committed operations record only
  the accepted quantity as `TRANSFER_ITEM`; evidence contains both slot policies, sides,
  automation mod ID, and transfer ID. Integrations call it only after the outermost
  transaction commits.
- This adapter is opt-in. Fabric Transfer API 5.4.4 exposes per-storage insert/extract
  methods and nested transaction commit/rollback, but no global callback for all third-party
  storage calls. NeoForge 21.1 capabilities register per block or block entity type;
  `IItemHandler` has a per-call `simulate` flag but no caller identity. ItemGraph therefore
  does not claim automatic interception of arbitrary modded inventories. NeoForge's
  native capability wrappers cover the vanilla providers listed above. Vanilla hopper
  transfers use a separate `HopperBlockEntity.tryMoveItems` hook on both loaders because
  the vanilla path mutates `Container` directly. The NeoForge capture snapshots at most
  seven adjacent positions and 512 total slots; both loaders report only net quantity
  changes with an UNKNOWN caller and endpoint. Narrow `DispenserBlock` and `DropperBlock`
  wrappers bind their `dispenseFrom` calls to `DefaultDispenseItemBehavior.spawnItem` and record
  only an accepted `ItemEntity` after `Level.addFreshEntity` succeeds. That observed
  path becomes a source-container-to-ground row with the entity UUID; custom behaviors,
  projectiles, buckets, and rejected spawns are outside the contract. The pinned Fabric
  0.116.12+1.21.1 event set and NeoForge 21.1.248 API do not provide a global hopper or
  dispenser committed-transfer event, so these vanilla hooks are loader-local and
  modded inventories use the explicit adapter contract.
- Fabric integrations can use `FabricTransferStorageAdapter` to wrap a `Storage<ItemVariant>`
  (or one `SlottedStorage` slot). It delegates reads and transfer results unchanged, keeps
  a bounded per-thread transaction journal, applies nested rollback, and submits endpoint
  deltas only from Fabric's outermost commit callback. Because a single storage wrapper
  cannot observe the opposite storage or transfer caller, its remote endpoint remains
  UNKNOWN; the shared `AutomationTransferAdapter` supports integrations that observe
  both ends directly. If a transaction exceeds 64 distinct item/direction deltas or
  262,144 units for one delta, ItemGraph omits the whole transaction's evidence and logs
  a bounded warning counter; it never persists a partial transaction as complete.
- NeoForge integrations can use `NeoForgeItemHandlerAdapter` to wrap an `IItemHandler`.
  It preserves delegate results, ignores `simulate=true`, records exact insert/extract
  quantities by slot, and leaves the caller/opposite endpoint UNKNOWN unless the
  integration submits a paired observation through the shared API. Block-backed storage
  identity includes the queried face and slot. Portable storage integrations provide
  their own stable opaque `ExternalInventoryEndpoint`; the adapter adds slot and side to
  each delta and does not derive identity from an `ItemStack`.
- A confirmed `QUEUE_FULL` from the public API receives at most three retries with the
  same source event ID and immutable observation. One daemon worker and a 128-entry
  pending queue bound retry memory; other statuses are not retried. Retries are
  in-memory only and can be interrupted by process shutdown.
- Committed vanilla hopper/dispenser observations use `InternalObservationService.submitNativeCapture`.
  The primary observation queue holds 10,000 rows; a second bounded 1,024-row queue retains
  native captures when the primary queue is full or already has deferred native captures.
  The same asynchronous worker drains both queues with alternating priority, preserving
  throughput for ordinary observations. Failed database batches retry into either bounded
  queue, so the deferred queue's `/ig status` size is lane occupancy, not a native-origin
  count. `/ig status` also reports `nativeCaptureBackpressureExhausted`; direct native
  capture rejection after both queues fill is counted as dropped evidence. Shutdown makes
  one persistence attempt for rows in both queues and counts a failed final write as dropped.
  This is bounded in-memory backpressure, not crash-durable storage.
- If an integration cannot identify the opposite endpoint or stable slot policy, it must
  preserve an UNKNOWN endpoint or omit the observation. It must not infer a player from
  proximity or report simulated/rolled-back movement as observed.
- API references: [Fabric Transfer API item storage](https://wiki.fabricmc.net/tutorial%3Atransfer-api_item_storage)
  and [NeoForge 1.21.1 capabilities](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/).

### Player container transfers (Issue 3)
- Player GUI clicks mutate the menu's `Container` directly
  (`AbstractContainerMenu.moveItemStackTo`); they never traverse `IItemHandler`, so
  a capability wrapper cannot observe them. `ContainerSessionListener` binds
  `PlayerContainerEvent.Open`/`Close` to `ContainerInteractionTracker` and emits a
  fingerprint-level session net delta. `timestamp_ms` and `timestamp_end_ms` bound the
  interval; the row is not click-time evidence.
- Withdraw-and-return activity with zero net change emits no row. That absence does not
  prove that no interaction occurred. Exact click/slot chronology remains out of scope.
- Capability deltas are excluded from the player residual whether their raw row queued
  or was rejected, preventing machine traffic from being attributed to a viewer.
- One open participant → attributed `ADD_ITEM`/`REMOVE_ITEM`. Multiple participants →
  one `[ambiguous]` observation (UNKNOWN actor endpoint, candidates preserved in
  `raw_data`) rather than N rows manufacturing quantity.
- Ender Chest menus use `EnderChestInteractionTracker` on both loaders. The tracker
  diffs the player-owned `PlayerEnderChestContainer` at open/close boundaries and
  emits signed `ADD_ITEM_ENDER`/`REMOVE_ITEM_ENDER` observations. The endpoint has
  no world coordinates; the observation keeps the player's last finite server
  position as context and stores SQL `NULL` when no position is known, while the
  durable external key is the player's UUID. Menu-switch and shutdown fallbacks
  never substitute world origin coordinates.
- Container resolution scans `menu.slots` for a `BlockEntity`-backed `Container`;
  double chests (`CompoundContainer`) recover the clicked position from
  `RightClickBlock` in the same tick, choose a deterministic physical anchor, and
  alias the partner half. A new standalone session at a former partner position
  retires that stale alias before opening its watch. If a single-container watch
  overlaps a new double-chest watch, close resolution keeps each viewer on its
  recorded key and capability deltas credit both live watches. Menus without a
  block-entity container (crafting grids and anvils) are not watched by the block
  container tracker; Ender Chest menus are handled by the player-owned tracker above.

### Command-toggled container inspection (Issue 10)

- `/ig inspect` stores only per-player UUID state in `InspectionService`; it is cleared on
  logout and server stop and is not persisted.
- `InspectionListener` handles `PlayerInteractEvent.RightClickBlock` at `HIGHEST` priority.
  It acts only when inspection is active, the player still has permission level 2, and the
  clicked block entity implements `Container`. It submits the flow-browser query first; only
  an accepted query cancels the click with `InteractionResult.SUCCESS`, which prevents both
  the normal container GUI and held-item use path from running. A rejected query preserves
  the ordinary container interaction instead of leaving the player with neither screen.
- Fabric's `FabricNativeAuditEventListener` applies the same decision through
  `UseBlockCallback`: it checks the server-side `Container`, calls the shared
  `FlowBrowserService.openContainer`, and returns `SUCCESS` only after the query is accepted.
  The Fabric disconnect callback clears that player's inspection state; server stop clears
  any remaining state. This follows Fabric's documented callback contract: listeners run
  until one returns a non-`PASS` `InteractionResult` ([Fabric 1.21.1 event guide](https://github.com/FabricMC/fabric-docs/blob/main/versions/1.21.1/develop/events.md)).
- Because the click is cancelled before `ContainerSessionListener` records it, inspection
  is not container-transfer evidence. The opened `FlowBrowserMenu` contains only a
  `SimpleContainer`, so its Open/Close lifecycle cannot create a block-entity-backed watch.
- Unsupported blocks and inactive/disabled inspection return `PASS`-equivalent vanilla
  behavior: the event remains uncancelled and the normal interaction pipeline proceeds.

### Expanded Query UX
- `/ig trace player <playerName>`: reconstructs all item transfers, container events, and ground movements involving a player.
- `/ig trace container <x> <y> <z>`: reconstructs item ingress and egress for a container at coordinates.
- `/ig trace item <query>`: resolves string queries by numeric ID, item registry ID, or custom name.
- `/ig lookup filters <filter1> ... <filter5>`: applies GriefLogger's `name.value`
  action, user, include, exclude, time, and required radius filters to native
  audit rows. The radius is a bounded cube around the issuing player and runs
  asynchronously on the read-only query worker.

## Transformation Lineage (Phase 9)

Item transformations (identity shifts) are tracked in `ig_item_transformations`:
- Records transitions linking source fingerprint to result fingerprint (`ANVIL_RENAME`, `CRAFTING`, `SMELTING`).
- Captured via `TransformationEventListener` (`AnvilRepairEvent`, `ItemCraftedEvent`, `ItemSmeltedEvent`).
- Persisted asynchronously via `InternalObservationService` with a memory-bounded queue (10,000 capacity).
- Surfaced chronologically in item trace timelines as `[TRANSFORMATION <type> <- <source>]` hops.

## Database Invariant Auditing & Diagnostics (Phase 10)

`AuditService` enforces ItemGraph's core invariants asynchronously:
1. **Quantity Conservation**: each observation's active allocations total at most its evidenced quantity. Each active edge has SOURCE and DESTINATION allocation sums equal to its edge quantity; those allocation observations must be linked evidence with the edge fingerprint and matching source/destination actor endpoints. Unsupported allocation roles are invalid.
2. **Positivity**: quantities on observations, edges, and allocations are strictly positive ($amount > 0$).
3. **Relational Graph Integrity**: zero orphaned allocations and zero missing edge endpoint nodes; active edges with missing or mismatched role allocations, evidence, fingerprints, actions, or endpoints are reported.
4. **Lifecycle State Consistency**: validates observation `correlation_status` against active allocations.
5. **Temporal Validity**: each active edge's start/end timestamps must equal its cited source/destination observation timestamps, and source evidence cannot occur after destination evidence.

The `/ig audit` command runs this engine on the query worker and outputs a comprehensive integrity report. `/ig status` reports active/superseded edges, internal queue throughput/drops, capability queue rejections, transformation totals, active tracked entities, and continuity matches; its database count/checkpoint reads also run on the query worker.

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

The GriefLogger source bridge is disabled by default. ItemGraph's native
observation service, storage, correlation, and query paths do not probe or read a
GriefLogger database. When an operator enables migration mode, ItemGraph uses
read-only source connections for supported-row sync and explicit history import.

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

**JDBC driver provisioning**: `sqlite-jdbc` is bundled in the ItemGraph jar via
`jarJar` (version range `[3.40.0.0,4.0.0.0)`, prefer `3.46.1.0`) and MariaDB
Connector/J `3.5.7` is bundled for both MySQL and MariaDB network storage. Production
boots standalone and the dedicated GriefLogger-compatible artifact removes only the
SQLite copy so GriefLogger remains the sole provider of `org.sqlite.*`. Dev
runs launch the mod from `build/classes`, so the project's jarJar contents never
materialize — `build.gradle` adds the driver to `additionalRuntimeClasspath` only when
no jar in `run/mods` embeds `sqlite-jdbc` (adding it unconditionally alongside such a
mod crashes module resolution with a duplicate `org.xerial.sqlitejdbc` module).

## Suggested persistence model

A relational database is appropriate for the MVP because the workload includes:

- indexed event queries
- time-window searches
- item fingerprint filtering
- joins by player/container
- durable append-oriented storage

SQLite remains the default for a simple local deployment. MySQL/MariaDB is the
network option for larger installations; the dialect layer preserves the same
constraints, ordering, migrations, and read-only query separation instead of
duplicating repository implementations.

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
- identifying the caller/cause of UNKNOWN-endpoint capability transfers when a supported API exposes it
- faction-aware visibility
- richer graph UI
- exportable moderation case reports
