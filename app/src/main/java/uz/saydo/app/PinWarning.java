package uz.saydo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;

/**
 * First-time warning when a macro holds a PIN (sensitive steps). Shown once, in the main screen.
 * Confirming also marks those macros as reviewed by the owner (Macro.sensitiveReviewed). NOT verified on a phone.
 */
final class PinWarning {
    private PinWarning() {}

    private static final String PREFS = "saydo_safety";
    private static final String KEY_ACK = "pin_warning_ack";

    static final String TEXT =
            "Bu makroda PIN-kod bosilgan. Xavfsizlik haqida bilib qo'ying:\n\n"
                    + "- PIN telefon ichida shifrlangan holda saqlanadi (Android Keystore), kalit telefondan chiqmaydi.\n"
                    + "- Bunday makro AI (MCP) ro'yxatida, qidiruvda va eksportda ko'rinmaydi va AI uni ishga tushira olmaydi.\n"
                    + "- Lekin telefonni qo'lga olgan kishi ovoz bilan yoki ilovadan shu makroni ishga tushirsa, "
                    + "makro PIN-ni o'zi kiritadi.\n\n"
                    + "Ancha xavfsiz yo'l: PIN o'rniga barmoq izi ishlating. Makro barmoq izida to'xtab, sizni kutadi.";

    static void maybeShow(final Activity a, List<Macro> macros) {
        try {
            if (a == null || a.isFinishing() || macros == null) return;
            final List<Macro> pin = new ArrayList<>();
            for (Macro m : macros) if (m.hasSensitiveSteps()) pin.add(m);
            if (pin.isEmpty()) return;
            final SharedPreferences sp = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
            if (sp.getBoolean(KEY_ACK, false)) return;
            new AlertDialog.Builder(a)
                    .setTitle("PIN saqlangan makro")
                    .setMessage(TEXT)
                    .setCancelable(false)
                    .setPositiveButton("Tushundim", (d, w) -> {
                        sp.edit().putBoolean(KEY_ACK, true).apply();
                        MacroRepository repo = MacroRepository.getInstance(a);
                        for (Macro m : pin) {
                            repo.save(m.withInfo(m.description, m.aliases, true, true));
                        }
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }
}
