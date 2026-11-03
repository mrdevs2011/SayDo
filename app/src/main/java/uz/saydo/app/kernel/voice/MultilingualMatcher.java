package uz.saydo.app.kernel.voice;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import uz.saydo.app.TriggerMatcher;

/**
 * Advanced Multilingual Speech & Semantic Intent Matcher.
 * Natively handles English, Uzbek (Latin diacritics & Cyrillic transliteration),
 * and Russian phonetics with Token-Sort Distance.
 */
public final class MultilingualMatcher {

    private static final Map<Character, String> CYRILLIC_TO_LATIN = new HashMap<>();

    static {
        CYRILLIC_TO_LATIN.put('а', "a");
        CYRILLIC_TO_LATIN.put('б', "b");
        CYRILLIC_TO_LATIN.put('в', "v");
        CYRILLIC_TO_LATIN.put('г', "g");
        CYRILLIC_TO_LATIN.put('д', "d");
        CYRILLIC_TO_LATIN.put('е', "e");
        CYRILLIC_TO_LATIN.put('ё', "yo");
        CYRILLIC_TO_LATIN.put('ж', "j");
        CYRILLIC_TO_LATIN.put('з', "z");
        CYRILLIC_TO_LATIN.put('и', "i");
        CYRILLIC_TO_LATIN.put('й', "y");
        CYRILLIC_TO_LATIN.put('к', "k");
        CYRILLIC_TO_LATIN.put('л', "l");
        CYRILLIC_TO_LATIN.put('м', "m");
        CYRILLIC_TO_LATIN.put('н', "n");
        CYRILLIC_TO_LATIN.put('о', "o");
        CYRILLIC_TO_LATIN.put('п', "p");
        CYRILLIC_TO_LATIN.put('р', "r");
        CYRILLIC_TO_LATIN.put('с', "s");
        CYRILLIC_TO_LATIN.put('т', "t");
        CYRILLIC_TO_LATIN.put('у', "u");
        CYRILLIC_TO_LATIN.put('ф', "f");
        CYRILLIC_TO_LATIN.put('х', "x");
        CYRILLIC_TO_LATIN.put('ц', "ts");
        CYRILLIC_TO_LATIN.put('ч', "ch");
        CYRILLIC_TO_LATIN.put('ш', "sh");
        CYRILLIC_TO_LATIN.put('щ', "sh");
        CYRILLIC_TO_LATIN.put('ъ', "");
        CYRILLIC_TO_LATIN.put('ы', "i");
        CYRILLIC_TO_LATIN.put('ь', "");
        CYRILLIC_TO_LATIN.put('э', "e");
        CYRILLIC_TO_LATIN.put('ю', "yu");
        CYRILLIC_TO_LATIN.put('я', "ya");
        CYRILLIC_TO_LATIN.put('ў', "o'");
        CYRILLIC_TO_LATIN.put('ғ', "g'");
        CYRILLIC_TO_LATIN.put('ҳ', "h");
        CYRILLIC_TO_LATIN.put('қ', "q");
    }

    private static final Map<String, String> RUSSIAN_COMMAND_MAP = new HashMap<>();

    static {
        RUSSIAN_COMMAND_MAP.put("открой", "open");
        RUSSIAN_COMMAND_MAP.put("открыть", "open");
        RUSSIAN_COMMAND_MAP.put("включи", "open");
        RUSSIAN_COMMAND_MAP.put("включить", "open");
        RUSSIAN_COMMAND_MAP.put("запусти", "open");
        RUSSIAN_COMMAND_MAP.put("запустить", "open");
        RUSSIAN_COMMAND_MAP.put("закрой", "close");
        RUSSIAN_COMMAND_MAP.put("закрыть", "close");
        RUSSIAN_COMMAND_MAP.put("выключи", "close");
        RUSSIAN_COMMAND_MAP.put("позвони", "call");
        RUSSIAN_COMMAND_MAP.put("напиши", "send");
        RUSSIAN_COMMAND_MAP.put("найди", "search");
        RUSSIAN_COMMAND_MAP.put("поиск", "search");
        RUSSIAN_COMMAND_MAP.put("камера", "camera");
        RUSSIAN_COMMAND_MAP.put("фото", "photo");
        RUSSIAN_COMMAND_MAP.put("настройки", "settings");
    }

    private static final Map<String, String> UZBEK_COMMAND_MAP = new HashMap<>();

    static {
        UZBEK_COMMAND_MAP.put("och", "open");
        UZBEK_COMMAND_MAP.put("ochish", "open");
        UZBEK_COMMAND_MAP.put("ishga tushir", "open");
        UZBEK_COMMAND_MAP.put("yoq", "open");
        UZBEK_COMMAND_MAP.put("yop", "close");
        UZBEK_COMMAND_MAP.put("yopish", "close");
        UZBEK_COMMAND_MAP.put("o'chir", "close");
        UZBEK_COMMAND_MAP.put("qo'ng'iroq", "call");
        UZBEK_COMMAND_MAP.put("qidir", "search");
        UZBEK_COMMAND_MAP.put("top", "search");
        UZBEK_COMMAND_MAP.put("yoz", "write");
        UZBEK_COMMAND_MAP.put("yubor", "send");
        UZBEK_COMMAND_MAP.put("rasm", "photo");
        UZBEK_COMMAND_MAP.put("sozlamalar", "settings");
    }

    private MultilingualMatcher() {
    }

    /**
     * Converts Uzbek/Russian Cyrillic script into normalized Latin representation.
     */
    public static String transliterateCyrillicToLatin(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toLowerCase(input.charAt(i));
            String mapped = CYRILLIC_TO_LATIN.get(c);
            if (mapped != null) {
                sb.append(mapped);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Normalizes multilingual text (Uzbek curly apostrophes, diacritics, Cyrillic, punctuation).
     */
    public static String normalizeMultilingual(String text) {
        if (text == null) return "";
        String s = text.toLowerCase().trim();
        // Standardize Uzbek diacritics
        s = s.replace('‘', '\'').replace('’', '\'').replace('`', '\'').replace('ʻ', '\'').replace('ʼ', '\'');

        // Map Uzbek command tokens
        String[] uzTokens = s.split("\\s+");
        StringBuilder mappedUz = new StringBuilder();
        for (String t : uzTokens) {
            String mapped = UZBEK_COMMAND_MAP.get(t);
            if (mapped != null) {
                mappedUz.append(mapped).append(" ");
            } else if (t.startsWith("kamera")) {
                mappedUz.append("camera").append(" ");
            } else {
                mappedUz.append(t).append(" ");
            }
        }
        s = mappedUz.toString().trim();

        // Transliterate if contains Cyrillic characters
        if (containsCyrillic(s)) {
            // First check if it's a known Russian command
            String[] tokens = s.split("\\s+");
            StringBuilder mappedTokens = new StringBuilder();
            for (String t : tokens) {
                String engCmd = RUSSIAN_COMMAND_MAP.get(t);
                if (engCmd != null) {
                    mappedTokens.append(engCmd).append(" ");
                } else {
                    mappedTokens.append(transliterateCyrillicToLatin(t)).append(" ");
                }
            }
            s = mappedTokens.toString().trim();
        }

        // Clean non-alphanumeric except apostrophe and spaces
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'' || Character.isWhitespace(c)) {
                clean.append(c);
            } else {
                clean.append(' ');
            }
        }
        return clean.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean containsCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) {
                return true;
            }
        }
        return false;
    }

    /**
     * Token-Sort Fuzzy Matcher: Reorders words alphabetically to recognize phrases
     * spoken in reverse or scrambled order (e.g. "camera please open" vs "open camera").
     */
    public static float tokenSortSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0f;
        String n1 = normalizeMultilingual(s1);
        String n2 = normalizeMultilingual(s2);
        if (n1.equals(n2)) return 1.0f;

        String sorted1 = sortTokens(n1);
        String sorted2 = sortTokens(n2);
        if (sorted1.equals(sorted2)) return 1.0f;

        // Word overlap Jaccard coefficient
        Set<String> set1 = new HashSet<>(Arrays.asList(n1.split("\\s+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(n2.split("\\s+")));

        // Discard filler words
        set1.remove("please");
        set1.remove("saydo");
        set2.remove("please");
        set2.remove("saydo");

        if (set1.isEmpty() || set2.isEmpty()) return 0.0f;

        int intersection = 0;
        for (String w : set1) {
            if (set2.contains(w)) intersection++;
        }

        int union = set1.size() + set2.size() - intersection;
        if (union <= 0) return 0.0f;
        return (float) intersection / (float) union;
    }

    private static String sortTokens(String text) {
        String[] tokens = text.split("\\s+");
        Arrays.sort(tokens);
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (!t.isEmpty() && !t.equals("please") && !t.equals("saydo")) {
                sb.append(t).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * Checks if user spoken text matches a target trigger using multilingual & token-sort intelligence.
     */
    public static boolean matchesMultilingual(String spoken, String targetTrigger) {
        if (spoken == null || targetTrigger == null) return false;
        String nSpoken = normalizeMultilingual(spoken);
        String nTarget = normalizeMultilingual(targetTrigger);

        if (nSpoken.contains(nTarget) || nTarget.contains(nSpoken)) {
            return true;
        }

        if (tokenSortSimilarity(nSpoken, nTarget) >= 0.70f) {
            return true;
        }

        // Standard English TriggerMatcher fallback using normalized phrases
        return TriggerMatcher.matches(nSpoken, nTarget) || TriggerMatcher.matches(spoken, targetTrigger);
    }
}
