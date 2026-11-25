package com.customdiscs.audio;

/**
 * Downmixes multi-channel PCM audio to mono and resamples to 48 kHz
 * for Opus encoding and OpenAL 3D spatial playback.
 */
public final class PcmResampler {
	private PcmResampler() {
	}

	/**
	 * Converts interleaved signed 16-bit PCM samples into 48 kHz mono signed 16-bit PCM.
	 */
	public static short[] toMono48k(short[] interleavedPcm, int sampleCount, int channels, int sourceSampleRate) {
		if (sampleCount <= 0 || channels <= 0 || sourceSampleRate <= 0) {
			return new short[0];
		}

		int frames = sampleCount / channels;
		if (frames <= 0) {
			return new short[0];
		}

		short[] mono;
		if (channels == 1) {
			mono = new short[frames];
			System.arraycopy(interleavedPcm, 0, mono, 0, frames);
		} else {
			mono = new short[frames];
			for (int f = 0; f < frames; f++) {
				int sum = 0;
				int base = f * channels;
				for (int c = 0; c < channels; c++) {
					sum += interleavedPcm[base + c];
				}
				mono[f] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum / channels));
			}
		}

		if (sourceSampleRate == CdopAudioFormat.SAMPLE_RATE) {
			return mono;
		}

		double ratio = (double) sourceSampleRate / CdopAudioFormat.SAMPLE_RATE;
		int outLength = Math.max(1, (int) Math.round(mono.length / ratio));
		short[] resampled = new short[outLength];

		for (int i = 0; i < outLength; i++) {
			double srcPos = i * ratio;
			int idx0 = (int) srcPos;
			if (idx0 >= mono.length - 1) {
				resampled[i] = mono[mono.length - 1];
			} else {
				double frac = srcPos - idx0;
				double val = mono[idx0] * (1.0 - frac) + mono[idx0 + 1] * frac;
				resampled[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(val)));
			}
		}
		return resampled;
	}
}
