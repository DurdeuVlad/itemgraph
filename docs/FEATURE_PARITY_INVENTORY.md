# GriefLogger and ItemGraph Feature Inventory

**Reviewed:** 2026-10-04

**ItemGraph snapshot:** `codex/issue24-live-client-evidence`, `cfb5c97cf695911f60296bbfbf8583f2fb0242ab`

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
| Movement capture | Player/container session deltas, capability insert/extract, drops, pickups, partial/death drops, Ender Chest session deltas, consumption, durability breaks, and throw/shoot attempts are recorded. | Loader listeners and mixins listed in `docs/ARCHITECTURE.md` and `docs/GRIEFLOGGER_PARITY.md`. Container observations are interval net deltas; generic capability caller/cause remains unknown. The current ordinary player block-break path records `BREAK_BLOCK` but does not capture the destroyed container's full contents. Projectile attempt is separate from accepted-spawn evidence. |
| Transformations | Crafting, smelting, anvil rename and anvil repair have separate source/result lineage. | `TransformationEventListener` and Fabric result-slot/menu hooks. Some transformation classes are source/test supported but absent from the bounded #31 replay; they must not be described as replay-proven. |
| Separate audit ledger | Player joins/quits, chat, command attempts, block place/break/interaction, kills, entity-interaction attempts/results, projectile attempts and accepted projectile spawns are kept in `ig_audit_events`, not represented as item quantities. | Shared taxonomy and both loader listeners; #31 report is a bounded native-only replay, not a live GriefLogger differential run. |
| Administrative and creative mutations | `AdminMutationCapture` and loader hooks capture supported `/give`, `/clear`, `/item`, creative-slot and creative block mutations. Attempts, effects, failures and unresolved causes have separate records/links; supported quantity deltas are not inferred from command text alone. | Current source includes `AdminMutationCapture`, Fabric/NeoForge admin mixins and `AdminMutationConformanceFixture`. Some causes remain explicitly undifferentiated (for example creative clone versus pick-block); issue #33 remains open for its complete cross-loader acceptance contract. |
| Query and inspector | Permission-checked traces, audit lookup, filtered/global evidence lookup, explanation, item/player/container history, pagination, `/inspect`, and a read-only vanilla flow browser. The query merge can include native audit, item observations, transformations and normalized imported rows. | Query/command tests plus 2026-10-04 connected MC Pilot replay. Clients were instrumented; this is not an unmodified vanilla-client claim. The accepted #24/#31 evidence contract remains open until the PR gates finish. |
| Historical import | An opt-in importer validates the supported legacy schema, retains provenance/raw rows, checkpoints resumable table scans, and builds bounded lookup projections. | `GriefLoggerHistoricalImporter`, `GriefLoggerHistoricalProjection`, ItemGraph-owned migration tables. It is a migration path; it is neither code reuse nor a runtime dependency. |
| Mod integration API | `PREVIEW_1` source registration, bounded observation submission and asynchronous typed flow queries use service-issued handles and DTOs; caller-supplied inference and JDBC access are not exposed. | `ItemGraphService`, `ItemGraphServiceImpl`, API docs and consumer fixture. A fixture does not imply automatic support for third-party inventory mods. |
| Storage and operational controls | ItemGraph-owned schema migrations, SQLite and MySQL/MariaDB, optional indexes, bounded ingestion and query workers, async heavy queries, cancellation, config validation, status diagnostics, audits and indefinite raw evidence retention. | Migrations through V21, CI backend jobs, invalid-config probes and operational GameTests. CI timings are not production server budgets; hard shutdown deadline for stalled JDBC I/O is not established. |
| Build/release controls | Standard standalone loader jars and temporary GriefLogger-compatible variants have distinct dependency metadata. CI builds/tests both loaders and verifies fixture, packaging and reports without packaging distributable jars. | Gradle and GitHub workflows. Compatible variants remain until M10 cutover gates and an explicit version bump; no artifact was built for this inventory. |

### ItemGraph advantages beyond GriefLogger’s exact release feature set

- Reconstructs temporal item paths rather than only listing local action rows.
- Makes observed facts, inferred edges, ambiguous alternatives and unresolved events distinct.
- Explains each inferred edge with exact evidence IDs and deterministic factors.
- Enforces quantity conservation and temporal ordering.
- Preserves source/result lineage for item transformations.
- Keeps raw evidence indefinitely and preserves removed-block history through append-only supersession links.
- Adds hopper evidence, Ender-session evidence, armor-stand/entity interaction evidence and a typed integration API as ItemGraph-owned extensions; these do not get mislabeled as GriefLogger release events.
- Adds bounded queues, asynchronous query dispatch, privacy controls, schema audits, and redacted CI conformance reports.

### Gaps and work already tracked

ItemGraph is **not yet complete parity**. The independent comparison found one uncovered exact-release gap and created [#140](https://github.com/DurdeuVlad/itemgraph/issues/140) for it. The other identified gaps already have owners in M8/M9:

| Gap | Existing work | Current boundary |
| --- | --- | --- |
| Finish command semantics, permissions, filters, paging and inspector parity; complete release-contract/native-only evidence | [#24](https://github.com/DurdeuVlad/itemgraph/issues/24), [#31](https://github.com/DurdeuVlad/itemgraph/issues/31) | Connected MC Pilot evidence exists, but issue delivery/review gates remain open. Do not call M8 complete yet. |
| Fine-grained named command permission nodes | [#137](https://github.com/DurdeuVlad/itemgraph/issues/137) | Current user-facing command checks use vanilla permission level 2; the named permission integration is not yet equivalent. |
| Server-side offline localization | [#136](https://github.com/DurdeuVlad/itemgraph/issues/136) | ItemGraph currently emits English literals; no ItemGraph locale catalog/config exists. |
| Rich chat hover details and safe, dimension-aware location navigation | [#138](https://github.com/DurdeuVlad/itemgraph/issues/138) | ItemGraph evidence labels and page controls exist; rich location affordances are deliberately bounded by permission/privacy and remain open. |
| Explosion, environmental, Enderman and moving-block cause evidence | [#35](https://github.com/DurdeuVlad/itemgraph/issues/35), [#55](https://github.com/DurdeuVlad/itemgraph/issues/55) | These event families remain planned or incomplete; do not assume general block-break capture covers them. |
| Entity/projectile lifecycle and item-entity merge/removal/impact outcomes | [#56](https://github.com/DurdeuVlad/itemgraph/issues/56) | Entity interaction attempts are separate M8 work; broader lifecycle/impact evidence is not complete. |
| Item-processing, trade, station and loot transformation coverage | [#57](https://github.com/DurdeuVlad/itemgraph/issues/57) | Craft/smelt/anvil are present; the broader planned families are not established as shipped. |
| Exact modded inventory/automation endpoints | [#34](https://github.com/DurdeuVlad/itemgraph/issues/34) | Vanilla capability coverage does not establish arbitrary backpack, faction or third-party inventory adapters. |
| Container contents when a player destroys the container block | [#140](https://github.com/DurdeuVlad/itemgraph/issues/140) | The exact GriefLogger release writes `REMOVE_ITEM` rows for a broken container's contents. ItemGraph currently records the block break only; it has no corresponding contents-removal or source-to-drop outcome record. |
| Measured server budgets and optimized hot paths | [#32](https://github.com/DurdeuVlad/itemgraph/issues/32), [#58](https://github.com/DurdeuVlad/itemgraph/issues/58) | Synthetic CI/local measurements are regression evidence, not staging-derived production budgets. |
| Cross-loader contributor adapter contract | [#36](https://github.com/DurdeuVlad/itemgraph/issues/36) | Preview API exists; the full published cross-loader adapter contract and fixtures remain open. |
| Tamper-evident operator incident export | [#37](https://github.com/DurdeuVlad/itemgraph/issues/37) | CI reports are not an operator export/chain-of-custody feature. |
| Queryable ambiguity/unresolved outcomes and component-aware/absolute-time filters | [#44](https://github.com/DurdeuVlad/itemgraph/issues/44), [#45](https://github.com/DurdeuVlad/itemgraph/issues/45) | Internal uncertainty and current filters exist; these issues own complete first-class result/filter surfaces. |

These issues are assigned to the existing M8 “Standalone GriefLogger replacement parity” and M9 “ItemGraph audit++ performance and extra events” milestones. No duplicate milestone was created; issue #140 was added for the newly verified container-break gap. M10 cutover and compatible-artifact retirement remain separate and blocked on M8 and required M9 safety evidence.

## Comparative conclusion

ItemGraph already offers a materially stronger item-investigation model than GriefLogger’s exact 1.21.1 release, but it has not reached the full replacement contract. Its advantage is the flow graph, conservation, evidence/inference boundary and explainability; its remaining work is exact command/UI parity and broader event-source, permission, localization, reporting and operational coverage. Therefore: **ItemGraph is a more capable foundation and has features GriefLogger lacks, but “has all GriefLogger features and more” is not yet true.** Close the existing M8/M9 issues with their required evidence before claiming parity or retiring the compatible artifacts.

### Related implementation research

CoreProtect’s public API separates typed result families and warns integrations to run database lookup work asynchronously after capturing live world state on the server thread ([CoreProtect API v13](https://docs.coreprotect.net/api/version/v13/)). ItemGraph’s `QueryDispatcher` and typed API follow that separation while adding internal bounded dispatch. This comparison is an architectural reference only; it does not make CoreProtect or GriefLogger behavior part of ItemGraph’s compatibility contract.

## Primary code references

- GriefLogger’s pinned source code: [repository tree at the audited commit](https://github.com/DAQEM/GriefLogger/tree/d315098b3f37317a5cddfbd75086f4f912f16a83).
- Exact released GriefLogger action/config/metadata fixture: [`docs/grieflogger-fixtures/1.2.10-1.21.1.json`](grieflogger-fixtures/1.2.10-1.21.1.json).
- Full pinned behavior and release-boundary notes: [`docs/GRIEFLOGGER_SOURCE_AUDIT.md`](GRIEFLOGGER_SOURCE_AUDIT.md).
- Normative action/filter/permission mappings: [`docs/GRIEFLOGGER_COMPATIBILITY.json`](GRIEFLOGGER_COMPATIBILITY.json).
- ItemGraph architecture and evidence contract: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md), [`docs/EVIDENCE_MODEL.md`](EVIDENCE_MODEL.md), [`docs/GRIEFLOGGER_PARITY.md`](GRIEFLOGGER_PARITY.md), and [`docs/TEST_PLAN.md`](TEST_PLAN.md).
