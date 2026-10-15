# GriefLogger source audit

**Audited source:** GriefLogger ref `26.2`, commit
[`d315098b3f37317a5cddfbd75086f4f912f16a83`](https://github.com/DAQEM/GriefLogger/commit/d315098b3f37317a5cddfbd75086f4f912f16a83),
reviewed 2026-09-29.

This is a behavior and storage audit. It is not a binary compatibility claim for
ItemGraph's `1.21.1` artifacts: the pinned source metadata targets Minecraft 26.2,
Java 25, NeoForge 26.2.0.37-beta, Fabric Loader 0.19.3, and Fabric API
0.156.0+26.2. ItemGraph's published compatibility artifact currently targets
GriefLogger `1.2.10-1.21.1`. The exact Fabric and NeoForge release bytes are
pinned in the [checked-in release fixture](grieflogger-fixtures/1.2.10-1.21.1.json),
including official file IDs, SHA-1/SHA-256/SHA-512 digests, manifests, mixins, embedded
JDBC versions, action IDs, table columns, commands, configuration, and inspector
contracts. The source audit below remains behavior research; it does not replace
the release-byte fixture.

## Exact 1.21.1 release evidence

The official release listing is the [CurseForge 1.21.1 file page](https://www.curseforge.com/minecraft/mc-mods/grieflogger/files/all?page=1&pageSize=20&version=1.21.1).
The artifact hashes and metadata were independently checked against the
[Modrinth version records](https://api.modrinth.com/v2/project/8oGVUFuX/version)
and the downloaded official CDN bytes. Fabric is version `zPIDeXeI`, file
`YhNX9z4z`, 18,952,593 bytes; NeoForge is version `IrRNGJIR`, file `XvmPP9qD`,
18,956,227 bytes. Both jars target Minecraft 1.21.1 and Java 21, embed SQLite
JDBC 3.47.2.0 and MySQL Connector/J 8.4.0, and carry required common mixins.
The fixture digest is
`d8181c2af8ba8eccb289bf0e6678be2d3a75ada4e0d51c0d459ba257afaf0853`.
It records the runtime-target mismatch as unresolved under #54 and the
remaining native-only release-contract coverage as unresolved under #31 (the
fixture records audit-time state; both issues are now closed). The
operator requires ItemGraph-only local tests; no GriefLogger runtime or
database is part of that proof.

## User-visible contract

- Commands are `/grieflogger` and `/gl`; `inspect`, `lookup`, and `page` require
  permission level 2 and the corresponding `grieflogger.command.*` permission.
- Lookup filters use `action`, `include`, `exclude`, `radius`, `time`, and `user`
  `name.value` tokens. Official docs describe a five-filter AND query, a player-centered
  cube radius, a default page size of 10, and clickable page navigation.
- `UserFilter.getOptions()` reads `Caches.USER.getAllUsernames()`. In the pinned
  source, `UserCache` refreshes that cache every 300 seconds through
  `UserService.getAllUsernames()`, which selects `id,name` from the `users` table.
  `UserService.insertOrUpdateName()` also writes each observed name to the separate
  `usernames` history table. ItemGraph completion includes both imported reference
  tables so offline current identities and retained past names are discoverable.
- Inspect mode consumes normal block interaction while active. Left-click shows
  block history; right-click shows interaction, container, double-chest, or door history.
- In the pinned `BlockHandler.isBlockIntractable` implementation, right-click
  targets are exactly `FenceGateBlock`, `DispenserBlock`, `NoteBlock`,
  `AbstractChestBlock`, `AbstractFurnaceBlock`, `LeverBlock`, `TrapDoorBlock`,
  `DoorBlock`, `BrewingStandBlock`, `DiodeBlock`, `HopperBlock`, `DropperBlock`,
  `ShulkerBoxBlock`, `BarrelBlock`, `GrindstoneBlock`, `ButtonBlock`,
  `LoomBlock`, `CraftingTableBlock`, `CartographyTableBlock`,
  `EnchantingTableBlock`, `SmithingTableBlock`, `StonecutterBlock`,
  `CrafterBlock`, `VaultBlock`, `DaylightDetectorBlock`, `SignBlock`,
  `LecternBlock`, and `BeaconBlock`. ItemGraph mirrors this vanilla list and
  also recognizes modded block entities implementing Minecraft `Container`.
- The pinned `RightClickBlockEvent.rightClickBlock()` writer logs
  `INTERACT_BLOCK` only for the main hand and only when the targeted block is in
  that list. It calls `LogBlockEvent.logBlock()` from the right-click hook before
  the block/item use result is known; its `ItemStack` parameter is not used by
  the writer. This source therefore proves an observed interaction attempt, not
  that the block accepted the use or that a state change completed. ItemGraph
  keeps `INTERACT_BLOCK_ATTEMPT` as the directly observed row and must not
  upgrade it to a completed interaction without an authoritative result.
  ItemGraph's native action capture now uses the pinned main-hand gate and
  functional-block predicate. Its broader modded `Container` support remains
  available to inspection and inventory-flow features without being counted as
  GriefLogger `INTERACT_BLOCK` parity.
- The exact NeoForge 1.21.1 release artifact from #54,
  `grieflogger-1.2.10-1.21.1-neoforge.jar` (SHA-256
  `fd252bc5466bb94e38d2386bafb9926b798bc250b26e1a3aa80f878ebccbc4a5`), was
  inspected as a ZIP/JVM class file without loading it. Its
  `RightClickBlockEvent.rightClickBlock(Player, InteractionHand, BlockPos,
  Direction)` bytecode gates on `MAIN_HAND`, checks `BlockHandler`, calls
  `LogBlockEvent.logBlock(..., INTERACT_BLOCK)`, then returns `PASS`; no
  interaction result or success check occurs in this writer. Its compiled
  `BlockHandler.isBlockIntractable()` contains the same 28 vanilla block class
  checks as the pinned source and `getIntractableBlocks()` returns an empty
  list. This confirms the attempt-only contract and vanilla target set for the
  exact NeoForge release. It does not prove that modded blocks are supported.
- The same exact NeoForge 1.21.1 jar was inspected for entity interactions.
  `com.daqem.grieflogger.event.EntityEvents.registerEvents()` registers only
  `LIVING_DEATH`; its entity writer records `KILL_ENTITY` for player-caused
  living-entity deaths. The jar has no `MixinArmorStand` class and its mixin
  configuration has no armor-stand interaction hook. Its fixture `block`
  action list contains `BREAK_BLOCK`, `PLACE_BLOCK`, `INTERACT_BLOCK`, and
  `KILL_ENTITY`, with no `INTERACT_ENTITY` action ID. Therefore the exact
  GriefLogger 1.2.10-1.21.1 release does not write entity-interaction evidence.
  This was confirmed by `jar tf`, read-only JSON extraction, and `javap -p -c`
  on the checksum-verified jar (`fd252bc5466bb94e38d2386bafb9926b798bc250b26e1a3aa80f878ebccbc4a5`);
  the jar was not loaded or executed.
- Chat and command rows are stored but excluded from GriefLogger's in-game lookup.
  ItemGraph intentionally exposes them through its own permission-checked audit lookup.

Primary sources: [lookup](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command),
[filters](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/filters),
[pages](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/pages),
[inspect](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/inspect-command),
[`LookupCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/LookupCommand.java),
[`InspectCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/InspectCommand.java).
The user completion path is pinned in [`UserFilter.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/filter/UserFilter.java),
[`UserCache.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/cache/UserCache.java),
[`UserService.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/service/UserService.java),
and [`UserRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/UserRepository.java).
The right-click target list is read from [`BlockHandler.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/block/BlockHandler.java).
The pre-use interaction writer is pinned in [`RightClickBlockEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/RightClickBlockEvent.java).

## Event and storage coverage

The source writes six event tables and five reference/identity tables:

| Table | Meaning |
| --- | --- |
| `blocks` | block/entity actions: `BREAK_BLOCK`, `PLACE_BLOCK`, `INTERACT_BLOCK`, `KILL_ENTITY`, `INTERACT_ENTITY` |
| `containers` | net item additions/removals on supported container sessions |
| `items` | player item actions: add/remove/drop/pickup/craft/break/consume/throw/shoot |
| `sessions` | player join and quit |
| `chats` | public chat text |
| `commands` | command attempts and command text |
| `users`, `usernames` | current identity and name history |
| `levels`, `materials`, `entities` | numeric reference IDs used by event rows |

The complete action enum is pinned in
[`common/src/main/java/com/daqem/grieflogger/model/action`](https://github.com/DAQEM/GriefLogger/tree/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action).
Ender action IDs exist in the enum but the audited source has no writer for them.

### Ender action writers in the exact release (#76)

The [release fixture](grieflogger-fixtures/1.2.10-1.21.1.json) pins the exact
Fabric and NeoForge `1.2.10-1.21.1` jar bytes. A read-only scan of every
checksum-verified classfile found the strings `ADD_ITEM_ENDER` and
`REMOVE_ITEM_ENDER` only in `com/daqem/grieflogger/model/action/ItemAction.class`
for both loaders. The bytecode parser also found no field references to either
enum constant outside the enum declaration. The only `ItemAction.values()`
callers are `Actions.<clinit>`, which enumerates available actions for the
generic name lookup catalog, and `ItemAction.fromId`, the enum's own ID decoder.
The only external `fromId` call is `ItemHistory.<init>`, which reconstructs a
stored history row. No writer class uses the decoder or selects Ender actions
dynamically. This rules out direct or dynamically selected enum
references from writer classes.
The classfile scan is enforced by `tools/validate_grieflogger_release_fixture.py`
whenever CI validates the official release bytes. The [pinned `ItemAction`
source](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/ItemAction.java)
defines IDs 9 and 10 but no other source class references either enum value to
write an Ender event. The published 1.21.1 bytecode is the target-specific
authority; the pinned 26.2 source is behavior research because its declared
runtime target differs. Together, the exact binary scan and source audit
establish `unsupported-no-writer` for the published release.

ItemGraph's separate Ender menu tracking emits interval net deltas as
`ITEMGRAPH_INTERNAL` observations with raw capture
`ender_inventory_session_net_delta`; those rows are not imported GriefLogger
actions. [CoreProtect API v13](https://docs.coreprotect.net/api/version/v13/)
is a related implementation reference: its item lookup includes Ender
transfers and its inventory lookup normalizes them to player inventory
additions/removals. This is a design comparison only; no CoreProtect code was
copied, and its transaction-level contract does not establish GriefLogger
compatibility.

Important semantics and limits:

- Container rows are close-time net deltas, not click history. Generic item handlers,
  backpacks, and arbitrary modded storage are not covered.
- Item rows are aggregated by player tick and item/component equality.
- Crafting and smelting both become `CRAFT_ITEM`.
- `ProjectileMixin` injects at `Projectile.shootFromRotation` HEAD and queues
  `THROW_ITEM` for `ThrowableItemProjectile` or `SHOOT_ITEM` for arrow-family
  projectiles before spawn acceptance is known ([pinned source](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/mixin/ProjectileMixin.java)).
- The pinned `MixinBucketItem` records `BREAK_BLOCK` only after a bucket has
  successfully picked up fluid. Its injected boundary receives the non-empty
  filled-bucket stack and the original `BlockHitResult` position, then reports
  the bucket's fluid as the removed block. ItemGraph implements this as
  non-quantity block evidence on both loaders after `BucketPickup.pickupBlock`
  returns a non-empty stack; it does not turn a fluid block into an item flow.
- Projectile rows carry item data but no projectile entity UUID.
- ItemGraph keeps the full attempt UUID in `raw_data` and stores a stable
  64-bit projection in the existing INTEGER `source_event_id` column, making
  worker retries idempotent through the source/event unique index. Accepted
  spawn evidence is emitted from the `ServerLevel.addFreshEntity` return value,
  so a cancellable join event cannot be reported as accepted.
- The separate pinned GriefLogger 26.2 source adds an armor-stand mixin that
  writes `INTERACT_ENTITY` only when `ArmorStand.interact` returns `SUCCESS` or
  `SUCCESS_SERVER`. This source behavior is not present in the exact 1.21.1
  release artifact above and must not be represented as released-binary parity.
  ItemGraph's mixins target the `ArmorStand.interactAt` override and inherited
  `Entity.interact` fallback. `ArmorStand` overrides `interactAt`, so a hook on
  `Entity.interactAt` would miss the override. NeoForge's 1.21.1 interaction
  pipeline posts `EntityInteractSpecific` before `Entity#interactAt`; if that
  result is nonterminal, it posts `EntityInteract` before fallback
  `Entity#interact`. ItemGraph records one attempt at the specific event and
  records a generic callback only when it is canceled. These callbacks provide
  the attempt boundary; the two method return hooks provide terminal result
  evidence. GriefLogger uses the same method-return pattern in its pinned source
  mixin at [`ArmorStand.interact` RETURN](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/mixin/MixinArmorStand.java).
  Fabric documents that `UseEntityCallback` is hooked before the spectator check
  and that `PASS` falls through to later processing in the [1.21.1 API
  docs](https://maven.fabricmc.net/docs/fabric-api-0.110.0%2B1.21.1/net/fabricmc/fabric/api/event/player/UseEntityCallback.html).
- The project's resolved Fabric API is `0.116.12+1.21.1` (`fabric-events-interaction-v0`
  `0.7.14+ba9dae0619`). Its [`UseEntityCallback` source](https://github.com/FabricMC/fabric-api/blob/0.116.12%2B1.21.1/fabric-events-interaction-v0/src/main/java/net/fabricmc/fabric/api/event/player/UseEntityCallback.java)
  constructs an array-backed event; its [server network handler mixin](https://github.com/FabricMC/fabric-api/blob/0.116.12%2B1.21.1/fabric-events-interaction-v0/src/main/java/net/fabricmc/fabric/mixin/event/interaction/ServerPlayNetworkHandlerMixin.java)
  calls the aggregate invoker and stops vanilla processing for a non-`PASS` result.
  Because listeners short-circuit in registration order, a listener on the same event
  cannot see an earlier listener's result if that listener prevents it from running.
  ItemGraph decorates the aggregate invoker where the event is constructed, snapshots
  actor, target, position, hand, and held-item fingerprint before listeners execute,
  calls the original invoker once, stores its final non-`PASS` result, and returns it unchanged.
  This preserves Fabric's ordering/short-circuit contract and avoids replaying callbacks.
  Fabric API issue [#1870](https://github.com/FabricMC/fabric-api/issues/1870) documents
  the callback duplication risk of handling the same interaction at multiple hooks;
  ItemGraph uses one aggregate boundary for the final callback result. Tests cover
  early and late short-circuits, including a listener mutating the held stack after the
  pre-callback snapshot. `fabric.mod.json` requires the exact Fabric API version resolved
  by this project build because the redirect targets that API initializer. Isolated
  Fabric and NeoForge GameTests now dispatch server interaction packets through each
  loader's handler and verify durable attempt/result rows; they also invoke the
  inherited return hook directly. These mock-player tests do not cover live client
  socket transport or packet-level denied/canceled/repeated interactions.
- The interactable block list is hard-coded.
- Interaction rows can be deleted when a block is broken, so the source is not an
  append-only forensic ledger.
- Event tables have no explicit event ID; importer-generated source keys are required.

## Configuration and execution

The documented server configuration supports SQLite and MySQL/MariaDB, with
`useIndexes`, `maxPageSize`, `queueFrequency` (default 20 ticks, range 1–100),
`helloFrequency` (default 600 ticks, range 1–1000), and JDBC connection
settings. Writes are queued from server ticks to worker threads and serialized under
one database lock. The source does not implement a bounded queue or explicit
backpressure limit. See [`GriefLoggerConfig.java`](https://raw.githubusercontent.com/DAQEM/GriefLogger/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/config/GriefLoggerConfig.java),
[`Database.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/Database.java),
and [official configuration](https://daqem.com/projects/grieflogger/wiki/getting-started/configuration).

The source has no stable public API. Its own API documentation recommends internal
service classes and warns that integrations can break on updates. ItemGraph therefore
uses a read-only storage contract and a versioned behavior profile instead of importing
GriefLogger classes or copying its implementation.

## Parity decisions for ItemGraph

1. Preserve all 11 source tables and opaque component bytes without mutating the source.
2. Normalize only event tables into the bounded unified lookup; keep references and
   identity rows exact and provenance-only.
3. Mark malformed, unknown-action, and undecodable rows `UNRESOLVED` with a reason and
   raw-byte hash.
4. Keep imported rows append-only even when a later source snapshot removes an
   interaction row.
5. Use bounded queues, asynchronous reads, and explicit permissions for sensitive chat,
   command, and identity data.
6. Treat this pinned 26.2 source as a behavior research fixture. Verify the actual
   GriefLogger `1.2.10-1.21.1` release before claiming binary compatibility.

For comparison, CoreProtect API v13 provides a stable typed lookup surface with
separate result types such as `EntityResult` and `BlockResult`, plus shared typed
filters. Its database lookups are synchronous on the caller thread; the official
documentation tells integrations to dispatch searches asynchronously and capture
live world state on the server thread first. ItemGraph follows the same separation
between typed query results and game-thread capture, while enforcing its own bounded
asynchronous query worker rather than relying on every caller to schedule correctly:
[CoreProtect API v13](https://docs.coreprotect.net/api/version/v13/).

## Line-by-line verification delta — 2026-10-15

Every Java file in the pinned tree (129 files under `common`, `fabric`,
`neoforge`, `buildSrc`) was read and cross-checked against this audit, the
feature inventory, and the exact `1.2.10-1.21.1` release fixture. The items
below are newly verified details, not restatements of earlier sections.

### Query and lookup

- Filtered history queries cap each source table at `LIMIT 1000`
  (`BlockRepository.getFilteredBlockHistory`, `ItemRepository.getFilteredItemHistory`,
  `ContainerRepository.getFilteredContainerHistory`,
  `SessionRepository.getFilteredSessionHistory`). A merged lookup can therefore
  return at most about 4,000 rows regardless of how many pages `/gl page` later
  navigates.
- The `radius` filter is optional in code. Each filtered query guards the
  coordinate predicates with `? IS NULL OR`, and `FilterList` substitutes `0`
  bounds only when the filter is absent — the official "a radius is required"
  wording is documentation, not enforcement. `RadiusFilter.parse` accepts any
  integer; the 1–100 range exists only in suggestions.
- Inspect mode can never display `KILL_ENTITY` rows. Left-click history uses
  `action IN (0,1)`; right-click/container history uses `action IN (2,4)`; the
  `KILL_ENTITY` id `3` matches neither inspect query and appears only through
  `/gl lookup`.
- Item rows record the player's position at flush time, not at the event:
  `MixinServerPlayer.grieflogger$tick` drains the per-tick queue with
  `blockPosition()`. A player who drops an item and walks before the next tick
  logs the drop at their later position.
- `blocks.type` is dual-purpose: it joins `materials` for actions `0-2` and
  `entities` for actions `3-4`, discriminated by the action id inside each
  query (`LEFT JOIN ... AND blocks.action = ...`).
- Material and entity names are stored with the `minecraft:` namespace stripped
  (`BlockService`/`ItemService`/`ContainerService` apply
  `.replace("minecraft:","")`); modded identifiers keep `modid:name`.
  ItemGraph's `ItemCanonicalizer.normalizeItemId` maps bare names back to the
  `minecraft` namespace, so imported rows remain compatible — verified.
- Filter quirks confirmed: filter names resolve only as a single character
  (`a.`, `e.`, `i.`, `r.`, `t.`, `u.`) or a lowercase name prefix;
  `Actions.getActions` drops non-lowercase tokens silently; `TimeFilter` parses
  its trailing character against *translated* unit abbreviations (`m/h/d/y`
  under `en_us`), so the accepted suffix alphabet changes with the configured
  language; `RadiusFilter` is an unbounded integer; and `FilterArgument`'s
  `filter1`-`filter5` argument path is dead code behind the greedy `filters`
  argument actually registered.
- Time filtering is a strict `time > ?` comparison against
  `now - n * unit`, not a window.

### Capture and storage

- Breaking an interactable block queues
  `DELETE FROM blocks WHERE level/x/y/z AND action = 2`
  (`RemoveBlockInteractionsEvent`, `RemoveDoorInteractionsEvent`,
  `BlockRepository.removeInteractionsForPosition`). The mutable-ledger contract
  is confirmed in code: interaction rows at a broken position are deleted, not
  superseded.
- `BreakContainerEvent.breakContainer` writes every removed stack as a
  `containers` `REMOVE_ITEM` row attributed to the breaking player's UUID.
  ItemGraph intentionally records the container as source with `UNKNOWN`
  destination and does not attribute the drop to the player — an intentional
  semantic difference, not a coverage gap.
- Container session detection is broader than "vanilla containers":
  `ContainerHandler.getContainers(MenuProvider)` reflectively scans declared
  fields and tracks every `BaseContainerBlockEntity` it finds, so modded menus
  exposing those fields are captured too. The single-container path requires
  the provider itself to be a `BaseContainerBlockEntity`.
- `MixinServerPlayer.openMenu` starts the transaction manager on any menu open;
  `doCloseContainer` finalizes it — delta is computed at close, exactly the
  zero-net-invisible session model already documented.
- The per-player item queue merges stacks by `SimpleItemStack.equals`, which
  compares item and `DataComponentPatch` only — count is intentionally
  excluded; totals accumulate per tick per action.
- `queue.hello()` is a `SELECT 1` keepalive queued every `helloFrequency`
  server ticks (default 600, i.e. every 30 s), not telemetry. Write queues are
  unbounded `ConcurrentLinkedQueue`s; workers are a fixed pool of
  `availableProcessors` threads; `ThreadManager.shutdown` awaits at most five
  seconds then calls `shutdownNow`.
- GriefLogger ships a neutered `org.sqlite.util.ProcessRunner` whose
  `runAndWaitFor` methods return `""`, preventing the embedded sqlite-jdbc
  native loader from spawning detection processes.
- Chat capture uses `component.getString()` (plain text), command capture
  stores the raw reader string at the command event — attempts, not proven
  effects. Both tables stay outside the lookup merge.
- `users` rows upsert `name` on `uuid` conflict and `usernames` keeps an
  insert-only `(uuid,name)` history; `UserRepository.insertNonPlayer` permits
  name-only actor rows (no caller in this tree).
- The 26.2 SQLite path is `<config>/grieflogger/database.db`
  (`Platform.INFO.getConfigFolder().resolve(MOD_ID)`); config is YAML
  (`grieflogger-server.yaml` via `yamlconfig`, depending on `knot` +
  `yamlconfig` mods). The exact `1.2.10-1.21.1` release instead uses TOML
  `config/grieflogger/grieflogger-server.toml` with `serverSideOnlyMode=true`
  — a config-system change between the release and this source, not a new
  feature.

### Non-player and entity actors (26.2 source only — absent from the release)

- `EntityUtils.logBlockAction` writes block actions caused by non-player
  entities under synthetic name-based UUIDs: `entity:<entityTypeId>` for pure
  mobs, `link:<playerUUID>:<entityTypeId>` when a player is responsible. The
  `users` row then carries names such as `Steve (Creeper)`. These UUIDs are
  deterministic `UUID.nameUUIDFromBytes` values, not real entity UUIDs.
- `resolveLogicalActor` attributes an action to: the player, else the
  `OwnableEntity` owner, else the `Projectile` owner, else a `Mob`'s current
  attack target. A creeper's explosion therefore blames the player it was
  targeting — the victim, not a cause.
- `MixinServerExplosion.interactWithBlocks` logs `BREAK_BLOCK` for every
  non-air target block with actor = `getIndirectSourceEntity()` falling back to
  `source`; `MixinEnderManTakeBlockGoal`/`MixinEnderManLeaveBlockGoal` log
  `BREAK_BLOCK`/`PLACE_BLOCK` for Enderman; `MixinArmorStand.interact` logs
  `INTERACT_ENTITY` only on `SUCCESS`/`SUCCESS_SERVER` into the `blocks` table.
- The fixture `action_writer_audit` proves none of these writers exist in the
  exact `1.2.10-1.21.1` jars: `BREAK_BLOCK`/`PLACE_BLOCK` are written only by
  `BreakBlockEvent`/`PlaceBlockEvent`, and `INTERACT_ENTITY` has neither an
  action id nor a writer. They remain 26.2-source behavior and must not be
  claimed as release-target coverage.

### Presentation

- `LanguageManager` performs network egress at server start: it downloads
  Mojang's `version_manifest_v2.json`, resolves the asset index, fetches the
  selected Minecraft language file into `config/grieflogger/lang_cache/`, and
  verifies its SHA-1. It additionally loads every installed mod's
  `/assets/<modid>/lang/<locale>.json` so item names can be translated
  server-side. `language` accepts `en_us`, `nl_nl`, `zh_tw` (`zh_cn` ships but
  is not selectable).
- History rows render `<unit> ago`, a colored `+`/`-` action prefix, the user
  name, the past-tense verb, and the material; positions render as clickable
  text that runs vanilla `/tp x y z`, and page arrows run
  `/grieflogger page <n>`.
- Inspect right-click on a non-interactable position reports the history of
  the *adjacent* face block (`pos.relative(direction)` after a 5-block
  raycast), not the clicked block — the lookup answers "what happened where
  this placement would land".

No additional release-target feature gap was found beyond the issues already
tracked for M9/M10.
