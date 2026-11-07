package uz.saydo.app.safety;

/**
 * Replay side of the "wait for user" step (pure Java, unit tested, needs no screen content).
 *
 * Waits for the auth prompt to show (up to GRACE_MS; if none shows, nothing is asked of the owner and
 * the macro goes on), tells the owner once, then waits until the prompt is gone (the next window
 * change that is not an auth window) or TIMEOUT_MS passed since the prompt showed.
 */
public final class WaitUserLogic {
    public enum State { WAITING_PROMPT, WAITING_USER, DONE, TIMEOUT }

    public static final long GRACE_MS = 5000;
    public static final long TIMEOUT_MS = 60_000;

    private final long start;
    private final long grace;
    private final long timeout;
    private boolean seenAuth;
    private boolean gone;
    private long authAt;
    private boolean notified;

    public WaitUserLogic(long startMs, boolean authVisibleNow) {
        this(startMs, authVisibleNow, GRACE_MS, TIMEOUT_MS);
    }

    public WaitUserLogic(long startMs, boolean authVisibleNow, long graceMs, long timeoutMs) {
        this.start = startMs;
        this.grace = graceMs;
        this.timeout = timeoutMs;
        this.seenAuth = authVisibleNow;
        this.authAt = startMs;
    }

    /** A window change happened. */
    public void onWindow(boolean isAuth, long now) {
        if (isAuth) {
            if (!seenAuth) authAt = now;
            seenAuth = true;
            gone = false;
        } else if (seenAuth) {
            gone = true;
        }
    }

    public State tick(long now) {
        if (seenAuth && gone) return State.DONE;
        if (!seenAuth) return now - start >= grace ? State.DONE : State.WAITING_PROMPT;
        return now - authAt >= timeout ? State.TIMEOUT : State.WAITING_USER;
    }

    /** True exactly once: when the owner should be told to act. */
    public boolean shouldNotify() {
        if (seenAuth && !gone && !notified) {
            notified = true;
            return true;
        }
        return false;
    }

    public boolean promptWasShown() {
        return seenAuth;
    }
}
