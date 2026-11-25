package com.customdiscs.client.audio;

import com.customdiscs.CustomDiscsMod;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 3D-positioned streaming SoundInstance for custom jukebox discs.
 * Uses FabricSoundInstance#getAudioStream to feed OpenAL directly without resource pack reloads.
 */
public final class CustomDiscSoundInstance extends AbstractSoundInstance {
	private final byte[] cdopData;
	private final int skipTicks;

	public CustomDiscSoundInstance(BlockPos pos, String trackId, byte[] cdopData, int skipTicks) {
		super(CustomDiscsMod.id("custom_disc_" + trackId.substring(0, 12)), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
		this.cdopData = cdopData;
		this.skipTicks = Math.max(0, skipTicks);
		this.x = pos.getX() + 0.5;
		this.y = pos.getY() + 0.5;
		this.z = pos.getZ() + 0.5;
		this.volume = 4.0F;
		this.pitch = 1.0F;
		this.looping = false;
		this.delay = 0;
		this.attenuation = Attenuation.LINEAR;
		this.relative = false;
	}

	@Override
	public WeighedSoundEvents getOrResolve(SoundManager soundManager) {
		this.sound = new Sound(
				this.identifier,
				ConstantFloat.of(1.0F),
				ConstantFloat.of(1.0F),
				1,
				Sound.Type.FILE,
				true,  // stream = true so SoundEngine invokes getAudioStream
				false,
				16     // base attenuation distance (16 * volume 4.0 = 64 blocks, matching vanilla jukebox)
		);
		this.soundEvent = new WeighedSoundEvents(this.identifier, null);
		return this.soundEvent;
	}

	@Override
	public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary soundBuffers, Identifier id, boolean looping) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return new OpusAudioStream(this.cdopData, this.skipTicks);
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		});
	}
}
