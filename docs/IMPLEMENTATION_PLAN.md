# Implementation Plan

## Phase 0 — Reconnaissance

Do not begin significant implementation before this phase is complete.

Collect:

- staging environment details
- repository layout
- Git state
- Java version
- NeoForge version
- GriefLogger version
- GriefLogger DB engine and schema
- faction/team mod
- relevant inventory/container mods
- armor stand behavior
- coffer behavior
- sample source events

Deliver a short report with:

1. Environment findings
2. GriefLogger coverage matrix
3. Logging gaps
4. Risks/unknowns
5. Recommended integration strategy

## Phase 1 — Project skeleton

Create:

- NeoForge 1.21.1 project
- `itemgraph` mod ID
- config system
- logging
- command root
- database abstraction
- schema migration framework
- basic `/ig status`

No complex inference yet.

## Phase 2 — Evidence ingestion

Implement:

- native ItemGraph event capture as the primary evidence source
- ItemGraph-owned durable raw evidence and incremental persistence
- canonical observation model with explicit observed, inferred, ambiguous, and unresolved classes
- an optional read-only GriefLogger importer for historical migration evidence, isolated from native capture
- narrowly scoped compatibility adapters only where the exact 1.21.1 release contract requires them

Acceptance:

- supported native events persist to ItemGraph's own store and remain queryable with GriefLogger absent
- native capture and storage do not open, poll, or modify the GriefLogger database
- when explicitly enabled, the importer reads historical source evidence without modifying GriefLogger

## Phase 3 — Item canonicalization

Implement:

- item registry ID extraction
- 1.21.1 data-component canonicalization
- deterministic metadata hash
- custom name extraction
- relevant damage/enchantment/trim fields

Document exactly what enters the fingerprint.

Acceptance:

- same semantic item produces same fingerprint
- changed relevant metadata produces expected new state

## Phase 4 — Graph nodes

Implement stable inventory-node identities for:

- players
- block containers
- item entities/ground where possible
- armor stands
- unknown holder

Do not over-generalize modded inventories before real use cases are tested.

## Phase 5 — Basic correlation

Support a narrow set first:

- container remove -> player gain
- player drop -> item entity
- item entity -> player pickup
- player remove -> container add

Use deterministic, explainable scoring.

Acceptance:

- controlled named item path reconstructed correctly

## Phase 6 — Query commands

Implement:

```text
/ig trace item ...
/ig event ...
/ig explain ...
/ig status
```

Add:

- pagination
- bounded time filters
- readable output
- permission checks

### Delivered

```text
/ig event   <observationId>
/ig explain <edgeId>
/ig trace item <query> [limit] [sinceMinutes]
/ig trace player <playerName> [limit] [sinceMinutes]
/ig trace container <x> <y> <z> [limit] [sinceMinutes]
/ig gui item <query> [sinceMinutes]
/ig gui player <playerName> [sinceMinutes]
/ig gui container <dimension> <x> <y> <z> [sinceMinutes]
/ig inspect [on|off|status]
/ig status
```

- `trace item` resolves numeric fingerprint IDs, registry IDs, and custom names; ambiguous
  string queries list candidates rather than choosing a fingerprint silently.
- `trace player` and `trace container` use the same database-backed target lookup as the
  corresponding GUI views. The GUI container target requires an explicit dimension.
- `inspect` stores per-player UUID state only, clears on logout/server stop, and requires
  permission level 2 for command and click. `InspectionListener` cancels a supported
  `RightClickBlock` container interaction at `HIGHEST` priority with `SUCCESS`, opens the
  read-only flow browser, and does not record the click as a transfer.
- Chat traces are row-capped (`limit` default 20, hard cap 100); `/ig explain` evidence is
  capped at 50. `sinceMinutes` is inclusive and uses interval overlap for session observations
  and inferred edges.
- The six-row vanilla flow browser shows up to 45 entries per page, labels observed versus
  inferred movement and confidence, and opens raw event, transformation, or stored
  edge-explanation details.
  Composite keyset pagination includes timestamp, provenance kind, row ID, and source kind.
  All item-movement click paths are rejected; page/detail controls are navigation-only.
- Permission level 2 is required for the command tree, menu validity, and every menu action.
- SQL uses the read-only `QueryDispatcher` worker; page/detail results return to the server
  thread before Minecraft menu state is created or changed. The worker queue accepts at most
  64 waiting queries and rejects overflow rather than accumulating unbounded work.
- `./gradlew clean build` passes 270 tests, 0 failures, 0 skipped (2026-09-25).
- A GriefLogger-present Mineflayer protocol-client GUI staging pass completed 12/12 checks,
  and graphical MC Pilot staging passed with GriefLogger both present and temporarily absent;
  see `docs/TEST_PLAN.md`. The MC Pilot runs used a real non-headless NeoForge 1.21.1 client,
  verified all three `/ig gui` entry points, ambiguity, pagination, observation/
  transformation/inference details, unresolved state, permission revocation, and read-only
  mutation rejection.
- A separate issue-#10 MC Pilot pass verified `/ig inspect` against chest, hopper, furnace,
  and crafting-table controls, including permission loss, reconnect/server-stop cleanup,
  non-empty-container preservation, and no new ItemGraph observations from browser opens.

### Deferred out of Phase 6

- `after` / `before` / `between` filters and click-event links from chat output.
- Dedicated `[AMBIGUOUS]` and `[UNRESOLVED]` line prefixes; source-group ambiguity and UNKNOWN
  endpoints are preserved in the current evidence/details instead.

See `docs/QUERY_MODEL.md` for argument, GUI, and pagination contracts and
`docs/ARCHITECTURE.md` for threading and evidence-boundary rules.

## Phase 7 — Quantity flow

Add stack-aware conservation.

Support:

- split
- merge
- partial transfers

### Delivered

- **Quantity-Allocation Ledger (`ig_edge_allocations`)**:
  Schema migration `V7__QuantityFlowLedger` adding `ig_edge_allocations` table with composite primary key `(edge_id, observation_id, allocation_role)` and indexes, plus backfilling historical Phase 5/6 edges.
- **Explicit Observation Lifecycle (`ig_observations.correlation_status`)**:
  Replaces overloaded binary interpretation of `correlated_at` with explicit states: `PENDING`, `PARTIALLY_ALLOCATED`, `FULLY_ALLOCATED`, and `CLOSED_UNRESOLVED`.
- **Residual Capacity Accounting**:
  Dynamic calculation of remaining capacity ($R_{obs} = Q_{obs} - \sum \text{allocated}$), enabling stack splits (1-to-many), stack merges (many-to-1), partial transfers, and multi-fragment flows with zero manufactured quantity.
- **Explainable Quantity Narratives**:
  `CorrelationEngine` generates detailed explanations recording flow classification (`stack split`, `stack merge`, `exact transfer`), allocated units, residuals before/after, candidate counts, and proximity/ambiguity factor arithmetic.
- **Single-Transaction Atomicity**:
  Inferred edges, evidence links, allocation records, and observation status updates commit in a single atomic transaction.
- **Automated Verification**:
  16 new comprehensive unit tests in `QuantityFlowTest` covering splits, merges, partials, window expiration, over-capacity protections, competing candidate penalties, idempotency, restart simulation, and transaction rollback. All 93 test cases passing.

Acceptance:

- ordinary iron/diamond stack movement reconstructed without item UUIDs while strictly conserving quantity.
- stack splitting, stack merging, and partial transfer scenarios pass with 100% invariant conservation.

## Phase 8 — Missing high-value integrations [COMPLETED]

Implemented:

- Authoritative `ItemEntity` UUID tracking (`ItemEntityTracker`, `ItemEntityEventListener`, schema V8 `item_entity_uuid` column), with drops recorded only after `ItemEntity.isAddedToLevel()` confirms world insertion.
- Unique `ItemEntity` UUID correlation boost: continuity matching assigns 0.9990 confidence only when one spatial/time candidate matches and records the entity-continuity explanation.
- Armor stand consuming, denied (`FAIL`), and method-boundary `PASS` results are captured as raw evidence at the `ArmorStand.interactAt` override and inherited `Entity.interact` fallback return boundaries; NeoForge canceled callbacks are retained as denied evidence. NeoForge records the `EntityInteractSpecific` attempt before either method, so the generic fallback callback does not duplicate it. No item-flow quantity is inferred from these outcomes.
- User experience query extensions:
  - `/ig trace player <playerName>`
  - `/ig trace container <x> <y> <z>`
  - `/ig trace item <query>` (accepts numeric IDs, registry names, and custom names).

Acceptance:

- Item entity continuity verified on live staging server with 0.9990 confidence.
- Handled, denied, unresolved generic-method-PASS, and NeoForge-canceled armor-stand interactions are recorded as raw audit events; no equip/unequip quantity or inventory transfer is claimed from the click.
- Player and container timelines reconstructed cleanly.

## Phase 9 — Transformations [COMPLETED]

Implemented:

- Transformation ledger table `ig_item_transformations` (schema V8).
- Supplemental event listener `TransformationEventListener` capturing:
  - Anvil repairs and item renames (`AnvilRepairEvent`).
  - Crafting operations (`ItemCraftedEvent`).
  - Smelting operations (`ItemSmeltedEvent`).
- Asynchronous batch persistence via `InternalObservationService`.
- Trace query lineage integration: `trace item` displays chronological transformation hops (`[TRANSFORMATION <type> <- <source>]`).

Acceptance:

- Anvil item renaming observed, persisted, and surfaced chronologically in live item trace timelines.
- Crafting and smelting operations link input and output fingerprints cleanly.

## Phase 10 — Hardening & Auditing [COMPLETED]

Implemented:

- Offline/off-thread database invariant auditor (`AuditService`).
  - Conservation invariant ($\sum \text{allocated} \le \text{amount}$).
  - Strict quantity positivity ($amount > 0$).
  - Relational graph integrity (no orphaned allocations or broken node endpoints).
  - Lifecycle state consistency (`correlation_status` vs allocations).
- Administrative audit command `/ig audit`.
- Live diagnostic telemetry in `/ig status` (internal queue throughput/drops, capability queue rejections, transformation totals, active tracked entities, continuity matches).
- 234 automated tests pass with 0 failures on the current M5 repair branch (`./gradlew clean build`, 2026-09-24).
- Zero-tamper verification against GriefLogger database (SHA-256 unchanged).
- Full restart persistence verified on live dedicated NeoForge server.

Acceptance:

- `/ig audit` reports `HEALTHY (ALL INVARIANTS SATISFIED)`.
- Live staging server tests passing end-to-end.
- Zero GriefLogger modification across all operational cycles.

## M5 evidence-integrity repair (staging and review pending)

Implemented in the current M5 repair branch:

- V11 destination-sensitive deduplication, `timestamp_end_ms`, cross-source groups/checks,
  and retained `edge_state` for superseded inferences.
- Unique shared ItemEntity UUID matching consolidates confirmed ItemGraph/GriefLogger
  copies; plausible pairs without strong identity remain `SOURCE_AMBIGUOUS` with no capacity.
- Drop ground rows require a confirmed added ItemEntity; canceled attempts never become
  ground movement. Spatial UUID enrichment is unique-only.
- Generic capability rows use UNKNOWN caller/cause labels; queue rejection is excluded from
  player attribution and retried once at session close.
- Container changes are interval-bounded net deltas, not click-time records; zero-net
  out-and-back activity is documented as unobserved.
- Internal persistence, GriefLogger ingestion, and correlation transactions serialize on
  the shared ItemGraph connection. `/ig ingest now` and database-backed `/ig status` are
  asynchronous.

Remaining delivery gates are controlled live movement/queue/cancellation scenarios, independent
adversarial review of the final diff, PR #6 CI/review, and authoritative merge confirmation.
V11 startup migration succeeded on loopback, but player scenarios were not run because the
available safe tools cannot disable the GriefLogger mod without moving its jar. Production and
the existing GriefLogger database remain out of scope for writes.

## M7 — Preview integration API

Issue #9 defined the maintainer-approved public contract in `docs/API.md`; issue #12
implements it as `com.itemgraph.api` `PREVIEW_1`, shipped in the main ItemGraph JAR and
consumed by trusted NeoForge mods through local-JAR `compileOnly` plus a runtime
`itemgraph` mod dependency.

Implemented implementation surface:

- immutable source registration and direct-observation DTOs;
- `(source modId, sourceEventId)` deduplication using the existing non-null source-event
  uniqueness model;
- durable `EXTERNAL_INVENTORY` nodes keyed by `(ownerModId, inventoryId)`, including a
  coordinate-less identity distinct from `UNKNOWN`;
- bounded asynchronous submission and query results with explicit `PERSISTED`,
  `DUPLICATE`, `INVALID_INPUT`, `QUEUE_FULL`, `DATABASE_UNAVAILABLE`, `SHUTDOWN`, and
  `FAILED` outcomes;
- flow DTOs preserving observed/inferred provenance, evidence refs, confidence, stored
  explanations, ambiguity, limits, and truncation;
- an ItemGraph-owned V12 migration for `ig_api_sources` and `ig_nodes.external_key`;
- a separate NeoForge 1.21.1 consumer fixture compiled against the built main JAR.

Staging completed both required modes on dedicated loopback `127.0.0.1:26417`: V12 applied
with GriefLogger present (`PERSISTED / query=AMBIGUOUS`) and ItemGraph ran standalone
with GriefLogger absent (`DUPLICATE / query=AMBIGUOUS`). The `AMBIGUOUS` query result is
expected staging evidence because `minecraft:diamond` has multiple fingerprint
candidates; `run/database.db` remained hash-identical and the GriefLogger JAR was
restored. Issue #12 was delivered through PR #18 and merged into `main`; post-merge CI
passed.

Non-goals for M7 remain JDBC/schema exposure, GriefLogger API access, caller-provided
inference, player-facing authorization, a standalone API artifact, and a stable pre-1.0
API promise.

## Git strategy

## Phase 11 — Native GriefLogger replacement parity

The native replacement is staged by evidence category. `ig_observations` remains
the item-flow ledger; `ig_audit_events` stores non-quantity audit events,
including accepted projectile-spawn evidence.

### Delivered in the current slice

- V13 creates the ItemGraph-owned `ig_audit_events` table and indexes.
- NeoForge captures player join/quit, chat, and command attempts; Fabric captures
  the same command-dispatch attempt through a narrowly scoped `CommandsMixin`.
  NeoForge also captures block place,
  block break, block interaction, and player-kill events asynchronously.
- NeoForge captures consume and durability break into the existing quantity-flow
  ledger, and both loaders capture `THROW_ITEM`/`SHOOT_ITEM` at
  `Projectile.shootFromRotation` HEAD with an explicit `UNKNOWN` destination;
  accepted projectile spawns remain separate raw evidence. Fabric captures
  join/quit, chat, block break/interaction, player-kill events through Fabric API callbacks,
  fresh player-owned projectile spawns through `ServerLevelMixin` as accepted-spawn
  evidence, and completed
  `BlockItem.place` actions through `BlockItemMixin`. Fabric also captures normal
  player drops and full or partial pickups through server-only `ServerPlayer.drop`
  and `ItemEntity.playerTouch` return hooks; the returned entity UUID and stack
  count delta are retained as the quantity evidence.
- Fabric captures completed eat/drink consumption through `LivingEntityMixin` at
  `completeUsingItem`'s return boundary. It preserves the original stack in a
  bounded nested capture, matches the HEAD and RETURN callbacks by a stable
  caller class/method marker plus stack depth, and discards stale or ambiguous
  markers instead of pairing the wrong invocation. `ItemStackMixin` captures
  durability breaks at the `hurtAndBreak` shrink boundary, before the broken
  stack is removed.
- Fabric captures custom item entities accepted during `ServerPlayer.die` in a
  bounded death window and deduplicates them against the normal
  `ServerPlayer.drop` path, preserving the returned entity UUID and stack count.
- Fabric result-slot mixins capture crafting, furnace-family smelting, and anvil
  rename/repair results into `ig_item_transformations` with canonical source and
  result fingerprints.
- Fabric `HopperBlockEntityMixin` captures successful vanilla hopper transfers as
  endpoint-unknown `HOPPER_INSERT`/`HOPPER_EXTRACT` net deltas, preserving quantity
  without attributing automation to a player.
- Both loaders expose the same per-player inspector: NeoForge uses its high-priority
  `InspectionListener`, while Fabric uses `UseBlockCallback`/`AttackBlockCallback`;
  each delegates to `ItemGraphCommands.openBlockInspection` and the shared exact-position
  `UnifiedEvidenceQueryService` page. The page merges block events, either-endpoint
  container deltas, transformations, and imported GriefLogger event rows. Query rejection
  preserves normal gameplay. V19 appends native and imported-row supersession links when
  a block/door is removed. Automated loader/query tests pass; the local server/client
  matrix and independent evidence audit remain open under #26.
- `/ig lookup <eventType> [limit] [sinceMinutes]` and
  `/ig lookup player <playerName> <eventType> [limit] [sinceMinutes]` return
  bounded native audit evidence; `/ig lookup near` adds exact dimension and
  bounded radius filters.
- `/ig lookup filters <filter1> ... <filter5>` implements the published
  GriefLogger `name.value` vocabulary for action, user, include, exclude, time,
  and required radius filters. `UnifiedEvidenceQueryService` merges native audit,
  item observations, transformations, and normalized historical `GRIEFLOGGER`
  event projections on the bounded query worker, preserving source and prefixed evidence
  IDs. Generic rows in `ig_grieflogger_rows` remain the immutable source ledger; exact
  source-hash/table/key queries expose reference and identity rows as provenance-only.
  The query
  is bounded to 20 rows, uses a 1..1024 cube around the issuing player, and rejects
  include/exclude conflicts before SQL dispatch.
- Player lookup queries are cancelled after five seconds through the SQLite progress
  handler, preserving the bounded worker queue under selective or poorly indexed scans.
- Canceled chat, command, death, and block events are excluded; command and block
  callbacks are labeled attempts where the loader hook is pre-action, and canceled
  actions remain distinct from completed evidence.

The Fabric mixins are deliberate: Fabric API's server message callbacks cover only
command-generated broadcasts, not general command execution. The mixin records the
Minecraft `Commands.performCommand` entry boundary and never labels a command as
successful. It is isolated to the Fabric adapter and covered by a recorder regression
test; replacing it with a broader mixin would increase the false-success surface.
The placement mixin follows the same boundary rule: it records only a successful
`BlockItem.place` return and the newly occupied cells in its bounded 5x5x5
before/after snapshot. Fabric's
official [1.21.1 event documentation](https://github.com/FabricMC/fabric-docs/blob/main/versions/1.21.1/develop/events.md)
says areas without API hooks should use a mixin;
there is no completed block-placement callback in the interaction events.
GriefLogger's [command documentation](https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands)
states that every command attempt is recorded regardless of permission or command
success, so the pre-execution semantics are the parity target. The pre-execution
semantics are also documented by NeoForge's
[`CommandEvent`](https://raw.githubusercontent.com/neoforged/NeoForge/1.21.1/src/main/java/net/neoforged/neoforge/event/CommandEvent.java),
and the Fabric API limitation is documented by Fabric's
[`ServerMessageEvents`](https://raw.githubusercontent.com/FabricMC/fabric-api/0.116.12+1.21.1/fabric-message-api-v1/src/main/java/net/fabricmc/fabric/api/message/v1/ServerMessageEvents.java),
whose server command event covers broadcast messages rather than general command
execution. Fabric's drop and pickup hooks use the same narrow boundary rule:
`ServerPlayer.drop` is paired with the `ServerLevel.addFreshEntity` boolean
result before a ground entity is recorded, while the `ItemEntity.playerTouch`
before/after count delta proves how many items were actually absorbed. Drops
observed while `ServerPlayer.isDeadOrDying()` are labeled `DEATH_DROP`; custom
item entities accepted during `ServerPlayer.die` are captured in a bounded death
window and deduplicated against the normal drop hook. Vanilla Fabric hopper
transfers now emit endpoint-unknown net deltas through `HopperBlockEntityMixin`.
GriefLogger has no hopper or mechanical-automation event, so modded automation
adapters are supplemental work outside the replacement-parity gate.

### Remaining parity slices

- A documented native-only migration/retention plan remains the operator
  cutover gate. The GriefLogger-absent Fabric replay now verifies the native
  audit, item-action, container, hopper, transformation, lookup, and inspector
  paths listed in `docs/GRIEFLOGGER_PARITY.md`. Modded automation adapters are
  optional supplemental work because GriefLogger has no equivalent event
  coverage.

Prefer small commits such as:

```text
chore: scaffold itemgraph neoforge project
feat: add observation persistence model
feat: add read-only grief logger adapter
feat: add item fingerprint canonicalization
feat: add basic transfer correlation
feat: add ig trace and event commands
feat: add explainable inferred edges
test: add stack split and merge scenarios
feat: add entity uuid tracking and armor stand listener
feat: add transformation tracking for anvil and crafting
feat: add audit service and ux trace commands
```

Avoid giant mixed commits.

## Definition of MVP complete

MVP is complete when:

- staging demonstrates named-item tracing
- ordinary quantity flow works
- evidence and inference are separately visible
- `/ig explain` is useful
- persistence survives restart
- GriefLogger remains untouched
- performance is acceptable on staging
- Phases 1 through 10 fully implemented and verified

# Release 0.3.2 Multi-Loader and Hexagonal Migration

## Reconnaissance snapshot (2026-09-28)

- Repository: `DurdeuVlad/itemgraph`, remote `origin` at `https://github.com/DurdeuVlad/itemgraph`.
- Starting point: published tag `v0.3.1`, commit `b937563`; work branch `feature/hexagonal-fabric-032`.
- The only local untracked path at the start was `.vscode/`; it is user workspace data and must remain untracked.
- Runtime/build: Java 21, starting Gradle wrapper 8.10.2, Minecraft 1.21.1, NeoForge 21.1.248, ItemGraph 0.3.1. Fabric Loom 1.10.5 refuses Gradle 8.10.2 and requires Gradle 8.12, so the wrapper is being raised to 8.12.1.
- Current loader support: NeoForge only. There is no Fabric module, Fabric metadata, Fabric Loom plugin, or Fabric CI job.
- Current Gradle model: root `build.gradle` applies ModDevGradle 2.0.78 to the root `src/main/java` source set. The root project is therefore the NeoForge mod rather than a platform-neutral application.
- Source coupling: 101 production Java sources across `api`, `audit`, `canon`, `command`, `config`, `correlation`, `db`, `graph`, `ingest`, `listener`, `query`, and `tracker`. 24 source files directly import Minecraft, NeoForge, or Brigadier types. Loader-specific code currently includes the mod entry point, config, database path resolution, canonicalization, commands/UI, API lifecycle/facade, and event listeners.
- CI `.github/workflows/ci.yml` runs `clean build verifyGriefLoggerCompatibleJar` on PRs and pushes to `main`, and uploads only the GriefLogger-compatible NeoForge jar.
- Release `.github/workflows/publish.yml` builds on `v*` tags, checks the tag against `gradle.properties`, extracts the matching `CHANGELOG.md` section, publishes GitHub and all four CurseForge files, and gates the four Modrinth uploads on successful project and version API responses. `origin/main` at 0.3.0 contains the previous Modrinth publishing step and project slug `itemgraph`; this is evidence to restore it for the next release, not approval for removing it.
- Release artifact requirements: Fabric 1.21.1 standard; Fabric 1.21.1 GriefLogger 1.2.10-compatible; NeoForge 1.21.1 standard; NeoForge 1.21.1 GriefLogger 1.2.10-compatible. Both compatible variants omit ItemGraph's nested/bundled SQLite and declare GriefLogger required. This mirrors GriefLogger's own Fabric/NeoForge builds, which use loader-specific jars and shade SQLite into the Fabric artifact (official source: [GriefLogger Fabric build](https://github.com/DAQEM/GriefLogger/blob/master/fabric/build.gradle)).
- Prior staging reconnaissance is recorded in `docs/PHASE0_RECON_REPORT.md` and dated 2026-09-15. It reports GriefLogger 1.2.10, SQLite, and event coverage, but this release task has not reconnected to staging; the older observations are not represented as freshly verified.

## Hexagonal target and adapter boundaries

Use a pure Java core containing domain records, deterministic correlation/query logic, application use cases, and ports. The core must not import `net.minecraft.*`, `net.neoforged.*`, `net.fabricmc.*`, Brigadier, or loader lifecycle classes. Persistence and platform integrations implement ports outside the core. Gradle must build independent Fabric and NeoForge mod jars from their own entry points and metadata; neither jar may contain the other loader's metadata or classes.

The Fabric and NeoForge adapters provide server lifecycle, command registration, item/component canonicalization, event capture, server path/config access, and Minecraft-specific API conversions. Shared JDBC persistence, GriefLogger read-only ingestion, graph reconstruction, and query use cases stay in loader-neutral shared code where their dependencies allow it. Fabric and NeoForge both provide native audit callbacks; loader-specific gaps are tracked in `docs/GRIEFLOGGER_PARITY.md`. Both loaders have a GriefLogger 1.2.10-1.21.1-compatible artifact.

Reference pattern: the maintained multi-loader Minecraft template uses a loader-free `common` module plus separate `fabric` and `neoforge` projects; Fabric Loom documents that multi-project mods list all participating source sets in `loom.mods`. ItemGraph's hexagonal boundary is stricter than the template's common source set: only adapter projects may bind to loader APIs, and the core is plain Java. Source references: [Player005 multi-loader template for 1.21.1](https://github.com/Player005/multiloader-mod-template/tree/1.21.1) and [Fabric Loom classpath groups](https://docs.fabricmc.net/develop/loom/classpath-groups).

## Release naming and publication contract

- Git tag and changelog heading: `v0.3.2` and `## [0.3.2]`.
- Archive names must expose loader and provider: `itemgraph-0.3.2-fabric.jar`, `itemgraph-0.3.2-fabric-grieflogger-compatible.jar`, `itemgraph-0.3.2-neoforge.jar`, and `itemgraph-0.3.2-neoforge-grieflogger-compatible.jar`.
- CurseForge: publish each archive as a separate file with Minecraft `1.21.1`, the correct loader tag (`Fabric` or `NeoForge`), dedicated-server environment, and an explicit display name naming loader and GriefLogger status. Only the two compatible files declare GriefLogger `1.2.10-1.21.1` required; each standard file retains optional dependency metadata.
- Modrinth: publish four separately named versions with exact loader arrays and Minecraft `1.21.1`. Use distinct, concise SemVer build metadata numbers (`0.3.2+fabric`, `0.3.2+fabric-gl`, `0.3.2+neoforge`, and `0.3.2+neoforge-gl`) because loader/dependency variants are separate Modrinth versions. Keep publishing on the existing project slug `itemgraph` while project authorization is pending.
- GitHub Release: attach all four archives and include the exact 0.3.2 changelog section.
- CI verification must assert each artifact's loader metadata, mod ID, Minecraft range, version, filename, and absence of foreign-loader metadata. It must assert each GriefLogger-compatible jar requires `1.2.10-1.21.1` and omits SQLite, while each standard jar retains its standalone SQLite dependency.
- Verification status: `.\\gradlew.bat clean build` completed successfully on 2026-09-28. This runs the NeoForge test suite, compiles Fabric, verifies all four output jars, and checks the `core` and `common` loader boundaries. `git diff --check`, YAML parsing of all workflows, four-jar contents/metadata inspection, and the 0.3.2 changelog heading check passed. Both GriefLogger-compatible release jars also started dedicated 1.21.1 staging servers on loopback only with GriefLogger `1.2.10-1.21.1`, initialized ItemGraph SQLite schema 12, reached `Done`, and shut down cleanly. The Fabric fresh-database first pass logged two missing-table reads during initial setup; a second start against the initialized GriefLogger database reached `Done` without those warnings. GitHub Actions run `36421275121` built all four artifacts and published the GitHub release and four CurseForge files (IDs `8999438`–`8999441`). Modrinth uploads returned 401 permission errors and the long compatible version numbers returned 400 length validation errors; compatible identifiers have since been shortened to `+fabric-gl` and `+neoforge-gl`. Both release workflows now gate Modrinth on successful project and version API responses, record the HTTP statuses in the job summary, and skip Modrinth without failing GitHub or CurseForge when the API returns an error. `.github/workflows/republish-modrinth.yml` provides a Modrinth-only rerun for an existing, version-matched release tag without re-uploading to CurseForge. Its `variant` input selects `all` or one artifact, a successful preflight rejects any selected version already listed, and same-version retries are serialized. Run it after `MODRINTH_TOKEN` has `VERSION_CREATE` permission for the approved `itemgraph` project. The public `itemgraph` project lookup returned 404 during this verification, so publication is currently disabled by the gate.


# M11 Admin-first investigation UX

GitHub milestone [M11: Admin-first investigation UX](https://github.com/DurdeuVlad/itemgraph/milestone/7) groups two admin-facing changes:

- [#142](https://github.com/DurdeuVlad/itemgraph/issues/142): a task-based quick start and current-feature map, grounded in the registered command tree and current permission behavior. Existing #11 remains the exhaustive syntax-reference issue.
- [#143](https://github.com/DurdeuVlad/itemgraph/issues/143): route a right-click on a `Container` block entity to the shared vanilla flow browser while preserving existing block-history inspection for left-clicks and non-container targets.

The user asked to run expensive validation once at the end of a milestone. Therefore the NeoForge/Fabric full unit and GameTest suites, client UX replay, and final adversarial review are one M11 end batch after both issues are implemented. Focused source and documentation review continues during implementation. No production server, release artifact, version bump, or publication is in scope.
