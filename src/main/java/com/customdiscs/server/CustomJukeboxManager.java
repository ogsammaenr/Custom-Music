package com.customdiscs.server;

import com.customdiscs.component.CustomDiscData;
import com.customdiscs.config.ModConfig;
import com.customdiscs.network.ModPayloads;
import com.customdiscs.registry.ModComponents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all currently playing custom discs in jukeboxes across dimensions,
 * handles late-joining / approaching multiplayer players, and spawns note particles.
 */
public final class CustomJukeboxManager {
	private final Map<JukeboxKey, ActivePlayback> activePlaybacks = new ConcurrentHashMap<>();

	public record JukeboxKey(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private static final class ActivePlayback {
		final String trackId;
		final String title;
		final String artist;
		final int durationTicks;
		final Set<UUID> notifiedPlayers = new HashSet<>();
		int elapsedTicks = 0;

		ActivePlayback(CustomDiscData data) {
			this.trackId = data.trackId();
			this.title = data.title();
			this.artist = data.artist();
			this.durationTicks = Math.max(20, data.durationTicks());
		}
	}

	public void onDiscInserted(ServerLevel level, BlockPos pos, ItemStack discStack) {
		CustomDiscData data = discStack.get(ModComponents.CUSTOM_DISC_DATA);
		if (data == null) {
			return;
		}
		JukeboxKey key = new JukeboxKey(level.dimension(), pos.immutable());
		onDiscRemoved(level, pos);

		ActivePlayback playback = new ActivePlayback(data);
		activePlaybacks.put(key, playback);

		double syncRadius = ModConfig.get().jukeboxHearRange + 16.0;
		for (ServerPlayer player : PlayerLookup.around(level, Vec3.atCenterOf(pos), syncRadius)) {
			playback.notifiedPlayers.add(player.getUUID());
			ServerPlayNetworking.send(player, new ModPayloads.PlayCustomDiscPayload(
					pos,
					playback.trackId,
					playback.title,
					playback.artist,
					0
			));
		}
	}

	public void onDiscRemoved(ServerLevel level, BlockPos pos) {
		JukeboxKey key = new JukeboxKey(level.dimension(), pos.immutable());
		ActivePlayback removed = activePlaybacks.remove(key);
		if (removed == null) {
			return;
		}

		ModPayloads.StopCustomDiscPayload stopPayload = new ModPayloads.StopCustomDiscPayload(pos);
		for (ServerPlayer player : PlayerLookup.level(level)) {
			if (removed.notifiedPlayers.contains(player.getUUID())
					|| player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= 128.0 * 128.0) {
				ServerPlayNetworking.send(player, stopPayload);
			}
		}
	}

	public void onPlayerDisconnect(UUID playerId) {
		for (ActivePlayback playback : activePlaybacks.values()) {
			playback.notifiedPlayers.remove(playerId);
		}
	}

	public void clear() {
		activePlaybacks.clear();
	}

	public void tickServer(MinecraftServer server) {
		if (activePlaybacks.isEmpty()) {
			return;
		}

		double syncRadius = ModConfig.get().jukeboxHearRange + 16.0;
		Iterator<Map.Entry<JukeboxKey, ActivePlayback>> it = activePlaybacks.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<JukeboxKey, ActivePlayback> entry = it.next();
			JukeboxKey key = entry.getKey();
			ActivePlayback playback = entry.getValue();

			ServerLevel level = server.getLevel(key.dimension());
			if (level == null || !level.isLoaded(key.pos()) || !level.getBlockState(key.pos()).is(Blocks.JUKEBOX)) {
				it.remove();
				continue;
			}

			playback.elapsedTicks++;

			// Spawn vanilla-style music note particle every second
			if (playback.elapsedTicks % 20 == 0) {
				Vec3 notePos = Vec3.atBottomCenterOf(key.pos()).add(0.0, 1.2, 0.0);
				double noteColor = level.getRandom().nextInt(4) / 24.0;
				level.sendParticles(ParticleTypes.NOTE, notePos.x, notePos.y, notePos.z, 0, noteColor, 0.0, 0.0, 1.0);
			}

			// Sync mid-song to any player who just walked into hearing range or logged in nearby
			if (playback.elapsedTicks % 10 == 0) {
				Vec3 center = Vec3.atCenterOf(key.pos());
				for (ServerPlayer player : PlayerLookup.around(level, center, syncRadius)) {
					if (playback.notifiedPlayers.add(player.getUUID())) {
						ServerPlayNetworking.send(player, new ModPayloads.PlayCustomDiscPayload(
								key.pos(),
								playback.trackId,
								playback.title,
								playback.artist,
								playback.elapsedTicks
						));
					}
				}
			}

			if (playback.elapsedTicks >= playback.durationTicks) {
				it.remove();
				ModPayloads.StopCustomDiscPayload stopPayload = new ModPayloads.StopCustomDiscPayload(key.pos());
				for (ServerPlayer player : PlayerLookup.level(level)) {
					if (playback.notifiedPlayers.contains(player.getUUID())) {
						ServerPlayNetworking.send(player, stopPayload);
					}
				}
			}
		}
	}
}
