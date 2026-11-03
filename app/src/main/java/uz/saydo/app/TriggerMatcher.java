package uz.saydo.app;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Aytilgan gapni trigger bilan solishtiradi (faqat inglizcha).
 * - Kichik harf, tinish belgilari va apostroflarsiz.
 * - Raqam so'zlari va homofonlar bir xil ko'rinishga keltiriladi (two/to/too, four/for, one/won ...).
 * - Bo'sh so'zlar (please, the, okay ...) e'tiborga olinmaydi.
 * - Aniq moslik, ibora ichida uchrashi, fonetik moslik va noaniq (Levenshtein) moslik.
 */
final class TriggerMatcher {
    static final double EXACT = 0.95;
    static final double FUZZY = 0.80;
    private static final double PHONETIC = 0.93;
    private static final double PHONETIC_WINDOW = 0.92;

    private static final Map<String, String> CANON = new HashMap<String, String>();
    private static final Set<String> FILLER = new HashSet<String>();

    private static void alias(String canon, String... words) {
        for (String w : words) CANON.put(w, canon);
    }

    static {
        alias("0", "zero");
        alias("1", "one", "won");
        alias("2", "two", "to", "too");
        alias("3", "three");
        alias("4", "four", "for", "fore");
        alias("5", "five");
        alias("6", "six");
        alias("7", "seven");
        alias("8", "eight", "ate");
        alias("9", "nine");
        alias("10", "ten");
        alias("11", "eleven");
        alias("12", "twelve");
        alias("13", "thirteen");
        alias("14", "fourteen");
        alias("15", "fifteen");
        alias("16", "sixteen");
        alias("17", "seventeen");
        alias("18", "eighteen");
        alias("19", "nineteen");
        alias("20", "twenty");
        alias("30", "thirty");
        alias("40", "forty");
        alias("50", "fifty");
        alias("60", "sixty");
        alias("70", "seventy");
        alias("80", "eighty");
        alias("90", "ninety");
        alias("100", "hundred");
        alias("no", "know");
        alias("right", "write");
        alias("by", "buy", "bye");
        alias("here", "hear");
        alias("there", "their", "theyre");
        alias("your", "youre");
        alias("new", "knew");
        alias("see", "sea");
        alias("weather", "whether");
        alias("mail", "male");
        alias("read", "red");
        alias("wait", "weight");
        alias("pause", "paws");
        alias("sun", "son");
        alias("whats", "whatsup");

        String[] filler = {"the", "a", "an", "please", "hey", "ok", "okay", "um", "uh", "uhm", "hmm", "er", "ah"};
        for (String f : filler) FILLER.add(f);
    }

    private TriggerMatcher() {}

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '\u02BB' || c == '\u02BC' || c == '\u2019'
                || c == '\u2018' || c == '`' || c == '\u00B4';
    }

    /** Kichik harf, tinish belgilarsiz, homofon/raqam kanonik shaklda, bo'sh so'zlarsiz. */
    static String norm(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length() + 4);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isApostrophe(c)) continue;
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else sb.append(' ');
        }
        String[] parts = sb.toString().trim().split("\\s+");
        StringBuilder all = new StringBuilder();
        StringBuilder kept = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            String c = CANON.containsKey(p) ? CANON.get(p) : p;
            if (all.length() > 0) all.append(' ');
            all.append(c);
            if (FILLER.contains(p)) continue;
            if (kept.length() > 0) kept.append(' ');
            kept.append(c);
        }
        // Faqat bo'sh so'zlardan iborat bo'lsa, asl holini qoldiramiz
        return kept.length() > 0 ? kept.toString() : all.toString();
    }

    /** Soddalashtirilgan Metaphone: bir xil eshitiladigan so'zlar bir xil kod beradi. */
    static String phon(String w) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetterOrDigit(c)) in.append(Character.toLowerCase(c));
        }
        String s = in.toString();
        if (s.isEmpty()) return "";
        if (s.startsWith("kn") || s.startsWith("gn") || s.startsWith("pn") || s.startsWith("wr")) s = s.substring(1);
        if (s.startsWith("x")) s = "s" + s.substring(1);
        if (s.startsWith("wh")) s = "w" + s.substring(2);
        StringBuilder out = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            char prev = i > 0 ? s.charAt(i - 1) : 0;
            char next = i + 1 < n ? s.charAt(i + 1) : 0;
            if (c == prev && c != 'c') continue;
            switch (c) {
                case 'a': case 'e': case 'i': case 'o': case 'u':
                    if (i == 0) out.append('a');
                    break;
                case 'b':
                    if (!(prev == 'm' && i == n - 1)) out.append('b');
                    break;
                case 'c':
                    if (next == 'h') { out.append('x'); i++; }
                    else if (next == 'i' || next == 'e' || next == 'y') out.append('s');
                    else out.append('k');
                    break;
                case 'd':
                    if (next == 'g' && i + 2 < n && "eiy".indexOf(s.charAt(i + 2)) >= 0) { out.append('j'); i++; }
                    else out.append('t');
                    break;
                case 'g':
                    if (next == 'h') {
                        if (i + 2 >= n || "aeiou".indexOf(s.charAt(i + 2)) < 0) { i++; break; }
                        out.append('k'); i++;
                        break;
                    }
                    if (next == 'n' && i + 2 >= n) break;
                    if (next == 'i' || next == 'e' || next == 'y') out.append('j');
                    else out.append('k');
                    break;
                case 'h':
                    if ("aeiou".indexOf(next) >= 0 && "csptg".indexOf(prev) < 0) out.append('h');
                    break;
                case 'k':
                    if (prev != 'c') out.append('k');
                    break;
                case 'p':
                    if (next == 'h') { out.append('f'); i++; }
                    else out.append('p');
                    break;
                case 'q':
                    out.append('k');
                    break;
                case 's':
                    if (next == 'h') { out.append('x'); i++; }
                    else out.append('s');
                    break;
                case 't':
                    if (next == 'h') { out.append('0'); i++; }
                    else out.append('t');
                    break;
                case 'v':
                    out.append('f');
                    break;
                case 'w': case 'y':
                    if ("aeiou".indexOf(next) >= 0) out.append(c);
                    break;
                case 'x':
                    out.append("ks");
                    break;
                case 'z':
                    out.append('s');
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static String phonPhrase(String phrase) {
        StringBuilder sb = new StringBuilder();
        for (String w : phrase.split(" ")) sb.append(phon(w));
        return sb.toString();
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
        String tPhon = phonPhrase(trigger);
        boolean phonOk = tPhon.length() >= 3;
        if (phonOk && tPhon.equals(phonPhrase(heard))) best = Math.max(best, PHONETIC);

        for (int size = Math.max(1, n - 1); size <= n + 1; size++) {
            for (int i = 0; i + size <= hw.length; i++) {
                StringBuilder w = new StringBuilder();
                for (int k = i; k < i + size; k++) {
                    if (k > i) w.append(' ');
                    w.append(hw[k]);
                }
                String win = w.toString();
                best = Math.max(best, sim(win, trigger));
                if (phonOk && tPhon.equals(phonPhrase(win))) best = Math.max(best, PHONETIC_WINDOW);
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

    /** Boshqa, uzunroq trigger shu triggerni so'z sifatida o'z ichiga oladimi (qisman natijada kutish kerak). */
    static boolean hasLongerSibling(List<String> triggers, int idx) {
        if (idx < 0 || idx >= triggers.size()) return false;
        String t = triggers.get(idx);
        for (int i = 0; i < triggers.size(); i++) {
            if (i == idx) continue;
            String o = triggers.get(i);
            if (o.length() > t.length() && (" " + o + " ").contains(" " + t + " ")) return true;
        }
        return false;
    }
}
