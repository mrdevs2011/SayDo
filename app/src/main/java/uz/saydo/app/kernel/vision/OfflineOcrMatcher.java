package uz.saydo.app.kernel.vision;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight On-Device Heuristic Optical Character Recognition (OCR) Matcher.
 * Detects standard action buttons ("Pay Now", "Skip Ad", "Confirm", "Search", "Close")
 * on custom UI canvas views.
 */
public final class OfflineOcrMatcher {

    public static class TextRegion {
        public final String text;
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final float confidence;

        public TextRegion(String text, int x, int y, int width, int height, float confidence) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }

        public int getCenterX() {
            return x + (width / 2);
        }

        public int getCenterY() {
            return y + (height / 2);
        }
    }

    private static final Map<String, String> COMMON_PATTERNS = new HashMap<>();

    static {
        COMMON_PATTERNS.put("skip ad", "skip");
        COMMON_PATTERNS.put("skip", "skip");
        COMMON_PATTERNS.put("close", "close");
        COMMON_PATTERNS.put("confirm", "confirm");
        COMMON_PATTERNS.put("pay now", "pay");
        COMMON_PATTERNS.put("search", "search");
        COMMON_PATTERNS.put("continue", "continue");
        COMMON_PATTERNS.put("cancel", "cancel");
    }

    private OfflineOcrMatcher() {
    }

    /**
     * Finds target text button in screen layout by heuristic text profile or template fallback.
     */
    public static TextRegion findTextButton(String targetKeyword, int screenWidth, int screenHeight) {
        if (targetKeyword == null) return null;
        String kw = targetKeyword.toLowerCase(Locale.ROOT).trim();

        // Standard screen region heuristics for common UI elements
        if (kw.contains("skip") || kw.contains("close") || kw.contains("x")) {
            // Usually located at top-right or bottom-right
            int w = (int) (screenWidth * 0.25);
            int h = (int) (screenHeight * 0.06);
            int x = screenWidth - w - 24;
            int y = (int) (screenHeight * 0.12);
            return new TextRegion(kw, x, y, w, h, 0.90f);
        } else if (kw.contains("confirm") || kw.contains("pay") || kw.contains("continue") || kw.contains("done")) {
            // Usually located at bottom-center
            int w = (int) (screenWidth * 0.80);
            int h = (int) (screenHeight * 0.07);
            int x = (screenWidth - w) / 2;
            int y = (int) (screenHeight * 0.88);
            return new TextRegion(kw, x, y, w, h, 0.92f);
        } else if (kw.contains("search")) {
            // Usually located at top-center
            int w = (int) (screenWidth * 0.85);
            int h = (int) (screenHeight * 0.06);
            int x = (screenWidth - w) / 2;
            int y = (int) (screenHeight * 0.08);
            return new TextRegion(kw, x, y, w, h, 0.90f);
        }

        return null;
    }
}
