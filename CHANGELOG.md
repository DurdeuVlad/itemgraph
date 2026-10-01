# Changelog

All notable changes to ItemGraph will be documented here.

The project follows a simple pre-1.0 development changelog model.

## [Unreleased]

### Added

- **Issue #27 action registry and exact-release writer matrix:** registry
  `m8.8.0` now records the pinned source enum and ID for all 18 GriefLogger
  actions plus the exact `1.2.10-1.21.1` writer result. CI rejects malformed
  boolean/float IDs and duplicate JSON keys, and checks action-specific JVM
  enum-field accesses in both official loader artifacts. `INTERACT_BLOCK` is
  compatible at the attempt-only boundary proven by #74; `INTERACT_ENTITY`
  has no action ID or writer in the target release and remains a separately
  labeled ItemGraph extension per #75. Ender actions remain `unsupported-no-writer`
  per #76. ItemGraph mod version remains 0.3.2; no distributable jar is built.
- **Issue #75 cross-loader interaction evidence:** non-armor-stand callbacks now carry stable `target_support=callback_only` and `target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT` metadata, while armor-stand rows identify their supported method-result boundary. Callback-level denied/unresolved outcomes remain recorded where observed. NeoForge and Fabric isolated server GameTests send entity-use packets through each loader's server handler for unsupported-entity attempts and armor-stand boot equip/unequip. They separately call inherited `ArmorStand.interact` to verify its `PASS` return hook and persisted unresolved method result. Both tests compare the complete `ig_observations` row snapshots before and after, and check the same normalized audit query and `QueryFormatter` output used by `/ig lookup`. CI runs both loader GameTests without creating distributable mod jars.
- **Issue #76 Ender action compatibility:** the release fixture validator now checksum-verifies both published 1.2.10-1.21.1 jars and finds no Ender action constant field references outside `ItemAction.class`; its `values()` calls are limited to the generic `Actions` catalog and the enum's own `fromId` decoder, and the only external `fromId` caller is `ItemHistory.<init>` reconstructing stored rows. The registry marks both GriefLogger actions `unsupported-no-writer` using `NO_WRITER_IN_EXACT_1_2_10_1_21_1_RELEASE`; existing ItemGraph session net deltas stay labeled as `ITEMGRAPH_INTERNAL` extensions. Regression coverage checks duplicate opens, reconnects, partial signed counts, atomic queue rejection/retry including orderly shutdown under one shared five-second retry deadline, opaque component redaction, no-net sessions, and restart persistence. Registry compatibility version is `m8.7.0`; ItemGraph mod version stays 0.3.2.
- **Issue #24 command contract:** `/ig` and `/itemgraph` expose the same level-2 command tree; GriefLogger's published direct `name.value` lookup syntax, six filter names and one-letter aliases, required cubic radius, five-filter limit, AND combination, ten-row page default, per-player pages, and page navigation are covered across NeoForge and Fabric tests. The selected published-doc radius requirement is explicit because GriefLogger 26.2 source accepts no-radius lookup. Output retains ItemGraph evidence labels and provenance; `/gl` and `/grieflogger` remain unregistered.
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
- **Issue #30 fail-closed NeoForge config validation:** numeric settings now
  reject invalid types and out-of-range values with the full config key before
  NeoForge can clamp them; string, boolean, and integer values retain their
  native config metadata. Fresh GameTest startup probes confirm
  `query.max_page_size=101` and `general.database_port=0` fail before database
  initialization and remain unchanged in TOML. This closes the reproduced
  `database_port=0` to `1` normalization gap; full invalid-config matrix
  coverage across both loaders remains open. No mod version bump or
  distributable jar was produced.
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
  spawn audit row for each loader; no release artifact or version bump was made.
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

### Fixed

- **Expired lookup-page cleanup (#24):** following an expired explicit `/ig page <page> <session>` link now removes the player's empty page-session map. Fabric dispatcher coverage exercises both command roots, invalid/expired sessions, cross-player tokens, denied permission, and error-only output; NeoForge page-session regressions remain green.

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
