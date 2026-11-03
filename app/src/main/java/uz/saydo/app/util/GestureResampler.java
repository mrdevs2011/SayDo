package uz.saydo.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uz.saydo.app.data.TouchPoint;

/**
 * High-precision Gesture Resampler and Spline Interpolator.
 * Implements Catmull-Rom cubic spline interpolation, velocity-aware resampling,
 * coordinate boundary clamping, and multi-stroke duration segmentation.
 */
public final class GestureResampler {

    public static final long MAX_RECOMMENDED_DURATION_MS = 59000L; // Safe margin below 60s
    public static final int DEFAULT_MAX_SEGMENTS = 200;

    private GestureResampler() {}

    /**
     * Clamps a coordinate strictly between min and max.
     */
    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * Resamples touch stream using time-proportional interpolation.
     * Ensures dense sampling during slow deliberate movement and sparse sampling during rapid swipes.
     */
    public static List<TouchPoint> resampleTimeProportional(List<TouchPoint> points, int targetSegments) {
        if (points == null || points.isEmpty()) return Collections.emptyList();
        if (points.size() == 1 || targetSegments <= 1) {
            TouchPoint p = points.get(0);
            return Collections.singletonList(new TouchPoint(p.x, p.y, p.t));
        }

        int n = points.size();
        long t0 = points.get(0).t;
        long totalDuration = points.get(n - 1).t - t0;
        if (totalDuration <= 0) totalDuration = 1;

        int segments = Math.max(2, Math.min(targetSegments, Math.max(n, DEFAULT_MAX_SEGMENTS)));
        List<TouchPoint> result = new ArrayList<>(segments);

        int srcIdx = 0;
        for (int si = 0; si < segments; si++) {
            double targetT = t0 + (totalDuration * (double) si / (segments - 1));

            while (srcIdx < n - 2 && points.get(srcIdx + 1).t <= targetT) {
                srcIdx++;
            }

            TouchPoint p1 = points.get(srcIdx);
            TouchPoint p2 = points.get(Math.min(srcIdx + 1, n - 1));

            long segDt = p2.t - p1.t;
            double frac = (segDt > 0) ? (targetT - p1.t) / (double) segDt : 0.0;
            frac = clamp(frac, 0.0, 1.0);

            double rx = clamp(p1.x + (p2.x - p1.x) * frac, 0.0, 1.0);
            double ry = clamp(p1.y + (p2.y - p1.y) * frac, 0.0, 1.0);
            long rt = Math.round(targetT);

            result.add(new TouchPoint(rx, ry, rt));
        }

        return result;
    }

    /**
     * Catmull-Rom cubic spline interpolation across 4 control points.
     */
    public static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1) +
                      (-p0 + p2) * t +
                      (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                      (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    /**
     * Smooths an irregular series of TouchPoints using Catmull-Rom cubic splines.
     */
    public static List<TouchPoint> smoothSpline(List<TouchPoint> points) {
        if (points == null || points.size() < 3) {
            return points != null ? new ArrayList<>(points) : Collections.emptyList();
        }

        int n = points.size();
        List<TouchPoint> smoothed = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            TouchPoint p0 = points.get(Math.max(0, i - 1));
            TouchPoint p1 = points.get(i);
            TouchPoint p2 = points.get(Math.min(n - 1, i + 1));
            TouchPoint p3 = points.get(Math.min(n - 1, i + 2));

            double sx = catmullRom(p0.x, p1.x, p2.x, p3.x, 0.5);
            double sy = catmullRom(p0.y, p1.y, p2.y, p3.y, 0.5);

            // Midpoint smoothing combined with original to preserve sharp corners
            double finalX = clamp(0.5 * (p1.x + sx), 0.0, 1.0);
            double finalY = clamp(0.5 * (p1.y + sy), 0.0, 1.0);

            smoothed.add(new TouchPoint(finalX, finalY, p1.t));
        }

        return smoothed;
    }

    /**
     * Splits long gesture paths exceeding maxDurationMs into contiguous stroke segments.
     */
    public static List<List<TouchPoint>> segmentByDuration(List<TouchPoint> points, long maxDurationMs) {
        if (points == null || points.isEmpty()) return Collections.emptyList();
        if (maxDurationMs <= 0) maxDurationMs = MAX_RECOMMENDED_DURATION_MS;

        List<List<TouchPoint>> segments = new ArrayList<>();
        int n = points.size();
        long segStartT = points.get(0).t;
        List<TouchPoint> currentSeg = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            TouchPoint pt = points.get(i);
            currentSeg.add(pt);

            if (pt.t - segStartT >= maxDurationMs && i < n - 1) {
                segments.add(new ArrayList<>(currentSeg));
                currentSeg.clear();
                // Overlap starting point with previous ending point for seamless stroke continuation
                currentSeg.add(pt);
                segStartT = pt.t;
            }
        }

        if (!currentSeg.isEmpty()) {
            segments.add(currentSeg);
        }

        return segments;
    }
}
