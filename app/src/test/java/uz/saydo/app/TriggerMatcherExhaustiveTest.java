package uz.saydo.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Exhaustive 50+ Test Suite for TriggerMatcher.
 * Validates Damerau-Levenshtein transposition detection, contraction expansions,
 * homophones, carrier phrases, prefix disambiguation, and phonetic edge cases.
 */
public class TriggerMatcherExhaustiveTest {

    // 1-10: Exact & Punctuation Insensitivity
    @Test
    public void test01_exactSingleWord() {
        assertEquals("camera", TriggerMatcher.norm("camera"));
        assertTrue(TriggerMatcher.score("camera", "camera") >= TriggerMatcher.EXACT);
    }

    @Test
    public void test02_exactMultiWord() {
        assertEquals("open camera", TriggerMatcher.norm("Open Camera"));
        assertTrue(TriggerMatcher.score("open camera", "open camera") >= TriggerMatcher.EXACT);
    }

    @Test
    public void test03_punctuationStripping() {
        assertEquals("open camera", TriggerMatcher.norm("Open, Camera!!!"));
        assertEquals("hello world", TriggerMatcher.norm("Hello... World?"));
    }

    @Test
    public void test04_smartApostropheStripping() {
        assertEquals("turn on", TriggerMatcher.norm("turn’ on"));
        assertEquals("turn on", TriggerMatcher.norm("turn` on"));
        assertEquals("turn on", TriggerMatcher.norm("turn' on"));
    }

    @Test
    public void test05_whitespaceCollapsing() {
        assertEquals("open youtube", TriggerMatcher.norm("   open     youtube   "));
        assertTrue(TriggerMatcher.score(TriggerMatcher.norm("open   youtube"), "open youtube") >= TriggerMatcher.EXACT);
    }

    @Test
    public void test06_nullAndEmptySafety() {
        assertEquals("", TriggerMatcher.norm(null));
        assertEquals("", TriggerMatcher.norm("   "));
        assertEquals(0.0, TriggerMatcher.score("", "test"), 1e-6);
        assertEquals(0.0, TriggerMatcher.score("test", ""), 1e-6);
        assertEquals(0.0, TriggerMatcher.score(null, null), 1e-6);
    }

    @Test
    public void test07_specialCharactersOnly() {
        assertEquals("", TriggerMatcher.norm("!@#$%^&*()"));
    }

    @Test
    public void test08_alphanumericPreservation() {
        assertEquals("test 123", TriggerMatcher.norm("Test 123"));
    }

    @Test
    public void test09_casingIndependence() {
        assertTrue(TriggerMatcher.score(TriggerMatcher.norm("OPEN YOUTUBE"), "open youtube") >= TriggerMatcher.EXACT);
        assertTrue(TriggerMatcher.score(TriggerMatcher.norm("oPeN YoUtUbE"), "open youtube") >= TriggerMatcher.EXACT);
    }

    @Test
    public void test10_repeatedPunctuation() {
        assertEquals("save file", TriggerMatcher.norm("save..... file????"));
    }

    // 11-20: Contractions Expansion
    @Test
    public void test11_contractionCant() {
        assertEquals("cannot stop", TriggerMatcher.norm("can't stop"));
    }

    @Test
    public void test12_contractionWont() {
        assertEquals("will not go", TriggerMatcher.norm("won't go"));
    }

    @Test
    public void test13_contractionDont() {
        assertEquals("do not touch", TriggerMatcher.norm("don't touch"));
    }

    @Test
    public void test14_contractionIm() {
        assertEquals("i am ready", TriggerMatcher.norm("I'm ready"));
    }

    @Test
    public void test15_contractionIts() {
        assertEquals("its working", TriggerMatcher.norm("it's working"));
    }

    @Test
    public void test16_contractionWhats() {
        assertEquals("what is that", TriggerMatcher.norm("what's that"));
    }

    @Test
    public void test17_contractionThats() {
        assertEquals("that is cool", TriggerMatcher.norm("that's cool"));
    }

    @Test
    public void test18_contractionLets() {
        assertEquals("let us go", TriggerMatcher.norm("let's go"));
    }

    @Test
    public void test19_contractionYoure() {
        assertEquals("your welcome", TriggerMatcher.norm("you're welcome"));
    }

    @Test
    public void test20_contractionTheyre() {
        assertEquals("there here", TriggerMatcher.norm("they're here"));
    }

    // 21-30: Damerau-Levenshtein Transposition & Fuzzy
    @Test
    public void test21_transpositionAdjacentChars() {
        // "teh" -> "the" is distance 1 in Damerau-Levenshtein
        assertEquals(1, TriggerMatcher.damerauLevenshtein("teh", "the"));
        assertEquals(1, TriggerMatcher.damerauLevenshtein("caemra", "camera"));
    }

    @Test
    public void test22_transpositionScoreFuzzyMatch() {
        double s = TriggerMatcher.score("caemra", "camera");
        assertTrue("Expected score >= FUZZY for transposition, got: " + s, s >= TriggerMatcher.FUZZY);
    }

    @Test
    public void test23_singleInsertionDistance() {
        assertEquals(1, TriggerMatcher.damerauLevenshtein("camera", "camerax"));
        assertTrue(TriggerMatcher.score("camerax", "camera") >= TriggerMatcher.FUZZY);
    }

    @Test
    public void test24_singleDeletionDistance() {
        assertEquals(1, TriggerMatcher.damerauLevenshtein("camera", "camra"));
        assertTrue(TriggerMatcher.score("camra", "camera") >= TriggerMatcher.FUZZY);
    }

    @Test
    public void test25_singleSubstitutionDistance() {
        assertEquals(1, TriggerMatcher.damerauLevenshtein("camera", "cemera"));
        assertTrue(TriggerMatcher.score("cemera", "camera") >= TriggerMatcher.FUZZY);
    }

    @Test
    public void test26_identicalStringZeroDistance() {
        assertEquals(0, TriggerMatcher.damerauLevenshtein("chrome", "chrome"));
        assertEquals(1.0, TriggerMatcher.sim("chrome", "chrome"), 1e-6);
    }

    @Test
    public void test27_emptyStringDistance() {
        assertEquals(6, TriggerMatcher.damerauLevenshtein("", "chrome"));
        assertEquals(6, TriggerMatcher.damerauLevenshtein("chrome", ""));
    }

    @Test
    public void test28_fuzzyPhraseMatching() {
        double s = TriggerMatcher.score(TriggerMatcher.norm("opn chrom"), "open chrome");
        assertTrue("Score: " + s, s >= TriggerMatcher.FUZZY);
    }

    @Test
    public void test29_subpixelTypoTolerance() {
        double s = TriggerMatcher.score(TriggerMatcher.norm("flaslight"), "flashlight");
        assertTrue(s >= TriggerMatcher.FUZZY);
    }

    @Test
    public void test30_unrelatedWordsLowScore() {
        double s = TriggerMatcher.score("airplane", "calculator");
        assertTrue("Unrelated words score should be low: " + s, s < 0.40);
    }

    // 31-40: Homophones and Number Aliases
    @Test
    public void test31_homophoneWonOne() {
        assertEquals("1", TriggerMatcher.norm("won"));
        assertEquals("1", TriggerMatcher.norm("one"));
        assertTrue(TriggerMatcher.score(TriggerMatcher.norm("won"), "1") >= TriggerMatcher.EXACT);
    }

    @Test
    public void test32_homophoneTwoToToo() {
        assertEquals("2", TriggerMatcher.norm("two"));
        assertEquals("2", TriggerMatcher.norm("to"));
        assertEquals("2", TriggerMatcher.norm("too"));
    }

    @Test
    public void test33_homophoneFourForFore() {
        assertEquals("4", TriggerMatcher.norm("four"));
        assertEquals("4", TriggerMatcher.norm("for"));
        assertEquals("4", TriggerMatcher.norm("fore"));
    }

    @Test
    public void test34_homophoneEightAte() {
        assertEquals("8", TriggerMatcher.norm("eight"));
        assertEquals("8", TriggerMatcher.norm("ate"));
    }

    @Test
    public void test35_homophoneSunSon() {
        assertEquals("sun", TriggerMatcher.norm("sun"));
        assertEquals("sun", TriggerMatcher.norm("son"));
    }

    @Test
    public void test36_homophoneHourOur() {
        assertEquals("hour", TriggerMatcher.norm("hour"));
        assertEquals("hour", TriggerMatcher.norm("our"));
    }

    @Test
    public void test37_homophoneBuyByBye() {
        assertEquals("by", TriggerMatcher.norm("buy"));
        assertEquals("by", TriggerMatcher.norm("by"));
        assertEquals("by", TriggerMatcher.norm("bye"));
    }

    @Test
    public void test38_homophoneWeatherWhether() {
        assertEquals("weather", TriggerMatcher.norm("whether"));
        assertEquals("weather", TriggerMatcher.norm("weather"));
    }

    @Test
    public void test39_homophoneSeeSea() {
        assertEquals("see", TriggerMatcher.norm("see"));
        assertEquals("see", TriggerMatcher.norm("sea"));
    }

    @Test
    public void test40_slangYoutubeAndWifi() {
        assertEquals("youtube", TriggerMatcher.norm("yt"));
        assertEquals("youtube", TriggerMatcher.norm("u tube"));
        assertEquals("wifi", TriggerMatcher.norm("wi fi"));
    }

    // 41-48: Carrier Phrases & Windowing
    @Test
    public void test41_carrierPhrasePrefix() {
        String heard = TriggerMatcher.norm("Hey assistant please open camera");
        double s = TriggerMatcher.score(heard, "open camera");
        assertTrue("Carrier phrase score: " + s, s >= TriggerMatcher.EXACT);
    }

    @Test
    public void test42_carrierPhraseSuffix() {
        String heard = TriggerMatcher.norm("open camera right now please");
        double s = TriggerMatcher.score(heard, "open camera");
        assertTrue("Carrier suffix score: " + s, s >= TriggerMatcher.EXACT);
    }

    @Test
    public void test43_carrierPhraseEmbedded() {
        String heard = TriggerMatcher.norm("can you please open camera for me");
        double s = TriggerMatcher.score(heard, "open camera");
        assertTrue("Embedded score: " + s, s >= TriggerMatcher.FUZZY);
    }

    @Test
    public void test44_fillerWordsFilteredOut() {
        assertEquals("open camera", TriggerMatcher.norm("please hey okay um open camera"));
    }

    @Test
    public void test45_fillerWordsPreservedIfAlone() {
        // When only filler words are spoken, do not reduce to empty string
        assertEquals("please", TriggerMatcher.norm("please"));
        assertEquals("ok", TriggerMatcher.norm("ok"));
    }

    @Test
    public void test46_soundexPhoneticEncoding() {
        assertEquals("R163", TriggerMatcher.soundex("Robert"));
        assertEquals("R163", TriggerMatcher.soundex("Rupert"));
        assertEquals("S530", TriggerMatcher.soundex("Smith"));
        assertEquals("S530", TriggerMatcher.soundex("Smythe"));
    }

    @Test
    public void test47_metaphonePhoneticEncoding() {
        assertFalse(TriggerMatcher.phon("knight").isEmpty());
        assertEquals(TriggerMatcher.phon("knight"), TriggerMatcher.phon("night"));
    }

    @Test
    public void test48_soundexPhraseConsistency() {
        assertNotNull(TriggerMatcher.soundex("Open"));
        assertEquals(4, TriggerMatcher.soundex("Open").length());
    }

    // 49-55: Best Matching & Sibling Prefix Disambiguation
    @Test
    public void test49_bestMatchingSimpleSelection() {
        List<String> triggers = Arrays.asList("open camera", "take picture", "flashlight on");
        List<String> heard = Collections.singletonList("please open camera");
        int idx = TriggerMatcher.best(heard, triggers, TriggerMatcher.FUZZY);
        assertEquals(0, idx);
    }

    @Test
    public void test50_bestMatchingNoMatchBelowThreshold() {
        List<String> triggers = Arrays.asList("open camera", "take picture");
        List<String> heard = Collections.singletonList("completely unrelated speech");
        int idx = TriggerMatcher.best(heard, triggers, TriggerMatcher.FUZZY);
        assertEquals(-1, idx);
    }

    @Test
    public void test51_hasLongerSiblingDetection() {
        List<String> triggers = Arrays.asList("open", "open camera", "take note");
        assertTrue(TriggerMatcher.hasLongerSibling(triggers, 0)); // "open" has "open camera"
        assertFalse(TriggerMatcher.hasLongerSibling(triggers, 1)); // "open camera" has no longer sibling
        assertFalse(TriggerMatcher.hasLongerSibling(triggers, 2)); // "take note" has no longer sibling
    }

    @Test
    public void test52_bestMatchesLongerSiblingWhenSpoken() {
        List<String> triggers = Arrays.asList("open", "open camera");
        List<String> heard = Collections.singletonList("open camera");
        int idx = TriggerMatcher.best(heard, triggers, TriggerMatcher.FUZZY);
        assertEquals("Should prefer longer exact match 'open camera'", 1, idx);
    }

    @Test
    public void test53_bestHandlesEmptyOrNullHeardGracefully() {
        List<String> triggers = Collections.singletonList("open");
        assertEquals(-1, TriggerMatcher.best(null, triggers, TriggerMatcher.FUZZY));
        assertEquals(-1, TriggerMatcher.best(Collections.emptyList(), triggers, TriggerMatcher.FUZZY));
        assertEquals(-1, TriggerMatcher.best(Collections.singletonList(""), triggers, TriggerMatcher.FUZZY));
    }

    @Test
    public void test54_hasLongerSiblingBoundarySafety() {
        List<String> triggers = Collections.singletonList("test");
        assertFalse(TriggerMatcher.hasLongerSibling(triggers, -1));
        assertFalse(TriggerMatcher.hasLongerSibling(triggers, 99));
        assertFalse(TriggerMatcher.hasLongerSibling(null, 0));
    }

    @Test
    public void test55_multiAlternativeHeardRanksHighestScore() {
        List<String> triggers = Collections.singletonList("open youtube");
        List<String> heard = Arrays.asList("something else", "open yt", "another text");
        int idx = TriggerMatcher.best(heard, triggers, TriggerMatcher.FUZZY);
        assertEquals(0, idx);
    }
}
