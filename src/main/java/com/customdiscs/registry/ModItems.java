package com.customdiscs.registry;

import com.customdiscs.CustomDiscsMod;
import com.customdiscs.item.BlankDiscItem;
import com.customdiscs.item.CustomMusicDiscItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public final class ModItems {
	public static final ResourceKey<Item> BLANK_DISC_KEY = ResourceKey.create(Registries.ITEM, CustomDiscsMod.id("blank_disc"));
	public static final ResourceKey<Item> CUSTOM_MUSIC_DISC_KEY = ResourceKey.create(Registries.ITEM, CustomDiscsMod.id("custom_music_disc"));
	public static final ResourceKey<Item> DISC_BURNER_KEY = ResourceKey.create(Registries.ITEM, CustomDiscsMod.id("disc_burner"));

	public static final Item BLANK_DISC = Registry.register(
			BuiltInRegistries.ITEM,
			BLANK_DISC_KEY,
			new BlankDiscItem(new Item.Properties()
					.setId(BLANK_DISC_KEY)
					.stacksTo(16))
	);

	public static final Item CUSTOM_MUSIC_DISC = Registry.register(
			BuiltInRegistries.ITEM,
			CUSTOM_MUSIC_DISC_KEY,
			new CustomMusicDiscItem(new Item.Properties()
					.setId(CUSTOM_MUSIC_DISC_KEY)
					.stacksTo(1)
					.rarity(Rarity.RARE))
	);

	public static final Item DISC_BURNER = Registry.register(
			BuiltInRegistries.ITEM,
			DISC_BURNER_KEY,
			new BlockItem(ModBlocks.DISC_BURNER, new Item.Properties()
					.setId(DISC_BURNER_KEY)
					.useBlockDescriptionPrefix())
	);

	private ModItems() {
	}

	public static void register() {
		// Triggers static initialization
	}
}
