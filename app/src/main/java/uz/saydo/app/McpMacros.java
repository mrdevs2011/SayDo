package uz.saydo.app;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.metrics.Metrics;
import uz.saydo.app.util.DebugLog;

/**
 * The "macro first" tools: find_macro, list_macros, describe_macro, set_macro_info, run_macro.
 * Android glue around the pure logic in MacroSearch, MacroDescriber and McpGuard.
 * Results are short plain text on purpose (tokens). Not verified on a phone.
 */
final class McpMacros {
    private final Context ctx;
    private final McpConfig cfg;
    private final Map<String, String> labelCache = new HashMap<>();

    McpMacros(Context ctx, McpConfig cfg) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
    }

    private final MacroDescriber.Labels labels = new MacroDescriber.Labels() {
        @Override public String label(String pkg) {
            synchronized (labelCache) {
                if (labelCache.containsKey(pkg)) return labelCache.get(pkg);
                String l = null;
                try {
                    PackageManager pm = ctx.getPackageManager();
                    CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                    if (cs != null && cs.length() > 0) l = cs.toString();
                } catch (Throwable ignored) {
                    // not installed (any more): the package name is used instead
                }
                labelCache.put(pkg, l);
                return l;
            }
        }
    };

    MacroDescriber.Labels labels() { return labels; }

    // ------------------------------------------------------------------ recording hook

    /** At the end of a recording: adds the generated description and detects money apps. Never throws. */
    static Macro withAutoInfo(Context ctx, Macro m) {
        try {
            McpMacros h = new McpMacros(ctx, new McpConfig(ctx));
            String d = m.description.isEmpty() ? MacroDescriber.describe(m, h.labels) : m.description;
            return m.withInfo(d, m.aliases, m.sensitive || McpGuard.touchesMoney(m), m.sensitiveReviewed);
        } catch (Throwable t) {
            DebugLog.e("MCP", "auto info failed: " + t.getClass().getSimpleName());
            return m;
        }
    }

    // ------------------------------------------------------------------ catalog

    /** What the AI may know about: macros with a PIN step are left out everywhere (list, find, describe, run). */
    private List<Macro> all() {
        List<Macro> out = new ArrayList<>();
        for (Macro m : MacroRepository.getInstance(ctx).getAll()) if (!McpGuard.hiddenFromAi(m)) out.add(m);
        return out;
    }

    /** Description shown to the AI: the owner's/AI's text, else generated from the steps. */
    private String descriptionOf(Macro m) {
        return m.description.isEmpty() ? MacroDescriber.describe(m, labels) : m.description;
    }

    private MacroSearch.Doc doc(Macro m) {
        return new MacroSearch.Doc(m.id, m.trigger, m.aliases, descriptionOf(m), MacroDescriber.appLabels(m, labels),
                MacroDescriber.anchorTexts(m), m.category, m.usageCount);
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** Resolves id, unique id prefix, exact trigger or alias, else a clearly best fuzzy match. */
    Macro resolve(String key) throws McpCommands.Fail {
        if (key == null || key.trim().isEmpty()) throw new McpCommands.Fail("Give a macro id or trigger.");
        List<Macro> all = all();
        String k = key.trim();
        for (Macro m : all) if (m.id.equals(k)) return m;
        if (k.length() >= 6) {
            Macro pre = null;
            int n = 0;
            for (Macro m : all) {
                if (m.id.startsWith(k)) {
                    pre = m;
                    n++;
                }
            }
            if (n == 1) return pre;
        }
        String nk = TriggerMatcher.norm(k);
        for (Macro m : all) {
            if (TriggerMatcher.norm(m.trigger).equals(nk)) return m;
            for (String a : m.aliases) if (TriggerMatcher.norm(a).equals(nk)) return m;
        }
        List<MacroSearch.Doc> docs = new ArrayList<>();
        for (Macro m : all) docs.add(doc(m));
        List<MacroSearch.Hit> hits = MacroSearch.rank(k, docs, 3, MacroSearch.MIN_SCORE);
        if (!hits.isEmpty() && hits.get(0).score >= 0.75
                && (hits.size() < 2 || hits.get(0).score - hits.get(1).score >= 0.1)) {
            for (Macro m : all) if (m.id.equals(hits.get(0).doc.id)) return m;
        }
        StringBuilder close = new StringBuilder();
        for (MacroSearch.Hit h : hits) {
            if (close.length() > 0) close.append(", ");
            close.append('"').append(h.doc.trigger).append('"');
        }
        throw new McpCommands.Fail("No macro clearly matches \"" + McpFormat.clip(k, 40) + "\""
                + (hits.size() > 1 && hits.get(0).score >= 0.75 ? " (several fit equally: " + close + ")"
                : hits.isEmpty() ? "" : " (closest: " + close + ")")
                + ". Use find_macro with the goal, or list_macros.");
    }

    // ------------------------------------------------------------------ tools

    String findMacro(JSONObject a) throws McpCommands.Fail {
        String q = a.optString("query", "").trim();
        if (q.isEmpty()) throw new McpCommands.Fail("Give query: what the owner wants done.");
        int limit = Math.max(1, Math.min(3, a.optInt("limit", 3)));
        List<Macro> all = all();
        if (all.isEmpty()) return "none: no macros recorded. Drive the phone with act.";
        List<MacroSearch.Doc> docs = new ArrayList<>();
        Map<String, Macro> byId = new HashMap<>();
        for (Macro m : all) {
            docs.add(doc(m));
            byId.put(m.id, m);
        }
        List<MacroSearch.Hit> hits = MacroSearch.rank(q, docs, limit, MacroSearch.MIN_SCORE);
        if (hits.isEmpty()) return "none: no macro fits. Try another phrasing or drive the phone with act.";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (MacroSearch.Hit h : hits) {
            Macro m = byId.get(h.doc.id);
            sb.append(++i).append(". ").append(String.format(Locale.US, "%.2f", h.score))
              .append(" id=").append(shortId(m.id))
              .append(" \"").append(m.trigger).append('"');
            if (McpGuard.isSensitive(m)) sb.append(" SENSITIVE");
            if (!h.doc.apps.isEmpty()) sb.append(" apps=").append(join(h.doc.apps));
            sb.append("\n   ").append(McpFormat.clip(h.doc.description, 140)).append('\n');
        }
        sb.append(hits.get(0).score >= MacroSearch.GOOD ? "best looks good: run_macro it."
                : "weak match: try one other phrasing, else use act.");
        return sb.toString();
    }

    String listMacros(JSONObject a) {
        List<Macro> all = all();
        if (all.isEmpty()) return "No macros recorded on this phone.";
        int limit = Math.max(1, Math.min(50, a.optInt("limit", 20)));
        int offset = Math.max(0, a.optInt("offset", 0));
        StringBuilder sb = new StringBuilder();
        int end = Math.min(all.size(), offset + limit);
        for (int i = offset; i < end; i++) {
            Macro m = all.get(i);
            sb.append(shortId(m.id)).append(" \"").append(m.trigger).append('"');
            if (McpGuard.isSensitive(m)) sb.append(" SENSITIVE");
            if (m.locked) sb.append(" lock-screen");
            List<String> apps = MacroDescriber.appLabels(m, labels);
            if (!apps.isEmpty()) sb.append(" | ").append(join(apps));
            sb.append(" | ").append(McpFormat.clip(descriptionOf(m), 70)).append('\n');
        }
        sb.append(offset >= all.size() ? "(offset past the end) " : "")
          .append(all.size()).append(" macros");
        if (end < all.size()) sb.append("; next offset=").append(end);
        return sb.toString();
    }

    String describeMacro(JSONObject a) throws McpCommands.Fail {
        Macro m = resolve(a.optString("macro", ""));
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(shortId(m.id)).append(" \"").append(m.trigger).append("\"\n");
        sb.append(McpFormat.clip(descriptionOf(m), 200)).append('\n');
        if (!m.aliases.isEmpty()) sb.append("aliases: ").append(join(m.aliases)).append('\n');
        List<String> apps = MacroDescriber.appLabels(m, labels);
        if (!apps.isEmpty()) sb.append("apps: ").append(join(apps)).append('\n');
        boolean sens = McpGuard.isSensitive(m);
        sb.append("sensitive: ").append(sens ? "yes" : "no")
          .append(sens && !m.sensitive ? " (money app detected)" : "").append('\n');
        List<String> slots = new ArrayList<>();
        for (Gesture g : m.gestures) if (!g.variableSlot.isEmpty() && !slots.contains(g.variableSlot)) slots.add(g.variableSlot);
        sb.append("params: ").append(slots.isEmpty() ? "none" : join(slots)).append('\n');
        sb.append("runs: ").append(m.usageCount).append("; success/fail counts are not stored\n");
        for (String line : MacroDescriber.stepLines(m, labels, 30)) sb.append(line).append('\n');
        return sb.toString().trim();
    }

    /** Metadata only. The AI may add aliases and set sensitive, never clear it. */
    String setMacroInfo(JSONObject a) throws McpCommands.Fail {
        Macro m = resolve(a.optString("macro", ""));
        String desc = m.description;
        List<String> aliases = new ArrayList<>(m.aliases);
        boolean sens = m.sensitive;
        List<String> notes = new ArrayList<>();
        if (a.has("description")) {
            desc = McpFormat.clip(a.optString("description", ""), 300);
            notes.add("description set");
        }
        JSONArray arr = a.optJSONArray("aliases");
        if (arr != null) {
            int added = 0;
            for (int i = 0; i < arr.length() && aliases.size() < 12; i++) {
                String al = McpFormat.clip(arr.optString(i, ""), 60).toLowerCase(Locale.ROOT);
                if (!al.isEmpty() && !aliases.contains(al)) {
                    aliases.add(al);
                    added++;
                }
            }
            notes.add(added + " aliases added");
        }
        if (a.has("sensitive")) {
            if (a.optBoolean("sensitive", false)) {
                sens = true;
                notes.add("marked sensitive");
            } else {
                notes.add("sensitive NOT cleared: only the owner can do that in the app");
            }
        }
        if (notes.isEmpty()) throw new McpCommands.Fail("Nothing to change: give description, aliases or sensitive=true.");
        MacroRepository.getInstance(ctx).save(m.withInfo(desc, aliases, sens, m.sensitiveReviewed));
        return "ok: " + join(notes);
    }

    // ------------------------------------------------------------------ run

    String runMacro(JSONObject a) throws Exception {
        final Macro m = resolve(a.optString("macro", ""));
        McpCommands.service(); // accessibility must be on
        final long t0 = System.currentTimeMillis();

        McpGuard.RunDecision d = McpGuard.decideRun(McpGuard.isSensitive(m), a.optBoolean("confirm", false),
                cfg.approveSensitiveOnPhone());
        if (d == McpGuard.RunDecision.NEED_CONFIRM) {
            throw new McpCommands.Fail(McpGuard.NEED_CONFIRM_MESSAGE + " Macro: \"" + m.trigger + "\".");
        }
        if (d == McpGuard.RunDecision.NEED_PHONE_APPROVAL && !McpApproval.ask(ctx, m.trigger)) {
            throw new McpCommands.Fail(McpGuard.DENIED_ON_PHONE_MESSAGE);
        }

        final JSONObject json = withParams(m, a.optJSONObject("params"));
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] completed = {false};
        int code = McpCommands.onMain(5000, () -> {
            Metrics.nextRunSource = "mcp";
            return Runner.run(ctx, json, ok -> {
                completed[0] = ok;
                done.countDown();
            });
        });
        if (code == Runner.NO_ACCESSIBILITY) throw new McpCommands.Fail("Accessibility is off.");
        if (code == Runner.LOCKED) throw new McpCommands.Fail("The phone is locked; this macro needs it unlocked.");
        if (code != Runner.OK) throw new McpCommands.Fail("Macro could not start (code " + code + ").");
        long waitMs = Math.max(10_000, 230_000 - (System.currentTimeMillis() - t0));
        if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
            return "still running after 230 s: \"" + m.trigger + "\"" + inFront();
        }
        if (completed[0]) return "completed \"" + m.trigger + "\" (" + m.gestures.size() + " steps)" + inFront();
        GestureService s = GestureService.instance;
        int f = s == null ? -1 : s.lastRunFirstFail();
        String line = "";
        if (f >= 0) {
            List<String> lines = MacroDescriber.stepLines(m, labels, Math.max(1, f + 1));
            if (f < lines.size()) line = lines.get(f);
        }
        return StopReason.message(m.trigger, f, m.gestures.size(), line, s == null ? "" : s.lastStopReason(),
                inFront());
    }

    private String inFront() {
        try {
            final GestureService s = GestureService.instance;
            if (s == null) return "";
            String pkg = McpCommands.onMain(3000, s::foregroundAppPkg);
            return pkg == null || pkg.isEmpty() ? "" : "; app in front: " + pkg;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Macro as JSON with parameter values put into the steps whose slot name is given.
     * Only changes "txt" of steps that already have a "var" slot; never the steps' kind or targets.
     * Note: the recorder does not create such steps yet, so today params only apply to macros that
     * were built with slots (see docs/MCP.md, open items).
     */
    private JSONObject withParams(Macro m, JSONObject params) throws Exception {
        JSONObject json = m.toJson();
        if (params == null || params.length() == 0) return json;
        JSONArray gs = json.optJSONArray("gestures");
        int used = 0;
        for (int i = 0; gs != null && i < gs.length(); i++) {
            JSONObject g = gs.optJSONObject(i);
            if (g == null) continue;
            String slot = g.optString("var", "");
            if (!slot.isEmpty() && params.has(slot)) {
                g.put("txt", params.optString(slot, ""));
                used++;
            }
        }
        if (used == 0) {
            throw new McpCommands.Fail("This macro has no parameter slots matching " + params.names()
                    + ". Run it without params or use describe_macro.");
        }
        return json;
    }
}
