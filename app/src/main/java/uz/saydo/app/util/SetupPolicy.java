package uz.saydo.app.util;

/**
 * Pure rules for the first START. Only things that are really needed to run block START.
 * Battery optimisation is optional and is asked once, after START, without blocking it.
 */
public final class SetupPolicy {
    public static final int OK = 0;
    public static final int NEED_OVERLAY = 1;
    public static final int NEED_ACCESSIBILITY = 2;
    public static final int NEED_MIC = 3;
    public static final int NEED_NOTIF = 4;

    private SetupPolicy() {}

    /** First required thing that is missing, or OK. */
    public static int firstMissing(boolean overlay, boolean accessibility, boolean mic,
                                   boolean notifNeeded, boolean notifGranted) {
        if (!overlay) return NEED_OVERLAY;
        if (!accessibility) return NEED_ACCESSIBILITY;
        if (!mic) return NEED_MIC;
        if (notifNeeded && !notifGranted) return NEED_NOTIF;
        return OK;
    }

    /** One-time, non-blocking battery prompt. */
    public static boolean shouldOfferBattery(int sdk, boolean ignoringOptimisations, boolean alreadyAsked) {
        return sdk >= 23 && !ignoringOptimisations && !alreadyAsked;
    }
}
