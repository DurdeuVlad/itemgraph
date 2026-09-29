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

| GriefLogger capability | ItemGraph native source | Storage | Query/UI status | Evidence status |
| --- | --- | --- | --- | --- |
| Container add/remove net deltas | `ContainerSessionListener`, capability wrappers | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested; the 2026-09-29 Fabric replay persisted `ADD_ITEM` and `REMOVE_ITEM` rows |
| Item drop/pickup/death drops | NeoForge `ItemEntityEventListener`; Fabric `ServerPlayerMixin`, `ServerLevelMixin`, and `ItemEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | NeoForge paths and Fabric normal, vanilla player-death, and custom death-event item additions are implemented; the Fabric replay persisted accepted `DROP_ITEM` and `PICKUP_ITEM` rows |
| Hopper/mechanical automation (ItemGraph supplemental) | NeoForge capability wrappers; Fabric `HopperBlockEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | GriefLogger's published feature surface has no hopper or mechanical-automation event; ItemGraph records successful vanilla hopper net deltas with unknown endpoints, while modded automation adapters remain an optional extension |
| Crafting, smelting, anvil rename/repair | NeoForge `TransformationEventListener`; Fabric `ResultSlotMixin`, `FurnaceResultSlotMixin`, `AnvilMenuMixin` | `ig_item_transformations` | Item lineage in trace | Both loaders capture crafting, furnace-family smelting, and anvil rename/repair results at server result-take boundaries; the Fabric replay persisted `CRAFT`, `SMELT`, and `ANVIL_RENAME` rows |
| Player join/quit | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted `PLAYER_JOIN` and `PLAYER_QUIT` rows |
| Chat messages | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted `CHAT_MESSAGE` rows and returned them through `/ig lookup` |
| Player commands | `NativeAuditEventListener`, Fabric `CommandsMixin` | `ig_audit_events` | `/ig lookup` | Both loaders record `COMMAND_ATTEMPT` at the pre-execution dispatch boundary, matching GriefLogger's documented behavior of recording attempts regardless of permission or command success; `COMMAND_EXECUTED` remains reserved for legacy rows and is never fabricated |
| Block place/break | `NativeAuditEventListener`, Fabric break callback, Fabric `BlockItemMixin` | `ig_audit_events` | `/ig lookup` | NeoForge place/break and Fabric place/break capture/query implemented; the Fabric replay persisted `PLACE_BLOCK` and `BREAK_BLOCK` rows |
| Block interaction | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Both loaders record `INTERACT_BLOCK_ATTEMPT`; the Fabric replay persisted interaction attempts and the pre-action callbacks do not claim that the block use completed |
| Player-killed entities | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted a `KILL_ENTITY` row for a player-killed zombie |
| Armor stand equip/unequip | `ArmorStandEventListener` | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested |
| Consume, break, throw, shoot item actions | NeoForge `NativeItemActionEventListener`, `ItemEntityEventListener`; Fabric `LivingEntityMixin`, `ItemStackMixin`, `ServerLevelMixin` | `ig_observations` for consume/break; `ig_audit_events` for native projectile spawn evidence | `/ig trace`, `/ig gui`, and `/ig lookup` | NeoForge and Fabric record completed eat/drink consumption at the return boundary, Fabric records durability breaks at the `ItemStack.hurtAndBreak` shrink boundary, and both loaders record fresh player-owned projectile spawns as non-quantity `THROW_ITEM`/`SHOOT_ITEM` audit evidence; the Fabric replay persisted all four action types |
| Location/action filtered lookup | `AuditLookupFilters`, `UnifiedEvidenceQueryService`, `AuditEventQueryService` | `ig_audit_events`, `ig_observations`, `ig_item_transformations` | `/ig lookup`, `/ig lookup near`, `/ig lookup filters` | GriefLogger-style action/user/include/exclude/time/radius filters use one bounded asynchronous merge across native audit, item-flow, transformation, and imported `GRIEFLOGGER` observations. Five-filter cap, required cube radius, AND semantics, global timestamp ordering, and source/evidence IDs are tested; the Fabric replay returned rows from both `/ig lookup CHAT_MESSAGE 10 60` and `/ig lookup filters action.chat_message time.1h radius.50` |
| Block/container inspector history | NeoForge `InspectionListener`; Fabric `FabricNativeAuditEventListener`; shared `FlowBrowserService`, `TraceQueryService` | `ig_observations` | `/ig inspect`, `/ig trace container` | Read-only coordinate history and paginated flow browser implemented on both loaders; the Fabric replay opened a read-only `minecraft:generic_9x6` flow browser for a populated chest |
| Paginated generic audit results | `AuditEventQueryService` offset paging | `ig_audit_events` | `/ig lookup page <page> ...` | Bounded 1-based page offsets and server-generated Previous/Next chat controls implemented |
| MySQL/MariaDB backend | SQLite only | ItemGraph-owned SQLite | — | Deliberate scope boundary |

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
   the five-filter GriefLogger syntax and uses a required cube radius around the player.
4. The existing item-flow tests remain green and the GriefLogger database is not
   opened by the native-only path.

References: [GriefLogger feature overview](https://daqem.com/projects/grieflogger),
[item usage](https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage),
[player sessions](https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions),
and [chat and commands](https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands).
