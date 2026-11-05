package uz.saydo.app.audio;

import java.util.Arrays;

/**
 * Pure audio-level helpers for the voice gate (no Android APIs, unit-tested).
 *
 * Levels are in dBFS: 0 = digital full scale, quiet room ≈ -60…-50, normal speech at
 * arm's length ≈ -35…-20. Absolute values differ per phone microphone, which is why
 * the threshold is calibrated per device instead of being a fixed "dB".
 */
public final class AudioLevel {

    public static final double MIN_DB = -90.0;
    public static final float MIN_THRESHOLD_DB = -60f;
    public static final float MAX_THRESHOLD_DB = -3f;
    public static final float DEFAULT_THRESHOLD_DB = -28f;
    /** Gate opens at "louder than your normal speech" + this many dB (user can move it). */
    public static final float DEFAULT_OFFSET_DB = 3f;
    /** Never put the threshold closer than this to the room noise floor. */
    public static final float MIN_MARGIN_OVER_NOISE_DB = 10f;

    private AudioLevel() {}

    /** RMS level of little-endian 16-bit PCM bytes, in dBFS. */
    public static double dbfs(byte[] pcm, int offset, int length) {
        int samples = length / 2;
        if (samples <= 0) return MIN_DB;
        double sum = 0;
        int end = offset + samples * 2;
        for (int i = offset; i < end; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / samples);
        if (rms < 1e-9) return MIN_DB;
        return Math.max(MIN_DB, 20.0 * Math.log10(rms / 32768.0));
    }

    /**
     * Opens when the level stays at/above the threshold for {@code needFrames} consecutive
     * frames, so a single click or tap on the table does not open the gate.
     */
    public static final class Detector {
        private volatile float thresholdDb;
        private final int needFrames;
        private int hot;

        public Detector(float thresholdDb, int needFrames) {
            this.thresholdDb = thresholdDb;
            this.needFrames = Math.max(1, needFrames);
        }

        public void setThreshold(float db) {
            thresholdDb = db;
        }

        public float threshold() {
            return thresholdDb;
        }

        public void reset() {
            hot = 0;
        }

        /** @return true exactly once when the gate should open */
        public boolean feed(double db) {
            if (db >= thresholdDb) {
                hot++;
                if (hot == needFrames) return true;
            } else {
                hot = 0;
            }
            return false;
        }
    }

    /** Fixed-size ring buffer that keeps the most recent audio (the "pre-roll"). */
    public static final class PreRoll {
        private final byte[] ring;
        private int pos;
        private boolean full;

        public PreRoll(int capacityBytes) {
            // Keep sample alignment for 16-bit PCM.
            ring = new byte[Math.max(2, capacityBytes & ~1)];
        }

        public synchronized void write(byte[] src, int off, int len) {
            if (len >= ring.length) {
                System.arraycopy(src, off + len - ring.length, ring, 0, ring.length);
                pos = 0;
                full = true;
                return;
            }
            int first = Math.min(len, ring.length - pos);
            System.arraycopy(src, off, ring, pos, first);
            if (len > first) System.arraycopy(src, off + first, ring, 0, len - first);
            int next = pos + len;
            if (next >= ring.length) full = true;
            pos = next % ring.length;
        }

        /** Oldest-to-newest copy of the buffered audio. */
        public synchronized byte[] snapshot() {
            if (!full) return Arrays.copyOf(ring, pos);
            byte[] out = new byte[ring.length];
            System.arraycopy(ring, pos, out, 0, ring.length - pos);
            System.arraycopy(ring, 0, out, ring.length - pos, pos);
            return out;
        }

        public synchronized void clear() {
            pos = 0;
            full = false;
        }
    }

    /** p-th percentile (0–100) of the first n values; NaN when n == 0. */
    public static double percentile(double[] values, int n, double p) {
        if (values == null || n <= 0) return Double.NaN;
        double[] s = Arrays.copyOf(values, n);
        Arrays.sort(s);
        int idx = (int) Math.ceil(p / 100.0 * n) - 1;
        idx = Math.max(0, Math.min(n - 1, idx));
        return s[idx];
    }

    /**
     * Threshold from a calibration: speech level + offset, but at least
     * {@link #MIN_MARGIN_OVER_NOISE_DB} above the noise floor, clamped to the allowed range.
     */
    public static float suggestThreshold(double noiseDb, double speechDb, float offsetDb) {
        double t = speechDb + offsetDb;
        if (!Double.isNaN(noiseDb)) t = Math.max(t, noiseDb + MIN_MARGIN_OVER_NOISE_DB);
        return clampThreshold((float) t);
    }

    public static float clampThreshold(float db) {
        if (Float.isNaN(db)) return DEFAULT_THRESHOLD_DB;
        return Math.max(MIN_THRESHOLD_DB, Math.min(MAX_THRESHOLD_DB, db));
    }
}
