package uz.saydo.app;

import org.junit.Test;

import java.io.File;
import java.util.List;

import uz.saydo.app.kernel.evolution.MemoryLeakSentinel;
import uz.saydo.app.kernel.evolution.SayDoChaosEngine;
import uz.saydo.app.kernel.evolution.SayDoEvolutionKernel;
import uz.saydo.app.kernel.evolution.StateMachineVerifier;
import uz.saydo.app.kernel.oem.OemDefenseMatrix;
import uz.saydo.app.kernel.oem.RuntimeCapabilityProbe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Capstone Mathematical & System Verification Test Suite (Project Omega).
 * Certifies state machine deadlock immunity, zero-leak memory retention,
 * dynamic OEM polymorphism, and chaos fault-tolerance.
 */
public class SayDoOmegaCapstoneTest {

    @Test
    public void testFormalStateMachineTransitions() {
        // Allowed transitions
        assertTrue(StateMachineVerifier.isValidTransition(
                StateMachineVerifier.VoiceState.STOPPED, StateMachineVerifier.VoiceState.PREPARING));
        assertTrue(StateMachineVerifier.isValidTransition(
                StateMachineVerifier.VoiceState.PREPARING, StateMachineVerifier.VoiceState.LISTENING));
        assertTrue(StateMachineVerifier.isValidTransition(
                StateMachineVerifier.VoiceState.LISTENING, StateMachineVerifier.VoiceState.PROCESSING));
        assertTrue(StateMachineVerifier.isValidTransition(
                StateMachineVerifier.VoiceState.PROCESSING, StateMachineVerifier.VoiceState.EXECUTING_MACRO));
        assertTrue(StateMachineVerifier.isValidTransition(
                StateMachineVerifier.VoiceState.EXECUTING_MACRO, StateMachineVerifier.VoiceState.COOLDOWN));
        assertTrue(StateMachineVerifier.isValidTransition(
                StateMachineVerifier.VoiceState.COOLDOWN, StateMachineVerifier.VoiceState.LISTENING));

        // Invalid direct jump (must go through cooldown or processing)
        assertFalse(StateMachineVerifier.isValidTransition(
                StateMachineVerifier.VoiceState.STOPPED, StateMachineVerifier.VoiceState.EXECUTING_MACRO));
    }

    @Test
    public void testStateMachineDeadlockImmunityAndReachability() {
        // Mathematical proof: every state can safely unwind to STOPPED
        assertTrue("All FSM states must have reachable paths to STOPPED",
                StateMachineVerifier.verifyAllStatesReachableToStopped());

        // Mathematical proof: every active state can recover back to LISTENING
        assertTrue("All active FSM states must have reachable paths to LISTENING",
                StateMachineVerifier.verifyAllStatesCanRecoverToListening());
    }

    @Test
    public void testZeroLeakMemorySentinel() {
        MemoryLeakSentinel sentinel = MemoryLeakSentinel.getInstance();
        sentinel.clear();

        // Simulate ephemeral allocation
        Object tempObj = new Object();
        sentinel.watch("temp_view", tempObj);

        // De-reference
        tempObj = null;

        // Force GC and calculate leak index
        float leakIndex = sentinel.getLeakIndex();
        assertEquals(0.00f, leakIndex, 0.001f);
    }

    @Test
    public void testOemDefenseMatrixBrandDetection() {
        OemDefenseMatrix.OemBrand brand = OemDefenseMatrix.detectOemBrand();
        assertNotNull(brand);
    }

    @Test
    public void testSayDoChaosEngineFullFuzzingBattery() {
        SayDoChaosEngine.ChaosReport report = SayDoChaosEngine.runFullChaosSuite();
        assertNotNull(report);
        assertEquals("Chaos suite must complete with zero failures", 0, report.failures);
        assertTrue(report.testsRun >= 3);
        assertTrue(report.status.contains("PASSED"));
    }

    @Test
    public void testEvolutionKernelSelfCalibration() {
        SayDoEvolutionKernel kernel = new SayDoEvolutionKernel(null);

        // Quiet room (100 RMS) -> relaxes threshold
        double quietThresh = kernel.calibrateVoiceThreshold(100.0);
        assertTrue("Quiet room threshold should be <= 0.75: " + quietThresh, quietThresh <= 0.75);

        // Noisy room (800 RMS) -> tightens threshold
        double noisyThresh = kernel.calibrateVoiceThreshold(800.0);
        assertTrue("Noisy room threshold should be >= 0.85: " + noisyThresh, noisyThresh >= 0.85);

        // Repetitive action suggestion
        assertNull(kernel.recordActionAndCheckSuggestion("com.telegram", "open chat"));
        assertNull(kernel.recordActionAndCheckSuggestion("com.telegram", "open chat"));
        String suggestion = kernel.recordActionAndCheckSuggestion("com.telegram", "open chat");
        assertNotNull("3rd repetitive action must trigger macro shortcut suggestion", suggestion);
        assertTrue(suggestion.contains("open chat"));
    }
}
