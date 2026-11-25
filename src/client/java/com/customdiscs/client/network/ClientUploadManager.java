package com.customdiscs.client.network;

import com.customdiscs.client.audio.AudioFileDecoder;
import com.customdiscs.client.audio.ClientAudioCache;
import com.customdiscs.config.ModConfig;
import com.customdiscs.network.ModPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages synced server upload limits and streams encoded tracks to the server in chunks.
 */
public final class ClientUploadManager {
	public interface UploadListener {
		void onUploadProgress(int percent);
		void onUploadComplete();
		void onUploadError(String errorMessage);
	}

	private static final ExecutorService UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "CustomDiscs-Uploader");
		t.setDaemon(true);
		return t;
	});

	private final ClientAudioCache cache;
	private volatile int maxFileSizeBytes = ModConfig.get().maxFileSizeBytes;
	private volatile int maxDurationSeconds = ModConfig.get().maxDurationSeconds;
	private volatile int opusBitrateBps = ModConfig.get().opusBitrateBps;

	private volatile PendingUpload pendingUpload;
	private volatile UploadListener listener;

	private record PendingUpload(String trackId, byte[] cdopBytes, int totalChunks) {
	}

	public ClientUploadManager(ClientAudioCache cache) {
		this.cache = cache;
	}

	public void updateServerConfig(ModPayloads.ServerConfigSyncPayload payload) {
		this.maxFileSizeBytes = payload.maxFileSizeBytes();
		this.maxDurationSeconds = payload.maxDurationSeconds();
		this.opusBitrateBps = payload.opusBitrateBps();
	}

	public int maxFileSizeBytes() {
		return maxFileSizeBytes;
	}

	public int maxDurationSeconds() {
		return maxDurationSeconds;
	}

	public int opusBitrateBps() {
		return opusBitrateBps;
	}

	public void setListener(UploadListener listener) {
		this.listener = listener;
	}

	public void startUpload(AudioFileDecoder.EncodedTrack track, String title, String artist, int color, BlockPos burnerPos) {
		byte[] bytes = track.cdopBytes();
		int totalChunks = (bytes.length + ModPayloads.CHUNK_SIZE - 1) / ModPayloads.CHUNK_SIZE;

		// Pre-cache locally so the uploader plays their own disc instantaneously
		cache.put(track.trackId(), bytes);

		this.pendingUpload = new PendingUpload(track.trackId(), bytes, totalChunks);

		ClientPlayNetworking.send(new ModPayloads.TrackUploadInitPayload(
				track.trackId(),
				bytes.length,
				totalChunks,
				title,
				artist,
				track.durationTicks(),
				color,
				burnerPos
		));
	}

	public void handleUploadStatus(ModPayloads.TrackUploadStatusPayload payload) {
		UploadListener currentListener = this.listener;
		PendingUpload pending = this.pendingUpload;

		switch (payload.status()) {
			case ModPayloads.UPLOAD_STATUS_ACCEPTED -> {
				if (pending != null && pending.trackId().equals(payload.trackId())) {
					streamChunks(pending);
				}
			}
			case ModPayloads.UPLOAD_STATUS_PROGRESS -> {
				if (currentListener != null) {
					currentListener.onUploadProgress(payload.progressPercent());
				}
			}
			case ModPayloads.UPLOAD_STATUS_COMPLETE -> {
				this.pendingUpload = null;
				if (currentListener != null) {
					currentListener.onUploadComplete();
				}
			}
			case ModPayloads.UPLOAD_STATUS_ERROR -> {
				this.pendingUpload = null;
				if (currentListener != null) {
					currentListener.onUploadError(payload.message());
				}
			}
			default -> {
			}
		}
	}

	public void cancel() {
		this.pendingUpload = null;
	}

	private void streamChunks(PendingUpload upload) {
		UPLOAD_EXECUTOR.submit(() -> {
			byte[] data = upload.cdopBytes();
			int totalChunks = upload.totalChunks();
			for (int i = 0; i < totalChunks; i++) {
				if (this.pendingUpload != upload) {
					return;
				}
				int start = i * ModPayloads.CHUNK_SIZE;
				int end = Math.min(data.length, start + ModPayloads.CHUNK_SIZE);
				byte[] slice = Arrays.copyOfRange(data, start, end);
				ClientPlayNetworking.send(new ModPayloads.TrackUploadChunkPayload(upload.trackId(), i, slice));

				// Gentle pacing every 6 chunks (~144 KB) so we never saturate the Netty channel
				if ((i + 1) % 6 == 0) {
					try {
						Thread.sleep(25L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		});
	}
}
