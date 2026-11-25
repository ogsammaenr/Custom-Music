package com.customdiscs.audio;

import io.github.jaredmdobson.OpusApplication;
import io.github.jaredmdobson.OpusDecoder;
import io.github.jaredmdobson.OpusEncoder;
import io.github.jaredmdobson.OpusException;
import io.github.jaredmdobson.OpusSignal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Compact, self-validating framed Opus container (.cdopus).
 * Always stores 48 kHz mono audio in 20 ms frames (960 samples/frame) so OpenAL
 * can play it with full 3D spatial attenuation and frame-accurate seeking.
 */
public final class CdopAudioFormat {
	public static final byte[] MAGIC = {'C', 'D', 'O', 'P'};
	public static final byte VERSION = 1;
	public static final int SAMPLE_RATE = 48_000;
	public static final int CHANNELS = 1;
	public static final int FRAME_SIZE_SAMPLES = 960; // 20 ms at 48 kHz
	public static final int HEADER_SIZE = 24;
	public static final int MAX_OPUS_PACKET_BYTES = 1275;

	private CdopAudioFormat() {
	}

	public record Header(int sampleRate, int channels, int frameSize, long totalSamples, int frameCount) {
		public int durationTicks() {
			return Math.max(20, (int) Math.ceil((double) totalSamples * 20.0 / sampleRate));
		}

		public double durationSeconds() {
			return (double) totalSamples / sampleRate;
		}
	}

	/**
	 * Encodes 48 kHz mono 16-bit PCM samples into the .cdopus binary container.
	 */
	public static byte[] encodeMono48kPcm(short[] monoPcm, int bitrateBps) throws IOException {
		if (monoPcm == null || monoPcm.length == 0) {
			throw new IOException("Audio contains no PCM samples");
		}
		int clampedBitrate = Math.max(16_000, Math.min(128_000, bitrateBps));
		int frameCount = (monoPcm.length + FRAME_SIZE_SAMPLES - 1) / FRAME_SIZE_SAMPLES;

		try {
			OpusEncoder encoder = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
			encoder.setBitrate(clampedBitrate);
			encoder.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
			encoder.setComplexity(5);

			ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(1024, monoPcm.length / 8));
			DataOutputStream out = new DataOutputStream(baos);

			// Header (24 bytes)
			out.write(MAGIC);
			out.writeByte(VERSION);
			out.writeInt(SAMPLE_RATE);
			out.writeByte(CHANNELS);
			out.writeShort(FRAME_SIZE_SAMPLES);
			out.writeLong(monoPcm.length);
			out.writeInt(frameCount);

			short[] frameBuf = new short[FRAME_SIZE_SAMPLES];
			byte[] packetBuf = new byte[MAX_OPUS_PACKET_BYTES];

			for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
				int offset = frameIndex * FRAME_SIZE_SAMPLES;
				int copyLen = Math.min(FRAME_SIZE_SAMPLES, monoPcm.length - offset);
				System.arraycopy(monoPcm, offset, frameBuf, 0, copyLen);
				if (copyLen < FRAME_SIZE_SAMPLES) {
					java.util.Arrays.fill(frameBuf, copyLen, FRAME_SIZE_SAMPLES, (short) 0);
				}

				int encodedLen = encoder.encode(frameBuf, 0, FRAME_SIZE_SAMPLES, packetBuf, 0, packetBuf.length);
				if (encodedLen <= 0 || encodedLen > MAX_OPUS_PACKET_BYTES) {
					throw new IOException("Invalid Opus packet size produced: " + encodedLen);
				}
				out.writeShort(encodedLen);
				out.write(packetBuf, 0, encodedLen);
			}

			out.flush();
			return baos.toByteArray();
		} catch (OpusException e) {
			throw new IOException("Failed to encode Opus audio", e);
		}
	}

	/**
	 * Strictly validates the header and every frame boundary of a .cdopus payload.
	 * Safe to run on untrusted server uploads.
	 */
	public static Header validate(byte[] data, int maxDurationSeconds) throws IOException {
		if (data == null || data.length < HEADER_SIZE) {
			throw new IOException("Audio data is too short");
		}
		if (data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
			throw new IOException("Invalid CDOP magic header");
		}
		if (data[4] != VERSION) {
			throw new IOException("Unsupported CDOP version: " + data[4]);
		}

		ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
		buf.position(5);
		int sampleRate = buf.getInt();
		int channels = buf.get() & 0xFF;
		int frameSize = buf.getShort() & 0xFFFF;
		long totalSamples = buf.getLong();
		int frameCount = buf.getInt();

		if (sampleRate != SAMPLE_RATE) {
			throw new IOException("Unsupported sample rate: " + sampleRate);
		}
		if (channels != CHANNELS) {
			throw new IOException("Unsupported channel count: " + channels);
		}
		if (frameSize != FRAME_SIZE_SAMPLES) {
			throw new IOException("Unsupported frame size: " + frameSize);
		}
		if (totalSamples <= 0 || frameCount <= 0) {
			throw new IOException("Audio contains no frames");
		}
		long maxSamples = (long) maxDurationSeconds * SAMPLE_RATE;
		if (totalSamples > maxSamples) {
			throw new IOException("Audio exceeds max duration of " + maxDurationSeconds + " seconds");
		}
		long expectedFrames = (totalSamples + frameSize - 1) / frameSize;
		if (frameCount != expectedFrames) {
			throw new IOException("Frame count mismatch: expected " + expectedFrames + ", got " + frameCount);
		}

		int pos = HEADER_SIZE;
		for (int i = 0; i < frameCount; i++) {
			if (pos + 2 > data.length) {
				throw new IOException("Truncated frame header at frame " + i);
			}
			int packetLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
			pos += 2;
			if (packetLen <= 0 || packetLen > MAX_OPUS_PACKET_BYTES) {
				throw new IOException("Invalid Opus packet length " + packetLen + " at frame " + i);
			}
			if (pos + packetLen > data.length) {
				throw new IOException("Truncated frame payload at frame " + i);
			}
			pos += packetLen;
		}

		if (pos != data.length) {
			throw new IOException("Unexpected trailing bytes in CDOP file: " + (data.length - pos));
		}

		return new Header(sampleRate, channels, frameSize, totalSamples, frameCount);
	}

	public static String sha256Hex(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	public static boolean isValidTrackId(String trackId) {
		if (trackId == null || trackId.length() != 64) {
			return false;
		}
		for (int i = 0; i < 64; i++) {
			char c = trackId.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Frame-by-frame streaming Opus decoder for .cdopus data.
	 * Supports skipping frames for mid-song multiplayer synchronization.
	 */
	public static final class StreamingDecoder {
		private final byte[] data;
		private final Header header;
		private final OpusDecoder decoder;
		private final short[] pcmScratch = new short[FRAME_SIZE_SAMPLES];
		private int offset = HEADER_SIZE;
		private int currentFrame = 0;
		private long emittedSamples = 0;

		public StreamingDecoder(byte[] data, int skipTicks) throws IOException {
			this.header = validate(data, 3600);
			this.data = data;
			try {
				this.decoder = new OpusDecoder(SAMPLE_RATE, CHANNELS);
			} catch (OpusException e) {
				throw new IOException("Failed to initialize Opus decoder", e);
			}

			if (skipTicks > 0) {
				// 1 tick = 50 ms = 2.5 Opus 20 ms frames
				int targetFrames = (int) Math.round(skipTicks * 2.5);
				int framesToSkip = Math.min(targetFrames, header.frameCount());
				for (int i = 0; i < framesToSkip; i++) {
					int packetLen = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
					offset += 2 + packetLen;
					currentFrame++;
					emittedSamples = Math.min(header.totalSamples(), emittedSamples + FRAME_SIZE_SAMPLES);
				}
			}
		}

		public Header header() {
			return header;
		}

		public boolean isFinished() {
			return currentFrame >= header.frameCount() || emittedSamples >= header.totalSamples();
		}

		/**
		 * Decodes the next Opus frame into little-endian 16-bit mono PCM bytes.
		 * Returns the number of PCM bytes written to {@code target}, or 0 if EOF.
		 */
		public int decodeNextFrame(ByteBuffer target) throws IOException {
			if (isFinished()) {
				return 0;
			}
			int packetLen = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
			offset += 2;
			int packetStart = offset;
			offset += packetLen;
			currentFrame++;

			int decodedSamples;
			try {
				decodedSamples = decoder.decode(data, packetStart, packetLen, pcmScratch, 0, FRAME_SIZE_SAMPLES, false);
			} catch (OpusException e) {
				throw new IOException("Failed to decode Opus frame " + (currentFrame - 1), e);
			}

			long remainingSamples = header.totalSamples() - emittedSamples;
			int samplesToWrite = (int) Math.min(decodedSamples, remainingSamples);
			emittedSamples += samplesToWrite;

			for (int i = 0; i < samplesToWrite; i++) {
				short s = pcmScratch[i];
				target.put((byte) (s & 0xFF));
				target.put((byte) ((s >>> 8) & 0xFF));
			}
			return samplesToWrite * 2;
		}
	}
}
