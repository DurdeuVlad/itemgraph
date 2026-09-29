# GriefLogger replacement parity

This matrix is the acceptance boundary for replacing GriefLogger as the native
server audit source. It is based on GriefLogger's published feature surface:
block actions, item usage, player sessions, chat, commands, inspector, filtered
lookup, pagination, and SQLite/MySQL storage.

## Current audit (2026-09-29)

The native ledger is usable, but the project has not reached 100% drop-in parity.
ItemGraph keeps `/ig` and `/itemgraph` as its only command names; compatibility is
semantic and does not add `/gl` or `/grieflogger` aliases. The open implementation
boundary is recorded in GitHub milestone [M8](https://github.com/DurdeuVlad/itemgraph/milestone/4)
and issues [#23](https://github.com/DurdeuVlad/itemgraph/issues/23) through
[#31](https://github.com/DurdeuVlad/itemgraph/issues/31).

Known gaps are explicit:

- filtered lookup currently centers on `ig_audit_events`; item-flow and transformation
  rows are still trace data rather than one compatibility query surface ([#25](https://github.com/DurdeuVlad/itemgraph/issues/25));
- inspector coverage is currently container-oriented and does not yet match the
  documented left-click, functional-block, double-chest, and door cases ([#26](https://github.com/DurdeuVlad/itemgraph/issues/26));
- native action names and attempted/completed semantics need a versioned mapping,
  including liquid placement and source pickup ([#23](https://github.com/DurdeuVlad/itemgraph/issues/23), [#27](https://github.com/DurdeuVlad/itemgraph/issues/27));
- the read-only adapter does not yet import every documented historical table,
  including chats and commands ([#28](https://github.com/DurdeuVlad/itemgraph/issues/28));
- native storage is SQLite-only until the MySQL/MariaDB contract is implemented
  and tested ([#29](https://github.com/DurdeuVlad/itemgraph/issues/29));
- lookup paging exists for bounded audit queries, but standalone session page
  compatibility and configuration/retention controls remain ([#24](https://github.com/DurdeuVlad/itemgraph/issues/24), [#30](https://github.com/DurdeuVlad/itemgraph/issues/30)).

The extension roadmap is GitHub milestone [M9](https://github.com/DurdeuVlad/itemgraph/milestone/5),
which covers performance budgets, extra causes/events, cross-loader integrations,
and tamper-evident exports. Research informing the design includes
[CoreProtect's command/API model](https://docs.coreprotect.net/commands/),
[Ledger's server-side event scope](https://modrinth.com/mod/ledger), and
[Fabric's event guidance](https://github.com/FabricMC/fabric-docs/blob/main/versions/1.21.1/develop/events.md).

| GriefLogger capability | ItemGraph native source | Storage | Query/UI status | Evidence status |
| --- | --- | --- | --- | --- |
| Container add/remove net deltas | `ContainerSessionListener`, capability wrappers | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested; compatibility lookup inclusion is tracked by [#25](https://github.com/DurdeuVlad/itemgraph/issues/25) |
| Item drop/pickup/death drops | NeoForge `ItemEntityEventListener`; Fabric `ServerPlayerMixin`, `ServerLevelMixin`, and `ItemEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | NeoForge paths and Fabric normal, vanilla player-death, and custom death-event item additions are implemented; the Fabric replay persisted accepted `DROP_ITEM` and `PICKUP_ITEM` rows |
| Hopper/mechanical automation (ItemGraph supplemental) | NeoForge capability wrappers; Fabric `HopperBlockEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | GriefLogger's published feature surface has no hopper or mechanical-automation event; ItemGraph records successful vanilla hopper net deltas with unknown endpoints, while modded automation adapters remain an optional extension |
| Crafting, smelting, anvil rename/repair | NeoForge `TransformationEventListener`; Fabric `ResultSlotMixin`, `FurnaceResultSlotMixin`, `AnvilMenuMixin` | `ig_item_transformations` | Item lineage in trace | Both loaders capture crafting, furnace-family smelting, and anvil rename/repair results at server result-take boundaries; the Fabric replay persisted `CRAFT`, `SMELT`, and `ANVIL_RENAME` rows |
| Player join/quit | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Native capture/query implemented; canonical GriefLogger naming and contract remain in [#23](https://github.com/DurdeuVlad/itemgraph/issues/23) and [#27](https://github.com/DurdeuVlad/itemgraph/issues/27) |
| Chat messages | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Native capture/query implemented; historical `chats` import and canonical mapping remain in [#28](https://github.com/DurdeuVlad/itemgraph/issues/28) and [#23](https://github.com/DurdeuVlad/itemgraph/issues/23) |
| Player commands | `NativeAuditEventListener`, Fabric `CommandsMixin` | `ig_audit_events` | `/ig lookup` | Both loaders record `COMMAND_ATTEMPT` at the pre-execution dispatch boundary; historical `commands` import and canonical mapping remain in [#28](https://github.com/DurdeuVlad/itemgraph/issues/28) and [#23](https://github.com/DurdeuVlad/itemgraph/issues/23) |
| Block place/break | `NativeAuditEventListener`, Fabric break callback, Fabric `BlockItemMixin` | `ig_audit_events` | `/ig lookup` | Ordinary place/break is implemented; liquid source pickup/placement and completion mapping remain in [#27](https://github.com/DurdeuVlad/itemgraph/issues/27) |
| Block interaction | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Both loaders record `INTERACT_BLOCK_ATTEMPT`; completion/attempt compatibility is intentionally unresolved until [#27](https://github.com/DurdeuVlad/itemgraph/issues/27) |
| Player-killed entities | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted a `KILL_ENTITY` row for a player-killed zombie |
| Armor stand equip/unequip | `ArmorStandEventListener` | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested |
| Consume, break, throw, shoot item actions | NeoForge `NativeItemActionEventListener`, `ItemEntityEventListener`; Fabric `LivingEntityMixin`, `ItemStackMixin`, `ServerLevelMixin` | `ig_observations` for consume/break; `ig_audit_events` for native projectile spawn evidence | `/ig trace`, `/ig gui`, and `/ig lookup` | NeoForge and Fabric record completed eat/drink consumption at the return boundary, Fabric records durability breaks at the `ItemStack.hurtAndBreak` shrink boundary, and both loaders record fresh player-owned projectile spawns as non-quantity `THROW_ITEM`/`SHOOT_ITEM` audit evidence; the Fabric replay persisted all four action types |
| Location/action filtered lookup | `AuditLookupFilters`, `AuditEventQueryService` | `ig_audit_events` | `/ig lookup`, `/ig lookup near`, `/ig lookup filters` | GriefLogger-style filters are implemented for native audit actions with five-filter cap and bounded cube radius; quantity-flow, transformation, and imported evidence still need one query surface in [#25](https://github.com/DurdeuVlad/itemgraph/issues/25) |
| Block/container inspector history | NeoForge `InspectionListener`; Fabric `FabricNativeAuditEventListener`; shared `FlowBrowserService`, `TraceQueryService` | `ig_observations` | `/ig inspect`, `/ig trace container` | Read-only container history is implemented; left-click/general functional blocks, double chests, and doors remain in [#26](https://github.com/DurdeuVlad/itemgraph/issues/26) |
| Paginated generic audit results | `AuditEventQueryService` offset paging | `ig_audit_events` | `/ig lookup page <page> ...` | Bounded 1-based audit paging exists; standalone session page semantics and command parity remain in [#24](https://github.com/DurdeuVlad/itemgraph/issues/24) |
| MySQL/MariaDB backend | Not implemented | ItemGraph-owned SQLite | — | Deliberate current gap; one storage contract and CI services are tracked by [#29](https://github.com/DurdeuVlad/itemgraph/issues/29) |
| Historical GriefLogger tables | Read-only `GriefLoggerAdapter` | Imported ItemGraph evidence (planned) | Compatibility lookup (planned) | Current adapter is limited to `items` and `containers`; users/levels/materials/chats/commands and resumable provenance are tracked by [#28](https://github.com/DurdeuVlad/itemgraph/issues/28) |

## Data boundary

`ig_observations` remains the item quantity-flow ledger. `ig_audit_events` stores
non-quantity evidence so a chat message, command, block action, or session event
cannot be misrepresented as an item transfer. Both tables are owned by ItemGraph;
the GriefLogger database remains read-only during migration and can be removed
after native coverage and staging verification meet this matrix.

The hopper/mechanical-automation row is supplemental ItemGraph coverage. It is
not required to replace a GriefLogger capability because GriefLogger does not
record those transfers.

## Native-only cutover and retention plan

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
   before any production decision. This plan does not authorize production
   changes.

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
  Quantity-flow actions such as `drop_item` and `pickup_item` are rejected on this
  native-audit command rather than being misclassified as projectile events; those
  records remain available through `/ig trace`.

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
   the five-filter GriefLogger syntax and uses a required cube radius around the player.
4. The existing item-flow tests remain green and the GriefLogger database is not
   opened by the native-only path.

References: [GriefLogger feature overview](https://daqem.com/projects/grieflogger),
[item usage](https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage),
[player sessions](https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions),
and [chat and commands](https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands).
