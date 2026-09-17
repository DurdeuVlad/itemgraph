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

Where item entity identity is available, correlation should become stronger.

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

## Automated coverage as of Phase 10

Run with `./gradlew test` (or `java -classpath "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain test`). All suites use a real SQLite file in a JUnit `@TempDir` with the real migrations applied — no mocked database, because a projection that drops a row on a LEFT-vs-INNER join mistake is exactly the class of bug these tests exist to catch.

| Suite | Covers |
| --- | --- |
| `ItemEntityTrackerTest` | Phase 8 in-memory item entity tracking: drop/pickup tracking, spatial matching, temporal expiration, and tracking counters (4 tests) |
| `ItemEntityCorrelationTest` | Phase 8 authoritative `ItemEntity` UUID correlation boost to 0.9990 confidence and narrative continuity citation (2 tests) |
| `PlayerAndContainerTraceTest` | Phase 8/9 player trace, container coordinate trace, dynamic string fingerprint resolution, and transformation lineage surfacing (5 tests) |
| `AuditServiceTest` | Phase 10 invariant auditing: conservation over-allocation detection, non-positive quantity detection, orphaned allocations, invalid node endpoints, and status consistency (5 tests) |
| `QuantityFlowTest` | Phase 7 stack-aware quantity flow: 1-to-many splits, many-to-1 merges, partial transfers, open/closed window semantics, over-capacity protections, competing candidate penalties, idempotency, restart continuity, and rollback atomicity (16 tests) |
| `TransformationEventListenerTest` | Phase 9 item transformation events: anvil rename/repair, crafting matrix ingredients with fallback, smelting, client-side guards, and empty stack handling (12 tests) |
| `ArmorStandEventListenerTest` | Phase 8B armor stand interactions: main-hand/off-hand equip, empty-hand unequip, empty stand handling, non-armor-stand and client-side guards (7 tests) |
| `InternalObservationServiceTest` | Phase 9 internal observation service: 10,000 item capacity bound & backpressure, multi-threaded concurrent enqueue, shutdown synchronous flush to SQLite, background worker persistence, armor stand node endpoints, and fingerprint dedup (7 tests) |
| `QueryDispatcherTest` | Async query marshalling seam: database disconnection failure rendering (shutdown race protection), canStillReport 3-branch lifecycle checks (server stopped, disconnected player, active player, console), off-thread synchronous execution, and idempotent shutdown (10 tests) |
| `ItemGraphConfigTest` | Mod configuration: default values (including 300s ground bridge window), config paths, range constraints [1, 86400], and NightConfig correction/clamping behavior (5 tests) |
| `QueryFormatterTest` | Forensic text rendering: UTC time formatting, duration formatting (ms, s, m, h, negative), 4-decimal confidence precision, OBSERVED/INFERRED labeling, trace limit capping & truncation notices, audit reports, and error messages (14 tests) |
| `ItemEntityEventListenerTest` | Phase 8A item entity drop and pickup tracking: ItemTossEvent, ItemEntityPickupEvent.Post, empty stack and null entity guards (6 tests) |
| `CorrelationEngineTest` | ground bridging, scoring, temporal ordering, the MVP chain end to end (Phase 5/7/8) |
| `ItemCanonicalizerTest` | fingerprint determinism and DataComponent decoding (Phase 3) |
| `NodeManagerTest` | node identity resolution (Phase 4) |
| `GriefLoggerAdapterTest`, `IngestionServiceTest` | read-only ingestion, checkpoints, flow direction (Phases 2, 4, 5) |
| `DatabaseManagerTest` | migrations (V1–V8), dedup constraint, read-only query connection |
| `EventQueryServiceTest` | found/not-found, dangling references rendering as "no such row", OBSERVED labelling |
| `ExplainQueryServiceTest` | evidence resolved back to observation detail, no cross-edge evidence leakage, unjustifiable edges reported, evidence cap |
| `TraceQueryServiceTest` | OBSERVED/INFERRED merge order, limit capping, truncation keeping the earliest hops, window containment vs edge overlap, per-line provenance |

Total automated test count: 167 tests, 100% passing.

