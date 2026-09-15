# ItemGraph — Claude Development Charter

This file defines the operational and engineering rules for AI-assisted work on ItemGraph.

## Project

- Name: **ItemGraph**
- Mod ID: `itemgraph`
- Command: `/itemgraph`
- Alias: `/ig`
- Minecraft: **1.21.1**
- Loader: **NeoForge**
- Primary goal: reconstruct item movement through time from authoritative evidence.

## Core principle

ItemGraph is not a universal per-item UUID system.

It reconstructs **item type / metadata / quantity flow** through inventories using a temporal directed multigraph.

The system must always preserve the distinction between:

- **Observed evidence**
- **Inferred movement**
- **Ambiguous possibilities**
- **Unresolved events**

Never present inference as direct evidence.

---

# Operational safety

1. Work on **staging only** unless explicitly instructed otherwise.
2. Verify current host, working directory, server instance, branch, and environment before changes.
3. Never access, modify, restart, stop, upload to, migrate, or otherwise touch production.
4. Preserve existing uncommitted work.
5. Use a dedicated Git branch for ItemGraph work.
6. Do not delete worlds, player data, logs, databases, configuration, or mods.
7. Avoid destructive recursive shell commands.
8. Do not expose or commit credentials, tokens, passwords, private addresses, or secrets.
9. Staging restarts are allowed only after verifying the instance is staging.
10. Do not install untrusted binaries or arbitrary dependencies.

# GriefLogger rules

1. Treat the GriefLogger database as **read-only**.
2. Never run `UPDATE`, `DELETE`, `INSERT`, `ALTER`, destructive `PRAGMA`, migrations, repairs, or schema changes against it.
3. Do not silently patch GriefLogger.
4. Prefer stable APIs or database-level integration over coupling to undocumented implementation internals.
5. Do not poll or scan the GriefLogger database every tick.
6. ItemGraph owns its own storage for supplemental observations, indexes, and derived data.

# Engineering rules

1. Prefer documented NeoForge events and APIs.
2. Mixins are a last resort and require written justification.
3. Keep the mod server-side wherever practical.
4. Never perform expensive graph traversal or database scans on the server thread.
5. Never snapshot all player inventories every tick.
6. Use event-driven deltas.
7. Heavy historical queries should be asynchronous.
8. Do not access thread-unsafe Minecraft state from arbitrary async threads.
9. Use bounded queues/backpressure.
10. Derived data should be rebuildable from immutable raw evidence where feasible.
11. Use versioned schema migrations for ItemGraph's own database.
12. Use small, reviewable Git commits.

# Reconnaissance before implementation

Before writing significant code, inspect and document:

- Repository/server layout
- Git state
- Java version
- Minecraft version
- NeoForge version
- GriefLogger version
- GriefLogger database engine
- GriefLogger schema
- Relevant example rows
- Faction/team mod and API
- Modded inventory/container systems
- Armor stand behavior
- Hopper/automation behavior
- Backpacks/coffers/shulker-like storage
- Existing logging coverage

Do not ask the operator questions that can be answered safely by inspecting staging.

# Required initial report

Produce:

1. Environment findings
2. GriefLogger coverage matrix
3. Identified logging gaps
4. Proposed ItemGraph architecture
5. Proposed ItemGraph database model
6. Proposed event/fingerprint model
7. Proposed MVP
8. Risks and unknowns
9. Implementation plan

# Forensic integrity

Every inference must be explainable.

An admin must be able to ask:

> Why does ItemGraph think this transfer happened?

and receive the exact supporting observations and scoring factors.

Confidence must be deterministic and based on documented evidence such as:

- metadata equality
- quantity compatibility
- timestamp proximity
- spatial proximity
- same interaction/session
- item entity UUID
- slot transitions
- source reliability
- absence or presence of competing candidates

Do not invent "AI confidence."

# Quantity conservation

Unless creation/transformation/destruction evidence exists, the inference engine must not manufacture quantity.

If 10 matching items enter a node, ItemGraph must not attribute 20 outgoing items to that flow.

# Item identity

Do not assign a permanent UUID to every item.

Track:

- item registry ID
- quantity
- canonical metadata/components
- custom name
- enchantments
- damage
- trim
- lore
- relevant mod components
- deterministic fingerprint

Named items are distinctive evidence, not guaranteed identities.

# Temporal ordering

All inferred paths must be temporally possible.

A later observation cannot explain an earlier event.

# Privacy

The full graph is sensitive.

Player-facing features must not reveal:

- hidden player inventories
- secret base coordinates
- faction storage
- unrelated player identities
- unauthorized container locations

Default-deny sensitive information.

# Documentation discipline

Keep these files current:

- `README.md`
- `CLAUDE.md`
- `CHANGELOG.md`
- `docs/ARCHITECTURE.md`
- `docs/EVIDENCE_MODEL.md`
- `docs/GRIEFLOGGER_INTEGRATION.md`
- `docs/QUERY_MODEL.md`
- `docs/SECURITY_AND_PERMISSIONS.md`
- `docs/TEST_PLAN.md`
- `docs/IMPLEMENTATION_PLAN.md`
- `docs/BRANDING.md`

Important decisions must not exist only in chat history.

# MVP

The first vertical slice should demonstrate:

```text
Chest A
  -> Player A
  -> Ground
  -> Player B
  -> Chest B
```

using a named armor item, followed by an ordinary stack quantity-flow test.

The output must show:

- raw observations
- inferred path
- timestamps
- item metadata
- confidence
- evidence IDs
- explanation of each inferred edge

Evidence first.
Inference second.
Confidence explicit.
Every conclusion traceable.
