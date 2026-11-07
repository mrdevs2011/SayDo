package uz.saydo.app.safety;

/**
 * Recording side of the "wait for user" step (pure Java, unit tested).
 *
 * Feed it the window changes. While an auth prompt is in front the recorder must not record normal
 * steps. When the prompt goes away:
 *  - if the owner tapped something inside it (a PIN pad), those taps were recorded as sensitive steps
 *    and nothing else is added;
 *  - if the owner touched nothing (fingerprint or face), a wait_user step is added.
 */
public final class AuthRecordLogic {
    public enum Action {
        /** Not an auth window and none was open: handle the event normally. */
        PASS,
        /** Inside the auth prompt, or the event that closed it after PIN taps: record nothing else. */
        CONSUME,
        /** The prompt closed and the owner touched nothing: record a wait_user step, consume the event. */
        EMIT_WAIT_USER
    }

    private boolean inAuth;
    private boolean touched;
    private long openedAt;

    public void reset() {
        inAuth = false;
        touched = false;
        openedAt = 0;
    }

    public boolean inAuth() {
        return inAuth;
    }

    public long openedAt() {
        return openedAt;
    }

    public Action onWindow(String pkg, String cls, String title, long now) {
        boolean auth = AuthDetector.isAuthWindow(pkg, cls, title);
        if (auth) {
            if (!inAuth) {
                inAuth = true;
                touched = false;
                openedAt = now;
            }
            return Action.CONSUME;
        }
        if (inAuth) {
            if (AuthDetector.isNeutralDuringAuth(pkg, cls)) return Action.CONSUME;
            inAuth = false;
            return touched ? Action.CONSUME : Action.EMIT_WAIT_USER;
        }
        return Action.PASS;
    }

    /** A click inside the prompt: the owner is using a PIN, not a fingerprint. */
    public void onTouchInAuth() {
        if (inAuth) touched = true;
    }
}
