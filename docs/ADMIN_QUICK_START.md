# ItemGraph admin quick start

This guide is for a server admin investigating an item incident for the first time. It
uses the current ItemGraph command tree and behavior for Minecraft 1.21.1. ItemGraph is a
server-side NeoForge or Fabric mod; players do not need a client mod for commands or the
vanilla flow browser. All current commands require vanilla permission level 2.

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

If no events have been captured yet, use a test world or staging server to perform one
ordinary action and confirm it appears. Do not test by changing production inventories.
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
| What was recorded at this button, furnace, or other supported functional block? | `/ig inspect on`, then right-click the functional block | A paginated audit-history query for the clicked block. |
| What was recorded at the block behind the face I am pointing at? | `/ig inspect on`, then right-click an ordinary non-container block | A paginated audit-history query for the adjacent block on the clicked face. |
| What flow touched a container I am looking at? | `/ig inspect on`, then right-click a block entity implementing `Container` | The read-only flow browser. Either half of a valid double chest opens the same canonical anchor used by container capture. |
| Is storage internally consistent? | `/ig audit` | Conservation, positivity, relational-integrity, and allocation-state results. |

`/ig` and `/itemgraph` are the same command root. `/ig help` lists current commands;
`/ig help lookup near`, `/ig help trace item`, and other topic forms give syntax and an
example. `/ig page <page>` continues the issuing player's saved lookup session. By
contrast, `/ig lookup page <page> <eventType> [limit] [sinceMinutes]` runs a direct page
number query for that event type.

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
client installation requirement. Its entries are read-only; attempts to move inventory
items through the menu are rejected. For a supported container, `/ig inspect on` and a
right-click open the same kind of container flow browser. For a valid double chest, either
half opens the canonical anchor used when its contents are recorded. Run `/ig inspect off`
when finished; mode also clears on logout and server stop.

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

## 5. Common failures and safe next steps

| Message or symptom | What it means | Safe next step |
| --- | --- | --- |
| Permission denied | The caller lacks vanilla permission level 2. | Ask a server owner/operator to grant the normal operator level; do not expose the database to bypass command permissions. |
| Query queue full | The bounded read-only query worker has no waiting slot. | Wait for current queries to finish, then retry with a narrower `sinceMinutes`, radius, or limit. |
| No matching target/candidates | No stored row matched the supplied item/player/container identity. | Check spelling, item registry ID, dimension, coordinates, and capture start time. An empty result only covers the stored evidence. |
| Database unavailable or `/ig audit` reports violations | Storage or evidence consistency needs operator attention. | Preserve the database and logs, stop interpreting missing results as proof, and follow the [security and permissions](SECURITY_AND_PERMISSIONS.md) and [configuration](CONFIGURATION.md) guidance. |
| `/ig inspect` does not consume a click | The read-only query was not accepted. | Check `/ig inspect status`, permission, target and server logs; ordinary block/container interaction continues when inspection cannot open. |

ItemGraph's graph and location history are sensitive. Share only the specific evidence
needed for an incident, and keep database files and exports in operator-controlled storage.
See [Security and permissions](SECURITY_AND_PERMISSIONS.md).

## Feature map: currently available surfaces

The command registration is authoritative. `Partial` means the feature is usable with the
listed boundary; `Planned` means there is no user-facing implementation to use yet.

| Feature | Entry point | Status | Permission / effect |
| --- | --- | --- | --- |
| Live command overview and topic help | bare `/ig`, `/ig help`, `/ig help <topic>` | Shipped | Level 2; read-only. Topic list includes near/page/provenance lookups. |
| Health, database, queues, and worker status | `/ig status` | Shipped | Level 2; read-only. |
| Database invariants and quantity audit | `/ig audit` | Shipped | Level 2; asynchronous, read-only. |
| Native audit-event lookup | `/ig lookup <eventType>`, `near`, `player`, `page` | Shipped | Level 2; async, read-only, max 100 rows per page. |
| Native audit events for one stored player name | `/ig lookup player <playerName> <eventType> [limit] [sinceMinutes]` | Shipped | Level 2; async, read-only; exact stored-name match. |
| Continue the issuing player's saved lookup | `/ig page <page> [session]` | Shipped | Level 2; private session, 1-based page, expires after 30 minutes. |
| Unified action/user/item/time/radius lookup | `/ig lookup action... radius...` or `/ig lookup filters ...` | Shipped | Level 2; async, read-only; radius required, maximum five filters. |
| Imported legacy-row provenance lookup | `/ig lookup provenance ...` | Shipped | Level 2; read-only; provenance does not contribute item quantity. |
| Raw item-observation details | `/ig event <observationId>` | Shipped | Level 2; read-only. |
| Inference explanation and evidence links | `/ig explain <edgeId>` | Shipped | Level 2; read-only; confidence is deterministic. |
| Item, player, and container chronology | `/ig trace item|player|container ...` | Shipped | Level 2; async, read-only, capped at 100 hops. |
| Vanilla menu flow browser | `/ig gui item|player|container ...` | Shipped | Level 2; player-only, read-only, up to 45 entries per page. |
| In-world block history and container flow inspection | `/ig inspect [on|off|status]` | Shipped | Level 2; per-player state; exact-position read-only query. |
| Normal ingest-and-correlate cycle | `/ig ingest now` | Shipped | Level 2; queues background work. |
| Optional historical GriefLogger import | `/ig ingest history` | Partial | Level 2; read-only source import, available only when configured; no runtime GriefLogger dependency. |
| Database, queue, and query bounds | `config/itemgraph*.toml`; [configuration reference](CONFIGURATION.md) | Shipped | Operator configuration; restart may be required. |
| Third-party mod integration | `com.itemgraph.api` `PREVIEW_1`; [API example](../examples/api-consumer) | Preview | Trusted server-side mod code; source-scoped bounded observations and async queries. |
| Named fine-grained permission nodes | Tracked by [#137](https://github.com/DurdeuVlad/itemgraph/issues/137) | Planned | Current command tree still uses the shared level-2 gate. |
| Rich result hover and safe location actions | Tracked by [#138](https://github.com/DurdeuVlad/itemgraph/issues/138) | Planned | Plain chat and page actions remain; no location click is available. |
| Complete player-broken container contents | Tracked by [#140](https://github.com/DurdeuVlad/itemgraph/issues/140) | In progress | Until merged, do not assume every container-break inventory is captured. |

For exact command syntax and defaults, continue to [Query model](QUERY_MODEL.md). For
parity gaps and work status, see [Feature parity inventory](FEATURE_PARITY_INVENTORY.md).
