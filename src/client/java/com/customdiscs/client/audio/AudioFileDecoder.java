package com.customdiscs.client.audio;

import com.customdiscs.audio.CdopAudioFormat;
import com.customdiscs.audio.PcmResampler;
import com.customdiscs.audio.WebmOpusDemuxer;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Decodes local MP3, WAV, and OGG Vorbis files into 48 kHz mono 16-bit PCM
 * and encodes them into our compact {@link CdopAudioFormat} container.
 */
public final class AudioFileDecoder {
	public static final long MAX_RAW_INPUT_BYTES = 80L * 1024L * 1024L; // 80 MB raw file safety cap

	private AudioFileDecoder() {
	}

	public record EncodedTrack(
			String trackId,
			byte[] cdopBytes,
			int durationTicks,
			String suggestedTitle,
			String suggestedArtist
	) {
	}

	private record DecodedPcm(short[] interleavedPcm, int sampleCount, int channels, int sampleRate) {
	}

	public static boolean isSupportedExtension(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".webm");
	}

	public static EncodedTrack decodeAndEncode(Path path, int maxDurationSeconds, int maxEncodedBytes, int bitrateBps) throws IOException {
		if (!Files.isRegularFile(path)) {
			throw new IOException("File does not exist: " + path.getFileName());
		}
		long fileSize = Files.size(path);
		if (fileSize <= 0) {
			throw new IOException("Audio file is empty");
		}
		if (fileSize > MAX_RAW_INPUT_BYTES) {
			throw new IOException("Audio file is too large (max 80 MB raw input)");
		}

		String fileName = path.getFileName().toString();
		String lower = fileName.toLowerCase(Locale.ROOT);
		byte[] rawBytes = Files.readAllBytes(path);

		// Extract metadata hints from ID3v1 or filename
		String[] meta = extractTitleAndArtist(fileName, rawBytes, lower.endsWith(".mp3"));

		short[] mono48k;
		if (lower.endsWith(".webm")) {
			mono48k = WebmOpusDemuxer.decodeToMono48k(rawBytes, maxDurationSeconds);
		} else {
			DecodedPcm decoded;
			if (lower.endsWith(".mp3")) {
				decoded = decodeMp3(rawBytes, maxDurationSeconds);
			} else if (lower.endsWith(".wav")) {
				decoded = decodeWav(rawBytes, maxDurationSeconds);
			} else if (lower.endsWith(".ogg")) {
				decoded = decodeOgg(rawBytes, maxDurationSeconds);
			} else {
				throw new IOException("Unsupported format. Supported: .mp3, .wav, .ogg, .webm");
			}
			mono48k = PcmResampler.toMono48k(
					decoded.interleavedPcm(),
					decoded.sampleCount(),
					decoded.channels(),
					decoded.sampleRate()
			);
		}

		return encodeMono48kToTrack(mono48k, maxDurationSeconds, maxEncodedBytes, bitrateBps, meta[0], meta[1]);
	}

	public static EncodedTrack encodeMono48kToTrack(
			short[] mono48k,
			int maxDurationSeconds,
			int maxEncodedBytes,
			int bitrateBps,
			String suggestedTitle,
			String suggestedArtist
	) throws IOException {
		int maxSamples = maxDurationSeconds * CdopAudioFormat.SAMPLE_RATE;
		if (mono48k.length == 0) {
			throw new IOException("Decoded audio contains no samples");
		}
		if (mono48k.length > maxSamples) {
			throw new IOException("Audio exceeds server limit of " + maxDurationSeconds + " seconds");
		}

		// Adapt bitrate downward if necessary to fit server's maxEncodedBytes
		int effectiveBitrate = bitrateBps;
		double durationSec = (double) mono48k.length / CdopAudioFormat.SAMPLE_RATE;
		if (durationSec > 1.0) {
			int maxAllowedBitrate = (int) (((maxEncodedBytes - 1024) * 8.0) / durationSec * 0.90);
			if (maxAllowedBitrate < effectiveBitrate) {
				effectiveBitrate = Math.max(16_000, maxAllowedBitrate);
			}
		}

		byte[] cdopBytes = CdopAudioFormat.encodeMono48kPcm(mono48k, effectiveBitrate);
		if (cdopBytes.length > maxEncodedBytes) {
			throw new IOException("Encoded audio (" + (cdopBytes.length / 1024) + " KB) exceeds server limit (" + (maxEncodedBytes / 1024) + " KB)");
		}

		CdopAudioFormat.Header header = CdopAudioFormat.validate(cdopBytes, maxDurationSeconds);
		String trackId = CdopAudioFormat.sha256Hex(cdopBytes);

		return new EncodedTrack(trackId, cdopBytes, header.durationTicks(), suggestedTitle, suggestedArtist);
	}

	private static DecodedPcm decodeMp3(byte[] rawBytes, int maxDurationSeconds) throws IOException {
		Bitstream bitstream = new Bitstream(new BufferedInputStream(new ByteArrayInputStream(rawBytes)));
		Decoder decoder = new Decoder();
		ShortArrayBuilder pcmBuilder = new ShortArrayBuilder(48_000 * 10);
		int sampleRate = 0;
		int channels = 0;
		long maxInterleavedSamples = 0;

		try {
			Header frameHeader;
			while ((frameHeader = bitstream.readFrame()) != null) {
				SampleBuffer output = (SampleBuffer) decoder.decodeFrame(frameHeader, bitstream);
				if (output != null) {
					if (sampleRate == 0) {
						sampleRate = output.getSampleFrequency();
						channels = output.getChannelCount();
						// Allow slight overshoot before exact post-resample check
						maxInterleavedSamples = (long) (maxDurationSeconds + 5) * sampleRate * channels;
					}
					int len = output.getBufferLength();
					if (pcmBuilder.size() + len > maxInterleavedSamples) {
						throw new IOException("Audio exceeds server limit of " + maxDurationSeconds + " seconds");
					}
					pcmBuilder.append(output.getBuffer(), 0, len);
				}
				bitstream.closeFrame();
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to decode MP3: " + e.getMessage(), e);
		} finally {
			try {
				bitstream.close();
			} catch (Exception ignored) {
			}
		}

		if (sampleRate <= 0 || channels <= 0 || pcmBuilder.size() == 0) {
			throw new IOException("No valid MP3 audio frames found");
		}
		return new DecodedPcm(pcmBuilder.buffer(), pcmBuilder.size(), channels, sampleRate);
	}

	private static DecodedPcm decodeWav(byte[] rawBytes, int maxDurationSeconds) throws IOException {
		try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(rawBytes)))) {
			AudioFormat baseFormat = sourceStream.getFormat();
			int channels = baseFormat.getChannels();
			float sampleRateF = baseFormat.getSampleRate();
			if (channels <= 0 || sampleRateF <= 0) {
				throw new IOException("Invalid WAV format");
			}
			int sampleRate = Math.round(sampleRateF);

			AudioFormat pcm16Format = new AudioFormat(
					AudioFormat.Encoding.PCM_SIGNED,
					sampleRateF,
					16,
					channels,
					channels * 2,
					sampleRateF,
					false
			);

			try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcm16Format, sourceStream)) {
				long maxInterleavedSamples = (long) (maxDurationSeconds + 5) * sampleRate * channels;
				ShortArrayBuilder pcmBuilder = new ShortArrayBuilder(48_000 * 10);
				byte[] readBuf = new byte[16_384];
				int bytesRead;
				while ((bytesRead = pcmStream.read(readBuf)) != -1) {
					int shortsCount = bytesRead / 2;
					if (pcmBuilder.size() + shortsCount > maxInterleavedSamples) {
						throw new IOException("Audio exceeds server limit of " + maxDurationSeconds + " seconds");
					}
					for (int i = 0; i + 1 < bytesRead; i += 2) {
						short s = (short) ((readBuf[i] & 0xFF) | ((readBuf[i + 1] & 0xFF) << 8));
						pcmBuilder.append(s);
					}
				}
				return new DecodedPcm(pcmBuilder.buffer(), pcmBuilder.size(), channels, sampleRate);
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to decode WAV: " + e.getMessage(), e);
		}
	}

	private static DecodedPcm decodeOgg(byte[] rawBytes, int maxDurationSeconds) throws IOException {
		ByteBuffer nativeBuf = MemoryUtil.memAlloc(rawBytes.length);
		long handle = 0L;
		try {
			nativeBuf.put(rawBytes).flip();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer errorBuf = stack.mallocInt(1);
				handle = STBVorbis.stb_vorbis_open_memory(nativeBuf, errorBuf, null);
				if (handle == 0L) {
					throw new IOException("Invalid OGG Vorbis file (STB error " + errorBuf.get(0) + ")");
				}

				STBVorbisInfo info = STBVorbisInfo.malloc(stack);
				STBVorbis.stb_vorbis_get_info(handle, info);
				int channels = info.channels();
				int sampleRate = info.sample_rate();
				if (channels <= 0 || sampleRate <= 0) {
					throw new IOException("Invalid OGG audio stream info");
				}

				long maxInterleavedSamples = (long) (maxDurationSeconds + 5) * sampleRate * channels;
				ShortArrayBuilder pcmBuilder = new ShortArrayBuilder(48_000 * 10);

				int chunkShorts = 8192 * channels;
				ShortBuffer chunkBuf = MemoryUtil.memAllocShort(chunkShorts);
				short[] tempArray = new short[chunkShorts];
				try {
					while (true) {
						chunkBuf.clear();
						int samplesPerChannel = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, chunkBuf);
						if (samplesPerChannel <= 0) {
							break;
						}
						int totalShorts = samplesPerChannel * channels;
						if (pcmBuilder.size() + totalShorts > maxInterleavedSamples) {
							throw new IOException("Audio exceeds server limit of " + maxDurationSeconds + " seconds");
						}
						chunkBuf.position(0);
						chunkBuf.get(tempArray, 0, totalShorts);
						pcmBuilder.append(tempArray, 0, totalShorts);
					}
				} finally {
					MemoryUtil.memFree(chunkBuf);
				}

				return new DecodedPcm(pcmBuilder.buffer(), pcmBuilder.size(), channels, sampleRate);
			}
		} finally {
			if (handle != 0L) {
				STBVorbis.stb_vorbis_close(handle);
			}
			MemoryUtil.memFree(nativeBuf);
		}
	}

	private static String[] extractTitleAndArtist(String fileName, byte[] rawBytes, boolean isMp3) {
		String baseName = fileName;
		int dot = baseName.lastIndexOf('.');
		if (dot > 0) {
			baseName = baseName.substring(0, dot);
		}

		String title = baseName.trim();
		String artist = "";

		// Parse "Artist - Title" filename convention
		int dashIdx = baseName.indexOf(" - ");
		if (dashIdx > 0 && dashIdx + 3 < baseName.length()) {
			artist = baseName.substring(0, dashIdx).trim();
			title = baseName.substring(dashIdx + 3).trim();
		}

		// Try ID3v1 tag at end of MP3 file
		if (isMp3 && rawBytes.length >= 128) {
			int tagOffset = rawBytes.length - 128;
			if (rawBytes[tagOffset] == 'T' && rawBytes[tagOffset + 1] == 'A' && rawBytes[tagOffset + 2] == 'G') {
				String id3Title = readNullTerminatedLatin1(rawBytes, tagOffset + 3, 30);
				String id3Artist = readNullTerminatedLatin1(rawBytes, tagOffset + 33, 30);
				if (!id3Title.isEmpty()) {
					title = id3Title;
				}
				if (!id3Artist.isEmpty()) {
					artist = id3Artist;
				}
			}
		}

		if (title.length() > 64) {
			title = title.substring(0, 64);
		}
		if (artist.length() > 64) {
			artist = artist.substring(0, 64);
		}
		return new String[]{title.isEmpty() ? "Untitled Track" : title, artist};
	}

	private static String readNullTerminatedLatin1(byte[] bytes, int offset, int maxLen) {
		int len = 0;
		while (len < maxLen && bytes[offset + len] != 0) {
			len++;
		}
		return new String(bytes, offset, len, StandardCharsets.ISO_8859_1).trim();
	}

	private static final class ShortArrayBuilder {
		private short[] buf;
		private int size;

		ShortArrayBuilder(int initialCapacity) {
			this.buf = new short[Math.max(1024, initialCapacity)];
		}

		void append(short value) {
			if (size >= buf.length) {
				buf = Arrays.copyOf(buf, buf.length * 2);
			}
			buf[size++] = value;
		}

		void append(short[] src, int offset, int length) {
			if (size + length > buf.length) {
				int nextCap = Math.max(buf.length * 2, size + length);
				buf = Arrays.copyOf(buf, nextCap);
			}
			System.arraycopy(src, offset, buf, size, length);
			size += length;
		}

		int size() {
			return size;
		}

		short[] buffer() {
			return buf;
		}
	}
}
