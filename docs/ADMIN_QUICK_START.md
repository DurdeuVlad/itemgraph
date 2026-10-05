# ItemGraph admin quick start

This guide is for a server admin investigating an item incident for the first time. It
uses the current ItemGraph command tree and behavior for Minecraft 1.21.1. ItemGraph is a
server-side NeoForge or Fabric mod; players do not need a client mod for commands or the
vanilla flow browser. Read section 0 before running commands; permission nodes and denial
behavior are listed in [Security and permissions](SECURITY_AND_PERMISSIONS.md).

## 0. Choose the server file and confirm access

ItemGraph targets Minecraft 1.21.1. Choose the file for the server's loader; NeoForge is
the primary loader and Fabric is also supported. Install ItemGraph on the server only;
players do not need the mod on their clients. Use the standard loader file for standalone
ItemGraph. A GriefLogger-compatible file is only for the exact GriefLogger release named
by that file and is not required for standalone capture. Do not install both ItemGraph
variants together. Download the approved release file, place it in the server's `mods/`
folder, and restart. Current file names and compatibility targets are listed in the
[README](../README.md).

Before running a command, make sure your permission provider has not explicitly denied
its required node. An unset node falls back to vanilla permission level 2; exact named
nodes do not inherit from dotted parents, and explicit `false` denies even an operator.
The `/ig` root and `/ig help` require `itemgraph.command`. If you cannot run help, ask a
server owner to check the provider directly or grant the exact node; the command cannot
explain a denial that blocks the help command itself. `/ig audit` additionally requires
`itemgraph.audit`. The complete command-to-node table is in
[Security and permissions](SECURITY_AND_PERMISSIONS.md).

## 1. Confirm that ItemGraph is ready

Run:

```text
/ig status
/ig audit
```

`/ig status` reports the configured database, schema, ingest queue, and recent worker
activity. `RUNNING` means ItemGraph started; it does not mean the database is healthy or
that the mod has captured an event. `/ig audit` runs a read-only database integrity and
quantity-conservation check. Fix a reported database/queue problem before treating an
empty search as evidence that nothing happened.

If no events have been captured yet, use a disposable test world or staging server to
perform one ordinary action and confirm a matching observation appears. Do not test by
changing production inventories. An empty result is not a capture test until you confirm
that capture was active and the event type is supported.
For server setup and the exact database keys, see [Configuration](CONFIGURATION.md).

## 2. Pick the investigation that matches your question

| Admin question | Start here | What it returns |
| --- | --- | --- |
| What happened to this item or item type? | `/ig trace item <item-or-name> [limit] [sinceMinutes]` | A time-ordered item-flow trace. Ambiguous fingerprints are shown as candidates. |
| What item movement involved this player? | `/ig trace player <playerName> [limit] [sinceMinutes]` | Item movements through player inventory endpoints. Duplicate player nodes are shown as candidates. |
| What item flow touched this container? | `/ig trace container <x> <y> <z> [limit] [sinceMinutes]` | A trace for container nodes at those coordinates. Use `/ig gui container` when dimension disambiguation or visual paging is useful. |
| What blocks, commands, or interactions were recorded nearby? | `/ig lookup near <dimension> <x> <y> <z> <radius> <eventType> [limit] [sinceMinutes]` | Native observed audit events in a bounded cube. |
| What evidence supports one raw observation? | `/ig event <observationId>` | One stored observation and its metadata. |
| Why does ItemGraph connect these events? | `/ig explain <edgeId>` | One inferred edge, deterministic confidence, explanation, and supporting evidence IDs. |
| Which imported legacy row is this? | `/ig lookup provenance <sourceSha256> <table> <sourceKey> [limit]` | Exact imported GriefLogger provenance; provenance-only rows do not add item quantity. |
| How do I browse without a custom client? | `/ig gui item`, `/ig gui player`, or `/ig gui container` | A read-only vanilla six-row menu with candidate selection, evidence detail, and paging. |
| What was recorded at the block I am looking at? | `/ig inspect on`, then left-click that block | An exact-position, paginated audit-history query in chat. |
| What was recorded at this button or another functional block that is not a container? | `/ig inspect on`, then right-click the block | A paginated audit-history query for the clicked block. |
| What was recorded at the block behind the face I am pointing at? | `/ig inspect on`, then right-click an ordinary non-container block | A paginated audit-history query for the adjacent block on the clicked face. |
| What flow touched a container I am looking at? | `/ig inspect on`, then right-click a block entity implementing `Container` (for example, a chest or furnace) | The read-only flow browser. Either half of a valid double chest opens the same canonical anchor used by container capture. |
| Is storage internally consistent? | `/ig audit` | Conservation, positivity, relational-integrity, and allocation-state results. |
| How do I run a filtered lookup from console? | `/ig lookup near <dimension> <x> <y> <z> <radius> <eventType> ...` | Coordinate-based nearby audit query; `/ig lookup <filters...>` is player-only because its radius uses the issuing player's position. |

`/ig` and `/itemgraph` are the same command root. `/ig help` gives task-first starting
points; `/ig help commands` groups every command path by investigation task. `/ig help
lookup near`, `/ig help trace item`, and other topic forms give syntax and an example.
`/ig help goto` explains that `[Go to ...]` is an in-game click action; do not type its token.
`/ig page <page>` continues the issuing player's saved lookup session. By
contrast, `/ig lookup page <page> <eventType> [limit] [sinceMinutes]` runs a direct page
number query for that event type.

### Grant a moderator lookup access only

Run `/ig help permissions` for the in-game summary. To let a level-1 moderator run audit
lookups, assign both exact nodes `itemgraph.command` and
`itemgraph.command.lookup` in the server's permission provider. Add
`itemgraph.command.page` if that moderator should also continue results with `/ig page`
or clickable page controls. Nodes do not inherit through dotted names. An explicit
provider denial overrides operator level 2; an unset node falls back to level 2.
NeoForge uses its built-in PermissionAPI handler. Fabric bundles
`fabric-permissions-api` 0.3.1, but a permission provider mod is needed to configure
per-player grants.

## 3. Follow one concrete incident

Replace the sample coordinates and IDs with the values from your server:

These executable examples are parsed against the registered `/ig` command tree by
`ItemGraphCommandsHelpTest.documentedQuickStartExamplesParseAgainstRegisteredCommandTree`.

<!-- executable-command-examples:start -->
```text
/ig lookup near minecraft:overworld 120 64 -30 32 BREAK_BLOCK 50 1440
/ig trace item "minecraft:diamond" 20 1440
/ig event 633
/ig explain 8
```

The first command looks for recorded block-break events within 32 blocks and the last 24
hours. The trace follows the matching item evidence, capped at 20 rows. Use an observation
ID from output with `/ig event`; use an inferred edge ID with `/ig explain`. Do not treat
matching item type alone as proof that two stacks are the same physical object.

To browse a particular container without guessing its dimension, use:

```text
/ig gui container minecraft:overworld 120 64 -30
```
<!-- executable-command-examples:end -->

The flow browser is a vanilla menu. It has no ItemGraph-specific item, screen, packet, or
client installation requirement. Each page has at most nine rows. A numbered chat companion
shows each row's observed/inferred class, safe item identity, event kind, and UTC time when
available. Match its number to the menu slot in reading order: left-to-right, then top-to-bottom.
Left-click that slot to open its details, then use the labeled Back control to return. On an
ambiguous candidate page, select
a candidate slot to open that candidate's timeline. Missing identity and unresolved history
have explicit labels. Hover text and icons provide supplemental detail. Its entries are
read-only; attempts to move inventory items through the menu are rejected. For a supported
container,
`/ig inspect on` and a
right-click open the same kind of container flow browser. For a valid double chest, either
half opens the canonical anchor used when its contents are recorded. Run `/ig inspect off`
when finished; mode also clears on logout and server stop.

After you select a timeline row, the detail menu shows one paper icon per detail line.
Hover an icon to read its full text; use the labeled next/previous controls when the
details span more than one page, and Back to return to the timeline.

For `/ig event`, `/ig explain`, `/ig trace`, and paged history results, hover a chat row for
evidence class, safe item identity, canonical fingerprint hash when available, event kind,
UTC time, and recorded endpoints. Click `[Go to ...]` only when you want to move your own
player to a recorded spatial endpoint; the link expires after two minutes, works once, and
requires the same query permissions when clicked. Console results remain plain text.

## 4. Read the result correctly

- **OBSERVED** means a specific event or inventory delta was recorded.
- **INFERRED** means ItemGraph linked observations under its deterministic temporal,
  quantity, metadata, endpoint, and candidate rules. It is an explanation, not a direct
  observation.
- **AMBIGUOUS** means multiple targets or candidate flows fit; inspect the candidates
  rather than choosing one silently.
- **UNRESOLVED** means the event is known but ItemGraph cannot safely claim its cause,
  destination, or relationship.
- **UNKNOWN** endpoints are deliberately unknown. For example, a container break can
  prove which stacks were present without proving which player or ground entity received
  each stack.

Movement is time ordered. Later evidence cannot explain an earlier event. Unless there is
evidence for creation, transformation, or destruction, an inference must conserve item
quantity. Names, enchantments, components, and fingerprints help distinguish item types
and metadata; they are not permanent per-item UUIDs. Every inferred edge should link back
to its evidence IDs and explanation.

A confidence value is a deterministic score from documented evidence factors, not a
calibrated probability that the history is true. Use `/ig explain <edgeId>` to see the
factors, candidate counts, and supporting observations behind a link.

### Coverage limits to check before trusting an empty result

An empty query means no matching row exists in the evidence ItemGraph has stored for that
query. It does not establish that nothing happened. Check the capture start time, event
type, permissions, dimension, and target first. Current known limits include explosion
and other environmental causes, Enderman causes, moving blocks, zero-net container
sessions where items are taken and returned before close, and arbitrary modded backpacks
or inventories without an adapter. Container GUI evidence is a net change over the
open/close session, not a record of each click. The feature status and evidence boundary
are listed in [Feature parity inventory](FEATURE_PARITY_INVENTORY.md).

### Optional: import GriefLogger history

Use this only when migrating a server with a supported GriefLogger database. Read the
[GriefLogger integration guide](GRIEFLOGGER_INTEGRATION.md) first. In NeoForge, set
`general.grieflogger_integration_enabled=true` and `general.grieflogger_database_path`;
in Fabric, set `grieflogger_integration_enabled=true` and `grieflogger_database_path`.
Use the source database path and the exact configuration file documented in
[`Configuration`](CONFIGURATION.md), then restart. The GriefLogger database stays
read-only; ItemGraph writes imported rows and reports only to its own database. Grant
`itemgraph.command`, `itemgraph.ingest`, and `itemgraph.import` to the operator. Run
`/ig ingest history` once to queue the historical import on the background worker, then
run `/ig status` to check the latest import status and imported/opaque row counts. A
`FAILED` or partial report means some rows were stored before the failure; it is not a
complete import. Opaque or unresolved rows do not prove an item flow. Do not enable the
bridge for normal standalone ItemGraph operation.

### Optional: integrate another server mod

The [ItemGraph API contract](API.md) describes the `PREVIEW_1` Java API for trusted
NeoForge server mods; it is not an in-game command or a stable API guarantee. The
[compiling consumer example](../examples/api-consumer) demonstrates registration,
bounded observation submission, and asynchronous queries.

## 5. Common failures and safe next steps

| Message or symptom | What it means | Safe next step |
| --- | --- | --- |
| Permission denied | The caller lacks `itemgraph.command` or the command's exact leaf node, or a provider explicitly denied it. | Ask a server owner to grant the required node(s) in the configured provider; see [Security and permissions](SECURITY_AND_PERMISSIONS.md). Do not expose the database to bypass command permissions. |
| Query queue full | The bounded read-only query worker has no waiting slot. | Wait for current queries to finish, then retry with a narrower `sinceMinutes`, radius, or limit. |
| No matching target/candidates | No stored row matched the supplied item/player/container identity. | Check spelling, item registry ID, dimension, coordinates, and capture start time. An empty result only covers the stored evidence. |
| Database unavailable or `/ig audit` reports violations | Storage or evidence consistency needs operator attention. | Preserve the database and logs, stop interpreting missing results as proof, and follow the [security and permissions](SECURITY_AND_PERMISSIONS.md) and [configuration](CONFIGURATION.md) guidance. |
| Startup reports a pending-evidence recovery error or capture stays disabled | `itemgraph-pending-evidence.json` is malformed, unsupported, or over 256 MiB; accepted new events may be preserved in `itemgraph-pending-evidence.json.overflow`. | Stop repeated restarts. Preserve the database, both recovery files if present, and `logs/latest.log`; the files are beside the SQLite database or in `./itemgraph/` with a network database. Do not edit the JSON or database. Send those files to the server owner/ItemGraph maintainer for recovery guidance. See [pending-evidence recovery](CONFIGURATION.md#pending-evidence-recovery). |
| `/ig inspect` does not consume a click | The read-only query was not accepted. | Check `/ig inspect status`, permission, target and server logs; ordinary block/container interaction continues when inspection cannot open. |

## Optional: choose the server message language

Set NeoForge `general.language` or Fabric `language` in
`config/itemgraph.properties` to `en_us` (default), `nl_nl`, or `zh_tw`, then
restart. ItemGraph validates this before opening its database and renders text
on the server, so vanilla clients do not need the mod or network access. Missing
keys fall back to English. Core query/detail/audit labels, inspection responses,
flow-browser rows, controls, help entry text and navigation labels have translated
entries. The explicit English fallback inventory currently contains 125 authored
source phrases, including detailed help topics. See
[`CONFIGURATION.md`](CONFIGURATION.md) for exact evidence limits: the checked-in
GriefLogger 1.2.10-1.21.1 artifact has no locale inventory, while the three
ItemGraph locales match only the separately pinned 26.2 source configuration.

ItemGraph's graph and location history are sensitive. Share only the specific evidence
needed for an incident, and keep database files and exports in operator-controlled storage.
See [Security and permissions](SECURITY_AND_PERMISSIONS.md).

## Feature map: currently available surfaces

The command registration is authoritative. `Partial` means the feature is usable with the
listed boundary; `Planned` means there is no user-facing implementation to use yet.

| Feature | Entry point | Status | Permission / effect |
| --- | --- | --- | --- |
| Live command overview and topic help | bare `/ig`, `/ig help`, `/ig help <topic>` | Shipped | `itemgraph.command`; unset uses level 2; read-only. Topic list includes near/page/provenance lookups and `goto` link help. |
| Fine-grained command permissions | `/ig help permissions`; [security and permissions](SECURITY_AND_PERMISSIONS.md) | Shipped | Exact per-surface nodes, explicit deny, level-2 fallback, and async/menu rechecks. |
| Health, database, queues, and worker status | `/ig status` | Shipped | `itemgraph.command`; unset falls back to level 2; read-only. |
| Database invariants and quantity audit | `/ig audit` | Shipped | `itemgraph.command` + `itemgraph.audit`; async, read-only. |
| Native audit-event lookup | `/ig lookup <eventType>`, `near`, `player`, `page` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; protected message/command events also require `itemgraph.audit`; async, max 100 rows/page. |
| Native audit events for one stored player name | `/ig lookup player <playerName> <eventType> [limit] [sinceMinutes]` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; protected event types also require `itemgraph.audit`; exact stored-name match. |
| Continue the issuing player's saved lookup | `/ig page <page> [session]` | Shipped | `itemgraph.command` + `itemgraph.command.page` + the originating lookup permission; protected sessions retain `itemgraph.audit`; private 30-minute session. |
| Unified action/user/item/time/radius lookup | `/ig lookup action... radius...` or `/ig lookup filters ...` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; player-only because radius is centered on the issuing player; require `itemgraph.audit` when filters can include protected events; max five filters. Console admins can use `/ig lookup near` with explicit dimension and coordinates. |
| Imported legacy-row provenance lookup | `/ig lookup provenance ...` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; imported `chats`/`commands` sources also require `itemgraph.audit`. |
| Raw item-observation details | `/ig event <observationId>` | Shipped | `itemgraph.command` + `itemgraph.event` + `itemgraph.audit`; read-only. |
| Inference explanation and evidence links | `/ig explain <edgeId>` | Shipped | `itemgraph.command` + `itemgraph.explain` + `itemgraph.audit`; confidence is deterministic. |
| Item, player, and container chronology | `/ig trace item|player|container ...` | Shipped | `itemgraph.command` + `itemgraph.trace` + `itemgraph.audit`; async, read-only, capped at 100 hops. |
| Vanilla menu flow browser | `/ig gui item|player|container ...` | Implemented; paired [menu](test-evidence/m8-flow-browser/page-1-menu.png), [row labels](test-evidence/m8-flow-browser/page-1-companion.png), and [detail](test-evidence/m8-flow-browser/observation-detail.png) screenshots | `itemgraph.command` + `itemgraph.gui` + `itemgraph.audit`; player-only, read-only, up to nine entries/page (also capped by `query.max_page_size`) with numbered row labels, selectable details, and page controls. |
| In-world block history and container flow inspection | `/ig inspect [on|off|status]` | Shipped | `itemgraph.command` + `itemgraph.command.inspect`; protected audit evidence also requires `itemgraph.audit`. |
| Normal ingest-and-correlate cycle | `/ig ingest now` | Shipped | `itemgraph.command` + `itemgraph.ingest`; queues background work. |
| Optional historical GriefLogger import | `/ig ingest history` | Partial | `itemgraph.command` + `itemgraph.ingest` + `itemgraph.import`; read-only source import, configured only. |
| Database, queue, and query bounds | `config/itemgraph*.toml`; [configuration reference](CONFIGURATION.md) | Shipped | Operator configuration; restart may be required. |
| Third-party mod integration | [API contract](API.md) and [compiling example](../examples/api-consumer) | NeoForge server-mod API preview; not a player command | `com.itemgraph.api` `PREVIEW_1`; trusted server-side mod code; source-scoped bounded observations and async queries. |
| Rich result hover and safe location actions | `/ig event`, `/ig explain`, `/ig trace`, paged lookups, `/ig help goto` | Implemented; local tests passed, PR CI pending | Hover shows bounded evidence details; click `[Go to ...]` for a private, one-use action that expires after two minutes and rechecks query permissions. `/ig goto <token>` is only the internal click action; do not type the token manually. |
| Player-broken container contents | Native block-break evidence | Implemented; local tests passed, PR CI pending | Per-slot removal is recorded; destination stays `UNKNOWN` until an authoritative drop link exists. |

For exact command syntax and defaults, continue to [Query model](QUERY_MODEL.md). For
parity gaps and work status, see [Feature parity inventory](FEATURE_PARITY_INVENTORY.md).
