package com.customdiscs.server;

import com.customdiscs.audio.CdopAudioFormat;
import com.customdiscs.component.CustomDiscData;
import com.customdiscs.config.ModConfig;
import com.customdiscs.menu.DiscBurnerMenu;
import com.customdiscs.network.ModPayloads;
import com.customdiscs.storage.TrackStorage;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages rate-limited, chunked uploads (Client -> Server) and
 * tick-paced downloads (Server -> Client) for multiplayer audio sync.
 */
public final class TrackTransferManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("customdiscs-transfer");
	private static final int DOWNLOAD_CHUNKS_PER_TICK = 8; // ~192 KB/tick (~3.8 MB/s) per player

	private volatile TrackStorage storage;
	private final Map<UUID, UploadSession> activeUploads = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastUploadCompleteMillis = new ConcurrentHashMap<>();
	private final Queue<DownloadSession> activeDownloads = new ConcurrentLinkedQueue<>();

	public void initialize(TrackStorage storage) {
		this.storage = storage;
		this.activeUploads.clear();
		this.lastUploadCompleteMillis.clear();
		this.activeDownloads.clear();
	}

	public void shutdown() {
		this.activeUploads.clear();
		this.activeDownloads.clear();
		this.lastUploadCompleteMillis.clear();
		this.storage = null;
	}

	public void onPlayerDisconnect(UUID playerId) {
		this.activeUploads.remove(playerId);
		this.activeDownloads.removeIf(dl -> dl.playerId.equals(playerId));
	}

	public void handleUploadInit(ServerPlayer player, ModPayloads.TrackUploadInitPayload payload) {
		TrackStorage store = this.storage;
		if (store == null) {
			sendUploadError(player, payload.trackId(), "Track storage unavailable on server");
			return;
		}

		ModConfig cfg = ModConfig.get();
		if (!cfg.allowAllPlayersToUpload && !player.level().getServer().getPlayerList().isOp(player.nameAndId())) {
			sendUploadError(player, payload.trackId(), "You do not have permission to upload custom discs");
			return;
		}

		long now = System.currentTimeMillis();
		Long lastCompleted = lastUploadCompleteMillis.get(player.getUUID());
		if (lastCompleted != null && (now - lastCompleted) < cfg.uploadCooldownSeconds * 1000L) {
			long remain = Math.max(1, (cfg.uploadCooldownSeconds * 1000L - (now - lastCompleted) + 999) / 1000);
			sendUploadError(player, payload.trackId(), "Please wait " + remain + "s before burning another disc");
			return;
		}

		if (!CdopAudioFormat.isValidTrackId(payload.trackId())) {
			sendUploadError(player, payload.trackId(), "Invalid track hash");
			return;
		}

		if (!(player.containerMenu instanceof DiscBurnerMenu burnerMenu)
				|| !burnerMenu.getBlockPos().equals(payload.burnerPos())
				|| !burnerMenu.stillValid(player)) {
			sendUploadError(player, payload.trackId(), "Disc Burner is not open");
			return;
		}

		if (!burnerMenu.hasBurnableDisc()) {
			sendUploadError(player, payload.trackId(), "Insert a Blank or Custom Disc first");
			return;
		}

		String sanitizedTitle = sanitizeLabel(payload.title(), "Untitled Track");
		String sanitizedArtist = sanitizeLabel(payload.artist(), "");
		int color = payload.color() & 0xFFFFFF;

		// Deduplication fast-path: if the server already stores this exact SHA-256 track, skip upload!
		if (store.exists(payload.trackId())) {
			Optional<byte[]> existing = store.load(payload.trackId());
			if (existing.isPresent()) {
				try {
					CdopAudioFormat.Header header = CdopAudioFormat.validate(existing.get(), cfg.maxDurationSeconds);
					CustomDiscData discData = new CustomDiscData(
							payload.trackId(),
							sanitizedTitle,
							sanitizedArtist,
							header.durationTicks(),
							color
					);
					if (burnerMenu.burnDisc(discData)) {
						lastUploadCompleteMillis.put(player.getUUID(), now);
						ServerPlayNetworking.send(player, new ModPayloads.TrackUploadStatusPayload(
								payload.trackId(), ModPayloads.UPLOAD_STATUS_COMPLETE, 100, "OK"
						));
						return;
					}
				} catch (IOException ignored) {
					// Fall through to re-upload if stored file was somehow invalid
				}
			}
		}

		if (payload.totalSize() <= CdopAudioFormat.HEADER_SIZE || payload.totalSize() > cfg.maxFileSizeBytes) {
			sendUploadError(player, payload.trackId(), "File size exceeds server limit (" + (cfg.maxFileSizeBytes / 1024) + " KB)");
			return;
		}

		int expectedChunks = (payload.totalSize() + ModPayloads.CHUNK_SIZE - 1) / ModPayloads.CHUNK_SIZE;
		if (payload.totalChunks() != expectedChunks || expectedChunks <= 0) {
			sendUploadError(player, payload.trackId(), "Invalid chunk metadata");
			return;
		}

		int maxDurationTicks = cfg.maxDurationSeconds * 20;
		if (payload.durationTicks() <= 0 || payload.durationTicks() > maxDurationTicks) {
			sendUploadError(player, payload.trackId(), "Track exceeds max duration (" + cfg.maxDurationSeconds + "s)");
			return;
		}

		UploadSession session = new UploadSession(
				payload.trackId(),
				payload.totalSize(),
				expectedChunks,
				sanitizedTitle,
				sanitizedArtist,
				color,
				payload.burnerPos()
		);
		activeUploads.put(player.getUUID(), session);

		ServerPlayNetworking.send(player, new ModPayloads.TrackUploadStatusPayload(
				payload.trackId(), ModPayloads.UPLOAD_STATUS_ACCEPTED, 0, "Accepted"
		));
	}

	public void handleUploadChunk(ServerPlayer player, ModPayloads.TrackUploadChunkPayload payload) {
		UploadSession session = activeUploads.get(player.getUUID());
		if (session == null || !session.trackId.equals(payload.trackId())) {
			return;
		}

		if (payload.chunkIndex() != session.nextChunkIndex) {
			activeUploads.remove(player.getUUID());
			sendUploadError(player, payload.trackId(), "Out-of-order upload chunk");
			return;
		}

		byte[] chunk = payload.data();
		if (chunk.length == 0 || chunk.length > ModPayloads.CHUNK_SIZE
				|| session.receivedBytes + chunk.length > session.totalSize) {
			activeUploads.remove(player.getUUID());
			sendUploadError(player, payload.trackId(), "Invalid upload chunk size");
			return;
		}

		session.buffer.write(chunk, 0, chunk.length);
		session.receivedBytes += chunk.length;
		session.nextChunkIndex++;

		int progress = Math.min(99, (int) ((session.receivedBytes * 100L) / session.totalSize));

		if (session.nextChunkIndex < session.totalChunks) {
			if (session.nextChunkIndex % 4 == 0) {
				ServerPlayNetworking.send(player, new ModPayloads.TrackUploadStatusPayload(
						session.trackId, ModPayloads.UPLOAD_STATUS_PROGRESS, progress, ""
				));
			}
			return;
		}

		// Final chunk received
		activeUploads.remove(player.getUUID());
		if (session.receivedBytes != session.totalSize) {
			sendUploadError(player, session.trackId, "Incomplete upload size");
			return;
		}

		TrackStorage store = this.storage;
		if (store == null) {
			sendUploadError(player, session.trackId, "Server track storage unavailable");
			return;
		}

		if (!(player.containerMenu instanceof DiscBurnerMenu burnerMenu)
				|| !burnerMenu.getBlockPos().equals(session.burnerPos)
				|| !burnerMenu.stillValid(player)
				|| !burnerMenu.hasBurnableDisc()) {
			sendUploadError(player, session.trackId, "Disc was removed from the Disc Burner");
			return;
		}

		ModConfig cfg = ModConfig.get();
		try {
			byte[] fullData = session.buffer.toByteArray();
			CdopAudioFormat.Header header = store.saveValidated(
					session.trackId,
					fullData,
					cfg.maxDurationSeconds,
					cfg.maxStoredTracks
			);

			CustomDiscData discData = new CustomDiscData(
					session.trackId,
					session.title,
					session.artist,
					header.durationTicks(),
					session.color
			);

			if (!burnerMenu.burnDisc(discData)) {
				sendUploadError(player, session.trackId, "Failed to update disc slot");
				return;
			}

			lastUploadCompleteMillis.put(player.getUUID(), System.currentTimeMillis());
			ServerPlayNetworking.send(player, new ModPayloads.TrackUploadStatusPayload(
					session.trackId, ModPayloads.UPLOAD_STATUS_COMPLETE, 100, "Complete"
			));
		} catch (IOException e) {
			LOGGER.warn("Rejected uploaded track from {}: {}", player.getName().getString(), e.getMessage());
			sendUploadError(player, session.trackId, e.getMessage());
		}
	}

	public void handleDownloadRequest(ServerPlayer player, ModPayloads.TrackDownloadRequestPayload payload) {
		TrackStorage store = this.storage;
		if (store == null || !CdopAudioFormat.isValidTrackId(payload.trackId())) {
			ServerPlayNetworking.send(player, new ModPayloads.TrackDownloadErrorPayload(payload.trackId(), "Invalid track ID"));
			return;
		}

		// Prevent duplicate simultaneous downloads of the same track to the same player
		for (DownloadSession dl : activeDownloads) {
			if (dl.playerId.equals(player.getUUID()) && dl.trackId.equals(payload.trackId())) {
				return;
			}
		}

		Optional<byte[]> loaded = store.load(payload.trackId());
		if (loaded.isEmpty()) {
			ServerPlayNetworking.send(player, new ModPayloads.TrackDownloadErrorPayload(payload.trackId(), "Track not found on server"));
			return;
		}

		activeDownloads.add(new DownloadSession(player.getUUID(), payload.trackId(), loaded.get()));
	}

	public void tickServer(MinecraftServer server) {
		if (activeDownloads.isEmpty()) {
			return;
		}

		Iterator<DownloadSession> it = activeDownloads.iterator();
		while (it.hasNext()) {
			DownloadSession dl = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(dl.playerId);
			if (player == null) {
				it.remove();
				continue;
			}

			int chunksSent = 0;
			while (chunksSent < DOWNLOAD_CHUNKS_PER_TICK && dl.nextChunkIndex < dl.totalChunks) {
				int start = dl.nextChunkIndex * ModPayloads.CHUNK_SIZE;
				int end = Math.min(dl.data.length, start + ModPayloads.CHUNK_SIZE);
				byte[] slice = Arrays.copyOfRange(dl.data, start, end);
				ServerPlayNetworking.send(player, new ModPayloads.TrackDownloadChunkPayload(
						dl.trackId,
						dl.nextChunkIndex,
						dl.totalChunks,
						slice
				));
				dl.nextChunkIndex++;
				chunksSent++;
			}

			if (dl.nextChunkIndex >= dl.totalChunks) {
				it.remove();
			}
		}
	}

	private static void sendUploadError(ServerPlayer player, String trackId, String reason) {
		String safeId = trackId == null ? "" : (trackId.length() > 64 ? trackId.substring(0, 64) : trackId);
		String safeMsg = reason == null ? "Upload rejected" : (reason.length() > 240 ? reason.substring(0, 240) : reason);
		ServerPlayNetworking.send(player, new ModPayloads.TrackUploadStatusPayload(
				safeId, ModPayloads.UPLOAD_STATUS_ERROR, 0, safeMsg
		));
	}

	private static String sanitizeLabel(String raw, String fallback) {
		if (raw == null) {
			return fallback;
		}
		String cleaned = raw.replace('\n', ' ').replace('\r', ' ').trim();
		if (cleaned.isEmpty()) {
			return fallback;
		}
		return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
	}

	private static final class UploadSession {
		final String trackId;
		final int totalSize;
		final int totalChunks;
		final String title;
		final String artist;
		final int color;
		final BlockPos burnerPos;
		final ByteArrayOutputStream buffer;
		int nextChunkIndex = 0;
		int receivedBytes = 0;

		UploadSession(String trackId, int totalSize, int totalChunks, String title, String artist, int color, BlockPos burnerPos) {
			this.trackId = trackId;
			this.totalSize = totalSize;
			this.totalChunks = totalChunks;
			this.title = title;
			this.artist = artist;
			this.color = color;
			this.burnerPos = burnerPos;
			this.buffer = new ByteArrayOutputStream(totalSize);
		}
	}

	private static final class DownloadSession {
		final UUID playerId;
		final String trackId;
		final byte[] data;
		final int totalChunks;
		int nextChunkIndex = 0;

		DownloadSession(UUID playerId, String trackId, byte[] data) {
			this.playerId = playerId;
			this.trackId = trackId;
			this.data = data;
			this.totalChunks = Math.max(1, (data.length + ModPayloads.CHUNK_SIZE - 1) / ModPayloads.CHUNK_SIZE);
		}
	}
}
