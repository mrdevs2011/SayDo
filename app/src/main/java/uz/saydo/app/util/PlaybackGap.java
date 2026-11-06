package uz.saydo.app.util;

/**
 * Pause rule between two replayed steps. Replay waits for the expected app and anchor, so a long
 * recorded pause is mostly thinking time. When the next step knows its app (pkg), the pause is
 * capped. Steps without pkg cannot wait for anything, so they keep the exact recorded pause.
 */
public final class PlaybackGap {
    public static final long MAX_GAP_WITH_PKG_MS = 3000;

    private PlaybackGap() {}

    public static long effective(long recordedGapMs, boolean nextStepHasPkg) {
        long gap = Math.max(0, recordedGapMs);
        if (nextStepHasPkg && gap > MAX_GAP_WITH_PKG_MS) return MAX_GAP_WITH_PKG_MS;
        return gap;
    }
}
