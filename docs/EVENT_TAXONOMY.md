# ItemGraph Event Taxonomy

## Purpose and authority

`com.itemgraph.audit.EventTaxonomy` is the shared runtime catalog for stable
ItemGraph audit-event, item-observation, and transformation identifiers. It is
the source for the native audit command list, unified-lookup suggestions,
canonical aliases, and audit evidence-class display. This taxonomy describes ItemGraph behavior; it does not replace or
change the separate GriefLogger compatibility registry in
[`GRIEFLOGGER_COMPATIBILITY.json`](GRIEFLOGGER_COMPATIBILITY.json).

The taxonomy uses stable string identifiers because event IDs are persisted in
existing databases and older records must remain readable after new IDs are
added. A runtime that does not recognize an ID must preserve and display the
stored value; it must not relabel the row or infer its meaning from a similar
name. `EventTaxonomy.find(id, surface)` returns `Optional.empty()` for an unknown ID.
Unified audit results retain that stored ID and display `UNCLASSIFIED`; this is
an unknown taxonomy classification, not the `UNRESOLVED` evidence class.

## Versioning

The taxonomy version is `2.1.0` (`EventTaxonomy.VERSION`) and is independent of
the ItemGraph mod version and GriefLogger registry version.

- Increment the major taxonomy version when an existing ID is removed, renamed,
  changes evidence, quantity, reliability, endpoint, actor, or privacy meaning,
  or changes from queryable to non-queryable on a loader.
- Increment the minor version when adding IDs or adding loader support without
  changing existing meanings.
- Increment the patch version for wording or documentation corrections that do
  not change the contract.

Every definition names its owning GitHub issue. `HISTORICAL_ONLY`, `PLANNED`,
and `UNSUPPORTED` loader entries carry stable reason codes. `HISTORICAL_ONLY`
means the query surface may return preserved/imported rows but there is no
current native writer. A planned definition is not evidence that a runtime
listener exists; only `IMPLEMENTED` means the current loader adapter and its
fixtures establish capture support.

## Definition fields

| Field | Meaning |
| --- | --- |
| `id` | Stable uppercase persisted/query identifier. |
| `family` | Related event group, such as `world_environment` or `item_processing`. |
| `surface` | `AUDIT_EVENT`, `ITEM_OBSERVATION`, or `TRANSFORMATION` storage/query boundary. |
| `evidenceClass` | `OBSERVED`, `INFERRED`, `AMBIGUOUS`, or `UNRESOLVED`; catalog entries do not promote an inference to observation. |
| `sourceReliability` | Capture boundary: authoritative game result, callback attempt, direct state delta, correlated evidence, or unresolved cause. |
| `evidenceIds` | Every queued row has an ItemGraph `ingest_event_uuid` and may also retain the source-provided event ID. |
| `endpoints` | The endpoint relationship the event may support: world location, actor/target, source/destination, input/output, or unknown. |
| `quantity` | `NONE`, `SIGNED_DELTA`, `INPUT_OUTPUT`, or `UNKNOWN`. `UNKNOWN` never authorizes a quantity change. |
| `actor` | Whether the actor is a player, entity, world, or unknown. Unknown is not replaced by the nearest player. |
| `privacy` | Redaction policy category. `SENSITIVE_LOCATION` includes locations that can disclose private bases or storage. |
| `fabric`, `neoForge` | Independent capture/query states and a reason code for historical-only, planned, or unsupported coverage. |
| `ownerIssue` | The issue responsible for implementation and acceptance evidence. |
| `aliases` | Stable lookup spellings mapped to one canonical identifier. |

## Current native audit IDs

The audit query offers current event IDs and historical-only IDs on both loaders.
The capture state remains distinct: `COMMAND_EXECUTED` and `INTERACT_BLOCK` are
queryable for preserved/imported history but have no current native writer.
Other entries in this table are registered as implemented on both loaders.
The shared command and suggestion lists use the intersection of the two loaders'
queryable IDs, so every offered ID works on both Fabric and NeoForge.
These claims state the current capture boundary only; they do not claim complete
event coverage or full GriefLogger parity.

| Family | IDs | Evidence boundary |
| --- | --- | --- |
| Player session | `PLAYER_JOIN`, `PLAYER_QUIT` | Server-reported player lifecycle; player activity. |
| Chat and commands | `CHAT_MESSAGE`, `COMMAND_ATTEMPT`, `COMMAND_EXECUTED` | Callback attempt or execution receipt; player activity may contain private text. |
| Block actions | `PLACE_BLOCK`, `BREAK_BLOCK`, `INTERACT_BLOCK`, `INTERACT_BLOCK_ATTEMPT` | `PLACE_BLOCK` uses NeoForge's cancellable `EntityPlaceEvent`; `BREAK_BLOCK` uses NeoForge's cancellable `BreakEvent`, documented as a player attempt. The shared taxonomy conservatively labels both as attempts across loaders. [NeoForge 1.21.1 `EntityPlaceEvent`](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/neoforged/neoforge/event/level/BlockEvent.EntityPlaceEvent.html), [NeoForge 1.21.1 `BreakEvent`](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/neoforged/neoforge/event/level/BlockEvent.BreakEvent.html). Both are player-attributed at a sensitive world location. |
| Entity interaction | `INTERACT_ENTITY`, `INTERACT_ENTITY_COMPLETED`, `INTERACT_ENTITY_DENIED`, `INTERACT_ENTITY_UNRESOLVED` | Attempt, method/callback result, or explicit unresolved boundary; no quantity claim. |
| Entity/projectile | `KILL_ENTITY`, `THROW_ITEM`, `SHOOT_ITEM`, `PROJECTILE_SPAWN_ACCEPTED` | Captured player/entity event boundary; projectile attempt and accepted spawn remain distinct. |

The item-flow and transformation action IDs are cataloged on their own storage
surfaces. Current IDs include `ADD_ITEM`, `REMOVE_ITEM`, `DROP_ITEM`,
`PICKUP_ITEM`, `THROW_ITEM`, `SHOOT_ITEM`, `BREAK_ITEM`, `CONSUME_ITEM`,
`HOPPER_INSERT`, `HOPPER_EXTRACT`, `DEATH_DROP`, `ADD_ITEM_ENDER`,
`REMOVE_ITEM_ENDER`, `CRAFT`, `SMELT`, `ANVIL_RENAME`, and `ANVIL_REPAIR`.
Quantity rows use signed deltas; transformations use explicit input/output
semantics. The code-level definitions identify issue ownership and the exact
surface for each ID.

`CREATIVE_ITEM_TRANSFORM` remains classifiable for forward compatibility but is
`UNSUPPORTED` on both loaders with reason
`CREATIVE_TRANSFORM_CAUSE_NOT_REPORTED`. Creative slot packets prove separate
before/after quantity deltas; they do not prove a transformation between the
two fingerprints. The ID is therefore excluded from query suggestions. This
support correction increments the taxonomy major version while preserving the
stable identifier.
Unified lookup continues to show the legacy row and its source/result
fingerprints, but omits its free-form legacy details so an old numeric string
cannot be mistaken for a proven quantity. Item traces include only transformation
IDs with an implemented native writer on both loaders; shared queries have no
loader provenance. Unknown, malformed, and unsupported IDs remain queryable
evidence without becoming observed movement hops.

## Planned child issue families

These definitions are deliberately `PLANNED` on Fabric and NeoForge until a
loader adapter, success/cancel/partial/unsupported fixtures, and the child
issue's acceptance checks establish otherwise.

| Issue | IDs | Acceptance focus |
| --- | --- | --- |
| [#55](https://github.com/DurdeuVlad/itemgraph/issues/55) | `EXPLOSION_BLOCK_CHANGE`, `FLUID_BLOCK_CHANGE`, `FIRE_BLOCK_CHANGE`, `PISTON_BLOCK_MOVE`, `ENDERMAN_BLOCK_MOVE`, `FALLING_BLOCK_CHANGE`, `DISPENSER_EFFECT`, `DROPPER_EFFECT` | Authoritative world cause, before/after state, endpoint, cancellation/partial outcome, stable unresolved reason. |
| [#56](https://github.com/DurdeuVlad/itemgraph/issues/56) | `ENTITY_SPAWN`, `ENTITY_DESPAWN`, `ENTITY_KILL`, `PROJECTILE_LAUNCH`, `PROJECTILE_IMPACT`, `ITEM_ENTITY_SPAWN`, `ITEM_ENTITY_DESPAWN` | Separate attempt/result/lifecycle; use UUID only when supplied by the event; do not treat it as item identity. |
| [#57](https://github.com/DurdeuVlad/itemgraph/issues/57) | `TRADE`, `ENCHANTING`, `BREWING`, `SMITHING`, `GRINDSTONE`, `LOOT_GENERATION` | Explicit input/output or unknown inputs; failed/cancelled/partial operations remain distinct. |

No row should be emitted as a successful effect solely because an attempt
callback ran. No quantity may be manufactured from an `UNKNOWN` quantity
definition. An unavailable or ambiguous cause remains unresolved with its
reason code and supporting evidence IDs.

Initial unresolved reason codes are `WORLD_EVENT_API_UNAVAILABLE`,
`WORLD_EFFECT_PARTIAL`, and `CAUSE_NOT_REPORTED` (#55);
`ENTITY_CAUSE_NOT_REPORTED` and `PROJECTILE_IMPACT_NOT_AUTHORITATIVE` (#56);
and `TRANSFORMATION_INPUTS_NOT_OBSERVED` and `TRANSFORMATION_CANCELLED` (#57).
`CONTAINER_BREAK_UNRESOLVED` has taxonomy actor status `UNKNOWN` because some
unresolved cases lack an authoritative actor. Audit lookup labels a row with no
player identity `(actor unavailable)` and never assigns a nearby player.
Container-break reason codes (#140) distinguish actor unavailable,
block entity unavailable, inventory adapter explicitly known but unsupported,
slot limit exceeded, snapshot failure, queue rejection, and a drop relationship
not authoritatively linked. Non-inventory block entities such as signs are
ignored and do not produce container-break events.
Their exact definitions and owning issues are in
`EventTaxonomy.unresolvedReasonCodes()`.

## Relationship to other work

- #35 owns the taxonomy, evidence vocabulary, stable reason-code rules, and
  synchronization with the child issues.
- #55, #56, and #57 own the loader adapters and reproducible event fixtures.
- #34 owns modded inventory/automation endpoint contracts; event adapters must
  use that identity contract instead of guessing from location.
- #36 and #37 own the external API and redacted export surfaces that will expose
  these definitions and their version.

The GriefLogger compatibility matrix remains authoritative only for the exact
GriefLogger target and its mapped behavior. ItemGraph-only event IDs are
extensions and must not be represented as GriefLogger actions.
