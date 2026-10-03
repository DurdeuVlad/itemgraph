# World-event capture

## Implemented: explosion block changes

Fabric API `0.116.12+1.21.1` and NeoForge `21.1.248` do not provide a shared
post-explosion callback that identifies which affected blocks actually changed.
NeoForge `ExplosionEvent.Detonate` runs before final block effects and exposes a
modifiable candidate list; a candidate is not proof of a state change. Fabric
has no corresponding general world-explosion result event in its pinned API.

ItemGraph therefore uses a narrow, justified mixin at
`Explosion.finalizeExplosion(boolean)` on both loaders:

1. At method entry, inspect at most the first 4,096 candidate-list entries and
   copy each loaded position's current `BlockState`. Duplicate positions are
   deduplicated in the snapshot and can consume the entry cap. Do not load
   chunks. Record the full candidate count so capped or unavailable coverage
   is visible.
2. On normal return or exceptional exit, compare each captured state with the
   current state.
   Emit `EXPLOSION_BLOCK_CHANGE` only when the states differ, with no more than
   256 confirmed block rows per explosion.
3. Link changed-block rows with one `cause_event_id`; each row has its own
   `ingest_event_uuid`, full before/after state text, dimension, position, and
   source entity identifiers only when the explosion supplies an entity.
4. If candidate coverage was capped, a candidate read failed, chunks were unavailable, more than 256
   blocks changed, or the bounded audit queue rejects a confirmed row, emit one
   `WORLD_EFFECT_UNRESOLVED` row with `WORLD_EFFECT_PARTIAL` and the candidate,
   changed, represented, unavailable, and rejected counts. If the queue also
   rejects that summary row, emit one cause-correlated error log for the
   explosion rather than silently losing the evidence.

Both event types are audit-only and have `quantity=NONE`. The hook never creates
an actor from nearby players, never creates item observations, never traverses
inventories, and never performs database work. Rows are submitted to the
existing bounded `InternalObservationService` queue. Explosion block locations
are classified `SENSITIVE_LOCATION` and remain under operator-only audit lookup
permissions. If queue submission rejects an individual delta, the unresolved
summary reports attempted changes, accepted/represented rows, and rejected rows
separately. Runtime capture exceptions are logged and contained so they cannot
replace vanilla gameplay behavior.

`Explosion.finalizeExplosion(boolean)` wrapper is justified because the
available loader callbacks either report attempts/candidates before the block
effects or do not expose a general explosion result callback. Comparing state
around the final effect method gives a direct delta and avoids claiming a
candidate was destroyed. A canceled explosion that never reaches finalization
produces no changed-block row. If a mod throws after changing some blocks,
ItemGraph records the confirmed deltas with `finalize_exit=THREW` and a
`WORLD_EFFECT_UNRESOLVED` summary with `finalize_exception_count=1`; observed
changes remain visible without claiming that the explosion completed. Normal
returns use `finalize_exit=RETURNED` and emit the unresolved summary when bounded
snapshot coverage is incomplete.

The accepted result rows are capped at 256 per explosion to bound server-thread
queue offers. The capture still compares no more than 4,096 candidate entries;
when the actual changed count exceeds the row cap, the unresolved row records
the full observed count and how many changed positions received individual rows.
The queue uses non-blocking offers. Rejected rows increment ItemGraph's existing
rejection metrics, and this capture logs one error per explosion with its cause
ID and total rejection count.

The cross-loader GameTest fixture is
`ExplosionWorldEventConformanceFixture`. It checks one destructive explosion,
one no-block-effect explosion, before/after state, stable row and cause event
IDs, actor absence, bounded audit semantics, and no false event for an unchanged
target. The partial-coverage GameTest constructs one loaded
and one distant, unloaded candidate to exercise the unresolved writer; it does
not claim that a native explosion under normal conditions produced an
incomplete candidate list. It also simulates a throw after a captured block
change and verifies its confirmed delta and unresolved summary share one cause
ID. The over-cap fixture verifies that a synthetic
4,097-entry candidate list is marked partial and retains its full count; it does
not persist a native explosion with more than 4,096 candidates.

## Implemented: piston block changes

Both loaders wrap the server-side `PistonBaseBlock.triggerEvent` method with
MixinExtras `@WrapMethod`. At entry, ItemGraph resolves the vanilla piston structure
without changing the world and snapshots the piston/head, moved-block sources,
destinations, and destroyed positions. It reads at most 64 loaded positions and
does not load chunks. At return, only changed states become observed
`PISTON_BLOCK_MOVE` rows; a failed resolver or a trigger with no state delta is
stored as `PISTON_BLOCK_ATTEMPT` with `outcome=UNCHANGED` and
`source_reliability=GAME_CALLBACK_ATTEMPT`. It carries no before/after state
claim and is never presented as a successful transfer. Confirmed movement rows
retain `DIRECT_STATE_DELTA` reliability. Rows share a
`cause_event_id`, use `quantity=NONE`, omit player identity, and are submitted to
the bounded audit queue. Nested piston triggers use a thread-local stack so a
neighbor-triggered piston cannot overwrite its parent's snapshot.

The NeoForge source confirms `PistonEvent.Pre` is cancellable and
`PistonEvent.Post` fires after movement; the pinned Fabric API has no matching
piston callback. The shared result-boundary hook therefore works across both
loaders and records only observed state changes. A no-movement row reports the
callback result without claiming cancellation unless the loader reports that
fact. The wrapper's `finally` block releases its thread-local snapshot on both
normal and exceptional exits; exceptional exits record any safe state deltas
with `callback_result=THREW` while preserving the original exception. The shared
GameTest fixture `PistonWorldEventConformanceFixture` verifies
a real extension, source and destination deltas, shared cause identity, queue
persistence, a blocked no-movement result, and a simulated exceptional callback
after one sampled block changes on both loader adapters. Exceptional exits keep
confirmed partial deltas and emit a cause-linked `WORLD_EFFECT_UNRESOLVED` row
with `WORLD_EFFECT_PARTIAL` and `callback_exception_count=1`; an `UNCHANGED`
result alone does not represent an incomplete exceptional callback. These are
`PistonBaseBlock.triggerEvent` return-boundary deltas: the callback may expose a
transient `moving_piston` state, so rows do not claim that the destination has
reached its settled block state. If vanilla cannot resolve a movement plan,
direction is derived from piston facing and the requested extension/retraction.
If before-state snapshotting or after-state comparison fails, ItemGraph emits a
`WORLD_EFFECT_UNRESOLVED` row with `WORLD_EFFECT_PARTIAL`, the shared cause ID,
and a `capture_stage` value. If that summary is rejected by the bounded queue,
the capture logs one queue-rejection error for the piston family.

## Implemented: fluid spread, fire, Enderman, and falling blocks

The exact Minecraft 1.21.1 source and pinned loader sources were compared before
choosing these capture boundaries. NeoForge's `BlockEvent.FluidPlaceBlockEvent`
is a pre-placement hook; it does not prove that the resulting state was written.
The pinned Fabric API exposes no matching fluid-result event. Both loaders
therefore wrap vanilla `FlowingFluid.spreadTo` and compare the single target
state at method exit. A normal return is tagged `callback_result=RETURNED`. If
the call throws, any confirmed delta remains observed and is paired with a
cause-linked `WORLD_EFFECT_UNRESOLVED` row using `WORLD_EFFECT_PARTIAL` and
`callback_exception_count=1`; the boundary is tagged `spreadTo.throw`. This also
observes modded fluids that use the shared vanilla base class. No source
inventory or quantity is inferred. Fluid state
changes use `FLUID_BLOCK_CHANGE`, with the fluid registry ID and spread direction.

Fabric's event guidance recommends its shared event hooks where available and
mixins only where an event is absent. The pinned APIs have no result callback
for fire spread, Enderman block take/place, or falling-block landing. ItemGraph
uses bounded result-boundary mixins for those vanilla methods:

* Fire capture wraps the exact `ServerLevel.setBlock` and `removeBlock` writes
  from `FireBlock.tick`, the `Level.setBlock` and `removeBlock` writes from
  `FireBlock.checkBurnOut`, and the direct `BlockState.onCaughtFire` call. It
  records only a changed block type, omitting fire-age property updates. Each
  evidence row names the exact write boundary and target position; no nearby
  world scan is performed.
* `EndermanTakeBlockGoal.tick` wraps its exact `Level.removeBlock` call and
  `EndermanLeaveBlockGoal.tick` wraps its exact `Level.setBlock` call. A row is
  emitted only when that target's state changes. Metadata includes the Enderman
  entity UUID, never a nearby player identity. The carried-block state is not
  treated as item quantity or permanent item identity.
* `FallingBlockEntity.fall` records its confirmed source removal after return.
  If it throws after removing the source and before returning an entity, ItemGraph
  records the direct source delta without an entity UUID and adds a linked
  unresolved row with `callback_exception_count=1`. If it throws before the
  source state changes, only the unresolved row is emitted; it carries no
  `SOURCE_REMOVED` movement claim.
  `FallingBlockEntity.tick` wraps the exact landing `Level.setBlock` call. The
  vanilla falling-entity UUID is the shared cause ID for successful source and landing rows
  and remains an entity identity, not an item identity. Each callback reads one
  target state and performs no neighborhood scan.

Each adapter catches and logs audit-capture failures without replacing the
vanilla result or exception. Each callback snapshots one target and submits at
most one changed-state row; persistence remains on the existing asynchronous queue. All four
families use `quantity=NONE`, sensitive-location privacy, and explicit cause
metadata. A state delta is observed evidence; no delta is not promoted to a
successful move. Dispenser and dropper inventory changes remain issue #34.

Comparison notes: CoreProtect's documented lookup groups world block changes
with player/container history, while GriefLogger's compatibility surface is
limited to its pinned action writers. Those systems establish useful query
precedent, but neither proves these ItemGraph runtime boundaries. ItemGraph
keeps source, direct state delta, and inference separate in its own event model.
See [`GRIEFLOGGER_PARITY.md`](GRIEFLOGGER_PARITY.md) for the version-pinned
comparison and [CoreProtect API v13](https://docs.coreprotect.net/api/version/v13/).
