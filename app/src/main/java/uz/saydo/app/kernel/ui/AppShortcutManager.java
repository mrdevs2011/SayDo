package uz.saydo.app.kernel.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uz.saydo.app.MainActivity;
import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;

/**
 * Dynamic App Launcher Shortcuts Manager (API 25+).
 * Exposes top 3 most frequently used macros as direct Android launcher shortcuts on icon long-press.
 */
public final class AppShortcutManager {

    private AppShortcutManager() {
    }

    public static void updateShortcuts(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return;
        }

        try {
            ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
            if (shortcutManager == null) return;

            MacroRepository repository = MacroRepository.getInstance(context);
            List<Macro> all = new ArrayList<>(repository.getAll());
            // Sort by usage count descending
            Collections.sort(all, (m1, m2) -> Integer.compare(m2.usageCount, m1.usageCount));

            List<ShortcutInfo> shortcuts = new ArrayList<>();
            int maxShortcuts = Math.min(3, all.size());

            for (int i = 0; i < maxShortcuts; i++) {
                Macro macro = all.get(i);
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.setAction(Intent.ACTION_VIEW);
                launchIntent.putExtra("execute_macro_id", macro.id);

                ShortcutInfo info = new ShortcutInfo.Builder(context, "macro_" + macro.id)
                        .setShortLabel(macro.trigger)
                        .setLongLabel("Trigger " + macro.trigger)
                        .setIcon(Icon.createWithResource(context, android.R.drawable.ic_media_play))
                        .setIntent(launchIntent)
                        .build();

                shortcuts.add(info);
            }

            shortcutManager.setDynamicShortcuts(shortcuts);
        } catch (Throwable t) {
            android.util.Log.w("AppShortcutManager", "Failed to update dynamic shortcuts", t);
        }
    }
}
