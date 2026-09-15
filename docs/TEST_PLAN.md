# Test Plan

## Objective

ItemGraph must be reliable enough for moderation disputes.

Testing should focus on correctness, explainability, quantity conservation, temporal ordering, persistence, and performance.

## Test environments

- unit tests
- integration tests where feasible
- staging server controlled scenarios

Never use production as the primary test environment.

## Core correctness tests

### Simple transfer

```text
Chest A -> Player
```

Expected:

- two observations or equivalent direct evidence
- one high-confidence inferred edge
- explainable evidence IDs

### Chest -> player -> chest

```text
Chest A -> Alice -> Chest B
```

Expected:

- monotonic timestamps
- correct intermediate node
- no fabricated movement

### Drop -> pickup

```text
Alice -> Ground -> Bob
```

Where item entity identity is available, correlation should become stronger.

### Stack split

```text
Alice receives 64 iron
Alice deposits 20 iron into Chest B
Alice retains 44
```

Expected:

- no need for per-ingot UUIDs
- conservation respected

### Stack merge

```text
Chest A -> Alice: 20 iron
Chest B -> Alice: 30 iron
Alice -> Chest C: 50 iron
```

Expected:

- valid quantity-flow reconstruction
- no forced individual lineage

### Named item

Use a distinct named armor piece.

Expected:

- metadata-aware matching
- higher confidence than type-only match

### Rename

```text
"Old Helmet" -> anvil -> "Old Reliable"
```

Expected:

- transformation preserved where event coverage allows

### Metadata change

Examples:

- damage changes
- trim applied
- enchantment applied

Expected:

- transformation or compatible state transition, not false unrelated identity

### Ambiguity

Two identical items move near the same time.

Expected:

- multiple plausible candidates or ambiguous state
- no fake certainty

### Impossible ordering

Destination observation precedes source.

Expected:

- edge rejected

### Missing event

One half of a transfer is absent.

Expected:

- unresolved observation
- no invented destination/source

### Duplicate ingestion

Same GriefLogger event imported twice.

Expected:

- exactly one canonical source observation

### Restart persistence

Create evidence, restart staging, query again.

Expected:

- data preserved
- checkpoints preserved
- no duplication

## Armor stand incident test

Controlled scenario:

1. Place named armor on an armor stand.
2. Remove one piece.
3. Move it through player inventory.
4. Store it in a chest.
5. Trace it.

Expected:

- armor-stand event captured if supported
- path reconstructed
- explanation available

## Coffer/modded inventory test

Reproduce the server's actual coffer inventory behavior.

Scenarios:

- move item into coffer
- remove item
- client display glitch if reproducible
- query server-side evidence

Goal:

Verify that ItemGraph reports authoritative inventory evidence independent of client rendering.

## Performance tests

Measure:

- event ingestion rate
- queue depth
- DB write latency
- CPU impact
- trace query duration
- worst-case bounded query duration
- memory usage

Test with realistic server event volumes.

## Invariants

### Quantity

Without creation/transformation:

```text
attributed_outgoing <= available_compatible_incoming
```

### Time

A path must be non-decreasing in time.

### Evidence

Every inferred edge references at least one source observation and should normally reference the observations it correlates.

### Source integrity

GriefLogger database remains unchanged by ItemGraph tests.

## Acceptance criteria for MVP

The MVP is accepted when staging can demonstrate:

### Named item path

```text
Chest A
 -> Player A
 -> Ground
 -> Player B
 -> Chest B
```

with:

- timestamps
- raw observations
- inferred edges
- confidence
- `/ig explain`

### Quantity flow

A stack split/merge scenario with no per-item UUIDs and correct conservation.
