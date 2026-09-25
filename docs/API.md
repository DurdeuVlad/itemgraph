# ItemGraph preview API contract

Status: **contract proposal for issue #9** — not yet implemented.
Package: `com.itemgraph.api`
API version: **`PREVIEW_1`**
Minecraft: **1.21.1**
Loader: **NeoForge**
Minimum Java: **21**

This document defines the public Java boundary for another server-side NeoForge mod to
submit direct observed item evidence and query bounded, explainable flows. It is a preview
API shipped inside the main ItemGraph JAR; it is not a stable API, a database contract, a
network API, or a GriefLogger API.

## Goals and boundaries

The API exists so a consumer can:

1. register an external evidence source;
2. submit a source-attributed **direct observed fact**;
3. ask for a bounded item, player, vanilla-container, or external-inventory flow;
4. receive explicit accepted/rejected/error results without blocking the server thread.

It deliberately does not expose:

- JDBC `Connection`, SQL, ItemGraph schema objects, migrations, or service internals;
- `ig_*` row IDs in method signatures;
- GriefLogger classes, rows, files, or database access;
- caller-computed inferred edges as a submission type;
- authorization for player-facing use;
- a custom packet/client protocol, web API, or CustomNPCs integration;
- a stable pre-1.0 compatibility guarantee.

Installed server mods are trusted to describe their own evidence honestly. The API still
rejects inferred/scored submissions and returns explicit failures; trust does not mean
accepting impossible evidence.

## Version and preview policy

`ItemGraphApi.API_VERSION` is `ApiVersion.PREVIEW_1`. It is separate from `mod_version`
(`0.2.0` at the time of writing). `PREVIEW_1` is the first public boundary and may change
incompatibly before ItemGraph 1.0.

Policy:

- every breaking API change increments the preview API number;
- every breaking change must be named in `CHANGELOG.md`;
- consumers must not assume source, signature, DTO field order, or binary compatibility
  across preview versions;
- no separate Maven artifact is required for M7.

## Compile and runtime setup

Put the built main JAR, not a test/fixture JAR, in the consumer's local `libs/` directory:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation "net.neoforged:neoforge:21.1.248"
    compileOnly files("libs/itemgraph-0.2.0.jar")
}
```

Declare the runtime mod dependency in `META-INF/neoforge.mods.toml`. Use `required` when
the consumer cannot run without ItemGraph; use `optional` for an optional integration:

```toml
[[dependencies.examplemod]]
    modId="itemgraph"
    type="required"
    versionRange="[0.2.0]"
    ordering="AFTER"
    side="SERVER"
```

The `compileOnly` declaration is intentionally a local-file dependency for M7. Do not
publish or fetch a separate `itemgraph-api` artifact. The example pins the exact JAR used
for compilation because a NeoForge `versionRange` cannot express the separate
`PREVIEW_1` API version; widen it only to releases known to implement `PREVIEW_1`.

## Public signatures

The following signatures are the proposed contract. Implementation in issue #12 must keep
these signatures source-compatible or explicitly record a new preview version in the
changelog and this document.

### Entry point and lifecycle

```java
package com.itemgraph.api;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;

public final class ItemGraphApi {
    public static final ApiVersion API_VERSION = ApiVersion.PREVIEW_1;

    private ItemGraphApi() {}

    /** Service for the server returned by ServerLifecycleHooks.getCurrentServer(). */
    public static Optional<ItemGraphService> current();

    /** Service for a known server instance. */
    public static Optional<ItemGraphService> get(MinecraftServer server);
}

public enum ApiVersion {
    PREVIEW_1;

    public int number();      // 1
    public String channel();  // "preview"
    public boolean preview(); // true
    public String label();    // "preview-1"
}
```

`current()` and `get(server)` return `Optional.empty()` before ItemGraph has initialized
its database and workers, after `ServerStoppingEvent` begins, or when `server` is null. A returned
`ItemGraphService` remains callable during shutdown, but calls complete with `SHUTDOWN`
rather than silently doing nothing.

```java
package com.itemgraph.api;

import java.util.concurrent.CompletableFuture;

public interface ItemGraphService {
    ApiVersion apiVersion();

    CompletableFuture<RegistrationResult> registerSource(SourceRegistration registration);

    CompletableFuture<SubmissionResult> submitObservation(
            SourceHandle source,
            DirectObservation observation);

    CompletableFuture<QueryResult> traceItem(ItemQuery query, QueryOptions options);
    CompletableFuture<QueryResult> tracePlayer(PlayerQuery query, QueryOptions options);
    CompletableFuture<QueryResult> traceContainer(ContainerQuery query, QueryOptions options);
    CompletableFuture<QueryResult> traceExternalInventory(
            ExternalInventoryEndpoint inventory,
            QueryOptions options);
}
```

The futures never require the caller to block on the server thread. Consumers must not
call `.join()` on the server thread. If a callback needs Minecraft state, the consumer
must marshal it with `server.execute(Runnable)` or an equivalent NeoForge mechanism.
ItemGraph worker threads are not a safe place to touch mutable world/player state.
Null or malformed arguments to `ItemGraphService` methods produce the applicable
`INVALID_INPUT` result rather than throwing. The public records preserve submitted values
so validation and its diagnostic live at the service boundary; static convenience
factories are for constructing the common valid shape, not for hiding validation.

### Source registration

```java
package com.itemgraph.api;

public record SourceRegistration(
        String modId,
        String displayName) {

    public static SourceRegistration of(String modId, String displayName);
}

public final class SourceHandle {
    // Package-private constructor: only ItemGraphService can issue a handle.
    SourceHandle(String modId, String displayName, ApiVersion apiVersion) {}

    public String modId();
    public String displayName();
    public ApiVersion apiVersion();
}

public enum RegistrationStatus {
    REGISTERED,           // first durable registration for this modId
    UNCHANGED,            // same registration already existed
    UPDATED,              // display name changed; evidence source identity did not
    INVALID_INPUT,
    DATABASE_UNAVAILABLE,
    SHUTDOWN,
    FAILED
}

public record RegistrationResult(
        RegistrationStatus status,
        SourceHandle source,       // null on non-success statuses
        String errorCode,          // null on success
        String message) {          // safe diagnostic, null on success
}
```

`modId` must be a valid NeoForge mod ID (`[a-z][a-z0-9_]{1,63}`), be present in
`ModList`, and not be `itemgraph`, `minecraft`, or `neoforge`. `displayName` must be
1–128 non-blank characters. Source handles are per-server-lifetime capabilities; a
consumer must re-register after restart, but the same `modId` resolves to the same durable
evidence source. Within the trusted-mod boundary a consumer can still claim another loaded
ID; implementation should log registration prominently and consumers must pass their own
ID. `SourceHandle` cannot be constructed by consumers, and a handle issued by a different
server/service generation is rejected as `INVALID_INPUT`.

### Direct observation submission

```java
package com.itemgraph.api;

import java.util.Map;
import java.util.UUID;

public enum ObservationAction {
    INSERT_ITEM,
    REMOVE_ITEM,
    TRANSFER_ITEM,
    DROP_ITEM,
    PICKUP_ITEM,
    CREATE_ITEM,
    DESTROY_ITEM,
    CONSUME_ITEM,
    CONTAINER_NET_DELTA,
    DIRECT_OBSERVED
}

public record DirectObservation(
        long sourceEventId,
        long timestampMs,
        ObservationAction action,
        EndpointRef origin,
        EndpointRef destination,
        ItemSnapshot item,
        UUID itemEntityUuid,       // nullable
        Long timestampEndMs,       // nullable interval end
        Map<String, String> attributes) {
}
```

`sourceEventId` is a positive signed 64-bit integer chosen by the consumer and stable
across restarts. `(source modId, sourceEventId)` is the deduplication identity; a replay
returns `DUPLICATE`, not a second row.

`timestampMs` must be positive. `timestampEndMs` must be `null` for a point event or
`>= timestampMs` for an interval such as `CONTAINER_NET_DELTA`. `attributes` must be a
non-null map (empty is allowed) and is copied into an immutable map; it may contain
source-specific audit details but must not contain
secrets or unrestricted player data. Its canonical UTF-8 representation is limited to
**16 KiB**; larger submissions return `INVALID_INPUT`. The record contains no confidence,
edge ID, inferred flag, score, or precomputed path — those fields cannot exist in the
public submission type, so a caller cannot submit an inference as raw evidence. Accepted
actions persist `action_type` as the exact `ObservationAction.name()` value.

```java
package com.itemgraph.api;

public enum SubmissionStatus {
    PERSISTED,
    DUPLICATE,
    INVALID_INPUT,
    QUEUE_FULL,
    DATABASE_UNAVAILABLE,
    SHUTDOWN,
    FAILED
}

public record SubmissionResult(
        SubmissionStatus status,
        String sourceModId,
        long sourceEventId,
        String errorCode,
        String message) {
}
```

Submission and source registration use a bounded external-observation queue with capacity
**1,024** pending tasks. `QUEUE_FULL` means no persistence was claimed. `SHUTDOWN` means
the task did not complete persistence. If a queued task persists during the bounded
shutdown drain, it may still return `PERSISTED`; otherwise it returns `SHUTDOWN`. A
submission is never left in an ambiguous "maybe persisted" state.

### Endpoint model

```java
package com.itemgraph.api;

public enum EndpointKind {
    PLAYER,
    CONTAINER,
    GROUND,
    ARMOR_STAND,
    EXTERNAL_INVENTORY,
    UNKNOWN
}

public sealed interface EndpointRef permits
        PlayerEndpoint,
        WorldEndpoint,
        ExternalInventoryEndpoint,
        UnknownEndpoint {

    EndpointKind kind();
}
```

```java
package com.itemgraph.api;

import java.util.UUID;

public record PlayerEndpoint(
        UUID playerUuid,
        String displayName) implements EndpointRef { // displayName nullable

    @Override public EndpointKind kind();
}
```

`playerUuid` is required because names are labels, not durable identity. `displayName` is
optional metadata, at most 128 characters, and does not change identity.

```java
package com.itemgraph.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record WorldLocation(
        ResourceKey<Level> level,
        BlockPos position) {
}

public record WorldEndpoint(
        EndpointKind kind,
        ResourceKey<Level> level,
        BlockPos position,
        String displayName) implements EndpointRef { // displayName nullable
}
```

`WorldEndpoint.kind` may be `CONTAINER`, `GROUND`, or `ARMOR_STAND`; other kinds are
invalid. `displayName` is optional and limited to 128 characters. Coordinates are
floored to block position, matching existing node identity.

```java
package com.itemgraph.api;

public record ExternalInventoryEndpoint(
        String ownerModId,
        String inventoryId,
        String displayName,                 // nullable
        WorldLocation lastKnownLocation) implements EndpointRef { // nullable

    @Override public EndpointKind kind();
}
```

`inventoryId` is the inventory owner's stable identifier: 1–128 characters, non-blank,
with no control characters or leading/trailing whitespace. It is case-sensitive and must
not be normalized, so an uppercase UUID and a lowercase UUID remain distinct identities.
`ownerModId` must use the same NeoForge mod-ID syntax as `SourceRegistration.modId` and
scopes the value, so two mods can use `main` without colliding. `displayName` is optional
and limited to 128 characters. `lastKnownLocation` is optional context and does not
determine identity. A
coordinate-less modded inventory is represented by `lastKnownLocation = null` and maps
to a durable `EXTERNAL_INVENTORY` node, not `UNKNOWN`.

```java
package com.itemgraph.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record UnknownEndpoint(
        ResourceKey<Level> level,
        String reason) implements EndpointRef { // reason nullable

    @Override public EndpointKind kind();
}
```

`reason` is optional safe diagnostic text limited to 256 characters. `UNKNOWN` remains
explicit unresolved evidence. A consumer must use it only when it truly does not know the
endpoint; it is not a substitute for an unregistered modded inventory.

### Immutable item value

```java
package com.itemgraph.api;

import java.util.Map;
import net.minecraft.world.item.ItemStack;

public record ItemSnapshot(
        String itemId,
        int amount,
        String customName,             // nullable
        Map<String, String> components) {

    /** Copies canonical item metadata immediately; the ItemStack is not retained. */
    public static ItemSnapshot of(ItemStack stack);

    /** Builds the immutable value without a live ItemStack. */
    public static ItemSnapshot of(
            String itemId,
            int amount,
            String customName,
            Map<String, String> components);
}
```

`itemId` must use Minecraft resource-location syntax. `amount` must be positive.
`customName` is optional and limited to 256 characters. `components` must be a non-null
map (empty is allowed) whose keys are component-type resource-location strings and whose
values are canonical SNBT strings. It is copied with `Map.copyOf`; it is a deterministic
fingerprint summary, not a full NBT
dump, and its canonical UTF-8 representation is limited to **32 KiB**. `of(ItemStack)`
performs the copy while the caller still owns valid Minecraft state; workers only receive
`ItemSnapshot`. No `ItemStack`, `CompoundTag`, `Container`, `IItemHandler`, `BlockEntity`,
`Level`, or `ServerPlayer` is retained by a submitted DTO.

### Queries

```java
package com.itemgraph.api;

public record ItemQuery(
        String itemId,           // nullable
        String customName,       // nullable
        String fingerprintHash)  // nullable
{
    public static ItemQuery itemId(String itemId);
    public static ItemQuery customName(String customName);
    public static ItemQuery fingerprintHash(String sha256Hex);
}

public record PlayerQuery(java.util.UUID playerUuid) {}

public record ContainerQuery(
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level,
        net.minecraft.core.BlockPos position) {}

public record QueryOptions(
        int limit,
        Long sinceMinutes) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static QueryOptions defaults();
}
```

`ItemQuery` requires exactly one selector. `itemId` is an exact registry ID lookup;
`customName` is a non-blank lookup of at most 256 characters and may be ambiguous;
`fingerprintHash` is the deterministic canonical metadata hash as a 64-character lowercase
SHA-256 hex string, not a database row ID.
`PlayerQuery` uses the durable UUID. `ContainerQuery` uses explicit dimension and block
coordinates. `traceExternalInventory` uses `(ownerModId, inventoryId)` as identity and
ignores `lastKnownLocation` for lookup.

`limit < 1` or `sinceMinutes < 1` is invalid input. `limit > 100` is accepted but capped;
`requestedLimit` preserves what the caller asked and `appliedLimit` records `100`.
`sinceMinutes == null` means all recorded history. A non-null value is resolved to
`[acceptance-time - sinceMinutes, acceptance-time]` on the query worker, so returned
`TimeWindow` values are absolute rather than drifting between calls.

```java
package com.itemgraph.api;

public enum QueryStatus {
    OK,
    NOT_FOUND,
    AMBIGUOUS,
    INVALID_INPUT,
    QUEUE_FULL,
    DATABASE_UNAVAILABLE,
    SHUTDOWN,
    FAILED
}

public record QueryResult(
        QueryStatus status,
        FlowResult result,  // null for failures and NOT_FOUND
        String errorCode,   // null on OK/AMBIGUOUS
        String message) {   // null on OK
}
```

`AMBIGUOUS` returns a non-null `FlowResult` with up to 10 candidates and no hops. Query
work uses the same bounded read-only query worker as `/ig` queries: one worker plus at
most **64** pending queries. Queue saturation returns `QUEUE_FULL` and does not wait on
the server thread.

```java
package com.itemgraph.api;

public record TimeWindow(
        Long sinceMs,   // inclusive; null means unbounded start
        Long untilMs) { // inclusive; null means unbounded end
}

public record FlowResult(
        String targetDescription,
        java.util.List<ItemDescriptor> itemCandidates,
        java.util.List<EndpointDescriptor> endpointCandidates,
        java.util.List<FlowHop> hops,
        TimeWindow window,
        int requestedLimit,
        int appliedLimit,
        boolean truncated) {
}
```

### Flow DTOs

```java
package com.itemgraph.api;

public record ItemDescriptor(
        String itemId,
        String customName,       // nullable
        String fingerprintHash) {
}

public record EndpointDescriptor(
        EndpointKind kind,
        String stableKey,
        String displayName,             // nullable when no label is stored
        WorldLocation location) {       // nullable for PLAYER, UNKNOWN, coordinate-less EXTERNAL_INVENTORY
}

public enum EvidenceKind {
    OBSERVATION,
    TRANSFORMATION,
    INFERRED_EDGE
}

public record EvidenceRef(
        EvidenceKind kind,
        String value) {
}

public enum Provenance {
    OBSERVED,
    INFERRED
}

public record FlowHop(
        Provenance provenance,
        EvidenceRef evidence,
        EndpointDescriptor origin,       // nullable only for a corrupt stored row
        EndpointDescriptor destination,  // nullable only for a corrupt stored row
        ItemDescriptor item,              // nullable only when the stored row has no fingerprint
        int amount,
        long timestampStartMs,
        long timestampEndMs,
        Double confidence,               // null for OBSERVED
        String detail,
        String explanation,              // stored explanation for INFERRED, otherwise null
        java.util.List<EvidenceRef> supportingEvidence,
        boolean supportingEvidenceTruncated) {
}
```

`EvidenceRef.value` is an opaque evidence URI such as
`itemgraph:observation:<id>`, `itemgraph:transformation:<id>`, or
`itemgraph:inferred-edge:<id>`. Consumers must treat it as an opaque identifier, not as a
foreign key. `supportingEvidence` is empty for `OBSERVED` hops and non-empty for
`INFERRED` hops; it carries the evidence the correlation engine cited, up to the existing
50-row explanation bound. `supportingEvidenceTruncated` is true when more cited rows were
left out. `confidence` is the deterministic stored score, never an invented AI
confidence.

`EndpointDescriptor.stableKey` values are:

- `player:<uuid>` for `PLAYER`;
- `<level>/<x>/<y>/<z>` for `CONTAINER`, `GROUND`, and `ARMOR_STAND`;
- `external:<ownerModId>:<inventoryId>` for `EXTERNAL_INVENTORY`;
- `unknown:<level>` for `UNKNOWN`.

## Persistence and migration contract

Issue #12 needs an ItemGraph-owned **V12** migration. The design intentionally requires
schema support for a durable external-node key while reusing the existing numeric source
event identity.

### Source registration and event deduplication

`SourceHandle.modId()` is persisted in a new ItemGraph table:

```sql
CREATE TABLE ig_api_sources (
    source_mod_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    api_version INTEGER NOT NULL,
    registered_at_ms INTEGER NOT NULL,
    last_seen_ms INTEGER NOT NULL
);
```

External observations write `ig_observations.source_type = 'EXTERNAL_API:<modId>'` and
`ig_observations.source_event_id = sourceEventId`. The existing unique index on
`(source_type, source_event_id)` therefore deduplicates per source mod without changing
`source_event_id` to text. `raw_data` stores the immutable observation metadata and API
version, not inferred conclusions. Registration and accepted submissions update
`last_seen_ms`; changing `display_name` updates the display label but never creates a new
evidence source.

### External inventory identity

Add an ItemGraph-only column/index:

```sql
ALTER TABLE ig_nodes ADD COLUMN external_key TEXT;
CREATE UNIQUE INDEX idx_nodes_external_key
ON ig_nodes(node_type, external_key)
WHERE external_key IS NOT NULL;
```

`ExternalInventoryEndpoint` maps to `node_type = 'EXTERNAL_INVENTORY'` and
`external_key = '<ownerModId>/<inventoryId>'`. `custom_label` stores the current display
name; it is not part of identity. If `lastKnownLocation` exists, the node uses that level
and floored block coordinates as last-known context. If it does not, `level_id` is
`external:<ownerModId>` and coordinates remain `NULL`; the node is still durable and is
never collapsed into the per-level `UNKNOWN` sentinel.

The migration affects only `run/itemgraph/itemgraph.db` or another configured ItemGraph
database. It does not touch `run/database.db` or any GriefLogger schema/table.

## Threading and backpressure

- Cheap structural checks may happen on the caller's thread; semantic validation still
  produces an explicit `INVALID_INPUT` result.
- Database writes, deduplication, external-node resolution, historical reads, and DTO
  formatting happen on bounded ItemGraph workers, never on the server thread.
- API submission capacity: **1,024** pending tasks.
- API query capacity: **64** pending tasks on the shared query worker.
- Every `CompletableFuture` completes with a result object; it does not normally complete
  exceptionally for expected domain failures. Only an unrecoverable implementation fault
  may complete exceptionally, and consumers should still handle it as `FAILED`.
- The API does not reuse mutable Minecraft objects or internal services across threads.

## Error result contract

Expected `errorCode` values are stable within a preview version:

| Status | Typical errorCode |
| --- | --- |
| `INVALID_INPUT` | `INVALID_MOD_ID`, `INVALID_EVENT_ID`, `INVALID_ENDPOINT`, `INVALID_ITEM`, `INVALID_TIME_RANGE`, `INVALID_QUERY` |
| `QUEUE_FULL` | `QUEUE_FULL` |
| `DATABASE_UNAVAILABLE` | `DATABASE_UNAVAILABLE` |
| `SHUTDOWN` | `SHUTDOWN` |
| `FAILED` | `PERSISTENCE_FAILED`, `REGISTRATION_FAILED`, `QUERY_FAILED` |

`DUPLICATE`, `NOT_FOUND`, and `AMBIGUOUS` are outcomes, not exceptions. `message` is a
safe operator-facing diagnostic and must not include secrets, stack traces, SQL text, or
unrelated player/location data.

## Privacy and authorization boundary

Flow results can contain player UUIDs/names, coordinates, hidden inventories, and custom
item metadata. `ItemGraphService` does **not** authorize a `ServerPlayer`, because trusted
server mods may consume data for reasons other than display. The consumer must enforce
its own permission check before exposing any `FlowResult`, candidate, endpoint, evidence,
or explanation to a player. Absence of a permission parameter is intentional: it prevents
the API from implying that ItemGraph has granted display authorization.

## Minimal consumer example

```java
package com.example.integrator;

import com.itemgraph.api.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class ExampleIntegration {
    private ExampleIntegration() {}

    public static CompletableFuture<Void> submitExample(
            MinecraftServer server,
            ServerPlayer player,
            ItemStack stack) {
        return ItemGraphApi.get(server)
                .map(service -> service
                        .registerSource(SourceRegistration.of("examplemod", "Example Integrator"))
                        .thenCompose(registration -> {
                            SourceHandle source = registration.source();
                            if (source == null) {
                                return CompletableFuture.completedFuture(null);
                            }
                            DirectObservation observation = new DirectObservation(
                                    42L,
                                    System.currentTimeMillis(),
                                    ObservationAction.TRANSFER_ITEM,
                                    new PlayerEndpoint(player.getUUID(), player.getName().getString()),
                                    new ExternalInventoryEndpoint(
                                            "examplemod",
                                            "remote/main",
                                            "Remote Main",
                                            new WorldLocation(Level.OVERWORLD, new BlockPos(1, 64, 1))),
                                    ItemSnapshot.of(stack.copy()),
                                    null,
                                    null,
                                    Map.of("capture", "examplemod-open-close"));
                            return service.submitObservation(source, observation)
                                    .thenCompose(submission ->
                                            submission.status() == SubmissionStatus.PERSISTED
                                                    || submission.status() == SubmissionStatus.DUPLICATE
                                                    ? service.traceItem(
                                                            ItemQuery.itemId("minecraft:diamond"),
                                                            QueryOptions.defaults())
                                                    : CompletableFuture.completedFuture(null));
                        })
                        .thenAccept(result -> server.execute(() -> {
                            // Check player permissions before displaying result.
                        })))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }
}
```

The example is illustrative for the contract, not the required sample mod in issue #12.
Issue #12 must compile a real consumer fixture against the built main JAR and exercise
submission plus a bounded trace on a dedicated server.

## Explicitly rejected designs

- **Expose `InternalObservationService` or `TraceQueryService`:** rejected because they
  take JDBC connections, internal node/fingerprint IDs, and mutable canonical internals.
- **Expose `ig_observations.id` or `ig_nodes.id` in method signatures:** rejected because
  consumers would bind to schema details. Returned `EvidenceRef` values are opaque.
- **Accept an inferred edge:** rejected because external submissions are raw evidence;
  ItemGraph's deterministic correlation must remain the only inference authority.
- **Use `UNKNOWN` for coordinate-less inventories:** rejected because a stable modded
  inventory identity must remain distinguishable and traceable.
- **GriefLogger API:** rejected; GriefLogger remains optional, additive, and read-only.
