package uz.saydo.app;

import java.util.Locale;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;

/**
 * Safety decisions for AI requests (pure Java, no Android, unit tested):
 *  - which apps count as money-related,
 *  - whether a macro is sensitive,
 *  - whether a sensitive macro may run now,
 *  - whether a screen-control tool may act while a money app is in front.
 * The Android parts (the approval notification, the settings) call these and nothing else decides.
 */
public final class McpGuard {
    private McpGuard() {}

    /** Package-name fragments of money apps (payments, banks, wallets, crypto). Lower case. */
    static final String[] MONEY_FRAGMENTS = {
            "payme", "click.uz", "uz.click", "clickuz", "paynet", "uzum", "anorbank", "humo", "uzcard", "bank", "wallet",
            "paypal", "payment", "finance", "fintech", "revolut", "binance", "crypto", "tinkoff", "apelsin",
            "mastercard", "spay", "paisa", "ipakyuli"
    };

    static boolean isMoneyPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String f : MONEY_FRAGMENTS) if (p.contains(f)) return true;
        return false;
    }

    /** True when any recorded step happened in a money app. */
    static boolean touchesMoney(Macro m) {
        for (Gesture g : m.gestures) if (isMoneyPackage(g.screenPkg)) return true;
        return false;
    }

    /**
     * Effective sensitivity: the macro's own flag, or detected money use unless the owner has
     * looked at the flag in the app (then the owner's choice stands).
     */
    public static boolean isSensitive(Macro m) {
        return m.sensitive || (!m.sensitiveReviewed && touchesMoney(m));
    }

    enum RunDecision { ALLOW, NEED_CONFIRM, NEED_PHONE_APPROVAL }

    /**
     * A sensitive macro runs on an AI request only with confirm=true AND (when switched on) an Allow on the phone.
     * A normal macro always runs.
     */
    static RunDecision decideRun(boolean sensitive, boolean confirm, boolean phoneApprovalRequired) {
        if (!sensitive) return RunDecision.ALLOW;
        if (!confirm) return RunDecision.NEED_CONFIRM;
        return phoneApprovalRequired ? RunDecision.NEED_PHONE_APPROVAL : RunDecision.ALLOW;
    }

    static final String NEED_CONFIRM_MESSAGE =
            "refused: this macro is sensitive (money, banking or similar). Ask the owner in the conversation to "
                    + "confirm this exact action, then call run_macro again with confirm=true. "
                    + "Do not set confirm=true without that explicit confirmation.";

    static final String DENIED_ON_PHONE_MESSAGE =
            "refused: the owner did not allow it on the phone (denied or no answer within 60 s). Do not retry "
                    + "unless the owner asks again.";

    /** Null = allowed, else the refusal for the AI. Read-only tools never call this. */
    static String screenActionRefusal(String foregroundPkg, boolean allowMoneyApps) {
        if (allowMoneyApps || !isMoneyPackage(foregroundPkg)) return null;
        return "refused: a money app (" + foregroundPkg + ") is in front and AI control of money apps is off. "
                + "Only the owner can allow it on the phone (SayDo > MCP > Allow AI in money apps). "
                + "read_screen and device_status still work.";
    }

    /** Opening a money app is refused too (so act cannot open it and then use it), unless allowed. */
    static String openAppRefusal(String pkg, boolean allowMoneyApps) {
        if (allowMoneyApps || !isMoneyPackage(pkg)) return null;
        return "refused: " + pkg + " is a money app and AI control of money apps is off "
                + "(owner: SayDo > MCP > Allow AI in money apps).";
    }

    /** Short, free of amounts and typed text: what the phone shows in the approval notification. */
    static String approvalText(String trigger) {
        String t = trigger == null ? "" : McpFormat.clip(trigger, 60);
        return "AI wants to run the sensitive macro \"" + t + "\". Allow?";
    }
}
