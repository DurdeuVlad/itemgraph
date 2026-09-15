# ItemGraph — Phase 0 Reconnaissance Report

**Date:** 2026-09-15  
**Author:** ItemGraph Engineering Team  
**Status:** Completed  

---

## 1. Environment Findings

Reconnaissance was conducted directly against the staging environment (`home-server-1`, container `mc-staging-server`, data root `/mnt/raid-storage/mc-staging/data/`).

| Dimension | Observed Value | Verification Method |
|---|---|---|
| **Host OS** | Ubuntu 24.04 LTS (Linux kernel 6.8.0) | `uname -a`, host environment |
| **Java Runtime** | OpenJDK 21.0.12 (build 21.0.12+8-1-24.04-Ubuntu) | `java -version` |
| **Minecraft Version** | 1.21.1 | Live server logs / mod metadata |
| **Mod Loader** | NeoForge 21.1.248 | Live server logs / jar manifests |
| **GriefLogger Mod** | `grieflogger-1.2.10-1.21.1-neoforge.jar` (18.9 MB) | `/mnt/raid-storage/mc-staging/data/mods/` |
| **GriefLogger Config** | `grieflogger.toml` (`useMysql = false`, `useIndexes = true`, `serverSideOnlyMode = true`) | `/mnt/raid-storage/mc-staging/data/config/grieflogger/` |
| **GriefLogger DB Engine** | SQLite 3 | Verified via `file` & `sqlite3` CLI |
| **GriefLogger DB Path** | `/mnt/raid-storage/mc-staging/data/database.db` | Staging root directory |
| **Factions/Teams Mod** | `SimpleTeams-1.1.0.jar` (`com.simpleteams`) | Inspected jar contents and team data |
| **Storage & Inventory Mods** | `supplementaries-1.21.1-3.9.9` (sacks, safes, jars, pedestals), `lootr` (instanced chests), `corpse` (death inventories), `ToolBelt`, `shoppy2` (player shops), `trotting_wagons` (wagon inventories), `curios` | Inspected mod jars in staging mods directory |
| **Automation Mods** | `create-1.21.1-6.0.10` (belts, funnels, chutes, vaults, mechanical arms) | Inspected mod jars |
| **Scripting / KubeJS** | `kubejs-neoforge-2101.7.2`, `kubejs-create-neoforge-2101.3.1` | Inspected mod jars |

---

## 2. GriefLogger Deep Dive & Coverage Matrix

### 2.1 Schema & Storage Analysis

Inspecting `/mnt/raid-storage/mc-staging/data/database.db` (read-only):

- **Tables:** `blocks`, `chats`, `commands`, `containers`, `entities`, `items`, `levels`, `materials`, `sessions`, `users`, `usernames`.
- **Primary Keys:**
  - Dimension/lookup tables (`materials`, `users`, `usernames`, `levels`, `entities`) have explicit `id integer PRIMARY KEY`.
  - Event tables (`items`, `containers`, `blocks`, `sessions`, `chats`, `commands`) **do not define an explicit primary key column**.
  - However, SQLite maintains an automatic 64-bit integer `rowid` (`_rowid_`) for standard tables. Queried via `SELECT rowid FROM items`, confirming sequential IDs `1, 2, 3...`. ItemGraph will use `rowid` as the durable `source_event_id` checkpoint.
- **Timestamp Representation:**
  - `time` is a 64-bit integer recording Unix timestamp in **milliseconds** (e.g. `1789330407684`).
- **Item Data / Metadata Representation:**
  - `data` column is `blob DEFAULT NULL`.
  - Disassembly of `com.daqem.grieflogger.model.SimpleItemStack` confirmed serialization:
    - Metadata is held as `net.minecraft.core.component.DataComponentPatch`.
    - Encoded via `DataComponentPatch.STREAM_CODEC.encode(friendlyByteBuf, this.tag)` onto a `RegistryFriendlyByteBuf`.
    - In staging DB, 100% of the 78 rows in `items` have `data` populated (lengths 2 to 209 bytes).
    - Unaltered items with default components have minimal/empty patch buffers (2 bytes, header only). Custom/damaged items have full component patches.

### 2.2 Action ID Mapping Discovered from Bytecode

Disassembly of `com.daqem.grieflogger.model.action.ItemAction` established the authoritative enum mapping:

| Action ID | Enum Identifier | Operation Semantics | Observed Count in Staging DB |
|---|---|---|---|
| **0** | `REMOVE_ITEM` | `REMOVE` (e.g. from container) | 0 |
| **1** | `ADD_ITEM` | `ADD` (e.g. to container) | 0 |
| **2** | `DROP_ITEM` | `REMOVE` (player drop to world) | 44 rows |
| **3** | `PICKUP_ITEM` | `ADD` (player pickup from world) | 0 |
| **4** | `CRAFT_ITEM` | `ADD` (player crafted item) | 1 row |
| **5** | `BREAK_ITEM` | `REMOVE` (tool/item durability broke) | 0 |
| **6** | `CONSUME_ITEM` | `REMOVE` (player ate food / drank potion) | 10 rows |
| **7** | `THROW_ITEM` | `REMOVE` (thrown snowball / egg / ender pearl) | 23 rows |
| **8** | `SHOOT_ITEM` | `REMOVE` (shot arrow from bow / crossbow) | 0 |
| **9** | `ADD_ITEM_ENDER` | `ADD` (placed into Ender Chest) | 0 |
| **10** | `REMOVE_ITEM_ENDER` | `REMOVE` (taken from Ender Chest) | 0 |

### 2.3 GriefLogger Coverage Matrix

| Event | GriefLogger Coverage | Metadata Quality | ItemGraph Hook Needed? | Notes |
|---|---|---:|---|---|
| **Container add** | Partial | High (DataComponentPatch blob) | **Yes** | GriefLogger logs `ADD_ITEM` only on container menu close if block entity extends `BaseContainerBlockEntity`. Modded containers (`IItemHandler`), unclosed container crashes, and automation are missed. |
| **Container remove** | Partial | High (DataComponentPatch blob) | **Yes** | Same constraint as container add. |
| **Player pickup** | Supported | High (DataComponentPatch blob) | **Low / Supplemental** | Logged via `PlayerEvent.PICKUP_ITEM_POST`. However, GriefLogger discards the entity UUID of the picked-up `ItemEntity`. Capturing entity UUID via ItemGraph hook would enable exact ground tracking. |
| **Player drop** | Supported | High (DataComponentPatch blob) | **Low / Supplemental** | Logged via `ServerPlayer.drop` mixin. Similar to pickup, the spawned `ItemEntity` UUID is not logged in GriefLogger DB. |
| **Crafting** | Supported | High (DataComponentPatch blob) | **Low** | Logged via `PlayerEvent.CRAFT_ITEM`. Inputs/ingredients are not linked, but output creation is logged. |
| **Consumption** | Supported | High (DataComponentPatch blob) | **No** | Logged via `ItemAction.CONSUME_ITEM`. |
| **Armor stand equip** | **None** | N/A | **Yes** (Phase 8) | GriefLogger has zero tracking for armor stands. |
| **Armor stand unequip**| **None** | N/A | **Yes** (Phase 8) | GriefLogger has zero tracking for armor stands. |
| **Player inv-only transfer** | **None** | N/A | **No** | Internal player inventory rearrangement is out of scope for MVP. |
| **Ender chest add/remove** | Supported | High (DataComponentPatch blob) | **No** | Logged via `ADD_ITEM_ENDER` and `REMOVE_ITEM_ENDER`. |
| **Hopper transfer** | **None** | N/A | **Yes** (Phase 8) | GriefLogger has zero hopper/chute/automation logging. |
| **Modded coffer / sack / safe** | Partial / None | High (when covered) | **Yes** (Phase 8) | Only standard `BaseContainerBlockEntity` implementations are captured by GriefLogger. |
| **Backpack / Curios** | **None** | N/A | **Yes** (Phase 8) | Handheld or equipped container interactions are not tracked. |
| **Faction storage** | **None** | N/A | **Deferred** | SimpleTeams has no dedicated shared block container. |
| **Anvil rename** | **None** | N/A | **Yes** (Phase 9) | Not logged as a rename transformation. |
| **Smithing** | **None** | N/A | **Yes** (Phase 9) | Not logged as an upgrade transformation. |
| **Item break** | Supported | High (DataComponentPatch blob) | **No** | Logged via `BREAK_ITEM`. |

---

## 3. Identified Logging Gaps

1. **Containers table is empty (`0 rows`):**
   GriefLogger's `ContainerTransactionManager` only fires when:
   - A player opens a GUI via `openMenu` on a container implementing `BaseContainerBlockEntity`.
   - The GUI is normally closed via `doCloseContainer`.
   Modded containers using NeoForge's capability system (`IItemHandler`) rather than `BaseContainerBlockEntity` are completely ignored.
2. **Ground Item Entity Identity:**
   When an item is dropped (`ItemAction.DROP_ITEM`) and picked up (`ItemAction.PICKUP_ITEM`), GriefLogger logs the player, location, and item bytes, but drops the Minecraft `ItemEntity` UUID. Without the entity UUID, correlation relies on spatial-temporal proximity and stack size matching. A lightweight ItemGraph hook on item entity spawn / pickup can supply exact ground continuity.
3. **Hopper & Mechanical Automation:**
   In heavily modded environments with Create (chutes, funnels, belts, hoppers), items can move between inventories without any player menu interactions. GriefLogger captures 0% of this movement.
4. **Armor Stands & Mannequins:**
   Armor stands are common targets for theft or gear transfer. GriefLogger has no armor stand event listeners.
5. **Transformations:**
   Anvils (renaming, repairing) and smithing tables transform items. Currently, GriefLogger will only see drop/pickup or container transactions, losing the transformation lineage.

---

## 4. Proposed ItemGraph Architecture

ItemGraph follows a strictly decoupled layered architecture:

```text
+-------------------------------------------------------------------+
|                        Command / UX Layer                         |
|   /ig status  |  /ig trace  |  /ig explain  |  /ig event          |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
|                    Reconstruction / Graph Layer                   |
|   Temporal Multigraph  |  Scoring Engine  |  Path Finding         |
+---------------------------------+---------------------------------+
                                  |
+---------------------------------v---------------------------------+
|                       Evidence Data Store                         |
|   ItemGraph SQLite DB (observations, checkpoints, nodes, edges)   |
+------------------+--------------------------------+---------------+
                   ^                                ^
                   | (read-only polling / ingest)   | (direct events)
+------------------+------------------+  +----------+---------------+
|     GriefLogger Ingestion Adapter   |  |   ItemGraph NeoForge     |
|   - Read-only SQLite connector      |  |   Supplemental Hooks     |
|   - Incremental checkpointing       |  |   - Container IItemHandler|
|   - Schema drift validation         |  |   - Armor stand listener |
+-------------------------------------+  +--------------------------+
```

### Threading Boundaries
- **Server Thread:** Reads minimal state, serializes immutable event structs, enqueues to bounded ingestion channel.
- **Worker Pool:** Ingests GriefLogger rows, calculates SHA-256 fingerprints, executes SQLite migrations and writes, handles `/ig` trace queries asynchronously.
- **Thread Safety:** Never touches `ServerLevel` or `ServerPlayer` from worker threads.

---

## 5. Proposed ItemGraph Database Model

ItemGraph will use an independent SQLite database located at:
`/mnt/raid-storage/mc-staging/data/itemgraph/itemgraph.db`

### Schema:
```sql
-- Migration version control
CREATE TABLE IF NOT EXISTS ig_schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL,
    description TEXT NOT NULL
);

-- Ingestion checkpoints for external sources
CREATE TABLE IF NOT EXISTS ig_source_checkpoints (
    source_name TEXT PRIMARY KEY,
    last_source_rowid INTEGER NOT NULL,
    last_timestamp INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Canonical Inventory Nodes
CREATE TABLE IF NOT EXISTS ig_nodes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    node_type TEXT NOT NULL,         -- 'PLAYER', 'CONTAINER', 'GROUND', 'ARMOR_STAND', 'UNKNOWN'
    owner_uuid TEXT,                 -- Player UUID if applicable
    level_id TEXT NOT NULL,          -- Dimension ResourceLocation (e.g. 'minecraft:overworld')
    x REAL, y REAL, z REAL,          -- Spatial coordinates
    block_id TEXT,                   -- Registry ID if container (e.g. 'minecraft:chest')
    custom_label TEXT                -- Optional human-readable name
);

-- Canonical Item Fingerprints
CREATE TABLE IF NOT EXISTS ig_item_fingerprints (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id TEXT NOT NULL,           -- e.g. 'minecraft:diamond_sword'
    fingerprint_hash TEXT NOT NULL UNIQUE, -- SHA-256 of canonical component payload
    custom_name TEXT,                -- Extracted display name if present
    rarity TEXT,
    component_summary TEXT           -- JSON summary of key components (enchantments, damage, trim)
);

-- Authoritative Raw Observations
CREATE TABLE IF NOT EXISTS ig_observations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_type TEXT NOT NULL,       -- 'GRIEFLOGGER' or 'ITEMGRAPH_HOOK'
    source_event_id INTEGER,         -- SQLite rowid from GriefLogger
    timestamp_ms INTEGER NOT NULL,
    node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
    target_node_id INTEGER REFERENCES ig_nodes(id),
    fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
    action_type TEXT NOT NULL,       -- 'ADD', 'REMOVE', 'DROP', 'PICKUP', 'CONSUME', 'BREAK', etc.
    amount INTEGER NOT NULL,
    raw_data BLOB,                   -- Original DataComponentPatch bytes
    FOREIGN KEY(node_id) REFERENCES ig_nodes(id),
    FOREIGN KEY(fingerprint_id) REFERENCES ig_item_fingerprints(id)
);

CREATE INDEX IF NOT EXISTS idx_obs_time_fp ON ig_observations(timestamp_ms, fingerprint_id);
CREATE INDEX IF NOT EXISTS idx_obs_source ON ig_observations(source_type, source_event_id);

-- Derived Inferred Edges
CREATE TABLE IF NOT EXISTS ig_inferred_edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
    to_node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
    fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
    amount INTEGER NOT NULL,
    time_start INTEGER NOT NULL,
    time_end INTEGER NOT NULL,
    confidence REAL NOT NULL,        -- Deterministic score 0.000 to 1.000
    explanation TEXT NOT NULL,       -- Human-readable rationale
    created_at INTEGER NOT NULL
);

-- Supporting Evidence linking Inferred Edges to Observations
CREATE TABLE IF NOT EXISTS ig_edge_evidence (
    edge_id INTEGER NOT NULL REFERENCES ig_inferred_edges(id),
    observation_id INTEGER NOT NULL REFERENCES ig_observations(id),
    PRIMARY KEY(edge_id, observation_id)
);
```

---

## 6. Proposed Event / Fingerprint Model

### Canonical Item Fingerprint
ItemGraph does NOT assign UUIDs to items. Instead, it computes a deterministic hash of the item's canonical characteristics:
$$\text{Fingerprint} = \text{SHA-256}(\text{Registry ID} \parallel \text{Canonical DataComponentPatch})$$

Components included in canonicalization:
1. Item Registry Key (`ResourceLocation`, e.g. `minecraft:netherite_chestplate`)
2. `custom_name` component
3. `enchantments` component (sorted deterministically by enchantment ID)
4. `damage` component (current durability state)
5. `trim` component
6. `lore` component
7. Mod-specific persistent data components

For stackable items with identical components, the fingerprint is identical, and quantity flow conservation tracks splits/merges across temporal intervals.

---

## 7. Proposed MVP (Phase 0 – Phase 5 Target)

Demonstrate end-to-end tracing on staging:
```text
Chest A (x1, y1, z1)
  -> Player A
  -> Ground (Drop)
  -> Player B (Pickup)
  -> Chest B (x2, y2, z2)
```
For a distinctive named item (e.g. `Named Diamond Helmet`), `/ig trace item ...` outputs:
- Chronological path steps
- Node names and coordinates
- Confidence scores and exact timestamps
- References to underlying GriefLogger & ItemGraph observation IDs

---

## 8. Risks and Unknowns

1. **GriefLogger SQLite Write Lock / Concurrency:**
   - SQLite allows only one writer at a time. While ItemGraph only reads GriefLogger (`mode=ro`), reading while GriefLogger's background queue is writing a transaction could encounter `SQLITE_BUSY`.
   - *Mitigation:* Always open GriefLogger connection with `PRAGMA busy_timeout = 5000` and `query_only = true` / read-only URI mode (`file:...db?mode=ro`).
2. **Container Event Deficit:**
   - GriefLogger has 0 container rows on staging, meaning container operations without ItemGraph hooks will have substantial gaps.
   - *Mitigation:* In Phase 2/3, supplement container open/close and capability interactions using NeoForge events.
3. **Lazymc Server Sleep:**
   - Staging server sleeps when idle. CLI or RCON commands will fail if the server is asleep.
   - *Mitigation:* Use `docker compose up -d --no-deps mc-server` before RCON commands and handle container wakeups.

---

## 9. Implementation Plan (Phased Roadmap)

- **Phase 0 (Reconnaissance):** Complete (this report).
- **Phase 1 (Project Skeleton):** NeoForge 1.21.1 MDK Gradle project setup, configuration, logging, `/ig status` command, database abstraction layer with SQLite driver, and schema migration framework.
- **Phase 2 (Evidence Ingestion):** Read-only GriefLogger polling adapter with incremental checkpoints.
- **Phase 3 (Item Canonicalization):** 1.21.1 data component canonicalization & SHA-256 fingerprinting.
- **Phase 4 (Graph Nodes):** Stable node identities for players, containers, ground, armor stands.
- **Phase 5 (Basic Correlation):** Scoring engine for transfer reconstruction.
- **Phase 6 (Query Commands):** `/ig trace`, `/ig explain`, `/ig event`.
- **Phase 7 (Quantity Flow):** Stack conservation for split/merge transfers.
- **Phase 8 (Missing High-Value Integrations):** Armor stands, modded containers, hoppers.
- **Phase 9 (Transformations):** Anvil, smithing, crafting transformations.
- **Phase 10 (Hardening & Delivery):** Final verification, metrics, audit logs.
