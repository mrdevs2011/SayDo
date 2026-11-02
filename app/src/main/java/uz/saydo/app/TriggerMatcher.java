package uz.saydo.app;

import java.util.List;
import java.util.Locale;

/**
 * Aytilgan gapni trigger bilan solishtiradi.
 * - Kirill va lotin yozuvi bir xil ko'rinishga keltiriladi (o', g' belgilari ham).
 * - Aniq moslik, so'z ichida uchrashi va noaniq (Levenshtein) moslik qo'llab-quvvatlanadi.
 */
final class TriggerMatcher {
    static final double EXACT = 0.95;
    static final double FUZZY = 0.80;

    private TriggerMatcher() {}

    private static String translit(char c) {
        switch (c) {
            case 'а': return "a";
            case 'б': return "b";
            case 'в': return "v";
            case 'г': return "g";
            case 'д': return "d";
            case 'е': return "e";
            case 'ё': return "yo";
            case 'ж': return "j";
            case 'з': return "z";
            case 'и': return "i";
            case 'й': return "y";
            case 'к': return "k";
            case 'л': return "l";
            case 'м': return "m";
            case 'н': return "n";
            case 'о': return "o";
            case 'п': return "p";
            case 'р': return "r";
            case 'с': return "s";
            case 'т': return "t";
            case 'у': return "u";
            case 'ф': return "f";
            case 'х': return "x";
            case 'ц': return "ts";
            case 'ч': return "ch";
            case 'ш': return "sh";
            case 'щ': return "sh";
            case 'ъ': return "";
            case 'ы': return "i";
            case 'ь': return "";
            case 'э': return "e";
            case 'ю': return "yu";
            case 'я': return "ya";
            case 'ў': return "o";
            case 'қ': return "q";
            case 'ғ': return "g";
            case 'ҳ': return "h";
            default: return null;
        }
    }

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '\u02BB' || c == '\u02BC' || c == '\u2019'
                || c == '\u2018' || c == '`' || c == '\u00B4';
    }

    /** Kichik harf, kirill -> lotin, tinish belgilari va apostroflarsiz, bitta bo'shliqlar. */
    static String norm(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length() + 4);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isApostrophe(c)) continue;
            String t = translit(c);
            if (t != null) {
                sb.append(t);
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        String[] parts = sb.toString().trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(p);
        }
        return out.toString();
    }

    private static int lev(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[m];
    }

    private static double sim(String a, String b) {
        int mx = Math.max(a.length(), b.length());
        if (mx == 0) return 1.0;
        return 1.0 - (double) lev(a, b) / mx;
    }

    /** Ikkalasi ham norm() dan o'tgan bo'lishi kerak. 0..1 oralig'ida ball qaytaradi. */
    static double score(String heard, String trigger) {
        if (heard.isEmpty() || trigger.isEmpty()) return 0;
        if (heard.equals(trigger)) return 1.0;
        if ((" " + heard + " ").contains(" " + trigger + " ")) return 0.97;

        String hNo = heard.replace(" ", "");
        String tNo = trigger.replace(" ", "");
        if (tNo.length() >= 4 && hNo.contains(tNo)) return 0.96;
        if (tNo.length() < 3) return 0;

        double best = Math.max(sim(heard, trigger), sim(hNo, tNo));
        String[] hw = heard.split(" ");
        int n = trigger.split(" ").length;
        for (int size = Math.max(1, n - 1); size <= n + 1; size++) {
            for (int i = 0; i + size <= hw.length; i++) {
                StringBuilder w = new StringBuilder();
                for (int k = i; k < i + size; k++) {
                    if (k > i) w.append(' ');
                    w.append(hw[k]);
                }
                best = Math.max(best, sim(w.toString(), trigger));
            }
        }
        return best;
    }

    /**
     * @param heard     tanib olingan variantlar (xom)
     * @param triggers  norm() dan o'tgan triggerlar
     * @param threshold minimal ball
     * @return eng mos trigger indeksi yoki -1
     */
    static int best(List<String> heard, List<String> triggers, double threshold) {
        int bestIdx = -1;
        double bestScore = 0;
        int bestLen = 0;
        for (int i = 0; i < triggers.size(); i++) {
            String t = triggers.get(i);
            if (t.isEmpty()) continue;
            for (String raw : heard) {
                double s = score(norm(raw), t);
                if (s < threshold) continue;
                boolean better = s > bestScore + 1e-9
                        || (Math.abs(s - bestScore) <= 1e-9 && t.length() > bestLen);
                if (better) {
                    bestScore = s;
                    bestIdx = i;
                    bestLen = t.length();
                }
            }
        }
        return bestIdx;
    }
}
