package uz.saydo.app;

import java.util.Locale;

/**
 * Pure text formatting for MCP tool results (no Android classes, unit tested).
 * Goal: few tokens per screen. One line per element: {@code index label flags [l,t][r,b]}.
 */
final class McpFormat {
    static final int DEFAULT_ELEMENTS = 80;
    static final int MAX_LABEL = 50;

    private McpFormat() {}

    /** Single line, trimmed, cut to {@code max} characters (an ellipsis counts as one of them). */
    static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return s.length() > max ? s.substring(0, Math.max(0, max - 1)) + "…" : s;
    }

    /** Words in a fixed order; {@code checked} is null when the element is not checkable. */
    static String flags(boolean click, boolean edit, boolean scroll, Boolean checked) {
        StringBuilder sb = new StringBuilder();
        if (click) sb.append(" click");
        if (edit) sb.append(" edit");
        if (scroll) sb.append(" scroll");
        if (checked != null) sb.append(checked ? " checked" : " unchecked");
        return sb.toString();
    }

    /** Text, else description, else the short view id, else "?". */
    static String label(String text, String desc, String viewId) {
        if (text != null && !text.trim().isEmpty()) return clip(text, MAX_LABEL);
        if (desc != null && !desc.trim().isEmpty()) return clip(desc, MAX_LABEL);
        if (viewId != null && !viewId.isEmpty()) return "#" + shortId(viewId);
        return "?";
    }

    static String shortId(String viewId) {
        int slash = viewId.indexOf('/');
        return slash >= 0 ? viewId.substring(slash + 1) : viewId;
    }

    /** {@code bounds} = {l,t,r,b} or null. */
    static String line(int idx, String label, String flags, int[] bounds) {
        StringBuilder sb = new StringBuilder();
        sb.append(idx).append(' ').append(label).append(flags);
        if (bounds != null) {
            sb.append(" [").append(bounds[0]).append(',').append(bounds[1]).append("][")
              .append(bounds[2]).append(',').append(bounds[3]).append(']');
        }
        return sb.toString();
    }

    static String header(String pkg, int w, int h) {
        return (pkg == null || pkg.isEmpty() ? "?" : pkg) + " " + w + "x" + h;
    }

    /** Case-insensitive substring over the given fields; an empty query matches everything. */
    static boolean matches(String query, String... fields) {
        if (query == null || query.isEmpty()) return true;
        String q = query.toLowerCase(Locale.ROOT);
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    static int clampMax(int requested, int dflt, int hardMax) {
        if (requested <= 0) return dflt;
        return Math.min(requested, hardMax);
    }
}
