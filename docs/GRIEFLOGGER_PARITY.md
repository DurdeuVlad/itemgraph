# GriefLogger replacement parity

This document defines the compatibility contract and acceptance boundary for
replacing GriefLogger as the native server audit source. Compatibility is
behavioral and evidence-preserving; ItemGraph keeps its own command names and
storage. The matrix below is based on GriefLogger's published feature surface:
block actions, item usage, player sessions, chat, commands, inspector, filtered
lookup, pagination, and SQLite/MySQL storage.

## Branding contract

- Supported commands are `/ig` and `/itemgraph`.
- `/gl` and `/grieflogger` are not ItemGraph commands or aliases.
- A GriefLogger database is a read-only source. ItemGraph never repairs,
  migrates, writes, deletes, indexes, vacuums, or purges it.
- ItemGraph labels direct observations, inferred movement, ambiguous candidates,
  and unresolved events separately. Non-quantity audit evidence is never
  presented as an item transfer.

## Registry

The normative machine-readable mapping is
[`GRIEFLOGGER_COMPATIBILITY.json`](GRIEFLOGGER_COMPATIBILITY.json). It records
canonical action names, accepted GriefLogger spellings, compatibility status,
evidence and quantity semantics, loader/storage support, lookup filters,
permission and paging controls, inspector behavior, configuration controls, and
the GitHub issue responsible for incomplete mappings.
The current registry compatibility version is `m8.2.1`.

The registry version changes when a mapping, status, evidence or quantity
meaning, loader, or backend contract changes. Documentation-only clarifications
are patch changes; additive mappings with existing behavior are minor changes;
renamed, removed, or incompatible mappings are major changes. The registry,
this document, and the owning issue change together.

## Published source surface

The contract uses GriefLogger's published documentation:

- https://daqem.com/projects/grieflogger
- https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage
- https://daqem.com/projects/grieflogger/wiki/player-actions/block-interactions
- https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions
- https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands
- https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command
- https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/inspect-command
- https://daqem.com/projects/grieflogger/wiki/getting-started/configuration

### Pinned source baseline

The source audit is pinned to GriefLogger ref `26.2`, commit
`d315098b3f37317a5cddfbd75086f4f912f16a83` ([source tree](https://github.com/DAQEM/GriefLogger/tree/26.2)), retrieved 2026-09-29.
The complete feature inventory and the 26.2-dev versus ItemGraph 1.21.1 compatibility
boundary are recorded in [the source audit](GRIEFLOGGER_SOURCE_AUDIT.md).
The source defines 18 actions across block, session, and item enums and creates
11 tables: `items`, `containers`, `blocks`, `sessions`, `chats`, `commands`,
`users`, `usernames`, `levels`, `materials`, and `entities`. The profile and
contradiction matrix are tracked in [issue #43](https://github.com/DurdeuVlad/itemgraph/issues/43).

### Exact 1.21.1 release fixture

The release target for drop-in compatibility is GriefLogger `1.2.10-1.21.1`,
for both Fabric and NeoForge. The checked-in
[`1.2.10-1.21.1 release fixture`](grieflogger-fixtures/1.2.10-1.21.1.json)
records the official Modrinth file IDs, URLs, byte sizes, SHA-1/SHA-256/SHA-512
digests, loader metadata, Java 21 mixin contracts, embedded SQLite 3.47.2.0
and MySQL Connector/J 8.4.0 versions, action IDs, all 11 table column layouts,
commands, configuration defaults, and inspector behavior. Its canonical
fixture digest is
`160f77435c9527304adba691388295ead00af40db482338fcbf87951d929e648`.

The exact published artifacts are the authority for the 1.21.1 target. The
Git tag named `1.2.10-1.21.1` points at source metadata from the later 26.2
development line, so that source tree is retained as behavior research and is
not treated as binary compatibility evidence. CI runs
`tools/validate_grieflogger_release_fixture.py` against the pinned metadata and
can download and hash both official artifacts when the release-fixture check
is enabled. The owning issue is issue #54
([exact-release fixture](https://github.com/DurdeuVlad/itemgraph/issues/54)); it
owns this fixture and any future release refresh.

The fixture records two explicit unresolved differences: the target runtime
metadata is Minecraft 1.21.1/Java 21 while the pinned source metadata is
Minecraft 26.2/Java 25 (owned by #54), and observable native-only behavior still
requires differential replay against that source profile (owned by
[#31](https://github.com/DurdeuVlad/itemgraph/issues/31)). Neither difference
is hidden behind a generic “compatible” label.

The source-profile hash is computed with SHA-256. Its canonical input is the
compact, sorted-key JSON object containing the pinned `ref`, `commit`, sorted
source-file URLs, sorted source action enum names, and sorted source table
names. The current digest is
`960291538000e9246ec2eb7c474441281cdf3b45ed7b3ac6be24e1fea4de3111`.
`tools/validate_grieflogger_profile.py` recomputes this digest and fails CI if
the registry, this document, or the milestone references drift. In CI it also
checks the live M8 issue milestone and acceptance-criteria records through the
read-only GitHub API.

The exact source files used for the audit are pinned at the same commit:

- Actions: [`BlockAction.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/BlockAction.java), [`ItemAction.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/ItemAction.java), and [`SessionAction.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/SessionAction.java).
- Commands, pages, and filters: [`LookupCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/LookupCommand.java), [`InspectCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/InspectCommand.java), [`PageCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/PageCommand.java), [`Page.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/page/Page.java), and [`FilterArgument.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/argument/FilterArgument.java).
- Configuration and database: [`GriefLoggerConfig.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/config/GriefLoggerConfig.java), [`Database.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/Database.java), [`Repository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/Repository.java), [`BlockRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/BlockRepository.java), [`ContainerRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/ContainerRepository.java), [`ItemRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/ItemRepository.java), [`SessionRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/SessionRepository.java), [`ChatRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/ChatRepository.java), [`CommandRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/CommandRepository.java), [`UserRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/UserRepository.java), [`UsernameRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/UsernameRepository.java), [`LevelRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/LevelRepository.java), [`MaterialRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/MaterialRepository.java), and [`EntityRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/EntityRepository.java).
- Inspector behavior: [`InspectBlockEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/InspectBlockEvent.java), [`InspectContainerEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/InspectContainerEvent.java), [`InspectDoorEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/InspectDoorEvent.java), [`RemoveBlockInteractionsEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/RemoveBlockInteractionsEvent.java), and [`RemoveDoorInteractionsEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/RemoveDoorInteractionsEvent.java).

The published pages and source disagree on several observable details: the pages
require a radius while `LookupCommand` accepts an empty/no-radius query; the
source lookup merge excludes chat and command rows even though those tables are
stored; and the source default `maxPageSize` is 10. ItemGraph records the chosen
operator-safe behavior and the source behavior as a versioned fixture instead of
claiming that the two are identical.

| GriefLogger capability | ItemGraph native source | Storage | Query/UI status | Evidence status |
| --- | --- | --- | --- | --- |
| Container add/remove net deltas | `ContainerSessionListener`, capability wrappers | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested; the 2026-09-29 Fabric replay persisted `ADD_ITEM` and `REMOVE_ITEM` rows |
| Item drop/pickup/death drops | NeoForge `ItemEntityEventListener`; Fabric `ServerPlayerMixin`, `ServerLevelMixin`, and `ItemEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | NeoForge paths and Fabric normal, vanilla player-death, and custom death-event item additions are implemented; the Fabric replay persisted accepted `DROP_ITEM` and `PICKUP_ITEM` rows |
| Hopper/mechanical automation (ItemGraph supplemental) | NeoForge capability wrappers; Fabric `HopperBlockEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | GriefLogger's published feature surface has no hopper or mechanical-automation event; ItemGraph records successful vanilla hopper net deltas with unknown endpoints, while modded automation adapters remain an optional extension |
| Crafting and smelting; anvil lineage extension | NeoForge `TransformationEventListener`; Fabric `ResultSlotMixin`, `FurnaceResultSlotMixin`, `AnvilMenuMixin` | `ig_item_transformations` | Item lineage in trace | GriefLogger records crafting and furnace output under `CRAFT_ITEM`; both loaders preserve that source meaning and add ItemGraph `SMELT`, `ANVIL_RENAME`, and `ANVIL_REPAIR` lineage rows at server result-take boundaries; the Fabric replay persisted `CRAFT`, `SMELT`, and `ANVIL_RENAME` rows |
| Player join/quit | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted `PLAYER_JOIN` and `PLAYER_QUIT` rows |
| Chat messages | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted `CHAT_MESSAGE` rows and returned them through `/ig lookup` |
| Player commands | `NativeAuditEventListener`, Fabric `CommandsMixin` | `ig_audit_events` | `/ig lookup` | Both loaders record `COMMAND_ATTEMPT` at the pre-execution dispatch boundary, matching GriefLogger's documented behavior of recording attempts regardless of permission or command success; `COMMAND_EXECUTED` remains reserved for legacy rows and is never fabricated |
| Block place/break | `NativeAuditEventListener`, Fabric break callback, Fabric `BlockItemMixin` | `ig_audit_events` | `/ig lookup` | NeoForge place/break and Fabric place/break capture/query implemented; the Fabric replay persisted `PLACE_BLOCK` and `BREAK_BLOCK` rows |
| Block interaction | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Both loaders record `INTERACT_BLOCK_ATTEMPT`; the Fabric replay persisted interaction attempts and the pre-action callbacks do not claim that the block use completed |
| Player-killed entities | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted a `KILL_ENTITY` row for a player-killed zombie |
| Entity interaction and Ender inventory actions | NeoForge `NativeAuditEventListener`/`ArmorStandEventListener`, Fabric `UseEntityCallback`; shared `EnderChestInteractionTracker` bound by both menu adapters | `ig_audit_events` for `INTERACT_ENTITY`; `ig_observations` for Ender deltas | `/ig lookup INTERACT_ENTITY`, `/ig lookup filters`, and `/ig trace` | Both loaders retain server-side entity interaction attempts, and native audit lookup now exposes `INTERACT_ENTITY` as a selectable event type. Both loaders emit signed `ADD_ITEM_ENDER`/`REMOVE_ITEM_ENDER` session deltas to a durable player-owned `EXTERNAL_INVENTORY` endpoint. Successful-outcome parity for entity interaction remains unresolved in [#27](https://github.com/DurdeuVlad/itemgraph/issues/27). |
| Armor stand equip/unequip | `ArmorStandEventListener` | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested |
| Consume, break, throw, shoot item actions | NeoForge `NativeItemActionEventListener`, `ItemEntityEventListener`; Fabric `LivingEntityMixin`, `ItemStackMixin`, `ServerLevelMixin` | `ig_observations` plus a legacy direct-lookup projection in `ig_audit_events` for projectile rows | `/ig trace`, `/ig gui`, and `/ig lookup` | NeoForge and Fabric record completed eat/drink consumption at the return boundary, durability breaks at the `ItemStack.hurtAndBreak` shrink boundary, and accepted player-owned projectile spawns as `THROW_ITEM`/`SHOOT_ITEM` observations carrying the canonical source stack and observed count to an explicit `UNKNOWN` endpoint. Projectile type and spawn coordinates are retained as raw evidence; unified filtered lookup suppresses the paired projection only when its shared raw event identity has a durable observation match, so a surviving audit row remains visible if observation persistence is lost. GriefLogger's shootFromRotation attempt boundary and ItemGraph's accepted-spawn outcome are not yet fully equivalent, and no projectile UUID or landing location is claimed. |
| Location/action filtered lookup | `AuditLookupFilters`, `UnifiedEvidenceQueryService`, `AuditEventQueryService` | `ig_audit_events`, `ig_observations`, `ig_item_transformations`, `ig_grieflogger_lookup` | `/ig lookup`, `/ig lookup near`, direct `/ig lookup <filter...>`, and `/ig lookup filters` | GriefLogger-style action/user/include/exclude/time/radius filters use one bounded asynchronous merge across native audit, item-flow, transformation, and normalized historical GriefLogger events. The published direct filter spelling now has token-aware suggestions and the ten-row default; the explicit `filters` literal remains an ItemGraph extension. Five-filter cap, required cube radius, AND semantics, global timestamp ordering, source/evidence IDs, and unresolved historical rows are tested; the Fabric replay returned rows from both `/ig lookup CHAT_MESSAGE 10 60` and `/ig lookup filters action.chat_message time.1h radius.50` |
| Block/container inspector history | NeoForge `InspectionListener`; Fabric `FabricNativeAuditEventListener`; shared `BlockInspectionTargets`, `FlowBrowserService`, `TraceQueryService`, and native audit query path | `ig_audit_events`, `ig_observations` | `/ig inspect`, `/ig page`, `/ig trace container` | Block inspection now resolves a valid double chest or door into both physical positions and queries them in one globally ordered page on both loaders. New double-chest sessions use one deterministic container anchor with the partner as an alias; reopening a former partner position after a topology split retires the stale alias before creating a new watch, while overlapping watches close against their recorded keys and share capability credits. Historical container-flow node merging and interaction supersession remain part of [#26](https://github.com/DurdeuVlad/itemgraph/issues/26). |
| Paginated generic audit results | `AuditEventQueryService` offset paging | `ig_audit_events` | `/ig lookup page <page> ...` | Bounded 1-based page offsets and server-generated Previous/Next chat controls implemented |
| Historical GriefLogger schema | `GriefLoggerHistoricalImporter`, `GriefLoggerHistoricalProjection`, and `GriefLoggerAdapter` | `ig_grieflogger_import_runs`, `ig_grieflogger_import_checkpoints`, `ig_grieflogger_rows`, `ig_grieflogger_lookup` | `/ig ingest history` and `/ig lookup provenance` | The supported core schema is validated before import; all 11 pinned source tables are retained as provenance rows with source/schema fingerprints, resumable per-table checkpoints, primary-key/hash/ordinal keys, retained source rowids, binary payload preservation, unresolved reasons, independent writer batches, and durable failed-run counts. Event tables are normalized into bounded unified lookup rows with historical username resolution. Reference and identity rows remain raw, exact source-hash/table/key provenance results and never become quantity evidence. |
| MySQL/MariaDB backend | SQLite only | ItemGraph-owned SQLite | — | Deliberate scope boundary |

## Data boundary

`ig_observations` remains the item quantity-flow ledger. `ig_audit_events` stores
non-quantity evidence so a chat message, command, block action, or session event
cannot be misrepresented as an item transfer. Both tables are owned by ItemGraph.
The GriefLogger database remains read-only during migration and is not removed
as part of native-only cutover; only the runtime jar/config is retired after the
checksummed source copy and rollback evidence are approved.

GriefLogger stores chat and command rows for external review and does not include
them in its in-game lookup merge. ItemGraph's unified lookup intentionally extends
that surface; the extension remains labeled by source and evidence class.

GriefLogger removes interaction rows when a block or door is removed. ItemGraph's
raw evidence is immutable, so parity work must use an explicit supersession or
tombstone record to reproduce the visible active-history result without deleting
the supporting observation.

The hopper/mechanical-automation row is supplemental ItemGraph coverage. It is
not required to replace a GriefLogger capability because GriefLogger does not
record those transfers.

## Native-only cutover and retention plan

The cutover decision is binary: ItemGraph may retire the compatible artifacts
only after every M8 parity gate is closed and the evidence below is recorded.
Until then, the standard and compatible loader jars remain distinct so an
operator can choose a dependency-safe migration path. After cutover, the
standard ItemGraph jar is the only supported runtime artifact; GriefLogger is
not a runtime dependency. The read-only importer remains available for a
checksummed historical database when an operator explicitly configures it.

1. Before cutover, stop the staging server and make an immutable, checksummed
   copy of the GriefLogger database. ItemGraph may read the source during the
   comparison window, but never writes to it.
2. Run ItemGraph native-only with the GriefLogger JAR absent for a complete
   24-hour staging window. Verify the acceptance gates above and record the
   ItemGraph schema version and row-count report.
3. Keep the immutable GriefLogger copy for 30 days after native-only cutover.
   Store its checksum beside the backup and keep the original database read-only.
4. After the 30-day retention window, keep the checksummed GriefLogger copy
   under the operator's archive policy and remove only the GriefLogger JAR/config
   after an operator approves the checksum and acceptance report. Leave
   `grieflogger_database_path` unset for native-only operation.
5. Rollback is bounded: restore the GriefLogger JAR and its immutable database
   copy, leave ItemGraph's database untouched, and re-run the staging checks
   before any production decision. The recorded rollback evidence is the source
   checksum, the restore command and timestamp, the successful read-only schema
   check, and the post-restore staging acceptance report. This plan does not
   authorize production changes.

## Verification notes

- **2026-09-29, Fabric native-only smoke:** the dedicated loopback staging server
  started with no GriefLogger JAR, applied the ItemGraph schema 13 migrations,
  loaded the Fabric mixins, and reached `Done` on port 27992. The ingestion worker
  correctly reported the GriefLogger source as unavailable and continued with
  native listeners. This verifies startup and isolation; player-action replay and
  row-by-row capture checks remain required before marking the event rows fully
  staging-verified.
- **2026-09-29, Fabric GriefLogger-absent row replay:** an isolated checkout on
  `C:\Users\User\itemgraph-staging-replay` ran on loopback `127.0.0.1:27993`
  with no GriefLogger JAR. The server reached `Done`, initialized schema 13,
  logged one informational GriefLogger-ingestion skip, and shut down cleanly.
  SQLite inspection after shutdown found `PLAYER_JOIN`, `PLAYER_QUIT`,
  `CHAT_MESSAGE`, `COMMAND_ATTEMPT`, `INTERACT_BLOCK_ATTEMPT`, `PLACE_BLOCK`,
  `BREAK_BLOCK`, `KILL_ENTITY`, `THROW_ITEM`, and `SHOOT_ITEM` audit rows, plus
  `ADD_ITEM`, `REMOVE_ITEM`, `DROP_ITEM`, `PICKUP_ITEM`, `CONSUME_ITEM`,
  `BREAK_ITEM`, `HOPPER_INSERT`, and `HOPPER_EXTRACT` observation rows. The
  replay also returned rows through both generic and filtered lookup commands.
  A first replay exposed a Fabric `NoSuchMethodError` when consuming an item;
  moving the bounded capture records outside the mixin package removed the
  invalid transformed constructor. The capture now matches HEAD and RETURN by
  a stable caller class/method marker and stack depth; stale or ambiguous
  markers are discarded rather than paired with another invocation. The final
  replay produced no kick or server exception.
- **2026-09-29, Fabric item-flow implementation:** normal player drops and
  pickups now use server-only `ServerPlayer.drop` and `ItemEntity.playerTouch`
  return hooks. Drop rows require the `addFreshEntity` acceptance result; pickup
  rows use the entity stack count delta, so partial pickups cannot manufacture
  quantity. Drops observed while `ServerPlayer.isDeadOrDying()` are labeled
  `DEATH_DROP`; custom item entities accepted during `ServerPlayer.die` are
  captured by a bounded death window and deduplicated against the normal drop
  hook.
- **2026-09-29, Fabric inspector implementation:** the Fabric `UseBlockCallback`
  now matches NeoForge inspection semantics. An enabled permission-level-2 player
  receives the shared read-only flow browser only for a block entity implementing
  `Container`; an accepted query returns `SUCCESS` and suppresses the normal GUI,
  while unsupported blocks, rejected queries, and permission loss preserve ordinary
  interaction. Disconnect and server-stop cleanup are covered by the adapter lifecycle.
- **2026-09-29, NeoForge block inspector implementation:** `/ig inspect` now
  handles only the initial left-click action and non-container right-clicks. The
  canceled event opens an asynchronous, permission-level-2 audit page constrained
  to the exact dimension and block coordinates, so adjacent blocks cannot leak into
  the result. Canceled inspection clicks are excluded from native audit capture;
  rejected block-history queries also keep the gameplay action canceled, and
  repeated left-click hold/abort packets are ignored. A valid double chest or
  door now expands to both physical positions in one ordered exact lookup; the
  container flow-browser node merge remains open in [issue #26](https://github.com/DurdeuVlad/itemgraph/issues/26).
- **2026-09-29, Fabric container sessions:** server menu initialization and close
  hooks now reuse the shared interval tracker for block containers and double chests;
  an orderly server stop flushes active session deltas before ItemGraph closes its
  database. `HopperBlockEntityMixin` snapshots only the hopper and its six adjacent
  container cells, then records successful net deltas with unknown endpoints; it does
  not infer a player or a modded automation cause. This is supplemental coverage;
  GriefLogger does not provide an equivalent hopper event.
- **2026-09-29, Fabric transformations:** result-slot hooks capture crafting,
  furnace-family smelting, and anvil rename/repair outputs with source/result
  canonical fingerprints. The isolated replay persisted one `CRAFT`, one
  `SMELT`, and one `ANVIL_RENAME` row.
- **2026-09-29, Fabric inspector replay:** with `/ig inspect on` enabled for an
  operator-level bot, right-clicking the populated chest opened the shared
  read-only `minecraft:generic_9x6` flow browser. Display rows were present and
  no normal mutable container window was opened; `/ig inspect off` cleared the
  mode before disconnect.
- **2026-09-29, filtered lookup implementation:** `/ig lookup filters` accepts up
  to five `name.value` filters matching GriefLogger's action, user, include,
  exclude, time, and radius vocabulary. Radius is required, clamped to 1..1024,
  centered on the issuing player, and applied as a cube; include/exclude conflicts
  are rejected before the asynchronous SQL query. Native subject IDs are normalized
  so bare Minecraft IDs such as `diamond_ore` match `minecraft:diamond_ore`.
  The filtered lookup now merges all three ItemGraph evidence tables. Quantity-flow
  and transformation actions retain their original action type and source/evidence
  reference; imported rows retain `GRIEFLOGGER` provenance. Every query remains
  bounded by the five-filter, radius, page-size, offset, and query-worker limits.

## Acceptance gates

1. Native capture tests prove one immutable row for each event category and no row
   for canceled command, chat, death, or block actions; persistence failures must
   retain the batch or count its loss without incrementing persisted counters.
2. A staging server with the GriefLogger JAR absent records join, quit, chat,
   command attempt, block, entity-kill, container, consume, break, shoot, and item
   events in ItemGraph storage. Fabric's interaction callback records the observed
   callback attempt. Command rows remain `COMMAND_ATTEMPT` because GriefLogger also
   records command attempts regardless of permission or command success.
3. A permission-level-2 lookup command returns those rows with player, location,
   subject, timestamp, and detail fields, with a bounded result limit. `/ig lookup near`
   applies exact dimension and a radius clamped to 1..1024 blocks. Interactive chat
   `/ig lookup page` controls continue pages with a 10,000-row offset ceiling and
   rerun the same bounded filters as the original query. `/ig lookup filters` accepts
   the five-filter GriefLogger syntax and uses a required cube radius around the player;
   the delivered native lookup/filter contract is tracked in [#25](https://github.com/DurdeuVlad/itemgraph/issues/25). Generic rows retained by the historical ledger are not returned by this query until the normalized projection in [#28](https://github.com/DurdeuVlad/itemgraph/issues/28) is complete.
4. The existing item-flow tests remain green and the GriefLogger database is not
   opened by the native-only path.
5. M8 is not complete while [#43](https://github.com/DurdeuVlad/itemgraph/issues/43),
   [#24](https://github.com/DurdeuVlad/itemgraph/issues/24), [#26](https://github.com/DurdeuVlad/itemgraph/issues/26),
   [#27](https://github.com/DurdeuVlad/itemgraph/issues/27), [#28](https://github.com/DurdeuVlad/itemgraph/issues/28),
   [#29](https://github.com/DurdeuVlad/itemgraph/issues/29), [#30](https://github.com/DurdeuVlad/itemgraph/issues/30),
   or [#31](https://github.com/DurdeuVlad/itemgraph/issues/31) still has an unresolved acceptance criterion.

References: [GriefLogger feature overview](https://daqem.com/projects/grieflogger),
[item usage](https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage),
[player sessions](https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions),
and [chat and commands](https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands).
