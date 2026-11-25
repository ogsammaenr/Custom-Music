package com.customdiscs.registry;

import com.customdiscs.CustomDiscsMod;
import com.customdiscs.block.DiscBurnerBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModBlocks {
	public static final ResourceKey<Block> DISC_BURNER_KEY = ResourceKey.create(Registries.BLOCK, CustomDiscsMod.id("disc_burner"));

	public static final Block DISC_BURNER = Registry.register(
			BuiltInRegistries.BLOCK,
			DISC_BURNER_KEY,
			new DiscBurnerBlock(BlockBehaviour.Properties.of()
					.setId(DISC_BURNER_KEY)
					.mapColor(MapColor.WOOD)
					.strength(2.5F, 6.0F)
					.sound(SoundType.WOOD))
	);

	private ModBlocks() {
	}

	public static void register() {
		// Triggers static initialization
	}
}
