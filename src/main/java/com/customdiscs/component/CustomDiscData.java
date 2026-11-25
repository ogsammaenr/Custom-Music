package com.customdiscs.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record CustomDiscData(
		String trackId,
		String title,
		String artist,
		int durationTicks,
		int color
) {
	public static final int DEFAULT_COLOR = 0x38D2F5;

	public static final Codec<CustomDiscData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("track_id").forGetter(CustomDiscData::trackId),
			Codec.STRING.fieldOf("title").forGetter(CustomDiscData::title),
			Codec.STRING.optionalFieldOf("artist", "").forGetter(CustomDiscData::artist),
			Codec.INT.fieldOf("duration_ticks").forGetter(CustomDiscData::durationTicks),
			Codec.INT.optionalFieldOf("color", DEFAULT_COLOR).forGetter(CustomDiscData::color)
	).apply(instance, CustomDiscData::new));

	public static final StreamCodec<ByteBuf, CustomDiscData> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, CustomDiscData::trackId,
			ByteBufCodecs.STRING_UTF8, CustomDiscData::title,
			ByteBufCodecs.STRING_UTF8, CustomDiscData::artist,
			ByteBufCodecs.VAR_INT, CustomDiscData::durationTicks,
			ByteBufCodecs.INT, CustomDiscData::color,
			CustomDiscData::new
	);

	public String formattedDisplayName() {
		if (artist != null && !artist.isBlank()) {
			return artist + " - " + title;
		}
		return title;
	}

	public String formattedDuration() {
		int totalSeconds = Math.max(0, (int) Math.round(durationTicks / 20.0));
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return String.format("%d:%02d", minutes, seconds);
	}
}
