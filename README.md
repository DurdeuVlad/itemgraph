# ItemGraph

**Trace item movement through time.**

ItemGraph is a server-side Minecraft moderation and forensic analysis mod for **NeoForge 1.21.1**.

Its purpose is to reconstruct how item types and stack quantities move through players, containers, entities, and other inventories over time. It does **not** assign a permanent UUID to every item. Instead, it combines raw evidence from existing logging systems such as GriefLogger with carefully selected supplemental event hooks, then builds an explainable temporal item-flow graph.

> Evidence first. Inference second. Confidence explicit. Every conclusion traceable.

## Project identity

- **Name:** ItemGraph
- **Mod ID:** `itemgraph`
- **Primary command:** `/itemgraph`
- **Alias:** `/ig`
- **Platform:** NeoForge
- **Target Minecraft version:** 1.21.1
- **Primary deployment model:** server-side
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

GriefLogger is treated as a read-only evidence source. ItemGraph maintains its own storage for supplemental observations and derived data.

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

## Example commands

Command syntax is provisional until implementation validates the query model.

```text
/ig trace item minecraft:iron_chestplate
/ig trace item minecraft:iron_chestplate name:"Old Reliable"
/ig trace player Vlad
/ig trace container
/ig event <id>
/ig explain <edge-id>
/ig status
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Evidence model](docs/EVIDENCE_MODEL.md)
- [GriefLogger integration](docs/GRIEFLOGGER_INTEGRATION.md)
- [Query model](docs/QUERY_MODEL.md)
- [Security and permissions](docs/SECURITY_AND_PERMISSIONS.md)
- [Test plan](docs/TEST_PLAN.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Branding](docs/BRANDING.md)

## Development rule

**Production must never be used as the development environment.**

All discovery, schema inspection, testing, database exploration, and early implementation must happen on staging.
