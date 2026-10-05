package com.itemgraph.audit;

import com.itemgraph.audit.EventTaxonomy.ActorStatus;
import com.itemgraph.audit.EventTaxonomy.EvidenceClass;
import com.itemgraph.audit.EventTaxonomy.LoaderStatus;
import com.itemgraph.audit.EventTaxonomy.QuantitySemantics;
import com.itemgraph.audit.EventTaxonomy.Surface;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTaxonomyTest {
    @Test
    void itemCommandPrivacyRecognizesBrigadierWhitespaceAndOnlyVanillaRoots() {
        assertTrue(AdminMutationCapture.isItemCommand("/give\t@p minecraft:diamond 1"));
        assertTrue(AdminMutationCapture.isItemCommand("/minecraft:give @p minecraft:diamond 1"));
        assertFalse(AdminMutationCapture.isItemCommand("/othermod:give @p mod:item 1"));
        assertTrue(AdminMutationCapture.shouldSuppressRawCommand(
                "/execute\tas @a run give @s minecraft:diamond 64"));
        assertTrue(AdminMutationCapture.shouldSuppressRawCommand(
                "/minecraft:execute as @a run minecraft:give @s minecraft:diamond 64"));
        assertFalse(AdminMutationCapture.shouldSuppressRawCommand("/othermod:give @p mod:item 1"));
    }

    @Test
    void definitionsAreUniquePerSurfaceAndContainCompleteVersionedContracts() {
        Set<String> keys = new HashSet<>();
        for (EventTaxonomy.Definition definition : EventTaxonomy.definitions()) {
            assertTrue(keys.add(definition.surface() + ":" + definition.id()), definition.id());
            assertNotNull(definition.evidenceClass(), definition.id());
            assertNotNull(definition.sourceReliability(), definition.id());
            assertNotNull(definition.evidenceIds(), definition.id());
            assertNotNull(definition.endpoints(), definition.id());
            assertNotNull(definition.quantity(), definition.id());
            assertNotNull(definition.actor(), definition.id());
            assertNotNull(definition.privacy(), definition.id());
            assertTrue(definition.ownerIssue() > 0, definition.id());
            assertNotNull(definition.fabric(), definition.id());
            assertNotNull(definition.neoForge(), definition.id());
            assertEquals(definition.fabric().status() == LoaderStatus.IMPLEMENTED,
                    definition.fabric().reasonCode() == null, definition.id() + " Fabric support");
            assertEquals(definition.neoForge().status() == LoaderStatus.IMPLEMENTED,
                    definition.neoForge().reasonCode() == null, definition.id() + " NeoForge support");
        }
        assertTrue(EventTaxonomy.VERSION.matches("\\d+\\.\\d+\\.\\d+"));
        assertEquals("2.2.0", EventTaxonomy.VERSION,
                "taxonomy extension remains at the expected event-contract version");

        Set<String> reasons = new HashSet<>();
        for (EventTaxonomy.ReasonCode reason : EventTaxonomy.unresolvedReasonCodes()) {
            assertTrue(reasons.add(reason.id()), "duplicate reason code: " + reason.id());
            assertTrue(reason.ownerIssue() == 33 || (reason.ownerIssue() >= 55 && reason.ownerIssue() <= 57)
                            || reason.ownerIssue() == 140,
                    reason.id());
        }
        assertTrue(reasons.contains("CONTAINER_BREAK_ACTOR_UNAVAILABLE"));
        assertTrue(reasons.contains("CONTAINER_BLOCK_ENTITY_UNAVAILABLE"));
        assertTrue(reasons.contains("CONTAINER_BLOCK_ENTITY_UNSUPPORTED"));
        assertTrue(reasons.contains("CONTAINER_SLOT_LIMIT_EXCEEDED"));
        assertTrue(reasons.contains("CONTAINER_SNAPSHOT_FAILED"));
    }

    @Test
    void canonicalActionAliasesPreserveExistingLookupBehavior() {
        assertEquals("PLAYER_JOIN", EventTaxonomy.canonicalAction("join").orElseThrow());
        assertEquals("CHAT_MESSAGE", EventTaxonomy.canonicalAction("chat").orElseThrow());
        assertEquals("COMMAND_ATTEMPT", EventTaxonomy.canonicalAction("command").orElseThrow());
        assertEquals("INTERACT_BLOCK_ATTEMPT", EventTaxonomy.canonicalAction("interact_block").orElseThrow());
        assertEquals("INTERACT_BLOCK_ATTEMPT",
                EventTaxonomy.canonicalAction("INTERACT-BLOCK-ATTEMPT").orElseThrow());
        assertEquals("ADD_ITEM", EventTaxonomy.canonicalAction("add").orElseThrow());
        assertEquals("CRAFT", EventTaxonomy.canonicalAction("craft_item").orElseThrow());
        assertEquals("ANVIL_REPAIR", EventTaxonomy.canonicalAction("anvil_rename_repair").orElseThrow());
        assertTrue(EventTaxonomy.canonicalAction("future_mod_event").isEmpty());

        assertEquals("INTERACT_BLOCK", EventTaxonomy.find("interact_block", Surface.AUDIT_EVENT)
                .orElseThrow().id());
        assertEquals("INTERACT_BLOCK_ATTEMPT",
                EventTaxonomy.find("interact_block_attempt", Surface.AUDIT_EVENT).orElseThrow().id());
        assertTrue(EventTaxonomy.find("future_mod_event", Surface.AUDIT_EVENT).isEmpty());
    }

    @Test
    void lookupChoicesExcludePlannedEventsAndKeepCurrentTypes() {
        assertTrue(EventTaxonomy.auditLookupTypes().contains("INTERACT_BLOCK_ATTEMPT"));
        assertTrue(EventTaxonomy.auditLookupTypes().contains("COMMAND_EXECUTED"));
        assertTrue(EventTaxonomy.auditLookupTypes().contains("PROJECTILE_SPAWN_ACCEPTED"));
        assertTrue(EventTaxonomy.unifiedLookupActions().contains("ADD_ITEM"));
        assertTrue(EventTaxonomy.unifiedLookupActions().contains("INTERACT_BLOCK_ATTEMPT"));
        assertFalse(EventTaxonomy.unifiedLookupActions().contains("INTERACT_BLOCK"));
        assertTrue(EventTaxonomy.unifiedLookupActions().contains("COMMAND_EXECUTED"));
        assertTrue(EventTaxonomy.auditLookupTypes().contains("EXPLOSION_BLOCK_CHANGE"));
        assertTrue(EventTaxonomy.auditLookupTypes().contains("PISTON_BLOCK_MOVE"));
        assertTrue(EventTaxonomy.auditLookupTypes().contains("PISTON_BLOCK_ATTEMPT"));
        assertFalse(EventTaxonomy.auditLookupTypes().contains("TRADE"));
        assertTrue(EventTaxonomy.unifiedLookupActions().contains("EXPLOSION_BLOCK_CHANGE"));
        assertTrue(EventTaxonomy.canonicalAction("trade").isEmpty());
        assertTrue(EventTaxonomy.auditLookupTypes().stream().allMatch(id -> {
            return EventTaxonomy.find(id, Surface.AUDIT_EVENT)
                    .map(definition -> definition.queryableOn(EventTaxonomy.Loader.FABRIC)
                            && definition.queryableOn(EventTaxonomy.Loader.NEOFORGE))
                    .orElse(false);
        }));
        assertEquals(LoaderStatus.HISTORICAL_ONLY,
                EventTaxonomy.find("COMMAND_EXECUTED", Surface.AUDIT_EVENT).orElseThrow().fabric().status());
    }

    @Test
    void plannedChildFamiliesAreExplicitAndDoNotClaimRuntimeSupport() {
        EventTaxonomy.Definition explosion = EventTaxonomy.find("EXPLOSION_BLOCK_CHANGE", Surface.AUDIT_EVENT)
                .orElseThrow();
        assertEquals(55, explosion.ownerIssue());
        assertEquals(EvidenceClass.OBSERVED, explosion.evidenceClass());
        assertEquals(QuantitySemantics.NONE, explosion.quantity());
        assertEquals(ActorStatus.UNKNOWN, explosion.actor());
        assertEquals(EventTaxonomy.SourceReliability.DIRECT_STATE_DELTA, explosion.sourceReliability());
        assertEquals(EventTaxonomy.EndpointSemantics.WORLD_LOCATION, explosion.endpoints());
        assertEquals(EventTaxonomy.PrivacyClass.SENSITIVE_LOCATION, explosion.privacy());
        assertEquals(LoaderStatus.IMPLEMENTED, explosion.fabric().status());
        assertEquals(LoaderStatus.IMPLEMENTED, explosion.neoForge().status());
        EventTaxonomy.Definition piston = EventTaxonomy.find("PISTON_BLOCK_MOVE", Surface.AUDIT_EVENT)
                .orElseThrow();
        assertEquals(55, piston.ownerIssue());
        assertEquals(EvidenceClass.OBSERVED, piston.evidenceClass());
        assertEquals(QuantitySemantics.NONE, piston.quantity());
        assertEquals(EventTaxonomy.SourceReliability.DIRECT_STATE_DELTA, piston.sourceReliability());
        assertEquals(LoaderStatus.IMPLEMENTED, piston.fabric().status());
        assertEquals(LoaderStatus.IMPLEMENTED, piston.neoForge().status());
        EventTaxonomy.Definition pistonAttempt = EventTaxonomy.find("PISTON_BLOCK_ATTEMPT", Surface.AUDIT_EVENT)
                .orElseThrow();
        assertEquals(EvidenceClass.OBSERVED, pistonAttempt.evidenceClass());
        assertEquals(EventTaxonomy.SourceReliability.GAME_CALLBACK_ATTEMPT, pistonAttempt.sourceReliability());
        assertEquals(QuantitySemantics.NONE, pistonAttempt.quantity());
        assertEquals(LoaderStatus.IMPLEMENTED, pistonAttempt.fabric().status());
        assertEquals(LoaderStatus.IMPLEMENTED, pistonAttempt.neoForge().status());
        EventTaxonomy.Definition worldAttempt = EventTaxonomy.find("WORLD_EFFECT_ATTEMPT", Surface.AUDIT_EVENT)
                .orElseThrow();
        assertEquals(55, worldAttempt.ownerIssue());
        assertEquals(EvidenceClass.OBSERVED, worldAttempt.evidenceClass());
        assertEquals(EventTaxonomy.SourceReliability.GAME_CALLBACK_ATTEMPT, worldAttempt.sourceReliability());
        assertEquals(QuantitySemantics.NONE, worldAttempt.quantity());
        assertEquals(EventTaxonomy.ActorStatus.WORLD, worldAttempt.actor());
        assertEquals(EventTaxonomy.PrivacyClass.SENSITIVE_LOCATION, worldAttempt.privacy());
        assertEquals(LoaderStatus.IMPLEMENTED, worldAttempt.fabric().status());
        assertEquals(LoaderStatus.IMPLEMENTED, worldAttempt.neoForge().status());
        for (String id : java.util.List.of("FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE",
                "ENDERMAN_BLOCK_MOVE", "FALLING_BLOCK_CHANGE")) {
            EventTaxonomy.Definition definition = EventTaxonomy.find(id, Surface.AUDIT_EVENT).orElseThrow();
            assertEquals(55, definition.ownerIssue());
            assertEquals(EvidenceClass.OBSERVED, definition.evidenceClass());
            assertEquals(QuantitySemantics.NONE, definition.quantity());
            assertEquals(EventTaxonomy.SourceReliability.DIRECT_STATE_DELTA, definition.sourceReliability());
            assertEquals(EventTaxonomy.PrivacyClass.SENSITIVE_LOCATION, definition.privacy());
            assertEquals(LoaderStatus.IMPLEMENTED, definition.fabric().status());
            assertEquals(LoaderStatus.IMPLEMENTED, definition.neoForge().status());
        }
        assertTrue(EventTaxonomy.definitions().stream()
                .filter(definition -> definition.surface() == Surface.AUDIT_EVENT
                        && definition.ownerIssue() != 55
                        && definition.fabric().status() == LoaderStatus.PLANNED)
                .allMatch(definition -> definition.evidenceClass() == EvidenceClass.UNRESOLVED
                        && definition.quantity() == QuantitySemantics.NONE));

        assertTrue(EventTaxonomy.find("TRANSFORMATION_INPUTS_NOT_OBSERVED", Surface.AUDIT_EVENT).isEmpty());
        EventTaxonomy.Definition trade = EventTaxonomy.find("TRADE", Surface.TRANSFORMATION).orElseThrow();
        assertEquals(57, trade.ownerIssue());
        assertEquals(QuantitySemantics.INPUT_OUTPUT, trade.quantity());
        assertTrue(EventTaxonomy.find("TRADE", Surface.AUDIT_EVENT).isEmpty());
        assertTrue(EventTaxonomy.unresolvedReasonCodes().stream()
                .anyMatch(reason -> reason.id().equals("TRANSFORMATION_INPUTS_NOT_OBSERVED")
                        && reason.ownerIssue() == 57));

        EventTaxonomy.Definition interaction = EventTaxonomy.find("INTERACT_ENTITY_UNRESOLVED",
                Surface.AUDIT_EVENT).orElseThrow();
        assertEquals(EvidenceClass.UNRESOLVED, interaction.evidenceClass());

        EventTaxonomy.Definition breakBlock = EventTaxonomy.find("BREAK_BLOCK", Surface.AUDIT_EVENT)
                .orElseThrow();
        assertEquals(EventTaxonomy.SourceReliability.GAME_CALLBACK_ATTEMPT,
                breakBlock.sourceReliability());
        EventTaxonomy.Definition containerBreak = EventTaxonomy
                .find("CONTAINER_BREAK_COMPLETED", Surface.AUDIT_EVENT).orElseThrow();
        assertEquals(140, containerBreak.ownerIssue());
        assertEquals(EventTaxonomy.SourceReliability.AUTHORITATIVE_GAME_RESULT,
                containerBreak.sourceReliability());
        assertEquals(ActorStatus.PLAYER, containerBreak.actor());
        EventTaxonomy.Definition unresolvedContainerBreak = EventTaxonomy
                .find("CONTAINER_BREAK_UNRESOLVED", Surface.AUDIT_EVENT).orElseThrow();
        assertEquals(EvidenceClass.UNRESOLVED, unresolvedContainerBreak.evidenceClass());
        assertEquals(ActorStatus.UNKNOWN, unresolvedContainerBreak.actor(),
                "an unresolved break family may have no authoritative player actor");
        assertTrue(EventTaxonomy.unifiedLookupActions().contains("REMOVE_ITEM"));
        assertTrue(EventTaxonomy.unresolvedReasonCodes().stream()
                .anyMatch(reason -> reason.id().equals("CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED")
                        && reason.ownerIssue() == 140));
        EventTaxonomy.Definition placeBlock = EventTaxonomy.find("PLACE_BLOCK", Surface.AUDIT_EVENT)
                .orElseThrow();
        assertEquals(EventTaxonomy.SourceReliability.GAME_CALLBACK_ATTEMPT,
                placeBlock.sourceReliability());
        assertEquals(ActorStatus.PLAYER,
                EventTaxonomy.find("KILL_ENTITY", Surface.AUDIT_EVENT).orElseThrow().actor());
        assertEquals(LoaderStatus.HISTORICAL_ONLY,
                EventTaxonomy.find("INTERACT_BLOCK", Surface.AUDIT_EVENT).orElseThrow().fabric().status());
    }

    @Test
    void administrativeAndCreativeItemEvidenceIsStaffPrivateAndQueryable() {
        for (String id : new String[] {"ADMIN_ITEM_COMMAND_ATTEMPT", "ADMIN_ITEM_COMMAND_EFFECT",
                "ADMIN_ITEM_COMMAND_FAILURE", "ADMIN_ITEM_COMMAND_UNRESOLVED", "CREATIVE_SLOT_ATTEMPT", "CREATIVE_SLOT_EFFECT",
                "CREATIVE_BLOCK_ATTEMPT", "CREATIVE_BLOCK_RESULT", "CREATIVE_BLOCK_UNRESOLVED"}) {
            EventTaxonomy.Definition event = EventTaxonomy.find(id, Surface.AUDIT_EVENT).orElseThrow();
            assertEquals(33, event.ownerIssue());
            assertEquals(EventTaxonomy.PrivacyClass.STAFF_ACTIVITY, event.privacy());
            assertTrue(event.queryableOn(EventTaxonomy.Loader.FABRIC));
            assertTrue(event.queryableOn(EventTaxonomy.Loader.NEOFORGE));
        }
        assertEquals(EventTaxonomy.SourceReliability.AUTHORITATIVE_GAME_RESULT,
                EventTaxonomy.find("CREATIVE_BLOCK_RESULT", Surface.AUDIT_EVENT).orElseThrow()
                        .sourceReliability());
        assertEquals(EventTaxonomy.EvidenceClass.UNRESOLVED,
                EventTaxonomy.find("CREATIVE_BLOCK_UNRESOLVED", Surface.AUDIT_EVENT).orElseThrow()
                        .evidenceClass());
        for (String id : new String[] {"ADMIN_ITEM_CREATE", "ADMIN_ITEM_REMOVE",
                "CREATIVE_ITEM_CREATE", "CREATIVE_ITEM_REMOVE"}) {
            EventTaxonomy.Definition event = EventTaxonomy.find(id, Surface.ITEM_OBSERVATION).orElseThrow();
            assertEquals(33, event.ownerIssue());
            assertEquals(EventTaxonomy.PrivacyClass.STAFF_ACTIVITY, event.privacy());
            assertEquals(QuantitySemantics.SIGNED_DELTA, event.quantity());
        }
        assertTrue(EventTaxonomy.find("ADMIN_ITEM_TRANSFORM", Surface.TRANSFORMATION).isPresent());
        EventTaxonomy.Definition creativeTransform = EventTaxonomy
                .find("CREATIVE_ITEM_TRANSFORM", Surface.TRANSFORMATION).orElseThrow();
        assertEquals(LoaderStatus.UNSUPPORTED, creativeTransform.fabric().status());
        assertEquals("CREATIVE_TRANSFORM_CAUSE_NOT_REPORTED", creativeTransform.fabric().reasonCode());
        assertEquals(LoaderStatus.UNSUPPORTED, creativeTransform.neoForge().status());
        assertEquals("CREATIVE_TRANSFORM_CAUSE_NOT_REPORTED", creativeTransform.neoForge().reasonCode());
        assertEquals(EvidenceClass.UNRESOLVED, creativeTransform.evidenceClass());
        assertEquals(EventTaxonomy.SourceReliability.UNRESOLVED_CAUSE,
                creativeTransform.sourceReliability());
        assertEquals(EventTaxonomy.EndpointSemantics.UNKNOWN, creativeTransform.endpoints());
        assertEquals(QuantitySemantics.UNKNOWN, creativeTransform.quantity());
        assertTrue(EventTaxonomy.traceableTransformationTypes().contains("CRAFT"));
        assertFalse(EventTaxonomy.traceableTransformationTypes().contains("CREATIVE_ITEM_TRANSFORM"));
        assertTrue(EventTaxonomy.isTraceableTransformation("ADMIN_ITEM_TRANSFORM"));
        assertFalse(EventTaxonomy.isTraceableTransformation("FUTURE_TRANSFORM"));
        assertTrue(EventTaxonomy.unresolvedReasonCodes().stream()
                .anyMatch(reason -> reason.id().equals("CREATIVE_TRANSFORM_CAUSE_NOT_REPORTED")));
        assertFalse(creativeTransform.queryableOn(EventTaxonomy.Loader.FABRIC));
        assertFalse(creativeTransform.queryableOn(EventTaxonomy.Loader.NEOFORGE));
        assertFalse(EventTaxonomy.unifiedLookupActions().contains("CREATIVE_ITEM_TRANSFORM"));
        EventTaxonomy.definitions().stream()
                .filter(definition -> definition.surface() == Surface.TRANSFORMATION)
                .forEach(definition -> assertEquals(definition.fabric(), definition.neoForge(),
                        "shared transformation queries need matching loader classifications: "
                                + definition.id()));
        List<String> implementedOnBothLoaders = EventTaxonomy.definitions().stream()
                .filter(definition -> definition.surface() == Surface.TRANSFORMATION)
                .filter(definition -> definition.fabric().status() == EventTaxonomy.LoaderStatus.IMPLEMENTED
                        && definition.neoForge().status() == EventTaxonomy.LoaderStatus.IMPLEMENTED)
                .map(EventTaxonomy.Definition::id)
                .toList();
        assertEquals(implementedOnBothLoaders, EventTaxonomy.traceableTransformationTypes());
    }
}
