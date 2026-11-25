package com.customdiscs.client.audio;

import com.customdiscs.audio.CdopAudioFormat;
import com.customdiscs.network.ModPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates client-side audio caching, on-demand track downloading from the server,
 * and 3D OpenAL playback for active custom jukeboxes.
 */
public final class ClientPlaybackManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("customdiscs-playback");

	private final ClientAudioCache cache;
	private final Map<BlockPos, CustomDiscSoundInstance> activeSounds = new ConcurrentHashMap<>();
	private final Map<String, DownloadAccumulator> inFlightDownloads = new ConcurrentHashMap<>();
	private final Map<BlockPos, PendingPlayback> pendingByPos = new ConcurrentHashMap<>();

	private record PendingPlayback(BlockPos pos, String trackId, String title, String artist, int initialElapsedTicks, long requestedAtMillis) {
	}

	private static final class DownloadAccumulator {
		final int totalChunks;
		final byte[][] chunks;
		int receivedCount = 0;

		DownloadAccumulator(int totalChunks) {
			this.totalChunks = totalChunks;
			this.chunks = new byte[totalChunks][];
		}

		boolean addChunk(int index, byte[] data) {
			if (index < 0 || index >= totalChunks) {
				return false;
			}
			if (chunks[index] == null) {
				chunks[index] = data;
				receivedCount++;
			}
			return receivedCount >= totalChunks;
		}

		byte[] assemble() {
			ByteArrayOutputStream out = new ByteArrayOutputStream(totalChunks * ModPayloads.CHUNK_SIZE);
			for (byte[] chunk : chunks) {
				if (chunk != null) {
					out.write(chunk, 0, chunk.length);
				}
			}
			return out.toByteArray();
		}
	}

	public ClientPlaybackManager(ClientAudioCache cache) {
		this.cache = cache;
	}

	public ClientAudioCache getCache() {
		return cache;
	}

	public void handlePlay(ModPayloads.PlayCustomDiscPayload payload) {
		BlockPos pos = payload.pos().immutable();
		stopAt(pos);

		if (!CdopAudioFormat.isValidTrackId(payload.trackId())) {
			return;
		}

		Optional<byte[]> cached = cache.get(payload.trackId());
		if (cached.isPresent()) {
			startSound(pos, payload.trackId(), payload.title(), payload.artist(), cached.get(), payload.elapsedTicks());
			return;
		}

		// Queue playback and request track from server
		pendingByPos.put(pos, new PendingPlayback(
				pos,
				payload.trackId(),
				payload.title(),
				payload.artist(),
				payload.elapsedTicks(),
				System.currentTimeMillis()
		));

		showOverlay(Component.translatable("jukebox.customdiscs.downloading"));

		if (!inFlightDownloads.containsKey(payload.trackId())) {
			inFlightDownloads.put(payload.trackId(), new DownloadAccumulator(1));
			ClientPlayNetworking.send(new ModPayloads.TrackDownloadRequestPayload(payload.trackId()));
		}
	}

	public void handleStop(ModPayloads.StopCustomDiscPayload payload) {
		BlockPos pos = payload.pos().immutable();
		pendingByPos.remove(pos);
		stopAt(pos);
	}

	public void handleDownloadChunk(ModPayloads.TrackDownloadChunkPayload payload) {
		if (!CdopAudioFormat.isValidTrackId(payload.trackId()) || payload.totalChunks() <= 0 || payload.totalChunks() > 2048) {
			return;
		}

		DownloadAccumulator acc = inFlightDownloads.compute(payload.trackId(), (id, existing) -> {
			if (existing == null || existing.totalChunks != payload.totalChunks()) {
				return new DownloadAccumulator(payload.totalChunks());
			}
			return existing;
		});

		boolean finished = acc.addChunk(payload.chunkIndex(), payload.data());
		if (!finished) {
			return;
		}

		inFlightDownloads.remove(payload.trackId());
		byte[] fullData = acc.assemble();
		String actualHash = CdopAudioFormat.sha256Hex(fullData);
		if (!actualHash.equals(payload.trackId())) {
			LOGGER.warn("Downloaded track {} failed SHA-256 verification", payload.trackId());
			clearPendingForTrack(payload.trackId());
			showOverlay(Component.translatable("jukebox.customdiscs.download_failed"));
			return;
		}

		cache.put(payload.trackId(), fullData);

		List<PendingPlayback> ready = new ArrayList<>();
		pendingByPos.values().removeIf(pending -> {
			if (pending.trackId().equals(payload.trackId())) {
				ready.add(pending);
				return true;
			}
			return false;
		});

		long now = System.currentTimeMillis();
		for (PendingPlayback pending : ready) {
			int extraTicks = (int) Math.max(0, (now - pending.requestedAtMillis()) / 50L);
			startSound(
					pending.pos(),
					pending.trackId(),
					pending.title(),
					pending.artist(),
					fullData,
					pending.initialElapsedTicks() + extraTicks
			);
		}
	}

	public void handleDownloadError(ModPayloads.TrackDownloadErrorPayload payload) {
		LOGGER.warn("Server failed to send track {}: {}", payload.trackId(), payload.reason());
		inFlightDownloads.remove(payload.trackId());
		clearPendingForTrack(payload.trackId());
		showOverlay(Component.translatable("jukebox.customdiscs.download_failed"));
	}

	public void stopAll() {
		Minecraft mc = Minecraft.getInstance();
		for (CustomDiscSoundInstance instance : activeSounds.values()) {
			mc.getSoundManager().stop(instance);
		}
		activeSounds.clear();
		pendingByPos.clear();
		inFlightDownloads.clear();
	}

	private void stopAt(BlockPos pos) {
		CustomDiscSoundInstance existing = activeSounds.remove(pos);
		if (existing != null) {
			Minecraft.getInstance().getSoundManager().stop(existing);
		}
	}

	private void clearPendingForTrack(String trackId) {
		pendingByPos.values().removeIf(p -> p.trackId().equals(trackId));
	}

	private void startSound(BlockPos pos, String trackId, String title, String artist, byte[] cdopData, int elapsedTicks) {
		Minecraft mc = Minecraft.getInstance();
		CustomDiscSoundInstance sound = new CustomDiscSoundInstance(pos, trackId, cdopData, elapsedTicks);
		activeSounds.put(pos, sound);
		mc.getSoundManager().play(sound);

		String display = (artist != null && !artist.isBlank()) ? (artist + " - " + title) : title;
		showOverlay(Component.translatable("jukebox.customdiscs.now_playing", display));
	}

	private static void showOverlay(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(message);
		}
	}
}
