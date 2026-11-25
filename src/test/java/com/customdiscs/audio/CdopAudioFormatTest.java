package com.customdiscs.audio;

import com.customdiscs.storage.TrackStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CdopAudioFormatTest {

	@Test
	void encodeValidateAndDecodeRoundTrip() throws IOException {
		// Generate 1 second of 440 Hz stereo 44.1 kHz sine wave
		int srcRate = 44_100;
		short[] stereo = new short[srcRate * 2];
		for (int i = 0; i < srcRate; i++) {
			short sample = (short) (Math.sin(2.0 * Math.PI * 440.0 * i / srcRate) * 16000);
			stereo[i * 2] = sample;
			stereo[i * 2 + 1] = sample;
		}

		short[] mono48k = PcmResampler.toMono48k(stereo, stereo.length, 2, srcRate);
		assertEquals(48_000, mono48k.length, 10);

		byte[] cdop = CdopAudioFormat.encodeMono48kPcm(mono48k, 64_000);
		assertTrue(cdop.length > CdopAudioFormat.HEADER_SIZE);
		assertTrue(cdop.length < 20_000, "1s of 64kbps Opus should be compact");

		CdopAudioFormat.Header header = CdopAudioFormat.validate(cdop, 600);
		assertEquals(48_000, header.sampleRate());
		assertEquals(1, header.channels());
		assertEquals(mono48k.length, header.totalSamples());
		assertEquals(20, header.durationTicks());

		String hash = CdopAudioFormat.sha256Hex(cdop);
		assertTrue(CdopAudioFormat.isValidTrackId(hash));

		CdopAudioFormat.StreamingDecoder decoder = new CdopAudioFormat.StreamingDecoder(cdop, 0);
		ByteBuffer out = ByteBuffer.allocate(CdopAudioFormat.FRAME_SIZE_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN);
		long totalBytes = 0;
		while (!decoder.isFinished()) {
			out.clear();
			totalBytes += decoder.decodeNextFrame(out);
		}
		assertEquals(mono48k.length * 2L, totalBytes);
	}

	@Test
	void rejectCorruptedAndOversizedTrack() throws IOException {
		short[] pcm = new short[48_000];
		byte[] cdop = CdopAudioFormat.encodeMono48kPcm(pcm, 32_000);

		// Corrupt magic
		byte[] badMagic = cdop.clone();
		badMagic[0] = 'X';
		assertThrows(IOException.class, () -> CdopAudioFormat.validate(badMagic, 60));

		// Truncate payload
		byte[] truncated = java.util.Arrays.copyOf(cdop, cdop.length - 5);
		assertThrows(IOException.class, () -> CdopAudioFormat.validate(truncated, 60));

		// Exceed duration limit
		assertThrows(IOException.class, () -> CdopAudioFormat.validate(cdop, 0));
	}

	@Test
	void trackStorageSavesDeduplicatesAndBlocksTraversal(@TempDir Path tempDir) throws IOException {
		TrackStorage storage = new TrackStorage(tempDir);
		short[] pcm = new short[960 * 5];
		byte[] cdop = CdopAudioFormat.encodeMono48kPcm(pcm, 48_000);
		String trackId = CdopAudioFormat.sha256Hex(cdop);

		assertFalse(storage.exists(trackId));
		storage.saveValidated(trackId, cdop, 60, 10);
		assertTrue(storage.exists(trackId));
		assertEquals(1, storage.countTracks());
		assertArrayEquals(cdop, storage.load(trackId).orElseThrow());

		// Saving the same track again deduplicates cleanly
		storage.saveValidated(trackId, cdop, 60, 10);
		assertEquals(1, storage.countTracks());

		// Path traversal blocked
		assertThrows(IOException.class, () -> storage.resolveTrackPath("../evil"));
		assertFalse(CdopAudioFormat.isValidTrackId("../evil"));

		// Non-normalized worldRoot (like Minecraft's LevelResource.ROOT ending with "/.")
		TrackStorage dotStorage = new TrackStorage(tempDir.resolve("world").resolve("."));
		dotStorage.saveValidated(trackId, cdop, 60, 10);
		assertTrue(dotStorage.exists(trackId));
		assertArrayEquals(cdop, dotStorage.load(trackId).orElseThrow());
	}
}
