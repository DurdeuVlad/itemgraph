# ItemGraph

**Trace item movement through time.**

ItemGraph is a server-side Minecraft moderation and forensic analysis mod for **Fabric and NeoForge 1.21.1**.

Its purpose is to reconstruct plausible item-type and stack-quantity movement across players, supported vanilla containers, and ground/entity observations over time, while retaining a native audit ledger for non-item events. It does **not** assign a permanent UUID to every item. ItemGraph records native server evidence into its own database and builds an explainable temporal item-flow graph.

**ItemGraph runs independently of GriefLogger.** Native capture, storage, reconstruction, and native queries require neither the GriefLogger jar nor its database. A separate read-only migration bridge can import legacy evidence when explicitly enabled. Native replacement coverage is tracked in [GriefLogger replacement parity](docs/GRIEFLOGGER_PARITY.md). Fabric and NeoForge use loader-specific event adapters over the same ItemGraph ledger.

Each loader has a standard build and a GriefLogger-compatible build. For GriefLogger
`1.2.10-1.21.1`, install the artifact ending in
`-<loader>-grieflogger-compatible.jar`; it omits only ItemGraph's SQLite dependency,
keeps the MariaDB Connector/J driver for ItemGraph-owned MySQL/MariaDB storage, and
requires that exact GriefLogger release. Use the standard loader jar when GriefLogger
is absent. The four release files are:

- `itemgraph-<version>-fabric.jar`
- `itemgraph-<version>-fabric-grieflogger-compatible.jar`
- `itemgraph-<version>-neoforge.jar`
- `itemgraph-<version>-neoforge-grieflogger-compatible.jar`

The compatible variants are a migration bridge while the M8 parity gates remain
open. ItemGraph will switch to the standard loader jar as the only supported
artifact only after every M8 feature-parity gate and the separate M10 operator
cutover checks in [the cutover plan](docs/GRIEFLOGGER_PARITY.md) are approved.
Those later operational checks do not make the GriefLogger JAR or database
runtime dependencies. That cutover retires the
GriefLogger jar dependency; it does not change the read-only importer or delete
the retained source database copy.

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

Implemented and available (all require permission level 2):

```text
/ig help [topic]
/ig status
/ig audit
/ig ingest now
/ig event   <observationId>
/ig explain <edgeId>
/ig trace item <query> [limit] [sinceMinutes]
/ig trace player <playerName> [limit] [sinceMinutes]
/ig trace container <x> <y> <z> [limit] [sinceMinutes]
/ig gui item <query> [sinceMinutes]
/ig gui player <playerName> [sinceMinutes]
/ig gui container <dimension> <x> <y> <z> [sinceMinutes]
/ig inspect [on|off|status]
```

- `/ig help`: bare `/itemgraph` or `/ig` shows the full live command tree. `/ig help <topic>` shows syntax, permission, defaults, query semantics, and an example; unknown topics list the valid topics.
- `/ig audit`: performs off-thread verification of database invariants (conservation, positivity, relational graph integrity, and allocation state consistency).
- `/ig trace item`: accepts numeric fingerprint IDs, item registry names, or custom item names. Quote namespaced IDs or names containing spaces (for example, `/ig gui item "minecraft:netherite_boots"`); use `/ig gui item "id:123"` to force an exact fingerprint ID when a bare numeric query is ambiguous. Shows the chronological timeline, including transformations (`[TRANSFORMATION <type> <- <source>]`).
- `/ig trace player`: shows all movements involving a player across inventories, ground drops/pickups, containers, and armor stands.
- `/ig trace container`: reconstructs item ingress and egress for a container at coordinates `(x, y, z)`.
- `/ig gui`: opens the same item/player/container timelines in a vanilla six-row chest menu. Each page has at most 45 timeline entries; entries distinguish observed from inferred movement, and selecting one opens evidence details. Ambiguous item matches and duplicate player/container nodes require candidate selection rather than silently choosing a target. The menu is permission-level 2, uses no custom client screen or packet, and rejects inventory-movement actions.
- `/ig inspect`: toggles a per-player container-inspection mode. With it enabled, right-clicking a block whose block entity implements `Container` opens that exact dimension/position in the read-only flow browser instead of the normal container GUI. `on`, `off`, and `status` are deterministic forms. The mode requires permission level 2 on both command and click, clears on logout/server stop, and does not consume the held item or record the inspection click as a transfer.
- `limit` defaults to 20 and is capped at 100; `sinceMinutes` defaults to unbounded. Player query results and the database-backed portion of `/ig status` return on the server thread. Entity-less server-thread console/RCON commands receive an acceptance message; completed query lines are logged because vanilla RCON returns its response buffer before asynchronous work completes. `/ig ingest now` queues one complete ingest-and-correlate cycle on the background worker. Full argument table and output format: [Query model](docs/QUERY_MODEL.md).

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
- [Query model](docs/QUERY_MODEL.md)
- [Security and permissions](docs/SECURITY_AND_PERMISSIONS.md)
- [Test plan](docs/TEST_PLAN.md)
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
