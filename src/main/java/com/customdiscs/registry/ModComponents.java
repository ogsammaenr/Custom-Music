package com.customdiscs.registry;

import com.customdiscs.CustomDiscsMod;
import com.customdiscs.component.CustomDiscData;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ModComponents {
	public static final DataComponentType<CustomDiscData> CUSTOM_DISC_DATA = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			CustomDiscsMod.id("disc_data"),
			DataComponentType.<CustomDiscData>builder()
					.persistent(CustomDiscData.CODEC)
					.networkSynchronized(CustomDiscData.STREAM_CODEC)
					.build()
	);

	private ModComponents() {
	}

	public static void register() {
		// Triggers static initialization
	}
}
