# Query Model

## Goals

The ItemGraph query interface should let moderators answer:

- Where did this item type move?
- What happened to this named item?
- What item flow involved this player?
- What happened around this container?
- Why does ItemGraph think two observations are connected?

The interface must be useful without dumping thousands of events into chat.

## Command root

```text
/itemgraph
```

Alias:

```text
/ig
```

Syntax remains provisional until implementation.

## Core commands

### Trace an item

```text
/ig trace item minecraft:iron_chestplate
```

With metadata:

```text
/ig trace item minecraft:iron_chestplate name:"Old Reliable"
```

Potential filters:

```text
since:2d
after:"2026-09-14 18:00"
before:"2026-09-15 12:00"
player:Vlad
world:minecraft:overworld
```

### Trace a player

```text
/ig trace player Vlad
```

Expected output:

- bounded time range
- summarized item movements
- links/click actions to deeper traces

### Inspect a container

Potential interaction-based command:

```text
/ig inspect
```

Then click/right-click a container.

Or explicit coordinates:

```text
/ig trace container 123 64 -412
```

Exact UX should depend on NeoForge command capabilities and admin ergonomics.

### View an observation

```text
/ig event 8812
```

Shows only raw source evidence.

### Explain an inferred edge

```text
/ig explain 9931
```

Shows:

- source node
- destination node
- item
- quantity
- confidence label
- evidence IDs
- matching/scoring factors
- competing candidates if any

### Operational status

```text
/ig status
```

Suggested fields:

- ingestion status
- GriefLogger connection state
- pending queue size
- latest source checkpoint
- DB write latency
- observation count
- inferred-edge count
- last reconstruction duration
- errors

## Time filters

Support:

- `since`
- `after`
- `before`
- `between`

Avoid unbounded searches by default.

A trace without an explicit time filter should use a reasonable server-configured default window.

## Item matching modes

Potential modes:

### Type

```text
item:minecraft:diamond
```

### Name

```text
name:"Old Reliable"
```

### Metadata

```text
enchant:minecraft:mending
trim:sentry
```

### Exact fingerprint

```text
fingerprint:<hash>
```

## Result representation

Chat output should distinguish:

```text
[OBSERVED]
[INFERRED]
[AMBIGUOUS]
[UNRESOLVED]
```

Example:

```text
[OBSERVED] 14:31:08 Chest A removed 1x "Old Reliable"
[OBSERVED] 14:31:08 Alice gained 1x "Old Reliable"
[INFERRED] Chest A -> Alice [VERY HIGH]
```

## Pagination

Never dump an unbounded result set.

Use:

- page size
- next/previous actions
- compact summaries
- hover text for metadata
- click actions for `/ig event` and `/ig explain`

## Player-facing queries

A future non-admin mode must be separately designed.

It must not expose:

- unrelated player inventory contents
- hidden coordinates
- faction bases
- private containers
- another player's movement history

No player-facing query should inherit admin visibility accidentally.

## Export

A future moderation-case export may produce:

- plain text
- JSON
- Markdown report

Exports should include evidence IDs and clearly mark inference.
