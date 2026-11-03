package uz.saydo.app;

import org.junit.Test;

import uz.saydo.app.kernel.voice.MultilingualMatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive Unit Test Suite for Multilingual Speech & Semantic Intent Recognition (Pillar 3).
 */
public class MultilingualMatcherTest {

    @Test
    public void testUzbekDiacriticNormalization() {
        assertEquals("o'zbekiston", MultilingualMatcher.normalizeMultilingual("o‘zbekiston"));
        assertEquals("o'zbekiston", MultilingualMatcher.normalizeMultilingual("o’zbekiston"));
        assertEquals("o'zbekiston", MultilingualMatcher.normalizeMultilingual("o`zbekiston"));
        assertEquals("g'alaba", MultilingualMatcher.normalizeMultilingual("g‘alaba"));
    }

    @Test
    public void testCyrillicTransliteration() {
        String cyr = "камера";
        String lat = MultilingualMatcher.transliterateCyrillicToLatin(cyr);
        assertEquals("kamera", lat);

        String cyrUz = "ўзбекистон";
        String latUz = MultilingualMatcher.transliterateCyrillicToLatin(cyrUz);
        assertEquals("o'zbekiston", latUz);
    }

    @Test
    public void testRussianCommandMapping() {
        // "открой камеру" -> maps "открой" to "open"
        String normalized = MultilingualMatcher.normalizeMultilingual("открой камеру");
        assertTrue(normalized.startsWith("open"));
    }

    @Test
    public void testTokenSortSimilarity() {
        float sim1 = MultilingualMatcher.tokenSortSimilarity("open camera", "camera open");
        assertTrue("Scrambled tokens should have high similarity: " + sim1, sim1 >= 0.85f);

        float sim2 = MultilingualMatcher.tokenSortSimilarity("camera please open", "open camera");
        assertTrue("Filler word 'please' should be handled: " + sim2, sim2 >= 0.85f);

        float sim3 = MultilingualMatcher.tokenSortSimilarity("saydo turn on flashlight", "flashlight turn on");
        assertTrue("Prefix 'saydo' should be ignored: " + sim3, sim3 >= 0.85f);
    }

    @Test
    public void testMatchesMultilingual() {
        assertTrue(MultilingualMatcher.matchesMultilingual("open camera", "open camera"));
        assertTrue(MultilingualMatcher.matchesMultilingual("camera open please", "open camera"));
        assertTrue(MultilingualMatcher.matchesMultilingual("открой камеру", "open kamera"));
        assertTrue(MultilingualMatcher.matchesMultilingual("och kamerani", "open camera"));
        assertFalse(MultilingualMatcher.matchesMultilingual("completely different sentence", "open camera"));
    }

    @Test
    public void testNullSafety() {
        assertEquals("", MultilingualMatcher.normalizeMultilingual(null));
        assertEquals("", MultilingualMatcher.transliterateCyrillicToLatin(null));
        assertEquals(0.0f, MultilingualMatcher.tokenSortSimilarity(null, "test"), 0.001f);
        assertFalse(MultilingualMatcher.matchesMultilingual(null, null));
    }
}
