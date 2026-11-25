package com.customdiscs.audio;

import io.github.jaredmdobson.OpusApplication;
import io.github.jaredmdobson.OpusEncoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebmOpusDemuxerTest {

	@Test
	void demuxSyntheticStereoWebmOpusToMono48k() throws Exception {
		// Encode 10 frames (200 ms) of 48 kHz stereo sine wave with Concentus
		int frames = 10;
		int samplesPerFrame = 960;
		OpusEncoder encoder = new OpusEncoder(48_000, 2, OpusApplication.OPUS_APPLICATION_AUDIO);
		encoder.setBitrate(96_000);

		List<byte[]> opusPackets = new ArrayList<>();
		byte[] packetBuf = new byte[1275];
		for (int f = 0; f < frames; f++) {
			short[] stereoPcm = new short[samplesPerFrame * 2];
			for (int i = 0; i < samplesPerFrame; i++) {
				int idx = f * samplesPerFrame + i;
				short s = (short) (Math.sin(2.0 * Math.PI * 440.0 * idx / 48_000.0) * 14000);
				stereoPcm[i * 2] = s;
				stereoPcm[i * 2 + 1] = s;
			}
			int len = encoder.encode(stereoPcm, 0, samplesPerFrame, packetBuf, 0, packetBuf.length);
			opusPackets.add(java.util.Arrays.copyOf(packetBuf, len));
		}

		byte[] webm = buildMinimalWebmOpus(opusPackets, 2, 0);
		short[] mono48k = WebmOpusDemuxer.decodeToMono48k(webm, 60);

		assertEquals(frames * samplesPerFrame, mono48k.length);

		// Verify we can re-encode into .cdopus and validate cleanly
		byte[] cdop = CdopAudioFormat.encodeMono48kPcm(mono48k, 64_000);
		CdopAudioFormat.Header header = CdopAudioFormat.validate(cdop, 60);
		assertEquals(48_000, header.sampleRate());
		assertEquals(1, header.channels());
		assertEquals(frames * samplesPerFrame, header.totalSamples());
	}

	private static byte[] buildMinimalWebmOpus(List<byte[]> opusPackets, int channels, int preSkip) {
		// OpusHead (19 bytes)
		byte[] opusHead = new byte[19];
		System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, opusHead, 0, 8);
		opusHead[8] = 1; // version
		opusHead[9] = (byte) channels;
		opusHead[10] = (byte) (preSkip & 0xFF);
		opusHead[11] = (byte) ((preSkip >>> 8) & 0xFF);

		byte[] trackEntry = concat(
				ebmlElement(0xD7, new byte[]{1}),                                     // TrackNumber = 1
				ebmlElement(0x83, new byte[]{2}),                                     // TrackType = 2 (audio)
				ebmlElement(0x86, "A_OPUS".getBytes(StandardCharsets.US_ASCII)),      // CodecID
				ebmlElement(0x63A2, opusHead),                                        // CodecPrivate
				ebmlElement(0xE1, ebmlElement(0x9F, new byte[]{(byte) channels}))     // Audio -> Channels
		);
		byte[] tracks = ebmlElement(0x1654AE6B, ebmlElement(0xAE, trackEntry));

		ByteArrayOutputStream clusterBody = new ByteArrayOutputStream();
		for (int i = 0; i < opusPackets.size(); i++) {
			byte[] pkt = opusPackets.get(i);
			ByteArrayOutputStream block = new ByteArrayOutputStream();
			block.write(0x81); // Track number 1 as VINT
			int tc = i * 20;
			block.write((tc >>> 8) & 0xFF);
			block.write(tc & 0xFF);
			block.write(0x80); // Keyframe, no lacing
			block.writeBytes(pkt);
			clusterBody.writeBytes(ebmlElement(0xA3, block.toByteArray()));
		}
		byte[] cluster = ebmlElement(0x1F43B675, clusterBody.toByteArray());
		byte[] ebmlHeader = ebmlElement(0x1A45DFA3, new byte[0]);
		byte[] segment = ebmlElement(0x18538067, concat(tracks, cluster));
		return concat(ebmlHeader, segment);
	}

	private static byte[] ebmlElement(int id, byte[] payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (id > 0xFFFFFF) {
			out.write((id >>> 24) & 0xFF);
			out.write((id >>> 16) & 0xFF);
			out.write((id >>> 8) & 0xFF);
			out.write(id & 0xFF);
		} else if (id > 0xFFFF) {
			out.write((id >>> 16) & 0xFF);
			out.write((id >>> 8) & 0xFF);
			out.write(id & 0xFF);
		} else if (id > 0xFF) {
			out.write((id >>> 8) & 0xFF);
			out.write(id & 0xFF);
		} else {
			out.write(id & 0xFF);
		}

		// Encode payload length as 4-byte VINT (0x10XXXXXX)
		int len = payload.length;
		out.write(0x10 | ((len >>> 24) & 0x0F));
		out.write((len >>> 16) & 0xFF);
		out.write((len >>> 8) & 0xFF);
		out.write(len & 0xFF);
		out.writeBytes(payload);
		return out.toByteArray();
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.writeBytes(p);
		}
		return out.toByteArray();
	}
}
