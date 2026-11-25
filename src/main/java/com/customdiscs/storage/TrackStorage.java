package com.customdiscs.storage;

import com.customdiscs.audio.CdopAudioFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Content-addressed server-side storage for uploaded .cdopus tracks.
 * Stored under {@code <world>/data/customdiscs/<sha256>.cdopus}.
 */
public final class TrackStorage {
	private static final Logger LOGGER = LoggerFactory.getLogger("customdiscs-storage");
	private final Path directory;

	public TrackStorage(Path worldRoot) throws IOException {
		this.directory = worldRoot.resolve("data").resolve("customdiscs").toAbsolutePath().normalize();
		Files.createDirectories(this.directory);
	}

	public Path resolveTrackPath(String trackId) throws IOException {
		if (!CdopAudioFormat.isValidTrackId(trackId)) {
			throw new IOException("Invalid trackId format");
		}
		Path resolved = directory.resolve(trackId + ".cdopus").toAbsolutePath().normalize();
		if (!resolved.startsWith(directory)) {
			throw new IOException("Path traversal blocked");
		}
		return resolved;
	}

	public boolean exists(String trackId) {
		try {
			return Files.isRegularFile(resolveTrackPath(trackId));
		} catch (IOException e) {
			return false;
		}
	}

	public long countTracks() {
		try (Stream<Path> stream = Files.list(directory)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".cdopus")).count();
		} catch (IOException e) {
			return 0;
		}
	}

	public synchronized CdopAudioFormat.Header saveValidated(String expectedTrackId, byte[] data, int maxDurationSeconds, int maxTracks) throws IOException {
		CdopAudioFormat.Header header = CdopAudioFormat.validate(data, maxDurationSeconds);
		String actualHash = CdopAudioFormat.sha256Hex(data);
		if (!actualHash.equals(expectedTrackId)) {
			throw new IOException("SHA-256 mismatch: expected " + expectedTrackId + ", got " + actualHash);
		}

		Path target = resolveTrackPath(expectedTrackId);
		if (Files.isRegularFile(target)) {
			return header;
		}

		if (countTracks() >= maxTracks) {
			throw new IOException("Server track storage limit reached (" + maxTracks + " tracks)");
		}

		Path tmp = Files.createTempFile(directory, expectedTrackId, ".tmp");
		try {
			Files.write(tmp, data);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException fallbackEx) {
				Files.deleteIfExists(tmp);
				throw fallbackEx;
			}
		}
		LOGGER.info("Stored custom disc track {} ({} bytes, {} ticks)", expectedTrackId, data.length, header.durationTicks());
		return header;
	}

	public Optional<byte[]> load(String trackId) {
		try {
			Path path = resolveTrackPath(trackId);
			if (!Files.isRegularFile(path)) {
				return Optional.empty();
			}
			return Optional.of(Files.readAllBytes(path));
		} catch (IOException e) {
			LOGGER.warn("Failed to read track {}: {}", trackId, e.getMessage());
			return Optional.empty();
		}
	}
}
