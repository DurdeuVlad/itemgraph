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
| `ItemGraphCommandsGuiTest` | `/ig gui` item/player/container and `/ig inspect` command shape, explicit dimension argument, quoted `"id:<id>"` parsing, and stale empty-cursor handling (3 tests) |
| `InspectionServiceTest`, `InspectionListenerTest`, `ItemGraphCommandsInspectTest` | per-player inspect state, deterministic command forms, permission denial, supported/unsupported clicks, browser-queue rejection fallback, logout cleanup, and canceled-click isolation from session tracking (3 + 6 + 2 tests) |
| `ItemGraphCommandsHelpTest` | bare-root overview, every help topic, invalid-topic diagnostics, permission denial, registered-path/help synchronization, and literal/player/item/dimension suggestions (7 tests) |

Total automated test count: **277 tests, 0 failures, 0 skipped** (`./gradlew clean build`, 2026-09-25).

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

