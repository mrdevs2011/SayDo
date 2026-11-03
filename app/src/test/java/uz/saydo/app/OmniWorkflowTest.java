package uz.saydo.app;

import org.junit.Test;

import java.util.List;

import uz.saydo.app.data.TouchPoint;
import uz.saydo.app.kernel.gesture.HumanizedTouchEngine;
import uz.saydo.app.kernel.workflow.BranchEvaluator;
import uz.saydo.app.kernel.workflow.ContextMemory;
import uz.saydo.app.kernel.workflow.StepCondition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit Test Suite for Omni Cross-App Workflows, Memory Registers & Logic (Omni Layer).
 */
public class OmniWorkflowTest {

    @Test
    public void testStepConditionFactory() {
        StepCondition pkgCond = StepCondition.waitForPackage("com.google.android.youtube", 5000);
        assertEquals(StepCondition.TYPE_WAIT_FOR_PACKAGE, pkgCond.type);
        assertEquals("com.google.android.youtube", pkgCond.targetValue);
        assertEquals(5000, pkgCond.timeoutMs);
        assertTrue(pkgCond.isSatisfied(null, "com.google.android.youtube"));
        assertFalse(pkgCond.isSatisfied(null, "com.other.app"));

        StepCondition textCond = StepCondition.waitForText("Ready", 3000);
        assertEquals(StepCondition.TYPE_WAIT_FOR_TEXT, textCond.type);
        assertEquals("Ready", textCond.targetValue);
        assertEquals(3000, textCond.timeoutMs);
    }

    @Test
    public void testContextMemoryRegisters() {
        ContextMemory memory = ContextMemory.getInstance();
        memory.clear();

        assertEquals("", memory.get(ContextMemory.REG_0));
        memory.set(ContextMemory.REG_0, "12345");
        assertEquals("12345", memory.get(ContextMemory.REG_0));
        assertTrue(memory.has(ContextMemory.REG_0));

        memory.set(ContextMemory.CLIPBOARD, "copied text");
        assertEquals("copied text", memory.get(ContextMemory.CLIPBOARD));
    }

    @Test
    public void testContextMemoryOtpRegexExtraction() {
        ContextMemory memory = ContextMemory.getInstance();
        memory.clear();

        String smsBody = "Your One-Time Password is 849201. Valid for 5 minutes.";
        boolean extracted = memory.extractPattern(smsBody, "\\b\\d{6}\\b", ContextMemory.LAST_OTP);

        assertTrue(extracted);
        assertEquals("849201", memory.get(ContextMemory.LAST_OTP));
        assertEquals("849201", memory.get(ContextMemory.LAST_EXTRACTED_TEXT));
    }

    @Test
    public void testContextMemoryTemplateInterpolation() {
        ContextMemory memory = ContextMemory.getInstance();
        memory.clear();

        memory.set(ContextMemory.LAST_OTP, "987654");
        memory.set(ContextMemory.REG_1, "Alice");

        String interpolated = memory.interpolate("Send code {$LAST_OTP} to {$REG_1}");
        assertEquals("Send code 987654 to Alice", interpolated);
    }

    @Test
    public void testBranchEvaluator() {
        ContextMemory memory = ContextMemory.getInstance();
        memory.clear();
        memory.set(ContextMemory.REG_0, "success");

        BranchEvaluator.BranchCondition cond1 = new BranchEvaluator.BranchCondition(
                BranchEvaluator.Operator.REGISTER_EQUALS,
                ContextMemory.REG_0,
                "success",
                5, // target true
                10 // target false
        );
        assertEquals(5, BranchEvaluator.evaluateBranch(cond1, null, memory, 1));

        BranchEvaluator.BranchCondition cond2 = new BranchEvaluator.BranchCondition(
                BranchEvaluator.Operator.REGISTER_EQUALS,
                ContextMemory.REG_0,
                "failed",
                5,
                10
        );
        assertEquals(10, BranchEvaluator.evaluateBranch(cond2, null, memory, 1));
    }

    @Test
    public void testHumanizedTouchEngineFittsDuration() {
        // Distance 600px, button 48px -> realistic duration ~500-800ms
        long dur = HumanizedTouchEngine.calculateFittsDuration(600, 48);
        assertTrue("Duration should be >= 120 and <= 1800, was: " + dur, dur >= 120 && dur <= 1800);
    }

    @Test
    public void testHumanizedTouchEngineSwipeGeneration() {
        List<TouchPoint> path = HumanizedTouchEngine.generateHumanizedSwipe(
                0.2, 0.8,
                0.8, 0.2,
                100, 400
        );
        assertNotNull(path);
        assertTrue("Should produce multiple interpolated trajectory points", path.size() >= 10);
        assertEquals(100, path.get(0).t);
        assertEquals(500, path.get(path.size() - 1).t);
        // Start and end points within tolerance
        assertTrue(path.get(0).x >= 0.18 && path.get(0).x <= 0.22);
    }
}
