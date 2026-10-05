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

`/ig status` starts with an `ACTION` line: it gives a safe next step for a reported
database/capture problem, or says that no stop was reported while warning that capture
coverage is still unproven. It then prints configuration and diagnostic counters,
followed by stored row counts when the database is connected. The action line does not
assign a health score. `RUNNING` means the
source-ingestion worker started; it does not mean the database is healthy or that native
capture is active. `/ig audit` runs a read-only database integrity and
quantity-conservation check. Fix a reported database/queue problem before treating an
empty search as evidence that nothing happened.

If no events have been captured yet, use a disposable test world or staging server to
perform one ordinary action and confirm a matching observation appears. Do not test by
changing production inventories. An empty result is not a capture test until you confirm
that capture was active and the event type is supported.
For server setup and the exact database keys, see [Configuration](CONFIGURATION.md).

### Read `/ig status` without guessing

Status values are operational facts, not a calibrated healthy/degraded score. No
production-derived performance thresholds are available. Start with `captureState` and
the database connection; then check queue/persistence counters and last operation results.
The in-game `/ig help status` gives the short version.

#### If status shows a problem

| Status result | Next safe action |
| --- | --- |
| `ACTION: No reported DB/capture stop` | No stop condition is exposed by those checks. Verify the event type and capture start time before trusting an empty search. |
| `ACTION: Records dropped` | Check queue/storage logs; search results may be incomplete. The diagnostic line includes the counter. |
| `ACTION: Recovery blocked` | Preserve recovery files and inspect startup logs; see [pending-evidence recovery](CONFIGURATION.md#pending-evidence-recovery). |
| `ACTION: Recovery loading` | Wait before interpreting queue totals. |
| `ACTION: Capture stopped` | Check server logs before relying on new evidence. |
| `ACTION: Capture disabled` | Results can cover stored evidence only; enable capture only if intended. |
| `ACTION: DB disconnected` | Check config/logs and preserve database files. Row statistics are unavailable. |
| `captureState=DISABLED_BY_CONFIG` | Native capture is off. If you intend to capture new events, enable the documented ItemGraph capture setting, restart if required, and run a safe test in a test world. |
| `captureState=RECOVERY_LOADING` | Wait for recovery to finish. `recoveryPendingRecords=unknown` is expected while the file is being read. |
| `captureState=RECOVERY_BLOCKED` | Preserve the recovery and `.overflow` files named in the log. Do not edit or delete them; follow [pending-evidence recovery](CONFIGURATION.md#pending-evidence-recovery). |
| `captureState=STOPPED` | Check startup/shutdown errors in the server log and restore the worker before relying on new capture. |
| `db=NOT CONNECTED` | Stored row counts are unavailable; the capture, queue, and performance diagnostics still print. Check the database settings and server log; preserve the database files. |
| `dropped` is above zero | Some evidence was rejected or lost. Preserve logs, investigate queue/storage errors, and do not treat missing query results as proof that no event occurred. |

For an empty result, check that capture was active before the event, that the event type
is supported, and that the query's item/player/container, dimension, time range, and
permissions match the incident. An empty result is not proof that the event did not happen.

| Status field | What it measures | Safe interpretation / next step |
| --- | --- | --- |
| `db=connected` / `db=NOT CONNECTED` | Whether ItemGraph's database manager is initialized when the command runs. | `connected` does not prove every row committed. If disconnected or statistics are unavailable, inspect the server log and database configuration; do not edit or delete the database. |
| `captureEnabled` | The configured native-capture toggle. | `false` means native capture is disabled by configuration. It is separate from optional GriefLogger history import. |
| `captureState=ACTIVE` | Internal worker is running, submissions are open, recovery loading is complete, and capture is enabled. | This confirms the capture path is open; it does not prove a particular event type is supported or that each accepted record is already durable. Check `persisted`, `dropped`, and the latest cycle/pass. |
| `captureState=DISABLED_BY_CONFIG` | Native capture is disabled by configuration. | Enable capture through the documented config only when intended; then restart as required and confirm `/ig status` again. |
| `captureState=RECOVERY_LOADING` | The worker is loading a pending-evidence recovery file. | `recoveryPendingRecords=unknown` is expected until loading finishes. New records may queue during recovery. Wait for loading to finish before interpreting queue totals. |
| `captureState=RECOVERY_BLOCKED` | The recovery file could not be loaded and intake is closed. | Preserve the primary recovery file, any `.overflow` file, and `logs/latest.log`. Do not edit or delete them; follow [pending-evidence recovery](CONFIGURATION.md#pending-evidence-recovery). `recoveryPendingRecords=unknown` is not zero. |
| `captureState=STOPPED` | The native evidence worker is not running or submission admission is closed. | Check startup/shutdown errors in the server log and ask the server owner to restore the worker before relying on new capture. |
| `recoveryPendingRecords` | Accepted records currently tracked for replay or recovery handoff. | A number is a record count awaiting durable acknowledgement. `unknown` means recovery has not been checked, is loading, or failed; never read it as zero. |
| `DIAGNOSTIC capture/queue: size`, `capacityPerQueue`, `idlePollMs`, `flushEveryTicks`, `maxBatchSize`, `networkHeartbeatMs` | Current queued-record count, per-queue cap, and configured worker cadence/batch/heartbeat settings. | `size` counts waiting records across queues; the batch already taken by the worker is not included. These are settings/current values, not a health verdict. The queue is bounded at 10,000 records per queue. |
| `enqueued`, `persisted`, `dropped`, `transformations`, `auditEvents` | In-memory totals for submitted, durably written, lost/rejected, and persisted transformation/audit records. | They count evidence records, not item quantity. A nonzero `dropped` means evidence was rejected or lost; preserve logs and investigate. Their reset time is not printed, so do not compare them as a time-window rate. |
| `capabilityQueueRejections` | Failed submissions from modded-inventory capability tracking. | A rejection means ItemGraph could not queue that capability delta; it does not identify a player action. The reset window is not printed. |
| `networkHeartbeats`, `networkHeartbeatFailures` | In-memory database liveness-check totals and failed checks. | The reset timestamp is not printed. Nonzero failures warrant checking database connectivity and server logs; they are not row counts. |
| `lastCycle` | Most recent source-ingestion cycle result, number of imported item/container rows, and duration. | `never run yet` is normal when optional history import has not run. `ERROR` refers to that source-ingestion cycle; it is separate from native capture. |
| `checkpoints` and `historicalImport` | Source row IDs reached by the importer and the latest historical GriefLogger import summary. | These describe optional migration input, not native evidence capture. `opaque` rows do not establish an item flow. |
| `lastPass` and `groundBridgeWindow` | Latest correlation result and configured temporal bridge window. `evaluated`, `bridges inferred`, `deferred`, and duration are counts/time for that pass. | `OK` means the pass completed, not that all evidence was linked. Deferred events remain for later processing; inspect `/ig explain` for a specific inferred edge. |
| `activeEdges`, `supersededEdges` | Durable counts of current and superseded inferred edges. | These are graph totals, not direct observations or proof that a specific transfer occurred. |
| `enqueueCount`, `persistBatches`, `queryCount`, `correlationCount` and their `Failed` fields | Operation sample/failure counts in `OperationalMetrics`. | These reset when the internal worker starts; status does not print their start time. `enqueueP95`, `commitP95`, `queryP95`, and `correlationP95` are upper-bound milliseconds from a fixed bucket, not exact percentiles. `n/a` means no sample. |
| `queuePeak`, `queueRejectedItems`, `persistedItems`, `largestBatch`, `persistFailedBatches` | Peak queued records, queue-rejected records, records in successful persistence batches, largest successful batch, and failed commit batches in the same operational-metric window. | Despite the `persistedItems` name, this counter is persistence records, not stack quantity. They are performance diagnostics. A failed commit or rejected record needs investigation; no configured threshold currently maps these values to a health label. |
| `unresolvedPayloadDecodeFailures`, `decodeFailureCacheInsertions`, `decodeCacheHits` | Malformed item-component decode encounters, negative-cache inserts, and cached failure lookups in the operational-metric window. | These are counts, not unique items. Repeated hits can describe one malformed payload; inspect logs for the item ID and do not treat the stack as decoded. |
| `heapUsedBytes`, `heapMaxBytes` | JVM heap used and maximum, sampled for this `/ig status` request. | Convert bytes to MiB if useful; this is an instantaneous sample, not a memory trend or configured ItemGraph limit. |
| `active`, `drops`, `pickups`, `continuityMatches` under entity tracking | Current tracked entity-map size and in-memory drop/pickup/continuity counters. | The counters do not identify a unique physical stack and have no reset timestamp in status. Use event IDs and supporting observations for a specific incident. |

An empty result still requires checking capture start time, event support, query target,
dimension, time window, and permission. Status does not include the recovery file path or
raw evidence. If startup logs say recovery failed, preserve the files named in those logs
and use the recovery procedure above.

## 2. Grant a moderator the exact permissions they need

Every command also requires `itemgraph.command`. Named nodes are exact: dots do not
inherit, a provider's explicit `false` denies access, and an unset node falls back to
vanilla permission level 2. Grant only the bundle matching the task. Protected evidence
requires `itemgraph.audit` in addition to the command's own node.

| Moderator role | Additional exact nodes | Verify safely |
| --- | --- | --- |
| Ordinary event lookup | `itemgraph.command.lookup` | Every command also needs `itemgraph.command`. Chat/command, staff/admin/creative, and sensitive-location events (including block/container and entity/projectile events) also need `itemgraph.audit`. In a test world, verify `/ig lookup PLAYER_JOIN 10 60` succeeds and a moderator without audit permission cannot query `KILL_ENTITY`. |
| Flow investigation | `itemgraph.trace`, `itemgraph.event`, `itemgraph.explain`, `itemgraph.audit`; add `itemgraph.command.lookup` and `itemgraph.command.page` to continue a saved lookup | Run `/ig trace item "minecraft:stone" 10 60`; open an observation with `/ig event <observationId>` or an inferred edge with `/ig explain <edgeId>`. |
| Block inspector | `itemgraph.command.inspect`; add `itemgraph.audit` for block-history results | Run `/ig inspect status`, then `/ig inspect on` in a test world. Block-history clicks need audit permission. |
| Container flow browser | `itemgraph.command.inspect`, `itemgraph.gui`, `itemgraph.audit` | A player must run `/ig inspect on` and right-click a supported container; the GUI is player-only and read-only. |
| Optional GriefLogger history import | `itemgraph.ingest`, `itemgraph.import` | On an isolated test server with a copy of the source database and ItemGraph data, run `/ig ingest history`; this queues a real import, not a permission-only check. There is no read-only import dry run. |

NeoForge resolves named grants through its PermissionAPI provider. Fabric bundles
`fabric-permissions-api` 0.3.1; a compatible permission-provider mod is needed to
configure named grants. The permission matrix and loader details are in
[Security and permissions](SECURITY_AND_PERMISSIONS.md). If a grant is denied, check
that `itemgraph.command` and every listed node are present, no explicit deny is set, and
the role's protected-evidence requirement is included. Do not assume wildcard or dotted
parent inheritance.

## 3. Pick the investigation that matches your question

| Admin question | Start here | What it returns |
| --- | --- | --- |
| What happened to this item or item type? | `/ig trace item <item-or-name> [limit] [sinceMinutes]` | A time-ordered item-flow trace. Ambiguous fingerprints are shown as candidates. |
| What item movement involved this player? | `/ig trace player <playerName> [limit] [sinceMinutes]` | Item movements through player inventory endpoints. Duplicate player nodes are shown as candidates. |
| What item flow touched this container? | `/ig trace container <x> <y> <z> [limit] [sinceMinutes]` | A trace for container nodes at those coordinates. Use `/ig gui container` when dimension disambiguation or visual paging is useful. |
| What blocks, commands, or interactions were recorded nearby? | `/ig lookup near <dimension> <x> <y> <z> <radius> <eventType> [limit] [sinceMinutes]` | Native observed audit events in a bounded cube. |
| What craft or smelt result was taken? | `/ig lookup CRAFT_OUTPUT_UNRESOLVED 50 1440` or `SMELT_OUTPUT_UNRESOLVED` | Requires `itemgraph.command` + `itemgraph.command.lookup` + `itemgraph.audit`. Shows the observed result item, output count, fingerprint, event ID, and `TRANSFORMATION_INPUTS_NOT_OBSERVED`; it does not prove the recipe or create a trace edge. `/ig event event:<evidence-uuid>` opens its detail. Legacy `CRAFT`/`SMELT` rows are unresolved and excluded from trace lineage. Anvil rename/repair remains a separate transition; trades, enchanting, brewing, smithing, grindstone, and loot are not covered. |
| What did a staff item command attempt or change? | `/ig lookup near <dimension> <x> <y> <z> 32 ADMIN_ITEM_COMMAND_EFFECT 50 1440` (repeat with `ADMIN_ITEM_COMMAND_ATTEMPT`, `ADMIN_ITEM_COMMAND_FAILURE`, or `ADMIN_ITEM_COMMAND_UNRESOLVED`) | Staff-private event outcomes near the coordinates. The command record keeps the root and outcome, not selector expressions or command arguments. To inspect an item's fingerprint flow separately, run `/ig trace item "<item-id>" 50 1440`; this needs `itemgraph.trace` + `itemgraph.audit` and does not associate the item with a specific command or actor. A console command actor can remain `UNKNOWN`. If the item query lists several fingerprints, choose the matching fingerprint ID and run `/ig trace item "id:<fingerprint-id>" 50 1440`; this follows one fingerprint and does not combine component variants. |
| What creative inventory or block action was recorded? | `/ig lookup near <dimension> <x> <y> <z> 32 CREATIVE_SLOT_EFFECT 50 1440` or `/ig lookup near <dimension> <x> <y> <z> 32 CREATIVE_BLOCK_RESULT 50 1440` | Staff-private action outcomes. Use `CREATIVE_SLOT_ATTEMPT`, `CREATIVE_BLOCK_ATTEMPT`, or `CREATIVE_BLOCK_UNRESOLVED` to check other outcomes. These records do not themselves prove a quantity change. |
| What item quantity changed through creative inventory? | `/ig lookup filters action.creative_item_create radius.32 time.24h` (repeat with `action.creative_item_remove`) | Creative item-flow observations within 32 blocks of the issuing player over 24 hours. This filter is player-only and requires the audit permission. |
| What was recorded when a container was broken? | `/ig lookup near <dimension> <x> <y> <z> 16 CONTAINER_BREAK_COMPLETED 50 1440` (also check `CONTAINER_BREAK_UNRESOLVED`) | The parent break audit event. This event-type lookup requires `itemgraph.command.lookup`; it does not return the slot rows. `CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED` means the break/snapshot exists but no drop destination was established. `CONTAINER_SNAPSHOT_INCOMPLETE` means the snapshot itself is incomplete. |
| Which item stacks were recorded in the broken container? | `/ig trace container <x> <y> <z> 50 1440` | If the destroyed container node resolves, the trace shows its slot-removal observations. It requires `itemgraph.trace` and `itemgraph.audit`; no destination or recipient is established. |
| What evidence supports one raw item event? | `/ig event <observationId>` or `/ig event event:<uuid>` | One stored observation, transformation, or unresolved audit record and its metadata. Admin/creative mutation rows also show copyable mutation, command-attempt, observation, transformation, and unresolved-event UUIDs. |
| Why does ItemGraph connect these events? | `/ig explain <edgeId>` | One inferred edge, deterministic confidence, explanation, and supporting evidence IDs. |
| Which imported legacy row is this? | `/ig lookup provenance <sourceSha256> <table> <sourceKey> [limit]` | Exact imported GriefLogger provenance; provenance-only rows do not add item quantity. |
| How do I browse without a custom client? | `/ig gui item`, `/ig gui player`, or `/ig gui container` | A read-only vanilla six-row menu with candidate selection, evidence detail, and paging. |
| What was recorded at the block I am looking at? | `/ig inspect on`, then left-click that block | An exact-position, paginated audit-history query in chat. |
| What was recorded at this button or another functional block that is not a container? | `/ig inspect on`, then right-click the block | A paginated audit-history query for the clicked block. |
| What was recorded at the block behind the face I am pointing at? | `/ig inspect on`, then right-click an ordinary non-container block | A paginated audit-history query for the adjacent block on the clicked face. |
| What flow touched a container I am looking at? | `/ig inspect on`, then right-click a block entity implementing `Container` (for example, a chest or furnace) | The read-only flow browser. Either half of a valid double chest opens the same canonical anchor used by container capture. |
| Is storage internally consistent? | `/ig audit` | Conservation, positivity, relational-integrity, and allocation-state results. |
| How do I run a filtered lookup from console? | `/ig lookup near <dimension> <x> <y> <z> <radius> <eventType> ...` | Coordinate-based nearby audit query; `/ig lookup <filters...>` is player-only because its radius uses the issuing player's position. |
| What automated inventory changes were recorded? | `/ig trace container <x> <y> <z> 50 60` | NeoForge rows use `CAPABILITY_INSERT`/`CAPABILITY_EXTRACT`; Fabric rows use `HOPPER_INSERT`/`HOPPER_EXTRACT`. The changed container is known, but caller and remote endpoint are `UNKNOWN`; the row alone does not link a transfer. |

`/ig` and `/itemgraph` are the same command root. `/ig help` gives task-first starting
points; `/ig help commands` is a compact task hub that points to detailed topics. `/ig help
lookup near`, `/ig help trace item`, and other topic forms give exact routes, syntax, and an example.
For complete first-time routes, use `/ig help journeys inspect`,
`/ig help journeys trace`, `/ig help journeys near`, or
`/ig help journeys filters`. The focused routes `/ig help lookup admin`,
`/ig help lookup lifecycle`, and `/ig help lookup transformations` cover those event families.
Each journey states the permission nodes, result meaning, next command, and checks for an empty result.
`/ig help goto` explains that `[Go to ...]` is an in-game click action; do not type its token.
`/ig page <page>` continues the issuing player's saved lookup session. By
contrast, `/ig lookup page <page> <eventType> [limit] [sinceMinutes]` runs a direct page
number query for that event type.
Console `/ig lookup near` results do not save coordinates or radius for a later page.
A direct event-type page query does not retain that nearby scope.

The filtered lookup accepts `action`, `user`, `include`, `exclude`, `time`, and `radius` in
`name.value` form. `include` and `exclude` match item registry IDs and cannot be combined.
`time` is a relative whole-number duration with `m`, `h`, `d`, or `y`, such as `time.1h`.
Radius is required, player-relative, and capped at 1,024 blocks. A request accepts at most
five unique filters and at most 32 comma-separated values per list. Quote a comma-separated
value so Brigadier passes it as one argument, for example:

```text
/ig lookup action.break_block "include.stone,diamond" radius.50 time.1h
```

These filters do not select enchantments, trims, custom names, arbitrary components, or
fingerprint hashes, and do not accept absolute UTC `after`, `before`, or `between`
timestamps. In particular, `CAPABILITY_INSERT`/`CAPABILITY_EXTRACT` are not accepted by the
current filtered lookup action registry; use `/ig trace container` and read the recorded
action instead. Use `/ig trace item` to resolve candidate fingerprints and hover for the full
canonical metadata hash. Equal metadata does not identify one physical item. See the
[Query model](QUERY_MODEL.md) for current behavior and planned extensions.

Automated inventory coverage is loader-specific. NeoForge observes completed
(`simulate=false`) `IItemHandler` calls on registered vanilla inventories, including
hoppers, dispensers, droppers, furnaces, chests, and shulker boxes. Each row identifies the
changed container; caller and remote endpoint remain `UNKNOWN`. Fabric observes before/after
net deltas around successful vanilla hopper transfers, also with an unknown remote endpoint.
The Fabric Transfer API adapter for modded inventories is not implemented. The planned
`DISPENSER_EFFECT` taxonomy entry does not prove that a dispenser launched an item. Do not
assume a backpack, pipe, or arbitrary modded inventory is covered; see
[GriefLogger integration](GRIEFLOGGER_INTEGRATION.md) and
[automated-container architecture](ARCHITECTURE.md#automated-container-transfers-issue-4).

## 4. Run a first read-only investigation

Use a test world or a known event in a time range you control. These commands only read
ItemGraph data:

<!-- guide-command-examples:start -->
```text
/ig status
/ig lookup BREAK_BLOCK 20 60
/ig trace item "minecraft:stone" 20 60
```
<!-- guide-command-examples:end -->

1. Check `/ig status` first. Confirm `captureState=ACTIVE` and database connectivity;
   if status reports a problem, follow the safe next action in section 1 before relying
   on an empty result.
2. `/ig lookup BREAK_BLOCK 20 60` searches the last hour for recorded block-break audit
   events. Replace the event type, limit, and time window to match your question.
3. `/ig trace item "minecraft:stone" 20 60` shows item-flow observations and any
   separately inferred links for that item type. Copy an observation ID to
   `/ig event <observationId>` or an inferred edge ID to `/ig explain <edgeId>`.
   Do not use an audit row number or subject UUID as an observation ID.

An empty result only means no stored row matched. Check capture start time, event support,
target, dimension, time range, and permissions. A parser-only command corpus used by both
loader tests is at `docs/test-evidence/m9-admin-ux/quick-start-command-corpus.txt`; it is
test input, **not a workflow or a list of commands to paste into a live server**. Importing
history and enabling in-world inspection are separate, intentional actions described above;
neither is part of this read-only first investigation.

## 5. Read a flow-browser result

The flow browser is a vanilla menu. It has no ItemGraph-specific item, screen, packet, or
client installation requirement. Each page has at most nine rows. A numbered chat companion
shows each row's observed/inferred class and shortened safe item identity. Match its number
to the menu slot in reading order: left-to-right, then top-to-bottom. Hover the menu slot to
read the full amount, event kind, exact UTC time, endpoints, evidence ID, and detail text.
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

## 6. Read the evidence correctly

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
- Staff item-command and creative-inventory events are protected evidence. A moderator
  needs the lookup node and `itemgraph.audit`; the output may report an `UNKNOWN` command
  actor. Do not infer identity or a quantity change from an attempt, failure, or unresolved
  row.

Movement is time ordered. Later evidence cannot explain an earlier event. Unless there is
evidence for creation, transformation, or destruction, an inference must conserve item
quantity. Names, enchantments, components, and fingerprints help distinguish item types
and metadata; they are not permanent per-item UUIDs. Every inferred edge should link back
to its evidence IDs and explanation.

A confidence value is a deterministic score from documented evidence factors, not a
calibrated probability that the history is true. Use `/ig explain <edgeId>` to see the
factors, candidate counts, and supporting observations behind a link.

Unified lookup rows have source-prefixed IDs. For `observation#42`, pass only `42` to
`/ig event 42`. An `audit#42` row is already inline evidence; transformation and imported
IDs are source references, not `/ig event` arguments. Read their row detail or narrow and
rerun the lookup. For an imported GriefLogger row, `/ig lookup provenance` requires its
source SHA-256, table, and exact source key; use only values supplied by the import record.
An unresolved row may not contain an event UUID, so use `/ig event event:<uuid>` only when
that exact supported UUID is printed. `/ig explain <edgeId>` requires
`itemgraph.explain` plus `itemgraph.audit`; item trace follow-ups require
`itemgraph.trace` plus `itemgraph.audit`.

### Coverage limits to check before trusting an empty result

An empty query means no matching row exists in the evidence ItemGraph has stored for that
query. It does not establish that nothing happened. Check the capture start time, event
type, permissions, dimension, and target first. Current known limits include explosion
and other environmental causes, Enderman causes, moving blocks, entity/projectile lifecycle
and impact outcomes, item-entity merge/removal, zero-net container sessions where items
are taken and returned before close, and arbitrary modded backpacks or inventories without
an adapter. Container GUI evidence is a net change over the
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

## 7. Common failures and safe next steps

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
entries. The explicit English fallback inventory currently contains 174 authored
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
| Live command overview and topic help | bare `/ig`, `/ig help`, `/ig help <topic>`, `/ig help journeys` and its four subtopics | Shipped | `itemgraph.command`; unset uses level 2; read-only. Journey topics give exact permission bundles, evidence meaning, follow-up commands, and safe empty-result checks for inspection, trace, nearby audit, and filtered player/console lookup. |
| Fine-grained command permissions | `/ig help permissions`; [security and permissions](SECURITY_AND_PERMISSIONS.md) | Shipped | Exact per-surface nodes, explicit deny, level-2 fallback, and async/menu rechecks. |
| Runtime, database, queues, and worker status | `/ig status` | Shipped | `itemgraph.command`; starts with a safe next step for a reported stop or warns that no stop still does not prove capture coverage, then labeled diagnostic counters; no calibrated health score; database-only counts can be unavailable; read-only. |
| Database invariants and quantity audit | `/ig audit` | Shipped | `itemgraph.command` + `itemgraph.audit`; async, read-only. |
| Native audit-event lookup | `/ig lookup <eventType>`, `near`, `player`, `page` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; protected message/command events also require `itemgraph.audit`; async, max 100 rows/page. |
| Item transformations | `/ig help lookup transformations`; exact event lookup for output-only craft/smelt | Partial (#57, #162) | Craft/smelt results require `itemgraph.command` + `itemgraph.command.lookup` + `itemgraph.audit` and report output evidence as `UNRESOLVED`; they do not create lineage. Legacy `CRAFT`/`SMELT` rows are excluded from traces. Anvil rename/repair remains separate lineage. Trade, enchanting, brewing, smithing, grindstone, and loot remain planned. |
| Native audit events for one stored player name | `/ig lookup player <playerName> <eventType> [limit] [sinceMinutes]` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; protected event types also require `itemgraph.audit`; exact stored-name match. |
| Admin item-command outcomes | `/ig help lookup admin`; `/ig lookup ADMIN_ITEM_COMMAND_EFFECT 50 1440` | Shipped | `itemgraph.command` + `itemgraph.command.lookup` + `itemgraph.audit`; the help topic names attempt/failure/unresolved alternatives. Records do not expose command arguments or selector values; item flow must be queried separately. |
| Creative inventory/block outcomes | `/ig lookup near <dimension> <x> <y> <z> 32 CREATIVE_SLOT_EFFECT 50 1440`; quantity: `/ig lookup filters action.creative_item_create radius.32 time.24h` | Shipped | Audit lookup requires `itemgraph.command.lookup` + `itemgraph.audit`; quantity filters are player-only. Outcome records do not by themselves prove a quantity change. |
| Entity, death-drop, throw, and projectile-spawn evidence | `/ig help lookup lifecycle`; `/ig lookup KILL_ENTITY 50 1440` | Partial | `itemgraph.command` + `itemgraph.command.lookup`; `KILL_ENTITY` and `PROJECTILE_SPAWN_ACCEPTED` also require `itemgraph.audit`. `THROW_ITEM`/`SHOOT_ITEM` are attempts. Death-drop trace requires `itemgraph.trace` + `itemgraph.audit` and does not identify the killer/cause. Accepted spawn is not impact. No entity-removal, impact, or item-entity merge/despawn query is shipped; #56 tracks that work. |
| Craft, smelt, and anvil transformations | `/ig help lookup transformations`; `/ig lookup filters action.craft radius.32 time.24h` | Partial | `itemgraph.command` + `itemgraph.command.lookup` + `itemgraph.audit`; player-only radius filter. Repeat with `action.smelt`, `action.anvil_rename`, or `action.anvil_repair`. Recorded inputs/results do not establish full recipe, station, or trade partner; other stations are tracked by #57. |
| Broken-container slot-removal observations | `/ig lookup near <dimension> <x> <y> <z> 16 CONTAINER_BREAK_COMPLETED`; then `/ig trace container <x> <y> <z> 50 1440` | Partial | Lookup: `itemgraph.command.lookup` + `itemgraph.audit`; trace: `itemgraph.trace` + `itemgraph.audit`. Also query `CONTAINER_BREAK_UNRESOLVED`. Slot removals can identify source container but destination/recipient stays `UNKNOWN`. |
| Continue the issuing player's saved lookup | `/ig page <page> [session]` | Shipped | `itemgraph.command` + `itemgraph.command.page` + the originating lookup permission; protected sessions retain `itemgraph.audit`; private 30-minute session. |
| Unified action/user/item/time/radius lookup | `/ig lookup action... radius...` or `/ig lookup filters ...` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; player-only because radius is centered on the issuing player; require `itemgraph.audit` when filters can include protected events; max five filters. Console admins can use `/ig lookup near` with explicit dimension and coordinates. |
| Imported legacy-row provenance lookup | `/ig lookup provenance ...` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; imported `chats`/`commands` sources also require `itemgraph.audit`. |
| Raw item-observation details | `/ig event <observationId>` or `/ig event event:<uuid>` | Shipped | `itemgraph.command` + `itemgraph.event` + `itemgraph.audit`; numeric IDs open observations; UUIDs open related observation, transformation, or unresolved evidence; read-only. |
| Inference explanation and evidence links | `/ig explain <edgeId>` | Shipped | `itemgraph.command` + `itemgraph.explain` + `itemgraph.audit`; confidence is deterministic. |
| Item, player, and container chronology | `/ig trace item|player|container ...` | Shipped | `itemgraph.command` + `itemgraph.trace` + `itemgraph.audit`; async, read-only, capped at 100 hops. |
| Vanilla menu flow browser | `/ig gui item|player|container ...` | Shipped | `itemgraph.command` + `itemgraph.gui` + `itemgraph.audit`; player-only, read-only, up to nine entries/page (also capped by `query.max_page_size`). Numbered chat entries map left-to-right to the first menu row; hovering an entry labels its exact menu slot and shows selectable details. Page controls are below. See [menu](test-evidence/m8-flow-browser/page-1-menu.png), [row labels](test-evidence/m8-flow-browser/page-1-companion.png), and [detail](test-evidence/m8-flow-browser/observation-detail.png) screenshots. |
| In-world block history and container flow inspection | `/ig inspect [on|off|status]` | Shipped | `itemgraph.command` + `itemgraph.command.inspect`; protected audit evidence also requires `itemgraph.audit`. |
| Automated inventory movement observations | `/ig trace container <x> <y> <z> [limit] [sinceMinutes]` | Partial | `itemgraph.command` + `itemgraph.trace` + `itemgraph.audit`; NeoForge records completed nonsimulated `IItemHandler` deltas only from registered vanilla providers (`CAPABILITY_INSERT`/`CAPABILITY_EXTRACT`), while Fabric records successful vanilla hopper net deltas (`HOPPER_INSERT`/`HOPPER_EXTRACT`). Each row proves a delta at the changed endpoint only; caller and remote endpoint remain `UNKNOWN`, so it does not prove a linked transfer. Unregistered NeoForge modded inventories, backpacks, pipes, Fabric Transfer API inventories, and proof that a dispenser launched an item are unsupported. Open a numeric observation with `/ig event <observationId>` or explain a separately returned inferred edge with `/ig explain <edgeId>`. |
| Optional GriefLogger source sync | `/ig ingest now` | Partial | `itemgraph.command` + `itemgraph.ingest`; requires the GriefLogger source integration. Native event capture runs automatically when enabled and does not require this command. |
| Optional historical GriefLogger import | `/ig ingest history` | Partial | `itemgraph.command` + `itemgraph.ingest` + `itemgraph.import`; read-only source import, configured only. |
| Database, queue, and query bounds | `config/itemgraph*.toml`; [configuration reference](CONFIGURATION.md) | Shipped | Operator configuration; restart may be required. |
| Third-party mod integration | [API contract](API.md) and [compiling example](../examples/api-consumer) | Partial | `com.itemgraph.api` `PREVIEW_1` is preview-only server-side integration: a NeoForge server-mod API preview; not a player command. Trusted server-side code submits bounded observations and uses asynchronous queries. |
| Rich result hover and safe location actions | `/ig event`, `/ig explain`, `/ig trace`, paged lookups; click `[Go to ...]` | Shipped | `itemgraph.audit` plus the query's named permission; hover shows bounded evidence; the private, one-use location action expires after two minutes and rechecks query permissions. `/ig goto <token>` is internal and click-only; never type its token. |
| Player-broken container break audit event | `/ig lookup near <dimension> <x> <y> <z> <radius> CONTAINER_BREAK_COMPLETED` or `CONTAINER_BREAK_UNRESOLVED` | Shipped | `itemgraph.command` + `itemgraph.command.lookup`; returns the parent break event, not the slot list. Slot-removal observations are documented in the Partial row above; destination remains `UNKNOWN` without an authoritative drop link. |
| Bounded incident export and verification | No command is available yet; tracked by [#37](https://github.com/DurdeuVlad/itemgraph/issues/37) | Planned | Do not treat a database copy or raw file as a redacted incident bundle. The feature map will list its exact permission, bounds, redaction, and verification steps when shipped. |

For exact command syntax and defaults, continue to [Query model](QUERY_MODEL.md). For
parity gaps and work status, see [Feature parity inventory](FEATURE_PARITY_INVENTORY.md).
