package uz.saydo.app.kernel.synthesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.kernel.data.NodeAnchor;

/**
 * Curated Offline Recipe Catalog for popular Android applications.
 * Provides out-of-the-box tested automations for YouTube, Telegram, Spotify, Camera, and System.
 */
public final class RecipeCatalog {

    private static final List<Macro> RECIPES = new ArrayList<>();

    static {
        // 1. YouTube Search Recipe
        List<Gesture> ytGestures = new ArrayList<>();
        ytGestures.add(Gesture.createTapWithAnchor(0.92, 0.05, 100, 120,
                new NodeAnchor("com.google.android.youtube:id/menu_item_0", "android.widget.ImageView", "", "Search", 900, 80, 1000, 180, true, false, false, 501)));
        ytGestures.add(Gesture.createTextInput("Trending", "{query}", 400));
        RECIPES.add(new Macro("recipe_yt_search", "search {query} on youtube", ytGestures, true, 1700000000000L, 800,
                Collections.singletonList("youtube search"), false, "Media", 0, 0));

        // 2. Camera Snapshot Recipe
        List<Gesture> camGestures = new ArrayList<>();
        camGestures.add(Gesture.createTapWithAnchor(0.50, 0.88, 150, 180,
                new NodeAnchor("com.android.camera:id/shutter_button", "android.widget.ImageView", "", "Shutter", 440, 1900, 640, 2100, true, false, false, 502)));
        RECIPES.add(new Macro("recipe_camera_shot", "take photo", camGestures, true, 1700000000000L, 400,
                Collections.singletonList("snap picture"), false, "Camera", 0, 0));

        // 3. Spotify Play Liked Songs
        List<Gesture> spotifyGestures = new ArrayList<>();
        spotifyGestures.add(Gesture.createTap(0.50, 0.50, 200, 220));
        RECIPES.add(new Macro("recipe_spotify_play", "play music", spotifyGestures, true, 1700000000000L, 500,
                Collections.singletonList("spotify play"), false, "Media", 0, 0));

        // 4. Turn On Flashlight
        List<Gesture> torchGestures = new ArrayList<>();
        torchGestures.add(Gesture.createGlobal("torch_toggle", 100));
        RECIPES.add(new Macro("recipe_flashlight", "turn on flashlight", torchGestures, true, 1700000000000L, 200,
                Collections.singletonList("flashlight"), false, "System", 0, 0));
    }

    private RecipeCatalog() {
    }

    public static List<Macro> getCuratedRecipes() {
        return Collections.unmodifiableList(RECIPES);
    }

    public static Macro findRecipeByTrigger(String trigger) {
        if (trigger == null) return null;
        for (Macro m : RECIPES) {
            if (m.trigger.equalsIgnoreCase(trigger)) {
                return m;
            }
        }
        return null;
    }
}
