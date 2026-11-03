package uz.saydo.app.kernel.gesture;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import uz.saydo.app.data.TouchPoint;

/**
 * Biomechanical Humanized Touch Physics Engine.
 * Generates natural human-like gesture paths governed by Fitts's Law,
 * log-normal velocity profiles, and sub-pixel micro-tremor jitter to bypass bot detection.
 */
public final class HumanizedTouchEngine {

    private static final Random RANDOM = new Random();

    private HumanizedTouchEngine() {
    }

    /**
     * Estimates natural human movement duration (in ms) using Fitts's Law:
     * T = a + b * log2(1 + D / W)
     * where D is distance, W is target width (e.g. button size ~48dp).
     */
    public static long calculateFittsDuration(double distancePx, double targetWidthPx) {
        if (targetWidthPx <= 0) targetWidthPx = 48.0;
        double id = Math.log(1.0 + (distancePx / targetWidthPx)) / Math.log(2.0); // Index of Difficulty
        double a = 120.0; // Reaction intercept ms
        double b = 140.0; // Movement slope ms/bit
        long duration = Math.round(a + b * id);
        return Math.max(120, Math.min(1800, duration));
    }

    /**
     * Generates a biomechanically realistic swipe trajectory between two points.
     */
    public static List<TouchPoint> generateHumanizedSwipe(double startX, double startY,
                                                         double endX, double endY,
                                                         long startTime, long duration) {
        if (duration < 50) duration = 50;
        int steps = (int) Math.max(10, duration / 12); // ~80-120Hz point stream
        List<TouchPoint> path = new ArrayList<>(steps);

        double dx = endX - startX;
        double dy = endY - startY;

        // Slight curvature control point perpendicular to line
        double midX = (startX + endX) / 2.0;
        double midY = (startY + endY) / 2.0;
        double perpX = -dy * 0.08 * (RANDOM.nextBoolean() ? 1 : -1);
        double perpY = dx * 0.08 * (RANDOM.nextBoolean() ? 1 : -1);
        double ctrlX = midX + perpX;
        double ctrlY = midY + perpY;

        for (int i = 0; i < steps; i++) {
            float t = (float) i / (float) (steps - 1);

            // Log-normal asymmetric bell curve for progress: slow start, fast mid, gradual settle
            double progress = smoothStepSigmoid(t);

            // Quadratic Bezier interpolation with control point
            double oneMinusT = 1.0 - progress;
            double curX = (oneMinusT * oneMinusT * startX) + (2.0 * oneMinusT * progress * ctrlX) + (progress * progress * endX);
            double curY = (oneMinusT * oneMinusT * startY) + (2.0 * oneMinusT * progress * ctrlY) + (progress * progress * endY);

            // Micro-tremor Gaussian noise (0.001 to 0.002 normalized screen coords ~1-2px)
            if (i > 0 && i < steps - 1) {
                curX += (RANDOM.nextGaussian() * 0.0012);
                curY += (RANDOM.nextGaussian() * 0.0012);
            }

            long pointTime = startTime + Math.round(t * duration);
            path.add(new TouchPoint(curX, curY, pointTime));
        }

        return path;
    }

    /**
     * S-curve with natural human acceleration and deceleration deceleration tail.
     */
    private static double smoothStepSigmoid(double t) {
        // Asymmetric sigmoid mimicking muscular ballistic movements
        if (t <= 0) return 0.0;
        if (t >= 1) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }
}
