package com.itemgraph.fabric.gametest;

import com.itemgraph.gametest.AutomationAdapterConformanceFixture;
import com.itemgraph.api.ItemGraphService;
import com.itemgraph.api.AutomationEndpoint;
import com.itemgraph.api.SourceHandle;
import com.itemgraph.fabric.automation.FabricTransferStorageAdapter;
import com.itemgraph.api.ItemGraphApiLifecycle;
import com.itemgraph.core.port.RuntimeInformationPort;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;


/** Durable inventory replay through Fabric Transfer API adapters. */
public final class AutomationAdapterGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "zz_itemgraph_automation", timeoutTicks = 1200)
    public void portableInventoryToShulkerLikeTransferPersistsExactObservedEvidence(GameTestHelper helper) {
        ItemGraphApiLifecycle.setRuntimeInformation(new RuntimeInformationPort() {
            @Override public String modVersion() { return "test"; }
            @Override public boolean isModLoaded(String modId) {
                return "itemgraph_gametest".equals(modId)
                        || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modId);
            }
        });
        AutomationAdapterConformanceFixture.run(helper, this::replay);
    }

    private void replay(GameTestHelper helper, ItemGraphService service, SourceHandle source,
                        AutomationEndpoint portable, AutomationEndpoint shulker, BlockPos shulkerPos) {
        PortableStorage backpack = new PortableStorage();
        backpack.seed(new ItemStack(Items.NETHER_STAR, 7));
        Storage<ItemVariant> nativeShulker = ItemStorage.SIDED.find(
                helper.getLevel(), shulkerPos, Direction.WEST);
        helper.assertTrue(nativeShulker instanceof SlottedStorage<?>,
                "placed shulker must expose slotted Fabric Transfer API storage");
        SlottedStorage<ItemVariant> slots = (SlottedStorage<ItemVariant>) nativeShulker;
        Storage<ItemVariant> backpackSlot = ((SlottedStorage<ItemVariant>) backpack).getSlot(0);
        Storage<ItemVariant> shulkerSlot = slots.getSlot(0);
        var backpackAdapter = new FabricTransferStorageAdapter(backpackSlot, service, source, portable,
                helper.getLevel().dimension(), AutomationAdapterConformanceFixture.TEST_MOD_ID, 0);
        var shulkerAdapter = new FabricTransferStorageAdapter(shulkerSlot, service, source, shulker,
                helper.getLevel().dimension(), AutomationAdapterConformanceFixture.TEST_MOD_ID, 0);
        ItemVariant star = ItemVariant.of(Items.NETHER_STAR);
        try (Transaction transaction = Transaction.openOuter()) {
            long extracted = backpackAdapter.extract(star, AutomationAdapterConformanceFixture.MOVED_AMOUNT, transaction);
            long inserted = shulkerAdapter.insert(star, extracted, transaction);
            helper.assertValueEqual((long) AutomationAdapterConformanceFixture.MOVED_AMOUNT, extracted,
                    "portable storage must extract the requested quantity");
            helper.assertValueEqual(extracted, inserted, "shulker storage must accept the full transfer");
            transaction.commit();
        }
        helper.assertValueEqual(4, backpack.stack().getCount(), "portable storage must retain its exact remainder");
        long shulkerCount = shulkerSlot.iterator().next().getAmount();
        helper.assertValueEqual(3L, shulkerCount, "shulker storage must receive the exact moved quantity");
    }

    private static final class PortableStorage extends SingleStackStorage {
        private ItemStack stack = ItemStack.EMPTY;

        @Override protected ItemStack getStack() { return stack; }
        @Override protected void setStack(ItemStack updated) { stack = updated; }
        void seed(ItemStack initial) { stack = initial; }
        ItemStack stack() { return stack; }
    }
}
