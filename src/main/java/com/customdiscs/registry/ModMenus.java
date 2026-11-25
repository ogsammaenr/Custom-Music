package com.customdiscs.registry;

import com.customdiscs.CustomDiscsMod;
import com.customdiscs.menu.DiscBurnerMenu;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
	public static final MenuType<DiscBurnerMenu> DISC_BURNER = Registry.register(
			BuiltInRegistries.MENU,
			CustomDiscsMod.id("disc_burner"),
			new ExtendedMenuType<>(DiscBurnerMenu::new, BlockPos.STREAM_CODEC)
	);

	private ModMenus() {
	}

	public static void register() {
		// Triggers static initialization
	}
}
