package uz.saydo.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uz.saydo.app.data.TouchPoint;
import uz.saydo.app.util.GestureResampler;

import static org.junit.Assert.*;

/**
 * Comprehensive Unit Tests for GestureResampler & Spline Interpolation Engine.
 */
public class GestureResamplerTest {

    @Test
    public void testClampNormal() {
        assertEquals(0.5, GestureResampler.clamp(0.5, 0.0, 1.0), 1e-6);
        assertEquals(0.0, GestureResampler.clamp(-0.25, 0.0, 1.0), 1e-6);
        assertEquals(1.0, GestureResampler.clamp(1.45, 0.0, 1.0), 1e-6);
    }

    @Test
    public void testClampNanAndInfinity() {
        assertEquals(0.0, GestureResampler.clamp(Double.NaN, 0.0, 1.0), 1e-6);
        assertEquals(0.0, GestureResampler.clamp(Double.NEGATIVE_INFINITY, 0.0, 1.0), 1e-6);
        assertEquals(0.0, GestureResampler.clamp(Double.POSITIVE_INFINITY, 0.0, 1.0), 1e-6);
    }

    @Test
    public void testResampleEmptyOrSingle() {
        assertTrue(GestureResampler.resampleTimeProportional(null, 10).isEmpty());
        assertTrue(GestureResampler.resampleTimeProportional(Collections.emptyList(), 10).isEmpty());

        List<TouchPoint> single = Collections.singletonList(new TouchPoint(0.2, 0.4, 100));
        List<TouchPoint> result = GestureResampler.resampleTimeProportional(single, 5);
        assertEquals(1, result.size());
        assertEquals(0.2, result.get(0).x, 1e-6);
        assertEquals(0.4, result.get(0).y, 1e-6);
    }

    @Test
    public void testResampleTwoPointsMonotonicity() {
        List<TouchPoint> points = new ArrayList<>();
        points.add(new TouchPoint(0.1, 0.1, 0));
        points.add(new TouchPoint(0.9, 0.9, 1000));

        int target = 10;
        List<TouchPoint> resampled = GestureResampler.resampleTimeProportional(points, target);
        assertEquals(target, resampled.size());

        // First point matches start
        assertEquals(0.1, resampled.get(0).x, 1e-4);
        assertEquals(0.1, resampled.get(0).y, 1e-4);
        assertEquals(0, resampled.get(0).t);

        // Last point matches end
        assertEquals(0.9, resampled.get(target - 1).x, 1e-4);
        assertEquals(0.9, resampled.get(target - 1).y, 1e-4);
        assertEquals(1000, resampled.get(target - 1).t);

        // Check strictly increasing timestamps and coordinates
        for (int i = 1; i < resampled.size(); i++) {
            assertTrue(resampled.get(i).t > resampled.get(i - 1).t);
            assertTrue(resampled.get(i).x > resampled.get(i - 1).x);
            assertTrue(resampled.get(i).y > resampled.get(i - 1).y);
        }
    }

    @Test
    public void testCatmullRomSplineBounds() {
        List<TouchPoint> points = new ArrayList<>();
        points.add(new TouchPoint(0.1, 0.2, 0));
        points.add(new TouchPoint(0.3, 0.8, 200));
        points.add(new TouchPoint(0.7, 0.4, 400));
        points.add(new TouchPoint(0.9, 0.9, 600));

        List<TouchPoint> smoothed = GestureResampler.smoothSpline(points);
        assertEquals(points.size(), smoothed.size());

        for (TouchPoint pt : smoothed) {
            assertTrue("x should be >= 0.0", pt.x >= 0.0);
            assertTrue("x should be <= 1.0", pt.x <= 1.0);
            assertTrue("y should be >= 0.0", pt.y >= 0.0);
            assertTrue("y should be <= 1.0", pt.y <= 1.0);
        }
    }

    @Test
    public void testSegmentByDurationShortPath() {
        List<TouchPoint> points = new ArrayList<>();
        points.add(new TouchPoint(0.1, 0.1, 0));
        points.add(new TouchPoint(0.5, 0.5, 5000));
        points.add(new TouchPoint(0.9, 0.9, 10000));

        List<List<TouchPoint>> segments = GestureResampler.segmentByDuration(points, 59000);
        assertEquals(1, segments.size());
        assertEquals(3, segments.get(0).size());
    }

    @Test
    public void testSegmentByDurationLongPath() {
        // Path spans 150 seconds (150,000ms), max segment 50,000ms -> should produce 3 segments
        List<TouchPoint> points = new ArrayList<>();
        for (int i = 0; i <= 150; i++) {
            points.add(new TouchPoint(i / 150.0, i / 150.0, i * 1000L));
        }

        long maxSeg = 50000L;
        List<List<TouchPoint>> segments = GestureResampler.segmentByDuration(points, maxSeg);
        assertEquals(3, segments.size());

        for (int s = 0; s < segments.size(); s++) {
            List<TouchPoint> seg = segments.get(s);
            long dur = seg.get(seg.size() - 1).t - seg.get(0).t;
            assertTrue("Segment " + s + " duration " + dur + " should be <= " + maxSeg, dur <= maxSeg);
        }

        // Verify continuity overlap: last point of segment 0 == first point of segment 1
        TouchPoint seg0End = segments.get(0).get(segments.get(0).size() - 1);
        TouchPoint seg1Start = segments.get(1).get(0);
        assertEquals(seg0End.t, seg1Start.t);
        assertEquals(seg0End.x, seg1Start.x, 1e-6);
        assertEquals(seg0End.y, seg1Start.y, 1e-6);
    }
}
