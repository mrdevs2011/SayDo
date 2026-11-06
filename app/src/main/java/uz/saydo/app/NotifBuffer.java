package uz.saydo.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * In-memory ring buffer of recent notifications plus the pure filtering/formatting for the
 * read_notifications tool. Never persisted; the content must not be logged.
 */
final class NotifBuffer {
    static final int CAPACITY = 100;
    static final int DEFAULT_LIMIT = 15;
    static final int MAX_LIMIT = 50;
    static final int TEXT_MAX = 120;
    static final int TITLE_MAX = 60;

    static final class Entry {
        final String key;
        final long time;
        final String pkg, label, title, text;

        Entry(String key, long time, String pkg, String label, String title, String text) {
            this.key = key;
            this.time = time;
            this.pkg = pkg == null ? "" : pkg;
            this.label = label == null ? "" : label;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }
    }

    private final int cap;
    private final LinkedList<Entry> items = new LinkedList<>(); // newest first

    NotifBuffer(int cap) {
        this.cap = cap;
    }

    /** Same key = the notification was updated: the old entry is replaced. */
    synchronized void add(Entry e) {
        if (e.key != null) {
            for (int i = 0; i < items.size(); i++) {
                if (e.key.equals(items.get(i).key)) {
                    items.remove(i);
                    break;
                }
            }
        }
        items.addFirst(e);
        while (items.size() > cap) items.removeLast();
    }

    synchronized void clear() {
        items.clear();
    }

    synchronized List<Entry> snapshot() {
        return new ArrayList<>(items);
    }

    /** Own notifications, ongoing/foreground-service ones and group summaries are ignored. */
    static boolean accept(boolean own, boolean ongoing, boolean foregroundService, boolean groupSummary) {
        return !own && !ongoing && !foregroundService && !groupSummary;
    }

    /** Buffer plus the notifications active right now; an active one replaces the buffered one with its key. */
    static List<Entry> merge(List<Entry> buffered, List<Entry> active) {
        List<Entry> all = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Entry e : active) {
            if (e.key != null && !keys.add(e.key)) continue;
            all.add(e);
        }
        for (Entry e : buffered) {
            if (e.key != null && !keys.add(e.key)) continue;
            all.add(e);
        }
        return all;
    }

    /** Newest first, filtered; one line each: {@code HH:mm Label: title - text}. */
    static List<String> query(List<Entry> all, int limit, String app, int sinceMinutes, long nowMs, TimeZone tz) {
        int lim = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String a = app == null ? "" : app.trim();
        List<Entry> sel = new ArrayList<>();
        for (Entry e : all) {
            if (sinceMinutes > 0 && e.time < nowMs - sinceMinutes * 60_000L) continue;
            if (!a.isEmpty() && !McpFormat.matches(a, e.pkg, e.label)) continue;
            sel.add(e);
        }
        Collections.sort(sel, (x, y) -> Long.compare(y.time, x.time));
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(tz);
        List<String> out = new ArrayList<>();
        for (Entry e : sel) {
            if (out.size() >= lim) break;
            out.add(line(e, f));
        }
        return out;
    }

    private static String line(Entry e, SimpleDateFormat f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.format(new Date(e.time))).append(' ')
          .append(e.label.isEmpty() ? e.pkg : e.label).append(':');
        String title = McpFormat.clip(e.title, TITLE_MAX);
        String text = McpFormat.clip(e.text, TEXT_MAX);
        if (!title.isEmpty()) sb.append(' ').append(title);
        if (!text.isEmpty()) sb.append(title.isEmpty() ? " " : " - ").append(text);
        return sb.toString();
    }
}
