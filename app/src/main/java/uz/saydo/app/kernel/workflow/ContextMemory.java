package uz.saydo.app.kernel.workflow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ephemeral Secure Context Memory Registers ($REG_0 to $REG_7, $CLIPBOARD, $LAST_OTP).
 * Transports extracted text, tokens, and variables across disparate apps in a workflow pipeline.
 */
public final class ContextMemory {

    public static final String REG_0 = "$REG_0";
    public static final String REG_1 = "$REG_1";
    public static final String REG_2 = "$REG_2";
    public static final String REG_3 = "$REG_3";
    public static final String REG_4 = "$REG_4";
    public static final String REG_5 = "$REG_5";
    public static final String REG_6 = "$REG_6";
    public static final String REG_7 = "$REG_7";
    public static final String CLIPBOARD = "$CLIPBOARD";
    public static final String LAST_EXTRACTED_TEXT = "$LAST_EXTRACTED_TEXT";
    public static final String LAST_OTP = "$LAST_OTP";
    public static final String PARAM_1 = "$PARAM_1";

    private static final ContextMemory INSTANCE = new ContextMemory();

    public static ContextMemory getInstance() {
        return INSTANCE;
    }

    private final Map<String, String> registers = new ConcurrentHashMap<>();

    public ContextMemory() {
        clear();
    }

    public void set(String registerName, String value) {
        if (registerName == null) return;
        if (value != null) {
            registers.put(registerName.toUpperCase(), value);
        } else {
            registers.remove(registerName.toUpperCase());
        }
    }

    public String get(String registerName) {
        if (registerName == null) return "";
        String val = registers.get(registerName.toUpperCase());
        return val != null ? val : "";
    }

    public boolean has(String registerName) {
        if (registerName == null) return false;
        return registers.containsKey(registerName.toUpperCase());
    }

    /**
     * Extracts regex matching pattern from source text and stores it in the designated register.
     * Example: extractPattern(sourceText, "\\b\\d{4,8}\\b", LAST_OTP);
     */
    public boolean extractPattern(String sourceText, String regexPattern, String targetRegister) {
        if (sourceText == null || regexPattern == null || targetRegister == null) return false;
        try {
            Pattern pattern = Pattern.compile(regexPattern);
            Matcher matcher = pattern.matcher(sourceText);
            if (matcher.find()) {
                String extracted = matcher.group();
                set(targetRegister, extracted);
                set(LAST_EXTRACTED_TEXT, extracted);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Substitutes register tokens in a template string (e.g. "Your code is {$LAST_OTP}").
     */
    public String interpolate(String template) {
        if (template == null || template.isEmpty()) return "";
        String result = template;
        for (Map.Entry<String, String> entry : registers.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            result = result.replace("{" + key + "}", val);
            if (key.startsWith("$")) {
                result = result.replace("{" + key.substring(1) + "}", val);
            }
            result = result.replace(key, val);
        }
        return result;
    }

    /**
     * Wipes all registers and scrubs memory.
     */
    public void clear() {
        registers.clear();
        for (int i = 0; i <= 7; i++) {
            registers.put("$REG_" + i, "");
        }
        registers.put(CLIPBOARD, "");
        registers.put(LAST_EXTRACTED_TEXT, "");
        registers.put(LAST_OTP, "");
        registers.put(PARAM_1, "");
    }
}
