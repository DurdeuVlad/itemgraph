# GriefLogger Integration

## Purpose

GriefLogger is an **optional additive evidence source** for ItemGraph as of version 0.2.0.

ItemGraph does not depend on GriefLogger to boot or operate. When GriefLogger is present, ItemGraph ingests its SQLite database in read-only mode to enrich its observations. When GriefLogger is absent, ItemGraph captures the complete item movement graph through its own native NeoForge event listeners and capability wrappers.

## Integration principle

```text
GriefLogger (optional) = external audit evidence (additive)
ItemGraph               = native event coverage, reconstruction, and graph inference
```

GriefLogger must always be treated as read-only. Ingestion skips gracefully with no log spam when the database is absent.

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

## Verified Coverage Matrix

| Event | GriefLogger coverage | Metadata quality | ItemGraph hook status |
|---|---|---:|---|
| Container add | Native (`containers` table, action 1) | Canonical components | Reused from GriefLogger |
| Container remove | Native (`containers` table, action 0) | Canonical components | Reused from GriefLogger |
| Player pickup | Native (`items` table, action 3) | Canonical components | Reused + supplemented with `ItemEntity` UUID |
| Player drop | Native (`items` table, action 2) | Canonical components | Reused + supplemented with `ItemEntity` UUID |
| Crafting | None | N/A | Implemented: `ItemCraftedEvent` |
| Smelting | None | N/A | Implemented: `ItemSmeltedEvent` |
| Armor stand equip | None | N/A | Implemented: `ArmorStandEventListener` |
| Armor stand unequip | None | N/A | Implemented: `ArmorStandEventListener` |
| Anvil rename / repair | None | N/A | Implemented: `AnvilRepairEvent` |
| Player ground bridge | Inferred across events | Canonical fingerprints | Implemented: `CorrelationEngine` (300s window) |

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
