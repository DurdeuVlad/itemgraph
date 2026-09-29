# GriefLogger source audit

**Audited source:** GriefLogger ref `26.2`, commit
[`d315098b3f37317a5cddfbd75086f4f912f16a83`](https://github.com/DAQEM/GriefLogger/commit/d315098b3f37317a5cddfbd75086f4f912f16a83),
reviewed 2026-09-29.

This is a behavior and storage audit. It is not a binary compatibility claim for
ItemGraph's `1.21.1` artifacts: the pinned source metadata targets Minecraft 26.2,
Java 25, NeoForge 26.2.0.37-beta, Fabric Loader 0.19.3, and Fabric API
0.156.0+26.2. ItemGraph's published compatibility artifact currently targets
GriefLogger `1.2.10-1.21.1`; that release pair needs its own source fixture before
the dependency can be called bytecode-compatible.

## User-visible contract

- Commands are `/grieflogger` and `/gl`; `inspect`, `lookup`, and `page` require
  permission level 2 and the corresponding `grieflogger.command.*` permission.
- Lookup filters use `action`, `include`, `exclude`, `radius`, `time`, and `user`
  `name.value` tokens. Official docs describe a five-filter AND query, a player-centered
  cube radius, a default page size of 10, and clickable page navigation.
- Inspect mode consumes normal block interaction while active. Left-click shows
  block history; right-click shows interaction, container, double-chest, or door history.
- Chat and command rows are stored but excluded from GriefLogger's in-game lookup.
  ItemGraph intentionally exposes them through its own permission-checked audit lookup.

Primary sources: [lookup](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command),
[filters](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/filters),
[pages](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/pages),
[inspect](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/inspect-command),
[`LookupCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/LookupCommand.java),
[`InspectCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/InspectCommand.java).

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
- Projectile rows carry item data but no projectile entity UUID.
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
