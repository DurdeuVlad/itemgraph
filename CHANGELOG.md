# Changelog

All notable changes to ItemGraph will be documented here.

The project follows a simple pre-1.0 development changelog model.

## [Unreleased]

### Fixed

- **Inspection-mode denial and revocation handling** (`docs/UX_AUDIT.md` finding F3).
  On both NeoForge and Fabric, a click that detects a revoked
  `itemgraph.command.inspect` grant now disables inspection, reports "Inspection
  disabled: the itemgraph.command.inspect permission was revoked.", and is consumed
  instead of falling through to vanilla — previously that click could break the
  block being inspected. Requests denied for a missing downstream grant
  (`itemgraph.audit`, or `itemgraph.gui` on containers) are now consumed for
  left-clicks and non-container right-clicks so a denied inspection never mutates
  the scene; container right-clicks still fall through so vanilla chest access is
  preserved, and transient rejections (queue full, database unavailable) still
  preserve ordinary interaction.

### Added

- **Admin-facing UX audit** (`docs/UX_AUDIT.md`). Flux-UX review of the `/ig`
  command tree, in-game help, async query feedback, flow browser, in-world
  inspection, permission denials, paging/location actions, localization, and
  the operator docs. Records ten findings with implementation evidence —
  including silent acceptance/timeout/revocation gaps in the async query path
  and an inspect-mode revocation fall-through — plus a prioritized follow-up
  backlog. No behavior changes.

### Removed

- **GriefLogger-compatible artifact variants retired.** ItemGraph replaces
  GriefLogger and no longer ships `-grieflogger-compatible` jars or declares
  the GriefLogger mod a usable companion. Both loaders now declare the
  GriefLogger mod a hard conflict (NeoForge `type="incompatible"`, Fabric
  `breaks`), store listings mark the relation `incompatible`, and releases
  publish only the two standard jars. Migration path: remove the GriefLogger
  mod jar, keep its `database.db`, and use the unchanged opt-in read-only
  import (`grieflogger_integration_enabled` + `grieflogger_database_path`).

## [0.4.0-beta.1] — 2026-10-06

First public beta of the ItemGraph 0.4 line. The standalone GriefLogger
1.2.10-1.21.1 feature-parity milestone (M8) is complete on both loaders, and
this release also carries the M9 admin UX, named-permission, privacy, and
reliability work delivered since 0.3.2. Four artifacts are published:
standard and `-grieflogger-compatible` jars for NeoForge and Fabric. The
`-grieflogger-compatible` jars are the last coexistence artifacts — they are
deprecated, and from the next release ItemGraph ships only the standard jars
and declares itself incompatible with the GriefLogger mod. Prefer the
standard jar and remove the GriefLogger mod; keep its `database.db` file for
the optional read-only import. ItemGraph is pre-1.0 and this build is a beta:
the native-only production cutover (M10, issues #71/#72) and the open M9
extension issues are explicitly not claimed here.

### Upgrade notes

- The database migrates automatically from schema V12 to V21 on first start.
  Back up `itemgraph.db` (or the configured network database) before
  upgrading; migrations run in order and preserve existing evidence.
- Named `itemgraph.*` permission nodes are new; operator level 2 remains the
  fallback, so existing servers keep working unchanged. Delegated permission
  setups must additionally grant `itemgraph.audit` for `/ig event`,
  `/ig explain`, `/ig trace`, the flow browser, and location-sensitive
  lookups (kills, projectile throws/shots, chat and command provenance) —
  these queries are now denied without it.
- New optional language setting (`general.language` on NeoForge, `language`
  on Fabric) selects server-side `en_us`, `nl_nl`, or `zh_tw` message text;
  clients need no ItemGraph assets.
- The event taxonomy is version 4.0.0; integrations reading ItemGraph event
  IDs should review `docs/EVENT_TAXONOMY.md` before upgrading.

### For beta testers

Install on a test server, not production. ItemGraph is server-side; players
need no mod. Use the standard jar and remove the GriefLogger mod jar if the
server still runs it — ItemGraph replaces it, and this beta's
`-grieflogger-compatible` jars are the last coexistence artifacts. To keep
GriefLogger history, retain its `database.db` file for the import in step 10.
Fabric also needs Fabric API; NeoForge 21.1.x needs no extra dependency.
First start migrates the database to schema V21 (automatic; back up first).

Worth exercising, roughly in order:

1. `/ig` with no arguments — a five-line task overview. Follow
   `/ig help commands`, `/ig help journeys`, `/ig help permissions`.
2. Give a non-op test account only `itemgraph.command`: help and
   `/ig status` should work; `/ig lookup` should fail with the exact missing
   node named.
3. Drop an item and pick it up (NeoForge): `/ig trace item diamond` should
   show player → ground → player, and `/ig explain <edgeId>` prints the
   deterministic evidence behind the inferred edge.
4. `/give` yourself an item — the outcome appears as admin-mutation audit
   evidence with linked item observation IDs (`/ig event event:<uuid>`).
5. Fill a chest, then break it: `/ig inspect` on the position should show
   per-slot contents removed by the break.
6. `/ig inspect on`, click blocks and double chests — one paged timeline per
   position; `/ig inspect off` when done.
7. `/ig status` leads with an ACTION line; `/ig audit` reports invariant
   health (`HEALTHY` when satisfied).
8. `/ig lookup` accepts the direct GriefLogger filter form
   (`action`, `user`, `radius`, `time`, `include`/`exclude`, `page`), e.g.
   `/ig lookup action.remove_item user.<name> radius.10 time.1h`.
9. Set `general.language` (NeoForge) or `language` (Fabric) to `nl_nl` or
   `zh_tw` and restart — chat text localizes server-side.
10. Optional: point `grieflogger_database_path` at a copy of a real
    GriefLogger SQLite database with `grieflogger_integration_enabled=true`
    and confirm historical rows surface in lookups (import is read-only).

Known boundaries — not bugs, tracked under open issues:

- Fabric drop→pickup ground bridging still keys to spawn position; the
  NeoForge fix is shipped, Fabric follows under #166.
- Modded inventories/hopper-adjacent adapters beyond vanilla coverage (#34),
  world/environmental causes (#55), entity/projectile lifecycle detail (#56),
  trade/enchant/brew/smith/loot transformations (#57), `/ig export` (#37),
  first-class ambiguous/unresolved query surfaces (#44), and
  component-aware/absolute-time filters (#45) are open M9 work.
- Craft/smelt records keep output-only UNRESOLVED evidence by design (#162).
- `zh_cn` is not a selectable locale (#136 scope was en_us/nl_nl/zh_tw).
- CI performance numbers are regression evidence, not production budgets;
  staging-derived budgets are open under #32/#58.

Reporting: open a GitHub bug report with `0.4.0-beta.1` as the version, your
loader + Minecraft 1.21.1 + Java versions, the smallest reproduction that
shows the problem, and sanitized `/ig status` or command output. Do not
paste real player data, private coordinates, databases, or unredacted logs.

### Fixed

- **Drop ground endpoint at rest position (#166, NeoForge):** drop
  observations now record the `GROUND` node where the item entity settles —
  on-ground, near-zero velocity, removal, or a five-second cap — instead of
  the eye-height spawn point. Same-spot drop→pickup pairs now share one
  ground node, so the entity-UUID continuity path ranks the real pickup
  (live-verified at confidence 0.9990 with an `ACTIVE` inferred edge). The
  equivalent Fabric path keeps the defect; #166 stays open for it.
- **Complete admin permission recipes (#153):** `/ig help permissions` now states the root requirement and root-only status/help access, gives separate copyable grants for lookup, trace, event detail, explanations, inspection/history, direct and inspector container views, audit, ingest, and optional import, and documents the extra audit node for chat/command provenance. It explains explicit denies, level-2 fallback, non-inheriting dotted nodes, and points to the exact command matrix without inventing provider-specific grant syntax.

- **Honest craft and smelt evidence (#162):** result-slot callbacks now preserve the observed output stack, quantity, fingerprint, actor, position, and event UUID as protected `UNRESOLVED` audit evidence. They no longer invent ingredient fingerprints or create false trace edges. Long modded display metadata is safely truncated without changing the fingerprint. Legacy `CRAFT`/`SMELT` rows are historical-only unresolved evidence and no longer display a source-to-result arrow. The differential profile and replay normalizer recognize the new output-only event; expensive M9 replay/load/export checks remain deferred to milestone closeout. Help and the quick start explain the lookup and limits.
- **First-time admin help/status UX (#153):** `/ig help commands` is a compact task hub with topic continuation; focused lookup topics cover admin item commands, entity/projectile lifecycle, and supported transformations. Journey help defines OBSERVED, INFERRED, AMBIGUOUS, UNRESOLVED, and PROVENANCE_ONLY with source-correct follow-up paths and exact grants. Filtered lookup distinguishes `observation#N` IDs accepted by `/ig event N` from audit, transformation, and imported references that remain inline; trace/explain, GUI, and saved-page requirements are explicit. Sensitive-location and staff-activity lookup types enforce `itemgraph.audit` from the shared event taxonomy, including saved pages and filtered lookups. Section 4 demonstrates only a short read-only path; broad parser cases live in an explicitly non-executable test corpus so placeholder IDs and stateful/import commands are not presented as a runnable incident. Incident export remains marked unavailable until #37 ships.
- **Projectile location privacy (taxonomy 4.0.0):** `THROW_ITEM` and `SHOOT_ITEM` expose player coordinates, so direct, paged, and filtered audit queries now require `itemgraph.audit`, as `KILL_ENTITY` and accepted projectile spawns already do. Taxonomy major version reflects the stricter privacy contract.
- **Issue #33 command-to-item evidence links:** admin and creative outcomes now list bounded UUIDs for accepted item observations, transformations, and unresolved before/after audit records. `/ig event event:<uuid>` opens each evidence type under the existing event and audit permissions; transformation details distinguish the affected player/entity from the command actor. Outcomes say `LINKED`, `PARTIAL_LINK_LIST`, `UNRESOLVED_EVIDENCE_RECORDED`, or `NO_ITEM_EVIDENCE_RECORDED` according to the captured records.
- **Flow-browser row labels (#146):** display only the observed action or transformation kind in the numbered companion; internal parent, break, and evidence UUIDs remain out of the concise event label. Numbered companion entries map left-to-right to the first menu row, and each selectable item hover names its exact slot. Known transformation-related fingerprints use their item icon. The admin guide explains how to map entries to slots and read hovered detail-page fields.
- **Protected evidence query gating:** `/ig event`, `/ig explain`, `/ig trace`, and the flow browser now require `itemgraph.audit` together with their existing leaf permission before any query or candidate lookup. Async delivery, GUI pages/details, menu validity/clicks, and page-derived location tokens retain and recheck the complete permission set, preventing protected observation or inferred-edge metadata from leaking through candidates, counts, errors, or navigation. Explain hover detail is attached only to its inferred summary line; cited observation rows keep their observed wording.
- **Issue #140 empty-container and unavailable-actor semantics:** an empty successful snapshot now records only its zero-content completion, without an unresolved ground-drop relation. `CONTAINER_BREAK_UNRESOLVED` is taxonomy actor `UNKNOWN`; lookup labels missing player identity `(actor unavailable)` rather than attributing the event to a player. The redacted conformance report and validator now require drop-link outcomes only for nonempty breaks.
- **Status capture and recovery clarity (#155):** `/ig status` now distinguishes disabled capture, active intake, recovery loading, recovery failure, and a stopped worker, and reports an unknown recovery count when the spool has not been read or could not be read. Runtime diagnostics print before asynchronous database statistics, so a failed stats query cannot hide capture and queue state. Help and the admin guide define the state meanings, counter units, reset windows where known, and safe next steps without inventing health thresholds.
- **First-time admin workflows (#156–#158):** `/ig help journeys` links four detailed inspect, trace, nearby-audit, and filtered-lookup topics. Each gives exact permission nodes, evidence meaning, a follow-up command, and safe checks for empty results. Trace observations use numeric IDs; `event:<uuid>` is for related IDs from admin/creative outcome details. Ingest help describes GriefLogger sync as optional and native capture as automatic. The quick start adds complete permission bundles and documents shipped feature boundaries without transient PR/CI status.
- Unsupported or unknown transformation rows no longer appear as observed item-trace hops. Unified lookup keeps their evidence ID, source/result fingerprints, unresolved reason, and unknown quantity without rendering free-form legacy details that could look like a numeric quantity. Existing raw database rows are unchanged.
- **Issue #24 right-click inspector:** NeoForge and Fabric now ignore off-hand right-clicks while inspection mode is active and resolve ordinary block clicks to the clicked-face neighbor. ItemGraph keeps its documented clicked-position behavior for functional blocks, and clicked-position support for modded `Container` block entities is an extension; GriefLogger 1.2.10's implementation uses this fallback for functional blocks other than doors and containers. Inspection still consumes the interaction only when ItemGraph accepts the asynchronous history request.
- **Issue #24 command packet test timing:** aligned the NeoForge and Fabric GameTest outer limit at 4,000 ticks so fast headless tick progression cannot preempt the fixture's 10-second wall-clock durability deadline. This corrects the CI-only timeout while retaining the bounded failure deadline.
- **Issue #32 shutdown durability:** shutdown waits are bounded at 5 seconds graceful plus 5 seconds after interruption for each database worker, 5 seconds for an ItemGraph-owned recovery-file writer, and 1 second for JDBC connection close. Startup replay and shutdown recovery-file I/O run on daemon workers, not the loader lifecycle callback. New events remain bounded in the normal queues during replay, and recovered rows are placed before them. A completed snapshot replays idempotently by `ingest_event_uuid`; corrupt or unsupported primary recovery data is preserved, intake closes, and already-accepted records are saved to an adjacent `.overflow` recovery file without replacing the primary. Both files replay after the primary file is repaired. If the filesystem does not finish a snapshot within its deadline, ItemGraph logs critical evidence risk and counts the outstanding records; a late daemon write may finish only if the process remains alive and the filesystem returns.
- **Issue #32 performance evidence:** correlation failures returned as result values now increment the failure counter. The MySQL and MariaDB CI probes now execute 20 registered `/ig lookup` commands alongside 20 raw-JDBC readers and 512 submitted events on both loaders, recording callback completion and p95/max latency. CI also enforces a 1,000 ms drain regression budget for the healthy NeoForge SQLite 10,000-event saturation fixture, based on five repeated local measurements.
- **Issue #32 idle baseline:** CI adds one-second live tick-hook no-input samples for both loaders against SQLite and worker-only samples against MySQL and MariaDB. The validator requires zero queue, persistence, lookup, correlation, rejection, and heartbeat work; reports compare durable observation, audit, and transformation row counts and capture heap at the sample boundary. Network-backend samples do not represent live server ticks or production budgets.
- **Expired lookup-page cleanup (#24):** following an expired explicit `/ig page <page> <session>` link now removes the player's empty page-session map. Fabric dispatcher coverage exercises both command roots, invalid/expired sessions, cross-player tokens, denied permission, and error-only output; NeoForge page-session regressions remain green.

### Added

- **Offline server-side localization (#136):** NeoForge `general.language` and Fabric `language` select ItemGraph-owned `en_us`, `nl_nl`, or `zh_tw` resources before database initialization. NeoForge and Fabric reject unsupported values with a `general.language` diagnostic; rendered chat/menu text uses server-side literals, so clients need no ItemGraph assets or network access. English per-key fallback preserves unknown/future messages. The key fallback inventory is empty for `nl_nl` and `zh_tw`; 125 authored source phrases use the tested English fallback, including detailed help topics. Evidence IDs, item IDs/names, timestamps, quantities, evidence-class tokens, and click command payloads remain data values. The exact GriefLogger 1.2.10-1.21.1 fixture has no locale inventory; `en_us`, `nl_nl`, and `zh_tw` are only verified in pinned 26.2 source research, and `zh_cn` is source-only/not selectable.

- **First-use admin workflows (#148–#150):** bare `/ig` now presents a five-message task-first overview; `/ig help guide` opens the server setup guide, `/ig help commands` groups command paths, and `/ig help permissions` documents exact access nodes. The guide puts loader choice, server-only installation, permissions, and safe test-world verification before investigations, then explains query surfaces, confidence, and concrete evidence gaps. README and the local CurseForge draft now agree on platform and permission behavior.

- **Structured query chat and scannable flow-browser rows (#138, #146):** query output keeps the stable `QueryFormatter` text while adding bounded hover details and player-only dimension-aware navigation bound to the exact originating permission set. Flow-browser timelines and candidate lists use up to nine rows per page, further capped by `query.max_page_size`, and send numbered companion lines with observed/inferred class, safe identity, event kind, and UTC time where recorded; missing identities and unresolved targets are labeled explicitly. No raw NBT or component payloads are exposed. These authored labels resolve through the selected ItemGraph locale, and evidence values remain data.
- **Named command permissions (#137):** `/ig` and `/itemgraph` keep vanilla permission level 2 as the fallback, while exact `itemgraph.*` nodes let providers delegate lookup, paging, inspection, traces, events, explanations, audits, GUI browsing, ingest, and import separately. Explicit deny overrides operator status. NeoForge uses registered permission nodes; Fabric embeds Permissions API 0.3.1 without requiring an external permission manager. Async results, inspection clicks, every flow-menu action, and protected audit lookup/page continuations recheck the required node set. Lookup-only access cannot read chat, command, administrative-item, or creative-action audit records. `/ig help permissions` explains delegation.
- **Player-broken container contents (#140):** successful Fabric and NeoForge
  player breaks now capture the exact non-empty block-entity slots as per-slot
  `REMOVE_ITEM` evidence with canonical fingerprints and stable parent/child event
  IDs, plus a deterministic link to the matching `BREAK_BLOCK` audit row. The
  completion summary and its slot rows persist atomically in one transaction; a
  slot-write failure rolls back the whole group. The destroyed container is the source and the destination remains unknown;
  the player is not assumed to receive the contents. Empty snapshots have no unresolved drop-link row; nonempty parent, unresolved drop-link,
  and slot rows share bounded queue admission. Tests include empty, one-stack,
  full single-chest, and double-chest-half cases. Nonempty drop relationships remain unresolved
  until an authoritative hook can establish them. The NeoForge result wrapper is
  narrowly scoped to `ServerPlayerGameMode.destroyBlock` because its 1.21.1 break
  event runs before mutation. The additive event IDs advance the independent event
  taxonomy to 2.1.0.

- **Standalone historical player-name resolution (#31):** native `PLAYER_JOIN`
  evidence now populates ItemGraph's UUID-keyed `ig_player_name_history` table.
  Native audit user filters and suggestions resolve names recorded on prior
  joins to every matching UUID without requiring GriefLogger. Imported
  GriefLogger history remains an optional source. The native-only replay now
  includes redacted `PLAYER_JOIN` signals so CI marks both the sessions and
  usernames categories exercised without exporting names or UUIDs. Names
  changed during an online session are not captured until a later join.

- **Issue #31 exact-release action-table replay:** the redacted ItemGraph-only
  NeoForge and Fabric reports now include separate `chats` and `commands`
  categories, one player quit, one consumed item, one durability break, and one
  `CRAFT` transformation, in addition to the existing movement, projectile,
  block, container, and entity evidence. Each loader report validates 25 events
  against the pinned release profile and represents all six GriefLogger event
  tables. Chat and command text, player names, UUIDs, absolute world coordinates, and
  database row IDs remain excluded from the reports. Other ItemGraph extensions
  stay explicitly assigned to their existing issues.

- **Issue #31 pickup coordinate rationale:** Documented the loader-specific
  drop hooks used by the replay fixtures. NeoForge's patched two-argument
  `Player.drop` fires `ItemTossEvent`; its three-argument overload bypasses that
  hook. Fabric captures the accepted entity through its three-argument drop
  path. The normalized replay records pickup Y=2 on NeoForge and Y=1 on Fabric;
  the exact fixture-level cause remains unverified because both adapters store
  block coordinates and the report omits sub-block positions.
- **Issue #31 replay queue evidence:** NeoForge and Fabric native replay reports
  now include each ingestion queue's export-time depth, fixed capacity, and the
  cumulative server-wide rejected-event counter since service initialization.
  The normalizer rejects over-capacity queue depths, capacity drift, or any
  server-wide rejection since service initialization. Raw report schema is v5
  and normalized comparison schema is v6; this
  complements the separate 8,000-event peak-backlog/load probe.
- **Issue #31 unresolved historical actions:** GriefLogger action IDs are checked
  against the source table's action map without narrowing; raw payloads and
  unresolved reasons retain larger IDs exactly, so table-
  invalid, oversized, and non-integral malformed IDs remain importable as
  unresolved evidence, with original values retained in the raw payload. The
  lookup projection no longer exposes an unknown or missing source action's amount
  or item-type ID as event semantics. The immutable import ledger retains the action,
  complete payload, and component bytes; the projection reports quantity `0`, no
  `subject_id`, `UNRESOLVED`, and the original raw-byte hash. Added an importer
  regression fixture with action ID `999` and malformed component bytes that also
  verifies the source database remains byte-for-byte unchanged.
- **Issue #31 source contradiction fixtures:** added a machine-readable corpus
  for the required-radius documentation/source difference, GriefLogger's stored
  chat/command rows versus its lookup surface, and newer Ender enum values absent
  from the exact 1.21.1 release writers. CI checks the pinned source identity,
  duplicate-free case IDs, exact owner issues and decisions, and declared
  Java-method or documentation anchors.
- **Issue #31 release-contract coverage inventory:** CI now emits a redacted
  sidecar beside each native-only NeoForge and Fabric replay. Schema v2 lists
  every compatibility-registry action and all eleven exact-release database
  table families, with replay counts separate from exact-release writer
  dispositions. It identifies the absence of a dedicated native
  username-history table as an uncovered standalone query category. The
  report distinguishes unobserved categories from unsupported release writers
  without claiming full runtime parity. Tests and CI do not package
  distributable mod jars.
- **Issue #127 native-only default:** NeoForge and Fabric no longer inspect a
  `database.db` file for GriefLogger data during normal operation. Read-only
  source sync and historical import require the explicit
  `grieflogger_integration_enabled=true` setting. Native capture, storage,
  correlation, and queries remain independent, and scheduled native-only ticks
  do not report the disabled source sync as an error.
- **Issue #32 operational metrics and queue probes:** `/ig status` reports
  redacted enqueue, persistence, query, correlation, queue-pressure, component
  decode-cache, and heap aggregates. CI emits validated 14-day reports for both
  loaders on SQLite and on disposable MySQL/MariaDB, plus a NeoForge queue
  saturation/shutdown durability report. Network probes run 20 read-only ledger
  count queries across four readers against 512 synthetic events; the shutdown
  probe persists all 10,000 accepted events in bounded worker batches and counts
  one rejected over-capacity event explicitly. Reports contain aggregate values
  without event or player identifiers. The real adapter workloads and
  staging-derived production budgets remain open under #32.
- **Issue #32 report provenance:** performance report fixtures record
  `grieflogger_runtime_state` as `absent`, `present`, or `unavailable` instead
  of treating an uninitialized NeoForge JUnit mod list as proof of absence.
  Loader GameTests assert GriefLogger is absent; CI rejects `present` reports
  and limits `unavailable` to NeoForge JUnit-only probes. The report schema is
  version 2.
- **Issue #32 malformed-component workload:** the SQLite correlation probes feed
  one malformed serialized component payload to the canonicalizer 500 times and
  record the first-decode and total elapsed time. CI requires one negative-cache
  insertion, 499 cache hits, and the same opaque unresolved fingerprint for every
  repetition. The decoder diagnostic now names the serialized component payload,
  independent of the optional historical importer.
- **Issue #33 administrative item evidence:** both loaders capture `/give`,
  `/clear`, `/item` slot mutations, creative inventory slot changes, accepted
  `/give` overflow, and negative-slot creative drops at vanilla mutation
  boundaries. Creative block placement and destruction store authoritative
  `CREATIVE_BLOCK_RESULT` events and explicitly report zero player-inventory
  quantity delta. Paired GameTests cover creative placement and destruction plus
  linked invalid-item failure outcomes. Attempts, outcomes, and observed deltas
  share mutation IDs; `/item modify` persists explicit canonical transformations,
  while creative packet and `/item replace` stack changes persist separate removal
  and creation deltas without inferring a causal transformation. Bounded or failed
  `/give` recipient snapshots retain an unresolved outcome and cannot fall through
  to player-drop attribution; accepted overflow still records exact ground-output
  evidence by target UUID, while rejected overflow records item, quantity,
  fingerprint, and entity identity as unresolved evidence, including canceled
  tosses. Unrelated players' drops remain independently
  capturable during the command. `/item ... from block/entity` evidence
  retains the copied-from slot and stack without treating a copy as source removal.
  Nested `/execute as` outcomes keep the original issuer as actor and store a differing
  effective entity as execution-context evidence. The implementation leaves clone
  versus pick-block undifferentiated, records empty slots as unresolved before/after
  evidence, keeps staff activity private, suppresses raw item-command and `/execute`
  arguments, and contains capture exceptions without changing vanilla results.

- **Issue #35 shared event taxonomy:** introduced the versioned `EventTaxonomy`
  for native audit, item observation, and transformation IDs. Audit command
  types and unified lookup aliases now use the shared registry. Definitions
  specify evidence class, capture reliability, endpoint and quantity semantics,
  actor status, privacy class, loader support, evidence-ID contract, aliases,
  and owning issue. #55–#57 event families and stable unresolved reason codes
  are listed as planned until loader adapters and fixtures prove support. This
  is a taxonomy version only.
- **Creative transformation evidence boundary:** taxonomy `2.0.0` retains
  `CREATIVE_ITEM_TRANSFORM` for classification but marks it unsupported on both
  loaders with `CREATIVE_TRANSFORM_CAUSE_NOT_REPORTED`. Creative slot packets
  prove separate quantity deltas, not conversion between item fingerprints, so
  the ID retains `UNRESOLVED` evidence with `UNKNOWN` quantity and no longer
  appears in lookup suggestions.
- **Issue #31 native replay reports:** NeoForge and Fabric GameTests export six
  durably verified item movement/projectile rows plus ten allowlisted audit
  events, including `PLACE_BLOCK`, `INTERACT_BLOCK_ATTEMPT`, and `KILL_ENTITY`,
  with namespaced entity/block `subject_id`, and a count-only `AuditService`
  whole-database invariant summary to redacted raw schema-v5
  reports when CI sets `ITEMGRAPH_DIFFERENTIAL_REPORT_DIR`. The mock GameTest
  establishes the target world states, then dispatches each registered loader
  event or placement capture handler exactly once. The audit uses one
  read-only transaction snapshot and rejects over-capacity observations,
  mismatched per-edge SOURCE/DESTINATION sums, unsupported roles, and
  allocations detached from direct evidence, matching fingerprints/actions, or
  actor endpoints. It rejects edge timestamps that do not match forward source
  and destination evidence. The CI normalizer independently rejects non-zero violation
  counts; Java and Python gates pin action/subject pairs and the three new
  block-action positions. Normalized schema-v6 output retains the validated
  count-only summary, preserves subject IDs, and exposes the issue-linked exception gate. Registry
  compatibility version is `m8.12.0`.
  These native-only reports do not claim a GriefLogger comparison or complete
  #31's paired replay, staging soak, or rollback gates.
- **Issue #31 parity fixture correction:** the replay report includes only the
  successful source-water bucket pickup among `BREAK_BLOCK` rows. It filters by
  the exact fixture position and `minecraft:water`, excluding the separate
  synthetic water-source/lava-result guard probe, which cannot occur in an actual
  GriefLogger bucket-pickup writer path. The current raw report is schema-v5 and
  normalized output is schema-v6.
- **Issue #31 issue-linked differential exceptions:** normalized schema-v6
  keeps every report difference visible, classifies only profile-declared
  native extensions as expected only when their source table also matches the
  pinned policy, and links each to its owning issue and stable reason code.
  `equivalent` remains false when any difference exists; CI passes
  only when there are no unexplained differences. Quantity, timestamp,
  endpoint, privacy, and evidence-class mismatches remain unexplained. Registry
  compatibility version is `m8.12.0`.
- **Issue #30 queue flush cadence:** both loader configs now expose
  `ingestion.queue_frequency_ticks` / `queue_frequency_ticks`, defaulting to 20
  and accepting 1–100 ticks to match GriefLogger's `queueFrequency`. Both server
  end-tick adapters signal the bounded background writer; queue submissions still
  do no database work on the server thread. ItemGraph raw evidence retention stays
  indefinite. Added live queue-flush GameTests to both loaders' registered CI
  suites.
- **Issue #24 Fabric inspector command execution:** `FabricItemGraphCommandsParityTest`
  now executes both command roots through enabled, already-enabled, status,
  disabled, already-disabled, and toggle-back states, asserting the per-player
  state and exact chat receipts. Permission-denied execution emits no success
  receipt and does not enable inspection. This is dispatcher-level coverage;
  it does not replace the pending vanilla-client command replay.
- **Issue #27 bucket fluid removal evidence:** NeoForge and Fabric now record a
  `BREAK_BLOCK` audit row when a server bucket pickup successfully removes a
  source fluid block. Both hooks wrap `BucketPickup.pickupBlock`, require a
  non-empty returned `BucketItem`, preserve the source block coordinates, and
  use the returned bucket's contained fluid for the fluid block ID. They write
  no item quantity observation. Shared loader GameTests verify the durable row
  and quantity ledger boundary. Registry compatibility version is
  `m8.9.0`.
- **Issue #27 action registry and exact-release writer matrix:** registry
  `m8.8.0` now records the pinned source enum and ID for all 18 GriefLogger
  actions plus the exact `1.2.10-1.21.1` writer result. CI rejects malformed
  boolean/float IDs and duplicate JSON keys, and checks action-specific JVM
  enum-field accesses in both official loader artifacts. `INTERACT_BLOCK` is
  compatible at the attempt-only boundary proven by #74; `INTERACT_ENTITY`
  has no action ID or writer in the target release and remains a separately
  labeled ItemGraph extension per #75. Ender actions remain `unsupported-no-writer`
  per #76.
- **Issue #27 projectile runtime conformance:** NeoForge and Fabric GameTests
  now call the real `Projectile.shootFromRotation` and `ServerLevel.addFreshEntity`
  boundaries for one snowball and one arrow. Read-only SQLite assertions require
  one quantity row per attempt, player-to-unknown endpoints, matching source event
  IDs in the legacy audit projections, accepted-spawn item/action identity,
  distinct accepted-spawn evidence, and no
  extra quantity rows for the test player or mutation of prior quantity rows.
  The test uses embedded mock players and does not establish vanilla-client
  transport or bow-ammunition accounting.
- **Issue #27 item movement runtime conformance:** both loader GameTests now
  exercise real chest-menu quick-moves and player inventory removal/drop/pickup.
  The shared read-only SQLite fixture requires exactly one `ADD_ITEM`, `REMOVE_ITEM`,
  `DROP_ITEM`, and `PICKUP_ITEM` row with expected registry IDs, quantities,
  endpoints, coordinates, metadata fingerprints, session intervals, and shared
  dropped-entity identity across drop/pickup. NeoForge uses the two-argument
  `Player.drop` path that posts `ItemTossEvent`; Fabric exercises its accepted
  `ServerPlayer.drop`/`ServerLevel.addFreshEntity` mixin path. Both assert that
  inventory counts before and after each transfer. The fixture also requires
  the exact spawned entity UUID, rejects any fifth observation for the replay
  player regardless of action/source, and verifies prior quantity rows remain
  unchanged. Ground evidence coordinates may differ by one block across the
  server tick. The remaining #27 writer actions still need paired runtime
  fixtures.
- **Issue #75 cross-loader interaction evidence:** non-armor-stand callbacks now carry stable `target_support=callback_only` and `target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT` metadata, while armor-stand rows identify their supported method-result boundary. Callback-level denied/unresolved outcomes remain recorded where observed. NeoForge and Fabric isolated server GameTests send entity-use packets through each loader's server handler for unsupported-entity attempts and armor-stand boot equip/unequip. They separately call inherited `ArmorStand.interact` to verify its `PASS` return hook and persisted unresolved method result. Both tests compare the complete `ig_observations` row snapshots before and after, and check the same normalized audit query and `QueryFormatter` output used by `/ig lookup`. CI runs both loader GameTests without creating distributable mod jars.
- **Issue #76 Ender action compatibility:** the release fixture validator now checksum-verifies both published 1.2.10-1.21.1 jars and finds no Ender action constant field references outside `ItemAction.class`; its `values()` calls are limited to the generic `Actions` catalog and the enum's own `fromId` decoder, and the only external `fromId` caller is `ItemHistory.<init>` reconstructing stored rows. The registry marks both GriefLogger actions `unsupported-no-writer` using `NO_WRITER_IN_EXACT_1_2_10_1_21_1_RELEASE`; existing ItemGraph session net deltas stay labeled as `ITEMGRAPH_INTERNAL` extensions. Regression coverage checks duplicate opens, reconnects, partial signed counts, atomic queue rejection/retry including orderly shutdown under one shared five-second retry deadline, opaque component redaction, no-net sessions, and restart persistence. Registry compatibility version is `m8.7.0`.
- **Issue #24 command contract:** `/ig` and `/itemgraph` expose the same level-2 command tree; GriefLogger's published direct `name.value` lookup syntax, six filter names and one-letter aliases, required cubic radius, five-filter limit, AND combination, ten-row page default, per-player pages, and page navigation are covered across NeoForge and Fabric tests. The selected published-doc radius requirement is explicit because GriefLogger 26.2 source accepts no-radius lookup. Output retains ItemGraph evidence labels and provenance; `/gl` and `/grieflogger` remain unregistered.
- **Issue #24 lookup filter boundary coverage:** Fabric and NeoForge now parse an exactly-five-filter valid lookup and execute both `/ig` and `/itemgraph` error cases through both direct and `lookup filters` forms for missing radius, malformed and invalid radius tokens, unknown and duplicate filters, include/exclude conflicts, and a sixth filter. Both roots return the same exact single failure before query output; runtime behavior is unchanged.
- **Historical user filter completion (#24):** `/ig lookup user.<name>` suggestions now include distinct names from imported GriefLogger `users` and `usernames` reference rows as well as online players. ItemGraph reads its own preserved provenance rows asynchronously through the bounded read-only query worker, caches the result for 30 seconds, and skips malformed payloads. Read-only queries now instrument JDBC statements with a five-second timeout and cancellation; SQLite keeps its progress-handler and connection-interrupt support.
- **Issue #26 block inspector evidence timeline:** `/ig inspect` now opens one exact-position, globally paginated timeline for block audit events, container item deltas, transformations, and imported GriefLogger event rows. Double chests and doors resolve to their physical target cells without duplicate rows; a container observation matches either endpoint, so a transfer is found even when the player endpoint is stored first. Schema V19 records block-removal supersession links for native and imported interaction evidence while keeping every source row immutable. Imported row links use a SHA-256 key and retain the full source key. Ordinary lookup still returns superseded rows with the replacement evidence ID and reason. Both loaders consume an inspection click only after the bounded asynchronous query is accepted; queue rejection preserves normal gameplay. Live client/server matrix verification remains pending.
- **Issue #30 configuration baseline:** added cross-loader page-cap, bounded
  queue idle-poll/batch, native-capture, server-only, and indefinite-retention
  settings and a configurable 30-second MySQL/MariaDB connection keepalive on
  the background worker. `/ig status` reports backend, schema, effective query
  cap, connection timeout, index policy, and queue controls while omitting
  database paths and raw exception details. A temporary loopback-only NeoForge
  21.1.248 server and Fabric Loader 0.16.9 server both confirmed that `/reload`
  leaves startup-snapshot controls unchanged and restart applies representative
  edited values; both status responses showed page cap 25, timeout 5,000 ms,
  indexes enabled, and the expected queue controls. The cross-loader config
  tests check Fabric startup snapshots and NeoForge worker-stop application;
  rejected live application leaves the query cap unchanged. CI exercises the
  worker heartbeat against both MySQL and MariaDB. GriefLogger queue cadence
  equivalence remains unverified because its scheduled flush and ItemGraph's
  signal-driven queue have different semantics. Local NeoForge-only startup,
  schema upgrade, and 2,000-record SQLite worker load passed. See
  `docs/CONFIGURATION.md` and `docs/TEST_PLAN.md`.
- **Issue #30 blank database path validation:** NeoForge and Fabric now reject
  empty or whitespace-only ItemGraph and GriefLogger database paths with the
  exact config key before applying operational settings or opening ItemGraph
  storage. NeoForge startup CI probes verify both empty paths fail before DB
  initialization and preserve the invalid TOML values.
- **Issue #30 configuration error keys:** network database settings now report
  their `general.*` key when rejecting blank host/name/username, invalid port or
  timeout, and unsupported TLS mode. Fabric also names invalid database backend
  and boolean settings with the shared NeoForge config key. Errors do not expose
  database passwords or echo invalid backend text. Network connection logs and
  failures omit endpoint fields and raw JDBC exception text.
- **Issue #30 remove unused debug logging setting:** removed the advertised
  `debug_logging` config value because it did not enable any logging behavior on
  either loader. The configuration reference no longer describes a no-op control.
- **Issue #30 fail-closed NeoForge config validation:** numeric settings now
  reject invalid types and out-of-range values with the full config key before
  NeoForge can clamp them; string, boolean, and integer values retain their
  native config metadata. Fresh GameTest startup probes confirm
  `query.max_page_size=101` and `general.database_port=0` fail before database
  initialization and remain unchanged in TOML. This closes the reproduced
  `database_port=0` to `1` normalization gap; full invalid-config matrix
  coverage across both loaders remains open.
- **Issue #30 Fabric numeric config parity:** Fabric now validates all seven
  documented integer settings, including database port and connection timeout
  even when SQLite is selected. Unit coverage accepts both endpoints and
  rejects below-range, above-range, and non-integer values with the full config
  key and rejected input. Non-numeric malformed-config combinations and
  GriefLogger queue-cadence evidence remain open.
- **Evidence and queue failure handling:** legacy topology migrations now copy
  observations, referenced fingerprint values, and raw payloads into
  `ig_legacy_observation_evidence`
  before removing obsolete endpoint projections. Failed transformation batches
  are returned to the bounded retry queue, included in pending counts, and
  explicitly counted as evidence loss if shutdown cannot persist them.
- **Idempotent ingestion retries:** schema V18 adds unique queue-event UUIDs to
  observation, transformation, and audit ledgers. A retry after a database
  commit whose acknowledgement was lost now resolves to the original row
  instead of duplicating evidence or quantity.
- **Configurable storage indexes (issue #29):** `storage.use_indexes` on NeoForge
  and `use_indexes` in Fabric's properties file now default to `true`. On every
  startup, ItemGraph creates any missing optional lookup
  indexes or drops only those optional non-unique indexes when disabled. Existing
  databases support either toggle direction after restart; required unique
  deduplication indexes remain active, and MySQL/MariaDB retain indexes needed
  to support foreign keys. Invalid Fabric boolean values fail config
  loading with the offending key named.

### Changed

- **Entity interaction outcomes (#75):** both loaders retain entity-use attempts with target UUID when available, held item ID/count/fingerprint hash, and explicit completion coverage. Armor-stand return hooks cover the `ArmorStand.interactAt` override and inherited `Entity.interact` fallback (required because 1.21.1 `ArmorStand` inherits `interact`): consuming results become `INTERACT_ENTITY_COMPLETED`, `FAIL` becomes `INTERACT_ENTITY_DENIED`, and fallback `Entity.interact` `PASS` becomes `INTERACT_ENTITY_UNRESOLVED` without claiming the full pipeline ended. NeoForge callback cancellations are retained. Fabric decorates the aggregate `UseEntityCallback` invoker once, preserving callback order and short-circuiting while recording one final non-`PASS` result, including cancellations before or after ItemGraph's listener. Non-armor-stand targets have no method-result hook; their rows identify `target_support=callback_only` with stable reason `ENTITY_CLASS_UNSUPPORTED_FOR_RESULT`, while observed callback-level denied/unresolved results remain recordable. Removed the prior pre-use armor-stand equip/unequip quantity rows because they could claim item movement before the game accepted it. A handled return is not proof of equipment movement. The exact GriefLogger 1.2.10-1.21.1 artifact has no entity-interaction writer; this ItemGraph behavior is documented as an extension, with the newer 26.2 source behavior kept distinct.
- **Block interaction evidence parity (#74):** native block-interaction capture on Fabric and NeoForge now follows GriefLogger 1.2.10-1.21.1's main-hand gate and exact 28-class functional-block set. Rows remain `INTERACT_BLOCK_ATTEMPT` because the reference hook runs before use results; modded `Container` inspection remains separate from this action mapping.
- **Long GriefLogger primary keys:** imported source keys over 191 Unicode
  codepoints now use a deterministic SHA-256 key over length-framed UTF-8 key
  values so MySQL/MariaDB composite indexes accept them without delimiter
  ambiguity; the immutable payload preserves all original key fields.
  The historical ledger and normalized lookup projection use the same key,
  while pre-change SQLite checkpoints resume using their original key format.
- **Loader runtime hardening:** Fabric pickup capture no longer exposes a
  non-private mixin helper or nested record that Mixin remaps as a Minecraft
  inner class. Native lookup event types now use vanilla literal command nodes,
  preserving direct GriefLogger filter syntax while allowing NeoForge and Fabric
  operators to receive the command tree without a disconnect. Connected-player
  staging replays verified one durable throw and shoot row plus one accepted
  spawn audit row for each loader.
- **One storage contract for SQLite and MySQL/MariaDB:** ItemGraph now exposes
  validated backend settings on NeoForge and Fabric, runs the shared migrations
  and JDBC queries through one dialect layer, preserves SQLite partial-dedup
  semantics with generated sentinel columns on network databases, and bundles
  MariaDB Connector/J 3.5.7. CI provisions MariaDB 10.11 and MySQL 8.0 for the
  same migration, upsert, constraint, and read-only integration contract. Issue
  #29 remains open until hosted CI completes its full acceptance run.
- **Network storage safety:** non-loopback MySQL/MariaDB connections configured
  with `database_ssl_mode=disable` now emit an explicit plaintext-transport
  warning; production deployments should use `verify-full` or `verify-ca`.
- **Compatibility profile m8.2.1:** projectile action rows now preserve observed stack counts without claiming a landing location; the direct lookup projection is paired with accepted observation enqueueing and the unified lookup suppresses it only when the shared raw event identity is durably persisted.
- **Observation retry integrity:** failed internal observation transactions are retained in the bounded queue with backoff, and shutdown failures are counted as dropped evidence instead of being reported as persisted.
- **GriefLogger parity profile:** pins the audited GriefLogger 26.2 source commit, records all 18 source actions and 11 source tables, separates ItemGraph-only transformations and automation from true source actions, and links the M8/M9 delivery issues for unresolved parity and audit++ work.
- **Compatibility profile CI gate:** adds the read-only `tools/validate_grieflogger_profile.py` check, a canonical source-profile SHA-256, pinned source-file citations, and milestone-reference validation before the loader build runs.
- **Exact GriefLogger 1.21.1 release fixture:** pins the official Fabric and NeoForge `1.2.10-1.21.1` artifact IDs, URLs, byte sizes, SHA-1/SHA-256/SHA-512 digests, manifests, Java 21 mixin contracts, embedded JDBC versions, action IDs, schema columns, commands, configuration defaults, and inspector behavior. CI can download and verify both published jars with `tools/validate_grieflogger_release_fixture.py`; no GriefLogger jar is vendored.
- **Compatibility profile contract hardening:** the CI validator now locks every published action's ItemGraph mapping, status, evidence class, quantity semantics, and loader set, and it requires the filtered-lookup owner issue (#25) in the parity and milestone records.
- **Native entity and Ender coverage:** NeoForge and Fabric now retain server-side `INTERACT_ENTITY` attempts, and Ender Chest menu sessions emit signed `ADD_ITEM_ENDER`/`REMOVE_ITEM_ENDER` deltas to a durable player-owned external-inventory node without polling or permanent item UUIDs. Successful entity-outcome parity remains tracked by #27.
- **Read-only historical import foundation:** `GriefLoggerHistoricalImporter` now preserves all 11 pinned GriefLogger tables in ItemGraph-owned provenance tables with source/schema fingerprints, bounded resumable checkpoints, deterministic primary-key/rowid/hash identities, opaque binary payload retention, unknown-action reasons, supported-schema rejection, independent writer batches, durable failed-run counts, and idempotent replay. It never writes to the GriefLogger database; projecting the generic historical ledger into unified lookup remains tracked by #28, while #25 covers the completed native/normalized lookup contract.
- **Historical GriefLogger lookup projection:** migration v15 adds a rebuildable `ig_grieflogger_lookup` index for `items`, `containers`, `blocks`, `sessions`, `chats`, and `commands`. Imported rows preserve table/key provenance, actor identity with historical username resolution, level/material/entity references, action IDs, quantities, opaque-byte hashes, and explicit unresolved status. Unified filtered lookup now merges the projection, while exact source/table/key provenance lookup exposes reference and identity rows without treating them as item quantities.
- **Historical projection recovery:** repeat imports revisit checkpointed source rows to backfill a missing v15 projection, and unified timelines select the latest completed source snapshot while retaining older snapshots for exact provenance review.
- **Native audit ledger:** ItemGraph now has its own `ig_audit_events` schema and native NeoForge/Fabric capture paths for supported player session, chat, block, entity, and command-attempt actions. NeoForge records consume and durability-break item actions in the quantity-flow ledger; projectile actions now use quantity observations in both loaders. Fabric post-execution command results remain tracked in `docs/GRIEFLOGGER_PARITY.md`.
- **Audit reliability:** NeoForge block use is labeled `INTERACT_BLOCK_ATTEMPT` until a completion event exists, capped audit pages report the effective page after offset limits, and transient audit database failures retry with bounded exponential backoff and rate-limited error logs.
- **Command evidence labeling:** NeoForge command hooks now store `COMMAND_ATTEMPT`; the underlying `CommandEvent` is a pre-execution callback, so ItemGraph no longer presents it as completed execution.
- **Fabric command coverage:** the Fabric adapter now records `COMMAND_ATTEMPT` at Minecraft's `Commands.performCommand` boundary through a narrowly scoped server mixin, matching NeoForge's pre-execution semantics without claiming command success.
- **Projectile evidence boundary:** the canonical `THROW_ITEM`/`SHOOT_ITEM` quantity row is emitted at the GriefLogger-compatible `shootFromRotation` attempt boundary; accepted player-owned spawns remain separate raw evidence with no landing or projectile-UUID claim.
- **Projectile retry and acceptance integrity:** projectile attempt rows now persist a durable source event identity for idempotent worker retries, and both loaders emit accepted-spawn evidence only after `addFreshEntity` returns true; accepted audit details do not claim a quantity.
- **Audit retry identity:** migration v16 adds source-event deduplication for native audit rows, and projectile attempt/accepted evidence now persists that identity in `ig_audit_events` as well as the quantity ledger.
- **NeoForge staging run:** the ModDev server now includes the shared `common` and `core` source sets, allowing a GriefLogger-absent `runServer` smoke to load ItemGraph and its mixins before a release build.
- **Fabric placement coverage:** the Fabric adapter now records completed `BlockItem.place` actions as `PLACE_BLOCK` audit evidence through a server-only return hook; failed and non-block interactions remain excluded.
- **Fabric ground-flow coverage:** the Fabric adapter now records normal player drops, vanilla player-death inventory drops, and full or partial pickups through server-only hooks. It requires the `addFreshEntity` acceptance result, preserves the returned `ItemEntity` UUID, labels death-time rows `DEATH_DROP`, captures custom item entities accepted during `ServerPlayer.die`, uses the returned entity stack count for drops, and uses the before/after count delta for pickups.
- **Fabric container sessions and automation:** server menu initialization and close hooks reuse the shared interval tracker for block containers and double chests, orderly server shutdown flushes active session deltas before database close, and `HopperBlockEntity` transfers emit bounded net `HOPPER_INSERT`/`HOPPER_EXTRACT` observations with unknown endpoints. Modded automation remains a tracked adapter gap.
- **Fabric transformation coverage:** crafting, furnace-family smelting, and anvil rename/repair results are captured at their server result-take boundaries and written to the shared transformation ledger with canonical source/result fingerprints.
- **Fabric item-action coverage:** completed eat/drink actions now use a server-only `LivingEntity.completeUsingItem` completion hook at the method's return boundary and preserve the original stack across nested calls; durability breaks are captured at the authoritative `ItemStack.hurtAndBreak` shrink boundary. The bounded capture records live in a non-mixin helper so Fabric remapping cannot turn nested records into invalid `LivingEntity` constructors. Canceled or non-consumable uses do not create `CONSUME_ITEM` evidence.
- **Fabric consume-capture stability:** HEAD and RETURN callbacks now match a consume invocation by a stable caller class/method marker and stack depth, excluding the target method's differing source line. Stale or ambiguous nested markers are discarded instead of pairing a completed use with another invocation's original stack.
- **Fabric inspector parity:** `/ig inspect` now opens the shared read-only container flow browser through Fabric's `UseBlockCallback`, with permission checks, accepted-query cancellation, rejected-query fallback, and disconnect cleanup matching NeoForge behavior.
- **Block inspection:** `/ig inspect` now opens an asynchronous, paginated exact-coordinate audit history on both loaders for initial left-clicks and non-container right-clicks, cancels the gameplay action before native capture (including rejected lookups), and ignores repeated left-click hold/abort packets. Double-chest/door logical-target aggregation remains tracked by #26.
- **Published lookup syntax:** `/ig lookup` now accepts the documented direct GriefLogger filter form (`action...`, `user...`, `include...`, `exclude...`, `time...`, and `radius...`) with token-aware suggestions and the published ten-row default; `/ig lookup filters ...` remains an explicit ItemGraph spelling.
- **Lookup completion parity:** direct lookup completion now offers published one-letter filter aliases, action values from GriefLogger 26.2 plus ItemGraph-supported actions, registered item identifiers, and online player names; it stops suggesting duplicate/conflicting filters and enforces the five-filter completion limit. Offline historical username completion remains tracked by #24.
- **Fabric command parity matrix:** the Fabric test suite now exercises the shared `/ig` and `/itemgraph` Brigadier contract for direct and explicit GriefLogger filter syntax, standalone paging, inspect toggles, and published suggestions alongside the NeoForge command matrix.
- **Logical inspector targets:** `/ig inspect` now queries both physical halves of a valid double chest or door in one bounded, globally ordered audit page, preserving one row per stored event and exact dimension filtering.
- **Stable double-chest session anchors:** new container sessions choose one deterministic physical anchor for both halves and register the other half as an alias on Fabric and NeoForge, preventing future clicks on opposite halves from creating divergent ItemGraph container nodes. Reopening a former partner position after a chest split retires the stale alias before creating the new watch; overlapping old and new watches close against their own keys and share capability credits so topology changes cannot manufacture player movement.
- **Native entity-interaction lookup:** `/ig lookup INTERACT_ENTITY` now selects the native audit-event path on both loaders, matching the existing server-side interaction-attempt evidence and the shared filtered lookup action vocabulary.
- **Unified GriefLogger-style filtered lookup:** `/ig lookup filters` now accepts up to five `name.value` filters for native audit, item-flow, and transformation actions, user names or UUIDs, included/excluded subjects, recent time, and a required bounded cube radius around the issuing player. One bounded asynchronous merge reads native audit events, observations, transformations, and imported `GRIEFLOGGER` observations while preserving source and prefixed evidence IDs. Include/exclude conflicts and unsupported actions are rejected before the read-only query.
- **Interactive audit paging:** `/ig lookup page` now returns permission-checked Previous/Next chat controls that rerun the same bounded filters.
- **Modrinth publication gate**: tagged releases and the Modrinth-only retry workflow now check both project API endpoints before publishing. A non-2xx response disables Modrinth steps cleanly while GitHub and CurseForge publication continue; a later release or manual retry becomes eligible after both endpoints return successful responses.
- **GriefLogger component decode handling**: undecodable historical `DataComponentPatch` rows no longer emit a per-row WARN or repeat the same codec failure for every duplicate row while its entry remains in the bounded cache. ItemGraph keeps the raw BLOB, records an opaque SHA-256 fingerprint so distinct payloads do not collapse into one item-ID fingerprint, and emits one DEBUG diagnostic per payload and registry context. Replacing the server registry context clears the cache and permits a retry.
- **Unsupported GriefLogger database guard:** a readable SQLite file is now checked for the complete supported `items`, `containers`, `users`, `levels`, and `materials` schema before ingestion. Unrelated or empty SQLite files are skipped with one informational message instead of repeated missing-table warnings.
- **Projectile action parity:** both loaders now record `THROW_ITEM` and `SHOOT_ITEM` at the same `Projectile.shootFromRotation` HEAD attempt boundary used by GriefLogger, while accepted player-owned spawns are retained as `PROJECTILE_SPAWN_ACCEPTED` evidence without duplicating quantity flow. Compatibility artifacts remain gated by the other unresolved action families.
- **Lookup case compatibility:** native `/ig lookup` preserves GriefLogger's
  case-insensitive event parsing without a custom command-tree serializer;
  literal aliases cover suggestions and the greedy fallback handles mixed-case
  root, player, paged, and near queries, including legacy `ALL` spellings.
- **Source-event collision handling:** native UUID-derived numeric source IDs now
  compare the producer UUID across both evidence tables and probe deterministic
  salted IDs when a distinct event collides, preserving paired observation and
  audit rows even when their raw payload details differ.

## [0.3.2] — 2026-09-28

### Added

- **Fabric 1.21.1 server build**: adds a Fabric Loom adapter and Fabric metadata while sharing ItemGraph query, API, database, and ingestion code. The Fabric adapter uses the same GriefLogger database as an additive read-only evidence source; configure `grieflogger_database_path` in `config/itemgraph.properties`.
- **GriefLogger-compatible artifacts for both loaders**: Fabric and NeoForge each build a separate `-grieflogger-compatible.jar` that requires GriefLogger `1.2.10-1.21.1` and omits ItemGraph's embedded SQLite driver. The regular Fabric and NeoForge jars retain their own SQLite dependency.
- **Hexagonal loader boundary**: adds a Java-only `core` module with the `RuntimeInformationPort`; Fabric and NeoForge adapters provide loader facts without a loader API dependency in shared code.
- **Multi-loader CI and release metadata**: the root build compiles/tests both loader modules, verifies all four loader/provider artifacts, and publishes loader-specific files to GitHub, CurseForge, and Modrinth.

### Changed

- **Artifact names include loader and provider**: release jars now use `itemgraph-<version>-<loader>[-grieflogger-compatible].jar`.
- **Gradle wrapper**: upgrades to 8.12.1 because Fabric Loom 1.10.5 requires Gradle 8.12 or later.

## [0.3.1] — 2026-09-28

### Added

- **GriefLogger-compatible server artifact**: CI and tagged releases now produce and distribute a separate `-grieflogger-compatible.jar` for GriefLogger `1.2.10-1.21.1`. It omits ItemGraph's SQLite Jar-in-Jar copy to avoid the `org.sqlite.*` module collision. CurseForge publishes this as a separately labeled file with GriefLogger required; use it when running GriefLogger and use the standard JAR without GriefLogger.

## [0.3.0] — 2026-09-25

### Added

- **Preview integration API**: `com.itemgraph.api` implements the approved PREVIEW_1 boundary for trusted NeoForge mods: active-container source registration through a service-issued `SourceHandle`, immutable raw direct-observation DTOs, `(source modId, sourceEventId)` deduplication, durable `EXTERNAL_INVENTORY` identity, bounded async submission/query workers, and explainable flow DTOs with opaque evidence URIs. `examples/api-consumer` compiles as a separate mod against the built ItemGraph JAR; dedicated staging passed with GriefLogger present (`PERSISTED / query=AMBIGUOUS`) and absent (`DUPLICATE / query=AMBIGUOUS`). This remains a preview API, not a stable compatibility promise.
- **Complete command help/reference**: bare `/itemgraph` or `/ig` and `/ig help [topic]` document every live command with syntax, defaults, permission, asynchronous behavior, evidence semantics, and examples. Player, item-ID, dimension, literal, and topic suggestions are registered, and dispatcher tests cross-check help coverage against the live command tree.
- **Read-only vanilla flow browser**: permission-level-2 `/ig gui item`, `/ig gui player`, and dimension-qualified `/ig gui container` commands open a vanilla six-row chest menu with 45-entry keyset-paginated timelines, provenance/confidence labels, and event/transformation/edge detail views. Menu actions cannot move items.
- **Command-toggled container inspector**: `/ig inspect [on|off|status]` stores per-player server-side mode. While enabled, a supported container right-click opens the read-only flow browser for that exact dimension/position without opening the normal container GUI, consuming the held item, or recording a transfer. The mode clears on logout and server stop.

### Fixed

- **Vanilla GUI title overflow**: resolved flow-browser titles now use compact node/fingerprint references such as `ItemGraph: item #100`; the complete target description remains available in the page tooltip.
- **Preview API validation boundaries**: malformed endpoint/component maps now return `INVALID_INPUT`, oversized `customName` values are rejected, persistence failures return explicit `FAILED` results, player query results do not expose first-seen coordinates, and canonical fingerprint payload values use deterministic escaping to prevent separator-forgery collisions.
- **Release dependency metadata**: publishing metadata now marks GriefLogger as optional and no longer advertises GriefLogger's own dependencies as required ItemGraph dependencies.

## [0.2.0] — 2026-09-20

### Summary

ItemGraph 0.2.0 is independent from GriefLogger. It starts and records supported native
NeoForge event/capability evidence without GriefLogger installed. When GriefLogger is present,
its read-only rows remain additive evidence; confirmed cross-source copies share one quantity
capacity, while uncertain pairs remain ambiguous. The hard boot dependency on GriefLogger is
removed.

### Added

- **Native drop and pickup observation** (`ItemEntityEventListener` promoted): successful
  ItemToss/LivingDrops entities write `DROP_ITEM`/`DEATH_DROP` only after
  `ItemEntity.isAddedToLevel()` confirms world insertion; `ItemEntityPickupEvent.Post` writes
  `PICKUP_ITEM`. Canceled tosses record `DROP_CANCELLED` to UNKNOWN, and canceled death drops
  are no-destination `DEATH_DROP_CANCELLED` events; neither is ground movement.
- **Partial-pickup pairing**: `ItemEntityPickupEvent.Pre` records the entity's stack
  count before `Inventory.add()` and a per-tick sweep emits the absorbed delta for
  pickups where Post never fires (NeoForge 21.1.248 gates Post on `add()` returning
  true, which is false for partial absorbs). Post consumes the pending entry so full
  pickups are never double-counted; paired pickups are tagged
  `{"detection":"pre_post_pairing"}` in `raw_data`.
- **Container capability wrapper** (Issue 4): `ContainerCapabilityWrapper` wraps the
  `IItemHandler` capability on the vanilla block/block-entity types NeoForge serves —
  sided-container face rules, double-chest views, hopper cooldowns, and composter
  re-evaluation remain delegated. Registration runs at `EventPriority.HIGHEST` because
  `BlockCapability.getCapability` returns the first non-null provider. Real calls emit
  `CAPABILITY_INSERT`/`CAPABILITY_EXTRACT`; `IItemHandler` does not identify its caller or
  cause, so both player/cause identity and the remote endpoint remain UNKNOWN.
- **Player container session deltas** (Issue 3): player GUI clicks mutate
  `Container` directly and never traverse `IItemHandler`, so player-driven changes are
  measured as fingerprint-level net deltas across `PlayerContainerEvent.Open`/`Close`.
  Rows carry `timestamp_ms`/`timestamp_end_ms` and are not click-time evidence. A zero-net
  withdraw-and-return is not represented and does not prove no interaction occurred.
  Capability deltas are subtracted; multi-viewer sessions produce one `[ambiguous]` row
  with candidates preserved in `raw_data`.
- **`ContainerInteractionTracker`**: Session-watch tracker holding per-container
  baseline totals, open sessions, participants, signed capability deltas, rejected-row
  recovery amounts, and double-chest position aliases.
- **`ContainerCapabilityRegistrar`**: Registers the capability wrapper providers
  on the mod event bus (`RegisterCapabilitiesEvent`, `EventPriority.HIGHEST`).
- **Schema migrations V9+V10**: V9 adds a partial unique index on
  `ig_observations(source_type, timestamp_ms, node_id, fingerprint_id, amount,
  action_type) WHERE source_event_id IS NULL` plus `idx_obs_action_type`. V10
  extends the dedup key with `item_entity_uuid` so re-submitted entity events still
  deduplicate while distinct same-millisecond events (two identical death-drop
  stacks, pile pickups, repeated machine pushes) are no longer collapsed.
- **Schema migration V11**: adds `timestamp_end_ms`, source-group/member and match-check
  tables, and `edge_state`; rebuilds the internal dedup index with `target_node_id` so
  same-time pickups for different recipients survive.
- **Cross-source quantity conservation**: a unique shared ItemEntity UUID and matching event
  details corroborate GriefLogger and ItemGraph rows without duplicating capacity. Uncertain
  pairs remain ambiguous; legacy edges using aliases are superseded without deleting records.
- **Shared-writer serialization**: internal observations, GriefLogger ingestion, and
  correlation transactions serialize on the shared ItemGraph JDBC connection.
- **`DEATH_DROP` action type**: Added to `CorrelationEngine.DROP_ACTIONS` so death drop
  observations participate correctly in ground-bridge correlation.
- **GriefLogger startup log**: ItemGraph now logs `GriefLogger integration: ENABLED` or
  `DISABLED` with reason at `ServerStartingEvent`.

### Changed

- **`neoforge.mods.toml`**: GriefLogger dependency demoted from `type="required"` to
  `type="optional"`. ItemGraph now starts without GriefLogger.
- **`build.gradle`**: `sqlite-jdbc` switched from plain `implementation` to
  `jarJar(implementation(...))` with version range `[3.40.0.0,4.0.0.0)` and preferred
  version `3.46.1.0`. ItemGraph bundles its own SQLite driver. NeoForge JarJar negotiation
  deduplicates with GriefLogger's bundled copy when both are present, eliminating the
  confirmed JPMS split-package crash.
- **`IngestionService`**: GL database unavailable no longer emits a WARN every 60 seconds.
  Logs once at INFO level on first skip; subsequent skips are silent until GL becomes
  available again.
- **`/ig status`**: GriefLogger source reports `ENABLED (database reachable)`,
  `DISABLED (not installed)`, or `DISABLED (mod present but database not found)`; database
  counts/checkpoints and active/superseded edge counts are queried off-thread, alongside
  queue loss and capability queue-rejection counts.
- **`/ig ingest now`**: queues one bounded ingest-and-correlate cycle on the background
  worker instead of doing source reads and candidate search on the server thread.
- **`InternalObservationService.persistBatch`**: Resolves `GROUND`, `CONTAINER`, `PLAYER`,
  `UNKNOWN`, and `ARMOR_STAND` endpoints. `INSERT OR IGNORE` uses the V11 destination-sensitive
  partial index so same-time events for different recipients are not conflated.
- **Version**: `0.1.0` → `0.2.0`.

### Fixed

- JPMS split-package crash when GriefLogger and ItemGraph are both installed (sqlite-jdbc
  conflict: `Modules grieflogger and org.xerial.sqlitejdbc export package org.sqlite`).
- NULL `source_event_id` dedup gap: internal observations could not be deduplicated by the
  existing `(source_type, source_event_id)` unique index because SQLite treats `NULL != NULL`.
- Partial item pickups were silently unobserved: `Inventory.addItem` returns false when
  only part of the stack fit, so `ItemEntityPickupEvent.Post` never fired and no
  `PICKUP_ITEM` row (or vanilla pickup stat) was produced. Resolved via the
  Pre/stack-delta pairing described above (verified live: 1-of-10 partial pickup now
  records exactly 1).
- Dev-run classpath gap: jarJar strips sqlite-jdbc from dev run classpaths, so
  `Class.forName("org.sqlite.JDBC")` only resolved when another installed mod embedded
  it — a standalone dev boot failed DB init with `ClassNotFoundException`. The driver
  is now added to `additionalRuntimeClasspath` only when no mod in `run/mods` already
  embeds sqlite (detected via `META-INF/jarjar|jars/sqlite-jdbc-*.jar` entries; override
  with `-Pitemgraph.devSqliteProvided=`); adding it unconditionally alongside such a
  mod crashes module resolution with a duplicate `org.xerial.sqlitejdbc` module.
  V9 adds a partial unique index covering internal row identity.
- `PICKUP_ITEM` recorded the pre-pickup stack count even when only part of the stack
  moved; it now records `originalStack - currentStack`.
- `ItemTossEvent`/`ItemEntityPickupEvent` handlers now guard `isClientSide` so
  client-side event posts cannot enqueue duplicate observations.
- `CorrelationEngine.findCompetingDrops` now counts `DEATH_DROP` rows when scoring
  drop-side ambiguity (matching `DROP_ACTIONS`).

### Earlier unreleased work

- Phase 10: Production Hardening, Integrity Auditing, and Comprehensive Diagnostics.
  - Off-thread `AuditService` checking core architectural invariants:
    - Quantity conservation ($\sum \text{allocated} \le \text{evidenced capacity}$) across all observations and inferred edges.
    - Strict positivity for quantities on observations, edges, and allocations.
    - Relational integrity: zero orphaned allocations and zero missing edge endpoint nodes.
    - Lifecycle status consistency: validates observation `correlation_status` against active allocations.
  - New administrative command `/ig audit`: dispatches audit analysis asynchronously and reports live invariant status.
  - Diagnostic metrics in `/ig status`: internal queue capacity/throughput, total persisted transformations, active entity tracking counts, and continuity matches.
  - Automated test suite `AuditServiceTest` verifying healthy graphs, conservation violation detection, orphaned allocation detection, and topology validation.
  - Reached 106 automated tests with 100% pass rate.

- Phase 9: Item Transformation Tracking (Renaming, Crafting, Smelting).
  - Schema table `ig_item_transformations`: records item transitions linking source fingerprint to result fingerprint with actor node, transformation type, quantity, timestamp, and details.
  - `TransformationEventListener`: captures NeoForge `AnvilRepairEvent` (renames, repairs), `ItemCraftedEvent` (crafting), and `ItemSmeltedEvent` (smelting).
  - Asynchronous batch persistence via `InternalObservationService` with a memory-bounded queue (10,000 capacity).
  - Chronological transformation surfacing in `TraceQueryService`: item traces now include `[TRANSFORMATION <type> <- <source>]` hops connecting item lineages across identity shifts.

- Phase 8: High-Value Integrations and UX Enhancements.
  - Authoritative `ItemEntity` UUID tracking:
    - Schema migration `V8__HighValueIntegrations` adding `item_entity_uuid` column to `ig_observations`.
    - Memory-bounded, thread-safe `ItemEntityTracker` tracking ground item drops and pickups with coordinate matching and temporal expiration.
    - `ItemEntityEventListener` subscribed to `ItemTossEvent` and `ItemEntityPickupEvent.Post`.
    - Enhanced `CorrelationEngine`: exact `item_entity_uuid` continuity matching yields `0.9990` confidence with narrative explanation citing authoritative Minecraft entity continuity.
  - Armor stand interactions:
    - `ArmorStandEventListener` capturing `PlayerInteractEvent.EntityInteractSpecific` to record `EQUIP_ARMOR_STAND` and `UNEQUIP_ARMOR_STAND` observations.
  - Player and container trace queries:
    - `/ig trace player <playerName> [limit] [sinceMinutes]`
    - `/ig trace container <x> <y> <z> [limit] [sinceMinutes]`
  - Dynamic string and registry query resolution for `/ig trace item <query>` supporting numeric IDs, registry IDs (e.g. `diamond_sword`), and custom names.

- Phase 7: Stack-aware quantity-flow reconstruction and allocation ledger.
  - Migration `V7__QuantityFlowLedger` adding `ig_edge_allocations` table (`edge_id`, `observation_id`, `allocation_role`, `amount`) and explicit observation lifecycle states in `ig_observations.correlation_status` (`PENDING`, `PARTIALLY_ALLOCATED`, `FULLY_ALLOCATED`, `CLOSED_UNRESOLVED`).
  - Stack splitting: supports 1-to-many flows (e.g. drop 64 -> pickup 20 + pickup 44) without per-item UUIDs.
  - Stack merging: supports many-to-1 flows (e.g. drop 20 + drop 30 -> pickup 50).
  - Partial transfers: strict quantity conservation ($\sum \text{allocated} \le \text{evidenced capacity}$) with unrecovered units preserved as residual capacity.
  - Dynamic capacity accounting and window closure semantics: observations with unallocated residual quantity transition to `CLOSED_UNRESOLVED` once the candidate window expires.
  - Atomic single-transaction persistence for inferred edges, evidence citations, allocations, and observation status updates.
  - Enhanced explanation narratives documenting flow classification (`stack split`, `stack merge`, `exact transfer`), allocated units, residuals before/after, candidate counts, and factor breakdown.
  - Automated test suite: `QuantityFlowTest` covering all 16 stack-aware flow scenarios (93 total project tests, 100% passing).
- Phase 6: the three forensic query commands, all gated at permission level 2 like the
  rest of the `/itemgraph` (alias `/ig`) tree.

  ```text
  /ig event   <observationId>
  /ig explain <edgeId>
  /ig trace item <fingerprintId> [limit] [sinceMinutes]
  ```

  `/ig event` prints one raw `ig_observations` row with both endpoints and the item
  fingerprint resolved. `/ig explain` prints one `ig_inferred_edges` row, its stored
  confidence, the scoring narrative written at inference time, and every observation
  cited through `ig_edge_evidence` — the literal implementation of the charter's
  "why does ItemGraph think this transfer happened?" requirement. `/ig trace item`
  merges `ig_observations` and `ig_inferred_edges` into one chronological timeline for
  a single fingerprint.
- OBSERVED/INFERRED labelling convention in all query output. Every line that asserts a
  movement is prefixed with its provenance — `[OBSERVED]` for a single raw evidence row,
  `[INFERRED conf=0.9025]` for a reconstruction, with the confidence printed on every
  inferred line to four decimals. There is no unlabelled movement line anywhere in the
  output. See "Query output: the labelling convention" in `docs/ARCHITECTURE.md`.
- Bounded results: `QueryLimits` caps any requested limit at 100 rows (default 20) and
  caps a single `/ig explain` evidence listing at 50; `QueryWindow` bounds a trace by
  relative minutes. Each side of a trace is queried with `LIMIT applied + 1`, so
  "there is more" is a reported fact rather than silence, and both the cap and the
  truncation are stated in the output instead of being applied quietly.
- Query commands run off the server thread. `QueryDispatcher` runs the SQL and the
  formatting on a dedicated single-threaded `ItemGraph-Query-Worker` — deliberately not
  the ingestion worker, so an incident lookup never queues behind a 60-second
  ingest-then-correlate cycle — and hands the finished lines back with
  `source.getServer().execute(Runnable)` for `sendSuccess`/`sendFailure`. See
  "Query execution: off-thread, reported back on-thread" in `docs/ARCHITECTURE.md`.
- `DatabaseManager.openReadOnlyConnection()`: each query gets its own short-lived
  connection with `PRAGMA query_only = ON`. Reading through the shared writer connection
  would execute inside the ingestion worker's open transaction and could show an admin
  rows that are about to be rolled back. WAL mode (already enabled) makes an independent
  reader both consistent and non-blocking.
- Phase 5: `CorrelationEngine`, which bridges a player's drop to a later player's pickup
  across the ephemeral `GROUND` node and writes an `ig_inferred_edges` row with its two
  supporting observations. This is the only Phase 5 pattern that is genuinely an inference
  — see "Correlation: ground bridging" in `docs/ARCHITECTURE.md` for why the other three
  patterns in `docs/IMPLEMENTATION_PLAN.md` are already single fully-evidenced observations
  after the direction fixes below.
- Deterministic, fully explainable confidence scoring:
  `confidence = 0.95 x proximity(dt) x ambiguity(competing pickups) x ambiguity(competing drops)`,
  where `proximity(dt) = 1 - 0.25 x clamp(dt/window, 0, 1)` and
  `ambiguity(n, gap) = 1/n + (1 - 1/n) x min(0.9, clamp(gap/window, 0, 1))` for `n > 1`, else `1`.
  No model, no tuning, no randomness. Base is 0.95 rather than 1.0 because without the
  `ItemEntity` UUID a ground bridge is always circumstantial evidence.
- Every inferred edge stores an `explanation` naming both players, both observation IDs, the
  block, both timestamps, the candidate counts on each side, and the arithmetic that produced
  the confidence.
- Migration V6 adds `ig_observations.correlated_at` plus correlation lookup indexes. Passes are
  driven by `correlated_at IS NULL` and bounded to 500 observations, so the table is never fully
  scanned. Drops whose window is still open are deliberately deferred rather than written off,
  because the pickup that explains them may not have been ingested yet.
- Correlation is wired into the existing ingestion worker: it runs on the same scheduled
  executor immediately after each ingestion cycle, never on the server thread and never
  concurrently with ingestion.
- `correlation.ground_bridge_max_seconds` config option (default 300s, the vanilla item-entity
  despawn time).
- `/ig status` now reports the correlation window and last-pass diagnostics; `/ig ingest now`
  queues a correlation pass onto the ingestion worker.
- Phase 4: `NodeType` enum (`PLAYER`, `CONTAINER`, `GROUND`, `ARMOR_STAND`, `UNKNOWN`) and
  `NodeManager`, which owns stable get-or-create identity resolution for every node type.
- `GROUND` nodes keyed by dimension + block coordinate, so a drop and the pickup that
  recovers the same stack share a node.
- `UNKNOWN` sentinel node (one per dimension, no coordinates) for endpoints that genuinely
  cannot be determined from GriefLogger evidence.
- `ARMOR_STAND` node identities, resolvable and tested but not yet wired to any event source
  (GriefLogger has no armor stand coverage; deferred to Phase 8).
- Initial project charter and documentation.

### Fixed

- Container-table ingestion wrote *every* row as `container -> player` regardless of the
  action, which is only correct for a withdrawal. Deposits (`ADD_ITEM`, `ADD_ITEM_ENDER`) flow
  `player -> container`, so every deposit already recorded pointed backwards and was
  topologically indistinguishable from a withdrawal — including the MVP chain's final hop
  (`Player B -> Chest B`). `resolveContainerEndpoints` now derives a real (origin, destination)
  pair per action, and migration V5 clears observations and checkpoints so every containers row
  is re-read through the corrected mapping. Existing fingerprints and node identities are
  preserved, and GriefLogger's database is untouched as always.

### Changed

- Item-table ingestion now records a real flow direction per action
  (`node_id` = origin, `target_node_id` = destination) instead of anchoring every row to the
  acting player with no target. See "Observation direction convention" in `docs/ARCHITECTURE.md`.
- Migration V4 clears derived observations and ingestion checkpoints so existing
  direction-less rows are re-ingested through the corrected logic. Item fingerprints and
  existing player/container node identities are preserved.
- Defined ItemGraph as a directed temporal item-flow graph.
- Established separation between raw observations and inferred movement.
- Established GriefLogger as a read-only evidence source.
- Defined initial security, privacy, testing, and performance requirements.
- Defined MVP around named-item and quantity-flow reconstruction.
