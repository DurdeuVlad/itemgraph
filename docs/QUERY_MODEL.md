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

## Implemented surface (Phases 6–10)

Everything below this heading and above "Not yet implemented" is live.

```text
/ig status
/ig audit
/ig ingest now
/ig event   <observationId>
/ig explain <edgeId>
/ig trace item <query> [limit] [sinceMinutes]
/ig trace player <playerName> [limit] [sinceMinutes]
/ig trace container <x> <y> <z> [limit] [sinceMinutes]
```

`/itemgraph` is the full root; `/ig` is a redirect to the same node, so every form works
under either name. The whole tree requires permission level 2 — the query subcommands
inherit the same gate as the operational ones rather than relaxing it, because a trace
names players, containers and coordinates (`docs/SECURITY_AND_PERMISSIONS.md`).

### Arguments

| Argument | Type | Default | Notes |
| --- | --- | --- | --- |
| `observationId` | long ≥ 1 | — | `ig_observations.id` |
| `edgeId` | long ≥ 1 | — | `ig_inferred_edges.id` |
| `fingerprintId` | long ≥ 1 | — | `ig_item_fingerprints.id` |
| `limit` | int ≥ 1 | 20 | hops returned; **capped at 100** |
| `sinceMinutes` | long ≥ 1 | unbounded | window is `[now - sinceMinutes, now]`, inclusive |

`limit` has no upper bound in the command grammar on purpose. An over-large request is
**capped, not rejected**: an admin chasing an incident gets the first page of real output
plus an explicit "capped from N" and "TRUNCATED at N" marker, rather than a usage message
and no data. The cap lives in `QueryLimits.clampLimit`, applied before the value reaches
SQL.

`sinceMinutes` is relative rather than an absolute epoch value because a human cannot
sanity-check a 13-digit number they typed by hand — an off-by-1000 typo would silently
return an empty result that reads like a real negative finding. Relative minutes fail
visibly. It is resolved against wall-clock "now" on the server thread, so the window shown
in the output is the moment the command was run.

Omitting `sinceMinutes` gives an all-time trace. That is still a bounded query: for a
single fingerprint the row count is already hard-capped by the limit. The time filter
narrows a noisy fingerprint; it is not what makes the query safe.

### Execution model

`/ig event`, `/ig explain`, `/ig trace`, `/ig audit`, and the database-backed portion of
`/ig status` run SQL and formatting off the server thread on a dedicated `ItemGraph-Query-Worker`, then marshal the finished lines back onto the server thread with
`source.getServer().execute(Runnable)` before calling `sendSuccess`/`sendFailure`. Each
query reads through its own short-lived read-only connection rather than the ingestion
worker's writer connection. Full rationale and the prior art this was checked against:
"Query execution: off-thread, reported back on-thread" in `docs/ARCHITECTURE.md`.

A command returns success as soon as the query is *accepted*; the answer arrives a tick or
two later.

### Not-found handling

A missing id is reported as a command **failure** with a plain message
(`No observation #42 exists in ig_observations.`), never an empty success and never a
stack trace. For `/ig trace item` this matters most: an unknown fingerprint id and a real
item with no recorded movement are different findings, and conflating them would let a
typo read as "nothing ever happened to it".

## Not yet implemented — design intent for later phases

Everything from here on is design intent. Where a sketch below disagrees with the
"Implemented surface (Phase 6)" section above, the implemented section is authoritative.

### Trace an item

**Partly implemented.** Phase 6 traces by `ig_item_fingerprints.id`
(`/ig trace item <fingerprintId> [limit] [sinceMinutes]`). Resolving a registry id or a
custom name to a fingerprint from the command line is not implemented, so an admin
currently reaches a fingerprint id via `/ig event <id>` output, which prints
`[fingerprint#N hash=...]` on its item line.

Intended eventual forms:

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

**Implemented.**

```text
/ig event 8812
```

Shows one raw source row: source and source event id, action and amount, item fingerprint,
endpoints, correlation state, and any derived source-group metadata. A confirmed source group
identifies the canonical capacity row and corroborating aliases; an ambiguous group states
that no independent quantity capacity was allocated. Interval rows show `timestamp_ms` to
`timestamp_end_ms` and their capture scope. Labelled `[OBSERVED]`; nothing on this view is
scored or reconstructed.

### Explain an inferred edge

**Implemented.**

```text
/ig explain 9931
```

Shows:

- source node
- destination node
- item
- quantity
- confidence label (`[INFERRED conf=0.9025]`, and again as a `confidence:` field)
- the stored scoring narrative, read from the row, never recomputed
- every observation cited through `ig_edge_evidence`, each labelled `[OBSERVED]`, each
  rendered in the same shape `/ig event` uses and cross-referenced as `/ig event <id>`

Competing candidates are named inside the stored explanation string written by the
correlation engine (for example `2 admissible pickups`). Confirmed cross-source group
members are listed as evidence so both source row IDs remain auditable. An edge superseded
by source-equivalence repair is labelled `[SUPERSEDED INFERENCE]`, excluded from current
traces and capacity, and remains addressable by `/ig explain <edgeId>`.

### Operational status

**Implemented.**

```text
/ig status
```

Reports the mod version, optional GriefLogger reachability, database connection/schema/path,
correlation window and last-pass counts, ingestion running state, total observation count,
both source checkpoints, last-cycle counts, active/superseded edge counts, internal queue
size/enqueued/persisted/dropped counts, capability queue-rejection count, transformations,
and ItemEntity tracking counters. Database counts/checkpoints run through `QueryDispatcher` off the server
thread. `/ig ingest now` queues one bounded manual ingest-and-correlate cycle on the existing
background worker and returns immediately; `/ig status` reports its result when available.

## Time filters

Support:

- `since` — implemented as the positional `sinceMinutes` argument on `/ig trace item`
- `after` — not implemented
- `before` — not implemented
- `between` — not implemented

Avoid unbounded searches by default.

A trace without an explicit time filter is currently unbounded *in time* but still bounded
*in rows* by the hard limit cap, which is what keeps it a safe query. A server-configured
default window is not implemented; `QueryWindow.unbounded()` is used instead. Point
observations are selected by their timestamp; session observations are selected when their
`timestamp_ms`–`timestamp_end_ms` interval overlaps the requested window. Inferred edges also
use overlap (`time_end >= since AND time_start <= until`), because an edge whose drop predates
the window but whose pickup falls inside it really did happen during the window, and excluding
it would leave a visible gap in the reconstructed path. Session rows display their interval
and state that order inside it is unknown.

## Item matching modes

None of the modes below are implemented. Phase 6 matches on exactly one thing: the
`ig_item_fingerprints.id` passed to `/ig trace item`. That is the `fingerprint:` mode in
all but syntax — the id is the primary key of the row whose `fingerprint_hash` is the
deterministic canonical-metadata hash — reached by reading it off `/ig event` output.

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
[OBSERVED]      implemented
[INFERRED]      implemented
[AMBIGUOUS]     not implemented
[UNRESOLVED]    not implemented
```

`[AMBIGUOUS]` and `[UNRESOLVED]` have no marker of their own yet. Ambiguity is currently
expressed as a *reduced confidence* on an `[INFERRED]` line plus the candidate counts in
the stored explanation; an unresolved endpoint is currently an `UNKNOWN` node, which
renders as a normal node on an `[OBSERVED]` line. Both deserve their own label later.

Implemented line shape for a `/ig trace item` hop:

```text
<label> <origin> -> <destination> : <amount>x at <time> (<ref>)
```

Real examples:

```text
[OBSERVED] CONTAINER 10,64,10 -> AlphaA : 1x at 2026-09-16 14:31:08 UTC (event#8812 REMOVE_ITEM)
[OBSERVED] AlphaA -> GROUND 20,64,20 : 1x at 2026-09-16 14:31:18 UTC (event#8813 DROP_ITEM)
[INFERRED conf=0.9025] AlphaA -> BetaB : 1x at 2026-09-16 14:31:18 UTC (edge#9931 inferred transfer spanning 1m0s)
[OBSERVED] GROUND 20,64,20 -> BetaB : 1x at 2026-09-16 14:32:18 UTC (event#8814 PICKUP_ITEM)
```

Provenance comes first on every line, so it is read before the claim it qualifies. The
full set of rules is "Query output: the labelling convention" in `docs/ARCHITECTURE.md`.

Note that the inferred bridge and the two observations underneath it all appear. That is
deliberate: hiding the observations would hide the evidence, and hiding the bridge would
hide the claim.

## Pagination

Never dump an unbounded result set.

Implemented:

- page size (`limit`, default 20, hard cap 100 in `QueryLimits`)
- an explicit `TRUNCATED at N hops - more movement matched.` marker, produced by fetching
  `LIMIT applied + 1` on each side so "there is more" is a fact rather than a guess
- an explicit `(capped from N)` marker when the request exceeded the ceiling
- a separate 50-row cap on the `/ig explain` evidence listing, with its own truncation line
- textual cross-references: every hop names `event#<id>` or `edge#<id>`, and every
  `/ig explain` evidence entry prints `/ig event <id>`

Not yet implemented:

- next/previous actions (there is no offset/cursor argument; narrow the window instead)
- hover text for metadata
- clickable `ClickEvent` links for `/ig event` and `/ig explain` — the cross-references are
  currently plain text an admin retypes

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
