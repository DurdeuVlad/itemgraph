# GriefLogger replacement parity

This matrix is the acceptance boundary for replacing GriefLogger as the native
server audit source. It is based on GriefLogger's published feature surface:
block actions, item usage, player sessions, chat, commands, inspector, filtered
lookup, pagination, and SQLite/MySQL storage.

| GriefLogger capability | ItemGraph native source | Storage | Query/UI status | Evidence status |
| --- | --- | --- | --- | --- |
| Container add/remove net deltas | `ContainerSessionListener`, capability wrappers | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested |
| Item drop/pickup/death drops | `ItemEntityEventListener` | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested |
| Crafting, smelting, anvil rename/repair | `TransformationEventListener` | `ig_item_transformations` | Item lineage in trace | Implemented and tested |
| Player join/quit | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; staging verification pending |
| Chat messages | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; staging verification pending |
| Player commands | `NativeAuditEventListener`; Fabric execution hook pending | `ig_audit_events` | `/ig lookup` | NeoForge capture/query implemented; Fabric API exposes command broadcast messages, not a general execution-complete callback |
| Block place/break | `NativeAuditEventListener`, Fabric break callback | `ig_audit_events` | `/ig lookup` | NeoForge place/break and Fabric break capture/query implemented; Fabric completed placement pending |
| Block interaction | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | NeoForge records completed interaction; Fabric records `INTERACT_BLOCK_ATTEMPT` because its callback has no completion hook |
| Player-killed entities | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; staging verification pending |
| Armor stand equip/unequip | `ArmorStandEventListener` | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested |
| Consume, break, throw, shoot item actions | `NativeItemActionEventListener`, `ItemEntityEventListener` | `ig_observations` | `/ig trace` and `/ig gui` | Consume, break, and bow shoot captured natively; throw-item and crossbow projectile hooks still pending |
| Location/action filtered lookup | `AuditEventQueryService` | `ig_audit_events` | `/ig lookup`, `/ig lookup near` | Action/player/time and exact dimension/radius filters implemented; staging verification pending |
| Block/container inspector history | `InspectionListener`, `FlowBrowserService`, `TraceQueryService` | `ig_observations` | `/ig inspect`, `/ig trace container` | Read-only coordinate history and paginated flow browser implemented; staging verification pending |
| Paginated generic audit results | `AuditEventQueryService` offset paging | `ig_audit_events` | `/ig lookup page <page> ...` | Bounded 1-based page offsets implemented; interactive chat controls remain pending |
| MySQL/MariaDB backend | SQLite only | ItemGraph-owned SQLite | — | Deliberate scope boundary |

## Data boundary

`ig_observations` remains the item quantity-flow ledger. `ig_audit_events` stores
non-quantity evidence so a chat message, command, block action, or session event
cannot be misrepresented as an item transfer. Both tables are owned by ItemGraph;
the GriefLogger database remains read-only during migration and can be removed
after native coverage and staging verification meet this matrix.

## Acceptance gates

1. Native capture tests prove one immutable row for each event category and no row
   for canceled command, chat, death, or block actions; persistence failures must
   retain the batch or count its loss without incrementing persisted counters.
2. A staging server with the GriefLogger JAR absent records join, quit, chat,
   command, block, entity-kill, container, consume, break, shoot, and item events
   in ItemGraph storage. Fabric's interaction callback records the observed callback
   attempt; Fabric command execution requires a dedicated loader hook before it can
   be marked complete.
3. A permission-level-2 lookup command returns those rows with player, location,
   subject, timestamp, and detail fields, with a bounded result limit. `/ig lookup near`
   applies exact dimension and a radius clamped to 1..1024 blocks. Interactive chat
   `/ig lookup page` continues pages with a 10,000-row offset ceiling; interactive
   chat controls remain a later UX slice.
4. The existing item-flow tests remain green and the GriefLogger database is not
   opened by the native-only path.

References: [GriefLogger feature overview](https://daqem.com/projects/grieflogger),
[item usage](https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage),
[player sessions](https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions),
and [chat and commands](https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands).
