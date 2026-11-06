package uz.saydo.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import uz.saydo.app.util.StepLogic;
import uz.saydo.app.util.StepLogic.NavDetector;
import uz.saydo.app.util.StepLogic.ScrollAccum;

import static org.junit.Assert.*;

public class StepLogicTest {
    @Test public void signatureIgnoresOrderAndEmpty() {
        long a = StepLogic.signature(Arrays.asList("a", "b", "", null, "c"));
        long b = StepLogic.signature(Arrays.asList("c", "a", "b"));
        assertEquals(a, b);
        assertNotEquals(a, StepLogic.signature(Arrays.asList("a", "b")));
    }

    @Test public void overlapShare() {
        assertEquals(0.5, StepLogic.overlap(new HashSet<>(Arrays.asList("a", "b")), new HashSet<>(Arrays.asList("a", "x"))), 1e-9);
        assertEquals(1.0, StepLogic.overlap(new HashSet<String>(), new HashSet<String>()), 1e-9);
    }

    @Test public void scrollAccumMergesQuickEvents() {
        ScrollAccum s = new ScrollAccum();
        assertFalse(s.needsFlush("list", 0));
        s.add("list", 0, 40, 0);
        assertFalse(s.needsFlush("list", 100));
        s.add("list", 0, 60, 100);
        assertEquals(100, s.dy());
        assertTrue(s.significant());
        assertTrue(s.needsFlush("list", 700));      // pause
        assertTrue(s.needsFlush("other", 150));     // other container
    }

    @Test public void tinyScrollIsNotSignificant() {
        ScrollAccum s = new ScrollAccum();
        s.add("l", 0, 5, 0);
        assertFalse(s.significant());
        s.reset();
        assertFalse(s.isOpen());
    }

    @Test public void textMergesOnlyOnSameFieldDirectlyAfter() {
        assertTrue(StepLogic.mergeText("text", "f1", "f1"));
        assertFalse(StepLogic.mergeText("tap", "f1", "f1"));
        assertFalse(StepLogic.mergeText("text", "f1", "f2"));
        assertFalse(StepLogic.mergeText(null, null, "f1"));
    }

    @Test public void navDetectsBackAndHome() {
        NavDetector n = new NavDetector();
        assertEquals(StepLogic.NAV_NONE, n.onWindow("Main", false, false, 0));
        n.onClick(1000);
        assertEquals(StepLogic.NAV_NONE, n.onWindow("Sub", false, false, 1200));   // explained by click
        assertEquals(StepLogic.NAV_BACK, n.onWindow("Main", false, false, 9000));  // no click: back
        assertEquals(StepLogic.NAV_HOME, n.onWindow("Launcher", true, false, 12000));
    }

    @Test public void navUnexplainedForwardIsNotBack() {
        NavDetector n = new NavDetector();
        n.onWindow("A", false, false, 0);
        assertEquals(StepLogic.NAV_NONE, n.onWindow("B", false, false, 5000));
        assertEquals(StepLogic.NAV_NONE, n.onWindow("C", false, false, 9000));
    }

    @Test public void navLauncherAfterClickIsNotHome() {
        NavDetector n = new NavDetector();
        n.onWindow("A", false, false, 0);
        n.onClick(100);
        assertEquals(StepLogic.NAV_NONE, n.onWindow("Launcher", true, false, 300));
    }

    @Test public void swipeStaysInsideBoundsAndOpposesContent() {
        int[] s = StepLogic.swipeFor(0, 0, 1000, 2000, 0, 600);
        assertEquals(500, s[0]);
        assertEquals(1300, s[1]);   // starts lower
        assertEquals(700, s[3]);    // ends higher: finger moves up, content moves up
        int[] big = StepLogic.swipeFor(0, 0, 1000, 2000, 0, 99999);
        assertTrue(big[1] <= 2000 && big[3] >= 0);
    }

    @Test public void searchDirectionForwardThenBack() {
        assertEquals(1, StepLogic.searchDirection(0, 3));
        assertEquals(1, StepLogic.searchDirection(2, 3));
        assertEquals(-1, StepLogic.searchDirection(3, 3));
    }

    @Test public void swipeMsBounded() {
        assertEquals(300, StepLogic.swipeMs(10));
        assertEquals(900, StepLogic.swipeMs(5000));
    }

    @Test public void stuckWhenSignatureSame() {
        assertTrue(StepLogic.stuck(5L, 5L));
        assertFalse(StepLogic.stuck(5L, 6L));
    }
}
