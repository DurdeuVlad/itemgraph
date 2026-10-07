# GriefLogger and ItemGraph Feature Inventory

**Reviewed:** 2026-10-06

**ItemGraph snapshot:** `main`, `c3e9bc969882724582ab9ac722f159384094f36a`

**GriefLogger target:** published `1.2.10-1.21.1` Fabric and NeoForge artifacts, pinned in [`docs/grieflogger-fixtures/1.2.10-1.21.1.json`](grieflogger-fixtures/1.2.10-1.21.1.json)
**GriefLogger source reviewed:** ref `26.2`, commit [`d315098b3f37317a5cddfbd75086f4f912f16a83`](https://github.com/DAQEM/GriefLogger/commit/d315098b3f37317a5cddfbd75086f4f912f16a83)

This inventory separates the exact 1.21.1 release target from the later 26.2 source tree. The source tree is useful for understanding behavior, but it is not proof that the published 1.21.1 jars contain that behavior. The exact release fixture remains authoritative for target-specific action writers and metadata. Neither GriefLogger nor its database was loaded or modified for this review.

## GriefLogger feature inventory

The official feature pages describe block actions, item usage, player sessions, chat and commands, inspector, filtered lookup, and SQLite/MySQL storage ([overview](https://daqem.com/projects/grieflogger), [block interactions](https://daqem.com/projects/grieflogger/wiki/player-actions/block-interactions), [item usage](https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage), [lookup](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command)). The source audit below includes code-backed behavior that is narrower or more specific than those summaries.

### Recorded activity

| Feature | What GriefLogger records | Source/release qualification |
| --- | --- | --- |
| Block placement | Player, block/material, dimension, coordinates, timestamp; bucket liquid placement is represented as `PLACE_BLOCK`. | Shared block event and loader-specific bucket mixins. |
| Block breaking | Player-attributed block/material break and location; bucket pickup of source fluid is represented as `BREAK_BLOCK`. | Also has source hooks for explosions and Enderman take/place behavior. Verify each hook against the exact release fixture before treating it as 1.21.1 target coverage. |
| Functional-block interaction | Main-hand right-click attempt against a hard-coded set of 28 vanilla functional blocks; records block/player/location. | The callback runs before the use result is known, so it means attempt, not successful use. Modded functional blocks are not generally discovered. |
| Player-caused entity death | `KILL_ENTITY`, with killer, entity type, location and time. | Exact-release writer is present. |
| Container inventory changes | Per-item net `ADD_ITEM`/`REMOVE_ITEM` quantities between opening and closing a supported block-container GUI. On a player break of a container block entity, `BreakContainerEvent` also writes `REMOVE_ITEM` rows for its contents before the block is removed. | Close-time session delta, not click history; zero-net take-and-return is invisible. Generic modded inventories/backpacks are not generally supported. The exact-release action-writer audit lists `BreakContainerEvent.class` as a `REMOVE_ITEM` writer. |
| Player item changes | `ADD_ITEM`, `REMOVE_ITEM`, `DROP_ITEM`, `PICKUP_ITEM`, `CRAFT_ITEM`, `BREAK_ITEM`, `CONSUME_ITEM`, `THROW_ITEM`, and `SHOOT_ITEM`. | Item actions are aggregated by player tick and item/component equality. Crafting and smelting both map to `CRAFT_ITEM`. Projectile actions are recorded at an attempt boundary before spawn acceptance is known. |
| Player sessions | Join and quit with player, position and time. | Current name and historical usernames are retained separately. |
| Chat | Public chat text, player, position and time. | Stored in a separate `chats` table; excluded from GriefLogger’s in-game lookup. |
| Commands | Player command text, position and time at the command event. | Captures command attempts; it does not prove command success or resulting world mutation. Stored in `commands`, outside the documented GriefLogger lookup. |
| Entity interaction | The 26.2 source includes an armor-stand interaction hook for successful `interact` results. | The exact 1.2.10-1.21.1 release has no entity-interaction action ID or writer. Not a required feature of the pinned release target. |
| Ender inventory actions | The 26.2 action enum declares `ADD_ITEM_ENDER` and `REMOVE_ITEM_ENDER`. | Source and both exact release jars have no event writer for these enum values. They are not a release feature. |

### Commands and moderator interface

| Surface | Behavior |
| --- | --- |
| Command roots | `/grieflogger` and `/gl`; child commands `inspect`, `lookup`, `page`. |
| Permissions | Root and child commands use named `grieflogger.command`, `.inspect`, `.lookup`, and `.page` permission nodes with permission-level-2 fallback. |
| Lookup filters | Space-separated `action`, `include`, `exclude`, `radius`, `time`, and `user` filter families; up to five filters; AND semantics; a radius is required. Filter values have suggestions. |
| Lookup results | Merges block, session, container and item history, ordered newest first. A default page size of 10 is bounded by `maxPageSize`; `/gl page <number>` and clickable page arrows navigate stored pages. |
| Inspect | `/gl inspect` toggles player-specific inspect mode. Left click shows block history; right click selects supported interactive blocks, containers, double chests and doors. Inspection clicks are consumed while inspecting. |
| Result presentation | Pinned 26.2 source and official feature pages describe localized/color-styled chat, item and block hover details, timestamps, clickable page controls, and clickable coordinate text that runs a teleport command. These are later-source/documentation claims; the exact 1.2.10-1.21.1 fixture does not verify each presentation detail. |

### Storage, configuration and runtime

- SQLite by default or MySQL when `database.useMysql=true`; configuration includes MySQL host, port, database, username, password and connection timeout.
- The pinned exact-release fixture records `useMysql=false`, `mysqlHost=localhost`, `mysqlPort=3306`, `mysqlDatabase=database`, `mysqlUsername=username`, `mysqlPassword=password`, `mysqlTimeout=5000`, `useIndexes=true`, `maxPageSize=10`, `serverSideOnlyMode=true`, `queueFrequency=20`, and `helloFrequency=600`. In the pinned source, `maxPageSize` permits 1–100, `queueFrequency` permits 1–100 ticks, and `helloFrequency` permits 1–1000 ticks. The fixture is authoritative for default values; the pinned 26.2 source is only behavior research for the ranges.
- `server.language` accepts `en_us`, `nl_nl`, and `zh_tw` in the pinned source. The pinned source also packages `zh_cn`. Its `LanguageManager` can download/cache Minecraft language data; this is source behavior and is not asserted as exact-release behavior without fixture evidence.
- Six event tables: `blocks`, `items`, `containers`, `sessions`, `chats`, `commands`. Five reference/identity tables: `users`, `usernames`, `levels`, `materials`, `entities`.
- Numeric lookup/reference IDs and item component/NBT payloads support rendering and filters. SQLite and MySQL dialects are implemented in the shared database layer.
- Tick-driven write queue and a shared database lock keep writes off event callbacks. The source exposes queue cadence but no explicit queue capacity/backpressure bound. Inspect/lookup database work is dispatched through its thread manager.
- Breaking a block or door removes prior interaction rows at that location. The database is therefore not an immutable forensic ledger.
- The source has no stable public integration API; its API guidance warns that internal service integrations can break across updates.

## ItemGraph feature inventory

This section was independently inventoried by a read-only subagent against the exact ItemGraph snapshot listed above and reconciled with the production source, current parity documents, test plan and open issues.

### Implemented capabilities

| Feature | ItemGraph behavior | Evidence and limits |
| --- | --- | --- |
| Standalone two-loader operation | Native Fabric and NeoForge 1.21.1 entry points share core evidence/query logic; native capture and storage do not require the GriefLogger jar or database. `/itemgraph` and `/ig` are ItemGraph roots. | ItemGraph-only local loader reports and CI. Optional GriefLogger import is disabled by default. The source database remains read-only when import is explicitly enabled. |
| Canonical item metadata | Deterministic fingerprints cover registry identity and canonical data components; no permanent UUID is assigned to every item. | `ItemCanonicalizer`, its tests, and `docs/EVIDENCE_MODEL.md`. Fingerprint equality is matching evidence, not proof of physical-item identity. |
| Temporal quantity-flow graph | Observations connect player, container, ground, armor-stand, unknown and registered external-inventory nodes. A durable allocation ledger supports partial flows, splits and merges without exceeding observed quantity. | `CorrelationEngine`, `ig_edge_allocations`, correlation and quantity-flow tests. Edges are inferred separately from raw observations and carry evidence IDs, deterministic confidence factors and explanations. |
| Movement capture | Player/container session deltas, capability insert/extract, drops, pickups, partial/death drops, Ender Chest session deltas, consumption, durability breaks, throw/shoot attempts, and per-slot contents removed by successful player container breaks are recorded. | Loader listeners and mixins listed in `docs/ARCHITECTURE.md` and `docs/GRIEFLOGGER_PARITY.md`. Container observations are interval net deltas; generic capability caller/cause remains unknown. Broken-container rows preserve slot provenance, use the container as source and `UNKNOWN` as destination, and do not attribute drops to the player. Projectile attempt is separate from accepted-spawn evidence. |
| Transformations | Anvil rename/repair have source/result lineage. Craft/smelt result takes are protected unresolved output evidence; full inputs are not reconstructed. | `TransformationEventListener`, Fabric result-slot/menu hooks, `CRAFT_OUTPUT_UNRESOLVED`, and `SMELT_OUTPUT_UNRESOLVED`. Legacy craft/smelt rows are excluded from trace lineage. |
| Separate audit ledger | Player joins/quits, chat, command attempts, block place/break/interaction, kills, entity-interaction attempts/results, projectile attempts and accepted projectile spawns are kept in `ig_audit_events`, not represented as item quantities. | Shared taxonomy and both loader listeners; #31 report is a bounded native-only replay, not a live GriefLogger differential run. |
| Administrative and creative mutations | `AdminMutationCapture` and loader hooks capture supported `/give`, `/clear`, `/item`, creative-slot and creative block mutations. Attempts, effects, failures and unresolved causes have separate records/links; supported quantity deltas are not inferred from command text alone. | Current source includes `AdminMutationCapture`, Fabric/NeoForge admin mixins and `AdminMutationConformanceFixture`. Some causes remain explicitly undifferentiated (for example creative clone versus pick-block). Issue #33 is closed; PR #122 merged the cross-loader acceptance contract. |
| Query and inspector | Permission-checked traces, audit lookup, filtered/global evidence lookup, explanation, item/player/container history, pagination, `/inspect`, and a read-only vanilla flow browser. The query merge can include native audit, item observations, transformations and normalized imported rows. | Query/command tests plus the 2026-10-04 connected MC Pilot replay. Clients were instrumented; this is not an unmodified vanilla-client claim. PR #139 closed #24 with the selected command and inspector evidence. #31 closed with the bounded native-only release-contract and invariant report; it is not a live GriefLogger differential run. |
| Historical import | An opt-in importer validates the supported legacy schema, retains provenance/raw rows, checkpoints resumable table scans, and builds bounded lookup projections. | `GriefLoggerHistoricalImporter`, `GriefLoggerHistoricalProjection`, ItemGraph-owned migration tables. It is a migration path; it is neither code reuse nor a runtime dependency. |
| Mod integration API | `PREVIEW_1` source registration, bounded observation submission and asynchronous typed flow queries use service-issued handles and DTOs; caller-supplied inference and JDBC access are not exposed. | `ItemGraphService`, `ItemGraphServiceImpl`, API docs and consumer fixture. A fixture does not imply automatic support for third-party inventory mods. |
| Storage and operational controls | ItemGraph-owned schema migrations, SQLite and MySQL/MariaDB, optional indexes, bounded ingestion and query workers, async heavy queries, cancellation, config validation, status diagnostics, audits and indefinite raw evidence retention. | Migrations through V21, CI backend jobs, invalid-config probes and operational GameTests. Issue #29 is closed (SQLite/MySQL/MariaDB contract delivered in PR #79); #30 closed with hosted MySQL/MariaDB heartbeat CI. CI timings are not production server budgets; hard shutdown deadline for stalled JDBC I/O is not established. |
| Build/release controls | Standard standalone loader jars and temporary GriefLogger-compatible variants have distinct dependency metadata. CI builds/tests both loaders and verifies fixture, packaging and reports without packaging distributable jars. | Gradle and GitHub workflows. Compatible variants remain until M10 cutover gates and an explicit version bump; no artifact was built for this inventory. |

### ItemGraph advantages beyond GriefLogger’s exact release feature set

- Reconstructs temporal item paths rather than only listing local action rows.
- Makes observed facts, inferred edges, ambiguous alternatives and unresolved events distinct.
- Explains each inferred edge with exact evidence IDs and deterministic factors.
- Enforces quantity conservation and temporal ordering.
- Preserves source/result lineage for supported transformations; unknown craft/smelt inputs remain unresolved and never become a trace edge.
- Keeps raw evidence indefinitely and preserves removed-block history through append-only supersession links.
- Adds hopper evidence, Ender-session evidence, armor-stand/entity interaction evidence and a typed integration API as ItemGraph-owned extensions; these do not get mislabeled as GriefLogger release events.
- Adds bounded queues, asynchronous query dispatch, privacy controls, schema audits, and redacted CI conformance reports.

### Gaps and work already tracked

ItemGraph is **not yet a complete replacement**. The M8 milestone (standalone GriefLogger replacement parity) is closed with all 20 issues delivered, but the replacement goal additionally requires the open M9 extension/operations issues and the M10 cutover gates. As of this review every confirmed gap already has an owner; no untracked gap was found.

Closed since the previous review (2026-10-04): #24 and #31 (command/lookup/inspector parity and the bounded native-only release-contract report), #136 (offline `en_us`/`nl_nl`/`zh_tw` localization), #137 (named permission nodes), #138 (rich history hover and safe location navigation), #140 (player-broken container contents), #146 (scannable flow-browser rows), and #33 (creative/admin mutations, PR #122). M8 milestone #4 is closed.

| Gap | Existing work | Current boundary |
| --- | --- | --- |
| Measured staging budgets: idle baseline, third-party adapter workload, real moderator-query tick impact, stalled-JDBC shutdown bound | [#32](https://github.com/DurdeuVlad/itemgraph/issues/32) | Open; no open PR. Synthetic CI/local measurements are regression evidence, not staging-derived production budgets. Blocks #58 optimization selection and the #71 cutover gate. |
| Shared event taxonomy and reason-code contract | [#35](https://github.com/DurdeuVlad/itemgraph/issues/35) | Open tracker; owns the taxonomy vocabulary and cannot close until #55, #56, and #57 are delivered or carry explicit unsupported dispositions. |
| Exact modded inventory/automation endpoints | [#34](https://github.com/DurdeuVlad/itemgraph/issues/34) | Open; draft PR #123. Vanilla capability coverage does not establish arbitrary backpack, faction or third-party inventory adapters. |
| World/environmental causes: explosions, fire/fluids, pistons, Enderman, falling blocks, non-player placement | [#55](https://github.com/DurdeuVlad/itemgraph/issues/55) | Open; draft PR #130. Exact-release fixture proves `PlaceBlockEvent` writer class presence only; non-player placement and actor-owner attribution remain unverified for 1.21.1 and are classified research-only until #71's byte-level classification resolves them. |
| Entity/projectile lifecycle: spawn/removal/death, impact, item-entity merge/despawn | [#56](https://github.com/DurdeuVlad/itemgraph/issues/56) | Open; no open PR. #73/#75 attempt and interaction-outcome evidence is prerequisite, not lifecycle coverage. |
| Item-processing/economy transformations: trades, enchanting, brewing, smithing, grindstone, loot | [#57](https://github.com/DurdeuVlad/itemgraph/issues/57) | Open; no open PR. Anvil rename/repair lineage is delivered; craft/smelt stay output-only unresolved evidence (#162/#163 closed). |
| Cross-loader integration contracts and adapter fixtures | [#36](https://github.com/DurdeuVlad/itemgraph/issues/36) | Open; draft PR #124 (stacked on #123). NeoForge preview API exists; the published cross-loader contract is not complete. |
| Tamper-evident operator incident export | [#37](https://github.com/DurdeuVlad/itemgraph/issues/37) | Open; draft PR #124. CI conformance reports are not an operator export/chain-of-custody feature; `/ig export` is not registered. |
| First-class ambiguous/unresolved query results | [#44](https://github.com/DurdeuVlad/itemgraph/issues/44) | Open; draft PR #124. Internal uncertainty exists; this issue owns the complete first-class state/reason surface. |
| Component-aware and absolute-time filters | [#45](https://github.com/DurdeuVlad/itemgraph/issues/45) | Open; draft PR #125 (stacked on #124). Current six-family relative filters do not prove these features shipped. |
| Measured hot-path optimization and retention/archive/compaction | [#58](https://github.com/DurdeuVlad/itemgraph/issues/58) | Open; no open PR. Blocked on #32 baseline plus #34 and #44 contracts; must preserve indefinite raw evidence retention. |
| Integrated first-time-admin UX closeout | [#153](https://github.com/DurdeuVlad/itemgraph/issues/153) | Open; draft PR #161. Non-visual findings resolved; current connected-client screenshots at 854x480 and 1280x720 are still required. The existing 1280x720 captures were overwritten during an interrupted test and show an unprivileged command error; they are not acceptance evidence. |
| Native-only cutover approval gate | [#71](https://github.com/DurdeuVlad/itemgraph/issues/71) | Open (M10); no open PR. Owns residual exact-release classification (non-player placement predicates, locale inventory, presentation detail), required #32 staging evidence, 24-hour native-only soak, archive checksum, and rollback rehearsal. |
| Compatible-artifact retirement | [#72](https://github.com/DurdeuVlad/itemgraph/issues/72) | Open (M10); no open PR. Blocked by #71 and an explicit maintainer version bump/tag. |

Known limitations that are explicitly not parity gaps: the GriefLogger importer reads SQLite sources only (`GriefLoggerAdapter` opens `jdbc:sqlite:file:...?mode=ro`); direct MySQL-source import is not claimed — GriefLogger itself has no export/import feature, so this is an ItemGraph migration-aid boundary rather than a parity gap. `serverSideOnlyMode=false` is a rejected strict invariant; ItemGraph is server-side only.

These issues are assigned to the existing M9 "ItemGraph audit++ performance and extra events" (milestone #5) and M10 "Native-only cutover and release hardening" (milestone #6). No duplicate milestone or catch-all issue was created. The accepted M9 execution cohorts and the single milestone-end expensive test batch are recorded in milestone #5 and issue #35; expensive cross-loader load/replay/export verification runs once after all scoped M9 issues land.

## Comparative conclusion

ItemGraph already offers a materially stronger item-investigation model than GriefLogger’s exact 1.21.1 release, and the M8 standalone-parity milestone is closed: every exact-release command, filter, inspector, storage, configuration, and writer-backed action surface has a delivered ItemGraph counterpart, with the two Ender enum IDs and `INTERACT_ENTITY` verified `unsupported-no-writer` in the release bytes. The full replacement claim still requires the open M9 extension, operations, and UX issues plus the M10 cutover evidence. Therefore: **ItemGraph covers the exact GriefLogger 1.2.10-1.21.1 feature profile and adds capabilities GriefLogger lacks, but “better GriefLogger replacement in production” remains unproven until the staged performance, cutover, and UX acceptance evidence lands.** Close the remaining M9 issues with their required evidence, then pass the M10 cutover gates. The `-grieflogger-compatible` artifacts have already been retired — ItemGraph declares the GriefLogger mod incompatible on both loaders and treats its database only as a read-only import source.

### Related implementation research

CoreProtect’s public API separates typed result families and warns integrations to run database lookup work asynchronously after capturing live world state on the server thread ([CoreProtect API v13](https://docs.coreprotect.net/api/version/v13/)). ItemGraph’s `QueryDispatcher` and typed API follow that separation while adding internal bounded dispatch. This comparison is an architectural reference only; it does not make CoreProtect or GriefLogger behavior part of ItemGraph’s compatibility contract.

## Primary code references

- GriefLogger’s pinned source code: [repository tree at the audited commit](https://github.com/DAQEM/GriefLogger/tree/d315098b3f37317a5cddfbd75086f4f912f16a83).
- Exact released GriefLogger action/config/metadata fixture: [`docs/grieflogger-fixtures/1.2.10-1.21.1.json`](grieflogger-fixtures/1.2.10-1.21.1.json).
- Full pinned behavior and release-boundary notes: [`docs/GRIEFLOGGER_SOURCE_AUDIT.md`](GRIEFLOGGER_SOURCE_AUDIT.md).
- Normative action/filter/permission mappings: [`docs/GRIEFLOGGER_COMPATIBILITY.json`](GRIEFLOGGER_COMPATIBILITY.json).
- ItemGraph architecture and evidence contract: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md), [`docs/EVIDENCE_MODEL.md`](EVIDENCE_MODEL.md), [`docs/GRIEFLOGGER_PARITY.md`](GRIEFLOGGER_PARITY.md), and [`docs/TEST_PLAN.md`](TEST_PLAN.md).
