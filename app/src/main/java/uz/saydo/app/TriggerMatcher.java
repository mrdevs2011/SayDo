package uz.saydo.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Name matching (normalising and similarity) used by macro search.
 * Features Damerau-Levenshtein edit distance (with transposition detection),
 * comprehensive phonetic matching (Metaphone + Soundex hybrid), contraction expansion,
 * homophone canonicalization, token windowing, and sibling prefix disambiguation.
 */
public final class TriggerMatcher {
    public static final double EXACT = 0.95;
    public static final double FUZZY = 0.80;
    private static final double PHONETIC = 0.93;
    private static final double PHONETIC_WINDOW = 0.92;

    private static final Map<String, String> CANON = new HashMap<>();
    private static final Map<String, String> CONTRACTIONS = new HashMap<>();
    private static final Set<String> FILLER = new HashSet<>();

    // Multi-word spellings joined before tokenizing. Single-word variants ("utube", "yt")
    // are handled per token by CANON. Lookarounds keep matches on whole words and let
    // adjacent matches share the separating space.
    private static final java.util.regex.Pattern MULTIWORD_WIFI =
            java.util.regex.Pattern.compile("(?<= )wi fi(?= )");
    private static final java.util.regex.Pattern MULTIWORD_YOUTUBE =
            java.util.regex.Pattern.compile("(?<= )(?:u|you|yu) tube(?= )|(?<= )you tobe(?= )");

    private static void alias(String canon, String... words) {
        for (String w : words) CANON.put(w, canon);
    }

    static {
        // Numbers & Homophones
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

        // Extended Homophones & English Phonetic Twins
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
        alias("app", "application");
        alias("youtube", "yt", "u tube", "you tube", "youtub", "utube", "you tobe", "yu tube");
        alias("wifi", "wi fi");
        alias("hello", "hi");
        alias("off", "of");
        alias("hour", "our");
        alias("peace", "piece");
        alias("which", "witch");
        alias("plain", "plane");
        alias("break", "brake");
        alias("tail", "tale");
        alias("stair", "stare");
        alias("meet", "meat");
        alias("flower", "flour");
        alias("bear", "bare");
        alias("allowed", "aloud");
        alias("sell", "cell");
        alias("sense", "cents");
        alias("course", "coarse");
        alias("dear", "deer");
        alias("die", "dye");
        alias("fair", "fare");
        alias("heal", "heel");
        alias("idle", "idol");
        alias("night", "knight");
        alias("not", "knot");
        alias("leak", "leek");
        alias("loan", "lone");
        alias("made", "maid");
        alias("main", "mane");
        alias("pale", "pail");
        alias("past", "passed");
        alias("poor", "pour");
        alias("pray", "prey");
        alias("principal", "principle");
        alias("profit", "prophet");
        alias("real", "reel");
        alias("role", "roll");
        alias("route", "root");
        alias("sale", "sail");
        alias("scene", "seen");
        alias("soul", "sole");
        alias("some", "sum");
        alias("steel", "steal");
        alias("sweet", "suite");
        alias("toe", "tow");
        alias("waste", "waist");
        alias("week", "weak");

        // Contractions (expanded into canonical words)
        CONTRACTIONS.put("cannot", "cannot");
        CONTRACTIONS.put("cant", "cannot");
        CONTRACTIONS.put("wont", "will not");
        CONTRACTIONS.put("dont", "do not");
        CONTRACTIONS.put("doesnt", "does not");
        CONTRACTIONS.put("didnt", "did not");
        CONTRACTIONS.put("isnt", "is not");
        CONTRACTIONS.put("arent", "are not");
        CONTRACTIONS.put("wasnt", "was not");
        CONTRACTIONS.put("werent", "were not");
        CONTRACTIONS.put("havent", "have not");
        CONTRACTIONS.put("hasnt", "has not");
        CONTRACTIONS.put("hadnt", "had not");
        CONTRACTIONS.put("shouldnt", "should not");
        CONTRACTIONS.put("couldnt", "could not");
        CONTRACTIONS.put("wouldnt", "would not");
        CONTRACTIONS.put("lets", "let us");
        CONTRACTIONS.put("its", "its");
        CONTRACTIONS.put("im", "i am");
        CONTRACTIONS.put("youre", "your");
        CONTRACTIONS.put("theyre", "there");
        CONTRACTIONS.put("were", "we are");
        CONTRACTIONS.put("thats", "that is");
        CONTRACTIONS.put("whats", "what is");
        CONTRACTIONS.put("theres", "there is");
        CONTRACTIONS.put("whos", "who is");

        // Conversational Fillers & Carrier Particles
        String[] fillers = {"the", "a", "an", "please", "hey", "ok", "okay", "um", "uh", "uhm", "hmm", "er", "ah"};
        Collections.addAll(FILLER, fillers);
    }

    private TriggerMatcher() {}

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '\u02BB' || c == '\u02BC' || c == '\u2019'
                || c == '\u2018' || c == '`' || c == '\u00B4';
    }

    /**
     * Normalizes an utterance: removes punctuation, expands contractions,
     * canonicalizes homophones/numbers, and strips extraneous conversational fillers.
     */
    public static String norm(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length() + 8);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isApostrophe(c)) continue;
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else sb.append(' ');
        }

        // Whole-word joins only. A plain substring replace turned "youtube" into
        // "yoyoutube" ("utube" inside it), so "yt" (-> "youtube") no longer matched.
        String cleaned = " " + sb.toString().trim().replaceAll("\\s+", " ") + " ";
        cleaned = MULTIWORD_WIFI.matcher(cleaned).replaceAll("wifi");
        cleaned = MULTIWORD_YOUTUBE.matcher(cleaned).replaceAll("youtube");
        cleaned = cleaned.trim();

        String[] rawParts = cleaned.split("\\s+");
        StringBuilder all = new StringBuilder();
        StringBuilder kept = new StringBuilder();

        for (String p : rawParts) {
            if (p.isEmpty()) continue;
            // Check contraction expansions
            String expanded = CONTRACTIONS.containsKey(p) ? CONTRACTIONS.get(p) : p;
            for (String sub : expanded.split("\\s+")) {
                if (sub.isEmpty()) continue;
                String c = CANON.containsKey(sub) ? CANON.get(sub) : sub;
                if (all.length() > 0) all.append(' ');
                all.append(c);
                if (FILLER.contains(sub)) continue;
                if (kept.length() > 0) kept.append(' ');
                kept.append(c);
            }
        }

        return kept.length() > 0 ? kept.toString() : all.toString();
    }

    /**
     * Damerau-Levenshtein edit distance: computes optimal string alignment
     * with insertion, deletion, substitution, and adjacent transposition.
     */
    public static int damerauLevenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;

        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                d[i][j] = Math.min(d[i - 1][j] + 1, // deletion
                          Math.min(d[i][j - 1] + 1, // insertion
                                   d[i - 1][j - 1] + cost)); // substitution

                // Transposition
                if (i > 1 && j > 1 &&
                    a.charAt(i - 1) == b.charAt(j - 2) &&
                    a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + cost);
                }
            }
        }
        return d[n][m];
    }

    /**
     * Similarity score between 0.0 and 1.0 based on Damerau-Levenshtein.
     */
    public static double sim(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int mx = Math.max(a.length(), b.length());
        if (mx == 0) return 1.0;
        return 1.0 - (double) damerauLevenshtein(a, b) / mx;
    }

    /**
     * Simplified Metaphone phonetic encoding.
     */
    public static String phon(String w) {
        if (w == null) return "";
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

    /**
     * American Soundex phonetic encoding.
     */
    public static String soundex(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        String upper = s.toUpperCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        char first = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                first = c;
                break;
            }
        }
        if (first == 0) return "";
        sb.append(first);

        char prevCode = getSoundexCode(first);
        for (int i = 1; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c < 'A' || c > 'Z') continue;
            char code = getSoundexCode(c);
            if (code != '0' && code != prevCode) {
                sb.append(code);
                if (sb.length() == 4) break;
            }
            prevCode = code;
        }

        while (sb.length() < 4) sb.append('0');
        return sb.toString();
    }

    private static char getSoundexCode(char c) {
        switch (c) {
            case 'B': case 'F': case 'P': case 'V': return '1';
            case 'C': case 'G': case 'J': case 'K': case 'Q': case 'S': case 'X': case 'Z': return '2';
            case 'D': case 'T': return '3';
            case 'L': return '4';
            case 'M': case 'N': return '5';
            case 'R': return '6';
            default: return '0';
        }
    }

    private static String phonPhrase(String phrase) {
        StringBuilder sb = new StringBuilder();
        for (String w : phrase.split(" ")) sb.append(phon(w));
        return sb.toString();
    }

    private static String soundexPhrase(String phrase) {
        StringBuilder sb = new StringBuilder();
        for (String w : phrase.split(" ")) sb.append(soundex(w));
        return sb.toString();
    }

    /**
     * Evaluates match score between normalized heard utterance and trigger phrase.
     * Returns score between 0.0 and 1.0.
     */
    public static double score(String heard, String trigger) {
        if (heard == null || trigger == null || heard.isEmpty() || trigger.isEmpty()) return 0.0;
        if (heard.equals(trigger)) return 1.0;
        if ((" " + heard + " ").contains(" " + trigger + " ")) return 0.97;

        String hNo = heard.replace(" ", "");
        String tNo = trigger.replace(" ", "");
        if (tNo.length() >= 3 && hNo.contains(tNo)) return 0.96;
        if (hNo.equals(tNo)) return 1.0;
        // 1–2 letter triggers only match exactly or as a whole word (handled above):
        // fuzzy scores on them are noise ("ab" vs "abc" = 0.67).
        if (tNo.length() < 3) return 0.0;

        double best = Math.max(sim(heard, trigger), sim(hNo, tNo));

        // Phonetic matching
        String tPhon = phonPhrase(trigger);
        boolean phonOk = tPhon.length() >= 3;
        if (phonOk && tPhon.equals(phonPhrase(heard))) best = Math.max(best, PHONETIC);

        String tSoundex = soundexPhrase(trigger);
        if (tSoundex.length() >= 4 && tSoundex.equals(soundexPhrase(heard))) {
            best = Math.max(best, PHONETIC);
        }

        // Sliding token window across utterance
        String[] hw = heard.split(" ");
        int n = trigger.split(" ").length;

        for (int size = Math.max(1, n - 1); size <= n + 2; size++) {
            for (int i = 0; i + size <= hw.length; i++) {
                StringBuilder w = new StringBuilder();
                for (int k = i; k < i + size; k++) {
                    if (k > i) w.append(' ');
                    w.append(hw[k]);
                }
                String win = w.toString();
                best = Math.max(best, sim(win, trigger));
                if (phonOk && tPhon.equals(phonPhrase(win))) {
                    best = Math.max(best, PHONETIC_WINDOW);
                }
            }
        }
        return best;
    }

    /**
     * Checks if heard utterance matches target trigger with standard fuzzy tolerance.
     */
    public static boolean matches(String heard, String trigger) {
        if (heard == null || trigger == null) return false;
        return score(norm(heard), norm(trigger)) >= FUZZY;
    }

    /**
     * Selects the highest scoring trigger above threshold with sibling prefix disambiguation.
     */
    public static int best(List<String> heard, List<String> triggers, double threshold) {
        if (heard == null || triggers == null || triggers.isEmpty()) return -1;
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

    /**
     * Checks if another trigger in the list is a longer extension of the candidate trigger.
     */
    public static boolean hasLongerSibling(List<String> triggers, int idx) {
        if (triggers == null || idx < 0 || idx >= triggers.size()) return false;
        String t = triggers.get(idx);
        for (int i = 0; i < triggers.size(); i++) {
            if (i == idx) continue;
            String o = triggers.get(i);
            if (o.length() > t.length() && (" " + o + " ").contains(" " + t + " ")) return true;
        }
        return false;
    }
}
