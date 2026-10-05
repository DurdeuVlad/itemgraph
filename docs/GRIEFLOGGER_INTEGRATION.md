# GriefLogger Integration

## Purpose

ItemGraph is the standalone product and intended complete replacement for
GriefLogger. Native capture, storage, reconstruction, and queries require neither
the GriefLogger jar nor its database. This document describes the optional,
read-only transition bridge, explicit historical import, and temporary
coexistence artifact.

## Supported modes

| GriefLogger installation | ItemGraph behavior |
|---|---|
| Absent | ItemGraph runs its native capture, own database, graph reconstruction, and queries. Exact coverage limits are tracked in the parity matrix. |
| Present, configured source file absent | Same native-only behavior. Both loader configs default the source path to `database.db`; a missing file disables the read-only transition integration. |
| Present, source file configured | ItemGraph can poll supported `items` and `containers` rows and run a separate explicit 11-table historical import through read-only connections. ItemGraph owns the imported copy and never writes to the source database. |

The reader is a migration facility, not a runtime dependency or source of
ItemGraph's native capture. When configured, its supported row poll runs
alongside native capture; the separate historical import is explicit. Imported
rows retain their source labels and provenance. The config path cannot be blank;
when another `database.db` exists, set the path to a known missing file to
disable the bridge explicitly.

## Artifact selection and SQLite module compatibility

The standard `itemgraph-<version>.jar` bundles `org.xerial:sqlite-jdbc` and
MariaDB Connector/J with NeoForge Jar-in-Jar and keeps GriefLogger optional. GriefLogger
`1.2.10-1.21.1` packages `org.sqlite.*` in its main mod module; when both standard
artifacts load, Java module resolution sees two modules exporting `org.sqlite.util`
and aborts server startup. Jar-in-Jar version negotiation cannot remove classes
embedded in GriefLogger's main JAR.

For temporary coexistence with servers running GriefLogger `1.2.10-1.21.1`, use
`itemgraph-<version>-grieflogger-compatible.jar`. It omits ItemGraph's SQLite
Jar-in-Jar dependency, keeps ItemGraph's MariaDB Connector/J driver, uses GriefLogger's SQLite classes, and declares that exact
GriefLogger version as required in `META-INF/neoforge.mods.toml`. Do not install
both ItemGraph artifacts together. CI builds and structurally verifies both; the
release workflow attaches both to GitHub Releases and publishes each CurseForge
file with matching dependency metadata.

ItemGraph-owned storage can use SQLite (the default) or MySQL/MariaDB through the
same migration and query contract. NeoForge exposes `general.database_backend`,
`database_host`, `database_port`, `database_name`, `database_username`,
`database_password`, `database_ssl_mode`, and `database_connection_timeout_ms`, plus
`storage.use_indexes`. Fabric exposes the storage key as `use_indexes` and the
other keys in `config/itemgraph.properties`. `use_indexes=true` is the default
and manages ItemGraph's optional non-unique lookup indexes on startup. Set it
to `false` and restart to remove
those performance indexes; setting it back to `true` and restarting recreates
them. MySQL/MariaDB retain an index when it is the only index supporting a
foreign key, because those engines require the index to enforce the constraint.
Migration-owned unique indexes remain enabled. This setting applies only to
ItemGraph's database and never changes GriefLogger's read-only database.
The SSL mode accepts
`disable`, `trust`, `verify-ca`, or `verify-full`. Use `mysql_mariadb` for the network
backend. The supported CI floor is MySQL 8.0+ and MariaDB 10.11+; the bundled
MariaDB Connector/J 3.5.7 driver is used for both server families. GriefLogger's
source database remains SQLite and read-only regardless of ItemGraph's own backend.
Application connections do not enable RSA public-key retrieval. MySQL deployments
using `caching_sha2_password` must configure `database_ssl_mode=verify-full` (or
`verify-ca` with an explicitly trusted CA) or use a server authentication method
that does not require key retrieval. `trust` encrypts the connection but does not
verify the server identity. The default `disable` mode is plaintext and is intended
only for local loopback development; ItemGraph logs a warning when a non-loopback
host uses it. Configure `verify-full` for production, or `verify-ca` when the
deployment has an explicitly trusted CA and hostname verification is handled
separately.
The CI-only MySQL fixture explicitly enables `allowPublicKeyRetrieval` with dummy
credentials to exercise the disposable service.

This artifact is a temporary bridge while tracked replacement gates remain
open; it is not required when running ItemGraph alone. Its tested scope is
GriefLogger `1.2.10-1.21.1` on
Minecraft 1.21.1 / NeoForge 21.1.248. Other GriefLogger versions need a separate
compatibility check before widening this artifact's declared version range.

## Integration principle

```text
ItemGraph               = native event capture, owned evidence, reconstruction, and queries
GriefLogger transition  = optional read-only supported-row sync plus explicit history import
Compatible artifact    = temporary coexistence bridge for SQLite class conflicts
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
| Armor stand interaction | No writer in the exact 1.2.10-1.21.1 release; pinned 26.2 source writes a completed interaction | `INTERACT_ENTITY` | ItemGraph records consuming results from the `ArmorStand.interactAt` override and inherited `Entity.interact` fallback as `INTERACT_ENTITY_COMPLETED`, `FAIL` as `INTERACT_ENTITY_DENIED`, and fallback-method `PASS` as `INTERACT_ENTITY_UNRESOLVED`. NeoForge callback cancellation is retained as denied/canceled evidence. Fabric wraps the aggregate `UseEntityCallback` invoker and records its final non-`PASS` result once, including short-circuits before or after ItemGraph's listener. Runtime player-interaction replay remains unverified. Attempts retain held item ID, count, and canonical fingerprint hash only. These results do not establish an item transfer. |
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

The historical importer is an explicit operator-triggered pass; it does not run
on every 60-second item/container ingestion tick. `GriefLoggerHistoricalImporter`
reads all eleven pinned 26.2 tables through the read-only adapter and writes only
ItemGraph-owned `ig_grieflogger_*` tables.

Preferred behavior:

1. Compute the source file SHA-256 and deterministic schema fingerprint.
2. Read each present table in bounded batches, using a primary-key source key or
   deterministic payload-hash/ordinal identity; SQLite `rowid` is retained as
   provenance when available but is not treated as a durable identity.
3. Preserve every row as canonical JSON plus original binary fields and explicit
   unresolved reasons for opaque payloads or unknown action IDs.
4. Validate the supported GriefLogger core schema before creating an import run;
   an unrelated readable SQLite file is rejected instead of being reported as a
   complete import with eleven missing tables.
5. Use an independent ItemGraph writer connection, commit bounded row batches and
   table boundaries, and advance checkpoints only after successful persistence so
   live observation queues are not blocked by the historical scan.
6. Re-running the same source is idempotent through the source-hash/table/key
   primary key; a failed source snapshot can resume without writing the source.

If a later table fails after earlier batches were committed, the run is marked
`FAILED` with the durable table and row counts plus the failed table's committed
partial report. The status record therefore cannot claim zero imported rows while
the provenance ledger already contains committed data.

The import report records missing tables, source/schema fingerprints, row counts,
opaque counts, checkpoint keys, and completion status in
`ig_grieflogger_import_runs` and `ig_grieflogger_import_checkpoints`.

### Normalized historical lookup

Migration v15 adds the rebuildable `ig_grieflogger_lookup` projection. Migration
v16 adds a unique `(source_type, source_event_id)` index to `ig_audit_events` so
native events with durable producer identities are retry-safe. Rows from
`items`, `containers`, `blocks`, `sessions`, `chats`, and `commands` are normalized
while the source connection is open read-only. The projection preserves the source
table and stable source key, original action ID, timestamp, actor UUID/name, level
and coordinates, material/entity subject, quantity, and an evidence class. A
historical username row is selected by UUID and the latest username timestamp at or
before the event, so a rename does not rewrite earlier evidence. Opaque component
bytes remain in `ig_grieflogger_rows`; the projection stores their SHA-256 and marks
the row `UNRESOLVED` with an explicit reason.

`UnifiedEvidenceQueryService.findFiltered` merges these normalized rows with native
audit, observations, and transformations. Reference and identity tables have no
event location, so they are available only through the exact
`source_sha256`/`table_name`/`source_key` method
`findHistoricalProvenance`; those rows are labeled `PROVENANCE_ONLY` and never
contribute item quantity. When multiple immutable source snapshots exist, the
bounded timeline uses the latest completed snapshot; exact provenance lookup can
still open any earlier snapshot by its source hash.

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

- armor stand method results from the `ArmorStand.interactAt` override and inherited `Entity.interact` fallback, plus canceled callbacks on both loaders (interaction evidence only; equipment movement remains unobserved). Fabric captures the final aggregate callback result, but live player-interaction replay remains unverified
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
