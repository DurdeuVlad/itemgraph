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
/ig gui item <query> [sinceMinutes]
/ig gui player <playerName> [sinceMinutes]
/ig gui container <dimension> <x> <y> <z> [sinceMinutes]
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
| `sinceMinutes` | long ≥ 1 | unbounded | window is `[now - sinceMinutes, now]`, inclusive; GUI keeps the resolved window constant across pages |
| `dimension` | resource location | — | required for `/ig gui container`; matches `ig_nodes.level_id` exactly |
| `x`, `y`, `z` | int | — | block coordinates for `/ig gui container` |

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
worker's writer connection. The shared worker accepts at most 64 waiting queries; excess
requests receive an explicit queue-full failure. Full rationale and prior art:
"Query execution: off-thread, reported back on-thread" in `docs/ARCHITECTURE.md`.

A command returns success as soon as the query is *accepted*; the answer arrives a tick or
two later.

### Vanilla flow browser

```text
/ig gui item <query> [sinceMinutes]
/ig gui player <playerName> [sinceMinutes]
/ig gui container <dimension> <x> <y> <z> [sinceMinutes]
```

These player-only, permission-level-2 commands open a vanilla `MenuType.GENERIC_9x6`
through `SimpleMenuProvider`; there is no custom `MenuType`, client screen, packet, or
ItemGraph item. The first 45 slots hold flow entries and the sixth row is reserved for
navigation. Item queries use the same fingerprint resolver as `/ig trace item`, player
queries use the same player-node lookup as `/ig trace player`, and container queries match
the explicit dimension and coordinates through the same container-node lookup as the trace
service. Ambiguous item, player-node, or container-node matches open a candidate-selection
view rather than choosing a target silently. Candidate lists are capped at 10 matches. A
resolved target with no rows shows an explicit empty state.

Each entry names its provenance in text: `[OBSERVED]` or `[INFERRED conf=X.XXXX]`. Selecting
an observation opens its raw event detail; selecting a transformation opens its stored
transformation detail; selecting an inferred edge opens `/ig explain`-equivalent detail with
the stored explanation and evidence IDs. Session intervals, UNKNOWN endpoints,
and source-group ambiguity are preserved. Navigation, selection, and close are the only
accepted menu actions: server-side handling rejects pickup, placement, shift-click, drag,
throw, swap, clone, and pickup-all paths. Permission level 2 is rechecked for menu validity
and each action.

Pages use keyset pagination, not offsets. The stable total-order cursor is
`(timestamp_ms, provenance kind, row id, source kind)`; each database source fetches at most
46 rows for a 45-entry page, merges them by the same ordering, and keeps the `QueryWindow`
resolved when the GUI was opened. This prevents ties at page boundaries from skipping or
repeating observations, edges, or transformations. Next pages query after the last entry;
previous pages query before the first entry in reverse order and reverse the bounded result for
display, so navigation does not retain an unbounded list of prior cursors or use offsets. The
SQL and formatting use the existing `QueryDispatcher` read-only worker; menu creation and state changes happen on the server
thread. The vanilla menu path follows the NeoForge 1.21.1 [`ChestMenu`](https://lexxie.dev/neoforge/1.21.1/net/minecraft/world/inventory/ChestMenu.html), [`SimpleMenuProvider`](https://lexxie.dev/neoforge/1.21.1/net/minecraft/world/SimpleMenuProvider.html), and [`AbstractContainerMenu`](https://lexxie.dev/neoforge/1.21.1/net/minecraft/world/inventory/AbstractContainerMenu.html) APIs. The cursor follows keyset-pagination guidance to include all tie-breakers from the stable order in the cursor predicate ([GitLab keyset pagination](https://docs.gitlab.com/development/database/keyset_pagination/)). Each page uses a new read-only connection rather than a long-lived SQLite snapshot; ingestion or correlation can change later pages while the GUI is open. Keyset correctness is guaranteed across page boundaries for an unchanged result set, not as a multi-page snapshot. Reopen the GUI to refresh the timeline.

### Not-found handling

A missing id is reported as a command **failure** with a plain message
(`No observation #42 exists in ig_observations.`), never an empty success and never a
stack trace. For `/ig trace item` this matters most: an unknown fingerprint id and a real
item with no recorded movement are different findings, and conflating them would let a
typo read as "nothing ever happened to it".

## Query design notes

The entries below mix current behavior with follow-up design intent. Explicit status labels
are authoritative; the implemented surface above documents the current command contract.

### Trace an item

**Implemented.** `/ig trace item <query> [limit] [sinceMinutes]` and `/ig gui item <query>
[sinceMinutes]` resolve numeric fingerprint IDs, item registry IDs, and custom names through
`TraceQueryService.resolveFingerprints`. A string matching multiple fingerprints is not
chosen arbitrarily: chat trace output lists candidates, while the GUI opens a candidate
selection menu. String matching is exact-or-substring and returns at most 10 candidates.

Intended metadata filters and broader matching remain future work:

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

**Implemented.** `/ig trace player <playerName> [limit] [sinceMinutes]` lists observed and
inferred movements for an exact stored player-label match; it does not substitute a
substring match such as `Alex2` for `Alex`. If several stored player nodes share that exact
label, chat lists the candidates and the GUI lets the moderator choose. `/ig gui player
<playerName> [sinceMinutes]` uses that same resolver in the read-only browser.

### Inspect a container

**Explicit coordinate traces are implemented.** Use `/ig trace container <x> <y> <z>` for
chat output or `/ig gui container <dimension> <x> <y> <z> [sinceMinutes]` for the GUI. The
GUI lookup requires an exact dimension and normalized block coordinates; multiple matching
nodes are listed for selection rather than silently resolved. The chat form also lists
multiple coordinate matches, including matches across dimensions. The command-toggled
in-world `/ig inspect` interaction is still future work tracked by issue #10.

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

- `since` — implemented as positional `sinceMinutes` on `/ig trace item`, `/ig trace player`, `/ig trace container`, and all `/ig gui` commands
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

Implemented item lookup accepts:

- the quoted `"id:<numericId>"` form for an unambiguous exact `ig_item_fingerprints.id` lookup;
- a bare numeric query, which includes an ID candidate and any text matches;
- an exact or substring match against the item registry ID, such as `minecraft:diamond` or `netherite`;
- an exact or substring match against a custom item name, such as `"Old Reliable"`.

A numeric query includes its matching database-ID candidate plus distinct registry-ID or
custom-name matches, capped at 10 candidates total; multiple matches are never silently
resolved to the ID. A non-numeric query also returns up to 10 candidates.
`/ig trace item` prints candidates; `/ig gui item` presents them as selectable entries. An ID
from `/ig event` output appears as the numeric ID candidate; choose it from the list if text
matches collide. Lookup does not treat
`fingerprint_hash` as an input and does not claim that equal fingerprints identify the same
physical item.

Not implemented: metadata predicates such as enchantment or trim filters, and direct
`fingerprint:<hash>` syntax. For example, these remain design ideas, not accepted command
syntax:

```text
enchant:minecraft:mending
trim:sentry
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

`[AMBIGUOUS]` and `[UNRESOLVED]` do not yet have separate line prefixes. Correlation
ambiguity is explained with competing-candidate counts; ambiguous cross-source groups are
labelled in observation details and cannot contribute independent quantity. An unresolved
endpoint is rendered as `UNKNOWN`; the output does not invent a destination.

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
- GUI paging: `/ig gui` returns at most 45 entries per page using the composite keyset cursor
  described above; equal timestamps are ordered by provenance, row ID, and source table

Not yet implemented:

- next/previous actions for chat `/ig trace` output; chat traces remain limit-capped rather
  than page-navigable
- full canonical component hover summaries beyond the item identity and evidence details
  shown by the GUI
- clickable `ClickEvent` links in chat for `/ig event` and `/ig explain`; chat cross-references
  remain plain text, while GUI entry selection opens the detail view

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
