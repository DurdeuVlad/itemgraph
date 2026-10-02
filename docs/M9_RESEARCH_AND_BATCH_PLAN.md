# M9 Research Comparison and Delivery Batches

## Research boundary

This comparison uses the current `common`, `fabric`, and `neoforge` source trees,
the M9 issue acceptance criteria, and the documented upstream APIs. It does not
claim live-server or benchmark results. The GitHub milestone is already open as
M9 (#5); this document records the current comparison and delivery order instead
of creating a duplicate milestone.

## Feature comparison

| Area | ItemGraph source today | M9 contract still missing | Prior-art pattern and decision |
| --- | --- | --- | --- |
| Lookup | CoreProtect-style `action`, `user`, `include`, `exclude`, `time`, `radius`; bounded asynchronous SQL | #45 absolute UTC windows and canonical component predicates; #44 first-class state filtering on every surface | CoreProtect documents the six filter families and page-based lookup. Keep its narrow token grammar, but add typed ItemGraph predicates and keyset pagination; evaluate filters in SQL before row limits. |
| Evidence states | Taxonomy defines four classes; trace, unified lookup, GUI, API `FlowHop`, and incident bundles carry state, reason, candidates, and quantity impact; disposed evidence remains visible as unresolved and contributes zero | #44 acceptance is implemented in source; it still needs consolidated verification and independent adversarial review | CoreProtect's documented lookup returns history rows and pages; it does not define uncertainty classes. ItemGraph retains raw evidence and represents ambiguity without allocation. |
| World/environment events | Shared taxonomy has planned IDs for explosions, fluids/fire, pistons, Endermen, falling blocks, dispenser/dropper effects; current adapters cover only selected vanilla item actions such as bucket and dispenser/dropper paths | #55 needs authoritative before/after effects, cancellation/partial/unsupported outcomes on both loaders | NeoForge's event model distinguishes cancelable attempts from committed effects. Fabric's documented `LootTableEvents.MODIFY_DROPS` runs after generation and exposes final drops; use only result boundaries that prove the changed state. Record unsupported causes as unresolved rather than guessing. |
| Entity lifecycle | Item-entity UUID continuity and several player/entity audit callbacks exist; shared taxonomy marks lifecycle families planned | #56 requires spawn/despawn/kill/projectile outcomes and attempt/result separation | Use loader lifecycle callbacks only where they identify the accepted entity/result; a projectile launch callback is not proof of impact. UUID is continuity evidence, not an item identity. |
| Transformations | Craft, smelt, anvil rename/repair are implemented; planned taxonomy families include trade, enchanting, brewing, smithing, grindstone, loot | #57 requires exact inputs/outputs, cancellation/partial status, and unresolved hidden inputs | Fabric's runtime loot-drop event offers final generated stacks. For stations, persist only observed input/output deltas; an output-only callback cannot justify inferred inputs. |
| Throughput and storage | Bounded workers/queues and a bounded malformed-component negative cache already exist; #32 is collecting baseline evidence; #58 is the optimization child | #58 needs measured changes, durable evidence behavior, retention/archive/WAL/compaction recovery | CoreProtect documents caller-managed asynchronous lookups and explicit operator-controlled purge/optimization. ItemGraph keeps SQL off the server thread and raw evidence indefinitely; do not copy destructive purge semantics or set budgets without #32 measurements. |
| Incident export | #37 local implementation is committed and independently reviewed; not yet consolidated-tested or PR'd | M9 acceptance still needs full dialect, privacy, tamper, collision, and cancellation checks | CoreProtect provides filtered lookup/pagination, not an equivalent redacted hash-chained case bundle. Preserve ItemGraph's separate evidence and inference records. |
| Integration API | #36 version negotiation is committed; API consumers negotiate exact preview numbers | #44 adds state/reason/candidate fields under PREVIEW_2, with candidates captured at inference time | Exact preview-version negotiation is the compatibility boundary; PREVIEW_1 consumers are rejected by PREVIEW_2 runtime negotiation. |

## Research sources

- [CoreProtect lookup, filters, pagination, purge, and optimize](https://docs.coreprotect.net/commands/)
- [CoreProtect API v13 async caller contract](https://docs.coreprotect.net/api/version/v13/)
- [Fabric event guide](https://docs.fabricmc.net/develop/events)
- [Fabric loot table events](https://docs.fabricmc.net/develop/events/loot-tables)
- [NeoForge 1.21.1 capabilities](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/)
- [NeoForge 1.21.1 loot tables](https://docs.neoforged.net/docs/1.21.1/resources/server/loottables/)
- [NeoForge 1.21.1 global loot modifiers](https://docs.neoforged.net/docs/1.21.1/resources/server/loottables/glm/)

## Delivery batches

1. **Foundations and provenance:** finish #36–37 against the current dependency
   stack; complete #44's evidence-state contract and #45's shared parser/query
   grammar. Keep all predicates bounded and apply them before per-source limits.
2. **Capture and taxonomy:** finish #32's measured baseline and #35 registry;
   implement #33–34, then #55–57 in dependency order. Unsupported event
   boundaries remain explicitly unsupported/unresolved in both loader reports.
3. **Measured operations:** use #32 artifacts to select #58 optimizations; retain
   raw evidence indefinitely and verify restart/failure recovery for derived data.
4. **Consolidated verification:** after the batch is implemented, run the full
   loader unit suites, loader GameTests, dialect/replay suite, benchmark matrix,
   incident export matrix, and one independent read-only adversarial review.
   No intermediate per-pass test runs. Do not build distributable jars without
   an authorized version bump. No production access.

## Current blockers and limits

- #32 has a draft baseline PR; no staging-derived numeric budget is accepted yet.
- #34 still needs a real third-party backpack/capability integration fixture.
- Live GUI clicks for #26 were explicitly skipped; no client-interaction claim
  may rely on those checks.
- #55–57 are not complete merely because taxonomy rows exist; each needs a
  loader adapter and event-result fixtures.
- The consolidated batch must not close M9 until its acceptance evidence and
  independent review are attached to the corresponding PRs.
