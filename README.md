# ItemGraph

**Trace item movement through time.**

ItemGraph is a server-side Minecraft moderation and forensic analysis mod for **Fabric and NeoForge 1.21.1**.

Its purpose is to reconstruct plausible item-type and stack-quantity movement across players, supported vanilla containers, and ground/entity observations over time, while retaining a native audit ledger for non-item events. It does **not** assign a permanent UUID to every item. ItemGraph records native server evidence into its own database and builds an explainable temporal item-flow graph.

**ItemGraph runs independently of GriefLogger.** Native capture, storage, reconstruction, and native queries require neither the GriefLogger jar nor its database. A separate read-only migration bridge can import legacy evidence when explicitly enabled. Native replacement coverage is tracked in [GriefLogger replacement parity](docs/GRIEFLOGGER_PARITY.md). Fabric and NeoForge use loader-specific event adapters over the same ItemGraph ledger.

ItemGraph **replaces** GriefLogger — the two mods must not be installed
together, and each loader declares the conflict in its metadata (NeoForge
`incompatible`, Fabric `breaks`). Both jars embed a SQLite driver in a way the
module system cannot deduplicate, so coexistence was never a stable state.
Releases ship exactly two files:

- `itemgraph-<version>-fabric.jar`
- `itemgraph-<version>-neoforge.jar`

To migrate a server that runs GriefLogger: stop the server, remove the
GriefLogger mod jar, keep its `database.db` file, install the standard
ItemGraph jar, and point `grieflogger_database_path` at the retained database
with `grieflogger_integration_enabled=true` to import the history read-only.
Only the GriefLogger `1.2.10-1.21.1` schema is a tested import source; other
versions are rejected cleanly rather than mis-imported.

The `0.4.0-beta.1` release additionally published two
`-grieflogger-compatible` jars as the final coexistence artifacts; they are
deprecated and no later release ships them.

The legacy bridge is disabled by default and does not probe `database.db`. To
use it during migration, set NeoForge `general.grieflogger_integration_enabled=true`
or Fabric `grieflogger_integration_enabled=true`, then set
`grieflogger_database_path` to GriefLogger's database file. The source remains
read-only. Native queries work without that bridge; queries over imported legacy
rows require a completed source sync/import. ItemGraph-owned storage defaults to SQLite; set `database_backend=mysql_mariadb`
plus `database_host`, `database_port`, `database_name`, `database_username`,
`database_password`, `database_ssl_mode`, and `database_connection_timeout_ms` to
use the shared MySQL/MariaDB storage contract. Optional non-unique lookup
indexes default to enabled. Set NeoForge `storage.use_indexes=false` or Fabric
`use_indexes=false` and restart to drop optional indexes from ItemGraph's database;
set either key to `true` and restart to recreate them. Required unique indexes
stay enabled for deduplication. MySQL/MariaDB also retain an index when it is the
only index supporting a foreign key. Set `database_ssl_mode=verify-full` or `verify-ca` for MySQL
`caching_sha2_password`; `disable` is intended for a server authentication method
that does not require RSA key retrieval and keeps database traffic plaintext. Use
`verify-full` for production, or `verify-ca` with an explicitly trusted CA;
ItemGraph warns when `disable` is used with a non-loopback host. NeoForge's
database connection keys are under `general`; the index setting is under
`storage` in its server TOML. The full key, default, range, and restart matrix is in the
[configuration reference](docs/CONFIGURATION.md). Query output defaults to ten
rows and is capped by `query.max_page_size`/`max_page_size` (range 1–100).
Raw evidence retention is fixed at `indefinite`; ItemGraph does not purge raw
evidence. Legacy V3–V5 topology resets preserve old observation rows and their
raw payloads in `ig_legacy_observation_evidence`; these superseded rows remain
available for forensic review and are excluded from current graph queries.

Server messages can use `en_us`, `nl_nl`, or `zh_tw` through NeoForge
`general.language` or Fabric `language`. Text is rendered server-side for vanilla
clients; missing keys fall back to English. Help entry text and navigation labels
have translated entries; 174 authored source phrases currently use the explicit
English fallback inventory, including detailed help topics. The exact GriefLogger
1.2.10-1.21.1 locale inventory is unknown; supported ItemGraph locales are
based on pinned 26.2 source config research only.

> Evidence first. Inference second. Confidence explicit. Every conclusion traceable.

## Project identity

- **Name:** ItemGraph
- **Mod ID:** `itemgraph`
- **Primary command:** `/itemgraph`
- **Alias:** `/ig`
- **Platforms:** Fabric, NeoForge
- **Target Minecraft version:** 1.21.1
- **Primary deployment model:** dedicated server
- **Preview integration API:** `com.itemgraph.api` `PREVIEW_1` in the main JAR
- **Changelog:** [`CHANGELOG.md`](CHANGELOG.md)
- **Tagline:** *Trace item movement through time.*

## Why ItemGraph exists

Existing grief and audit loggers are very good at answering questions such as:

- Who removed items from this chest?
- What did this player pick up?
- When was this container modified?
- What blocks were broken here?

They are less suited to answering:

> "Given all available evidence, how did this named armor piece plausibly move from one inventory to another over the last two days?"

ItemGraph adds that reconstruction layer.

## Core idea

ItemGraph models item history as a **directed temporal multigraph**.

- **Nodes** represent inventories or locations capable of holding items.
- **Observed events** record direct evidence.
- **Inferred edges** connect compatible observations into plausible item flow.
- **Time** is a first-class constraint.
- **Quantity** is conserved unless creation, destruction, or transformation evidence exists.
- **Confidence** is explicit and explainable.

Example:

```text
Chest A --14:31, 1x named helmet--> Alice
Alice   --14:52, 1x named helmet--> Ground
Ground  --14:52, 1x named helmet--> Bob
Bob     --15:08, 1x named helmet--> Chest B
```

The system must distinguish what was **observed** from what was **inferred**.

## High-level architecture

```text
GriefLogger / Minecraft / Mod hooks
               |
               v
        Raw observations
               |
               v
        Evidence storage
               |
               v
    Correlation / inference engine
               |
               v
         Item Flow Graph
               |
               v
       Query + explanation UI
```

ItemGraph works independently of GriefLogger and stores its own native evidence and derived data. The GriefLogger read-only migration bridge is disabled by default and never probes `database.db` unless an operator enables `general.grieflogger_integration_enabled` (NeoForge) or `grieflogger_integration_enabled` (Fabric). When enabled, confirmed cross-source copies preserve both raw rows but contribute one quantity capacity; uncertain matches remain ambiguous. Container GUI observations are session net deltas with explicit time bounds, not click history, and generic `IItemHandler` rows keep caller/cause identity UNKNOWN.

## MVP goals

The first useful vertical slice should:

1. Read relevant GriefLogger evidence without mutating it.
2. Record only missing high-value inventory events where necessary.
3. Canonicalize an item fingerprint.
4. Represent inventories as graph nodes.
5. Represent raw observations separately from inferred movement.
6. Reconstruct a simple item path through time.
7. Trace a distinctive named item.
8. Trace ordinary stack quantity flow.
9. Explain why an inferred edge exists.
10. Persist across restarts.
11. Demonstrate the feature safely on staging.

## Commands

Implemented and available. Unset named permissions fall back to vanilla permission
level 2; permission providers can delegate each sensitive surface by exact node. See
`/ig help permissions` and [Security and permissions](docs/SECURITY_AND_PERMISSIONS.md).
Every command requires `itemgraph.command` plus its exact leaf nodes where listed; nodes
do not inherit through dotted names. An explicit permission-provider denial overrides
operator status, while an unset node falls back to vanilla permission level 2.

```text
/ig help [topic]
/ig help permissions
/ig status
/ig audit
/ig ingest now
/ig ingest history
/ig event   <observationId>
/ig explain <edgeId>
/ig lookup <eventType> [limit] [sinceMinutes]
/ig lookup near <dimension> <x> <y> <z> <radius> <eventType> [limit] [sinceMinutes]
/ig lookup player <playerName> <eventType> [limit] [sinceMinutes]
/ig lookup page <page> <eventType> [limit] [sinceMinutes]
/ig lookup action.<value> [user.<value>] [include.<value>] [exclude.<value>] [time.<value>] radius.<value>
/ig lookup provenance <sourceSha256> <table> <sourceKey> [limit]
/ig page <page> [session]
/ig trace item <query> [limit] [sinceMinutes]
/ig trace player <playerName> [limit] [sinceMinutes]
/ig trace container <x> <y> <z> [limit] [sinceMinutes]
/ig gui item <query> [sinceMinutes]
/ig gui player <playerName> [sinceMinutes]
/ig gui container <dimension> <x> <y> <z> [sinceMinutes]
/ig inspect [on|off|status]
```

Native ItemGraph event capture starts automatically when enabled in config.
`/ig ingest now` is only needed to sync from a configured GriefLogger source.

- `/ig help`: bare `/itemgraph` or `/ig` shows a short task-first overview. `/ig help commands` is a compact task hub that points to detailed topics; `/ig help <topic>` provides exact routes, syntax, and examples where applicable. Run `/ig help permissions` for the exact access nodes. Unknown topics list the valid topics.
- `/ig audit`: performs off-thread verification of database invariants (conservation, positivity, relational graph integrity, and allocation state consistency).
- `/ig trace item`: accepts numeric fingerprint IDs, item registry names, or custom item names. Quote namespaced IDs or names containing spaces (for example, `/ig gui item "minecraft:netherite_boots"`); use `/ig gui item "id:123"` to force an exact fingerprint ID when a bare numeric query is ambiguous. Shows supported chronological lineage, including anvil transformations. Craft/smelt outputs with unobserved inputs remain unresolved audit events and do not create trace edges.
- `/ig trace player`: shows all movements involving a player across inventories, ground drops/pickups, containers, and armor stands.
- `/ig trace container`: reconstructs item ingress and egress for a container at coordinates `(x, y, z)`.
- `/ig event`, `/ig explain`, and `/ig trace`: preserve their stable visible text and provide bounded hover fields for evidence class, safe item identity, canonical fingerprint hash, event kind, UTC time, and recorded endpoints. Recorded spatial endpoints include a player-only, one-use `[Go to ...]` link; it rechecks the originating query permissions when clicked and works only while that dimension is loaded.
- `/ig gui`: opens item/player/container timelines in a vanilla six-row chest menu. Each page has at most nine entries. A numbered chat companion identifies each row by evidence class, safe item identity, event kind, and UTC time when available; row numbers match menu slots. Hover text and icons add detail. In a selected detail page, hover each paper icon to read its field; use the page controls and Back button to navigate. Ambiguous targets require candidate selection; missing identities have an explicit fallback. The command and every menu action require `itemgraph.command`, `itemgraph.gui`, and `itemgraph.audit`; see [Security and permissions](docs/SECURITY_AND_PERMISSIONS.md). The menu uses no custom client screen or packet and rejects inventory-movement actions.
- `/ig inspect`: toggles per-player inspection mode. Left-clicking a block shows that block's paginated audit history in chat. Right-clicking a block entity that implements `Container` opens the read-only flow browser instead of the normal container GUI. Double chests clicked on either half resolve to the same canonical anchor used when their contents are recorded. Other right-clicks show paginated block history; ordinary blocks select the block on the clicked face. A click is consumed only after the read-only request is accepted. `on`, `off`, and `status` are deterministic forms. The command and each inspection click require `itemgraph.command.inspect`; an inspection click on a container also requires `itemgraph.gui`. Permission loss clears inspection mode, and inspection does not use the held item or record an inspection click as a transfer.
- `/ig lookup`: a level-1 lookup-only moderator needs both `itemgraph.command` and `itemgraph.command.lookup`. Add `itemgraph.command.page` for `/ig page` and clickable page controls. Provider denies override operator level 2; unset nodes use the level-2 fallback. Use `/ig help permissions` for the other nodes.
- `/ig lookup` and `/ig lookup filters` return native or unified evidence, depending on the syntax. Historical reads are asynchronous and read-only. Native event capture runs automatically when enabled in config. `/ig ingest now` optionally syncs from a configured GriefLogger source; `/ig ingest history` is a separate read-only import from that source. Full argument table and output format: [Query model](docs/QUERY_MODEL.md).

New admins: start with the [admin quick start](docs/ADMIN_QUICK_START.md), which maps common incident questions to every live feature and explains how to interpret evidence.

Sample `/ig trace item` output:

```text
[OBSERVED] CONTAINER 10,64,10 -> AlphaA : 1x at 2026-09-16 14:31:08 UTC (observation#8812 REMOVE_ITEM)
[OBSERVED] AlphaA -> GROUND 20,64,20 : 1x at 2026-09-16 14:31:18 UTC (observation#8813 DROP_ITEM)
[INFERRED conf=0.9990] AlphaA -> BetaB : 1x at 2026-09-16 14:31:18 UTC (edge#9931 inferred transfer spanning 1m0s)
[OBSERVED] GROUND 20,64,20 -> BetaB : 1x at 2026-09-16 14:32:18 UTC (observation#8814 PICKUP_ITEM)
```

Evidence and inference are labelled per line, never once at the top.

## Preview integration API

Trusted server mods can use `com.itemgraph.api` `PREVIEW_1` to register their own source
identity, submit bounded raw observations, and run asynchronous item/player/container
queries. The API is shipped in this JAR, uses a service-issued `SourceHandle`, and returns
immutable DTOs plus opaque evidence URIs—not JDBC, schema IDs, mutable Minecraft state, or
caller-provided inference. A compiling NeoForge fixture lives in `examples/api-consumer`.
The complete boundary and authorization rules are in [Preview integration API](docs/API.md).

## Documentation

- [Business and intended value](Business.md)
- [Decision log](Decision.md)
- [Milestones](Milestones.md)
- [Collaboration policy](Collaboration.md)
- [Open-source policy](OSS.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [MIT license](LICENSE)
- [Architecture](docs/ARCHITECTURE.md)
- [Preview integration API](docs/API.md)
- [Evidence model](docs/EVIDENCE_MODEL.md)
- [ItemGraph event taxonomy](docs/EVENT_TAXONOMY.md)
- [GriefLogger integration](docs/GRIEFLOGGER_INTEGRATION.md)
- [GriefLogger schema versions](docs/GRIEFLOGGER_SCHEMA_VERSIONS.md)
- [Complete GriefLogger and ItemGraph feature comparison](docs/FEATURE_PARITY_INVENTORY.md)
- [GriefLogger source audit](docs/GRIEFLOGGER_SOURCE_AUDIT.md)
- [GriefLogger replacement parity](docs/GRIEFLOGGER_PARITY.md)
- [Query model](docs/QUERY_MODEL.md)
- [Security and permissions](docs/SECURITY_AND_PERMISSIONS.md)
- [Test plan](docs/TEST_PLAN.md)
- [UX audit](docs/UX_AUDIT.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Branding](docs/BRANDING.md)

## Open-source project

ItemGraph is developed in the open under the MIT License. External
contributions use forks and pull requests. The configured non-publishing CI
workflow runs on pull requests and pushes to main; release publishing remains
a maintainer-only tag operation and never exposes release credentials to fork
contributors.

Read CONTRIBUTING.md before opening a pull request. Report security issues
privately using SECURITY.md.

## Development rule

**Production must never be used as the development environment.**

All discovery, schema inspection, testing, database exploration, and early implementation must happen on staging.
