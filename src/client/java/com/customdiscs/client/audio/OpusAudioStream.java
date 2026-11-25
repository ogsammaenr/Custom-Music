package com.customdiscs.client.audio;

import com.customdiscs.audio.CdopAudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streams 48 kHz 16-bit signed little-endian MONO PCM from a .cdopus track
 * directly into Minecraft's OpenAL sound engine.
 */
public final class OpusAudioStream implements AudioStream {
	private static final AudioFormat FORMAT = new AudioFormat(
			AudioFormat.Encoding.PCM_SIGNED,
			CdopAudioFormat.SAMPLE_RATE,
			16,
			1,
			2,
			CdopAudioFormat.SAMPLE_RATE,
			false
	);

	private final CdopAudioFormat.StreamingDecoder decoder;
	private boolean closed = false;

	public OpusAudioStream(byte[] cdopData, int skipTicks) throws IOException {
		this.decoder = new CdopAudioFormat.StreamingDecoder(cdopData, skipTicks);
	}

	@Override
	public AudioFormat getFormat() {
		return FORMAT;
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		if (closed || decoder.isFinished()) {
			return BufferUtils.createByteBuffer(0);
		}

		int frameBytes = CdopAudioFormat.FRAME_SIZE_SAMPLES * 2;
		int capacity = Math.max(frameBytes, ((size + frameBytes - 1) / frameBytes) * frameBytes);
		ByteBuffer buffer = BufferUtils.createByteBuffer(capacity).order(ByteOrder.LITTLE_ENDIAN);

		while (!decoder.isFinished() && buffer.remaining() >= frameBytes) {
			int written = decoder.decodeNextFrame(buffer);
			if (written <= 0) {
				break;
			}
		}

		buffer.flip();
		return buffer;
	}

	@Override
	public void close() {
		closed = true;
	}
}
