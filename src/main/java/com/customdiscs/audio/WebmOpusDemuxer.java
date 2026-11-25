package com.customdiscs.audio;

import io.github.jaredmdobson.OpusDecoder;
import io.github.jaredmdobson.OpusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pure-Java Matroska / WebM EBML demuxer for Opus audio tracks (e.g. YouTube itag 251/250/249).
 * Extracts Opus packets from SimpleBlock and Block elements and decodes them via Concentus
 * directly into 48 kHz mono 16-bit PCM — requiring zero external ffmpeg binaries.
 */
public final class WebmOpusDemuxer {
	// EBML Element IDs
	private static final long ID_EBML = 0x1A45DFA3L;
	private static final long ID_SEGMENT = 0x18538067L;
	private static final long ID_TRACKS = 0x1654AE6BL;
	private static final long ID_TRACK_ENTRY = 0xAEL;
	private static final long ID_TRACK_NUMBER = 0xD7L;
	private static final long ID_TRACK_TYPE = 0x83L;
	private static final long ID_CODEC_ID = 0x86L;
	private static final long ID_CODEC_PRIVATE = 0x63A2L;
	private static final long ID_AUDIO = 0xE1L;
	private static final long ID_CHANNELS = 0x9FL;
	private static final long ID_CLUSTER = 0x1F43B675L;
	private static final long ID_SIMPLE_BLOCK = 0xA3L;
	private static final long ID_BLOCK_GROUP = 0xA0L;
	private static final long ID_BLOCK = 0xA1L;

	private static final int MAX_OPUS_FRAME_SAMPLES = 5760; // 120 ms max Opus frame at 48 kHz

	private WebmOpusDemuxer() {
	}

	public static short[] decodeToMono48k(byte[] webmBytes, int maxDurationSeconds) throws IOException {
		if (webmBytes == null || webmBytes.length < 16) {
			throw new IOException("WebM audio data is too short");
		}

		TrackInfo opusTrack = new TrackInfo();
		long maxSamples = (long) (maxDurationSeconds + 5) * CdopAudioFormat.SAMPLE_RATE;
		ShortBufferBuilder monoOut = new ShortBufferBuilder(CdopAudioFormat.SAMPLE_RATE * 10);

		ParserState state = new ParserState(webmBytes, opusTrack, monoOut, maxSamples, maxDurationSeconds);
		state.parseRange(0, webmBytes.length, 0);

		if (opusTrack.trackNumber <= 0) {
			throw new IOException("No Opus audio track (A_OPUS) found in WebM stream");
		}
		if (monoOut.size() == 0) {
			throw new IOException("WebM Opus track contained no decodable audio frames");
		}
		return monoOut.toArray();
	}

	private static final class TrackInfo {
		long trackNumber = -1;
		int channels = 2;
		int preSkip = 0;
		OpusDecoder decoder;
		short[] decodeScratch;
		int remainingPreSkip = 0;

		void ensureDecoder() throws IOException {
			if (decoder != null) {
				return;
			}
			int ch = (channels == 1) ? 1 : 2;
			this.channels = ch;
			this.remainingPreSkip = Math.max(0, preSkip);
			try {
				this.decoder = new OpusDecoder(CdopAudioFormat.SAMPLE_RATE, ch);
			} catch (OpusException e) {
				throw new IOException("Failed to initialize WebM Opus decoder", e);
			}
			this.decodeScratch = new short[MAX_OPUS_FRAME_SAMPLES * ch];
		}
	}

	private static final class ParserState {
		final byte[] data;
		final TrackInfo opusTrack;
		final ShortBufferBuilder monoOut;
		final long maxSamples;
		final int maxDurationSeconds;

		ParserState(byte[] data, TrackInfo opusTrack, ShortBufferBuilder monoOut, long maxSamples, int maxDurationSeconds) {
			this.data = data;
			this.opusTrack = opusTrack;
			this.monoOut = monoOut;
			this.maxSamples = maxSamples;
			this.maxDurationSeconds = maxDurationSeconds;
		}

		void parseRange(int start, int end, int depth) throws IOException {
			if (depth > 8) {
				return;
			}
			int pos = start;
			while (pos < end) {
				Vint id = readElementId(data, pos, end);
				if (id == null) {
					break;
				}
				pos = id.nextPos;

				Vint size = readElementSize(data, pos, end);
				if (size == null) {
					break;
				}
				pos = size.nextPos;

				int elemEnd;
				if (size.unknown) {
					elemEnd = end;
				} else {
					long computedEnd = (long) pos + size.value;
					elemEnd = (int) Math.min(end, computedEnd);
				}

				long elemId = id.value;
				if (elemId == ID_SEGMENT || elemId == ID_TRACKS || elemId == ID_CLUSTER || elemId == ID_BLOCK_GROUP) {
					parseRange(pos, elemEnd, depth + 1);
				} else if (elemId == ID_TRACK_ENTRY) {
					parseTrackEntry(pos, elemEnd);
				} else if (elemId == ID_SIMPLE_BLOCK || elemId == ID_BLOCK) {
					parseBlock(pos, elemEnd);
				} else if (elemId == ID_EBML) {
					// Header verified, skip payload
				}

				if (size.unknown) {
					break;
				}
				pos = elemEnd;
			}
		}

		private void parseTrackEntry(int start, int end) throws IOException {
			long trackNum = -1;
			long trackType = -1;
			String codecId = null;
			int channels = 2;
			int preSkip = 0;

			int pos = start;
			while (pos < end) {
				Vint id = readElementId(data, pos, end);
				if (id == null) break;
				pos = id.nextPos;
				Vint size = readElementSize(data, pos, end);
				if (size == null || size.unknown) break;
				pos = size.nextPos;
				int elemEnd = (int) Math.min(end, (long) pos + size.value);

				if (id.value == ID_TRACK_NUMBER) {
					trackNum = readUnsignedInt(data, pos, elemEnd);
				} else if (id.value == ID_TRACK_TYPE) {
					trackType = readUnsignedInt(data, pos, elemEnd);
				} else if (id.value == ID_CODEC_ID) {
					codecId = new String(data, pos, elemEnd - pos, StandardCharsets.UTF_8);
				} else if (id.value == ID_AUDIO) {
					int ch = parseAudioChannels(pos, elemEnd);
					if (ch > 0) channels = ch;
				} else if (id.value == ID_CODEC_PRIVATE && elemEnd - pos >= 12) {
					// Check "OpusHead" magic
					if (data[pos] == 'O' && data[pos + 1] == 'p' && data[pos + 2] == 'u' && data[pos + 3] == 's'
							&& data[pos + 4] == 'H' && data[pos + 5] == 'e' && data[pos + 6] == 'a' && data[pos + 7] == 'd') {
						int headCh = data[pos + 9] & 0xFF;
						if (headCh == 1 || headCh == 2) {
							channels = headCh;
						}
						preSkip = (data[pos + 10] & 0xFF) | ((data[pos + 11] & 0xFF) << 8);
					}
				}
				pos = elemEnd;
			}

			if ("A_OPUS".equals(codecId) && (trackType == 2 || trackType == -1) && trackNum > 0) {
				opusTrack.trackNumber = trackNum;
				opusTrack.channels = channels;
				opusTrack.preSkip = preSkip;
			}
		}

		private int parseAudioChannels(int start, int end) {
			int pos = start;
			while (pos < end) {
				Vint id = readElementId(data, pos, end);
				if (id == null) break;
				pos = id.nextPos;
				Vint size = readElementSize(data, pos, end);
				if (size == null || size.unknown) break;
				pos = size.nextPos;
				int elemEnd = (int) Math.min(end, (long) pos + size.value);
				if (id.value == ID_CHANNELS) {
					return (int) readUnsignedInt(data, pos, elemEnd);
				}
				pos = elemEnd;
			}
			return -1;
		}

		private void parseBlock(int start, int end) throws IOException {
			if (opusTrack.trackNumber <= 0 || start >= end) {
				return;
			}
			Vint trackVint = readElementSize(data, start, end);
			if (trackVint == null || trackVint.value != opusTrack.trackNumber) {
				return;
			}
			int pos = trackVint.nextPos;
			if (pos + 3 > end) {
				return;
			}
			// 2 bytes relative timecode + 1 byte flags
			int flags = data[pos + 2] & 0xFF;
			pos += 3;

			int lacing = (flags >>> 1) & 0x03;
			if (lacing == 0) {
				// No lacing: single Opus packet
				decodeOpusPacket(pos, end - pos);
				return;
			}

			if (pos >= end) {
				return;
			}
			int numFrames = (data[pos++] & 0xFF) + 1;

			if (lacing == 2) {
				// Fixed-size lacing
				int totalPayload = end - pos;
				if (totalPayload <= 0 || totalPayload % numFrames != 0) {
					return;
				}
				int frameSize = totalPayload / numFrames;
				for (int i = 0; i < numFrames; i++) {
					decodeOpusPacket(pos, frameSize);
					pos += frameSize;
				}
			} else if (lacing == 1) {
				// Xiph lacing
				int[] sizes = new int[numFrames];
				int sumSizes = 0;
				for (int i = 0; i < numFrames - 1; i++) {
					int s = 0;
					while (pos < end) {
						int b = data[pos++] & 0xFF;
						s += b;
						if (b < 255) break;
					}
					sizes[i] = s;
					sumSizes += s;
				}
				sizes[numFrames - 1] = Math.max(0, (end - pos) - sumSizes);
				for (int s : sizes) {
					if (pos + s > end) break;
					decodeOpusPacket(pos, s);
					pos += s;
				}
			} else if (lacing == 3) {
				// EBML lacing
				int[] sizes = new int[numFrames];
				int sumSizes = 0;
				Vint first = readElementSize(data, pos, end);
				if (first == null) return;
				pos = first.nextPos;
				long prevSize = first.value;
				sizes[0] = (int) prevSize;
				sumSizes += sizes[0];

				for (int i = 1; i < numFrames - 1; i++) {
					Vint delta = readSignedVint(data, pos, end);
					if (delta == null) return;
					pos = delta.nextPos;
					prevSize += delta.value;
					if (prevSize < 0) return;
					sizes[i] = (int) prevSize;
					sumSizes += sizes[i];
				}
				sizes[numFrames - 1] = Math.max(0, (end - pos) - sumSizes);
				for (int s : sizes) {
					if (pos + s > end) break;
					decodeOpusPacket(pos, s);
					pos += s;
				}
			}
		}

		private void decodeOpusPacket(int offset, int length) throws IOException {
			if (length <= 0 || offset + length > data.length) {
				return;
			}
			opusTrack.ensureDecoder();
			int decodedPerChannel;
			try {
				decodedPerChannel = opusTrack.decoder.decode(
						data, offset, length,
						opusTrack.decodeScratch, 0, MAX_OPUS_FRAME_SAMPLES, false
				);
			} catch (OpusException e) {
				// Skip corrupt individual packet rather than failing whole stream if minor
				return;
			}

			if (decodedPerChannel <= 0) {
				return;
			}

			int ch = opusTrack.channels;
			short[] scratch = opusTrack.decodeScratch;
			int startSample = 0;
			if (opusTrack.remainingPreSkip > 0) {
				int skip = Math.min(opusTrack.remainingPreSkip, decodedPerChannel);
				opusTrack.remainingPreSkip -= skip;
				startSample = skip;
			}

			int samplesToAdd = decodedPerChannel - startSample;
			if (samplesToAdd <= 0) {
				return;
			}
			if (monoOut.size() + samplesToAdd > maxSamples) {
				throw new IOException("Audio exceeds server limit of " + maxDurationSeconds + " seconds");
			}

			if (ch == 1) {
				monoOut.append(scratch, startSample, samplesToAdd);
			} else {
				for (int i = startSample; i < decodedPerChannel; i++) {
					int base = i * ch;
					int avg = (scratch[base] + scratch[base + 1]) / 2;
					monoOut.append((short) avg);
				}
			}
		}
	}

	private record Vint(long value, int nextPos, boolean unknown) {
	}

	private static Vint readElementId(byte[] data, int pos, int end) {
		if (pos >= end) return null;
		int first = data[pos] & 0xFF;
		if (first == 0) return null;
		int len = Integer.numberOfLeadingZeros(first) - 24 + 1;
		if (len < 1 || len > 4 || pos + len > end) return null;
		long val = first;
		for (int i = 1; i < len; i++) {
			val = (val << 8) | (data[pos + i] & 0xFF);
		}
		return new Vint(val, pos + len, false);
	}

	private static Vint readElementSize(byte[] data, int pos, int end) {
		if (pos >= end) return null;
		int first = data[pos] & 0xFF;
		if (first == 0) return null;
		int len = Integer.numberOfLeadingZeros(first) - 24 + 1;
		if (len < 1 || len > 8 || pos + len > end) return null;
		int mask = (1 << (8 - len)) - 1;
		long val = first & mask;
		long allOnes = mask;
		for (int i = 1; i < len; i++) {
			val = (val << 8) | (data[pos + i] & 0xFF);
			allOnes = (allOnes << 8) | 0xFFL;
		}
		return new Vint(val, pos + len, val == allOnes);
	}

	private static Vint readSignedVint(byte[] data, int pos, int end) {
		Vint unsigned = readElementSize(data, pos, end);
		if (unsigned == null) return null;
		int len = unsigned.nextPos - pos;
		long bias = (1L << (7 * len - 1)) - 1;
		return new Vint(unsigned.value - bias, unsigned.nextPos, false);
	}

	private static long readUnsignedInt(byte[] data, int start, int end) {
		long val = 0;
		for (int i = start; i < end; i++) {
			val = (val << 8) | (data[i] & 0xFFL);
		}
		return val;
	}

	private static final class ShortBufferBuilder {
		private short[] buf;
		private int size;

		ShortBufferBuilder(int initialCapacity) {
			this.buf = new short[Math.max(1024, initialCapacity)];
		}

		void append(short s) {
			if (size >= buf.length) {
				buf = Arrays.copyOf(buf, buf.length * 2);
			}
			buf[size++] = s;
		}

		void append(short[] src, int offset, int len) {
			if (size + len > buf.length) {
				buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + len));
			}
			System.arraycopy(src, offset, buf, size, len);
			size += len;
		}

		int size() {
			return size;
		}

		short[] toArray() {
			return Arrays.copyOf(buf, size);
		}
	}
}
