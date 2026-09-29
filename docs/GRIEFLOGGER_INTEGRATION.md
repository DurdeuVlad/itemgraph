# GriefLogger Integration

## Purpose

GriefLogger has been an **optional additive evidence source** for ItemGraph since version 0.2.0.

## Supported modes

| GriefLogger installation | ItemGraph behavior |
|---|---|
| Absent | ItemGraph boots with its own database and records its supported native NeoForge observations and registered vanilla `IItemHandler` changes. Coverage remains limited: container observations are open/close session net deltas, capability callers/causes are UNKNOWN, private ender chests are not natively watched, and non-vanilla inventories need dedicated adapters. |
| Present | The same ItemGraph-native capture remains enabled. ItemGraph additionally ingests GriefLogger's SQLite evidence through read-only connections. Raw rows from both sources are preserved; confirmed copies share one capacity group and uncertain matches remain ambiguous. |

These are additive modes, not an either/or switch. GriefLogger is not required for ItemGraph to boot or run.

## Artifact selection and SQLite module compatibility

The standard `itemgraph-<version>.jar` bundles `org.xerial:sqlite-jdbc` with
NeoForge Jar-in-Jar and keeps GriefLogger optional. GriefLogger
`1.2.10-1.21.1` packages `org.sqlite.*` in its main mod module; when both standard
artifacts load, Java module resolution sees two modules exporting `org.sqlite.util`
and aborts server startup. Jar-in-Jar version negotiation cannot remove classes
embedded in GriefLogger's main JAR.

For servers running GriefLogger `1.2.10-1.21.1`, use
`itemgraph-<version>-grieflogger-compatible.jar`. It omits ItemGraph's SQLite
Jar-in-Jar dependency, uses GriefLogger's SQLite classes, and declares that exact
GriefLogger version as required in `META-INF/neoforge.mods.toml`. Do not install
both ItemGraph artifacts together. CI builds and structurally verifies both; the
release workflow attaches both to GitHub Releases and publishes each CurseForge
file with matching dependency metadata.

The compatibility artifact's tested scope is GriefLogger `1.2.10-1.21.1` on
Minecraft 1.21.1 / NeoForge 21.1.248. Other GriefLogger versions need a separate
compatibility check before widening this artifact's declared version range.

## Integration principle

```text
GriefLogger (optional) = external audit evidence (additive)
ItemGraph               = native event coverage, reconstruction, and graph inference
```

GriefLogger must always be treated as read-only. Ingestion skips gracefully with no log spam when the database is absent.

### Historical component blobs

GriefLogger stores `DataComponentPatch.STREAM_CODEC` bytes. A blob written with a
different Minecraft/mod component registry can fail to decode after the server's
modpack changes. ItemGraph preserves the original blob and continues ingestion with
an opaque SHA-256 fingerprint for that row, so distinct undecodable payloads do not
collapse into one item-ID fingerprint. Component-level distinctions remain
unresolved until the blob can be decoded with a compatible registry. These expected
historical compatibility failures are recorded at DEBUG level with the raw hash; the
same raw hash is decoded and diagnosed once while its entry remains in the bounded
registry-aware cache, so repeated source rows do not produce log or codec spam. The
cache is cleared when the server supplies a new registry context, allowing a retry
after the compatible modpack is restored.

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
| Container add | Native (`containers` table, action 1) | Canonical components | Reused from GriefLogger; without it, `ContainerSessionListener` emits an interval-bounded `ADD_ITEM` session net delta between open/close. A zero-net withdraw-and-return is not represented. |
| Container remove | Native (`containers` table, action 0) | Canonical components | Reused from GriefLogger; without it, session net delta emits `REMOVE_ITEM`; not click-time history. |
| Capability-mediated transfer | None | N/A | `ContainerCapabilityWrapper` emits `CAPABILITY_INSERT`/`CAPABILITY_EXTRACT` with caller and remote endpoint UNKNOWN; the cause is not asserted as hopper/automation. |
| Player pickup | Native (`items` table, action 3) | Canonical components | Reused + supplemented with `ItemEntity` UUID only when the spatial/time match is unique |
| Player drop | Native (`items` table, action 2) | Canonical components | Ground row emitted only after the ItemEntity is confirmed in the level; canceled toss is `DROP_CANCELLED` to UNKNOWN |
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

A unique constraint over source + source-event ID prevents replay of one producer's row. It
does not establish that a GriefLogger row and an ItemGraph row describe the same physical
event. Cross-source equivalence requires a unique shared `item_entity_uuid` plus matching
action family, fingerprint, amount, actor, and timestamps within 250 ms. Both source rows stay
stored and are cited by the inferred edge; only one canonical row contributes capacity.
Compatible rows without a unique shared entity identity are marked ambiguous and withheld from
allocation. ItemGraph's derived source-group tables and edge-state corrections never write to
the GriefLogger database.

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
