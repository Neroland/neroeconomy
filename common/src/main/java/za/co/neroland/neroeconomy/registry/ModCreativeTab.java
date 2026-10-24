package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * NeroEconomy's own creative tab, registered cross-loader via Core's {@link RegistrationProvider}
 * over the vanilla {@code CREATIVE_MODE_TAB} registry. Every Nero mod has its own tab; Core's
 * "Neroland" tab holds only Core's items.
 */
public final class ModCreativeTab {

    public static final RegistrationProvider<CreativeModeTab> TABS =
            RegistrationProvider.get(Registries.CREATIVE_MODE_TAB, NeroEconomyCommon.MOD_ID);

    // NOTE: vanilla CreativeModeTab.builder takes (Row, column); the no-arg overload and
    // withTabsBefore/After are NeoForge-only extensions, so they are avoided here (common = raw vanilla).
    public static final RegistryEntry<CreativeModeTab> NEROECONOMY = TABS.register("neroeconomy",
            key -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.neroeconomy"))
                    .icon(() -> new ItemStack(ModItems.CREDIT_CHIP.get()))
                    .displayItems((params, output) -> ModItems.creativeContents().forEach(output::accept))
                    .build());

    private ModCreativeTab() {
    }

    /** Force class-load so the static tab registration runs. Call after {@link ModItems#init()}. */
    public static void init() {
    }
}
