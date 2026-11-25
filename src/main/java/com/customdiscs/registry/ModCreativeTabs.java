package com.customdiscs.registry;

import com.customdiscs.CustomDiscsMod;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModCreativeTabs {
	public static final ResourceKey<CreativeModeTab> MAIN_TAB_KEY = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			CustomDiscsMod.id("main")
	);

	public static final CreativeModeTab MAIN_TAB = Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			MAIN_TAB_KEY,
			FabricCreativeModeTab.builder()
					.title(Component.translatable("itemGroup.customdiscs.main"))
					.icon(() -> new ItemStack(ModItems.DISC_BURNER))
					.displayItems((params, output) -> {
						output.accept(ModItems.DISC_BURNER);
						output.accept(ModItems.BLANK_DISC);
					})
					.build()
	);

	private ModCreativeTabs() {
	}

	public static void register() {
		// Triggers static initialization
	}
}
