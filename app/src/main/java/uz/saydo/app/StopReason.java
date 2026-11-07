package uz.saydo.app;

/**
 * Turns the short internal reason of a stopped macro into a clear sentence for the AI (pure Java, unit tested).
 * The internal reasons are the Uzbek texts shown to the owner in the stop toast; the sentences here are
 * English and say what probably happened and what to do next.
 */
final class StopReason {
    private StopReason() {}

    /** What the AI should do next; same for every reason. */
    static final String ADVICE = "read_screen to see where it stopped; do not simply run it again.";

    static String explain(String why) {
        if (why == null || why.trim().isEmpty()) {
            return "it was interrupted before the end (the owner cancelled it, or Accessibility was switched off)";
        }
        String w = why.toLowerCase(java.util.Locale.ROOT);
        if (w.contains("ko'rinmadi")) {
            return "the button or view it needed was not found on screen (waited 8 s and scrolled to look). "
                    + "The app may look different now, or a dialog, update or permission screen is in the way";
        }
        if (w.contains("ilova ochilmadi")) {
            return "the app it expected did not come to the front. It may be closed, not installed, or "
                    + "another app or the lock screen is in front";
        }
        if (w.contains("yozish maydoni")) {
            return "the text field it needed was not found on screen";
        }
        if (w.contains("matn yozilmadi")) {
            return "the text could not be entered into the field (the field refused it or the keyboard took focus)";
        }
        if (w.contains("bekor qilindi")) {
            return "Android cancelled the touch (a real touch, a system dialog or the lock screen interrupted it)";
        }
        if (w.contains("qabul qilmadi") || w.contains("bajarilmadi")) {
            return "Android refused to perform the touch (Accessibility may have lost its permission)";
        }
        if (w.contains("qurib bo'lmadi")) {
            return "the recorded step is damaged and cannot be performed";
        }
        if (w.contains("kutish vaqti tugadi")) {
            return "it waited 60 s for the owner to pass the fingerprint, face or lock screen, and nobody did. "
                    + "Ask the owner to be at the phone and run it again";
        }
        return "reason: " + w;
    }

    /**
     * One message for run_macro. {@code stepLine} may be empty, {@code step} is 0-based or -1 when unknown.
     */
    static String message(String trigger, int step, int total, String stepLine, String why, String inFront) {
        StringBuilder sb = new StringBuilder("stopped \"").append(trigger).append('"');
        if (step >= 0) {
            sb.append(" at step ").append(step + 1).append(" of ").append(total);
            if (stepLine != null && !stepLine.isEmpty()) sb.append(" (").append(stepLine).append(')');
        }
        sb.append(": ").append(explain(why)).append(inFront == null ? "" : inFront).append(". ").append(ADVICE);
        return sb.toString();
    }
}
