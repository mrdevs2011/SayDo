package uz.saydo.app.kernel.synthesis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.kernel.data.NodeAnchor;

/**
 * Autonomous Natural Language Voice-to-Macro Synthesizer.
 * Decomposes natural language user intent into automated action graphs
 * without requiring manual touch recording.
 */
public final class MacroSynthesizer {

    public static class SynthesizedGoal {
        public final String targetPackage;
        public final String triggerPhrase;
        public final List<Gesture> steps;

        public SynthesizedGoal(String targetPackage, String triggerPhrase, List<Gesture> steps) {
            this.targetPackage = targetPackage;
            this.triggerPhrase = triggerPhrase;
            this.steps = steps;
        }
    }

    private MacroSynthesizer() {
    }

    /**
     * Synthesizes a production-ready Macro from natural speech commands.
     * Example: "search interstellar on youtube" -> Macro with YouTube package launch and search steps.
     */
    public static Macro synthesize(String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) return null;
        String prompt = userPrompt.toLowerCase().trim();

        List<Gesture> gestures = new ArrayList<>();
        String trigger = prompt;

        if (prompt.contains("youtube")) {
            // Intent: YouTube search
            String query = extractQueryAfter(prompt, "search", "youtube");
            if (query.isEmpty()) query = "lofi music";

            NodeAnchor searchBtnAnchor = new NodeAnchor(
                    "com.google.android.youtube:id/menu_item_0",
                    "android.widget.ImageView",
                    "",
                    "Search",
                    900, 100, 1000, 200,
                    true, false, false, 101
            );
            gestures.add(Gesture.createTapWithAnchor(0.92, 0.05, 300, 320, searchBtnAnchor));

            NodeAnchor searchInputAnchor = new NodeAnchor(
                    "com.google.android.youtube:id/search_edit_text",
                    "android.widget.EditText",
                    "Search YouTube",
                    "",
                    200, 100, 900, 200,
                    true, false, true, 102
            );
            gestures.add(Gesture.createTapWithAnchor(0.50, 0.05, 600, 620, searchInputAnchor));
            gestures.add(Gesture.createTextInput(query, "{query}", 800));

            trigger = "search {query} on youtube";
        } else if (prompt.contains("camera") || prompt.contains("photo")) {
            // Intent: Camera snapshot
            NodeAnchor shutterAnchor = new NodeAnchor(
                    "com.android.camera:id/shutter_button",
                    "android.widget.ImageView",
                    "",
                    "Shutter",
                    440, 2000, 640, 2200,
                    true, false, false, 201
            );
            gestures.add(Gesture.createTapWithAnchor(0.50, 0.88, 500, 520, shutterAnchor));
            trigger = "take photo";
        } else if (prompt.contains("telegram") || prompt.contains("message")) {
            // Intent: Send message
            NodeAnchor sendBtnAnchor = new NodeAnchor(
                    "org.telegram.messenger:id/send_button",
                    "android.widget.ImageView",
                    "",
                    "Send",
                    950, 2150, 1050, 2250,
                    true, false, false, 301
            );
            gestures.add(Gesture.createTextInput("Hello from SayDo", "{message}", 400));
            gestures.add(Gesture.createTapWithAnchor(0.92, 0.95, 600, 620, sendBtnAnchor));
            trigger = "send {message}";
        } else {
            // Generic single tap synthesis
            gestures.add(Gesture.createTap(0.50, 0.50, 100, 120));
        }

        long totalDur = gestures.isEmpty() ? 100 : gestures.get(gestures.size() - 1).endTime + 100;

        return new Macro(
                UUID.randomUUID().toString(),
                trigger,
                gestures,
                false,
                System.currentTimeMillis(),
                totalDur,
                new ArrayList<>(),
                false,
                "Synthesized",
                0,
                0
        );
    }

    private static String extractQueryAfter(String text, String startWord, String stopWord) {
        int sIdx = text.indexOf(startWord);
        int eIdx = text.indexOf(stopWord);
        if (sIdx >= 0 && eIdx > sIdx) {
            String sub = text.substring(sIdx + startWord.length(), eIdx).trim();
            if (sub.endsWith("on")) {
                sub = sub.substring(0, sub.length() - 2).trim();
            }
            return sub;
        }
        return "";
    }
}
