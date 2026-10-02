package com.itemgraph.audit;

import com.itemgraph.audit.EventTaxonomy.ActorStatus;
import com.itemgraph.audit.EventTaxonomy.EvidenceClass;
import com.itemgraph.audit.EventTaxonomy.LoaderStatus;
import com.itemgraph.audit.EventTaxonomy.QuantitySemantics;
import com.itemgraph.audit.EventTaxonomy.SourceReliability;
import com.itemgraph.audit.EventTaxonomy.Surface;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
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

        Set<String> reasons = new HashSet<>();
        for (EventTaxonomy.ReasonCode reason : EventTaxonomy.unresolvedReasonCodes()) {
            assertTrue(reasons.add(reason.id()), "duplicate reason code: " + reason.id());
            assertTrue(reason.ownerIssue() >= 55 && reason.ownerIssue() <= 57, reason.id());
        }
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
    void vanillaAutomationActionsAreVersionedAndImplementedOnBothLoaders() {
        for (String action : new String[] {"HOPPER_INSERT", "HOPPER_EXTRACT", "DISPENSER_DROP", "DROPPER_DROP"}) {
            EventTaxonomy.Definition definition = EventTaxonomy.find(action, Surface.ITEM_OBSERVATION)
                    .orElseThrow();
            assertEquals(34, definition.ownerIssue(), action);
            assertEquals(QuantitySemantics.SIGNED_DELTA, definition.quantity(), action);
            assertEquals(LoaderStatus.IMPLEMENTED, definition.fabric().status(), action);
            assertEquals(LoaderStatus.IMPLEMENTED, definition.neoForge().status(), action);
        }
    }

    @Test
    void externalApiActionsHaveFixedReliabilityAndCrossLoaderDefinitions() {
        for (String action : new String[] {
                "INSERT_ITEM", "REMOVE_ITEM", "TRANSFER_ITEM", "DROP_ITEM", "PICKUP_ITEM",
                "CREATE_ITEM", "DESTROY_ITEM", "CONSUME_ITEM", "CONTAINER_NET_DELTA", "DIRECT_OBSERVED"
        }) {
            EventTaxonomy.Definition definition = EventTaxonomy.find(action, Surface.ITEM_OBSERVATION)
                    .orElseThrow(() -> new AssertionError("missing API action " + action));
            assertEquals(SourceReliability.DIRECT_STATE_DELTA, definition.sourceReliability(), action);
            assertTrue(definition.availableOnBothLoaders(), action);
            assertTrue(definition.ownerIssue() > 0, action);
        }
        assertEquals(QuantitySemantics.SIGNED_DELTA,
                EventTaxonomy.find("DIRECT_OBSERVED", Surface.ITEM_OBSERVATION)
                        .orElseThrow().quantity());
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
        assertFalse(EventTaxonomy.auditLookupTypes().contains("EXPLOSION_BLOCK_CHANGE"));
        assertFalse(EventTaxonomy.auditLookupTypes().contains("TRADE"));
        assertFalse(EventTaxonomy.unifiedLookupActions().contains("EXPLOSION_BLOCK_CHANGE"));
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
        assertEquals(QuantitySemantics.UNKNOWN, explosion.quantity());
        assertEquals(ActorStatus.UNKNOWN, explosion.actor());
        assertEquals(LoaderStatus.PLANNED, explosion.fabric().status());
        assertEquals(LoaderStatus.PLANNED, explosion.neoForge().status());

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
        assertTrue(EventTaxonomy.find("CREATIVE_ITEM_TRANSFORM", Surface.TRANSFORMATION).isPresent());
    }
}
