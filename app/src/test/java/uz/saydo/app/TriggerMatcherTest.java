package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TriggerMatcherTest {

    @Test public void norm_lowercasesAndStripsPunctuation() {
        assertEquals("hello world", TriggerMatcher.norm("Hello, World!"));
    }

    @Test public void norm_nullAndEmpty() {
        assertEquals("", TriggerMatcher.norm(null));
        assertEquals("", TriggerMatcher.norm("   "));
    }

    @Test public void norm_collapsesSpaces() {
        assertEquals("a b", TriggerMatcher.norm("  a   b  "));
    }

    @Test public void norm_removesApostrophes() {
        assertEquals("ozbek", TriggerMatcher.norm("O'zbek"));
    }

    @Test public void norm_transliteratesCyrillic() {
        assertEquals("privet", TriggerMatcher.norm("Привет"));
        assertEquals("qol", TriggerMatcher.norm("Қўл"));
    }

    @Test public void score_exactMatchIsOne() {
        assertEquals(1.0, TriggerMatcher.score("open camera", "open camera"), 1e-9);
    }

    @Test public void score_triggerInsideSentence() {
        assertEquals(0.97, TriggerMatcher.score("please open camera now", "open camera"), 1e-9);
    }

    @Test public void score_ignoresSpacingDifferences() {
        assertEquals(0.96, TriggerMatcher.score("opencamera", "open camera"), 1e-9);
    }

    @Test public void score_emptyInputIsZero() {
        assertEquals(0.0, TriggerMatcher.score("", "open"), 1e-9);
        assertEquals(0.0, TriggerMatcher.score("open", ""), 1e-9);
    }

    @Test public void score_tooShortTriggerNeverFuzzyMatches() {
        assertEquals(0.0, TriggerMatcher.score("abc def", "ab"), 1e-9);
    }

    @Test public void score_smallTypoStillMatchesFuzzy() {
        assertTrue(TriggerMatcher.score("open camra", "open camera") >= TriggerMatcher.FUZZY);
    }

    @Test public void score_unrelatedTextIsBelowFuzzy() {
        assertTrue(TriggerMatcher.score("hello world", "open camera") < TriggerMatcher.FUZZY);
    }

    @Test public void best_picksMatchingTrigger() {
        List<String> triggers = Arrays.asList("open camera", "close app");
        assertEquals(0, TriggerMatcher.best(Collections.singletonList("open camera"), triggers, TriggerMatcher.FUZZY));
        assertEquals(1, TriggerMatcher.best(Collections.singletonList("close app"), triggers, TriggerMatcher.FUZZY));
    }

    @Test public void best_returnsMinusOneWhenNothingMatches() {
        List<String> triggers = Arrays.asList("open camera", "close app");
        assertEquals(-1, TriggerMatcher.best(Collections.singletonList("banana"), triggers, TriggerMatcher.FUZZY));
    }

    @Test public void best_prefersLongerTriggerOnTie() {
        List<String> triggers = Arrays.asList("open", "open camera");
        assertEquals(1, TriggerMatcher.best(Collections.singletonList("open camera"), triggers, TriggerMatcher.FUZZY));
    }

    @Test public void best_skipsEmptyTriggers() {
        List<String> triggers = Arrays.asList("", "go");
        assertEquals(1, TriggerMatcher.best(Collections.singletonList("go"), triggers, TriggerMatcher.FUZZY));
    }

    @Test public void best_respectsThreshold() {
        List<String> triggers = Collections.singletonList("open camera");
        assertEquals(-1, TriggerMatcher.best(Collections.singletonList("open camra"), triggers, TriggerMatcher.EXACT));
    }
}
