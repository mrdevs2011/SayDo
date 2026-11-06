package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class McpFormatTest {

    @Test
    public void clipKeepsShortAndCutsLongToExactLength() {
        assertEquals("hi", McpFormat.clip("  hi \n", 50));
        String cut = McpFormat.clip("a".repeat(80), 50);
        assertEquals(50, cut.length());
        assertTrue(cut.endsWith("…"));
        assertEquals("a b c", McpFormat.clip("a\nb\tc", 50));
        assertEquals("", McpFormat.clip(null, 5));
    }

    @Test
    public void flagsAreFixedOrderWords() {
        assertEquals("", McpFormat.flags(false, false, false, null));
        assertEquals(" click edit scroll checked", McpFormat.flags(true, true, true, true));
        assertEquals(" unchecked", McpFormat.flags(false, false, false, false));
    }

    @Test
    public void labelFallsBackToDescThenIdThenQuestionMark() {
        assertEquals("Send", McpFormat.label("Send", "desc", "p:id/x"));
        assertEquals("desc", McpFormat.label("  ", "desc", "p:id/x"));
        assertEquals("#send_btn", McpFormat.label("", "", "org.app:id/send_btn"));
        assertEquals("?", McpFormat.label("", "", ""));
    }

    @Test
    public void lineWithoutBoundsHasNoBracketsAndWithBoundsHasThem() {
        assertEquals("7 Yuborish click", McpFormat.line(7, "Yuborish", " click", null));
        assertEquals("7 Yuborish click [10,20][30,40]",
                McpFormat.line(7, "Yuborish", " click", new int[]{10, 20, 30, 40}));
    }

    @Test
    public void headerShowsPackageAndSize() {
        assertEquals("org.telegram.messenger 1080x2340", McpFormat.header("org.telegram.messenger", 1080, 2340));
        assertEquals("? 10x20", McpFormat.header("", 10, 20));
    }

    @Test
    public void queryIsCaseInsensitiveSubstringOverAnyField() {
        assertTrue(McpFormat.matches("", "x"));
        assertTrue(McpFormat.matches("yubor", "YUBORISH", null, null));
        assertTrue(McpFormat.matches("SEND", "", "Send message", ""));
        assertTrue(McpFormat.matches("btn", "", "", "p:id/send_btn"));
        assertFalse(McpFormat.matches("zzz", "a", "b", "c"));
    }

    @Test
    public void maxElementsDefaultsAndClamps() {
        assertEquals(80, McpFormat.clampMax(0, McpFormat.DEFAULT_ELEMENTS, 600));
        assertEquals(5, McpFormat.clampMax(5, McpFormat.DEFAULT_ELEMENTS, 600));
        assertEquals(600, McpFormat.clampMax(5000, McpFormat.DEFAULT_ELEMENTS, 600));
    }

    @Test
    public void compactLineIsMuchShorterThanOldFormat() {
        String old = "[12] TextView \"Yuborish\" desc=\"Send message\" id=send_button clickable [900,2100][1060,2250]";
        String neu = McpFormat.line(12, McpFormat.label("Yuborish", "Send message", "x:id/send_button"),
                McpFormat.flags(true, false, false, null), null);
        assertTrue(neu.length() < old.length() / 2);
    }
}
