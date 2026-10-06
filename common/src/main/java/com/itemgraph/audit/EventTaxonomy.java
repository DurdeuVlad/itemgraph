package com.itemgraph.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Versioned catalog for ItemGraph evidence and query action identifiers.
 *
 * <p>Identifiers stay strings because they are persisted and may outlive a
 * particular ItemGraph release. Consumers must tolerate identifiers added by
 * later taxonomy versions; this registry only resolves identifiers known to
 * this runtime.</p>
 */
public final class EventTaxonomy {
    public static final String VERSION = "4.0.0";
    public static final String UNCLASSIFIED_EVIDENCE = "UNCLASSIFIED";

    public enum Surface { AUDIT_EVENT, ITEM_OBSERVATION, TRANSFORMATION }
    public enum EvidenceClass { OBSERVED, INFERRED, AMBIGUOUS, UNRESOLVED }
    public enum SourceReliability {
        AUTHORITATIVE_GAME_RESULT,
        GAME_CALLBACK_ATTEMPT,
        DIRECT_STATE_DELTA,
        CORRELATED_EVIDENCE,
        UNRESOLVED_CAUSE
    }
    public enum EvidenceIdSemantics { INGEST_EVENT_UUID_AND_OPTIONAL_SOURCE_EVENT_ID }
    public enum EndpointSemantics {
        PLAYER_CONTEXT, WORLD_LOCATION, ACTOR_AND_TARGET, SOURCE_AND_DESTINATION, INPUTS_AND_OUTPUTS, UNKNOWN
    }
    public enum QuantitySemantics { NONE, SIGNED_DELTA, INPUT_OUTPUT, UNKNOWN }
    public enum ActorStatus { PLAYER, ENTITY, WORLD, UNKNOWN }
    public enum PrivacyClass { PUBLIC_WORLD_EVENT, PLAYER_ACTIVITY, STAFF_ACTIVITY, SENSITIVE_LOCATION }
    public enum LoaderStatus { IMPLEMENTED, HISTORICAL_ONLY, PLANNED, UNSUPPORTED }
    public enum Loader { FABRIC, NEOFORGE }

    public record LoaderSupport(LoaderStatus status, String reasonCode) {
        public LoaderSupport {
            Objects.requireNonNull(status, "status");
            if (status == LoaderStatus.IMPLEMENTED && reasonCode != null) {
                throw new IllegalArgumentException("implemented loader support cannot have a reason code");
            }
            if (status != LoaderStatus.IMPLEMENTED && (reasonCode == null || reasonCode.isBlank())) {
                throw new IllegalArgumentException("planned or unsupported loader support requires a reason code");
            }
        }
    }

    public record Definition(
            String id,
            String family,
            Surface surface,
            EvidenceClass evidenceClass,
            SourceReliability sourceReliability,
            EvidenceIdSemantics evidenceIds,
            EndpointSemantics endpoints,
            QuantitySemantics quantity,
            ActorStatus actor,
            PrivacyClass privacy,
            LoaderSupport fabric,
            LoaderSupport neoForge,
            int ownerIssue,
            List<String> aliases
    ) {
        public Definition {
            if (id == null || !id.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("event ID must be a stable uppercase identifier");
            }
            if (family == null || family.isBlank()) {
                throw new IllegalArgumentException("event family is required");
            }
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(evidenceClass, "evidenceClass");
            Objects.requireNonNull(sourceReliability, "sourceReliability");
            Objects.requireNonNull(evidenceIds, "evidenceIds");
            Objects.requireNonNull(endpoints, "endpoints");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(privacy, "privacy");
            Objects.requireNonNull(fabric, "fabric");
            Objects.requireNonNull(neoForge, "neoForge");
            if (ownerIssue < 1) {
                throw new IllegalArgumentException("every taxonomy entry must have an owning issue");
            }
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
        }

        public boolean availableOnBothLoaders() {
            return fabric.status() == LoaderStatus.IMPLEMENTED
                    && neoForge.status() == LoaderStatus.IMPLEMENTED;
        }

        public boolean implementedOn(Loader loader) {
            return switch (loader) {
                case FABRIC -> fabric.status() == LoaderStatus.IMPLEMENTED;
                case NEOFORGE -> neoForge.status() == LoaderStatus.IMPLEMENTED;
            };
        }

        public boolean queryableOn(Loader loader) {
            LoaderStatus status = loader == Loader.FABRIC ? fabric.status() : neoForge.status();
            return status == LoaderStatus.IMPLEMENTED || status == LoaderStatus.HISTORICAL_ONLY;
        }

        public boolean selectableInUnifiedLookup() {
            boolean queryable = queryableOn(Loader.FABRIC) && queryableOn(Loader.NEOFORGE);
            // Historical audit rows use INTERACT_BLOCK; the canonical unified
            // action remains INTERACT_BLOCK_ATTEMPT, as before this registry.
            return queryable && !(surface == Surface.AUDIT_EVENT && id.equals("INTERACT_BLOCK"));
        }
    }

    public record ReasonCode(String id, int ownerIssue, String meaning) {
        public ReasonCode {
            if (id == null || !id.matches("[A-Z][A-Z0-9_]*") || ownerIssue < 1
                    || meaning == null || meaning.isBlank()) {
                throw new IllegalArgumentException("reason codes need a stable ID, owner issue, and meaning");
            }
        }
    }

    private static final LoaderSupport IMPLEMENTED = new LoaderSupport(LoaderStatus.IMPLEMENTED, null);
    private static final LoaderSupport HISTORICAL_ONLY = new LoaderSupport(LoaderStatus.HISTORICAL_ONLY,
            "NO_NATIVE_WRITER_HISTORICAL_QUERY_ONLY");
    private static final LoaderSupport LEGACY_TRANSFORMATION_INPUTS_UNVERIFIED = new LoaderSupport(
            LoaderStatus.HISTORICAL_ONLY, "LEGACY_TRANSFORMATION_INPUTS_NOT_VERIFIED");
    private static final LoaderSupport CREATIVE_TRANSFORM_UNSUPPORTED = new LoaderSupport(
            LoaderStatus.UNSUPPORTED, "CREATIVE_TRANSFORM_CAUSE_NOT_REPORTED");
    private static final List<ReasonCode> UNRESOLVED_REASON_CODES = List.of(
            new ReasonCode("WORLD_EVENT_API_UNAVAILABLE", 55,
                    "The loader exposes no authoritative callback for this world event."),
            new ReasonCode("WORLD_EFFECT_PARTIAL", 55,
                    "The event was observed but its complete before/after effect is unavailable."),
            new ReasonCode("CAUSE_NOT_REPORTED", 55,
                    "The world changed but the source event did not report a causal actor or source."),
            new ReasonCode("ENTITY_CAUSE_NOT_REPORTED", 56,
                    "The entity lifecycle event did not report the responsible actor or cause."),
            new ReasonCode("PROJECTILE_IMPACT_NOT_AUTHORITATIVE", 56,
                    "The available callback does not establish a projectile impact result."),
            new ReasonCode("TRANSFORMATION_INPUTS_NOT_OBSERVED", 57,
                    "The operation output is known but one or more input stacks were not observed."),
            new ReasonCode("LEGACY_TRANSFORMATION_INPUTS_NOT_VERIFIED", 162,
                    "The stored transformation predates complete input evidence and cannot establish lineage."),
            new ReasonCode("TRANSFORMATION_CANCELLED", 57,
                    "The transformation operation was cancelled before a successful result."),
            new ReasonCode("CREATIVE_TRANSFORM_CAUSE_NOT_REPORTED", 33,
                    "Creative slot changes do not establish an item transformation cause."),
            new ReasonCode("CONTAINER_SNAPSHOT_INCOMPLETE", 140,
                    "A successful player container break was observed, but its complete pre-break inventory snapshot was unavailable."),
            new ReasonCode("CONTAINER_BREAK_ACTOR_UNAVAILABLE", 140,
                    "The loader reported a container-break boundary without an authoritative player actor."),
            new ReasonCode("CONTAINER_BLOCK_ENTITY_UNAVAILABLE", 140,
                    "The block state required a block entity, but it was unavailable at the break snapshot boundary."),
            new ReasonCode("CONTAINER_BLOCK_ENTITY_UNSUPPORTED", 140,
                    "The broken block entity does not expose inventory through ItemGraph's supported Container contract."),
            new ReasonCode("CONTAINER_SLOT_LIMIT_EXCEEDED", 140,
                    "The inventory exceeded the bounded slot count supported by the container-break snapshot."),
            new ReasonCode("CONTAINER_SNAPSHOT_FAILED", 140,
                    "Reading the container inventory failed before a complete snapshot could be recorded."),
            new ReasonCode("CONTAINER_OBSERVATION_QUEUE_REJECTED", 140,
                    "The bounded ingestion queue rejected the related container-break evidence batch."),
            new ReasonCode("CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED", 140,
                    "The container contents were observed before destruction, but available callbacks did not link resulting ground item entities to those contents.")
    );
    private static final List<Definition> DEFINITIONS = createDefinitions();
    private static final Map<String, Definition> BY_ID = indexById(DEFINITIONS);
    private static final Map<String, String> ACTION_ALIASES = indexAliases(DEFINITIONS);
    private static final List<String> AUDIT_LOOKUP_TYPES = DEFINITIONS.stream()
            .filter(definition -> definition.surface() == Surface.AUDIT_EVENT
                    && definition.queryableOn(Loader.FABRIC) && definition.queryableOn(Loader.NEOFORGE))
            .map(Definition::id)
            .toList();
    private static final List<String> UNIFIED_LOOKUP_ACTIONS = List.copyOf(new LinkedHashSet<>(
            DEFINITIONS.stream().filter(Definition::selectableInUnifiedLookup)
                    .map(Definition::id).toList()));

    private EventTaxonomy() { }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static List<String> auditLookupTypes() {
        return AUDIT_LOOKUP_TYPES;
    }

    public static List<ReasonCode> unresolvedReasonCodes() {
        return UNRESOLVED_REASON_CODES;
    }

    public static List<String> unifiedLookupActions() {
        return UNIFIED_LOOKUP_ACTIONS;
    }

    /** Transformation IDs implemented on both loaders; shared queries have no loader provenance. */
    public static List<String> traceableTransformationTypes() {
        return DEFINITIONS.stream()
                .filter(definition -> definition.surface() == Surface.TRANSFORMATION)
                .filter(definition -> definition.fabric().status() == LoaderStatus.IMPLEMENTED
                        && definition.neoForge().status() == LoaderStatus.IMPLEMENTED)
                .map(Definition::id)
                .toList();
    }

    public static boolean isTraceableTransformation(String id) {
        return find(id, Surface.TRANSFORMATION)
                .map(definition -> definition.fabric().status() == LoaderStatus.IMPLEMENTED
                        && definition.neoForge().status() == LoaderStatus.IMPLEMENTED)
                .orElse(false);
    }

    public static Optional<Definition> find(String id) {
        return find(id, Surface.AUDIT_EVENT);
    }

    public static Optional<Definition> find(String id, Surface surface) {
        if (id == null || surface == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(key(surface, id.toUpperCase(Locale.ROOT))));
    }

    /** Resolves canonical names and GriefLogger-style lookup aliases. */
    public static Optional<String> canonicalAction(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toUpperCase(Locale.ROOT).replace('-', '_');
        return Optional.ofNullable(ACTION_ALIASES.get(normalized));
    }

    private static List<Definition> createDefinitions() {
        List<Definition> entries = new ArrayList<>();

        // Existing persisted audit event IDs. These entries document the current
        // boundary; they do not claim complete GriefLogger or world-event parity.
        audit(entries, "PLAYER_JOIN", "player_session", SourceReliability.AUTHORITATIVE_GAME_RESULT,
                ActorStatus.PLAYER, PrivacyClass.PLAYER_ACTIVITY, 25, "JOIN");
        audit(entries, "PLAYER_QUIT", "player_session", SourceReliability.AUTHORITATIVE_GAME_RESULT,
                ActorStatus.PLAYER, PrivacyClass.PLAYER_ACTIVITY, 25, "QUIT");
        audit(entries, "CHAT_MESSAGE", "chat", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.PLAYER_ACTIVITY, 25, "CHAT");
        audit(entries, "COMMAND_ATTEMPT", "command", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.PLAYER_ACTIVITY, 24, "COMMAND");
        audit(entries, "COMMAND_EXECUTED", "command", SourceReliability.AUTHORITATIVE_GAME_RESULT,
                ActorStatus.PLAYER, PrivacyClass.PLAYER_ACTIVITY, 24, HISTORICAL_ONLY);
        audit(entries, "ADMIN_ITEM_COMMAND_ATTEMPT", "admin_item_command",
                SourceReliability.GAME_CALLBACK_ATTEMPT, ActorStatus.UNKNOWN,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "ADMIN_ITEM_COMMAND_EFFECT", "admin_item_command",
                SourceReliability.AUTHORITATIVE_GAME_RESULT, ActorStatus.UNKNOWN,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "ADMIN_ITEM_COMMAND_FAILURE", "admin_item_command",
                SourceReliability.AUTHORITATIVE_GAME_RESULT, ActorStatus.UNKNOWN,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "ADMIN_ITEM_COMMAND_UNRESOLVED", "admin_item_command",
                EvidenceClass.UNRESOLVED, SourceReliability.UNRESOLVED_CAUSE, ActorStatus.UNKNOWN,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "CREATIVE_SLOT_ATTEMPT", "creative_inventory",
                SourceReliability.GAME_CALLBACK_ATTEMPT, ActorStatus.PLAYER,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "CREATIVE_SLOT_EFFECT", "creative_inventory",
                SourceReliability.AUTHORITATIVE_GAME_RESULT, ActorStatus.PLAYER,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "CREATIVE_BLOCK_ATTEMPT", "creative_world_action",
                SourceReliability.GAME_CALLBACK_ATTEMPT, ActorStatus.PLAYER,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "CREATIVE_BLOCK_RESULT", "creative_world_action",
                SourceReliability.AUTHORITATIVE_GAME_RESULT, ActorStatus.PLAYER,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "CREATIVE_BLOCK_UNRESOLVED", "creative_world_action", EvidenceClass.UNRESOLVED,
                SourceReliability.UNRESOLVED_CAUSE, ActorStatus.PLAYER,
                PrivacyClass.STAFF_ACTIVITY, 33);
        audit(entries, "PLACE_BLOCK", "block_action", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27);
        audit(entries, "BREAK_BLOCK", "block_action", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27);
        audit(entries, "CONTAINER_BREAK_COMPLETED", "container_break",
                SourceReliability.AUTHORITATIVE_GAME_RESULT, ActorStatus.PLAYER,
                PrivacyClass.SENSITIVE_LOCATION, 140);
        audit(entries, "CONTAINER_BREAK_UNRESOLVED", "container_break",
                EvidenceClass.UNRESOLVED, SourceReliability.UNRESOLVED_CAUSE,
                ActorStatus.UNKNOWN, PrivacyClass.SENSITIVE_LOCATION, 140);
        audit(entries, "INTERACT_BLOCK", "block_action", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27, HISTORICAL_ONLY);
        audit(entries, "INTERACT_BLOCK_ATTEMPT", "block_action", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27, "INTERACT_BLOCK");
        audit(entries, "INTERACT_ENTITY", "entity_interaction", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 75);
        audit(entries, "INTERACT_ENTITY_COMPLETED", "entity_interaction", SourceReliability.AUTHORITATIVE_GAME_RESULT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 75);
        audit(entries, "INTERACT_ENTITY_DENIED", "entity_interaction", SourceReliability.AUTHORITATIVE_GAME_RESULT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 75);
        audit(entries, "INTERACT_ENTITY_UNRESOLVED", "entity_interaction", EvidenceClass.UNRESOLVED,
                SourceReliability.UNRESOLVED_CAUSE, ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 75);
        audit(entries, "KILL_ENTITY", "entity_lifecycle", SourceReliability.AUTHORITATIVE_GAME_RESULT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27);
        audit(entries, "THROW_ITEM", "projectile", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27);
        audit(entries, "SHOOT_ITEM", "projectile", SourceReliability.GAME_CALLBACK_ATTEMPT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27);
        audit(entries, "PROJECTILE_SPAWN_ACCEPTED", "projectile", SourceReliability.AUTHORITATIVE_GAME_RESULT,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION, 27);

        // Canonical action names and user-facing aliases shared by audit,
        // observation, and transformation lookup parsing.
        action(entries, "ADD_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow", "add");
        action(entries, "REMOVE_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow", "remove");
        action(entries, "DROP_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow", "drop");
        action(entries, "PICKUP_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow", "pickup");
        action(entries, "THROW_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow");
        action(entries, "SHOOT_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow");
        historicalTransformation(entries, "CRAFT", "craft_item");
        historicalTransformation(entries, "SMELT");
        add(entries, "CRAFT_OUTPUT_UNRESOLVED", "item_processing", Surface.AUDIT_EVENT,
                EvidenceClass.UNRESOLVED, SourceReliability.UNRESOLVED_CAUSE,
                EndpointSemantics.PLAYER_CONTEXT, QuantitySemantics.UNKNOWN,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION,
                IMPLEMENTED, IMPLEMENTED, 162);
        add(entries, "SMELT_OUTPUT_UNRESOLVED", "item_processing", Surface.AUDIT_EVENT,
                EvidenceClass.UNRESOLVED, SourceReliability.UNRESOLVED_CAUSE,
                EndpointSemantics.PLAYER_CONTEXT, QuantitySemantics.UNKNOWN,
                ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION,
                IMPLEMENTED, IMPLEMENTED, 162);
        action(entries, "ANVIL_RENAME", Surface.TRANSFORMATION, QuantitySemantics.INPUT_OUTPUT,
                57, "transformation", "anvil");
        action(entries, "ANVIL_REPAIR", Surface.TRANSFORMATION, QuantitySemantics.INPUT_OUTPUT,
                57, "transformation", "anvil_rename_repair");
        action(entries, "BREAK_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow", "item_break");
        action(entries, "CONSUME_ITEM", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA,
                27, "item_flow", "consume");
        action(entries, "HOPPER_INSERT", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA, 34, "automation");
        action(entries, "HOPPER_EXTRACT", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA, 34, "automation");
        action(entries, "DEATH_DROP", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA, 56, "entity_lifecycle");
        action(entries, "ADD_ITEM_ENDER", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA, 76, "ender_inventory");
        action(entries, "REMOVE_ITEM_ENDER", Surface.ITEM_OBSERVATION, QuantitySemantics.SIGNED_DELTA, 76, "ender_inventory");
        action(entries, "ADMIN_ITEM_CREATE", Surface.ITEM_OBSERVATION,
                QuantitySemantics.SIGNED_DELTA, 33, "admin_inventory",
                ActorStatus.UNKNOWN, PrivacyClass.STAFF_ACTIVITY);
        action(entries, "ADMIN_ITEM_REMOVE", Surface.ITEM_OBSERVATION,
                QuantitySemantics.SIGNED_DELTA, 33, "admin_inventory",
                ActorStatus.UNKNOWN, PrivacyClass.STAFF_ACTIVITY);
        action(entries, "CREATIVE_ITEM_CREATE", Surface.ITEM_OBSERVATION,
                QuantitySemantics.SIGNED_DELTA, 33, "creative_inventory",
                ActorStatus.PLAYER, PrivacyClass.STAFF_ACTIVITY);
        action(entries, "CREATIVE_ITEM_REMOVE", Surface.ITEM_OBSERVATION,
                QuantitySemantics.SIGNED_DELTA, 33, "creative_inventory",
                ActorStatus.PLAYER, PrivacyClass.STAFF_ACTIVITY);
        action(entries, "ADMIN_ITEM_TRANSFORM", Surface.TRANSFORMATION,
                QuantitySemantics.INPUT_OUTPUT, 33, "admin_inventory",
                ActorStatus.UNKNOWN, PrivacyClass.STAFF_ACTIVITY);
        // Keep the stable ID classifiable for forward compatibility, but do
        // not advertise it as supported: creative slot packets expose a
        // before/after delta, not evidence that one item was transformed into
        // another. Any such row must remain unresolved with unknown quantity.
        add(entries, "CREATIVE_ITEM_TRANSFORM", "creative_inventory", Surface.TRANSFORMATION,
                EvidenceClass.UNRESOLVED, SourceReliability.UNRESOLVED_CAUSE,
                EndpointSemantics.UNKNOWN, QuantitySemantics.UNKNOWN,
                ActorStatus.PLAYER, PrivacyClass.STAFF_ACTIVITY,
                CREATIVE_TRANSFORM_UNSUPPORTED, CREATIVE_TRANSFORM_UNSUPPORTED, 33);

        // Planned child-issue entries are intentionally not exposed as supported
        // lookup actions until loader adapters and reproducible fixtures exist.
        planned(entries, Surface.AUDIT_EVENT, "EXPLOSION_BLOCK_CHANGE", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "FLUID_BLOCK_CHANGE", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "FIRE_BLOCK_CHANGE", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "PISTON_BLOCK_MOVE", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "ENDERMAN_BLOCK_MOVE", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "FALLING_BLOCK_CHANGE", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "DISPENSER_EFFECT", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "DROPPER_EFFECT", "world_environment", QuantitySemantics.UNKNOWN, 55);
        planned(entries, Surface.AUDIT_EVENT, "ENTITY_SPAWN", "entity_lifecycle", QuantitySemantics.NONE, 56);
        planned(entries, Surface.AUDIT_EVENT, "ENTITY_DESPAWN", "entity_lifecycle", QuantitySemantics.NONE, 56);
        planned(entries, Surface.AUDIT_EVENT, "ENTITY_KILL", "entity_lifecycle", QuantitySemantics.NONE, 56);
        planned(entries, Surface.AUDIT_EVENT, "PROJECTILE_LAUNCH", "projectile", QuantitySemantics.UNKNOWN, 56);
        planned(entries, Surface.AUDIT_EVENT, "PROJECTILE_IMPACT", "projectile", QuantitySemantics.UNKNOWN, 56);
        planned(entries, Surface.AUDIT_EVENT, "ITEM_ENTITY_SPAWN", "entity_lifecycle", QuantitySemantics.SIGNED_DELTA, 56);
        planned(entries, Surface.AUDIT_EVENT, "ITEM_ENTITY_DESPAWN", "entity_lifecycle", QuantitySemantics.SIGNED_DELTA, 56);
        planned(entries, Surface.TRANSFORMATION, "TRADE", "item_processing", QuantitySemantics.INPUT_OUTPUT, 57);
        planned(entries, Surface.TRANSFORMATION, "ENCHANTING", "item_processing", QuantitySemantics.INPUT_OUTPUT, 57);
        planned(entries, Surface.TRANSFORMATION, "BREWING", "item_processing", QuantitySemantics.INPUT_OUTPUT, 57);
        planned(entries, Surface.TRANSFORMATION, "SMITHING", "item_processing", QuantitySemantics.INPUT_OUTPUT, 57);
        planned(entries, Surface.TRANSFORMATION, "GRINDSTONE", "item_processing", QuantitySemantics.INPUT_OUTPUT, 57);
        planned(entries, Surface.TRANSFORMATION, "LOOT_GENERATION", "item_processing", QuantitySemantics.UNKNOWN, 57);

        return List.copyOf(entries);
    }

    private static void audit(List<Definition> entries, String id, String family,
                              SourceReliability reliability, ActorStatus actor,
                              PrivacyClass privacy, int ownerIssue, String... aliases) {
        audit(entries, id, family, EvidenceClass.OBSERVED, reliability, actor, privacy,
                ownerIssue, IMPLEMENTED, IMPLEMENTED, aliases);
    }

    private static void audit(List<Definition> entries, String id, String family,
                              SourceReliability reliability, ActorStatus actor,
                              PrivacyClass privacy, int ownerIssue, LoaderSupport support) {
        audit(entries, id, family, EvidenceClass.OBSERVED, reliability, actor, privacy,
                ownerIssue, support, support);
    }

    private static void audit(List<Definition> entries, String id, String family,
                              EvidenceClass evidenceClass, SourceReliability reliability,
                              ActorStatus actor, PrivacyClass privacy, int ownerIssue, String... aliases) {
        audit(entries, id, family, evidenceClass, reliability, actor, privacy,
                ownerIssue, IMPLEMENTED, IMPLEMENTED, aliases);
    }

    private static void audit(List<Definition> entries, String id, String family,
                              EvidenceClass evidenceClass, SourceReliability reliability,
                              ActorStatus actor, PrivacyClass privacy, int ownerIssue,
                              LoaderSupport fabric, LoaderSupport neoForge, String... aliases) {
        add(entries, id, family, Surface.AUDIT_EVENT, evidenceClass, reliability,
                auditEndpoints(family), QuantitySemantics.NONE, actor, privacy,
                fabric, neoForge, ownerIssue, aliases);
    }

    private static void action(List<Definition> entries, String id, Surface surface,
                               QuantitySemantics quantity, int ownerIssue,
                               String family, String... aliases) {
        action(entries, id, surface, quantity, ownerIssue, family,
                ActorStatus.UNKNOWN, PrivacyClass.SENSITIVE_LOCATION, aliases);
    }

    private static void action(List<Definition> entries, String id, Surface surface,
                               QuantitySemantics quantity, int ownerIssue, String family,
                               ActorStatus actor, PrivacyClass privacy) {
        action(entries, id, surface, quantity, ownerIssue, family, actor, privacy, new String[0]);
    }

    private static void action(List<Definition> entries, String id, Surface surface,
                               QuantitySemantics quantity, int ownerIssue, String family,
                               ActorStatus actor, PrivacyClass privacy, String... aliases) {
        add(entries, id, family, surface, EvidenceClass.OBSERVED,
                SourceReliability.DIRECT_STATE_DELTA,
                surface == Surface.TRANSFORMATION ? EndpointSemantics.INPUTS_AND_OUTPUTS
                        : EndpointSemantics.SOURCE_AND_DESTINATION,
                quantity, actor, privacy,
                IMPLEMENTED, IMPLEMENTED, ownerIssue, aliases);
    }

    private static void planned(List<Definition> entries, Surface surface, String id, String family,
                                QuantitySemantics quantity, int ownerIssue) {
        LoaderSupport pending = new LoaderSupport(LoaderStatus.PLANNED,
                "IMPLEMENTATION_PENDING_ISSUE_" + ownerIssue);
        add(entries, id, family, surface, EvidenceClass.OBSERVED,
                SourceReliability.AUTHORITATIVE_GAME_RESULT,
                plannedEndpoints(family),
                quantity, ActorStatus.UNKNOWN, PrivacyClass.SENSITIVE_LOCATION,
                pending, pending, ownerIssue);
    }

    private static void historicalTransformation(List<Definition> entries, String id, String... aliases) {
        add(entries, id, "transformation", Surface.TRANSFORMATION, EvidenceClass.UNRESOLVED,
                SourceReliability.UNRESOLVED_CAUSE, EndpointSemantics.UNKNOWN,
                QuantitySemantics.UNKNOWN, ActorStatus.PLAYER, PrivacyClass.SENSITIVE_LOCATION,
                LEGACY_TRANSFORMATION_INPUTS_UNVERIFIED, LEGACY_TRANSFORMATION_INPUTS_UNVERIFIED,
                57, aliases);
    }

    private static EndpointSemantics auditEndpoints(String family) {
        return switch (family) {
            case "player_session", "chat", "command", "item_processing" -> EndpointSemantics.PLAYER_CONTEXT;
            case "admin_item_command", "creative_inventory" -> EndpointSemantics.UNKNOWN;
            case "entity_interaction", "entity_lifecycle", "projectile" -> EndpointSemantics.ACTOR_AND_TARGET;
            default -> EndpointSemantics.WORLD_LOCATION;
        };
    }

    private static EndpointSemantics plannedEndpoints(String family) {
        return switch (family) {
            case "item_processing" -> EndpointSemantics.INPUTS_AND_OUTPUTS;
            case "entity_lifecycle", "projectile" -> EndpointSemantics.ACTOR_AND_TARGET;
            case "world_environment" -> EndpointSemantics.WORLD_LOCATION;
            default -> EndpointSemantics.UNKNOWN;
        };
    }

    private static void add(List<Definition> entries, String id, String family,
                            Surface surface, EvidenceClass evidenceClass,
                            SourceReliability reliability, EndpointSemantics endpoints,
                            QuantitySemantics quantity, ActorStatus actor, PrivacyClass privacy,
                            LoaderSupport fabric, LoaderSupport neoForge, int ownerIssue,
                            String... aliases) {
        entries.add(new Definition(id, family, surface, evidenceClass, reliability,
                EvidenceIdSemantics.INGEST_EVENT_UUID_AND_OPTIONAL_SOURCE_EVENT_ID,
                endpoints, quantity, actor, privacy, fabric, neoForge, ownerIssue,
                aliases == null ? List.of() : List.of(aliases)));
    }

    private static Map<String, Definition> indexById(List<Definition> definitions) {
        Map<String, Definition> result = new LinkedHashMap<>();
        for (Definition definition : definitions) {
            String key = key(definition.surface(), definition.id());
            if (result.putIfAbsent(key, definition) != null) {
                throw new IllegalStateException("duplicate event taxonomy ID: " + key);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static String key(Surface surface, String id) {
        return surface.name() + ":" + id;
    }

    private static Map<String, String> indexAliases(List<Definition> definitions) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Definition definition : definitions) {
            for (String alias : definition.aliases()) {
                putAlias(result, alias, definition.id());
            }
        }
        for (Definition definition : definitions) {
            // Explicit aliases retain their established canonical target even
            // when the source spelling also names a legacy audit event.
            String normalizedId = definition.id().toUpperCase(Locale.ROOT).replace('-', '_');
            if (definition.selectableInUnifiedLookup()) {
                result.putIfAbsent(normalizedId, definition.id());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void putAlias(Map<String, String> aliases, String alias, String id) {
        String normalized = alias.toUpperCase(Locale.ROOT).replace('-', '_');
        String previous = aliases.putIfAbsent(normalized, id);
        if (previous != null && !previous.equals(id)) {
            throw new IllegalStateException("event alias '" + alias + "' maps to both "
                    + previous + " and " + id);
        }
    }
}
