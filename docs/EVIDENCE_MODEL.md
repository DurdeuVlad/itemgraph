# Evidence Model

## Goal

ItemGraph must make a strict distinction between what the server **knows** and what the system **infers**.

This is necessary for trustworthy moderation.

## Evidence classes

### 1. Observed

An event directly reported by a source.

Examples:

- `REMOVE_ITEM` from a chest
- `ADD_ITEM` to a chest
- player pickup
- player drop
- armor stand equipment removal
- anvil rename

Observed evidence should include the source and original source identifier whenever available.

### 2. Inferred

A relationship constructed from two or more observations.

Example:

```text
Chest A -1 "Old Reliable"
Alice   +1 "Old Reliable"
```

may support:

```text
Chest A -> Alice
```

### 3. Ambiguous

Multiple candidate explanations are viable.

Example:

```text
Alice -1 diamond

Bob   +1 diamond
Chris +1 diamond
```

If both are temporally/spatially plausible, the system must preserve ambiguity.

### 4. Unresolved

Evidence exists, but no defensible matching counterpart is available.

Example:

```text
Chest A -5 emeralds
```

with no plausible destination event. In Phase 7, this is formally tracked as `CLOSED_UNRESOLVED` once the candidate correlation window expires.

## Cross-source corroboration (V11)

`source_type + source_event_id` is producer-local identity; a GriefLogger row ID is not
an ItemGraph event ID. ItemGraph preserves both raw observations and stores their derived
relationship in `ig_observation_groups` / `ig_observation_group_members`.

A pair is `CONFIRMED` only when a unique cross-source counterpart shares a non-null
`item_entity_uuid`, action family, fingerprint, amount, actor, and a timestamp within 250 ms.
The canonical row is the only row with quantity capacity; corroborating rows are added to
`ig_edge_evidence` but never to allocations. If identity is missing, non-unique, or otherwise
uncertain, the group is `AMBIGUOUS`, its members receive `SOURCE_AMBIGUOUS`, and none of its
rows may support an inferred edge. Raw observations and source IDs are never deleted.

If a legacy active edge allocated through a corroborating or ambiguous row, ItemGraph keeps
the edge and allocations but changes `edge_state` to `SUPERSEDED_SOURCE_DUPLICATE` or
`SUPERSEDED_SOURCE_AMBIGUITY`. Superseded edges are excluded from active traces and capacity
audit totals; `/ig explain <edgeId>` labels them as superseded.

## Interval and unresolved observations

`timestamp_end_ms` bounds interval observations. A container observation with
`captureType=container_session_net_delta` represents the net fingerprint-level change between
`PlayerContainerEvent.Open` and `Close`; it is not click-time evidence. A withdraw-and-return
that leaves zero net change emits no row, and that absence is not evidence that no transfer
occurred. Multi-viewer attribution remains one ambiguous row, not one row per viewer.

An `/ig inspect` click is an access request, not evidence. `InspectionListener` cancels the
supported-container interaction before `ContainerSessionListener` records a pending opening,
and the read-only `FlowBrowserMenu` is backed by `SimpleContainer`; therefore opening it
creates no block-entity watch, no net-delta row, and no held-item consumption observation.

`CAPABILITY_INSERT` and `CAPABILITY_EXTRACT` mean an item changed through an `IItemHandler`.
`IItemHandler` does not expose the caller or cause, so the remote endpoint is UNKNOWN and the
action is not labeled hopper/automation without separate evidence. Queue-rejected capability
rows are excluded from player session attribution and retried once at session close as
interval-bounded UNKNOWN-caller evidence. If the queue remains full, the retry is counted as
dropped and is not attributed to a player; `/ig status` reports `dropped` and
`capabilityQueueRejections` counters.

A canceled `ItemTossEvent` is recorded as `DROP_CANCELLED` to UNKNOWN because it did not
produce a world item entity. A canceled `LivingDropsEvent` is recorded as
`DEATH_DROP_CANCELLED` with no destination. Neither is a ground transfer or correlation
capacity. `DROP_ITEM`/`DEATH_DROP` ground evidence is written only after the corresponding
`ItemEntity` is confirmed with `isAddedToLevel()`.

### 5. Quantity-Flow Evidence and Ledgers (Phase 7)

ItemGraph reconstructs stack flows without permanent item UUIDs by tracking exact quantity capacities and allocations:

- **Stack Split (1-to-many)**:
  ```text
  Observation 101: Alice drops 64x diamond at Ground
  Observation 102: Bob picks up 20x diamond at Ground
  Observation 103: Chris picks up 44x diamond at Ground

  -> Inferred Edge 1: Alice -> Bob   (20x diamonds, alloc 20/64)
  -> Inferred Edge 2: Alice -> Chris (44x diamonds, alloc 44/64)
  ```
  Both pickups are `FULLY_ALLOCATED`. Drop is `FULLY_ALLOCATED`. Total 64 conserved.

- **Stack Merge (many-to-1)**:
  ```text
  Observation 201: Alice drops 20x iron at Ground
  Observation 202: Bob drops 30x iron at Ground
  Observation 203: Chris picks up 50x iron at Ground

  -> Inferred Edge 1: Alice -> Chris (20x iron)
  -> Inferred Edge 2: Bob   -> Chris (30x iron)
  ```
  Chris picked up 50 units across two distinct drops. Total 50 conserved.

- **Partial Transfer & Residual Capacity**:
  ```text
  Observation 301: Alice drops 64x gold at Ground
  Observation 302: Bob picks up 20x gold at Ground

  -> Inferred Edge 1: Alice -> Bob (20x gold)
  ```
  - While correlation window remains open: Alice's drop is `PARTIALLY_ALLOCATED` with residual capacity of 44 units available for future pickups.
  - When correlation window expires: Alice's drop transitions to `CLOSED_UNRESOLVED` for the 44 unrecovered units. Quantity is never manufactured.

## Observation fields

A canonical observation should support:

- `observation_id`
- `source_type`
- `source_event_id`
- `timestamp`
- `event_type`
- `actor_uuid`
- `player_uuid`
- `world_key`
- `x`
- `y`
- `z`
- `inventory_node_id`
- `inventory_slot` if available
- `item_registry_id`
- `quantity_delta`
- `custom_name`
- `components_canonical`
- `components_hash`
- `damage`
- `enchantments`
- `trim`
- `lore`
- `item_entity_uuid` if available
- `raw_source_payload_ref` where practical

Not every source can supply every field.

Missing fields must remain missing rather than guessed.

## Item fingerprint

A fingerprint represents the comparable properties of an item or stack.

It is not a permanent item ID.

### Levels of specificity

#### Type-level

```text
minecraft:iron_ingot
```

Useful for ordinary stack flow.

#### Metadata-aware

```text
minecraft:iron_chestplate
name="Old Reliable"
trim=sentry/gold
damage=82
enchantments=...
```

Useful for distinctive items.

#### Exact canonical fingerprint

A deterministic hash over selected canonical components.

The canonicalization rules must be versioned.

## Quantity model

Quantity is part of evidence.

If:

```text
Chest A -> Alice: 20 iron
Chest B -> Alice: 30 iron
Alice -> Chest C: 50 iron
```

ItemGraph does not need to decide which exact 20 atoms came from Chest A.

It only needs to respect compatible quantity flow.

## Conservation rule

In ordinary transfer flow:

```text
attributed_outgoing <= compatible_incoming + creation_events
```

A trace must never fabricate item quantity.

Destruction and transformation events must explicitly account for quantity leaving one item state and entering another.

## Temporal validity

A candidate transition is invalid if its destination observation occurs before its source observation.

A valid graph path must be time-monotonic.

## Candidate scoring

Initial scoring should be deterministic and documented.

Possible factors:

- exact item registry match
- exact canonical metadata match
- custom-name match
- quantity compatibility
- timestamp distance
- spatial distance
- same actor
- same GUI interaction/session
- same item entity UUID
- same slot or transfer context
- no competing compatible event
- source trust level

The first implementation should prefer simple, explainable scoring over complex opaque heuristics.

## Explainability

Every inferred edge should retain enough information to produce an explanation such as:

```text
Edge 9931: Chest A -> Alice
Confidence: VERY HIGH

Evidence:
- Observation 8812: Chest A removed 1x minecraft:iron_chestplate
- Observation 8813: Alice gained 1x minecraft:iron_chestplate

Supporting factors:
- Exact metadata fingerprint match
- Timestamp delta: 35 ms
- Same block interaction context
- Same coordinates
- No competing candidate within 2 seconds
```

## Renames and Transformations (Phase 9)

A custom name is distinctive evidence, not permanent identity.

Transformations are tracked in `ig_item_transformations` (schema V8):

```sql
CREATE TABLE IF NOT EXISTS ig_item_transformations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transformation_type TEXT NOT NULL,
    player_node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
    source_fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
    result_fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
    quantity INTEGER NOT NULL,
    timestamp_ms INTEGER NOT NULL,
    details TEXT
);
```

Implemented transformation types:

- `ANVIL_RENAME`: captured via NeoForge `AnvilRepairEvent` when an item receives a custom name or repair.
- `CRAFTING`: captured via `ItemCraftedEvent` linking input item components to crafted output products.
- `SMELTING`: captured via `ItemSmeltedEvent`.

In query output, transformations are represented chronologically as:

```text
[OBSERVED] PlayerB -> PlayerB : 1x at 2026-09-17 06:55:47 UTC (transformation#1 [TRANSFORMATION ANVIL_RENAME <- minecraft:netherite_boots] (Renamed on Anvil))
```

## Entity Continuity Tracking (Phase 8)

Authoritative Minecraft `ItemEntity` UUIDs are tracked only after an item entity is confirmed in the level, then reused on pickup when identity remains unique.

- Stored in `ig_observations.item_entity_uuid`.
- Tracked via `ItemEntityTracker` and `ItemEntityEventListener`.
- `ItemEntityTracker.findMatchingDropEntity` returns an exact UUID only when exactly one spatial/time candidate fits; ambiguity returns null.
- When matching a drop observation to a pickup observation, an identical unique `ItemEntity` UUID assigns a confidence score of `0.9990` and produces an explanation citing direct entity continuity on the ground.

