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
`160f77435c9527304adba691388295ead00af40db482338fcbf87951d929e648`.
It records the runtime-target mismatch as unresolved under #54 and the
remaining native-only differential-replay proof as unresolved under #31.

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

Important semantics and limits:

- Container rows are close-time net deltas, not click history. Generic item handlers,
  backpacks, and arbitrary modded storage are not covered.
- Item rows are aggregated by player tick and item/component equality.
- Crafting and smelting both become `CRAFT_ITEM`.
- `ProjectileMixin` injects at `Projectile.shootFromRotation` HEAD and queues
  `THROW_ITEM` for `ThrowableItemProjectile` or `SHOOT_ITEM` for arrow-family
  projectiles before spawn acceptance is known ([pinned source](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/mixin/ProjectileMixin.java)).
- Projectile rows carry item data but no projectile entity UUID.
- ItemGraph keeps the full attempt UUID in `raw_data` and stores a stable
  64-bit projection in the existing INTEGER `source_event_id` column, making
  worker retries idempotent through the source/event unique index. Accepted
  spawn evidence is emitted from the `ServerLevel.addFreshEntity` return value,
  so a cancellable join event cannot be reported as accepted.
- Entity interaction is implemented for armor stands only.
- The interactable block list is hard-coded.
- Interaction rows can be deleted when a block is broken, so the source is not an
  append-only forensic ledger.
- Event tables have no explicit event ID; importer-generated source keys are required.

## Configuration and execution

The documented server configuration supports SQLite and MySQL/MariaDB, with
`useIndexes`, `maxPageSize`, `queueFrequency`, `helloFrequency`, and JDBC connection
settings. Writes are queued from server ticks to worker threads and serialized under
one database lock. The source does not implement a bounded queue or explicit
backpressure limit. See [`GriefLoggerConfig.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/config/GriefLoggerConfig.java),
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

For comparison, CoreProtect's typed asynchronous lookup/API model is a better design
reference for stable integration boundaries than GriefLogger's internal services:
[commands](https://docs.coreprotect.net/commands/), [API](https://docs.coreprotect.net/api/version/v13/).
