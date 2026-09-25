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

**Default deny.**

No sensitive graph access should be granted unless a permission explicitly allows it.

## Suggested permission structure

Exact permission integration depends on the server's permission system.

Conceptual permissions:

```text
itemgraph.admin
itemgraph.trace.item
itemgraph.trace.player
itemgraph.trace.container
itemgraph.event.view
itemgraph.explain
itemgraph.status
itemgraph.export
```

Potential restricted sub-permissions:

```text
itemgraph.view.coordinates
itemgraph.view.player_inventory
itemgraph.view.faction_storage
itemgraph.view.names
```

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

The `/ig gui` browser remains level-2 only. `FlowBrowserMenu` rechecks permission while
open and on every click, and the menu never delegates an item-movement action to
`ChestMenu`; it handles only page navigation, flow selection, detail display, and close.
All page/detail SQL runs through the read-only `QueryDispatcher` connection.

`/ig inspect` is likewise level-2 only. The command cannot be enabled by a non-operator,
`InspectionListener` rechecks permission on every supported-container click, and permission
loss clears that player's inspection mode without suppressing the ordinary block interaction.
Inspection opens only ItemGraph's read-only menu; it does not grant access to the clicked
container's contents and does not relax any GUI permission checks.

## Preview API boundary

`docs/API.md` proposes a `com.itemgraph.api` `PREVIEW_1` Java boundary for installed
server mods, not a player-facing permission grant. Trusted consumers may submit direct
observations and read full flow results, but they remain responsible for checking the
receiving player's permissions before displaying player UUIDs, coordinates, hidden
inventories, or custom metadata. The API must not expose JDBC, schema objects, GriefLogger
access, mutable Minecraft state, or caller-provided inferred edges. This boundary is a
proposal until issue #12 implements the approved contract.

## Privacy-aware explanation

`/ig explain` should show enough evidence for moderation without automatically revealing unrelated sensitive data.

Permission filtering must apply at every layer, not only to the final formatted output.
