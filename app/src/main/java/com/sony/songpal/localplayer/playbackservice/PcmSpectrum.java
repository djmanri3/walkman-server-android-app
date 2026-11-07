package com.sony.songpal.localplayer.playbackservice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Small FFT of the actual post-DSP PCM, for the existing web visualizers. */
final class PcmSpectrum {
    private static final int SIZE = 256;
    private final double[] real = new double[SIZE], imaginary = new double[SIZE];
    private volatile byte[] magnitudes = new byte[SIZE / 2];
    private long lastUpdate;
    byte[] get() { return magnitudes; }
    void update(ByteBuffer input, int bytes, int channels) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (channels < 1 || bytes < SIZE * channels * 2 || now - lastUpdate < 80) return;
        lastUpdate = now;
        ByteBuffer pcm = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        pcm.clear(); pcm.limit(Math.min(bytes, pcm.capacity()));
        for (int i = 0; i < SIZE; i++) {
            double sample = 0;
            for (int c = 0; c < channels; c++) sample += pcm.getShort() / 32768.0;
            real[i] = sample / channels * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (SIZE - 1)));
            imaginary[i] = 0;
        }
        for (int i = 1, j = 0; i < SIZE; i++) {
            int bit = SIZE >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double value = real[i]; real[i] = real[j]; real[j] = value;
            }
        }
        for (int length = 2; length <= SIZE; length <<= 1) {
            double step = -2 * Math.PI / length;
            for (int start = 0; start < SIZE; start += length) {
                for (int i = 0; i < length / 2; i++) {
                    int a = start + i, b = a + length / 2;
                    double cos = Math.cos(step * i), sin = Math.sin(step * i);
                    double re = real[b] * cos - imaginary[b] * sin;
                    double im = real[b] * sin + imaginary[b] * cos;
                    real[b] = real[a] - re; imaginary[b] = imaginary[a] - im;
                    real[a] += re; imaginary[a] += im;
                }
            }
        }
        byte[] output = new byte[SIZE / 2];
        for (int i = 0; i < output.length; i++) {
            double amplitude = Math.hypot(real[i], imaginary[i]) * 4 / SIZE;
            double db = 20 * Math.log10(Math.max(1e-7, amplitude));
            output[i] = (byte) Math.max(0, Math.min(255, Math.round((db + 100) * 255 / 70)));
        }
        magnitudes = output;
    }
}
