package uz.saydo.app.kernel.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic Voice-Parametric Template Matcher & Slot Extractor ("Say It With Variables").
 * Matches speech templates like "search {query} on youtube" and extracts runtime parameter values.
 */
public final class ParametricMatcher {

    public static class MatchResult {
        public final boolean matched;
        public final Map<String, String> variables;

        public MatchResult(boolean matched, Map<String, String> variables) {
            this.matched = matched;
            this.variables = (variables != null) ? Collections.unmodifiableMap(variables) : Collections.emptyMap();
        }

        public static MatchResult failed() {
            return new MatchResult(false, Collections.emptyMap());
        }

        public String get(String slotName) {
            return variables.get(slotName);
        }
    }

    private static final Pattern SLOT_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    private ParametricMatcher() {
    }

    /**
     * Determines whether a trigger string contains dynamic variable slots (e.g. {query}).
     */
    public static boolean hasSlots(String trigger) {
        if (trigger == null) return false;
        return SLOT_PATTERN.matcher(trigger).find();
    }

    /**
     * Extracts all slot names defined in a template trigger.
     */
    public static List<String> extractSlotNames(String template) {
        if (template == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        Matcher m = SLOT_PATTERN.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Matches spoken utterance against a template trigger and extracts variable bindings.
     * Example: template="search {query} on youtube", spoken="search interstellar on youtube"
     * -> matched=true, variables={"query": "interstellar"}
     */
    public static MatchResult match(String spoken, String template) {
        if (spoken == null || template == null) {
            return MatchResult.failed();
        }

        String nSpoken = MultilingualMatcher.normalizeMultilingual(spoken);
        String nTemplate = template.toLowerCase().trim();

        if (!hasSlots(nTemplate)) {
            boolean plainMatch = MultilingualMatcher.matchesMultilingual(nSpoken, nTemplate);
            return new MatchResult(plainMatch, Collections.emptyMap());
        }

        List<String> slotNames = extractSlotNames(nTemplate);
        if (slotNames.isEmpty()) {
            return MatchResult.failed();
        }

        // Construct dynamic regex with named capture groups
        StringBuilder regexBuilder = new StringBuilder();
        int lastEnd = 0;
        Matcher slotMatcher = SLOT_PATTERN.matcher(nTemplate);
        List<String> foundSlots = new ArrayList<>();
        List<String> staticParts = new ArrayList<>();

        while (slotMatcher.find()) {
            staticParts.add(nTemplate.substring(lastEnd, slotMatcher.start()).trim());
            foundSlots.add(slotMatcher.group(1));
            lastEnd = slotMatcher.end();
        }
        String trailingSuffix = nTemplate.substring(lastEnd).trim();

        for (int i = 0; i < foundSlots.size(); i++) {
            String part = staticParts.get(i);
            if (!part.isEmpty()) {
                if (i == 0) regexBuilder.append(".*?");
                regexBuilder.append(Pattern.quote(part)).append("\\s+");
            }
            String slot = foundSlots.get(i);
            boolean isLastSlot = (i == foundSlots.size() - 1);
            if (isLastSlot && trailingSuffix.isEmpty()) {
                regexBuilder.append("(?<").append(slot).append(">.+)");
            } else {
                regexBuilder.append("(?<").append(slot).append(">.+?)");
            }
        }

        if (!trailingSuffix.isEmpty()) {
            regexBuilder.append("\\s+").append(Pattern.quote(trailingSuffix));
        }

        try {
            Pattern pattern = Pattern.compile(regexBuilder.toString(), Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(nSpoken);

            if (matcher.find()) {
                Map<String, String> vars = new HashMap<>();
                for (String name : slotNames) {
                    try {
                        String value = matcher.group(name);
                        if (value != null) {
                            vars.put(name, value.trim());
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                return new MatchResult(true, vars);
            }
        } catch (Throwable t) {
            android.util.Log.w("ParametricMatcher", "Regex compile error: " + regexBuilder, t);
        }

        return MatchResult.failed();
    }
}
