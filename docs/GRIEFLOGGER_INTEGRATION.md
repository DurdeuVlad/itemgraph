# GriefLogger Integration

## Purpose

GriefLogger is expected to provide a significant portion of ItemGraph's raw evidence.

ItemGraph should complement it rather than replace it.

## Integration principle

```text
GriefLogger = existing audit evidence
ItemGraph   = reconstruction and missing-event coverage
```

GriefLogger must be treated as read-only.

## Reconnaissance checklist

Before coding the integration, inspect the actual staging installation and record:

- GriefLogger mod version
- Minecraft version
- NeoForge version
- database engine
- database file/path
- relevant tables
- primary keys
- timestamp representation
- player/user tables
- material/item tables
- container event tables
- pickup/drop tables
- NBT/component representation
- indexes
- schema versioning behavior

Do not rely solely on online documentation if the installed version differs.

## Coverage matrix template

During staging discovery, fill this table with verified results.

| Event | GriefLogger coverage | Metadata quality | ItemGraph hook needed? |
|---|---|---:|---|
| Container add | TBD | TBD | TBD |
| Container remove | TBD | TBD | TBD |
| Player pickup | TBD | TBD | TBD |
| Player drop | TBD | TBD | TBD |
| Crafting | TBD | TBD | TBD |
| Consumption | TBD | TBD | TBD |
| Armor stand equip | TBD | TBD | TBD |
| Armor stand unequip | TBD | TBD | TBD |
| Player inventory-only transfer | TBD | TBD | TBD |
| Ender chest | TBD | TBD | TBD |
| Hopper transfer | TBD | TBD | TBD |
| Modded coffer | TBD | TBD | TBD |
| Backpack inventory | TBD | TBD | TBD |
| Faction storage | TBD | TBD | TBD |
| Anvil rename | TBD | TBD | TBD |
| Smithing | TBD | TBD | TBD |
| Item break | TBD | TBD | TBD |

## Safe database access

The GriefLogger connection must be read-only wherever the database driver supports it.

Forbidden operations:

- `INSERT`
- `UPDATE`
- `DELETE`
- `ALTER`
- `DROP`
- schema migration
- repair
- vacuum/compaction that writes to DB
- index creation
- trigger creation

ItemGraph should maintain its own import checkpoint.

Example concept:

```text
source = grief_logger
last_seen_event_id = 918221
last_seen_timestamp = ...
```

## Incremental ingestion

Avoid full rescans.

Preferred behavior:

1. Read events after the last durable checkpoint.
2. Normalize them into ItemGraph observations.
3. Commit ItemGraph observations.
4. Advance the checkpoint only after successful persistence.

This should be idempotent.

## Source identity

Imported observations should preserve:

- source system
- source table/event type
- source row ID
- original timestamp
- source player identity
- original coordinates
- original item data

A unique constraint over source + source-event ID can prevent duplicate ingestion.

## Schema drift

The integration layer must fail safely if GriefLogger changes its schema.

Do not silently reinterpret incompatible columns.

Preferred behavior:

- identify supported schema/version
- log clear incompatibility
- disable affected ingestion
- keep ItemGraph server functional where possible
- never attempt automatic mutation of GriefLogger

## Direct hooks vs GriefLogger reuse

Only add ItemGraph hooks when they provide evidence GriefLogger does not already capture with sufficient fidelity.

Examples of potentially valuable missing hooks:

- armor stand equipment changes
- unusual modded inventory transitions
- player-inventory-only events
- custom coffer behavior
- item entity UUID correlation
- inventory transitions hidden from ordinary container logs

The actual list must be based on staging evidence.

## Failure modes

ItemGraph should survive:

- GriefLogger database temporarily locked
- database unavailable during startup
- malformed historical row
- unsupported source schema
- duplicate row ingestion
- partial import
- ItemGraph restart mid-import

The source must remain untouched in every case.
