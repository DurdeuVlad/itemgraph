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

## Renames

A custom name is evidence, not identity.

If renaming is observable, represent it as a transformation:

```text
"Old Helmet"
    |
    | anvil rename
    v
"Old Reliable"
```

This preserves continuity without pretending the name itself is permanent.

## Transformations

Future ItemGraph versions should model:

- crafting
- smithing
- repair
- enchanting
- trimming
- renaming
- consumption
- breakage

A transformation edge may consume one or more fingerprints and produce another.
