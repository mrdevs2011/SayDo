package uz.saydo.app;

import org.junit.Test;

import java.util.List;

import uz.saydo.app.kernel.voice.ParametricMatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive Unit Test Suite for Dynamic Voice-Parametric Macros (Pillar 2).
 */
public class ParametricMatcherTest {

    @Test
    public void testHasSlots() {
        assertTrue(ParametricMatcher.hasSlots("search {query} on youtube"));
        assertTrue(ParametricMatcher.hasSlots("send {amount} to {recipient}"));
        assertTrue(ParametricMatcher.hasSlots("{action}"));
        assertFalse(ParametricMatcher.hasSlots("open camera"));
        assertFalse(ParametricMatcher.hasSlots("turn on flashlight"));
        assertFalse(ParametricMatcher.hasSlots(null));
        assertFalse(ParametricMatcher.hasSlots(""));
    }

    @Test
    public void testExtractSlotNames() {
        List<String> s1 = ParametricMatcher.extractSlotNames("search {query} on youtube");
        assertEquals(1, s1.size());
        assertEquals("query", s1.get(0));

        List<String> s2 = ParametricMatcher.extractSlotNames("send {amount} dollars to {recipient}");
        assertEquals(2, s2.size());
        assertEquals("amount", s2.get(0));
        assertEquals("recipient", s2.get(1));

        List<String> s3 = ParametricMatcher.extractSlotNames("plain text without slots");
        assertTrue(s3.isEmpty());
    }

    @Test
    public void testSingleSlotMatch() {
        ParametricMatcher.MatchResult res = ParametricMatcher.match(
                "search interstellar on youtube",
                "search {query} on youtube"
        );
        assertTrue(res.matched);
        assertEquals("interstellar", res.get("query"));
    }

    @Test
    public void testMultiWordSlotMatch() {
        ParametricMatcher.MatchResult res = ParametricMatcher.match(
                "search lofi hip hop beats on youtube",
                "search {query} on youtube"
        );
        assertTrue(res.matched);
        assertEquals("lofi hip hop beats", res.get("query"));
    }

    @Test
    public void testMultiSlotMatch() {
        ParametricMatcher.MatchResult res = ParametricMatcher.match(
                "send 50 dollars to dad",
                "send {amount} dollars to {recipient}"
        );
        assertTrue(res.matched);
        assertEquals("50", res.get("amount"));
        assertEquals("dad", res.get("recipient"));
    }

    @Test
    public void testTrailingSlotMatch() {
        ParametricMatcher.MatchResult res = ParametricMatcher.match(
                "type meet me at noon",
                "type {message}"
        );
        assertTrue(res.matched);
        assertEquals("meet me at noon", res.get("message"));
    }

    @Test
    public void testMismatchReturnsFalse() {
        ParametricMatcher.MatchResult res = ParametricMatcher.match(
                "open camera now",
                "search {query} on youtube"
        );
        assertFalse(res.matched);
        assertNull(res.get("query"));
    }

    @Test
    public void testNullInputs() {
        assertFalse(ParametricMatcher.match(null, "template").matched);
        assertFalse(ParametricMatcher.match("spoken", null).matched);
        assertFalse(ParametricMatcher.match(null, null).matched);
    }

    @Test
    public void testPlainMatchFallback() {
        ParametricMatcher.MatchResult res = ParametricMatcher.match(
                "open camera",
                "open camera"
        );
        assertTrue(res.matched);
        assertTrue(res.variables.isEmpty());
    }
}
