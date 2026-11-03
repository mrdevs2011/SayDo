package uz.saydo.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit Test Suite for Macro Studio Timeline Step Editing (Pillar 6).
 */
public class MacroStudioTimelineTest {

    @Test
    public void testTimelineStepAdjustment() {
        List<Gesture> gestures = new ArrayList<>();
        gestures.add(Gesture.createTap(0.2, 0.4, 100, 120));
        gestures.add(Gesture.createHold(0.5, 0.5, 200, 600));

        Macro m = new Macro("m1", "test macro", gestures, false, 1000L, 600);
        assertEquals(2, m.getGestureCount());

        // Modify first step delay +50ms
        Gesture g0 = gestures.get(0);
        Gesture g0Updated = new Gesture(g0.type, g0.action, g0.x, g0.y, g0.startTime, g0.startTime + 70, 70, g0.points);
        gestures.set(0, g0Updated);

        Macro updatedMacro = m.withGestures(gestures);
        assertEquals(2, updatedMacro.getGestureCount());
        assertEquals(70, updatedMacro.gestures.get(0).duration);
    }

    @Test
    public void testTimelineStepDeletion() {
        List<Gesture> gestures = new ArrayList<>();
        gestures.add(Gesture.createTap(0.1, 0.1, 100, 120));
        gestures.add(Gesture.createTap(0.2, 0.2, 200, 220));
        gestures.add(Gesture.createTap(0.3, 0.3, 300, 320));

        Macro m = new Macro("m2", "triple tap", gestures, false, 1000L, 320);
        assertEquals(3, m.getGestureCount());

        // Delete accidental middle step
        gestures.remove(1);
        Macro updated = m.withGestures(gestures);
        assertEquals(2, updated.getGestureCount());
        assertEquals(0.1, updated.gestures.get(0).x, 0.001);
        assertEquals(0.3, updated.gestures.get(1).x, 0.001);
    }

    @Test
    public void testAliasesAndSensitivityUpdate() {
        List<Gesture> gestures = new ArrayList<>();
        gestures.add(Gesture.createTap(0.5, 0.5, 100, 120));
        Macro m = new Macro("m3", "open cam", gestures, false, 1000L, 120);

        assertFalse(m.sensitive);
        assertTrue(m.aliases.isEmpty());

        List<String> aliases = new ArrayList<>();
        aliases.add("camera");
        aliases.add("take photo");
        Macro withAliases = m.withAliases(aliases);
        assertEquals(2, withAliases.aliases.size());
        assertTrue(withAliases.aliases.contains("camera"));

        Macro sensitive = withAliases.withSensitive(true);
        assertTrue(sensitive.sensitive);
    }
}
