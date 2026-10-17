package com.itemgraph.gametest;

import com.itemgraph.api.ItemGraphApiLifecycle;
import com.itemgraph.neoforge.automation.NeoForgeItemHandlerAdapter;
import com.itemgraph.core.port.RuntimeInformationPort;
import com.itemgraph.api.ExternalInventoryEndpoint;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Durable inventory replay through the NeoForge capability adapter. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class AutomationAdapterGameTests {
    private AutomationAdapterGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty",
            batch = "zz_itemgraph_automation", timeoutTicks = 200)
    public static void portableInventoryToShulkerLikeTransferPersistsExactObservedEvidence(
            GameTestHelper helper) {
        ItemGraphApiLifecycle.setRuntimeInformation(new RuntimeInformationPort() {
            @Override public String modVersion() { return "test"; }
            @Override public boolean isModLoaded(String modId) {
                return "itemgraph_gametest".equals(modId)
                        || net.neoforged.fml.ModList.get().isLoaded(modId);
            }
        });
        AutomationAdapterConformanceFixture.run(helper, (test, service, source, portable, shulker, shulkerPos) -> {
            ItemStackHandler backpack = new ItemStackHandler(1);
            ItemStack initial = new ItemStack(Items.NETHER_STAR, 7);
            initial.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal("ItemGraph portable automation replay"));
            backpack.setStackInSlot(0, initial);
            var blockHandler = test.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                    shulkerPos, net.minecraft.core.Direction.WEST);
            test.assertTrue(blockHandler != null, "placed shulker must expose its NeoForge item capability");

            var portableAdapter = new NeoForgeItemHandlerAdapter(backpack, service, source,
                    new ExternalInventoryEndpoint(portable.reference().ownerModId(),
                            portable.reference().inventoryId(), portable.reference().displayName(), null),
                    portable.side(), test.getLevel().dimension(), AutomationAdapterConformanceFixture.TEST_MOD_ID);
            var shulkerAdapter = new NeoForgeItemHandlerAdapter(blockHandler, service, source,
                    AutomationAdapterConformanceFixture.TEST_MOD_ID, "Fixture Shulker-like Storage",
                    test.getLevel().dimension(), shulkerPos, net.minecraft.core.Direction.WEST,
                    AutomationAdapterConformanceFixture.TEST_MOD_ID);

            ItemStack extracted = portableAdapter.extractItem(0,
                    AutomationAdapterConformanceFixture.MOVED_AMOUNT, false);
            ItemStack remainder = shulkerAdapter.insertItem(0, extracted, false);
            test.assertTrue(remainder.isEmpty(), "shulker capability must accept the full committed quantity");
            test.assertValueEqual(4, backpack.getStackInSlot(0).getCount(),
                    "portable capability must retain the exact source remainder");
            test.assertValueEqual(3, blockHandler.getStackInSlot(0).getCount(),
                    "shulker capability must receive the exact committed quantity");
        });
    }
}
