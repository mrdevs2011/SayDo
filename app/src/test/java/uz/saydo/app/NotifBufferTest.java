package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

public class NotifBufferTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long NOW = 1_700_000_000_000L; // 2023-11-14 22:13:20 UTC

    private static NotifBuffer.Entry e(String key, long minutesAgo, String pkg, String label, String title, String text) {
        return new NotifBuffer.Entry(key, NOW - minutesAgo * 60_000L, pkg, label, title, text);
    }

    @Test
    public void ringBufferKeepsNewest100() {
        NotifBuffer b = new NotifBuffer(NotifBuffer.CAPACITY);
        for (int i = 0; i < 130; i++) b.add(e("k" + i, 0, "p", "P", "t" + i, ""));
        List<NotifBuffer.Entry> s = b.snapshot();
        assertEquals(100, s.size());
        assertEquals("t129", s.get(0).title);
        assertEquals("t30", s.get(99).title);
    }

    @Test
    public void sameKeyReplacesInsteadOfDuplicating() {
        NotifBuffer b = new NotifBuffer(10);
        b.add(e("a", 5, "p", "P", "old", ""));
        b.add(e("b", 4, "p", "P", "other", ""));
        b.add(e("a", 1, "p", "P", "new", ""));
        List<NotifBuffer.Entry> s = b.snapshot();
        assertEquals(2, s.size());
        assertEquals("new", s.get(0).title);
    }

    @Test
    public void clearEmptiesTheBuffer() {
        NotifBuffer b = new NotifBuffer(10);
        b.add(e("a", 1, "p", "P", "t", ""));
        b.clear();
        assertTrue(b.snapshot().isEmpty());
    }

    @Test
    public void acceptIgnoresOwnOngoingForegroundAndSummaries() {
        assertTrue(NotifBuffer.accept(false, false, false, false));
        assertFalse(NotifBuffer.accept(true, false, false, false));
        assertFalse(NotifBuffer.accept(false, true, false, false));
        assertFalse(NotifBuffer.accept(false, false, true, false));
        assertFalse(NotifBuffer.accept(false, false, false, true));
    }

    @Test
    public void queryIsNewestFirstWithTimeLabelTitleText() {
        List<NotifBuffer.Entry> all = Arrays.asList(
                e("1", 30, "org.telegram.messenger", "Telegram", "Ali", "salom"),
                e("2", 2, "com.whatsapp", "WhatsApp", "Vali", "qalaysan"));
        List<String> out = NotifBuffer.query(all, 15, "", 0, NOW, UTC);
        assertEquals(2, out.size());
        assertEquals("22:11 WhatsApp: Vali - qalaysan", out.get(0));
        assertEquals("21:43 Telegram: Ali - salom", out.get(1));
    }

    @Test
    public void limitDefaultsAndCaps() {
        List<NotifBuffer.Entry> all = new ArrayList<>();
        for (int i = 0; i < 80; i++) all.add(e("k" + i, i, "p", "P", "t", "x"));
        assertEquals(15, NotifBuffer.query(all, 0, null, 0, NOW, UTC).size());
        assertEquals(3, NotifBuffer.query(all, 3, null, 0, NOW, UTC).size());
        assertEquals(50, NotifBuffer.query(all, 999, null, 0, NOW, UTC).size());
    }

    @Test
    public void sinceMinutesFilter() {
        List<NotifBuffer.Entry> all = Arrays.asList(
                e("1", 90, "p", "P", "old", ""), e("2", 10, "p", "P", "recent", ""));
        List<String> out = NotifBuffer.query(all, 15, null, 60, NOW, UTC);
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("recent"));
    }

    @Test
    public void appFilterMatchesPackageOrLabelCaseInsensitively() {
        List<NotifBuffer.Entry> all = Arrays.asList(
                e("1", 1, "org.telegram.messenger", "Telegram", "a", ""),
                e("2", 2, "com.whatsapp", "WhatsApp", "b", ""));
        assertEquals(1, NotifBuffer.query(all, 15, "TELEGRAM", 0, NOW, UTC).size());
        assertEquals(1, NotifBuffer.query(all, 15, "com.whats", 0, NOW, UTC).size());
        assertEquals(0, NotifBuffer.query(all, 15, "signal", 0, NOW, UTC).size());
    }

    @Test
    public void textIsSingleLineAndTruncated() {
        String longText = "x".repeat(300) + "\nsecond line";
        List<String> out = NotifBuffer.query(Arrays.asList(e("1", 1, "p", "P", "T", longText)), 15, null, 0, NOW, UTC);
        assertFalse(out.get(0).contains("\n"));
        assertTrue(out.get(0).length() <= "22:12 P: T - ".length() + NotifBuffer.TEXT_MAX);
    }

    @Test
    public void emptyTitleOrTextStillFormats() {
        List<String> out = NotifBuffer.query(Arrays.asList(
                e("1", 1, "p.q", "", "", "only text"), e("2", 2, "p", "P", "only title", "")), 15, null, 0, NOW, UTC);
        assertEquals("22:12 p.q: only text", out.get(0));
        assertEquals("22:11 P: only title", out.get(1));
    }

    @Test
    public void mergeLetsActiveReplaceBufferedWithSameKey() {
        List<NotifBuffer.Entry> merged = NotifBuffer.merge(
                Arrays.asList(e("a", 5, "p", "P", "buffered-a", ""), e("b", 6, "p", "P", "buffered-b", "")),
                Arrays.asList(e("a", 1, "p", "P", "active-a", "")));
        assertEquals(2, merged.size());
        boolean sawActive = false;
        for (NotifBuffer.Entry x : merged) {
            assertFalse(x.title.equals("buffered-a"));
            if (x.title.equals("active-a")) sawActive = true;
        }
        assertTrue(sawActive);
    }
}
