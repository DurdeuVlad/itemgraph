# Implementation Plan

## Phase 0 — Reconnaissance

Do not begin significant implementation before this phase is complete.

Collect:

- staging environment details
- repository layout
- Git state
- Java version
- NeoForge version
- GriefLogger version
- GriefLogger DB engine and schema
- faction/team mod
- relevant inventory/container mods
- armor stand behavior
- coffer behavior
- sample source events

Deliver a short report with:

1. Environment findings
2. GriefLogger coverage matrix
3. Logging gaps
4. Risks/unknowns
5. Recommended integration strategy

## Phase 1 — Project skeleton

Create:

- NeoForge 1.21.1 project
- `itemgraph` mod ID
- config system
- logging
- command root
- database abstraction
- schema migration framework
- basic `/ig status`

No complex inference yet.

## Phase 2 — Evidence ingestion

Implement:

- read-only GriefLogger adapter
- incremental checkpointing
- source deduplication
- canonical observation model
- minimal supplemental hooks only for verified gaps

Acceptance:

- selected source events appear in ItemGraph's own observation store
- GriefLogger remains unchanged

## Phase 3 — Item canonicalization

Implement:

- item registry ID extraction
- 1.21.1 data-component canonicalization
- deterministic metadata hash
- custom name extraction
- relevant damage/enchantment/trim fields

Document exactly what enters the fingerprint.

Acceptance:

- same semantic item produces same fingerprint
- changed relevant metadata produces expected new state

## Phase 4 — Graph nodes

Implement stable inventory-node identities for:

- players
- block containers
- item entities/ground where possible
- armor stands
- unknown holder

Do not over-generalize modded inventories before real use cases are tested.

## Phase 5 — Basic correlation

Support a narrow set first:

- container remove -> player gain
- player drop -> item entity
- item entity -> player pickup
- player remove -> container add

Use deterministic, explainable scoring.

Acceptance:

- controlled named item path reconstructed correctly

## Phase 6 — Query commands

Implement:

```text
/ig trace item ...
/ig event ...
/ig explain ...
/ig status
```

Add:

- pagination
- bounded time filters
- readable output
- permission checks

## Phase 7 — Quantity flow

Add stack-aware conservation.

Support:

- split
- merge
- partial transfers

Acceptance:

- ordinary iron/diamond stack movement can be reconstructed without item UUIDs

## Phase 8 — Missing high-value integrations

Based on staging findings, add only the most valuable gaps:

Potential examples:

- armor stands
- coffer inventory
- faction storage
- ender chest
- hopper automation

## Phase 9 — Transformations

Add selected transformation support:

- rename
- smithing
- repair
- crafting

Only after transfer logic is stable.

## Phase 10 — Hardening

Add:

- metrics
- bounded queues
- corruption recovery
- source schema compatibility checks
- migration tests
- performance tests
- command audit logging if desired

## Git strategy

Prefer small commits such as:

```text
chore: scaffold itemgraph neoforge project
feat: add observation persistence model
feat: add read-only grief logger adapter
feat: add item fingerprint canonicalization
feat: add basic transfer correlation
feat: add ig trace and event commands
feat: add explainable inferred edges
test: add stack split and merge scenarios
```

Avoid giant mixed commits.

## Definition of MVP complete

MVP is complete when:

- staging demonstrates named-item tracing
- ordinary quantity flow works
- evidence and inference are separately visible
- `/ig explain` is useful
- persistence survives restart
- GriefLogger remains untouched
- performance is acceptable on staging
