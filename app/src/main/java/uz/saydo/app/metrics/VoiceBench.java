package uz.saydo.app.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Voice benchmark session: prompts triggers in shuffled rounds and scores
 * what the recognizer actually matched. Pure Java — timing is passed in.
 *
 * While a session is active, VoiceService reports matches here instead of
 * running the macro.
 */
public final class VoiceBench {

    public enum Res { HIT, WRONG, MISS }

    public static final class Trial {
        public final String expected;
        public final String got;
        public final Res res;
        public final long latencyMs;

        Trial(String expected, String got, Res res, long latencyMs) {
            this.expected = expected;
            this.got = got;
            this.res = res;
            this.latencyMs = latencyMs;
        }
    }

    /** Main-thread callback for the UI; VoiceService calls into the active session. */
    public interface Listener {
        void onTrial(Trial t);
    }

    private static volatile VoiceBench active;

    public static VoiceBench active() {
        return active;
    }

    public static void setActive(VoiceBench b) {
        active = b;
    }

    private final List<String> plan = new ArrayList<>();
    private final List<Trial> trials = new ArrayList<>();
    private int index = 0;
    private long promptAt = -1;
    private String lastHeard = "";
    private Listener listener;

    public VoiceBench(List<String> triggers, int rounds, Random rnd) {
        if (triggers == null || triggers.isEmpty() || rounds <= 0) return;
        for (int r = 0; r < rounds; r++) {
            List<String> round = new ArrayList<>(triggers);
            Collections.shuffle(round, rnd);
            // Avoid the same trigger twice in a row across round borders
            if (!plan.isEmpty() && round.size() > 1 && round.get(0).equals(plan.get(plan.size() - 1))) {
                Collections.swap(round, 0, round.size() - 1);
            }
            plan.addAll(round);
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int total() {
        return plan.size();
    }

    public int position() {
        return index;
    }

    public boolean isDone() {
        return index >= plan.size();
    }

    /** Trigger the user must say now, or null when finished. */
    public String current() {
        return isDone() ? null : plan.get(index);
    }

    /** Whether a prompt is showing and waiting for speech. */
    public boolean isWaiting() {
        return promptAt >= 0 && !isDone();
    }

    public void prompt(long now) {
        if (isDone()) return;
        promptAt = now;
        lastHeard = "";
    }

    public void onHeard(String text) {
        if (text != null) lastHeard = text;
    }

    /**
     * Recognizer matched a trigger. Ignored when no prompt is waiting.
     * @param sttLatencyMs end-of-speech → match time from the recognizer, -1 if unknown
     *                     (user reaction time is deliberately not counted)
     */
    public Trial onMatch(String trigger, long sttLatencyMs) {
        if (!isWaiting()) return null;
        String exp = plan.get(index);
        Res res = exp.equals(trigger) ? Res.HIT : Res.WRONG;
        return record(new Trial(exp, trigger == null ? "" : trigger, res, sttLatencyMs));
    }

    /** Prompt time ran out without a match. */
    public Trial onTimeout() {
        if (!isWaiting()) return null;
        return record(new Trial(plan.get(index), lastHeard, Res.MISS, -1));
    }

    private Trial record(Trial t) {
        trials.add(t);
        index++;
        promptAt = -1;
        if (listener != null) listener.onTrial(t);
        return t;
    }

    public List<Trial> trials() {
        return Collections.unmodifiableList(trials);
    }

    public int count(Res res) {
        int n = 0;
        for (Trial t : trials) if (t.res == res) n++;
        return n;
    }
}
