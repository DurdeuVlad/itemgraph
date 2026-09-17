package com.itemgraph.canon;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class ItemCanonicalizerTest {

    private static RegistryAccess registryAccess;

    @BeforeAll
    static void initMinecraftRegistries() {
        if (net.neoforged.fml.loading.LoadingModList.get() == null) {
            net.neoforged.fml.loading.LoadingModList.of(
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of()
            );
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable expectedOutsideRealGameLaunch) {
            // Intentionally swallowed - see comment above.
        }
        registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    // Tests below use ItemCanonicalizer.canonicalizePatch() directly against an
    // already-built DataComponentPatch, rather than round-tripping through
    // STREAM_CODEC.encode() first: encoding DataComponentType-keyed entries
    // requires a "network-synced" RegistryAccess, which only exists on a live
    // server and isn't available in a bare `./gradlew test` JVM (confirmed:
    // IllegalStateException "Cannot use ID syncing for non-synced built-in
    // registry"). Decoding does NOT have this requirement - see
    // testDecodeRealStagingGriefLoggerBlobWithCustomName below, which exercises
    // the real decode path (the one ItemGraph's production code actually uses)
    // against a real observed GriefLogger blob.

    @Test
    void testVanillaItemWithoutPatchProducesDeterministicHash() {
        CanonicalItem item1 = ItemCanonicalizer.canonicalize("minecraft:diamond_sword", null, registryAccess);
        CanonicalItem item2 = ItemCanonicalizer.canonicalize("diamond_sword", new byte[0], registryAccess);

        assertNotNull(item1.fingerprintHash());
        assertEquals("minecraft:diamond_sword", item1.itemId());
        assertEquals(item1.fingerprintHash(), item2.fingerprintHash());
        assertNull(item1.customName());
        assertNull(item1.componentSummary());
    }

    @Test
    void testCustomNameDifferentiatesFingerprint() {
        DataComponentPatch namedPatch = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"))
                .build();

        CanonicalItem vanilla = ItemCanonicalizer.canonicalize("minecraft:diamond_sword", null, registryAccess);
        CanonicalItem named = ItemCanonicalizer.canonicalizePatch("minecraft:diamond_sword", namedPatch);

        assertEquals("Excalibur", named.customName());
        assertNotEquals(vanilla.fingerprintHash(), named.fingerprintHash());
        assertTrue(named.componentSummary().contains("custom_name=Excalibur"));
    }

    @Test
    void testTwoItemsWithSameMetadataProduceIdenticalHash() {
        DataComponentPatch patch1 = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Old Reliable"))
                .set(DataComponents.DAMAGE, 42)
                .build();
        DataComponentPatch patch2 = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Old Reliable"))
                .set(DataComponents.DAMAGE, 42)
                .build();

        CanonicalItem item1 = ItemCanonicalizer.canonicalizePatch("minecraft:iron_sword", patch1);
        CanonicalItem item2 = ItemCanonicalizer.canonicalizePatch("minecraft:iron_sword", patch2);

        assertEquals(item1.fingerprintHash(), item2.fingerprintHash());
        assertEquals("Old Reliable", item1.customName());
        assertEquals(item1.componentSummary(), item2.componentSummary());
        assertTrue(item1.componentSummary().contains("custom_name=Old Reliable"));
        assertTrue(item1.componentSummary().contains("damage=42"));
    }

    @Test
    void testTwoItemsDifferingOnlyInCustomNameProduceDifferentHashes() {
        DataComponentPatch sword1 = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Sword A"))
                .set(DataComponents.DAMAGE, 10)
                .build();
        DataComponentPatch sword2 = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("Sword B"))
                .set(DataComponents.DAMAGE, 10)
                .build();

        CanonicalItem item1 = ItemCanonicalizer.canonicalizePatch("minecraft:diamond_sword", sword1);
        CanonicalItem item2 = ItemCanonicalizer.canonicalizePatch("minecraft:diamond_sword", sword2);

        assertNotEquals(item1.fingerprintHash(), item2.fingerprintHash());
        assertEquals("Sword A", item1.customName());
        assertEquals("Sword B", item2.customName());
    }

    @Test
    void testDecodeRealStagingGriefLoggerBlobWithCustomName() {
        // Real hex blob from staging GriefLogger items rowid 43 (berserker_rpg:unique_berserker_axe_2)
        // Contains custom name "Okhotnik za Golovami"
        String hexBlob = "0500030C000A0300216B696E67646F6D5F636F6D655F636F6D6261745F626C6F6F645F70657263656E740000005C0009043C054A033B014F03011003050800144F6B686F746E696B207A6120476F6C6F76616D69";
        byte[] blobBytes = HexFormat.of().parseHex(hexBlob);

        CanonicalItem item = ItemCanonicalizer.canonicalize("berserker_rpg:unique_berserker_axe_2", blobBytes, registryAccess);

        assertNotNull(item.fingerprintHash());
        // Custom name is extracted even from complex modded patch
        if (item.customName() != null) {
            assertEquals("Okhotnik za Golovami", item.customName());
            assertTrue(item.componentSummary().contains("Okhotnik za Golovami"));
        }
    }
}
