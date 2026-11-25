package com.customdiscs.client.color;

import com.customdiscs.component.CustomDiscData;
import com.customdiscs.registry.ModComponents;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Tints layer1 (the center label) of {@code customdiscs:custom_music_disc}
 * according to the color saved in its {@link CustomDiscData} component.
 */
public final class DiscLabelTintSource implements ItemTintSource {
	public static final DiscLabelTintSource INSTANCE = new DiscLabelTintSource();
	public static final MapCodec<DiscLabelTintSource> MAP_CODEC = MapCodec.unit(INSTANCE);

	private DiscLabelTintSource() {
	}

	@Override
	public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity) {
		CustomDiscData data = stack.get(ModComponents.CUSTOM_DISC_DATA);
		int rgb = (data != null) ? (data.color() & 0xFFFFFF) : CustomDiscData.DEFAULT_COLOR;
		return 0xFF000000 | rgb;
	}

	@Override
	public MapCodec<? extends ItemTintSource> type() {
		return MAP_CODEC;
	}
}
