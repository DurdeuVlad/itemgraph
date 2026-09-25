# Test Plan

## Objective

ItemGraph must be reliable enough for moderation disputes.

Testing should focus on correctness, explainability, quantity conservation, temporal ordering, persistence, and performance.

## Test environments

- unit tests
- integration tests where feasible
- staging server controlled scenarios

Never use production as the primary test environment.

## Core correctness tests

### Simple transfer

```text
Chest A -> Player
```

Expected:

- two observations or equivalent direct evidence
- one high-confidence inferred edge
- explainable evidence IDs

### Chest -> player -> chest

```text
Chest A -> Alice -> Chest B
```

Expected:

- monotonic timestamps
- correct intermediate node
- no fabricated movement

### Drop -> pickup

```text
Alice -> Ground -> Bob
```

Where item entity identity is available, correlation should become stronger. A ground row is
created only after the ItemEntity is confirmed in the level; a canceled toss is UNKNOWN-
destination evidence and a canceled death drop has no ground endpoint.

### Cross-source copies

Seed one physical five-item drop and pickup as both `ITEMGRAPH_INTERNAL` and `GRIEFLOGGER`
rows with shared unique ItemEntity UUIDs:

- exactly one active five-item inferred edge is allowed;
- the edge evidence cites all four raw source rows, including when the GriefLogger copy
  arrives after an edge has already been inferred;
- the corroborating rows never contribute extra capacity.

Repeat with compatible signatures but no shared entity UUID. Both events must be
`SOURCE_AMBIGUOUS`, with no inferred allocation. A canceled ItemGraph drop attempt that
conflicts with a GriefLogger ground-drop row must also remain ambiguous. A pre-V11
duplicate-edge fixture must retain its old edge/allocation rows as superseded while
rebuilding active capacity from one canonical source group.

### Container session interval

A viewer opens a container at total 10, the total changes to 7, and the viewer closes:
`REMOVE_ITEM 3` has `timestamp_ms` and `timestamp_end_ms`, and query output identifies it as a
session net delta. A 10 → 5 → 10 withdraw-and-return before close emits no net row; this is a
known coverage limit, not evidence that no interaction occurred.

### Stack split

```text
Alice receives 64 iron
Alice deposits 20 iron into Chest B
Alice retains 44
```

Expected:

- no need for per-ingot UUIDs
- conservation respected

### Stack merge

```text
Chest A -> Alice: 20 iron
Chest B -> Alice: 30 iron
Alice -> Chest C: 50 iron
```

Expected:

- valid quantity-flow reconstruction
- no forced individual lineage

### Named item

Use a distinct named armor piece.

Expected:

- metadata-aware matching
- higher confidence than type-only match

### Rename

```text
"Old Helmet" -> anvil -> "Old Reliable"
```

Expected:

- transformation preserved where event coverage allows

### Metadata change

Examples:

- damage changes
- trim applied
- enchantment applied

Expected:

- transformation or compatible state transition, not false unrelated identity

### Ambiguity

Two identical items move near the same time.

Expected:

- multiple plausible candidates or ambiguous state
- no fake certainty

### Impossible ordering

Destination observation precedes source.

Expected:

- edge rejected

### Missing event

One half of a transfer is absent.

Expected:

- unresolved observation
- no invented destination/source

### Duplicate ingestion

Same GriefLogger event imported twice.

Expected:

- exactly one canonical source observation

### Restart persistence

Create evidence, restart staging, query again.

Expected:

- data preserved
- checkpoints preserved
- no duplication

## Armor stand incident test

Controlled scenario:

1. Place named armor on an armor stand.
2. Remove one piece.
3. Move it through player inventory.
4. Store it in a chest.
5. Trace it.

Expected:

- armor-stand event captured if supported
- path reconstructed
- explanation available

## Coffer/modded inventory test

Reproduce the server's actual coffer inventory behavior.

Scenarios:

- move item into coffer
- remove item
- client display glitch if reproducible
- query server-side evidence

Goal:

Verify that ItemGraph reports authoritative inventory evidence independent of client rendering.

## Performance tests

Measure:

- event ingestion rate
- queue depth
- DB write latency
- CPU impact
- trace query duration
- worst-case bounded query duration
- memory usage

Test with realistic server event volumes.

## Invariants

### Quantity

Without creation/transformation:

```text
attributed_outgoing <= available_compatible_incoming
```

### Time

A path must be non-decreasing in time.

### Evidence

Every inferred edge references at least one source observation and should normally reference the observations it correlates.

### Source integrity

GriefLogger database remains unchanged by ItemGraph tests.

## Acceptance criteria for MVP

The MVP is accepted when staging can demonstrate:

### Named item path

```text
Chest A
 -> Player A
 -> Ground
 -> Player B
 -> Chest B
```

with:

- timestamps
- raw observations
- inferred edges
- confidence
- `/ig explain`

### Quantity flow

A stack split/merge scenario with no per-item UUIDs and correct conservation.

## Automated coverage for M5 and M6 issue #8

Run with `./gradlew test` (or `java -classpath "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain test`). All suites use a real SQLite file in a JUnit `@TempDir` with the real migrations applied — no mocked database, because a projection that drops a row on a LEFT-vs-INNER join mistake is exactly the class of bug these tests exist to catch.

| Suite | Covers |
| --- | --- |
| `ItemEntityTrackerTest` | in-memory entity tracking: drop/pickup links, unique-only spatial/time matches, ambiguity rejection, expiry, and counters |
| `ItemEntityCorrelationTest` | authoritative ItemEntity UUID correlation boost to 0.9990 and continuity citation (1 test) |
| `PlayerAndContainerTraceTest` | player/container traces, fingerprint resolution, and transformation lineage (4 tests) |
| `AuditServiceTest` | active-edge conservation, non-positive quantities, orphaned allocations, invalid endpoints, correlation status, source-group consistency, and invalid edge-state detection (6 tests) |
| `QuantityFlowTest` | stack splits/merges, partial transfers, windows, capacity limits, competing candidates, idempotency, restart continuity, and rollback atomicity (19 tests) |
| `TransformationEventListenerTest` | anvil rename/repair, crafting matrix fallback, smelting, client guards, and empty-stack handling (12 tests) |
| `ArmorStandEventListenerTest` | Phase 8B armor stand interactions: main-hand/off-hand equip, empty-hand unequip, empty stand handling, non-armor-stand and client-side guards (7 tests) |
| `InternalObservationServiceTest` | bounded queue/backpressure, concurrent enqueue, shutdown flush, persistence, endpoint mapping, canceled-drop provenance, and fingerprint dedup (14 tests) |
| `QueryDispatcherTest` | text/data async marshalling, entity-less RCON delivery and interrupt restoration, delivery-time permission checks, inline shutdown guards, read-only connections, bounded-queue rejection, failure callbacks, active SQLite interruption, pre-statement cancellation, server-thread RCON acknowledgement, and wrapper-free RCON errors (23 tests) |
| `ItemGraphConfigTest` | default values, config paths, range constraints, and NightConfig correction/clamping (5 tests) |
| `QueryFormatterTest` | forensic labels, confidence/time formatting, session and queue-recovery intervals, source-group labels, trace limits, audit reports, and errors (16 tests) |
| `ItemEntityEventListenerTest` | successful-spawn-only ground drops, canceled toss/death evidence, pickup quantity, partial-pickup handling, empty/null guards (10 tests) |
| `CorrelationEngineTest` | ground bridging/scoring, cross-source confirmed/ambiguous groups, canceled-source conflicts, legacy edge supersession, temporal ordering, and MVP chain (24 tests) |
| `ItemCanonicalizerTest` | fingerprint determinism and DataComponent decoding (Phase 3) |
| `NodeManagerTest` | node identity resolution (Phase 4) |
| `GriefLoggerAdapterTest`, `IngestionServiceTest` | read-only ingestion, checkpoints, flow direction, and concurrent shared-connection transaction isolation (5 + 10 tests) |
| `DatabaseManagerTest` | migrations V1–V11, interval/group/edge-state schema, dedup constraints, read-only query connection |
| `EventQueryServiceTest` | found/not-found, dangling references rendering as "no such row", OBSERVED labelling |
| `ExplainQueryServiceTest` | evidence resolved back to observation detail, no cross-edge evidence leakage, unjustifiable edges reported, evidence cap, and SQL NULL confidence rejection (8 tests) |
| `TraceQueryServiceTest` | OBSERVED/INFERRED merge order, session intervals, limit capping, exact dimension/coordinates and player labels, ambiguous node/fingerprint/numeric-ID candidates, target-ID pinning, and bidirectional tie-safe cursor pages (20 tests) |
| `ContainerCapabilityWrapperTest` | capability action labels, UNKNOWN caller/endpoints, open-session reconciliation, and queue rejection recovery (9 tests) |
| `ContainerInteractionTrackerTest`, `ContainerSessionListenerTest` | open/close net deltas, timestamp intervals, multi-viewer ambiguity, capability-credit subtraction, and zero-net limitation (14 + 1 tests) |
| `V9InternalObservationDedupTest`, `V10InternalDedupEntityUuidTest` | partial-index, UUID, destination-sensitive dedup, NULL-UUID preservation, and V11 idempotence (5 + 6 tests) |
| `ItemEntityEventListenerPartialPickupTest` | pending-pickup resolution: emit on reduced count, drop on removal/expiry, keep while unchanged |
| `FlowBrowserMenuTest` | vanilla six-row menu type, textual provenance/confidence/evidence labels, every click category rejected or handled as navigation/detail only, and permission recheck (2 tests) |
| `ItemGraphCommandsGuiTest` | `/ig gui` item/player/container command shape, explicit dimension argument, quoted `"id:<id>"` parsing, and stale empty-cursor handling (3 tests) |

Total automated test count: **259 tests, 0 failures, 0 skipped** (`./gradlew clean build`, 2026-09-25).

## M6 issue #8: vanilla flow browser verification

Automated coverage includes `TraceQueryServiceTest` forward and reverse keyset pages across
same-timestamp observations, transformations, and inferred edges; exact player and
explicit-dimension container resolution, duplicate-target ambiguity, and ID-pinned continuation;
`QueryDispatcherTest` read-only off-thread page delivery; and
`FlowBrowserMenuTest` rejection of item-moving click types and permission loss. Run these
with `./gradlew test`.

The delivery staging check is still required and has not been performed for this feature.
On a dedicated NeoForge 1.21.1 server with a vanilla client, open all three `/ig gui`
targets, navigate both directions, inspect observation, transformation, and inferred-edge detail,
and attempt pickup,
placement, shift-click, drag, throw, swap, clone, and pickup-all actions while verifying the
server inventory is unchanged. Repeat representative checks with GriefLogger absent and
present. Do not perform the GriefLogger-present run against the existing staging database
until it can be safely isolated; the earlier V11 startup smoke is not GUI verification.

## Historical live server results (pre-V11 staging `run/`)

The rows below document the earlier schema-V10 staging build. They are retained as historical
evidence, not as verification of V11 source groups, canceled-spawn handling, interval output,
or queue-rejection recovery. A separate V11 staging run must record those cases before this
repair is merge-ready. These historical runs used staging only and treated GriefLogger as
read-only; production was not used.

| Scenario | Expected | Observed rows |
| --- | --- | --- |
| Single-viewer chest withdraw (10 cobble) | one `REMOVE_ITEM` container→player | id 633: `REMOVE_ITEM` 10, correct endpoints |
| Single-viewer deposit (10 cobble) | one `ADD_ITEM` player→container | id 644: `ADD_ITEM` 10 |
| Two viewers on merged double chest, one withdraws 5 | one ambiguous row, no fabricated target | id 803: `REMOVE_ITEM` 5, target NULL, `raw_data.ambiguousActorCandidates` = both players |
| Withdraw 3 while hopper drains same chest (V10) | residual attribution only | historical id 905: `REMOVE_ITEM` 3 to player; 72 legacy `HOPPER_EXTRACT` rows → UNKNOWN |
| Real dev client shift-click 20 cobble (V10) | session net delta row | historical id 2560: `REMOVE_ITEM` 20 → player Dev; no interval field in V10 |
| Toss + re-pickup 10 cobble | DROP + PICKUP linked by entity uuid | ids 2764/2765: `DROP_ITEM`/`PICKUP_ITEM` 10, same `item_entity_uuid` |
| Partial pickup (1 of 10 fits) | `PICKUP_ITEM` amount 1 | id 2774: amount 1, `raw_data.detection=pre_post_pairing` |
| `/kill` with 3 item types in inventory | `DEATH_DROP` per stack | ids 2778–2780: diamond 7, iron 12, emerald 3, distinct entity uuids |
| Boot without GriefLogger jar | clean DISABLED, internal persistence | `GriefLogger integration: DISABLED` + `database initialized successfully`; ids 2787–2792 persisted |

Comparative note: for the same sessions GriefLogger attributed 10 units for the 5-item
dual-viewer move (one row per viewer) and misattributed hopper churn to the player;
ItemGraph kept quantity conservation in both cases.

## V11 staging startup smoke test (loopback)

On `E:\\Github2\\itemgraph\\run`, `./gradlew runServer` bound to `127.0.0.1:26417`
after temporarily setting `server-ip=127.0.0.1`. Startup applied schema V11 successfully;
`/run/itemgraph/itemgraph.db` read-only inspection after stop showed schema version 11,
2,793 observations, 0 source groups, 52 match checks, 10 active edges, and the V11
`timestamp_end_ms`/destination-sensitive index. The original blank `server-ip` was restored,
and no ItemGraph staging Java process remained.

No player movement scenarios were run with GriefLogger enabled: its startup logged database
preparation, so I stopped before Mineflayer actions that could create GriefLogger rows. The
GriefLogger `run/database.db` mtime remained 2026-09-20 18:10:47 UTC and no WAL/SHM sidecar
was present after stop, but no pre-start hash was captured; this is not a hash-verified
no-write claim. Standalone live movement scenarios remain blocked until GriefLogger can be
isolated without modifying its existing database.

