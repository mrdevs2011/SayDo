package uz.saydo.app.metrics;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the raw metrics event log into a human-readable report.
 * Pure Java (no Android APIs) so it can be unit-tested.
 *
 * Event schema (one JSON object per line, field "e" = type):
 *   voice_heard  {text, trig ("" = no match), partial, lat}
 *   voice_error  {code, name}
 *   run_start    {run, id, trig, steps, src}
 *   run_fail     {run, i, reason}
 *   run_end      {run, ok, fails, first, ms, cancelled}
 *   verdict      {run, ok, broke}
 *   bench_voice  {exp, got, res (HIT|WRONG|MISS), lat}
 */
public final class MetricsSummary {

    private MetricsSummary() {}

    public static final class Result {
        // Voice (real usage)
        public int heardFinal;
        public int matchedFinal;
        public int matchedPartial;
        public final Map<String, Integer> errors = new LinkedHashMap<>();
        public final List<Long> matchLatencies = new ArrayList<>();

        // Macro runs
        public int runs;
        public int runsReportedOk;
        public int runsWithStepFailures;
        public int runsCancelled;
        public final Map<String, Integer> failReasons = new LinkedHashMap<>();
        public final Map<Integer, Integer> firstFailStep = new HashMap<>();

        // Human verdicts (ground truth)
        public int verdicts;
        public int verdictOk;
        /** App said OK, human said it was wrong — the "false success" count. */
        public int falseOk;
        public final Map<Integer, Integer> brokeAtStep = new HashMap<>();

        // Voice benchmark
        public int benchTrials;
        public int benchHit;
        public int benchWrong;
        public int benchMiss;
        public final List<Long> benchLatencies = new ArrayList<>();
        public final Map<String, int[]> benchPerTrigger = new LinkedHashMap<>(); // [hit, total]
    }

    public static Result compute(List<JSONObject> events) {
        Result r = new Result();
        Map<Long, Boolean> reportedOk = new HashMap<>();
        if (events == null) return r;

        for (JSONObject ev : events) {
            if (ev == null) continue;
            String e = ev.optString("e", "");
            switch (e) {
                case "voice_heard": {
                    boolean partial = ev.optBoolean("partial", false);
                    boolean matched = !ev.optString("trig", "").isEmpty();
                    if (partial) {
                        if (matched) r.matchedPartial++;
                    } else {
                        r.heardFinal++;
                        if (matched) r.matchedFinal++;
                    }
                    long lat = ev.optLong("lat", -1);
                    if (matched && lat >= 0) r.matchLatencies.add(lat);
                    break;
                }
                case "voice_error": {
                    String key = ev.optString("name", "") + "(" + ev.optInt("code", -1) + ")";
                    inc(r.errors, key);
                    break;
                }
                case "run_end": {
                    r.runs++;
                    boolean ok = ev.optBoolean("ok", false);
                    if (ok) r.runsReportedOk++;
                    if (ev.optInt("fails", 0) > 0) r.runsWithStepFailures++;
                    if (ev.optBoolean("cancelled", false)) r.runsCancelled++;
                    int first = ev.optInt("first", -1);
                    if (first >= 0) incInt(r.firstFailStep, first + 1);
                    reportedOk.put(ev.optLong("run", 0), ok);
                    break;
                }
                case "run_fail":
                    inc(r.failReasons, ev.optString("reason", "?"));
                    break;
                case "verdict": {
                    r.verdicts++;
                    boolean ok = ev.optBoolean("ok", false);
                    if (ok) {
                        r.verdictOk++;
                    } else {
                        int broke = ev.optInt("broke", -1);
                        if (broke > 0) incInt(r.brokeAtStep, broke);
                        Boolean rep = reportedOk.get(ev.optLong("run", 0));
                        if (rep != null && rep) r.falseOk++;
                    }
                    break;
                }
                case "bench_voice": {
                    r.benchTrials++;
                    String res = ev.optString("res", "");
                    String exp = ev.optString("exp", "?");
                    int[] pt = r.benchPerTrigger.get(exp);
                    if (pt == null) {
                        pt = new int[2];
                        r.benchPerTrigger.put(exp, pt);
                    }
                    pt[1]++;
                    if ("HIT".equals(res)) {
                        r.benchHit++;
                        pt[0]++;
                        long lat = ev.optLong("lat", -1);
                        if (lat >= 0) r.benchLatencies.add(lat);
                    } else if ("WRONG".equals(res)) {
                        r.benchWrong++;
                    } else {
                        r.benchMiss++;
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return r;
    }

    public static String format(Result r, String deviceInfo) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== SayDo Metrics ===\n");
        if (deviceInfo != null) sb.append(deviceInfo).append('\n');

        sb.append("\n[Ovoz benchmark]\n");
        if (r.benchTrials == 0) {
            sb.append("  hali o'tkazilmagan\n");
        } else {
            sb.append("  sinov: ").append(r.benchTrials)
              .append("  to'g'ri: ").append(r.benchHit).append(" (").append(pct(r.benchHit, r.benchTrials)).append(")")
              .append("  noto'g'ri trigger: ").append(r.benchWrong)
              .append("  eshitilmadi: ").append(r.benchMiss).append('\n');
            sb.append("  kechikish: ").append(latency(r.benchLatencies)).append('\n');
            for (Map.Entry<String, int[]> en : r.benchPerTrigger.entrySet()) {
                int[] v = en.getValue();
                sb.append("   - \"").append(en.getKey()).append("\": ")
                  .append(v[0]).append('/').append(v[1]).append(" (").append(pct(v[0], v[1])).append(")\n");
            }
        }

        sb.append("\n[Ovoz — real foydalanish]\n");
        sb.append("  yakuniy natijalar: ").append(r.heardFinal)
          .append("  trigger topildi: ").append(r.matchedFinal).append(" (").append(pct(r.matchedFinal, r.heardFinal)).append(")")
          .append("  qisman natijadan ishga tushdi: ").append(r.matchedPartial).append('\n');
        sb.append("  kechikish (gap tugashidan): ").append(latency(r.matchLatencies)).append('\n');
        if (!r.errors.isEmpty()) sb.append("  xatolar: ").append(r.errors).append('\n');

        sb.append("\n[Makro ijrosi — ilova hisobi]\n");
        sb.append("  ishga tushirildi: ").append(r.runs)
          .append("  ilova 'OK' dedi: ").append(r.runsReportedOk)
          .append("  qadam xatosi bo'lgan: ").append(r.runsWithStepFailures)
          .append("  bekor qilingan: ").append(r.runsCancelled).append('\n');
        if (!r.failReasons.isEmpty()) sb.append("  qadam xato sabablari: ").append(r.failReasons).append('\n');
        if (!r.firstFailStep.isEmpty()) sb.append("  birinchi xato qadami: ").append(sorted(r.firstFailStep)).append('\n');

        sb.append("\n[Makro ijrosi — inson tekshiruvi (haqiqiy natija)]\n");
        if (r.verdicts == 0) {
            sb.append("  hali o'tkazilmagan\n");
        } else {
            sb.append("  tekshirildi: ").append(r.verdicts)
              .append("  haqiqatan to'g'ri: ").append(r.verdictOk).append(" (").append(pct(r.verdictOk, r.verdicts)).append(")")
              .append("  yolg'on 'OK': ").append(r.falseOk).append('\n');
            if (!r.brokeAtStep.isEmpty()) sb.append("  buzilgan qadam: ").append(sorted(r.brokeAtStep)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    static String pct(int part, int total) {
        if (total <= 0) return "—";
        return String.format(Locale.US, "%.0f%%", 100.0 * part / total);
    }

    static String latency(List<Long> values) {
        if (values.isEmpty()) return "—";
        List<Long> s = new ArrayList<>(values);
        Collections.sort(s);
        return "p50=" + percentile(s, 50) + "ms p90=" + percentile(s, 90) + "ms (n=" + s.size() + ")";
    }

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static Map<Integer, Integer> sorted(Map<Integer, Integer> m) {
        return new java.util.TreeMap<>(m);
    }

    private static void inc(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static void incInt(Map<Integer, Integer> m, int k) {
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }
}
