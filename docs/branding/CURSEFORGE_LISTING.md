# CurseForge Publishing Guide & Brand Assets

This document contains everything needed to list and publish **ItemGraph** on CurseForge.

---

## 1. Asset Checklist & File Paths

| Asset | File Path | Dimensions | CurseForge Field | Notes |
|---|---|---|---|---|
| **Project Avatar (Logo)** | [`docs/branding/curseforge_avatar_512.png`](file:///E:/Github2/itemgraph/docs/branding/curseforge_avatar_512.png) | 512 × 512 | **Project Logo** | Dark slate tile with amber graph trace, nearest-neighbour pixel art. Also available in 1024×1024 at [`docs/branding/curseforge_avatar_1024.png`](file:///E:/Github2/itemgraph/docs/branding/curseforge_avatar_1024.png). |
| **Header Banner** | [`docs/branding/curseforge_banner.png`](file:///E:/Github2/itemgraph/docs/branding/curseforge_banner.png) | 1024 × 256 | **Project Description Header** | Includes title "ItemGraph", tagline, and slate graph motifs. Embed at top of Description. |
| **In-Game Icon** | [`src/main/resources/itemgraph_icon.png`](file:///E:/Github2/itemgraph/src/main/resources/itemgraph_icon.png) | 256 × 256 | Bundled in Jar | Loaded by NeoForge mods menu via `neoforge.mods.toml`. |
| **Release Jar** | [`build/libs/itemgraph-0.1.0.jar`](file:///E:/Github2/itemgraph/build/libs/itemgraph-0.1.0.jar) | Jar File | **File Upload** | Built with Java 21, NeoForge 21.1.248. |

---

## 2. Project Metadata

- **Project Name**: `ItemGraph`
- **Primary Tagline / Short Description** (169 / 255 chars):
  > *A server-side forensic moderation mod for NeoForge 1.21.1. Reconstructs item movement, stack splits, and transformations through time from authoritative evidence.*
- **Primary Category**: `Server Utility`
- **Secondary Categories**: `Utility & QoL`, `Management & Hosting`
- **Environment**:
  - **Server**: Required
  - **Client**: Unsupported / Optional (Server-Side only)
- **License**: MIT (or project default)
- **Source Code URL**: `https://github.com/DurdeuVlad/itemgraph`
- **Issue Tracker URL**: `https://github.com/DurdeuVlad/itemgraph/issues`

---

## 3. Recommended Gallery Screenshots

CurseForge projects with 3–5 clean screenshots convert substantially higher. The following screenshots should be captured in-game:

1. **Screenshot 1: The Core Reconstructed Trace (`/ig trace item`)**
   - **Caption**: *Chronological item trace distinguishing [OBSERVED] raw facts from [INFERRED] temporal flows.*
   - **Content**: Run `/ig trace item <query>` showing container withdraw, ground drop, inferred bridge, pickup, and container deposit.

2. **Screenshot 2: Forensic Explanation (`/ig explain <edgeId>`)**
   - **Caption**: *Deterministic factor breakdown and evidence citations for every inferred transfer.*
   - **Content**: Run `/ig explain <id>` showing confidence arithmetic, candidate counts, spatial delta, and exact supporting observations.

3. **Screenshot 3: Player Timeline Trace (`/ig trace player <player>`)**
   - **Caption**: *Comprehensive movement timeline for a player across containers, ground drops, and armor stands.*
   - **Content**: Run `/ig trace player <player>` highlighting multi-hop transfers.

4. **Screenshot 4: Database Invariant Audit (`/ig audit`)**
   - **Caption**: *Live off-thread verification of quantity conservation, positivity, and graph integrity.*
   - **Content**: Run `/ig audit` reporting `HEALTHY (ALL INVARIANTS SATISFIED)`.

5. **Screenshot 5: Server Status & Telemetry (`/ig status`)**
   - **Caption**: *Operational status showing GriefLogger detection, ingestion throughput, and entity tracking.*
   - **Content**: Run `/ig status`.

---

## 4. Full Project Description (Ready to Paste into CurseForge)

```markdown
![ItemGraph Banner](https://raw.githubusercontent.com/DurdeuVlad/itemgraph/master/docs/branding/curseforge_banner.png)

# ItemGraph

**Trace item movement through time.**

ItemGraph is a server-side Minecraft moderation and forensic analysis mod for **NeoForge 1.21.1**.

Existing loggers (like GriefLogger or CoreProtect) are exceptional at telling you *what happened at a single coordinate*—who opened a chest, who broke a block, or who picked up an item. But when items disappear across players, ground drops, and containers, server admins are left manually cross-referencing timestamps.

**ItemGraph adds that missing reconstruction layer.**

Instead of assigning artificial, invasive UUIDs to every Minecraft item, ItemGraph models item history as a **directed temporal multigraph** using canonical component fingerprints and an explicit quantity-allocation ledger.

> **Evidence first. Inference second. Confidence explicit. Every conclusion traceable.**

---

### 🛡️ Core Architectural Principles

1. **No Synthetic Per-Item UUIDs**: Works with vanilla and modded items without altering item NBT or bloating world save files.
2. **Strict Quantity Conservation**: The engine never manufactures quantity ($\sum \text{allocated} \le \text{evidenced capacity}$). Stack splits (1-to-many) and merges (many-to-1) are tracked through an explicit allocation ledger.
3. **Explicit Provenance**: Chat and logs strictly distinguish `[OBSERVED]` raw evidence from `[INFERRED conf=...]` transfers.
4. **Read-Only Integration**: GriefLogger's SQLite database is strictly read-only (`PRAGMA query_only = ON`). ItemGraph owns its own isolated SQLite storage (`run/itemgraph/itemgraph.db`).
5. **Zero Server Lag**: Graph traversals, heavy historical scans, and invariant audits execute exclusively on asynchronous worker threads.

---

### ✨ Key Features

- **Ground Bridge Reconstruction**: Reconstructs player-to-player transfers across ground tosses and pickups within configurable correlation windows.
- **Authoritative Entity Continuity**: Matches Minecraft `ItemEntity` UUIDs on drop and pickup, boosting transfer confidence to `0.9990`.
- **Transformation Tracking**: Automatically links item identity shifts across **Anvil repairs & renames**, **Crafting tables**, and **Smelting furnaces**.
- **Armor Stand & Container Integration**: Captures armor stand equipment swaps alongside standard chest, barrel, and hopper inventories.
- **Live Invariant Auditor (`/ig audit`)**: Self-diagnosing auditor verifies database health, quantity conservation, and relational integrity on demand.

---

### 📜 Commands & Examples

*All commands require permission level 2 (moderator/op).*

#### `/ig trace item <query> [limit] [sinceMinutes]`
Reconstructs the complete lifecycle of an item by numeric fingerprint ID, item registry ID (e.g. `minecraft:netherite_boots` or `diamond_sword`), or custom name.
```text
[OBSERVED] CONTAINER 10,64,10 -> Alice : 1x at 2026-09-17 12:00:00 UTC (observation#14 REMOVE_ITEM)
[OBSERVED] Alice -> GROUND 15,64,15 : 1x at 2026-09-17 12:00:05 UTC (observation#15 DROP_ITEM)
[INFERRED conf=0.9990] Alice -> Bob : 1x at 2026-09-17 12:00:05 UTC (edge#8 inferred transfer spanning 3s)
[OBSERVED] GROUND 15,64,15 -> Bob : 1x at 2026-09-17 12:00:08 UTC (observation#16 PICKUP_ITEM)
[OBSERVED] Bob -> Bob : 1x at 2026-09-17 12:00:30 UTC (transformation#1 [TRANSFORMATION ANVIL_RENAME <- minecraft:netherite_boots] (Renamed on Anvil))
```

#### `/ig explain <edgeId>`
Explains *why* ItemGraph inferred a transfer, detailing mathematical factor scores, candidate counts, spatial delta, and exact evidence citations.
```text
Inferred Edge #8: amount=1, conf=0.9990
Explanation: Ground bridge (exact transfer): Alice dropped 1x minecraft:netherite_boots at GROUND 15,64,15 (obs#15); Bob picked up 1x there 3s later (obs#16). Matched on exact item fingerprint, same ground block, within 300s window. Authoritative Minecraft ItemEntity UUID match establishes direct entity continuity on the ground.
```

#### `/ig trace player <playerName> [limit] [sinceMinutes]`
Reconstructs all item ingress, egress, container interactions, and ground exchanges involving a specific player.

#### `/ig trace container <x> <y> <z> [limit] [sinceMinutes]`
Shows all item additions and removals for a container at block coordinates `(x, y, z)`.

#### `/ig audit`
Runs an asynchronous invariant check across the database:
```text
[ItemGraph] === DATABASE INVARIANT AUDIT ===
  Status: HEALTHY (ALL INVARIANTS SATISFIED)
  Observations: 23 | Inferred edges: 10
  Allocations: 20 | Transformations: 1
  Conservation violations: 0
  Non-positive quantities: 0
  Orphaned allocations: 0
```

#### `/ig status`
Displays live operational health, background ingestion checkpoints, correlation metrics, internal queue throughput, and entity tracking counters.

---

### 📦 Installation & Requirements

- **Platform**: **NeoForge 1.21.1** (tested on `21.1.248+`)
- **Java**: Java 21+
- **Environment**: **Server-Side Only**. Players do not need ItemGraph installed on their clients to join.
- **Dependencies**:
  - [GriefLogger](https://www.curseforge.com/minecraft/mc-mods/grieflogger) (`1.2.10+` for 1.21.1)
  - [Architectury API](https://www.curseforge.com/minecraft/mc-mods/architectury-api)
  - [SuperMartijn642's Config Lib](https://www.curseforge.com/minecraft/mc-mods/supermartijn642s-config-lib)
```
