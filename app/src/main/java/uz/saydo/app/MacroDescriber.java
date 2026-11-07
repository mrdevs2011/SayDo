package uz.saydo.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.kernel.data.NodeAnchor;

/**
 * Builds a short human description of a macro from its recorded steps (pure Java, unit tested).
 * Uses app labels and the visible text of clicked buttons only. Never typed text, never anything from
 * a text field, never numbers that could be amounts or phone numbers.
 */
final class MacroDescriber {
    private MacroDescriber() {}

    /** Resolves a package name to the app's label; null when unknown. */
    interface Labels {
        String label(String pkg);
    }

    static final int MAX_LABEL = 24;
    static final int MAX_PARTS_PER_APP = 6;
    static final int MAX_APPS = 4;
    static final int MAX_BODY = 140;

    /** Launcher and system UI packages are where macros start; they are not "the app" of the macro. */
    static boolean isShellPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("launcher") || p.equals("com.android.systemui") || p.endsWith(".home")
                || p.contains("quickstep");
    }

    /** Visible text of a clicked element, or "" when it must not be used. */
    static String anchorLabel(Gesture g) {
        if (g == null || g.anchor == null || g.sensitive) return "";
        if (g.type.equals(Gesture.TYPE_TEXT)) return "";
        NodeAnchor a = g.anchor;
        if (a.isEditable() || a.getClassName().toLowerCase(Locale.ROOT).contains("edittext")) return "";
        String t = a.getText().trim();
        if (t.isEmpty()) t = a.getContentDescription().trim();
        t = t.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return "";
        if (hasManyDigits(t)) return "";
        return McpFormat.clip(t, MAX_LABEL);
    }

    private static boolean hasManyDigits(String s) {
        int digits = 0; // total, so "500 000" counts as well as "12345"
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i)) && ++digits >= 4) return true;
        }
        return false;
    }

    private static String appLabel(String pkg, Labels labels) {
        String l = labels == null ? null : labels.label(pkg);
        return l == null || l.trim().isEmpty() ? pkg : l.trim();
    }

    /** Distinct app labels in order of first use (launcher and system UI left out). */
    static List<String> appLabels(Macro m, Labels labels) {
        List<String> out = new ArrayList<>();
        for (Gesture g : m.gestures) {
            if (g.screenPkg.isEmpty() || isShellPackage(g.screenPkg)) continue;
            String l = appLabel(g.screenPkg, labels);
            if (!containsIgnoreCase(out, l)) out.add(l);
        }
        return out;
    }

    /** Clicked button texts of the whole macro, for ranking (distinct, in order). */
    static List<String> anchorTexts(Macro m) {
        List<String> out = new ArrayList<>();
        for (Gesture g : m.gestures) {
            String t = anchorLabel(g);
            if (!t.isEmpty() && !containsIgnoreCase(out, t)) out.add(t);
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> l, String s) {
        for (String x : l) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    /** For example "YouTube: Search > Library > Favourites (6 steps)". */
    static String describe(Macro m, Labels labels) {
        List<String> groupLabels = new ArrayList<>();
        List<List<String>> groupParts = new ArrayList<>();
        List<String> pending = new ArrayList<>(); // texts clicked in the launcher before the app opened
        String curPkg = null;
        for (Gesture g : m.gestures) {
            String pkg = g.screenPkg;
            String t = anchorLabel(g);
            if (pkg.isEmpty()) pkg = curPkg == null ? "" : curPkg;
            if (isShellPackage(pkg) || pkg.isEmpty()) {
                if (!t.isEmpty() && curPkg == null) pending.add(t);
                continue;
            }
            if (!pkg.equals(curPkg)) {
                curPkg = pkg;
                groupLabels.add(appLabel(pkg, labels));
                groupParts.add(new ArrayList<String>());
            }
            List<String> parts = groupParts.get(groupParts.size() - 1);
            if (!t.isEmpty() && !t.equalsIgnoreCase(groupLabels.get(groupLabels.size() - 1))
                    && !(parts.size() > 0 && parts.get(parts.size() - 1).equalsIgnoreCase(t))) {
                parts.add(t);
            }
        }
        if (!groupParts.isEmpty()) {
            for (String p : pending) {
                if (!p.equalsIgnoreCase(groupLabels.get(0))) groupParts.get(0).add(0, p);
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < groupLabels.size() && i < MAX_APPS; i++) {
            if (i > 0) body.append("; ");
            body.append(groupLabels.get(i));
            List<String> parts = groupParts.get(i);
            if (!parts.isEmpty()) body.append(": ");
            for (int k = 0; k < parts.size() && k < MAX_PARTS_PER_APP; k++) {
                if (k > 0) body.append(" > ");
                body.append(parts.get(k));
            }
        }
        if (groupLabels.isEmpty() && !pending.isEmpty()) {
            for (int k = 0; k < pending.size() && k < MAX_PARTS_PER_APP; k++) {
                if (k > 0) body.append(" > ");
                body.append(pending.get(k));
            }
        }
        String b = body.toString();
        if (b.length() > MAX_BODY) b = b.substring(0, MAX_BODY - 1) + "…";
        int n = m.gestures.size();
        String steps = n + (n == 1 ? " step" : " steps");
        return b.isEmpty() ? steps : b + " (" + steps + ")";
    }

    /** One line per step for describe_macro: "1 tap YouTube 'Search'". Never text typed by the owner. */
    static List<String> stepLines(Macro m, Labels labels, int max) {
        List<String> out = new ArrayList<>();
        String lastPkg = "";
        int i = 0;
        for (Gesture g : m.gestures) {
            i++;
            if (out.size() >= max) {
                out.add("+" + (m.gestures.size() - i + 1) + " more");
                break;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(i).append(' ');
            String kind = g.type;
            if (g.type.equals(Gesture.TYPE_GLOBAL) && !g.action.isEmpty()) kind = g.action;
            if (g.type.equals(Gesture.TYPE_WAIT_USER)) kind = "wait_user (owner: " + g.action + ")";
            sb.append(kind);
            if (!g.variableSlot.isEmpty()) sb.append(" {").append(g.variableSlot).append('}');
            String t = anchorLabel(g);
            if (!t.isEmpty()) sb.append(" '").append(t).append('\'');
            else if (g.anchor == null && g.type.equals(Gesture.TYPE_TAP)) sb.append(" (position)");
            if (!g.screenPkg.isEmpty() && !g.screenPkg.equals(lastPkg)) {
                sb.append(" @").append(appLabel(g.screenPkg, labels));
                lastPkg = g.screenPkg;
            }
            out.add(sb.toString());
        }
        return out;
    }
}
