# Test Plan

## Objective

ItemGraph must be reliable enough for moderation disputes.

Testing should focus on correctness, explainability, quantity conservation, temporal ordering, persistence, and performance.

## Test environments

- unit tests
- integration tests where feasible
- staging server controlled scenarios

Never use production as the primary test environment.

## Local NeoForge operations check (2026-09-30)

- Runtime: Minecraft 1.21.1, NeoForge 21.1.248, ItemGraph 0.3.2, Java 21;
  no other mods and no GriefLogger.
- A fresh local server on loopback port 25688 started with a separate temporary
  game directory and SQLite database. Startup upgraded that database from schema
  V17 to V18 and logged the GriefLogger integration as disabled. The server was
  stopped and the listener was verified closed.
- `InternalObservationServiceTest.workerPersistsConcurrentQueueLoadWithoutDuplicateOrLostRows`
  submits 2,000 observations from eight producer threads to the bounded worker,
  then checks that SQLite contains exactly 2,000 rows and 2,000 distinct durable
  `ingest_event_uuid` values.
- `./gradlew.bat :neoforge:test :fabric:test --no-daemon --max-workers=1 --rerun-tasks`
  passed: NeoForge 394 tests (0 failures, 3 skipped) and Fabric 29 tests
  (0 failures, 0 skipped). The skipped cases require local MySQL/MariaDB service
  variables; CI provisions both database services.
- This validates local SQLite queue persistence and worker startup. It does not
  measure tick-time impact from real player events, compare GriefLogger's queue
  scheduler, or validate a live MySQL/MariaDB endpoint.

## Native queue wakeup and isolated NeoForge load check (2026-10-01)

- `InternalObservationServiceTest.auditQueueWakeDoesNotPersistBeforeConfiguredServerTickCadence`
  verifies producer wakeups do not bypass the configured flush cadence: with a
  two-tick test cadence, the row stays absent after tick 1 and persists after
  tick 2. `ItemGraphOperationalSettingsTest.legacyOperationsOverloadRetainsTheTwentyTickDefault`
  verifies the compatibility overload retains the production default.
- `OperationalLoadGameTests.nativeAuditQueueBurstPersistsOnWorkerWithoutBlockingServerThread`
  runs on a fresh local NeoForge 21.1.248 / Minecraft 1.21.1 GameTest server with
  ItemGraph 0.3.2 and no GriefLogger. It queues 8,000 native audit events in 20
  server-thread batches of 400, checks every batch remains below 50 ms, then
  allows 20 seconds of wall-clock worker time before checking counters and the
  SQLite ledger. The latest 2026-10-01 run logged a 20-tick flush cadence,
  accepted and durably persisted all 8,000, dropped 0, ended with 0 queued
  records, measured 47.234698 ms total enqueue time, and measured 7.694 ms for
  the slowest server-thread batch. Peak queue depth was 6,815 of 10,000 audit
  queue slots. All three NeoForge GameTests passed.
- Fabric's `OperationalQueueGameTests.endServerTickFlushPersistsAcceptedAuditEvents`
  uses `GameTestHelper.runAtTickTime` to poll once per configured queue cadence
  and reads the ledger at most once early when the global persisted counter
  delta reaches the probe size, then again at the 10-second wall-clock deadline
  if needed. A queue drop triggers one immediate ledger read and failure. Its
  GameTest tick timeout is 300,000 ticks, providing headroom for the worker
  window; tick
  timeout and worker deadline use separate clocks, so extreme tick acceleration
  can still end the test first.
  It checks 32 matching durable rows, the persisted counter delta, zero drops,
  and reports queue depth plus callback/cadence details on failure. A single
  class-level end-tick callback avoids accumulating global listeners across
  repeated test runs. An earlier local run on 2026-10-01 failed the former
  225-logical-tick polling probe after 32 end-tick callbacks but before any
  matching rows became durable. After the revision, the grouped local run on
  2026-10-02 passed all three Fabric GameTests and Fabric unit tests; the queue
  probe saw all 32 rows and completed after its early ledger check. A first
  revision using `CompletableFuture.delayedExecutor` plus `server.execute` did
  persist the rows but left the GameTest in its running batch, so it was stopped
  and replaced with native `GameTestHelper.runAtTickTime` scheduling.
- The same temporary game directory was restarted against the same SQLite
  database. NeoForge reopened schema version 20, persisted the next 8,000 events
  with 0 drops and an empty queue, and a read-only SQLite check found exactly
  80,000 total probe rows. This verifies restart durability for the probe
  ledger; the probe rows were preserved.
- The development-only GameTest is in NeoForge `src/main/java`; NeoForge jar
  tasks exclude the GameTest package. It writes uniquely numbered probe rows to
  the isolated temporary SQLite database so a second server start can verify
  persistence without clearing or modifying prior evidence.
- These are controlled server-thread queue checks, not a realistic player-action
  replay or a GriefLogger throughput comparison. They do not establish a
  universal tick budget. The disposable MySQL/MariaDB heartbeat checks run in CI.

## Operational config lifecycle matrix (2026-10-01)

- A fresh temporary NeoForge 21.1.248 / Minecraft 1.21.1 server and a fresh
  temporary Fabric Loader 0.16.9 / Minecraft 1.21.1 server each ran ItemGraph
  0.3.2 on loopback only. EULA acceptance, RCON credentials, configs, worlds,
  logs, and SQLite databases stayed inside their respective temporary
  directories. Neither server had GriefLogger installed or player workload.
- Both servers started with `idlePollMs=250`, `maxBatchSize=100`,
  `networkHeartbeatMs=30000`, and `captureEnabled=true`. Their ItemGraph config
  files were changed to 550 ms, 250 rows, 60,000 ms, and `false`; `query.max_page_size`
  was set to 25. Running `/reload` left the worker controls at the original
  values, confirming that these server settings are startup snapshots.
- Each server then shut down cleanly and restarted against the same temporary
  config and database. NeoForge loaded `idlePollMs=550`, `maxBatchSize=250`,
  `networkHeartbeatMs=60000`, `captureEnabled=false`; Fabric loaded the same
  values. Both opened ItemGraph schema version 20. The queue remained empty and
  no records were dropped. After `/ig status` exposed effective settings, the
  follow-up live run verified `maxPageSize=25`, `databaseConnectionTimeoutMs=5000`,
  and `useIndexes=true` on both loaders.
- This verifies representative worker, capture, query-cap, and status settings
  across startup, `/reload`, and restart. It does not cover every invalid config
  field or compare queue cadence with GriefLogger; both remain outside this
  runtime matrix.

## NeoForge invalid server-config startup check (2026-10-01)

- `python tools/test_neoforge_invalid_config_startup.py` creates a fresh
  separate temporary NeoForge GameTest directories for
  `query.max_page_size=101`, `general.database_port=0`, and an empty
  `general.database_path`, `general.grieflogger_database_path`, and an
  unsupported `general.database_backend`.
- Every probe requires the exact key and rejection reason in the startup exception,
  a failed GameTest server task, no ItemGraph database initialization, and the
  original invalid setting unchanged after NeoForge fills missing config
  defaults. This catches
  the prior behavior where NeoForge rewrote `database_port=0` to `1`.
- The 2026-10-01 isolated run met those conditions for all five settings. The Windows
  Gradle batch launcher returned process code 0 despite logging `BUILD FAILED`,
  so the script validates task output and the pre-database failure point rather
  than relying on that wrapper exit code.
- The unsupported-backend probe uses a JDBC URL containing a marker credential
  and requires that marker to be absent from startup output.
- `DatabaseManagerTest.failedNetworkDatabaseDiagnosticsDoNotExposeEndpointsOrCredentials`
  and `DialectConnectionTest` check SQLState-only failures for initial and
  independent connections, connection methods, statements, result sets, and
  metadata result sets. Raw driver messages and cause chains are not retained.
- `FabricItemGraphConfigTest.validatesEveryNumericConfigRangeBeforeApplyingBackendSpecificSettings`
  uses temporary `itemgraph.properties` files to accept both boundaries and
  reject underflow, overflow, and non-integer input for all seven integer
  settings. Database port and timeout are validated even with the default
  SQLite backend. The local Fabric test run passed on 2026-10-01.

## Compatibility profile gate

Run `python tools/validate_grieflogger_profile.py` from the repository root.
The check is read-only and must pass before any M8 parity issue is marked
complete. It verifies the pinned GriefLogger 26.2 commit, all 18 audited source
actions, all 11 source tables, the canonical source-profile SHA-256, source-file
citations, parity-document references, and milestone issue references. CI also
reads the nine M8 issue records through the read-only GitHub API and verifies
their milestone and acceptance-criteria sections.

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

## Entity interaction cross-loader query/output test (2026-10-01)

NeoForge `EntityInteractionGameTests.serverInteractPacketPersistsEntityAttemptAndArmorStandOutcome`
and Fabric `EntityInteractionGameTests.serverInteractPacketPersistsEntityAttemptAndArmorStandOutcome`
send the same cow and armor-stand interaction packets through each loader's server
handler. After the worker flushes, both tests read `ig_audit_events` through the
shared `AuditEventQueryService` using a read-only connection and assert matching
actor UUID/name, dimension, exact block position, entity subject, timestamp presence,
target UUID, held-stack details, event counts, and `QueryFormatter` console output.
The database is also inspected directly to ensure one cow attempt, two armor-stand
packet attempts, two `interact_at` completions, and one direct inherited `Entity.interact`
unresolved result persist without duplicate method-result rows. Each loader snapshots every
`ig_observations` column in stable `id` order through a read-only connection before
dispatch and asserts that the complete encoded row set is unchanged after the replay.

This is isolated server GameTest evidence using a mock player and direct invocation
of the server interaction handler. The shared conformance fixture checks the
same event-type multiset and formatter contract in both loader runs; it invokes
`QueryFormatter` directly rather than dispatching `/ig lookup`. It does not cover
real client transport, a full Minecraft server restart, or live canceled/repeated
interactions. The persistence restart fixture for an `INTERACT_ENTITY_DENIED` event is
`InternalObservationServiceTest.entityInteractionOutcomeSurvivesDatabaseRestartWithoutQuantityObservation`.

## Entity interaction differential and conservation oracle (2026-10-01)

The differential baseline is intentionally split by source authority:

| Baseline/replay | Expected and observed result | Comparison conclusion |
| --- | --- | --- |
| Exact GriefLogger `1.2.10-1.21.1` release fixture and checksum-verified NeoForge jar | No `INTERACT_ENTITY` action ID or entity-interaction writer exists in the published jar. | Published-binary parity for entity clicks is absent; ItemGraph must not claim it. |
| Pinned GriefLogger `26.2` source at commit `d315098b3f37317a5cddfbd75086f4f912f16a83` | Its armor-stand return hook emits `INTERACT_ENTITY` only for `SUCCESS` or `SUCCESS_SERVER`. The source is inspected but is not loaded or executed as a test dependency. | This is a newer source reference, not the `1.2.10-1.21.1` release contract. |
| ItemGraph `1.21.1` NeoForge and Fabric isolated GameTests | A cow produces one callback-only attempt. Armor-stand boot equip and empty-hand unequip packets each preserve one attempt and one handled `interact_at` result. Separately, each test invokes inherited `ArmorStand.interact` directly, asserts its `PASS` return, and expects one `INTERACT_ENTITY_UNRESOLVED` row with `method=interact`; that direct invocation is the method-hook check and is not counted as a packet replay. Both loader runs assert the same target support, held-stack identity/count, normalized query fields, and formatted output. | Attempts and method results are ItemGraph extensions; `EntityInteractionEvidence` does not claim the newer source has the same attempt/result schema. |
| ItemGraph quantity-flow ledger before/after those clicks | Both loader tests snapshot every `ig_observations` column in stable `id` order through a read-only connection before packet dispatch and require the complete encoded row set to be identical after the audit worker flush. | The replay neither adds nor mutates quantity rows; result events describe method return values only. |

This is a source-and-runtime differential report, not an executable GriefLogger
side-by-side test: the published artifact has no entity-interaction writer, and
the pinned 26.2 source was not loaded into either replay. The compatibility
registry therefore keeps this mapping unresolved against the release and labels
the richer behavior as an ItemGraph extension. The NeoForge and Fabric runs are
local isolated server GameTests with mock players and direct server-handler
dispatch, as directed by the operator; they are not live-client transport tests.

The same GameTest exports six persisted item movement and projectile rows plus
seven audit events only after its durable read-only assertions pass. Its
`BREAK_BLOCK` report row is restricted to the successful source-water pickup at
the fixture position; the separate synthetic water-source/lava-result guard
probe remains a unit assertion and is excluded because GriefLogger cannot
produce it through a real bucket pickup.
`ItemGraphReplayReportFixture` queries `ig_observations` and the allowlisted
`ig_audit_events` columns through `DatabaseManager.openReadOnlyConnection()`;
the artifact contains profile-normalized action identities, fixture-relative
coordinates, namespaced subjects, and replay actor aliases, with no player
UUIDs, names, database row IDs, audit detail, or raw payloads. CI validates
separate native-only Fabric and NeoForge reports and uploads only the normalized
JSON. This export validates report
shape and redaction; it does not establish GriefLogger equivalence or complete
the paired replay, 24-hour soak, or rollback criteria in
#31. The raw schema-v3 report also contains an `AuditService.audit` summary from
one read-only transaction snapshot over the complete isolated GameTest
database. Every active edge must have SOURCE and DESTINATION allocation sums
equal to its amount, linked evidence with matching fingerprints, actions, and
actor endpoints, and no unsupported allocation roles; every observation must
remain within its quantity capacity. Edge time bounds must equal the source and
destination observation timestamps in forward order. Both the fixture and CI
normalizer require zero allocation, edge-time, positivity, orphan, endpoint,
and lifecycle violations. The normalized schema-v5 comparison report retains
this count-only summary without database IDs or violation details. Comparator
fixtures verify that profile-linked native extensions remain visible with an
issue URL and stable reason while passing the difference gate; unlinked field
mismatches, including quantity changes, remain failures.
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

CI integration tests and GameTests emit redacted performance JSON under
`ITEMGRAPH_PERFORMANCE_REPORT_DIR`. `tools/validate_itemgraph_performance_report.py`
checks the pinned report schema, loader/backend identity, exact durable row
counts, queue accounting, and existing server-thread submission ceilings.
`tools/test_validate_itemgraph_performance_report.py` covers malformed schemas,
rejection accounting, and the complete artifact set. CI uploads the validated
`itemgraph-performance-reports` artifact for 14 days.

The nine-report set covers NeoForge SQLite burst (8,000 events), Fabric SQLite
flush (32 events), NeoForge and Fabric each against disposable MySQL and MariaDB
(512 synthetic events, 20 concurrent raw-JDBC ledger lookups, and 20 registered
`/ig lookup radius.20` command queries per backend),
NeoForge shutdown saturation (10,000 accepted audit events, one explicitly
rejected over-capacity submission, worker-owned bounded drain, and exact durable
row verification), and a cross-loader SQLite correlation workload (500 accepted
observations, five passes over 50 drop/pickup pairs each, and 250 quantity-
conserving inferred edges). The correlation reports verify durable source rows,
finalized observations, edge totals, and source/destination allocation totals.
The network probes require both raw-JDBC and registered command-query overlap
with event submissions and include labeled synthetic automation and
modded-inventory events. The command workload runs Brigadier parsing, the shared
`ItemGraphCommands` lookup handler, JDBC query dispatch, and queued result
delivery with mocked Minecraft server/player objects. It returns matching
synthetic audit rows and reports redacted completion, failure, overlap, total,
maximum, and p95 dispatch-to-callback latency. This measures the application
lookup path on disposable CI databases; it does not measure live-client delivery,
real server tick impact, or third-party adapter behavior. An idle baseline,
adapter-specific load, and staging-derived latency/memory budgets remain open;
CI timings are measurements, not production budgets.

The performance reports snapshot process-wide metrics. Each queue GameTest now
stops and drains the prior worker, clears its counters, and restarts it before
measuring; each GameTest has its own batch. The validator requires the enqueue
sample count to equal this scenario's attempted-event count, and each network
report's query sample count to equal its 20 raw-JDBC plus 20 registered command
lookups. It requires all 20 command callbacks to complete successfully and at
least one callback to finish while submissions continue. Correlation reports
must contain exactly five successful passes and the expected durable quantity
allocations. This catches metrics accidentally carried in from another test.
Reports still contain one CI run per scenario; their latency and heap fields are
diagnostic rather than statistical regression baselines. `heap_used_bytes` is a
point-in-time snapshot, not peak memory or allocation rate. The latency histogram
reports coarse upper-bound buckets, so it cannot support narrow p95 regression
gates.

Two redacted CI artifacts show why those limits matter. Runs
[36944915206](https://github.com/DurdeuVlad/itemgraph/actions/runs/36944915206)
and [37011138142](https://github.com/DurdeuVlad/itemgraph/actions/runs/37011138142)
used the same pinned 8,000-event NeoForge burst, but the reported slowest
server-thread batch was 12.85 ms and 20.40 ms, respectively. Their NeoForge
MySQL query average was 30.82 ms and 42.04 ms, with histogram p95 upper bounds
of 50 ms and 100 ms. The first artifact also counted 8,025 enqueue samples for
8,000 events in the NeoForge queue report and 36 for 32 events in the Fabric
queue report; these mismatches exposed cross-test metric contamination. The
GameTests and validator now isolate and reject that condition.

GitHub documents each standard hosted runner job as a new virtual machine
(public `ubuntu-latest`: 4 CPUs and 16 GB RAM; [runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)),
so consecutive CI runs do not share a calibrated machine. [Google Benchmark's
guide](https://github.com/google/benchmark/blob/main/docs/user_guide.md) uses
warmups, repeated runs, and random interleaving, with [statistical
comparison](https://github.com/google/benchmark/blob/main/docs/tools.md) to
separate performance change from machine noise. [OpenJDK JMH](https://github.com/openjdk/jmh)
recommends a standalone harness setup for more reliable JVM measurements. These
practices support repeated, environment-matched staging runs for ItemGraph.
They do not supply ItemGraph's production budgets. Until those measurements
exist, CI gates the established 50 ms server-thread submission ceiling, exact
queue bounds, durability/loss accounting, and report-shape/workload invariants.
The separate five-second application command-query cancellation deadline is not
a measured command-latency budget; the CI fixture uses a 30-second callback
completion wait. CI does not invent persistence, correlation, query, or memory
budgets.

### Consolidated local validation (2026-10-02)

- One Gradle invocation ran `:neoforge:test`, `:neoforge:runGameTestServer`,
  `:fabric:test`, and `:fabric:runGameTest`. It completed successfully. NeoForge
  and Fabric each passed all 3 required GameTests. The NeoForge probe accepted
  8,000 events, measured a 9.299 ms slowest 400-event submission batch, peaked
  at 7,900 queued events, and verified exactly 8,000 matching durable rows,
  zero drops, and an empty queue. Fabric verified exactly 32 matching durable
  rows after its end-tick flush. NeoForge used a fresh temporary GameTest
  directory through `-PitemgraphGameTestDirectory`; the default project path is
  unchanged.
- `python -B tools/test_validate_itemgraph_performance_report.py` passed all 12
  validator cases, including rejection of a missing queue-depth measurement;
  `git diff --check` passed. Local GameTests did not write performance JSON
  because `ITEMGRAPH_PERFORMANCE_REPORT_DIR` was unset. Both network backend
  integration tests compiled and were skipped by their GitHub Actions-only
  guard. CI artifact upload and MySQL/MariaDB performance reports therefore
  remain unverified locally.
- These runs use ItemGraph 0.3.2 and build no distributable mod jar. They are
  isolated SQLite checks and do not establish staging latency or memory budgets.

### Consolidated local validation (2026-10-03)

- `:neoforge:test :neoforge:runGameTestServer :fabric:test :fabric:runGameTest`
  completed successfully without packaging a distributable mod JAR. JUnit XML
  reports 471 NeoForge tests and 67 Fabric tests, with zero failures/errors;
  six NeoForge and two Fabric tests were skipped. The four MySQL/MariaDB
  performance probes are intentionally GitHub Actions-only, so their registered
  command path still requires the next hosted CI run for runtime evidence.
- `python -B tools/test_validate_itemgraph_performance_report.py` passed all 28
  cases, including the registered command callback count, overlap, and latency
  invariants; `git diff --check` passed. The command report records aggregates
  only, with no player identity or evidence detail.

### Measured local performance profile

- Environment: Windows 11 x64, OpenJDK 21.0.12.1, Minecraft 1.21.1,
  NeoForge 21.1.248, ItemGraph only, and a fresh temporary SQLite database and
  GameTest directory for every sample. GriefLogger was absent.
- Five separate `:neoforge:runGameTestServer` runs passed all four registered
  GameTests. The 8,000-event burst persisted exactly 8,000 rows with zero drops
  on each run. The slowest measured server-thread producer batch ranged from
  3.95 ms to 5.39 ms; the existing 50 ms guard passed each run.
- Five separate
  `:neoforge:test --tests com.itemgraph.ingest.InternalObservationServiceTest.saturatedQueueDrainsOnWorkerDuringShutdownAndReportsExplicitRejection`
  executions were forced with `--rerun-tasks` so Gradle did not reuse a cached
  test result. Each accepted 10,000 audit records, rejected one extra record,
  persisted all 10,000 accepted rows, and emptied the queue. Measured graceful
  drain times were 626, 627, 650, 647, and 666 ms; batches remained capped at
  100 records.
- The shutdown report is a single elapsed-time sample per run, not a worst-case
  driver-stall test. The 1,000 ms CI threshold is a regression budget for this
  exact healthy SQLite fixture, derived from the 666 ms maximum observed across
  five local repetitions and checked against hosted CI. It does not bound a
  stalled database operation or establish production/staging latency or memory
  budgets. The server continues waiting for active writes to finish to preserve
  accepted evidence.

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
| `AuditServiceTest` | observation capacity, exact per-edge SOURCE/DESTINATION sums, evidence/action/fingerprint/endpoint linkage, unsupported-role and backwards-time rejection, non-positive quantities, orphaned allocations, invalid endpoints, correlation status, source-group consistency, and invalid edge-state detection (10 tests) |
| `QuantityFlowTest` | stack splits/merges, partial transfers, windows, capacity limits, competing candidates, idempotency, restart continuity, and rollback atomicity (19 tests) |
| `TransformationEventListenerTest` | anvil rename/repair, crafting matrix fallback, smelting, client guards, and empty-stack handling (12 tests) |
| `EntityInteractionEvidenceTest` | Armor stand method outcomes, target UUID, actor/position/dimension, and client-side suppression; interaction attempts are queried separately from completed results |
| `InternalObservationServiceTest` | bounded queue/backpressure, concurrent enqueue/stop admission race, 2,000-record worker persistence, shutdown flush, confirmed-loss and unknown-commit accounting, persistence, endpoint mapping, canceled-drop provenance, fingerprint dedup, UUID projection collision preservation, paired-ledger remapping, post-commit lost-ack replay idempotency for all three native ledgers, and failed network heartbeat accounting |
| `LegacyObservationArchiveTest` | migrations V3–V5 copy source identifiers and raw payload bytes before clearing obsolete active observation rows |
| `QueryDispatcherTest` | text/data async marshalling, entity-less RCON delivery and interrupt restoration, delivery-time permission checks, inline shutdown guards, read-only connections, bounded-queue rejection, failure callbacks, active SQLite interruption, pre-statement cancellation, server-thread RCON acknowledgement, and wrapper-free RCON errors (23 tests) |
| `ItemGraphConfigTest` | default values, config paths and metadata, strict NeoForge type/range rejection, and NightConfig default correction without clamping invalid supplied values (8 tests) |
| `ItemGraphOperationalSettingsTest`, `ItemGraphConfigTest`, `FabricItemGraphConfigTest`, `QueryLimitsTest`, `TraceQueryServiceTest.configuredPageCapConstrainsSqlBackedTracePages` | fail-closed operational bounds and policies, both-loader defaults/custom values, Fabric config re-read creates the next startup snapshot, NeoForge worker guard against live setting changes and application after worker stop, atomic rejection without changing the query cap, capture controls, query cap on command and SQL-backed GUI pages, queue idle poll/batch/flush-tick/heartbeat settings, and retention invariants |
| `FabricItemGraphPageDispatchTest` | executed `/ig page` and `/itemgraph page` failures for missing, malformed, and expired explicit sessions; expired-session owner-map cleanup; cross-player token denial without invalidating the owner's session; and permission-level-2 enforcement on both roots |
| `LegacyObservationArchiveTest`, `InternalObservationServiceTest.failedTransformationBatchIsRetainedAndShutdownLossIsCounted` | V3–V5 preserve legacy raw observation payloads before active-projection resets; transformation retries remain bounded and shutdown loss is counted |
| `ItemGraphStatusSecurityTest` | `/ig status` reports SQLite or MySQL/MariaDB backend and schema while redacting database paths, raw driver errors, and sentinel network host/database/user/password values |
| `QueryFormatterTest` | forensic labels, confidence/time formatting, session and queue-recovery intervals, source-group labels, trace limits, audit reports, and errors (16 tests) |
| `ItemEntityEventListenerTest` | successful-spawn-only ground drops, canceled toss/death evidence, pickup quantity, partial-pickup handling, empty/null guards (10 tests) |
| `CorrelationEngineTest` | ground bridging/scoring, cross-source confirmed/ambiguous groups, canceled-source conflicts, legacy edge supersession, temporal ordering, and MVP chain (24 tests) |
| `ItemCanonicalizerTest` | fingerprint determinism and DataComponent decoding (Phase 3) |
| `NodeManagerTest` | node identity resolution (Phase 4) |
| `GriefLoggerAdapterTest`, `IngestionServiceTest` | read-only ingestion, checkpoints, flow direction, worker termination before stop returns, and concurrent shared-connection transaction isolation (5 + 11 tests) |
| `GriefLoggerHistoricalImporterTest` | all 11 source tables, all 18 action IDs, opaque binary retention, source-byte immutability, supported-schema rejection, independent-writer concurrency, durable failed-run counts, per-table checkpoints, and idempotent replay |
| `DatabaseManagerTest` | migrations V1–V14, interval/group/edge-state schema, API source/external-key schema, historical import provenance/checkpoints, dedup constraints, read-only query connection, and independent writer configuration |
| `EventQueryServiceTest` | found/not-found, dangling references rendering as "no such row", OBSERVED labelling |
| `ExplainQueryServiceTest` | evidence resolved back to observation detail, no cross-edge evidence leakage, unjustifiable edges reported, evidence cap, and SQL NULL confidence rejection (8 tests) |
| `TraceQueryServiceTest` | OBSERVED/INFERRED merge order, session intervals, limit capping, exact dimension/coordinates and player labels, ambiguous node/fingerprint/numeric-ID candidates, target-ID pinning, and bidirectional tie-safe cursor pages (20 tests) |
| `ContainerCapabilityWrapperTest` | capability action labels, UNKNOWN caller/endpoints, open-session reconciliation, and queue rejection recovery (9 tests) |
| `ContainerInteractionTrackerTest`, `ContainerSessionListenerTest` | open/close net deltas, timestamp intervals, multi-viewer ambiguity, capability-credit subtraction, and zero-net limitation (14 + 1 tests) |
| `V9InternalObservationDedupTest`, `V10InternalDedupEntityUuidTest` | partial-index, UUID, destination-sensitive dedup, NULL-UUID preservation, and V11 idempotence (5 + 6 tests) |
| `ItemEntityEventListenerPartialPickupTest` | pending-pickup resolution: emit on reduced count, drop on removal/expiry, keep while unchanged |
| `FlowBrowserMenuTest` | vanilla six-row menu type, compact resolved-menu titles, textual provenance/confidence/evidence labels, every click category rejected or handled as navigation/detail only, and permission recheck (3 tests) |
| `ItemGraphCommandsGuiTest` | `/ig gui` item/player/container and `/ig inspect` command shape, explicit dimension argument, quoted `"id:<id>"` parsing, and stale empty-cursor handling (3 tests) |
| `InspectionServiceTest`, `InspectionListenerTest`, `ItemGraphCommandsInspectTest`, `FabricNativeAuditEventListenerTest` | per-player inspect state, deterministic command forms, permission denial, supported/unsupported clicks, browser-queue rejection fallback, NeoForge logout and Fabric disconnect handlers, and canceled-click isolation from session tracking |
| `ItemGraphCommandsHelpTest` | bare-root overview, every help topic, invalid-topic diagnostics, permission denial, registered-path/help synchronization, literal/player/item/dimension suggestions, published filter examples under both roots, aliases and bounds, value completion, page-session expiry, and vanilla `ClientboundCommandsPacket` command-tree encode/decode (expanded in #24) |
| `FabricItemGraphCommandsParityTest` | Fabric-side command registration, all published lookup examples under both roots, filter aliases and bounds, action/user/item value suggestions, duplicate/conflicting/fifth-filter completion limits, permission denial, invalid pages, inspect/page syntax, and vanilla `ClientboundCommandsPacket` command-tree encode/decode |
| `LookupPageSessionPolicyTest`, `QueryDispatcherNoResultTest` | page expiry boundary and paging limits on Fabric; asynchronous no-result delivery as a command failure on the server thread |
| `ItemGraphCommandsHelpTest`, `ItemGraphPageSessionSecurityTest`, `InspectionListenerTest` | NeoForge black-box rejection of another player's copied lookup-page token; page-session ownership and explicit cleanup; NeoForge inspection logout cleanup. `NativeAuditEventListenerTest.playerLogoutClearsItsPageSessionAndRecordsQuit` and `FabricNativeAuditEventListenerTest.disconnectHandlerClearsOnlyThatPlayersStateAndRecordsPlayerQuit` invoke the audit handlers directly. Fabric inspection logout cleanup is tested by `InspectionListenerTest.logoutClearsOnlyThatPlayersInspectionMode`. Loader callback/event-bus registration remains source-inspected; these tests do not prove socket disconnect transport. Command roots remain exactly `/itemgraph` and `/ig` |
| `ItemGraphApiTest` | service-issued `SourceHandle`, registration idempotency/spoof rejection, deduplication, endpoint/field validation, malformed-map/custom-name validation, stale-source/database/shutdown outcomes, coordinate-less external inventories, opaque evidence refs, ambiguity, player-coordinate suppression, limit/window conversion, canonical separator-forgery resistance, provenance, explanation/supporting evidence, and lifecycle (15 tests) |
| `V12PreviewApiSourcesAndExternalNodesTest` | `ig_api_sources`, `ig_nodes.external_key`, unique external identity, and coordinate-less `EXTERNAL_INVENTORY` schema (3 tests) |

Last passing full loader test run: **487 tests, 0 failures, 3 skipped** (`:neoforge:test :neoforge:runGameTestServer :fabric:test :fabric:runGameTest`, 2026-10-01); each loader's required GameTest passed. A later Fabric run on the same date failed the queue probe; see the native queue section below. The skipped tests require local MySQL/MariaDB services; hosted CI runs those integration tests against MariaDB 10.11 and MySQL 8.0.

## M8 issue #24: command semantics and page ownership

- The official [lookup command](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command), [filter reference](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/filters), [page reference](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/pages), and [inspect reference](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/inspect-command) define the documented command contract. The published pages require radius and permission level 2; the pinned 26.2 source accepts a no-radius lookup. ItemGraph follows the published operator-safety requirement and records the source discrepancy in `docs/GRIEFLOGGER_PARITY.md`.
- `ItemGraphCommandsHelpTest.allPublishedLookupExamplesParseForBothItemGraphRoots` and `FabricItemGraphCommandsParityTest.allPublishedLookupExamplesParseForBothItemGraphRootsOnFabric` check all 15 published lookup examples through both `/ig` and `/itemgraph`. Each example asserts complete Brigadier parsing, retention of the `lookupFilters` argument, and normalized `AuditLookupFilters` action IDs, player names, item registry IDs, radius, and time window against a fixed clock. Separate tests verify aliases, comma values, required radius, five-filter bounds, suggestions, permissions, invalid pages, and inspect/page syntax. Existing query tests cover deterministic empty-result errors and previous/next action generation.
- `ItemGraphCommandsInspectTest.inspectCommandTogglesAndExplicitFormsAreDeterministic` and `FabricItemGraphCommandsParityTest.inspectCommandExecutesBothRootsWithDeterministicStateAndMessagesOnFabric` now execute the same toggle, explicit `on`/`off`, and `status` sequence through both roots on both loaders. They assert player-scoped state and the same seven exact success receipts. Both permission-denial tests verify denied callers cannot change state or emit a success receipt, and assert the command body does not send a failure message when Brigadier rejects permission. Dispatcher tests still do not prove vanilla-client transport.
- `CommandPacketGameTests.inspectCommandsExecuteFromClientPacketsAndPersistAttempts` in both loader GameTest suites sends `ServerboundChatCommandPacket` instances through the server's `handleChatCommand` handler for `itemgraph inspect on`, `ig inspect status`, and `ig inspect off`. It checks permission-level-2 execution, enabled/status/disabled state transitions, exactly one durable `COMMAND_ATTEMPT` row per command with player attribution and timestamp, and an unchanged snapshot of each `ig_observations` cell's JDBC value and Java type. The shared fixture queries ItemGraph's database read-only after the audit watermark. The test uses an embedded mock player and calls the packet handler directly; it proves packet-handler-to-audit persistence on each loader, not socket transport, rendered client output, or GriefLogger differential behavior.
- `ItemGraphCommandsHelpTest.vanillaCommandTreePacketCodecRoundTripsBothCommandRoots` and `FabricItemGraphCommandsParityTest.vanillaCommandTreePacketCodecRoundTripsBothCommandRootsOnFabric` encode and decode the complete registered command tree with Minecraft 1.21.1's `ClientboundCommandsPacket.STREAM_CODEC`. Each asserts the `/ig` redirect targets `/itemgraph`, the lookup/page/inspect children survive decode, `/gl` and `/grieflogger` stay absent, and the packet is fully consumed. This catches unsupported Brigadier argument serializers in both loader test suites, but does not prove a live server/client connection or rendered output.
- `ItemGraphCommandsHelpTest.malformedLookupFiltersReturnExactFailuresOnBothRootsAndForms` and `FabricItemGraphCommandsParityTest.malformedLookupFiltersReturnExactFailuresOnBothRootsAndFormsOnFabric` execute invalid lookups through both roots and both direct/`lookup filters` forms on each loader. They assert exact failure output for missing radius, malformed and invalid radius, unknown and duplicate filters, include/exclude conflict, and six filters, with no success output. The filter parser also accepts a valid exactly-five-filter lookup on both loaders. Because the command returns the parser failure before database dispatch, these tests distinguish validation feedback from database errors.
- `pageSessionTokensAreIsolatedByPlayerAndExplicitlyClearable` executes the page command with another level-2 player holding a copied UUID token and expects `No active lookup page session`; `ItemGraphPageSessionSecurityTest` runs the same player-keyed session lookup and cleanup contract in the Fabric suite. Page lifetime and offset boundaries remain covered by `LookupPageSessionPolicyTest` and the NeoForge command tests.
- Prior connected-player loopback replays exercised generic and filtered `/ig lookup` results, and the 2026-09-30 connected-player test verified the server delivered its serializable command tree. The new packet-codec tests verify the same tree can round-trip through the vanilla packet codec; mock-player packet GameTests add server handler and durable-audit evidence. None fills the published-command vanilla-client replay requirement. These observations are recorded above in the native-only replay section. The client did not run the full 15-example matrix; that matrix is parser-tested in both loader suites.
- No `/gl` or `/grieflogger` root is registered. The shared roots preserve ItemGraph's observed/inferred/ambiguous/unresolved labels, evidence references, and privacy checks.

## M6 issue #8: vanilla flow browser verification

Automated coverage includes `TraceQueryServiceTest` forward and reverse keyset pages across
same-timestamp observations, transformations, and inferred edges; exact player and
explicit-dimension container resolution, duplicate-target ambiguity, and ID-pinned continuation;
`QueryDispatcherTest` read-only off-thread page delivery; and
`FlowBrowserMenuTest` rejection of item-moving click types and permission loss. Run these
with `./gradlew test`.

A GriefLogger-present protocol-client staging pass was run on 2026-09-25 on
`E:\Github2\itemgraph\run` with `./gradlew runServer`, NeoForge 21.1.248, Minecraft
1.21.1, GriefLogger 1.2.10 enabled, and Mineflayer 4.39.0 client `IGBotGui`. The ignored
driver `run/livebot/ig_gui_staging.js` passed **12/12 checks**:

- `/ig gui item stone` opened `minecraft:generic_9x6`; its ambiguous text match presented
  `minecraft:stone` fingerprint 103 and `minecraft:cobblestone` fingerprint 102. Selecting
  fingerprint 103 pinned the resolved target and displayed 45 timeline entries.
- Next-page and previous-page clicks opened a distinct page 2 and restored the exact page-1
  top-inventory signature.
- An observation entry opened its evidence detail view.
- Raw 1.21.1 `window_click` packets for right-click, shift-click, number-key swap, clone,
  throw-one, throw-stack, pickup-all, player-inventory left/right/shift/number clicks,
  outside-window click, and left/right drag sequences were sent. A full server resync showed
  no change to the 54 display slots, the player inventory, or the client cursor.
- `/ig gui item netherite_boots` presented both fingerprint candidates; selecting
  fingerprint 100 opened its timeline, including inferred-edge and observed-transformation
  detail views.
- `/ig gui player PlayerA` presented both duplicate `PlayerA` nodes; selecting node 1 opened
  the resolved player timeline.
- `/ig gui container minecraft:overworld -39 112 -8` opened a resolved 45-entry flow page.
- The same mutation-click set was replayed against the container browser without changing
  display slots, player inventory, or cursor state.

GriefLogger was enabled and wrote its own additive staging telemetry during this authorized
live pass. `run/database.db` changed from SHA-256
`50be0d7c234f1327eb9df2ff594c842e8499467a6ba5c6c100d40fd975fae594` to
`239b57f9317493b791b59993a53e46faafe0229759667a33174074e838b055fa`; representative counts
changed from `commands=0`, `sessions=45`, `users=12`, `items=25` to `commands=7`,
`sessions=51`, `users=13`, `items=26`. ItemGraph's own database changed from 2,793
observations / 10 fingerprints / 44 nodes to 2,795 / 11 / 46.

Mineflayer is a real protocol client, not the Mojang vanilla graphical client. Therefore that
run verifies the vanilla `GENERIC_9x6` server protocol and read-only click handling with
GriefLogger present, but it is not visual-client proof. The earlier V11 startup smoke is not
GUI verification and the V11 live-movement scenario remains unverified.

A graphical MC Pilot pass was then run on 2026-09-25 using the source-built MC Pilot CLI at
commit `87b9da4` (upstream `0.15.0`; the published npm package could not resolve an upstream
`workspace:*` dependency with npm 11). It launched real Minecraft 1.21.1 NeoForge client
`itemgraph-gui` (`neoforge-21.1.235`, non-headless) against staging `127.0.0.1:26417`.
Screenshots were captured under the ignored directory `run/mcpilot-screenshots/`.

GriefLogger-present visual checks:

- `/ig gui item stone` rendered the vanilla six-row chest screen, `minecraft:stone`
  fingerprint 103 and `minecraft:cobblestone` fingerprint 102 candidates, page metadata, and
  the unchanged player inventory (`8 dirt`, `2 wheat seeds`, `1 sugar cane`).
- Selecting fingerprint 103 rendered a 45-entry timeline; next-page displayed `Page 2` and
  previous-page restored `Page 1`.
- `/ig gui item netherite_boots` rendered both raw and named fingerprints; selecting raw
  fingerprint 100 rendered observed, inferred (`conf=0.9990`), and transformation entries.
  Observation detail `#65`, inference-edge detail `#8` (including its warning and two
  supporting observations), and transformation detail `#1` rendered correctly.
- `/ig gui player PlayerA` rendered duplicate nodes `PLAYER node#1` and `PLAYER node#100`;
  selecting node 1 opened the player timeline.
- `/ig gui container minecraft:overworld -39 112 -8` rendered node `node#109` and a 45-entry
  timeline.
- `/ig gui item definitely_not_an_item` rendered the explicit `No matching target` state.
- Real-client mutation attempts covered right-click, middle-click, shift-left/right,
  hotbar-key swap, player-inventory click/shift-click/hotbar-key swap, left/right GUI drag,
  `q` throw, outside-window click, player-inventory double-click pickup-all, raw mouse drag,
  and number-key input. After server resync, display contents, player inventory, and cursor
  remained unchanged. A raw drag beginning on an occupied entry intentionally invoked the
  allowed left-click detail action; follow-up raw drag/key checks from empty slots left the
  view unchanged.
- `deop IGBotGui` while a browser was open invalidated and closed the GUI; a subsequent click
  returned `GUI_NOT_OPEN` rather than navigating.
- No ItemGraph error/exception appeared in `run/logs/latest.log`. Client disconnect produced
  only the expected Netty `Connection reset` log.
- GriefLogger wrote its own normal staging telemetry. `run/database.db` SHA-256 changed from
  `239b57f9317493b791b59993a53e46faafe0229759667a33174074e838b055fa` to
  `f5b98321c4231bfd7d7e833e6c6d59e9687f6cb6ed2b9034afba4f114f0a34dc`; representative counts
  changed to `commands=14`, `sessions=52`, `users=13`, `items=26`. ItemGraph's database showed
  2,798 observations, 13 fingerprints, and 49 nodes after the two client sessions.

For the GriefLogger-absent pass, `run/mods/grieflogger-1.2.10-1.21.1-neoforge.jar` was
temporarily moved to `run/mods.disabled/` and restored afterward. Startup showed only
Architectury, ItemGraph, Minecraft, NeoForge, and SuperMartijn642 Config Library, and logged
`GriefLogger integration: DISABLED`. The same real MC Pilot client then passed item
candidate selection, page forward/back, player-node candidates, container timeline,
observation detail, and representative right/shift/inventory/`q` mutation rejection checks.
The preserved GriefLogger jar SHA-256 remained
`ed26d5ad6f3c6cf0425a3b55aa41f84a61197f030d78be5b70eae2a71b7c6e89`, and
`run/database.db` remained `f5b98321c4231bfd7d7e833e6c6d59e9687f6cb6ed2b9034afba4f114f0a34dc`
with no WAL/SHM sidecar. Both runs restored `server-ip=` and left no server or client listener
running.

## M6 issue #10: command-toggled inspector verification

Automated coverage now includes `InspectionServiceTest`, `InspectionListenerTest`, and
`ItemGraphCommandsInspectTest`: UUID-scoped state, bare toggle and deterministic
`on`/`off`/`status`, permission denial, supported-container cancellation, inactive and
unsupported clicks, browser-queue rejection fallback, logout cleanup, and isolation from
`ContainerSessionListener` pending clicks. `./gradlew clean build` passed **270 tests, 0
failures, 0 skipped** on 2026-09-25.

A GriefLogger-present graphical staging pass ran on `E:\Github2\itemgraph\run` with
`./gradlew runServer`, NeoForge 21.1.248, Minecraft 1.21.1, GriefLogger
`1.2.10-1.21.1-neoforge`, and the real non-headless MC Pilot NeoForge 1.21.1 client
`itemgraph-gui` (`neoforge-21.1.235`). Fixtures were placed at `minecraft:overworld`
`(-48..-45, 64, -23)`: chest, hopper, furnace, and a crafting table as the non-`Container`
control. Screenshots were captured under the ignored directory `run/mcpilot-screenshots/`.

Observed checks:

- With inspection off, ordinary vanilla screens opened: chest `minecraft:generic_9x3`
  (`26-off-chest.png`), hopper `minecraft:hopper` (`24-off-hopper.png`), and furnace
  `minecraft:furnace` (`25-off-furnace.png`).
- With inspection on, the unsupported crafting table still opened `minecraft:crafting`
  (`20-inspect-unsupported-crafting.png`).
- With inspection on, chest, hopper, and furnace each opened the read-only
  `minecraft:generic_9x6` ItemGraph browser with exact-coordinate titles
  `[-48, 64, -23]`, `[-47, 64, -23]`, and `[-46, 64, -23]` (`21-23`, then non-empty
  `28-30` screenshots).
- `/ig inspect` toggled enabled→disabled; `/ig inspect status` reported without changing
  state; repeated `/ig inspect on` and `/ig inspect off` were idempotent. Event output used
  the deterministic enabled/disabled messages rather than changing state implicitly.
- After fixtures were populated via server-side test commands (`dirt×5`, `wheat_seeds×3`,
  `sugar_cane×1`), inspection clicks opened only ItemGraph's browser. `data get block`
  returned the same item/count/slot triple afterward, and the held `dirt×8` stack stayed
  unchanged.
- `deop IGBotGui` while inspection was enabled cleared the mode on the next click: the
  chest opened vanilla `minecraft:generic_9x3`, `/ig inspect status` was rejected as a
  permission-gated command, and after re-op status reported disabled
  (`27-deopped-chest-vanilla.png`).
- Reconnect cleanup was verified by enabling inspection, running `client reconnect`, and
  receiving `Container inspection is disabled` from `/ig inspect status` after rejoin.
- Server-stop cleanup was verified by enabling inspection, stopping with RCON `stop`,
  restarting `./gradlew runServer`, reconnecting, and receiving
  `Container inspection is disabled`.
- ItemGraph's read-only database inspection showed `ig_observations=2798`,
  `ADD_ITEM`/`REMOVE_ITEM` count `26`, and `max(id)=2798` before and after the inspection
  browser opens; the menu did not create a container-transfer observation.
- GriefLogger wrote its own normal staging telemetry while enabled. `run/database.db`
  SHA-256 changed from `f5b98321c4231bfd7d7e833e6c6d59e9687f6cb6ed2b9034afba4f114f0a34dc`
  to `1f47bd0ff5b28c45ad2d4faba735aeb07d38e8e4b619bb5b9dac9fd695f6f388`; the GriefLogger
  jar remained `ed26d5ad6f3c6cf0425a3b55aa41f84a61197f030d78be5b70eae2a71b7c6e89`.
- After verification, `server-ip=` was restored to blank, and no staging server, RCON, or
  MC Pilot client listener remained.

This is a real rendered-client check through MC Pilot, not an unmodified Mojang launcher
client. It verifies the server-authoritative interaction and vanilla `GENERIC_9x6` screen
path on a graphical NeoForge 1.21.1 client. The earlier V11 live-movement scenario remains
unverified for the reason recorded above.

## M7 issue #12: preview API and consumer fixture

Automated coverage includes `ItemGraphApiTest`, `V12PreviewApiSourcesAndExternalNodesTest`,
`NodeManagerTest` external-identity cases, `ItemCanonicalizerTest`, and
`DatabaseManagerTest` schema-version assertions. The API tests use isolated temporary
ItemGraph databases, not `run/database.db`, and cover source registration/spoof rejection,
`PERSISTED`/`DUPLICATE`, endpoint and payload validation, queue/shutdown/database status
mapping, coordinate-less `EXTERNAL_INVENTORY` identity, opaque evidence URIs, ambiguity,
limits/relative windows, inferred explanations/supporting evidence, and service lifecycle.
`./gradlew clean build` must remain green before delivery.

`examples/api-consumer` is a separate NeoForge 1.21.1 subproject. It uses a
`compileOnly` local-file dependency on `build/libs/itemgraph-${rootProject.mod_version}.jar`—the
built main JAR, not source project internals—and `run/mods/itemgraph-api-consumer-0.1.0.jar`
was produced by
`./gradlew :examples:api-consumer:build`. Its `neoforge.mods.toml` declares an `itemgraph`
runtime dependency. On `ServerStartedEvent` it registers `itemgraph_api_consumer`,
submits stable source event `1`, then calls `traceItem(ItemQuery.itemId("minecraft:diamond"),
QueryOptions.defaults())`.

Dedicated staging loopback `127.0.0.1:26417` results:

- With GriefLogger present, `run/logs/latest.log` showed migration V12 applying from
  schema 11 and `ItemGraph API fixture completed: PERSISTED / query=AMBIGUOUS`. The
  ItemGraph database persisted `ig_api_sources` row
  `itemgraph_api_consumer|ItemGraph API Consumer Example|1` and observation source
  `EXTERNAL_API:itemgraph_api_consumer`, `source_event_id=1`, `TRANSFER_ITEM`. `AMBIGUOUS`
  is expected because staging has multiple `minecraft:diamond` fingerprint candidates.
- With `grieflogger-1.2.10-1.21.1-neoforge.jar` temporarily parked outside `run/mods`,
  ItemGraph started standalone and logged `GriefLogger integration: DISABLED`; the same
  stable source event returned `DUPLICATE / query=AMBIGUOUS`, proving persisted dedup and
  query behavior without GriefLogger. The GriefLogger JAR was restored and
  `run/database.db` remained SHA-256 `f5b98321c4231bfd7d7e833e6c6d59e9687f6cb6ed2b9034afba4f114f0a34dc`.
- No API consumer code called `server.execute`, JDBC, GriefLogger classes, or ItemGraph
  internals; lifecycle shutdown ran on `ServerStoppingEvent`.

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

## GriefLogger-compatible artifact smoke test (isolated RustiCraft clone)

On 2026-09-28, `java -classpath gradle/wrapper/gradle-wrapper.jar
org.gradle.wrapper.GradleWrapperMain clean build verifyGriefLoggerCompatibleJar`
completed successfully on Java 21.0.12. The build created
`build/libs/itemgraph-0.3.0-grieflogger-compatible.jar`; the verification task
confirmed that it has no SQLite classes/JarJar metadata, requires GriefLogger
`1.2.10-1.21.1`, and that the standard JAR still bundles SQLite and keeps
GriefLogger optional.

The built compatibility artifact was run in the isolated copy
`run/rusticraft-itemgraph-smoke-20260928`, with GriefLogger
`1.2.10-1.21.1-neoforge.jar`, Minecraft 1.21.1, NeoForge 21.1.248, and a fresh
world `itemgraph-smoke-world` bound only to `127.0.0.1:27991`. The server reached
`Done (17.953s)`, ItemGraph applied schema migrations through V12 and initialized
its own database, and GriefLogger was detected as enabled. The original failing
ItemGraph JAR was preserved outside `mods` as
`mods-repro/itemgraph-0.3.0-conflicting.jar`; the source clone's world and GriefLogger
database were not copied or accessed. This startup smoke establishes that the module
resolution crash is gone. The isolated folder had no GriefLogger database schema,
so this run does not verify event ingestion against the source clone's existing DB.

## SQLite and MySQL/MariaDB storage contract

The shared `DialectSqlTest` covers the deterministic rewrites for identity
columns, text/blob types, idempotent indexes, SQLite `PRAGMA table_info`, and
upserts. `MariaDbDialectIntegrationTest` is skipped locally unless endpoints
are supplied, then runs the same migration and basic evidence contract against
each configured server. The background-worker network heartbeat and closed
connection recovery are checked separately against both servers. CI provisions
MariaDB 10.11 on port 3306 and MySQL 8.0 on port 3307 with the following
environment variables. The MySQL heartbeat test uses `sslMode=trust` with the
ephemeral CI endpoint so MySQL 8's `caching_sha2_password` exchange uses TLS
instead of enabling unauthenticated RSA public-key retrieval. This mode does
not verify the server certificate or hostname; it is test-only and is not a
recommended production TLS policy.

```text
ITEMGRAPH_TEST_MARIADB_URL=jdbc:mariadb://127.0.0.1:3306/itemgraph
ITEMGRAPH_TEST_MYSQL_URL=jdbc:mariadb://127.0.0.1:3307/itemgraph?allowPublicKeyRetrieval=true
```

The public-key retrieval option is CI-only for the disposable MySQL service. ItemGraph
application connections leave it disabled; production MySQL authentication must use
verified TLS or a server authentication method that does not require RSA key retrieval.

The test verifies clean schema setup, a second idempotent migration pass,
translated `INSERT IGNORE`, generated-column dedup support, and translated
`ON CONFLICT ... excluded.column` upserts. It does not access the GriefLogger
source database; that adapter remains a separate SQLite read-only path.

## Native GriefLogger parity slice (source verification)

The current source-level verification runs without producing a release artifact:

- `./gradlew.bat :neoforge:test --tests com.itemgraph.ingest.InternalObservationServiceTest --tests com.itemgraph.listener.NativeAuditEventListenerTest --tests com.itemgraph.listener.NativeItemActionEventListenerTest --tests com.itemgraph.query.AuditEventQueryServiceTest --rerun-tasks --no-daemon`
- `./gradlew.bat :neoforge:test --tests com.itemgraph.command.ItemGraphCommandsHelpTest --tests com.itemgraph.query.AuditEventQueryServiceTest --rerun-tasks --no-daemon`
- `./gradlew.bat :fabric:compileJava --no-daemon`

These checks cover V13 audit persistence, cancellation filtering, shutdown-loss
accounting, defensive raw-data copying, opaque-detail formatting, location/radius
filters, stable page offsets, native item-action wiring, Ender session delta
conservation, and the Fabric callback adapter. The 2026-09-29 GriefLogger-absent Fabric replay in the isolated
`C:\Users\User\itemgraph-staging-replay` checkout exercised join/quit, chat,
commands, block actions, entity kills, containers, consume, durability break,
throw, shoot, projectile, and hopper events through the real server. After clean
shutdown, the isolated SQLite database contained rows for every exercised event,
and the bot received results from both generic and filtered `/ig lookup` commands.
The same staging checkout persisted `CRAFT`, `SMELT`, and `ANVIL_RENAME` rows,
and `/ig inspect on` opened a read-only `minecraft:generic_9x6` flow browser for
the populated chest. The remaining cutover work is the documented migration and
retention procedure; no production change is authorized by this test.

The 2026-09-30 NeoForge staging startup additionally ran `:neoforge:runServer`
with the shared `common` and `core` source sets attached to the ModDev run. It
loaded the NeoForge mixin configuration, applied migration v16, reached `Done`,
and shut down without a mod-loading or mixin error. A connected-player replay
then performed one snowball throw and one bow shot. Read-only SQLite inspection
found one durable `THROW_ITEM` and one `SHOOT_ITEM` observation, one accepted
spawn audit row for each, non-null source event IDs on all four rows, and zero
duplicate source-event groups. Fabric passed the identical replay and query on
port 27993; NeoForge used port 27994. These replays are staging evidence only.

## M8 issue #76: Ender action writer determination and session deltas

- `python tools/validate_grieflogger_release_fixture.py --check-remote` downloads
  the pinned Fabric and NeoForge 1.2.10-1.21.1 jars, verifies their recorded
  hashes, and scans every classfile to prove Ender action symbols occur only in
  `ItemAction.class`.
- `python tools/validate_grieflogger_profile.py` checks the
  `unsupported-no-writer` status, stable reason code, evidence issue, and the
  separately labeled `ITEMGRAPH_INTERNAL` session-delta extension.
- `EnderChestInteractionTrackerTest` checks duplicate menu opens, reconnect
  sessions, signed partial-transfer counts, unchanged sessions, and opaque
  component redaction. It also rejects a multi-fingerprint delta batch, verifies
  the complete session remains retryable, and then accepts the batch once.
  `orderlyShutdownRetriesQueueRejectedSessionBeforeClearingIt` verifies shutdown
  retries a rejected batch and clears the session only after acceptance.
  The shared shutdown retry deadline is five seconds total across all player
  sessions; `shutdownQueueRetryBudgetIsSharedAcrossAllSessions` checks it does
  not multiply by the number of sessions.
  `InternalObservationService.submitAll` and worker batch requeues share the
  producer lock; `atomicObservationBatchCannotBePartiallyAcceptedDuringWorkerRequeue`
  races both producers with one free queue slot and verifies no partial batch
  enqueue. `InternalObservationServiceTest`
  `enderSessionDeltaRemainsQueryableAfterDatabaseRestart` verifies one durable
  player-owned endpoint and amount after restart, with no duplicate row and no
  fingerprint in raw data. `ItemGraphCommandsHelpTest` checks an unprivileged
  caller is denied at the command root.
- The GriefLogger release database is never opened by these checks. The
  determination uses checksum-verified published jars and the pinned source
  audit; no source rows are read or mutated.
- Run `./gradlew.bat :neoforge:test :neoforge:runGameTestServer :fabric:test
  :fabric:runGameTest` for loader unit and GameTest coverage. The no-writer
  result does not claim transaction-level cross-loader GriefLogger replay.

## M8 issue #27: canonical action IDs and exact-release writers

- `python tools/validate_grieflogger_profile.py` requires all 18 pinned 26.2
  source action enum names, enum classes, IDs, loader support, evidence and
  quantity contracts, and exact-release writer status to agree with the
  compatibility registry. `python tools/test_grieflogger_validators.py`
  rejects boolean/float IDs, non-integer fixture schema versions, and duplicate
  JSON object keys even when a modified fixture has a freshly recomputed digest.
- `python tools/validate_grieflogger_release_fixture.py --check-remote`
  downloads the official Fabric and NeoForge 1.2.10-1.21.1 artifacts, verifies
  SHA-1/SHA-256/SHA-512 and class counts, then checks each action's executable enum-field accesses against the pinned
  class list. The expected matrix is in
  `docs/grieflogger-fixtures/1.2.10-1.21.1.json`.
- Regressions require `INTERACT_BLOCK` to remain an exact-release
  main-hand-attempt mapping, `INTERACT_ENTITY` to remain absent from the exact
  release action catalog with its native behavior labeled as an extension,
  and the Ender action constants to remain enum-only with their stable
  no-writer reason.
- These are source/binary mapping checks; they do not replace the full
  loader replay or the differential acceptance owned by #31.
- The paired NeoForge and Fabric `EntityInteractionGameTests` also perform an
  actual server-side water-bucket use against a source block. The shared
  `BucketPickupConformanceFixture` requires one durable `BREAK_BLOCK` audit row
  with `minecraft:water` at the source coordinates and verifies that no
  non-projectile quantity observation was created for that test player. This
  mirrors the pinned GriefLogger `MixinBucketItem` behavior without attributing
  the water source or returned bucket as a quantity transfer. It also verifies
  that an empty pickup result writes no row and that a mismatched water source
  with a lava-bucket result records `minecraft:lava`, proving the item content
  is the recorded fluid source for modded pickup results.
- The paired NeoForge and Fabric `EntityInteractionGameTests` also exercise
  projectile capture through the real `Projectile.shootFromRotation` mixin and
  accepted-spawn `ServerLevel.addFreshEntity` hook. The shared
  `ProjectileConformanceFixture` reads ItemGraph's SQLite database read-only and
  requires exactly one durable amount-1 `THROW_ITEM` snowball row and one
  `SHOOT_ITEM` arrow row, each from the mock player's node to `UNKNOWN` with a
  non-null `source_event_id`. Their `THROW_ITEM`/`SHOOT_ITEM` audit projections
  must reuse those event IDs; two `PROJECTILE_SPAWN_ACCEPTED` rows must have
  distinct IDs and no `quantity=` claim. Accepted-spawn action, `subject_id`
  and raw projectile identifier must map to the expected snowball or arrow.
  The fixture compares all prior raw
  quantity rows byte-for-byte and rejects any additional quantity row for the
  test player. This
  exercises the production server hooks and persistence boundary but not a real
  client socket, GriefLogger-present differential replay, or bow-ammunition
  consumption semantics.
- The paired NeoForge and Fabric `EntityInteractionGameTests` also exercise
  container and ground item movement. Each test seeds 2 dirt in the player's
  inventory and 3 cobblestone in a chest, uses server-side `QUICK_MOVE` chest
  menu clicks to deposit the dirt and withdraw the cobblestone, and asserts both
  inventory and chest counts after each transfer. It then removes 4 diamonds
  from the player's inventory, drops them, picks up the same `ItemEntity`, and
  asserts the inventory receives all 4 back. `ItemMovementConformanceFixture`
  opens SQLite read-only and requires exactly four new rows: `ADD_ITEM` (player to container),
  `REMOVE_ITEM` (container to player), `DROP_ITEM` (player to ground), and
  `PICKUP_ITEM` (ground to player). An unfiltered observation query also
  requires these to be the replay player's only four new quantity rows, all
  `ITEMGRAPH_INTERNAL`. The fixture checks item IDs, amounts, canonical
  fingerprints, player ownership, exact container coordinates and dimension,
  session net-delta raw markers and non-negative intervals, distinct drop and
  pickup event identities, and the exact spawned entity UUID and fingerprint
  across the drop/pickup pair. Ground endpoints must stay in the same dimension
  and within one block to account for entity movement between event capture and the server
  tick that confirms the drop. It compares the full prior quantity-row
  snapshot to ensure the replay did not mutate earlier evidence. NeoForge calls
  the two-argument `Player.drop` overload so `ItemTossEvent` reaches the native
  listener; Fabric calls the three-argument `ServerPlayer.drop` overload covered
  by its accepted-entity mixin. The mock-player GameTests verify both loader
  persistence paths but do not establish client transport or GriefLogger-present
  differential parity. The other exact-release item writers and full #27
  replay remain outstanding.

## M8 issue #26: exact block/container inspector and immutable supersession

Automated checks currently cover the shared query and both loader adapters:

- `UnifiedEvidenceQueryServiceTest.exactInspectorTimelineMergesSourcesAndPagesWithoutAdjacentPositionBleed` verifies global page order across audit, observation, and transformation tables; duplicate multi-cell targets; exact dimension/position filtering; and container-endpoint selection when the player endpoint is stored elsewhere.
- `InternalObservationServiceTest.blockRemovalLinksEarlierInteractionsWithoutDeletingRawEvidence` verifies native interaction retention, two-cell door supersession, imported GriefLogger row supersession, retry idempotency, reason/evidence IDs in ordinary lookup, and active inspector filtering.
- The same internal observation test uses a 616-code-point imported source key, including non-BMP characters, to prove supersession stores the full key and uses a code-point-safe indexed prefix. It also checks that a case-variant key recorded after a break stays active. `MariaDbDialectIntegrationTest` checks the V19 MySQL/MariaDB columns use `LONGTEXT` for the source key and a fixed 64-character digest for the primary key.
- NeoForge `InspectionListenerTest` and Fabric `FabricNativeAuditEventListenerTest` verify per-player and permission behavior, accepted-query cancellation, queue-rejection gameplay fallback, and shared unified-history dispatch.

On 2026-09-30, the development server started in a fresh temporary directory with
`eula=true`, bound only to `127.0.0.1:25575`, and loaded only ItemGraph 0.3.2,
Minecraft 1.21.1, and NeoForge 21.1.248. Startup reached `Done`; ItemGraph opened
its isolated SQLite database and reported schema version 19. A read-only database
check confirmed both V19 supersession tables and zero audit/supersession rows.
This verifies dedicated-server loading and migration only. It does not satisfy the
player interaction, queue saturation, conservation, or live supersession matrix.

The implementation follows the observed GriefLogger cleanup behavior at the presentation boundary while keeping evidence immutable: GriefLogger removes old interaction rows after an interactable block/door break; ItemGraph records `BLOCK_REMOVED_AT_TARGET` links in its own schema V19 tables and retains the native row, imported projection, and immutable provenance. Source reference: [`RemoveBlockInteractionsEvent`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/RemoveBlockInteractionsEvent.java) and [`RemoveDoorInteractionsEvent`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/RemoveDoorInteractionsEvent.java).

**Visible-client matrix status:** skipped by the maintainer. No connected-client clicks were run for ordinary/function blocks, single/double chests, doors, empty/missing history, block removal, door removal, repeated clicks, or query-queue saturation. Issue #26 is closed, but this client evidence remains unverified; its closure is not proof that the matrix ran. Automated adapter/query tests and the dedicated-server startup/migration smoke above remain the available evidence. No production or staging instance is involved.

## M8 issue #75: entity interaction outcomes

Automated tests cover these server-side boundaries:

- `EntityInteractionEvidenceTest` verifies consuming results become `INTERACT_ENTITY_COMPLETED`, `FAIL` becomes `INTERACT_ENTITY_DENIED`, the fallback `Entity.interact` `PASS` becomes `INTERACT_ENTITY_UNRESOLVED` because later entity-use steps may still run; the intermediate `ArmorStand.interactAt` `PASS` does not create a second method-result row, missing target UUIDs are omitted, attempt metadata contains only held item ID/count/fingerprint, non-armor targets are rejected by the armor-stand result recorder, and client-side calls do not emit evidence.
- `NativeAuditEventListenerTest` verifies the NeoForge armor-stand specific callback records one normalized attempt, canceled specific and generic callbacks are retained, canceled non-armor specifics are retained, missing target UUIDs are omitted, and the non-canceled generic armor-stand event path does not duplicate the attempt.
- `FabricNativeAuditEventListenerTest` verifies Fabric attempt detail uses the target UUID when available, hand, target type, position, held item metadata, and completion coverage fields. `FabricUseEntityCallbackAuditTest` verifies aggregate callback order and one final non-`PASS` result when an earlier callback short-circuits before ItemGraph or a later callback returns a handled result after ItemGraph returns `PASS`.
- NeoForge `:neoforge:runGameTestServer` and Fabric `:fabric:runGameTest` run equivalent isolated server GameTests with the ItemGraph loader integration enabled. Each creates a mock server player, records the full read-only `ig_observations` row snapshot, sends `ServerboundInteractPacket` instances through `ServerGamePacketListenerImpl.handleInteract` for an unsupported cow and an armor stand, equips diamond boots, then removes them with an empty hand. Each test separately calls inherited `ArmorStand.interact` directly and checks its `PASS` result to exercise the method return hook; that call is not attributed to a packet. Each test verifies the resulting armor-stand equipment state, stops and joins the ItemGraph worker to flush pending writes, opens ItemGraph's audit database read-only, and requires one cow attempt plus exactly two armor-stand packet attempts, two handled `interactAt` results, and one unresolved direct `interact` method result, with no duplicate rows. It requires every `ig_observations` row and column to remain identical before and after. The tests assert held-stack details and hand on attempts, `target_support=callback_only` plus `target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT` for cow evidence, and `target_support=armor_stand_method_result` for armor-stand evidence. The shared fixture verifies the same normalized query rows and formatter output in both loader runs. NeoForge test classes and structure data are excluded from both NeoForge jar tasks; Fabric tests use Loom's separate `gametest` source set and test mod. CI runs both server GameTests beside both loader unit suites without packaging distributable mod jars. This validates both local packet-to-callback-to-durable-ledger paths and the direct return-hook boundary; it does not validate a live client's socket transport.
- `InternalObservationServiceTest.entityInteractionOutcomeSurvivesDatabaseRestartWithoutQuantityObservation` submits the denied result through the bounded audit queue, calls `stop()` without starting a worker (covering its synchronous shutdown-flush path), closes and reopens the ItemGraph SQLite database, verifies the immutable row and held fingerprint remain queryable, and asserts that no `ig_observations` quantity row was written. This does not cover worker-thread interrupt/join or a server restart.
- `V20UnverifiedArmorStandInteractionEvidenceTest` upgrades a simulated v19 database, retains raw legacy callbacks with a disposition, supersedes linked active edges, hides disposed evidence from current flow queries, keeps `/ig event` explicit, remains idempotent, and leaves `/ig audit` healthy. The MariaDB/MySQL contract seeds equivalent legacy evidence for hosted CI.

**Runtime boundary:** the operator asked to skip visible-client clicks. Both local loader GameTests directly invoke the actual server packet handler with embedded mock players and verify armor-stand equip/unequip attempt and result rows. No real client connection, whole-server restart, or live denied/canceled/repeated interaction sequence was run. Denied/canceled handling, missing target UUID behavior, and restart persistence are covered by cross-loader unit/database tests, not by packet-level GameTests. MariaDB/MySQL integration cases passed in hosted CI; local endpoints remain unconfigured. The exact 1.21.1 GriefLogger release artifact has no entity-interaction writer; the pinned 26.2 source records a successful armor-stand interaction without held-item data. ItemGraph makes no item-transfer or equipment-slot claim from these method results.

## M9 issue #35: shared event taxonomy

`EventTaxonomyTest` checks stable unique IDs per evidence surface, complete
evidence/reliability/endpoint/quantity/actor/privacy/loader/owner fields,
reason-code uniqueness, version shape, and unknown-ID behavior. It also checks
the legacy unified-lookup aliases (including the `interact_block` mapping),
that each shared query choice is implemented or historical-queryable on both
loaders, and that planned #55–#57 definitions do not claim runtime support.
They validate the shared taxonomy contract; they do not prove loader event capture,
database persistence of new event families, or runtime parity. Those checks
belong to the child issue fixtures and the consolidated M9 acceptance pass.

