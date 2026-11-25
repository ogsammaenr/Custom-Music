package com.customdiscs.client;

import com.customdiscs.CustomDiscsMod;
import com.customdiscs.client.audio.ClientAudioCache;
import com.customdiscs.client.audio.ClientPlaybackManager;
import com.customdiscs.client.color.DiscLabelTintSource;
import com.customdiscs.client.network.ClientUploadManager;
import com.customdiscs.client.screen.DiscBurnerScreen;
import com.customdiscs.network.ModPayloads;
import com.customdiscs.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.color.item.ItemTintSources;
import net.minecraft.client.gui.screens.MenuScreens;

public class CustomDiscsClient implements ClientModInitializer {
	public static ClientAudioCache AUDIO_CACHE;
	public static ClientPlaybackManager PLAYBACK_MANAGER;
	public static ClientUploadManager UPLOAD_MANAGER;

	@Override
	public void onInitializeClient() {
		AUDIO_CACHE = new ClientAudioCache(FabricLoader.getInstance().getGameDir());
		PLAYBACK_MANAGER = new ClientPlaybackManager(AUDIO_CACHE);
		UPLOAD_MANAGER = new ClientUploadManager(AUDIO_CACHE);

		MenuScreens.register(ModMenus.DISC_BURNER, DiscBurnerScreen::new);
		ItemTintSources.ID_MAPPER.put(CustomDiscsMod.id("disc_label"), DiscLabelTintSource.MAP_CODEC);

		registerNetworkReceivers();

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			PLAYBACK_MANAGER.stopAll();
			UPLOAD_MANAGER.cancel();
		}));
	}

	private void registerNetworkReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.ServerConfigSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> UPLOAD_MANAGER.updateServerConfig(payload))
		);

		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.TrackUploadStatusPayload.TYPE, (payload, context) ->
				context.client().execute(() -> UPLOAD_MANAGER.handleUploadStatus(payload))
		);

		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.TrackDownloadChunkPayload.TYPE, (payload, context) ->
				context.client().execute(() -> PLAYBACK_MANAGER.handleDownloadChunk(payload))
		);

		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.TrackDownloadErrorPayload.TYPE, (payload, context) ->
				context.client().execute(() -> PLAYBACK_MANAGER.handleDownloadError(payload))
		);

		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.PlayCustomDiscPayload.TYPE, (payload, context) ->
				context.client().execute(() -> PLAYBACK_MANAGER.handlePlay(payload))
		);

		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.StopCustomDiscPayload.TYPE, (payload, context) ->
				context.client().execute(() -> PLAYBACK_MANAGER.handleStop(payload))
		);
	}
}
