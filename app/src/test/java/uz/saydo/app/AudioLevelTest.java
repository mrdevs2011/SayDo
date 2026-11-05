package uz.saydo.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import uz.saydo.app.audio.AudioLevel;

public class AudioLevelTest {

    private static byte[] constant(short value, int samples) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            b[2 * i] = (byte) (value & 0xFF);
            b[2 * i + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return b;
    }

    @Test
    public void dbfsOfSilenceIsFloor() {
        assertEquals(AudioLevel.MIN_DB, AudioLevel.dbfs(new byte[640], 0, 640), 1e-9);
    }

    @Test
    public void dbfsOfFullScaleIsZero() {
        byte[] b = constant(Short.MAX_VALUE, 320);
        assertEquals(0.0, AudioLevel.dbfs(b, 0, b.length), 0.01);
    }

    @Test
    public void dbfsHalfScaleIsMinusSix() {
        byte[] b = constant((short) 16384, 320);
        assertEquals(-6.02, AudioLevel.dbfs(b, 0, b.length), 0.01);
    }

    @Test
    public void dbfsHandlesNegativeSamples() {
        byte[] b = constant((short) -16384, 320);
        assertEquals(-6.02, AudioLevel.dbfs(b, 0, b.length), 0.01);
    }

    @Test
    public void detectorNeedsConsecutiveLoudFrames() {
        AudioLevel.Detector d = new AudioLevel.Detector(-30f, 3);
        assertFalse(d.feed(-20));
        assertFalse(d.feed(-20));
        assertFalse(d.feed(-40)); // a quiet frame resets
        assertFalse(d.feed(-20));
        assertFalse(d.feed(-25));
        assertTrue(d.feed(-30));  // third consecutive, equal counts
        assertFalse(d.feed(-10)); // fires only once until reset
        d.reset();
        assertFalse(d.feed(-10));
        assertFalse(d.feed(-10));
        assertTrue(d.feed(-10));
    }

    @Test
    public void detectorThresholdChangeTakesEffect() {
        AudioLevel.Detector d = new AudioLevel.Detector(-10f, 1);
        assertFalse(d.feed(-20));
        d.setThreshold(-25f);
        assertTrue(d.feed(-20));
    }

    @Test
    public void preRollKeepsNewestInOrder() {
        AudioLevel.PreRoll p = new AudioLevel.PreRoll(6);
        p.write(new byte[]{1, 2, 3, 4}, 0, 4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, p.snapshot());
        p.write(new byte[]{5, 6, 7, 8}, 0, 4);
        assertArrayEquals(new byte[]{3, 4, 5, 6, 7, 8}, p.snapshot());
        p.write(new byte[]{9, 10}, 0, 2);
        assertArrayEquals(new byte[]{5, 6, 7, 8, 9, 10}, p.snapshot());
    }

    @Test
    public void preRollLargeWriteKeepsTail() {
        AudioLevel.PreRoll p = new AudioLevel.PreRoll(4);
        p.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 0, 8);
        assertArrayEquals(new byte[]{5, 6, 7, 8}, p.snapshot());
        p.clear();
        assertEquals(0, p.snapshot().length);
    }

    @Test
    public void preRollCapacityIsSampleAligned() {
        AudioLevel.PreRoll p = new AudioLevel.PreRoll(5);
        p.write(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6);
        assertEquals(4, p.snapshot().length);
    }

    @Test
    public void percentileBasics() {
        double[] v = {5, 1, 3, 2, 4, 99};
        assertEquals(3.0, AudioLevel.percentile(v, 5, 50), 1e-9);
        assertEquals(5.0, AudioLevel.percentile(v, 5, 100), 1e-9);
        assertEquals(1.0, AudioLevel.percentile(v, 5, 1), 1e-9);
        assertTrue(Double.isNaN(AudioLevel.percentile(v, 0, 50)));
    }

    @Test
    public void suggestedThresholdIsAboveSpeechAndNoise() {
        // Normal: speech + offset
        assertEquals(-22f, AudioLevel.suggestThreshold(-55, -25, 3f), 1e-4);
        // Noisy room: at least noise + margin
        assertEquals(-20f, AudioLevel.suggestThreshold(-30, -32, 3f), 1e-4);
        // Clamped
        assertEquals(AudioLevel.MAX_THRESHOLD_DB, AudioLevel.suggestThreshold(-20, 0, 3f), 1e-4);
        assertEquals(AudioLevel.DEFAULT_THRESHOLD_DB, AudioLevel.clampThreshold(Float.NaN), 1e-4);
    }
}
