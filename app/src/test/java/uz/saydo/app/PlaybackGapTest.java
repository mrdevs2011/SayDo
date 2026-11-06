package uz.saydo.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import uz.saydo.app.util.PlaybackGap;

public class PlaybackGapTest {
    @Test public void shortGapUnchanged() {
        assertEquals(1200, PlaybackGap.effective(1200, true));
        assertEquals(1200, PlaybackGap.effective(1200, false));
    }
    @Test public void exactlyAtCapUnchanged() {
        assertEquals(3000, PlaybackGap.effective(3000, true));
    }
    @Test public void longGapCappedWithPkg() {
        assertEquals(3000, PlaybackGap.effective(17000, true));
        assertEquals(3000, PlaybackGap.effective(3001, true));
    }
    @Test public void longGapExactWithoutPkg() {
        assertEquals(17000, PlaybackGap.effective(17000, false));
    }
    @Test public void negativeGapIsZero() {
        assertEquals(0, PlaybackGap.effective(-5, true));
        assertEquals(0, PlaybackGap.effective(-5, false));
    }
}
