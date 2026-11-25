package com.customdiscs.client.audio;

import com.customdiscs.audio.CdopAudioFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Local client disk cache for downloaded or locally encoded .cdopus tracks.
 * Stored in {@code <gameDir>/customdiscs_cache/<sha256>.cdopus}.
 */
public final class ClientAudioCache {
	private static final Logger LOGGER = LoggerFactory.getLogger("customdiscs-cache");
	private final Path cacheDir;

	public ClientAudioCache(Path gameDir) {
		this.cacheDir = gameDir.resolve("customdiscs_cache").toAbsolutePath().normalize();
		try {
			Files.createDirectories(cacheDir);
		} catch (IOException e) {
			LOGGER.warn("Failed to create cache dir {}: {}", cacheDir, e.getMessage());
		}
	}

	public Optional<byte[]> get(String trackId) {
		if (!CdopAudioFormat.isValidTrackId(trackId)) {
			return Optional.empty();
		}
		Path file = cacheDir.resolve(trackId + ".cdopus").toAbsolutePath().normalize();
		if (!file.startsWith(cacheDir) || !Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			byte[] data = Files.readAllBytes(file);
			if (!CdopAudioFormat.sha256Hex(data).equals(trackId)) {
				Files.deleteIfExists(file);
				return Optional.empty();
			}
			return Optional.of(data);
		} catch (IOException e) {
			LOGGER.warn("Failed to read cached track {}: {}", trackId, e.getMessage());
			return Optional.empty();
		}
	}

	public void put(String trackId, byte[] data) {
		if (!CdopAudioFormat.isValidTrackId(trackId)) {
			return;
		}
		if (!CdopAudioFormat.sha256Hex(data).equals(trackId)) {
			return;
		}
		try {
			Files.createDirectories(cacheDir);
			Path target = cacheDir.resolve(trackId + ".cdopus").toAbsolutePath().normalize();
			if (!target.startsWith(cacheDir)) {
				return;
			}
			Path tmp = Files.createTempFile(cacheDir, trackId, ".tmp");
			Files.write(tmp, data);
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to cache track {}: {}", trackId, e.getMessage());
		}
	}
}
