package uz.saydo.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure text folding for MCP matching (no Android classes, unit tested).
 * One folded form for Latin, Uzbek Latin, Uzbek Cyrillic and Russian, so "Ютуб", "YouTube" and "yutub"
 * can be compared, and "Yuborish" equals "Юбориш". Not a spelling checker: it only removes the
 * differences that are cheap to remove (case, accents, apostrophes, script).
 */
final class McpText {
    private McpText() {}

    private static final String[] CYR = {
            "а", "a", "б", "b", "в", "v", "г", "g", "д", "d", "е", "e", "ё", "yo", "ж", "j", "з", "z",
            "и", "i", "й", "y", "к", "k", "л", "l", "м", "m", "н", "n", "о", "o", "п", "p", "р", "r",
            "с", "s", "т", "t", "у", "u", "ф", "f", "х", "h", "ц", "ts", "ч", "ch", "ш", "sh", "щ", "sh",
            "ъ", "", "ы", "i", "ь", "", "э", "e", "ю", "yu", "я", "ya", "ў", "o", "ғ", "g", "ҳ", "h",
            "қ", "q", "ә", "a", "і", "i", "ї", "i", "є", "e"};

    private static String cyr(char c) {
        for (int i = 0; i < CYR.length; i += 2) if (CYR[i].charAt(0) == c) return CYR[i + 1];
        return null;
    }

    private static boolean apostrophe(char c) {
        return c == '\'' || c == 'ʻ' || c == 'ʼ' || c == '’' || c == '‘' || c == '`' || c == '´';
    }

    /** Lowercase, accents and apostrophes removed, Cyrillic turned into Latin. Other characters stay. */
    static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        String d = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length() + 4);
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (apostrophe(c)) continue;
            String l = cyr(c);
            if (l != null) sb.append(l);
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Folded words (letters and digits only). */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        String f = fold(s);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= f.length(); i++) {
            char c = i < f.length() ? f.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        return out;
    }

    /**
     * Rough sound skeleton of a folded word: "youtube", "yutub" and "ютуб" all give "ytb".
     * Vowels other than y are dropped, similar consonants merged. Only useful for words of 3+ letters.
     */
    static String skeleton(String foldedWord) {
        String w = foldedWord.replace("you", "yu").replace("oo", "u").replace("ee", "i")
                .replace("ph", "f").replace("ck", "k").replace("ts", "s");
        StringBuilder sb = new StringBuilder(w.length());
        char prev = 0;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if ("aeiou".indexOf(c) >= 0) continue;
            if (c == 'w') c = 'v';
            else if (c == 'q' || c == 'c') c = 'k';
            else if (c == 'x') c = 'h';
            else if (c == 'z') c = 's';
            else if (c == 'g') c = 'k';
            else if (c == 'd') c = 't';
            else if (c == 'p') c = 'b';
            if (c == prev) continue;
            sb.append(c);
            prev = c;
        }
        return sb.toString();
    }

    /** Case, accent and script blind "contains"; an empty needle matches everything. */
    static boolean containsFolded(String hay, String needle) {
        if (needle == null || needle.isEmpty()) return true;
        if (hay == null) return false;
        return fold(hay).contains(fold(needle));
    }
}
