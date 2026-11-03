package uz.saydo.app.kernel.synthesis;

import java.util.HashMap;
import java.util.Map;

/**
 * On-Device Reinforcement Learning Path Optimizer (Self-Evolving Macros).
 * Learns faster execution transitions and automatically prunes redundant delays using Q-learning.
 */
public final class PathOptimizer {

    private static final double ALPHA = 0.20; // Learning rate
    private static final double GAMMA = 0.90; // Discount factor

    // Q-Table: State -> (Action -> Q-Value)
    private final Map<String, Map<String, Double>> qTable = new HashMap<>();

    public PathOptimizer() {
    }

    /**
     * Retrieves expected reward / Q-value for an action in a given state.
     */
    public synchronized double getQValue(String state, String action) {
        Map<String, Double> actions = qTable.get(state);
        if (actions == null) return 0.0;
        Double val = actions.get(action);
        return val != null ? val : 0.0;
    }

    /**
     * Updates Q-value using Bellman Equation:
     * Q(s, a) = Q(s, a) + alpha * [reward + gamma * max(Q(s', a')) - Q(s, a)]
     */
    public synchronized void updateQValue(String state, String action, double reward, String nextState) {
        Map<String, Double> actions = qTable.computeIfAbsent(state, k -> new HashMap<>());
        double currentQ = actions.getOrDefault(action, 0.0);

        double maxNextQ = 0.0;
        Map<String, Double> nextActions = qTable.get(nextState);
        if (nextActions != null) {
            for (double q : nextActions.values()) {
                if (q > maxNextQ) maxNextQ = q;
            }
        }

        double newQ = currentQ + ALPHA * (reward + (GAMMA * maxNextQ) - currentQ);
        actions.put(action, newQ);
    }

    /**
     * Prunes redundant gesture delay between two steps if high confidence has been achieved.
     */
    public long optimizeDelay(long recordedDelayMs, String transitionKey) {
        double q = getQValue(transitionKey, "prune_delay");
        if (q > 5.0 && recordedDelayMs > 150) {
            // Safe to compress delay by up to 35%
            return Math.max(80, Math.round(recordedDelayMs * 0.65));
        }
        return recordedDelayMs;
    }

    public synchronized void reset() {
        qTable.clear();
    }
}
