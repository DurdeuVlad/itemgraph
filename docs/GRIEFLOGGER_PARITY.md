# GriefLogger replacement parity

This document defines the compatibility contract and acceptance boundary for
replacing GriefLogger as the native server audit source. Compatibility is
behavioral and evidence-preserving; ItemGraph keeps its own command names and
storage. The matrix below is based on GriefLogger's published feature surface:
block actions, item usage, player sessions, chat, commands, inspector, filtered
lookup, pagination, and SQLite/MySQL storage.

## Branding contract

- ItemGraph is the standalone product and the intended complete replacement for
  GriefLogger. Normal operation requires neither the GriefLogger mod nor its
  database; native ItemGraph capture and storage are authoritative.
- The optional GriefLogger database importer is a read-only migration path for
  historical evidence. It is not a runtime dependency and does not execute or
  reuse GriefLogger code.
- Compatible jars are temporary coexistence builds. Retire them only when the
  native parity and cutover gates below are satisfied; after cutover, the
  standalone loader jars are the only supported artifacts.
- GriefLogger is a behavior reference only. Implement every feature with
  ItemGraph-owned code, schemas, and event types; do not copy or patch
  GriefLogger implementation code.
- Supported commands are `/ig` and `/itemgraph`.
- `/gl` and `/grieflogger` are not ItemGraph commands or aliases.
- A GriefLogger database is a read-only source. ItemGraph never repairs,
  migrates, writes, deletes, indexes, vacuums, or purges it.
- ItemGraph labels direct observations, inferred movement, ambiguous candidates,
  and unresolved events separately. Non-quantity audit evidence is never
  presented as an item transfer.

## Registry

The normative machine-readable mapping is
[`GRIEFLOGGER_COMPATIBILITY.json`](GRIEFLOGGER_COMPATIBILITY.json). It records
canonical action names, accepted GriefLogger spellings, compatibility status,
evidence and quantity semantics, loader/storage support, lookup filters,
permission and paging controls, inspector behavior, configuration controls, and
the GitHub issue responsible for incomplete mappings.
The current registry compatibility version is `m8.12.0`.

The registry version changes when a mapping, status, evidence or quantity
meaning, loader, or backend contract changes. Documentation-only clarifications
are patch changes; additive mappings with existing behavior are minor changes;
renamed, removed, or incompatible mappings are major changes. The registry,
this document, and the owning issue change together.

This GriefLogger compatibility registry is separate from ItemGraph's
[event taxonomy](EVENT_TAXONOMY.md), which defines ItemGraph-owned audit,
observation, and transformation IDs, loader support, and privacy/evidence
semantics. ItemGraph-only extensions do not become GriefLogger actions by being
listed in that taxonomy.

## Published source surface

The contract uses GriefLogger's published documentation:

- https://daqem.com/projects/grieflogger
- https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage
- https://daqem.com/projects/grieflogger/wiki/player-actions/block-interactions
- https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions
- https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands
- https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command
- https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/inspect-command
- https://daqem.com/projects/grieflogger/wiki/getting-started/configuration

### Pinned source baseline

The source audit is pinned to GriefLogger ref `26.2`, commit
`d315098b3f37317a5cddfbd75086f4f912f16a83` ([source tree](https://github.com/DAQEM/GriefLogger/tree/26.2)), retrieved 2026-09-29.
The complete feature inventory and the 26.2-dev versus ItemGraph 1.21.1 compatibility
boundary are recorded in [the source audit](GRIEFLOGGER_SOURCE_AUDIT.md).
The source defines 18 actions across block, session, and item enums and creates
11 tables: `items`, `containers`, `blocks`, `sessions`, `chats`, `commands`,
`users`, `usernames`, `levels`, `materials`, and `entities`. The profile and
contradiction matrix are tracked in [issue #43](https://github.com/DurdeuVlad/itemgraph/issues/43).

### Exact 1.21.1 release fixture

The release target for drop-in compatibility is GriefLogger `1.2.10-1.21.1`,
for both Fabric and NeoForge. The checked-in
[`1.2.10-1.21.1 release fixture`](grieflogger-fixtures/1.2.10-1.21.1.json)
records the official Modrinth file IDs, URLs, byte sizes, SHA-1/SHA-256/SHA-512
digests, loader metadata, Java 21 mixin contracts, embedded SQLite 3.47.2.0
and MySQL Connector/J 8.4.0 versions, action IDs, all 11 table column layouts,
commands, configuration defaults, inspector behavior, and an action enum
field-access matrix verified against both published jars. Its canonical fixture
digest is
`d8181c2af8ba8eccb289bf0e6678be2d3a75ada4e0d51c0d459ba257afaf0853`.

The exact published artifacts are the authority for the 1.21.1 target. The
Git tag named `1.2.10-1.21.1` points at source metadata from the later 26.2
development line, so that source tree is retained as behavior research and is
not treated as binary compatibility evidence. CI runs
`tools/validate_grieflogger_release_fixture.py` against the pinned metadata and
can download and hash both official artifacts when the release-fixture check
is enabled. The owning issue is issue #54
([exact-release fixture](https://github.com/DurdeuVlad/itemgraph/issues/54)); it
owns this fixture and any future release refresh.

The fixture records two explicit unresolved differences: the target runtime
metadata is Minecraft 1.21.1/Java 21 while the pinned source metadata is
Minecraft 26.2/Java 25 (owned by #54), and observable native-only behavior still
requires differential replay against that source profile (owned by
[#31](https://github.com/DurdeuVlad/itemgraph/issues/31)). Neither difference
is hidden behind a generic “compatible” label.

### Native action ID and release-writer matrix (#27)

The registry stores the pinned 26.2 enum class and numeric ID for each of the
18 source actions. `release_action_id` and `release_writer_status` separately
describe the exact 1.2.10-1.21.1 target. The release-fixture validator scans
both checksum-verified loader jars and requires the listed action enum-field
access instructions in the expected class files. Enum declaration
self-references do not count as writers. The action enum IDs are not globally
unique, so the enum class is part of every source ID.

This is static bytecode evidence that each listed class accesses an action
constant. It does not prove that the path is reachable or that a row is
persisted at runtime; runtime behavior remains subject to the loader replay
and differential acceptance in #31.

| Source enum | 26.2 ID | Action | Exact 1.2.10-1.21.1 writer result | ItemGraph mapping |
| --- | ---: | --- | --- | --- |
| `BlockAction` | 0 | `BREAK_BLOCK` | present | compatible |
| `BlockAction` | 1 | `PLACE_BLOCK` | present | compatible |
| `BlockAction` | 2 | `INTERACT_BLOCK` | present, main-hand attempt only | compatible |
| `BlockAction` | 3 | `KILL_ENTITY` | present | compatible |
| `BlockAction` | 4 | `INTERACT_ENTITY` | no action ID or writer | unsupported-no-writer; native extension |
| `ItemAction` | 0 | `REMOVE_ITEM` | present | compatible |
| `ItemAction` | 1 | `ADD_ITEM` | present | compatible |
| `ItemAction` | 2 | `DROP_ITEM` | present | compatible |
| `ItemAction` | 3 | `PICKUP_ITEM` | present | compatible |
| `ItemAction` | 4 | `CRAFT_ITEM` | present | extended; preserves transformation lineage |
| `ItemAction` | 5 | `BREAK_ITEM` | present | compatible |
| `ItemAction` | 6 | `CONSUME_ITEM` | present | compatible |
| `ItemAction` | 7 | `THROW_ITEM` | present | compatible |
| `ItemAction` | 8 | `SHOOT_ITEM` | present | compatible |
| `ItemAction` | 9 | `ADD_ITEM_ENDER` | enum only; no writer | unsupported-no-writer; native extension |
| `ItemAction` | 10 | `REMOVE_ITEM_ENDER` | enum only; no writer | unsupported-no-writer; native extension |
| `SessionAction` | 0 | `JOIN` | present | compatible |
| `SessionAction` | 1 | `QUIT` | present | compatible |

For `BREAK_BLOCK`, both loaders also record source fluid removed by a successful
empty-bucket pickup. The hook wraps `BucketPickup.pickupBlock`, waits for its
non-empty returned stack, then records the returned `BucketItem`'s contained
fluid block ID at the source block's coordinates. This follows GriefLogger's
`MixinBucketItem` behavior for modded pickup results where the returned bucket
fluid can differ from the source block fluid. This is block audit evidence
only; it does not create an item quantity row.

For the exact-release item writers, paired NeoForge and Fabric GameTests now
cover `ADD_ITEM`, `REMOVE_ITEM`, `DROP_ITEM`, and `PICKUP_ITEM`. The chest
session fixture uses server-side `QUICK_MOVE` clicks and checks the matching
player inventory and container deltas; the ground fixture removes an item from
player inventory before dropping it and checks the pickup restores it. A shared
read-only SQLite fixture checks these transfers, including item registry IDs,
quantities, canonical fingerprints, player/container/ground endpoints, the container's
position and dimension, session interval evidence, distinct event IDs, and the
exact spawned `ItemEntity` UUID across the drop/pickup pair. Ground endpoints
must remain in the same dimension and within one block, allowing for the server
tick between drop confirmation and pickup. A second unfiltered query requires
the replay player to have exactly these four new ItemGraph-sourced quantity
rows; an unexpected fifth observation fails the fixture. CoreProtect's
documented inventory lookup also normalizes a transfer into player inventory
addition/removal rows; ItemGraph uses its own container session deltas and keeps
that implementation separate from the external API's semantics. This fixture
proves only these four ItemGraph persistence paths. It does not verify the
remaining exact-release writers, connected clients, or differential replay
against GriefLogger; those gates remain open under #27 and #31.

`CHAT` and `COMMAND` remain source features backed by their own tables, not
members of these three action enums, so they have no enum ID. The complete
field-access class lists, source IDs, and no-writer reason codes are in the
machine-readable registry and release fixture. For comparison, CoreProtect's
API v13 also distinguishes action types and action strings instead of treating
an integer ID as a globally unique action; that is the reason ItemGraph keys
these IDs by enum class ([CoreProtect API v13](https://docs.coreprotect.net/api/version/v13/)).

The block interaction row is compatible only at its observed attempt boundary:
the listener records the same main-hand/functional-block attempt before use
completion is known, and never claims the click was accepted. Entity
interaction and Ender IDs differ by target: the published 1.21.1 artifacts do
not expose a writer for them. Their later 26.2 source behavior does not upgrade
the compatibility claim for the older exact release. Those mappings cite the
closed #75/#76 evidence and remain explicitly unsupported for this target.

The source-profile hash is computed with SHA-256. Its canonical input is the
compact, sorted-key JSON object containing the pinned `ref`, `commit`, sorted
source-file URLs, sorted source action enum names, and sorted source table
names. The current digest is
`960291538000e9246ec2eb7c474441281cdf3b45ed7b3ac6be24e1fea4de3111`.
`tools/validate_grieflogger_profile.py` recomputes this digest and fails CI if
the registry, this document, or the milestone references drift. In CI it also
checks the live M8 issue milestone and acceptance-criteria records through the
read-only GitHub API.

The exact source files used for the audit are pinned at the same commit:

- Actions: [`BlockAction.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/BlockAction.java), [`ItemAction.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/ItemAction.java), and [`SessionAction.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/model/action/SessionAction.java).
- Commands, pages, and filters: [`LookupCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/LookupCommand.java), [`InspectCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/InspectCommand.java), [`PageCommand.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/PageCommand.java), [`Page.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/page/Page.java), and [`FilterArgument.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/command/argument/FilterArgument.java).
- Configuration and database: [`GriefLoggerConfig.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/config/GriefLoggerConfig.java), [`Database.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/Database.java), [`Repository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/Repository.java), [`BlockRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/BlockRepository.java), [`ContainerRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/ContainerRepository.java), [`ItemRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/ItemRepository.java), [`SessionRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/SessionRepository.java), [`ChatRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/ChatRepository.java), [`CommandRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/CommandRepository.java), [`UserRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/UserRepository.java), [`UsernameRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/UsernameRepository.java), [`LevelRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/LevelRepository.java), [`MaterialRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/MaterialRepository.java), and [`EntityRepository.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/database/repository/EntityRepository.java).
- Inspector behavior: [`InspectBlockEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/InspectBlockEvent.java), [`InspectContainerEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/InspectContainerEvent.java), [`InspectDoorEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/InspectDoorEvent.java), [`RemoveBlockInteractionsEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/RemoveBlockInteractionsEvent.java), and [`RemoveDoorInteractionsEvent.java`](https://github.com/DAQEM/GriefLogger/blob/d315098b3f37317a5cddfbd75086f4f912f16a83/common/src/main/java/com/daqem/grieflogger/event/block/RemoveDoorInteractionsEvent.java).

The published pages and source disagree on several observable details: the pages
require a radius while `LookupCommand` accepts an empty/no-radius query; the
source lookup merge excludes chat and command rows even though those tables are
stored; and the source default `maxPageSize` is 10. ItemGraph records the chosen
operator-safe behavior and the source behavior as a versioned fixture instead of
claiming that the two are identical.

### Command semantics (#24)

The published [lookup command](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/lookup-command),
[filter reference](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/filters),
[page reference](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/pages),
and [inspect reference](https://daqem.com/projects/grieflogger/wiki/inspecting-lookup/inspect-command)
are the operator-facing command contract. ItemGraph registers `/itemgraph` as
the full root and redirects `/ig` to the same node; it does not register `/gl`
or `/grieflogger`. Both roots use permission level 2. The direct lookup form
accepts the six documented `name.value` filters and one-letter aliases, quoted
comma-separated values, at most five filters, required radius, cubic distance,
and AND semantics. The explicit `/ig lookup filters` spelling is an ItemGraph
extension. The required-radius rule follows the published safety guidance even
though pinned GriefLogger 26.2 `LookupCommand` accepts a no-radius query; that
versioned source discrepancy is retained above rather than silently represented
as exact parity.

Published lookup examples are parser-tested under both roots in
`ItemGraphCommandsHelpTest` and `FabricItemGraphCommandsParityTest`. Each of
the 15 examples now checks both Brigadier command parsing and the normalized
`AuditLookupFilters` values: action IDs, player names, item registry IDs,
radius, and the fixed-clock time window. The command-tree parse also asserts
that the `lookupFilters` argument retains the published expression through
both roots. Both loaders cover aliases, filter
bounds, suggestions, permission checks, inspect forms, invalid pages, and
asynchronous no-result handling. Lookup pages keep
per-player state, expire after 30 minutes, cap offset at 10,000 rows, and emit
Previous/Next commands tied to the same session.
Fabric and NeoForge dispatcher tests execute both inspector roots and verify
the toggle, explicit `on`/`off`, `status`, and permission denial. Both assert
the same seven exact chat receipts for the published toggle plus ItemGraph's
explicit forms. These checks do not
replace the required vanilla-client command replay. Both loader command suites
also encode and decode the full tree through Minecraft 1.21.1's
`ClientboundCommandsPacket` codec, then verify `/itemgraph` and `/ig` survive
and `/gl` and `/grieflogger` remain absent. This exercises vanilla packet
serialization but not a connection or client-side command rendering. Paired
server GameTests also send `ServerboundChatCommandPacket` through each loader's actual
`handleChatCommand` handler for `itemgraph inspect on`, `ig inspect status`,
and `ig inspect off`. They verify permission level 2, player-scoped state
transitions, exactly one persisted `COMMAND_ATTEMPT` per packet, player and
timestamp fields, and unchanged JDBC value and Java type snapshots for every
`ig_observations` cell. Their shared fixture reads ItemGraph's database only. These embedded
mock-player tests establish handler-to-ledger behavior; they do not establish
client socket transport, rendered command output, or GriefLogger differential
parity. The required connected vanilla-client replay remains open under #24.
`pageSessionTokensAreIsolatedByPlayerAndExplicitlyClearable` and
`lookupPageSessionCannotBeResolvedByAnotherPlayerAndCanBeCleared` verify a
copied token cannot expose one player's page to another level-2 player and that
the shared cleanup helper invalidates the owner's token. The
`NativeAuditEventListener.onPlayerLoggedOut` handler clears the NeoForge page
token and records `PLAYER_QUIT`; NeoForge inspection cleanup is handled by
`InspectionListener`. Fabric's
`FabricNativeAuditEventListener.onDisconnect` clears that player's page token
and inspection mode, records `PLAYER_QUIT`, and closes its container session.
`NativeAuditEventListenerTest.playerLogoutClearsItsPageSessionAndRecordsQuit`
and
`FabricNativeAuditEventListenerTest.disconnectHandlerClearsOnlyThatPlayersStateAndRecordsPlayerQuit`
invoke those audit handlers directly. The Fabric inspection logout behavior is
also covered by `InspectionListenerTest.logoutClearsOnlyThatPlayersInspectionMode`.
The Fabric callback registration and NeoForge event-bus registration remain
source-inspected; these tests do not simulate socket disconnect transport. The
output continues to label evidence source/type and retain evidence IDs; ItemGraph-only
output remains an explicit extension.

Fabric `FabricItemGraphPageDispatchTest` also executes `/ig page` and
`/itemgraph page` without an active session and checks the exact failure text,
rejects malformed and expired explicit session tokens, removes expired per-player
state, denies a copied token owned by another player without invalidating the
owner's session, and verifies permission level 2 at dispatch on both roots. This
complements NeoForge's execution-level page tests; Brigadier tests do not verify
vanilla client transport or rendered clickable chat controls.

| GriefLogger capability | ItemGraph native source | Storage | Query/UI status | Evidence status |
| --- | --- | --- | --- | --- |
| Container add/remove net deltas | `ContainerSessionListener`, capability wrappers | `ig_observations` | `/ig trace` and `/ig gui` | Implemented and tested; the 2026-09-29 Fabric replay persisted `ADD_ITEM` and `REMOVE_ITEM` rows |
| Item drop/pickup/death drops | NeoForge `ItemEntityEventListener`; Fabric `ServerPlayerMixin`, `ServerLevelMixin`, and `ItemEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | NeoForge paths and Fabric normal, vanilla player-death, and custom death-event item additions are implemented; the Fabric replay persisted accepted `DROP_ITEM` and `PICKUP_ITEM` rows |
| Hopper/mechanical automation (ItemGraph supplemental) | NeoForge capability wrappers; Fabric `HopperBlockEntityMixin` | `ig_observations` | `/ig trace` and `/ig gui` | GriefLogger's published feature surface has no hopper or mechanical-automation event; ItemGraph records successful vanilla hopper net deltas with unknown endpoints, while modded automation adapters remain an optional extension |
| Crafting and smelting; anvil lineage extension | NeoForge `TransformationEventListener`; Fabric `ResultSlotMixin`, `FurnaceResultSlotMixin`, `AnvilMenuMixin` | `ig_item_transformations` | Item lineage in trace | GriefLogger records crafting and furnace output under `CRAFT_ITEM`; both loaders preserve that source meaning and add ItemGraph `SMELT`, `ANVIL_RENAME`, and `ANVIL_REPAIR` lineage rows at server result-take boundaries; the Fabric replay persisted `CRAFT`, `SMELT`, and `ANVIL_RENAME` rows |
| Player join/quit | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted `PLAYER_JOIN` and `PLAYER_QUIT` rows |
| Chat messages | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted `CHAT_MESSAGE` rows and returned them through `/ig lookup` |
| Player commands | `NativeAuditEventListener`, Fabric `CommandsMixin` | `ig_audit_events` | `/ig lookup` | Both loaders record `COMMAND_ATTEMPT` at the pre-execution dispatch boundary, matching GriefLogger's documented behavior of recording attempts regardless of permission or command success; `COMMAND_EXECUTED` remains reserved for legacy rows and is never fabricated |
| Block place/break | `NativeAuditEventListener`, Fabric break callback, Fabric `BlockItemMixin` | `ig_audit_events` | `/ig lookup` | NeoForge place/break and Fabric place/break capture/query implemented; the Fabric replay persisted `PLACE_BLOCK` and `BREAK_BLOCK` rows |
| Block interaction | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Both loaders record main-hand attempts against the exact 28-class 1.21.1 GriefLogger target set as `INTERACT_BLOCK_ATTEMPT`; modded `Container` inspection remains separate, and pre-use callbacks do not claim that block use completed. The Fabric replay persisted interaction attempts. |
| Player-killed entities | `NativeAuditEventListener`, `FabricNativeAuditEventListener` | `ig_audit_events` | `/ig lookup` | Capture/query implemented; the Fabric replay persisted a `KILL_ENTITY` row for a player-killed zombie |
| Entity interaction and Ender inventory actions | NeoForge `NativeAuditEventListener` and `ArmorStandInteractionMixin`, Fabric aggregate `UseEntityCallback` audit decorator and `ArmorStandInteractionMixin`; shared `EnderChestInteractionTracker` bound by both menu adapters | `ig_audit_events` for attempt, denied, handled-result, and unresolved-result evidence; `ig_observations` for ItemGraph Ender session deltas | `/ig lookup INTERACT_ENTITY`, `/ig lookup INTERACT_ENTITY_COMPLETED`, `/ig lookup INTERACT_ENTITY_DENIED`, `/ig lookup INTERACT_ENTITY_UNRESOLVED`, `/ig lookup filters`, and `/ig trace` | Both loaders retain entity-use attempts, target UUID when available, and held stack registry ID/count/fingerprint without raw component values. NeoForge retains canceled specific/generic callbacks and suppresses only duplicate generic attempts; the armor-stand mixin hooks the `interactAt` override and the entity mixin hooks inherited fallback `interact`, with fallback-method `PASS` explicit as `INTERACT_ENTITY_UNRESOLVED` (without claiming the full entity-use pipeline ended). These are method results, not proof of equipment movement. No non-armor-stand target class has a method-result hook. Those records retain the entity registry ID in `subject_id`, declare `target_support=callback_only`, and carry `target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT`; callback-level denied or unresolved outcomes remain recordable. Armor-stand records declare `target_support=armor_stand_method_result`. Fabric wraps the aggregate `UseEntityCallback` invoker and records one final non-`PASS` result, including short-circuits before or after ItemGraph's listener. Both loaders have local server GameTests that dispatch entity-use packets through the server handler, verify a cow attempt, armor-stand equip/unequip packet attempts and `interact_at` results, then separately invoke inherited `ArmorStand.interact` directly and verify its `PASS` return is retained as unresolved; the direct call is a method-hook check and is not attributed to a packet. Both runs assert duplicate-free persisted counts. The shared `EntityInteractionConformanceFixture` checks the same event-type and detail contracts through read-only `AuditEventQueryService` and `QueryFormatter`, including normalized actor, dimension, position, subject, timestamp presence, and console formatter output. Each test compares the complete `ig_observations` row snapshot before and after, proving the replay neither adds nor mutates quantity-flow evidence. The formatter is called directly; these tests do not execute `/ig lookup` through command dispatch. They use GameTest mock players and direct server-handler calls, so they do not prove real client transport. The exact GriefLogger 1.2.10-1.21.1 binary has no entity interaction writer; the 26.2 source has a success-only armor-stand writer. Entity capture work is closed in #75; the exact-release mapping is `unsupported-no-writer` and is no longer counted as unresolved action coverage under [#27](https://github.com/DurdeuVlad/itemgraph/issues/27). Ender action IDs 9 and 10 exist in the source enum but have no writer in the exact release binaries; the registry marks them `unsupported-no-writer` with reason `NO_WRITER_IN_EXACT_1_2_10_1_21_1_RELEASE` under [#76](https://github.com/DurdeuVlad/itemgraph/issues/76). ItemGraph's signed Ender rows come from its own `ender_inventory_session_net_delta` capture under `ITEMGRAPH_INTERNAL` and are an extension, not mapped GriefLogger actions. |
| Consume, break, throw, shoot item actions | NeoForge `NativeItemActionEventListener`, NeoForge `ProjectileMixin`, NeoForge `ServerLevelMixin`, `ItemEntityEventListener`; Fabric `LivingEntityMixin`, `ItemStackMixin`, `ProjectileMixin`, `ServerLevelMixin` | `ig_observations` for the GriefLogger-compatible attempt row; `ig_audit_events` for the accepted-spawn extension | `/ig trace`, `/ig gui`, and `/ig lookup` | NeoForge and Fabric record completed eat/drink consumption at the return boundary, durability breaks at the `ItemStack.hurtAndBreak` shrink boundary, and `THROW_ITEM`/`SHOOT_ITEM` at the exact `Projectile.shootFromRotation` HEAD attempt boundary with the canonical source stack and observed count. Projectile attempt rows carry a durable `source_event_id` derived from their UUID event identity, so worker retries cannot manufacture a second quantity row. Accepted player-owned spawns are retained as `PROJECTILE_SPAWN_ACCEPTED` raw evidence only after `ServerLevel.addFreshEntity` returns true, without a second quantity row or quantity claim in the audit detail. Projectile type and coordinates remain raw evidence; no projectile UUID or landing location is claimed. |
| Location/action filtered lookup | `AuditLookupFilters`, `UnifiedEvidenceQueryService`, `AuditEventQueryService` | `ig_audit_events`, `ig_observations`, `ig_item_transformations`, `ig_grieflogger_lookup` | `/ig lookup`, `/ig lookup near`, direct `/ig lookup <filter...>`, and `/ig lookup filters` | GriefLogger-style action/user/include/exclude/time/radius filters use one bounded asynchronous merge across native audit, item-flow, transformation, and normalized historical GriefLogger events. The published direct filter spelling now has token-aware suggestions and the ten-row default; the explicit `filters` literal remains an ItemGraph extension. Five-filter cap, required cube radius, AND semantics, global timestamp ordering, source/evidence IDs, and unresolved historical rows are tested; the Fabric replay returned rows from both `/ig lookup CHAT_MESSAGE 10 60` and `/ig lookup filters action.chat_message time.1h radius.50` |
| Block/container inspector history | NeoForge `InspectionListener`; Fabric `FabricNativeAuditEventListener`; shared `BlockInspectionTargets`, `UnifiedEvidenceQueryService`, and `AuditPageSession` | `ig_audit_events`, `ig_observations`, `ig_item_transformations`, `ig_grieflogger_lookup`, ItemGraph supersession tables | `/ig inspect`, `/ig page`, `/ig trace container` | Implemented in code: one exact-position, globally paginated timeline merges audit, item-flow, transformation, and imported rows; observation matching checks either endpoint. Double chests and doors deduplicate target cells. Schema V19 preserves block/door removal history through explicit supersession links, and normal lookup exposes each retained row and reason. Automated cross-loader tests pass. Issue #26 is closed, but maintainers skipped its visible-client click matrix; server/client click evidence remains unverified and is not claimed for #24 or #31. |
| Paginated generic audit results | `AuditEventQueryService` offset paging | `ig_audit_events` | `/ig lookup page <page> ...` | Bounded 1-based page offsets and server-generated Previous/Next chat controls implemented |
| Historical GriefLogger schema | `GriefLoggerHistoricalImporter`, `GriefLoggerHistoricalProjection`, and `GriefLoggerAdapter` | `ig_grieflogger_import_runs`, `ig_grieflogger_import_checkpoints`, `ig_grieflogger_rows`, `ig_grieflogger_lookup` | `/ig ingest history` and `/ig lookup provenance` | The supported core schema is validated before import; all 11 pinned source tables are retained as provenance rows with source/schema fingerprints, resumable per-table checkpoints, primary-key/hash/ordinal keys, retained source rowids, binary payload preservation, unresolved reasons, independent writer batches, and durable failed-run counts. Primary-key source keys over 191 Unicode codepoints use a deterministic `pksha256:` SHA-256 digest over length-framed UTF-8 values to fit MySQL/MariaDB composite indexes without delimiter ambiguity; the immutable payload retains the original primary-key fields and values. Resume recognizes checkpoints written before this normalization and preserves the existing ledger key when rebuilding a projection. Event tables are normalized into bounded unified lookup rows with historical username resolution. Reference and identity rows remain raw source-hash/table/key provenance results and never become quantity evidence. |

### Ender action writer determination (#76)

The exact release fixture declares `ADD_ITEM_ENDER` (ID 9) and
`REMOVE_ITEM_ENDER` (ID 10) in its action enum. The checked-in fixture now also
records the binary writer audit. `tools/validate_grieflogger_release_fixture.py`
downloads both official Modrinth artifacts, verifies their pinned byte sizes and
SHA-1/SHA-256/SHA-512 checksums, and scans every classfile for references to the
`ItemAction` enum class and both action symbols. In the Fabric jar (SHA-256
`07839dca10f93b3c0be543fa9f529ba69dcb8fe347d5234f8bc5248ec0113278`) and the
NeoForge jar (SHA-256
`fd252bc5466bb94e38d2386bafb9926b798bc250b26e1a3aa80f878ebccbc4a5`), both
action-symbol strings occur only in
`com/daqem/grieflogger/model/action/ItemAction.class`; the classfile scan also
finds no Ender enum field references outside the enum declaration. The only
`ItemAction.values()` callsites are `Actions.<clinit>`, which builds the generic
action-name catalog used by `getAction`/`getActions`, and `ItemAction.fromId`,
the enum's own ID decoder. The only external `fromId` call is
`ItemHistory.<init>`, which reconstructs a stored history row. No writer class
references Ender action constants or selects them dynamically. The
pinned 26.2 source audit reaches the same result: the enum values exist, but
there is no writer for them. The target-specific binary scan is authoritative
because the pinned source metadata targets 26.2 rather than the published
1.21.1 jars.
The registry therefore uses `unsupported-no-writer` with stable reason code
`NO_WRITER_IN_EXACT_1_2_10_1_21_1_RELEASE`. It does not count these mappings
as unresolved parity targets.

ItemGraph continues to record Ender menu session net deltas. Those rows have
`source_type=ITEMGRAPH_INTERNAL`, raw capture
`ender_inventory_session_net_delta`, and a player-owned external inventory key
`minecraft:ender_chest/<player UUID>`. They are explicitly labeled as an
ItemGraph extension and do not claim transaction-level parity with a GriefLogger
writer. CoreProtect offers a useful design comparison: its [API v13
`itemLookup`](https://docs.coreprotect.net/api/version/v13/) includes Ender
transfers, while `inventoryLookup` normalizes transfers to addition/removal on
the player's inventory and retains source/action IDs. ItemGraph keeps its
current interval-based net-delta model and makes no claim that CoreProtect's
behavior or code is part of the GriefLogger contract.
| MySQL/MariaDB backend | SQLite only | ItemGraph-owned SQLite or MySQL/MariaDB | Same migration/query contract; CI runs disposable MariaDB 10.11 and MySQL 8.0 services | Implemented in the shared JDBC dialect layer; issue #29 remains open until hosted CI proves clean setup, upgrade, restart, and read-only paths |

## Data boundary

`ig_observations` remains the item quantity-flow ledger. `ig_audit_events` stores
non-quantity evidence so a chat message, command, block action, or session event
cannot be misrepresented as an item transfer. Both tables are owned by ItemGraph.
The GriefLogger database remains read-only during migration and is not removed
as part of native-only cutover; only the runtime jar/config is retired after the
checksummed source copy and rollback evidence are approved.

GriefLogger stores chat and command rows for external review and does not include
them in its in-game lookup merge. ItemGraph's unified lookup intentionally extends
that surface; the extension remains labeled by source and evidence class.

The lookup filter parser accepts the published aliases and value forms, and
completion offers unused filters, pinned GriefLogger action names, ItemGraph
actions, registered item identifiers, online player names, current names from
imported GriefLogger `users` rows, and historical names from `usernames` rows.
GriefLogger's pinned `UserFilter` reads its cached options from the `users` table;
ItemGraph also offers the retained historical `usernames` values. Name reads run through the
bounded asynchronous query worker, use ItemGraph's read-only database connection,
and are cached for 30 seconds; malformed provenance payloads are skipped without
breaking completion. Completion includes reference rows even when a username has
no corresponding event row.

GriefLogger removes interaction rows when a block or door is removed. ItemGraph's
raw evidence is immutable, so parity work must use an explicit supersession or
tombstone record to reproduce the visible active-history result without deleting
the supporting observation.

The hopper/mechanical-automation row is supplemental ItemGraph coverage. It is
not required to replace a GriefLogger capability because GriefLogger does not
record those transfers.

ItemGraph's query page cap defaults to ten rows and is operator-configurable in
the range 1–100. Raw evidence is retained indefinitely and is never automatically
purged. `ingestion.queue_frequency_ticks` defaults to 20, accepts the source
range 1–100, and flushes on server end tick through a background worker; bounded
SQL batch passes continue until the backlog drains. The isolated local NeoForge
21.1.248 GameTest on Minecraft 1.21.1 persisted 8,000 uniquely tagged queued
audit events with zero drops, zero backlog, and exactly 8,000 matching durable
rows. The latest local run recorded a queue peak of 7,900 across the three
bounded queues and a slowest 400-event producer batch of 9.299 ms, below the
existing 50 ms per-batch server-thread budget. The isolated Fabric GameTest used
a 20-tick cadence and persisted all 32 accepted queue events after the end-tick
callback with zero drops; all three registered Fabric GameTests passed locally.
CI uploads redacted reports for both loader SQLite probes, NeoForge and Fabric
512-event probes against disposable MySQL and MariaDB services, and a NeoForge
shutdown saturation probe. Each network probe runs 20 read-only ledger count
queries across four worker threads and 20 registered `/ig lookup radius.20`
commands while synthetic hopper, automation, and modded-inventory audit events
are submitted. The command probe requires returned evidence and successful
server-thread callbacks, and reports aggregate dispatch-to-callback latency. It
uses mocked server/player objects; it does not measure live-client delivery,
real server tick impact, or third-party adapter behavior. The shutdown probe
rejects one event beyond the 10,000-item audit queue capacity and verifies every
accepted event is durable after worker-owned shutdown draining. Idle baselines,
adapter-specific load, and staging-derived latency/memory budgets remain open
under [#32](https://github.com/DurdeuVlad/itemgraph/issues/32).
Five isolated local NeoForge runs measured the 10,000-event SQLite shutdown
drain at 626–666 ms; CI now applies a 1,000 ms regression budget to that exact
healthy-database fixture. This is not a hard deadline for stalled JDBC I/O and
does not establish a production budget.
The metrics
and current probe limits are documented in [configuration](CONFIGURATION.md)
and the [test plan](TEST_PLAN.md); they do not establish staging budgets.
`helloFrequency` defaults to 600 ticks
(30,000 ms at 20 TPS); disposable MariaDB and MySQL CI services verify successful
background JDBC heartbeats and reconnection after a closed connection. ItemGraph's
`server_side_only=true` is an explicit invariant; false is unsupported and is
classified as a strict ItemGraph extension to preserve vanilla-client support.

## Native-only cutover and retention plan

The cutover decision is binary: ItemGraph may retire the compatible artifacts
only after every tracked GriefLogger parity acceptance gate is closed and the
evidence below is recorded.
Until then, the standard and compatible loader jars remain distinct so an
operator can choose a dependency-safe migration path. After cutover, the
standard ItemGraph jar is the only supported runtime artifact; GriefLogger is
not a runtime dependency. The read-only importer remains available for a
checksummed historical database when an operator explicitly configures it.

1. Before cutover, stop the staging server and make an immutable, checksummed
   copy of the GriefLogger database. ItemGraph may read the source during the
   comparison window, but never writes to it.
2. Run ItemGraph native-only with the GriefLogger JAR absent for a complete
   24-hour staging window. Verify the acceptance gates above and record the
   ItemGraph schema version and row-count report.
3. Keep ItemGraph raw evidence indefinitely. Store the GriefLogger source-copy
   checksum beside that immutable backup; its archive and retention are governed
   by the operator's policy, with no ItemGraph purge.
4. Remove only the GriefLogger JAR/config after an operator approves the
   checksum and acceptance report. The optional migration bridge is disabled by
   default; set `general.grieflogger_integration_enabled=true` (NeoForge) or
   `grieflogger_integration_enabled=true` (Fabric) only when migration sync or
   historical import is required. This setting does not disable native capture.
5. Rollback is bounded: restore the GriefLogger JAR and its immutable database
   copy, leave ItemGraph's database untouched, and re-run the staging checks
   before any production decision. The recorded rollback evidence is the source
   checksum, the restore command and timestamp, the successful read-only schema
   check, and the post-restore staging acceptance report. This plan does not
   authorize production changes.

### Differential report comparator foundation

`tools/itemgraph_differential_report.py` defines normalized report schema
version 5 for #31. The shared
`ItemGraphReplayReportFixture` writes raw schema version 3 with six durable
movement rows and seven allowlisted audit rows checked by the NeoForge and
Fabric GameTests. CI normalizes and
validates each loader report, then uploads the redacted JSON as a workflow
artifact. The normalizer labels the output `system=itemgraph` and
`runtime_mode=native_only`; this native-only export does not claim a
GriefLogger side-by-side comparison. The tools read exported JSON only; they do
not open or modify either system's database. The GameTest event timestamps are
the persisted observation timestamps and are not seeded; the integer seed
identifies the deterministic scenario setup, not a fixed clock. These native
exports validate the data contract and redaction but cannot be compared as a
timestamp-exact paired replay. A future #31 capture must establish a shared
replay clock or a documented timestamp comparison rule while retaining the
original timestamps. Every normalized report is pinned to the
`compatibility_version`, `source_profile_sha256`, and exact-release
`release_fixture_sha256` from the checked-in registry and fixture. Reports
must name the same loader, deterministic `scenario_id`, and integer `seed`, with
GriefLogger captured in `grieflogger_present` mode and ItemGraph captured in
`native_only` mode.

Each native ItemGraph report also contains a read-only whole-database audit
summary from `AuditService.audit`: observation, active-edge, allocation, and
transformation totals plus per-observation over-allocation, invalid per-edge
allocation/evidence links, invalid edge-time links, non-positive-quantity,
orphaned-allocation, invalid-edge-node, and status-mismatch counts. Every active edge must have
SOURCE and DESTINATION allocations whose sums each equal the edge amount.
Those allocations must reference direct edge evidence, match the edge
fingerprint and action direction, and use the source and destination actor
endpoints recorded by the edge; other allocation roles are invalid. Each
observation's total active allocation must also stay within its amount. Edge
start/end timestamps must equal the allocated source/destination observation
timestamps in forward order. The fixture reads these counters in one read-only transaction snapshot (explicit
`REPEATABLE_READ` for MySQL/MariaDB) and refuses to write an unhealthy report.
The normalizer independently requires every
invariant violation count to be zero and checks that `healthy` agrees with
those counts. The comparison result retains the validated audit summary. This
summary exports counts only and omits database row IDs and violation details.
It covers the complete database attached to the isolated GameTest run, not just
the 13 events in the replay report.

Each event has a unique scenario-local `event_key`, a unique integer `sequence`,
and explicit normalized action, evidence class, quantity, item registry ID,
Unix-millisecond timestamp, dimension, integer block position, optional
namespaced `subject_id` for the affected entity or block, replay-local actor
reference, raw source table/action identity, separately normalized
compatibility table/action identity, privacy class, and unresolved reason.
ItemGraph raw identity remains its actual source table (`ig_observations` or
`ig_audit_events`) plus the native action string; the normalized compatibility
action ID and table are explicit profile-derived fields and are never
represented as the raw ItemGraph source identity. Actor
references must use an `actor:replay-<alias>` value;
privacy classes are `replay_fixture_only` or `staging_restricted`, and
unresolved reasons are stable uppercase codes. The output contains a digest of
the scenario ID, uses only the integer sequence to refer to events, and replaces
actor references, block coordinates, and unresolved reason values with
`[REDACTED]`; raw event and actor keys are never echoed, including in validation
errors.
Reports must not contain player UUIDs, names, or raw
payloads. The comparator checks event multiplicity, profile-mapped action,
action ID, evidence class and table family, every listed field, profile pins,
and chronological order against the recorded sequence. Positive `signed_delta`
quantities are required for `ADD_ITEM`, `PICKUP_ITEM`, `ADD_ITEM_ENDER`, and
`HOPPER_INSERT`; negative quantities are required for `REMOVE_ITEM`,
`DROP_ITEM`, `BREAK_ITEM`, `CONSUME_ITEM`, `THROW_ITEM`, `SHOOT_ITEM`,
`REMOVE_ITEM_ENDER`, and `HOPPER_EXTRACT`. Transformation quantities must be
positive result counts, and actions without quantity semantics require null.
The comparison follows SQLite's SQLLogicTest precedent: deterministic inputs
are compared against the same expected results across systems, and differences
remain visible rather than being normalized away ([SQLLogicTest method](https://www.sqlite.org/sqllogictest/doc/trunk/about.wiki),
[SQLite testing strategy](https://www.sqlite.org/testing.html)). ItemGraph keeps
that strict default while giving documented native extensions explicit,
profile-pinned issue links.

Unknown source actions remain visible as `UNRESOLVED_SOURCE_ACTION` with a
stable reason and system raw identity; GriefLogger uses its numeric source ID,
while ItemGraph preserves its native action string. Neither can claim an item
identity or quantity. Unknown fields, duplicate JSON keys, duplicate event
keys or sequences, unclassified evidence, backwards timestamps, and unresolved
events without a reason are rejected. Every difference is preserved in the
JSON output. The checked-in action profile is the only source of accepted
exceptions: each exception names its exact difference kind, ItemGraph source
table, reason code, and owning issue. Unsupported GriefLogger actions link their
exception to the closed evidence issue recorded on the action row. ItemGraph-only
extensions carry their own issue link because they have no GriefLogger
`evidence_issue`: hopper deltas link to [#34](https://github.com/DurdeuVlad/itemgraph/issues/34),
smelting/anvil lineage links to [#57](https://github.com/DurdeuVlad/itemgraph/issues/57),
entity interaction links to [#75](https://github.com/DurdeuVlad/itemgraph/issues/75),
and Ender inventory deltas link to [#76](https://github.com/DurdeuVlad/itemgraph/issues/76).
An issue-linked difference remains visible and keeps
`equivalent=false`, but does not fail the comparison gate. Every other mismatch
is classified `unexplained` and fails the gate. Exceptions currently cover the
documented native-only transformation, automation, entity-interaction, and
Ender-inventory events; a quantity, timestamp, endpoint, privacy, or evidence
class mismatch, or wrong native source table never inherits an action-level exception. The comparator still
requires exact per-event quantities and rejects any whole-database allocation
or integrity violation reported by `AuditService`.

The GameTest export contains 13 checked durable rows: six quantity observations
for chest deposit and withdrawal, ground drop and pickup, and projectile throw
and shoot; one successful source-water pickup audit row; three entity
interaction attempts; two handled armor-stand results; and one unresolved
inherited-method result. Audit rows
retain only their namespaced `subject_id`, event identity, timestamp, dimension,
relative position, and replay-local actor. They exclude raw audit detail and
payloads. The export excludes
database IDs, player UUIDs and names, raw payloads, and absolute world
coordinates. Positions are block coordinates relative to the GameTest
structure origin; actors use fixed replay aliases. Events sort by persisted
timestamp, source table, and source row ID; row IDs are not exported. This gives
deterministic ordering when events share a millisecond across source tables. CI
requires the fixed scenario ID, seed,
13 event records with pinned per-action counts, unique event keys, and contiguous
sequence, tests malformed inputs, and pins each report to its loader and source
profile. The raw report schema is v3 and normalized report schema is v5. It does not start
GriefLogger or compare its live database rows. A paired GriefLogger capture,
issue-linked exception policy, the 24-hour native-only staging window, and
rollback rehearsal remain open #31 acceptance criteria. The whole-database
quantity and integrity audit is included in each native report and enforced as
a zero-violation gate; it does not replace paired legacy/native replay.

## Verification notes

- **2026-09-30, NeoForge native-only startup:** the staging `:neoforge:runServer`
  launch now includes the shared `common` and `core` source sets in the ModDev
  run. The dedicated server loaded `itemgraph-neoforge.mixins.json`, initialized
  Mixin 0.8.7, reached `Done`, applied schema migration v16, and reported
  GriefLogger disabled without a mod-loading or mixin error. This proves the
  NeoForge runtime can load the mixin configuration.
- **2026-09-30, connected-player projectile parity:** GriefLogger-absent
  Mineflayer replays ran against Fabric on `127.0.0.1:27993` and NeoForge on
  `127.0.0.1:27994`, each with a fresh world and ItemGraph schema v16. Each
  replay performed one snowball throw and one bow shot. Read-only SQLite checks
  found one `THROW_ITEM` and one `SHOOT_ITEM` row in `ig_observations`, each with
  a non-null `source_event_id`, and exactly one matching
  `PROJECTILE_SPAWN_ACCEPTED` audit row per projectile in `ig_audit_events`.
  Duplicate `(source_type, source_event_id)` queries returned zero rows for both
  tables. The operator connected successfully after the command tree was changed
  to vanilla literal event-type nodes; the previous unserializable custom
  argument no longer disconnects clients. Compatibility artifacts remain gated
  by the unresolved action and operations mappings listed in the registry.
- **2026-10-01, projectile CI conformance:** paired NeoForge and Fabric
  GameTests call `Projectile.shootFromRotation` for a player-owned snowball and
  arrow, then require `ServerLevel.addFreshEntity` to accept both. Each loader
  verifies durable `THROW_ITEM` and `SHOOT_ITEM` rows with amount 1, source
  player and `UNKNOWN` destination, non-null idempotency keys, and matching
  attempt IDs in the native audit projection. Each accepted spawn has its own
  raw audit event whose action, subject and projectile identifier match the
  snowball or arrow, with no second quantity claim. Every prior quantity row
  remains byte-for-byte present, and exactly two new quantity rows are
  attributed to the replay player. These embedded mock-player GameTests validate the server hooks and
  durable read path; they do not replace connected-client or #31 differential
  replay evidence.
- **2026-09-29, Fabric native-only smoke:** the dedicated loopback staging server
  started with no GriefLogger JAR, applied the ItemGraph schema 13 migrations,
  loaded the Fabric mixins, and reached `Done` on port 27992. The ingestion worker
  correctly reported the GriefLogger source as unavailable and continued with
  native listeners. This verifies startup and isolation; player-action replay and
  row-by-row capture checks remain required before marking the event rows fully
  staging-verified.
- **2026-09-29, Fabric GriefLogger-absent row replay:** an isolated checkout on
  `C:\Users\User\itemgraph-staging-replay` ran on loopback `127.0.0.1:27993`
  with no GriefLogger JAR. The server reached `Done`, initialized schema 13,
  logged one informational GriefLogger-ingestion skip, and shut down cleanly.
  SQLite inspection after shutdown found `PLAYER_JOIN`, `PLAYER_QUIT`,
  `CHAT_MESSAGE`, `COMMAND_ATTEMPT`, `INTERACT_BLOCK_ATTEMPT`, `PLACE_BLOCK`,
  `BREAK_BLOCK`, `KILL_ENTITY`, `THROW_ITEM`, and `SHOOT_ITEM` audit rows, plus
  `ADD_ITEM`, `REMOVE_ITEM`, `DROP_ITEM`, `PICKUP_ITEM`, `CONSUME_ITEM`,
  `BREAK_ITEM`, `HOPPER_INSERT`, and `HOPPER_EXTRACT` observation rows. The
  replay also returned rows through both generic and filtered lookup commands.
  A first replay exposed a Fabric `NoSuchMethodError` when consuming an item;
  moving the bounded capture records outside the mixin package removed the
  invalid transformed constructor. The capture now matches HEAD and RETURN by
  a stable caller class/method marker and stack depth; stale or ambiguous
  markers are discarded rather than paired with another invocation. The final
  replay produced no kick or server exception.
- **2026-09-29, Fabric item-flow implementation:** normal player drops and
  pickups now use server-only `ServerPlayer.drop` and `ItemEntity.playerTouch`
  return hooks. Drop rows require the `addFreshEntity` acceptance result; pickup
  rows use the entity stack count delta, so partial pickups cannot manufacture
  quantity. Drops observed while `ServerPlayer.isDeadOrDying()` are labeled
  `DEATH_DROP`; custom item entities accepted during `ServerPlayer.die` are
  captured by a bounded death window and deduplicated against the normal drop
  hook.
- **2026-09-29, Fabric inspector implementation:** the Fabric `UseBlockCallback`
  now matches NeoForge inspection semantics. An enabled permission-level-2 player
  receives the shared read-only flow browser only for a block entity implementing
  `Container`; an accepted query returns `SUCCESS` and suppresses the normal GUI,
  while unsupported blocks, rejected queries, and permission loss preserve ordinary
  interaction. Disconnect and server-stop cleanup are covered by the adapter lifecycle.
- **2026-09-29, NeoForge block inspector implementation:** `/ig inspect` now
  handles only the initial left-click action and non-container right-clicks. The
  canceled event opens an asynchronous, permission-level-2 audit page constrained
  to the exact dimension and block coordinates, so adjacent blocks cannot leak into
  the result. Canceled inspection clicks are excluded from native audit capture;
  rejected block-history queries also keep the gameplay action canceled, and
  repeated left-click hold/abort packets are ignored. A valid double chest or
  door now expands to both physical positions in one ordered exact lookup; the
  container flow-browser node merge remains open in [issue #26](https://github.com/DurdeuVlad/itemgraph/issues/26).
- **2026-09-29, Fabric container sessions:** server menu initialization and close
  hooks now reuse the shared interval tracker for block containers and double chests;
  an orderly server stop flushes active session deltas before ItemGraph closes its
  database. `HopperBlockEntityMixin` snapshots only the hopper and its six adjacent
  container cells, then records successful net deltas with unknown endpoints; it does
  not infer a player or a modded automation cause. This is supplemental coverage;
  GriefLogger does not provide an equivalent hopper event.
- **2026-09-29, Fabric transformations:** result-slot hooks capture crafting,
  furnace-family smelting, and anvil rename/repair outputs with source/result
  canonical fingerprints. The isolated replay persisted one `CRAFT`, one
  `SMELT`, and one `ANVIL_RENAME` row.
- **2026-09-29, Fabric inspector replay:** with `/ig inspect on` enabled for an
  operator-level bot, right-clicking the populated chest opened the shared
  read-only `minecraft:generic_9x6` flow browser. Display rows were present and
  no normal mutable container window was opened; `/ig inspect off` cleared the
  mode before disconnect.
- **2026-09-29, filtered lookup implementation:** `/ig lookup filters` accepts up
  to five `name.value` filters matching GriefLogger's action, user, include,
  exclude, time, and radius vocabulary. Radius is required, clamped to 1..1024,
  centered on the issuing player, and applied as a cube; include/exclude conflicts
  are rejected before the asynchronous SQL query. Native subject IDs are normalized
  so bare Minecraft IDs such as `diamond_ore` match `minecraft:diamond_ore`.
  The filtered lookup now merges all three ItemGraph evidence tables. Quantity-flow
  and transformation actions retain their original action type and source/evidence
  reference; imported rows retain `GRIEFLOGGER` provenance. Every query remains
  bounded by the five-filter, radius, page-size, offset, and query-worker limits.

## Acceptance gates

1. Native capture tests prove one immutable row for each event category and no row
   for canceled command, chat, death, or block actions; persistence failures must
   retain the batch or count its loss without incrementing persisted counters.
2. A staging server with the GriefLogger JAR absent records join, quit, chat,
   command attempt, block, entity-kill, container, consume, break, shoot, and item
   events in ItemGraph storage. Fabric's interaction callback records the observed
   callback attempt. Command rows remain `COMMAND_ATTEMPT` because GriefLogger also
   records command attempts regardless of permission or command success.
3. A permission-level-2 lookup command returns those rows with player, location,
   subject, timestamp, and detail fields, with a bounded result limit. `/ig lookup near`
   applies exact dimension and a radius clamped to 1..1024 blocks. Interactive chat
   `/ig lookup page` controls continue pages with a 10,000-row offset ceiling and
   rerun the same bounded filters as the original query. `/ig lookup filters` accepts
   the five-filter GriefLogger syntax and uses a required cube radius around the player;
   the delivered native lookup/filter contract is tracked in [#25](https://github.com/DurdeuVlad/itemgraph/issues/25). Generic rows retained by the historical ledger are not returned by this query until the normalized projection in [#28](https://github.com/DurdeuVlad/itemgraph/issues/28) is complete.
4. The existing item-flow tests remain green and the GriefLogger database is not
   opened by the native-only path.
5. M8 is not complete while [#43](https://github.com/DurdeuVlad/itemgraph/issues/43),
   [#24](https://github.com/DurdeuVlad/itemgraph/issues/24), [#26](https://github.com/DurdeuVlad/itemgraph/issues/26),
   [#27](https://github.com/DurdeuVlad/itemgraph/issues/27), [#28](https://github.com/DurdeuVlad/itemgraph/issues/28),
   [#29](https://github.com/DurdeuVlad/itemgraph/issues/29), [#30](https://github.com/DurdeuVlad/itemgraph/issues/30),
   or [#31](https://github.com/DurdeuVlad/itemgraph/issues/31) still has an unresolved acceptance criterion.

References: [GriefLogger feature overview](https://daqem.com/projects/grieflogger),
[item usage](https://daqem.com/projects/grieflogger/wiki/player-actions/item-usage),
[player sessions](https://daqem.com/projects/grieflogger/wiki/player-actions/player-sessions),
and [chat and commands](https://daqem.com/projects/grieflogger/wiki/player-actions/chat-commands).
