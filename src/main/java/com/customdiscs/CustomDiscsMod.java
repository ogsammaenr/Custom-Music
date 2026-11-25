package com.customdiscs;

import com.customdiscs.config.ModConfig;
import com.customdiscs.network.ModPayloads;
import com.customdiscs.registry.ModBlocks;
import com.customdiscs.registry.ModComponents;
import com.customdiscs.registry.ModCreativeTabs;
import com.customdiscs.registry.ModItems;
import com.customdiscs.registry.ModMenus;
import com.customdiscs.server.CustomJukeboxManager;
import com.customdiscs.server.TrackTransferManager;
import com.customdiscs.storage.TrackStorage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class CustomDiscsMod implements ModInitializer {
	public static final String MOD_ID = "customdiscs";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final TrackTransferManager TRANSFER_MANAGER = new TrackTransferManager();
	public static final CustomJukeboxManager JUKEBOX_MANAGER = new CustomJukeboxManager();

	@Override
	public void onInitialize() {
		ModConfig.load(FabricLoader.getInstance().getConfigDir());

		ModComponents.register();
		ModBlocks.register();
		ModItems.register();
		ModMenus.register();
		ModCreativeTabs.register();
		ModPayloads.registerTypes();

		registerNetworkHandlers();
		registerServerLifecycle();

		LOGGER.info("Custom Discs mod initialized (Minecraft 26.3 Fabric).");
	}

	private void registerNetworkHandlers() {
		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.TrackUploadInitPayload.TYPE, (payload, context) ->
				context.server().execute(() -> TRANSFER_MANAGER.handleUploadInit(context.player(), payload))
		);

		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.TrackUploadChunkPayload.TYPE, (payload, context) ->
				context.server().execute(() -> TRANSFER_MANAGER.handleUploadChunk(context.player(), payload))
		);

		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.TrackDownloadRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> TRANSFER_MANAGER.handleDownloadRequest(context.player(), payload))
		);
	}

	private void registerServerLifecycle() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path worldRoot = server.getWorldPath(LevelResource.ROOT);
			try {
				TrackStorage storage = new TrackStorage(worldRoot);
				TRANSFER_MANAGER.initialize(storage);
				LOGGER.info("Initialized custom disc track storage at {}", worldRoot.resolve("data/customdiscs"));
			} catch (IOException e) {
				LOGGER.error("Failed to initialize custom disc track storage", e);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TRANSFER_MANAGER.shutdown();
			JUKEBOX_MANAGER.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TRANSFER_MANAGER.tickServer(server);
			JUKEBOX_MANAGER.tickServer(server);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ModConfig cfg = ModConfig.get();
			sender.sendPacket(new ModPayloads.ServerConfigSyncPayload(
					cfg.maxFileSizeBytes,
					cfg.maxDurationSeconds,
					cfg.opusBitrateBps
			));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			TRANSFER_MANAGER.onPlayerDisconnect(handler.player.getUUID());
			JUKEBOX_MANAGER.onPlayerDisconnect(handler.player.getUUID());
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
