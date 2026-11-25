package com.customdiscs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("customdiscs-config");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public int maxFileSizeBytes = 6 * 1024 * 1024; // 6 MB encoded Opus (~12 min at 64 kbps)
	public int maxDurationSeconds = 600;           // 10 minutes
	public int uploadCooldownSeconds = 5;
	public int opusBitrateBps = 64_000;            // 64 kbps mono music quality
	public boolean allowAllPlayersToUpload = true;
	public int maxStoredTracks = 500;
	public double jukeboxHearRange = 64.0;

	private static volatile ModConfig instance = new ModConfig();

	public static ModConfig get() {
		return instance;
	}

	public static void load(Path configDir) {
		Path file = configDir.resolve("customdiscs.json");
		ModConfig loaded = new ModConfig();
		if (Files.exists(file)) {
			try {
				String json = Files.readString(file);
				ModConfig parsed = GSON.fromJson(json, ModConfig.class);
				if (parsed != null) {
					loaded = parsed;
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to read {}, using defaults: {}", file, e.getMessage());
			}
		}

		// Clamp sanity bounds
		loaded.maxFileSizeBytes = Math.max(64 * 1024, Math.min(32 * 1024 * 1024, loaded.maxFileSizeBytes));
		loaded.maxDurationSeconds = Math.max(10, Math.min(1800, loaded.maxDurationSeconds));
		loaded.uploadCooldownSeconds = Math.max(0, Math.min(300, loaded.uploadCooldownSeconds));
		loaded.opusBitrateBps = Math.max(16_000, Math.min(128_000, loaded.opusBitrateBps));
		loaded.maxStoredTracks = Math.max(10, Math.min(10_000, loaded.maxStoredTracks));
		loaded.jukeboxHearRange = Math.max(16.0, Math.min(128.0, loaded.jukeboxHearRange));

		instance = loaded;
		try {
			Files.createDirectories(configDir);
			Files.writeString(file, GSON.toJson(loaded));
		} catch (IOException e) {
			LOGGER.warn("Failed to write config file {}: {}", file, e.getMessage());
		}
	}
}
