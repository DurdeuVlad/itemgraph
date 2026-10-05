# Security and Permissions

## Threat model

ItemGraph can expose highly sensitive server information:

- hidden player locations
- base coordinates
- private container contents
- faction storage
- player behavior timelines
- movement of valuable items

For that reason, ItemGraph is not merely a convenience command. It is a privileged moderation system.

## Default policy

**Non-operators are denied by default.** When no permissions provider has an
explicit decision for a node, ItemGraph falls back to vanilla permission level 2.
A provider's explicit `false` denies that node even to a level-2 operator.

### Active command permission nodes

The command root requires `itemgraph.command`; commands with a listed leaf node require
both `itemgraph.command` and that leaf node. Permission nodes are exact strings; dots do not imply parent or wildcard inheritance.
Thus a lookup-only moderator must receive both `itemgraph.command` and
`itemgraph.command.lookup`. A provider's explicit `false` denies access even when
the player has vanilla permission level 2. An unset node resolves to vanilla level 2.

| Command or action | Required named node(s) | Rechecked at |
| --- | --- | --- |
| `/itemgraph`, `/ig`, help, status | `itemgraph.command` | Brigadier command registration/execution; status result delivery |
| `/ig lookup ...` | `itemgraph.command` + `itemgraph.command.lookup`; broad or protected audit results also require `itemgraph.audit` | Query construction, asynchronous result delivery, and next-page existence checks |
| `/ig page ...` and clickable lookup page controls | `itemgraph.command` + `itemgraph.command.page` + the originating lookup permission; protected sessions retain `itemgraph.audit` | Command execution, async result delivery, and before page controls or counts are produced |
| `/ig goto <token>` result-location action | `itemgraph.command` + every exact permission node used by the originating query | One-use player-bound token, again at click execution; dimension must be loaded and coordinates finite |
| `/ig inspect ...` and inspection-mode block clicks | `itemgraph.command` + `itemgraph.command.inspect`; inspection block-history queries and continuations that can include protected evidence also require `itemgraph.audit` | Toggle/status/click recognition: command + INSPECT; history query and delivery: INSPECT + AUDIT; container browser additionally requires GUI |
| `/ig trace ...` | `itemgraph.command` + `itemgraph.trace` + `itemgraph.audit` | Command execution and asynchronous result delivery; gated before candidate resolution so protected-only fingerprints/counts cannot leak |
| `/ig event ...` | `itemgraph.command` + `itemgraph.event` + `itemgraph.audit` | Command execution and asynchronous result delivery |
| `/ig explain ...` | `itemgraph.command` + `itemgraph.explain` + `itemgraph.audit` | Command execution and asynchronous result delivery |
| `/ig audit` | `itemgraph.command` + `itemgraph.audit` | Command execution and asynchronous result delivery |
| `/ig gui ...`, container flow menus | `itemgraph.command` + `itemgraph.gui` + `itemgraph.audit` | Command execution, async page/detail delivery, menu validity, and every menu click/navigation action |
| `/ig ingest now` | `itemgraph.command` + `itemgraph.ingest` | Command execution |
| `/ig ingest history` | `itemgraph.command` + `itemgraph.ingest` + `itemgraph.import` | Command execution |

World/environment event types expose sensitive coordinates. Lookups for
`EXPLOSION_BLOCK_CHANGE`, `PISTON_BLOCK_MOVE`, `PISTON_BLOCK_ATTEMPT`,
`WORLD_EFFECT_ATTEMPT`, `FLUID_BLOCK_CHANGE`, `FIRE_BLOCK_CHANGE`,
`ENDERMAN_BLOCK_MOVE`, `FALLING_BLOCK_CHANGE`, and `WORLD_EFFECT_UNRESOLVED` require
`itemgraph.command.lookup` plus `itemgraph.audit`. Direct, filtered, and nearby queries
carry that audit requirement into saved pages and one-use location actions.

Entity lifecycle lookups for `KILL_ENTITY` and `PROJECTILE_SPAWN_ACCEPTED` also expose
sensitive coordinates and require `itemgraph.command.lookup` plus `itemgraph.audit`.
Their direct, filtered, nearby, saved-page, and one-use location paths retain the audit
requirement.

Unified transformation lookups for `CRAFT`, `SMELT`, `ANVIL_RENAME`, and
`ANVIL_REPAIR` expose actor, item-fingerprint, and coordinate details. They require
`itemgraph.command.lookup` plus `itemgraph.audit`; saved pages and one-use location
actions retain that audit requirement. Item traces of those transformations require
`itemgraph.trace` plus `itemgraph.audit`.

The browser opened by right-clicking a container in inspection mode additionally
requires `itemgraph.gui`; the inspection click itself requires `itemgraph.command.inspect`.
Inspection mode is cleared when its named permission is revoked. Page sessions remain
player-scoped; permissions do not relax the existing session-token ownership checks.

### Loader provider behavior

- **NeoForge 1.21.1:** ItemGraph registers boolean `PermissionNode` values through
  `PermissionGatherEvent.Nodes` and resolves them with `PermissionAPI`. The default
  node resolver is `ServerPlayer.createCommandSourceStack().hasPermission(2)`. Console
  and command-block sources use vanilla permission level 2 because NeoForge's node API
  accepts a player.
- **Fabric 1.21.1:** ItemGraph embeds `fabric-permissions-api` `0.3.1`, so no extra
  API mod is required. `Permissions.check(source, node, 2)` uses the compatible
  permission-provider decision when present and vanilla permission level 2 when the
  provider returns `DEFAULT` or no provider is installed. To assign named grants,
  operators still need a compatible provider mod such as their server's permission
  manager.

There is no required LuckPerms or other permission-manager dependency. Keep default
access at level 2 when the provider is absent. Do not assign permissions by wildcard
unless the installed provider's documented policy explicitly expands that wildcard.
See `/ig help permissions` and the [copyable role recipes in the admin quick start](ADMIN_QUICK_START.md#2-grant-a-moderator-the-exact-permissions-they-need).

## Player-facing mode

A future player-facing mode may be useful for disputes, but it must be designed separately.

Possible safe rules:

- only show the player's own inventory events
- only show containers the player is currently authorized to inspect
- hide unrelated player identity
- hide coordinates outside currently authorized areas
- do not expose destination after an item leaves authorized scope

Example:

```text
14:31 You removed 1x named helmet from your chest
14:52 1x named helmet left your inventory
16:03 1x named helmet returned to your inventory
```

Not:

```text
14:52 Bob carried it to SecretBase at X 431 Z -882
```

## Faction-aware access

Do not implement faction permissions generically until the actual faction/team mod is identified.

Integration should use the mod's real ownership/membership API where possible.

## Audit of ItemGraph usage

Because ItemGraph itself is sensitive, admin queries should eventually be auditable.

Potential fields:

- moderator UUID
- command/query
- timestamp
- result scope
- export action

This protects both players and moderators.

## Database security

ItemGraph's own database may contain sensitive historical information.

Recommendations:

- keep database files outside public web roots
- do not expose DB ports unnecessarily
- use least-privilege credentials if using client/server DB
- do not log credentials
- redact secrets from diagnostics
- avoid storing arbitrary full NBT if it may contain unrelated secrets unless required

## Command safety

Queries should have:

- bounded default time windows
- result count limits
- permission checks before resolving sensitive nodes
- pagination
- rate limits if necessary

Lookup-only access covers explicitly selected, non-sensitive event types.
`CHAT_MESSAGE`, `COMMAND_ATTEMPT`, and `COMMAND_EXECUTED` rows can contain private
conversation, command arguments, or credentials accidentally typed into chat, so
they require both `itemgraph.command.lookup` and `itemgraph.audit`. An `all` query
or filter with no explicit event type can include these rows and therefore requires
`itemgraph.audit` before the result query or next-page probe runs. The same policy
applies to case aliases, player and near forms, and saved-page continuation.

Every `EventTaxonomy` definition marked `SENSITIVE_LOCATION` requires
`itemgraph.audit`, whether it is an audit event, item observation, or
transformation. This includes block placement/breaking, container breaks, block
and entity interactions, item-flow and automation actions, world causes, and
transformation results. Direct, filtered, nearby, paged, async-delivery, and
one-use location actions retain and recheck the same audit requirement. The
permission predicate is derived from the taxonomy privacy class so new sensitive
event IDs do not silently become lookup-visible.

Issue #33 administrative item-command and creative-inventory records are also
staff-private. `/give`, `/clear`, and `/item` attempt rows retain only the command
root and outcome; they do not copy selector expressions or command arguments.
Generic command history also suppresses `/execute` text because it can contain a
nested item mutation and private selectors or item arguments.
Confirmed inventory deltas include item fingerprints, quantities, slot identifiers,
and affected entity identifiers where needed to explain the observed mutation.
For nested `/execute as`, `actor_uuid` and `actor_name` identify the original
command issuer; a differing effective entity is retained separately as
`execution_context_actor_*`. `/item ... from block/entity` records the copied-from
endpoint and exact stack in staff-private evidence without implying that the source
slot lost quantity.
Keep `ADMIN_ITEM_COMMAND_*`, `CREATIVE_SLOT_*`, `CREATIVE_BLOCK_*`,
`ADMIN_ITEM_*`, and `CREATIVE_ITEM_*` restricted to the audit permission. Event-type
lookups and action filters that can return these categories require
`itemgraph.command.lookup` plus `itemgraph.audit`; broad lookup filters require
audit permission. Flow views are available only to users with the audit grant. A pre-execution loader callback is
only an attempt; only a post-mutation slot comparison can produce item-flow evidence.

The `/ig event`, `/ig explain`, `/ig trace`, and flow-browser surfaces are gated by `itemgraph.audit`
because those queries can include staff-private administrative-item and creative-inventory evidence,
including inferred edges supported by those observations. The audit gate is checked before any query,
candidate resolution, async result delivery, and every menu action; this avoids leaking protected-only
matches through counts, candidate pages, or error distinctions. The `/ig gui` browser also requires
`itemgraph.command` and `itemgraph.gui`. `FlowBrowserMenu` rechecks both named permissions while open
and on every click, and the menu never delegates an item-movement action to
`ChestMenu`; it handles only page navigation, flow selection, detail display, and close.
All page/detail SQL runs through the read-only `QueryDispatcher` connection. Its
numbered chat companion contains only rows from that authorized query and labels each
with evidence class, a safe item identity or explicit fallback, event kind, and UTC
time where available; the number maps to the corresponding menu slot. It does not
expose raw NBT/component payloads, unrelated players, or hidden inventories.

`/ig inspect` requires both `itemgraph.command` and `itemgraph.command.inspect`.
`InspectionListener` rechecks permission on every supported-container click, and permission
loss clears that player's inspection mode without suppressing the ordinary block interaction.
Inspection block-history requests return bounded chat history. Right-clicking a supported
container opens the read-only flow browser, which additionally checks `itemgraph.gui` and
`itemgraph.audit`;
inspection does not grant access to the live container inventory or relax any GUI check.
The inspection-mode toggle, `on`, `off`, `status`, and supported-click recognition require
`itemgraph.command.inspect`. A block-history request queries `eventType=all`, so it can
include staff-private evidence and additionally requires `itemgraph.audit` before the
query and before a page-continuation probe. Grant `itemgraph.audit` only to inspectors who
should see protected chat, command, administrative-item, and creative-inventory evidence.

Rich `/ig event`, `/ig explain`, `/ig trace`, and history lookup chat rows keep their
stable formatted text visible and attach a bounded hover summary containing evidence class,
safe item identity, canonical fingerprint hash when present, event kind, exact UTC time, and
recorded endpoints. Hover text never includes raw NBT or component serialization. A result
location link uses a short-lived one-use token bound to the requesting player and the exact
permission nodes used by that query. The server rechecks those nodes on click and resolves
only the recorded dimension and finite coordinates; a token cannot be replayed by another
player or after a permission is revoked.

## Preview API boundary

`docs/API.md` implements a `com.itemgraph.api` `PREVIEW_1` Java boundary for installed
server mods, not a player-facing permission grant. Trusted consumers may submit direct
observations and read full flow results, but they remain responsible for checking the
receiving player's permissions before displaying player UUIDs, coordinates, hidden
inventories, or custom metadata. `SourceHandle` is a final service-issued capability bound
to the server/service generation, so a caller cannot implement or reuse a forged handle.
Registration validates that the claimed `modId` exists in `ModList` and logs the claim
prominently; Java cannot cryptographically prove which loaded mod called the method, so the
PREVIEW_1 trust boundary still requires consumers to pass their own ID. API persistence writes only raw
`EXTERNAL_API` observations and the source registry in ItemGraph's own database; it does
not expose JDBC, schema objects, GriefLogger access, mutable Minecraft state, or
caller-provided inferred edges/confidence.

## Privacy-aware explanation

`/ig explain` should show enough evidence for moderation without automatically revealing unrelated sensitive data.

Permission filtering must apply at every layer, not only to the final formatted output.
