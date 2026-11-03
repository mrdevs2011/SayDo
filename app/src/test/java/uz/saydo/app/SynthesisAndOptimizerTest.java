package uz.saydo.app;

import org.junit.Test;

import java.util.List;

import uz.saydo.app.data.Macro;
import uz.saydo.app.kernel.synthesis.MacroSynthesizer;
import uz.saydo.app.kernel.synthesis.NeuralVlmEngine;
import uz.saydo.app.kernel.synthesis.PathOptimizer;
import uz.saydo.app.kernel.synthesis.RecipeCatalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit Test Suite for Autonomous Synthesis, Edge SLM Reasoning & Q-Learning Optimizer (Infinity Layer).
 */
public class SynthesisAndOptimizerTest {

    @Test
    public void testNaturalLanguageMacroSynthesis() {
        Macro ytMacro = MacroSynthesizer.synthesize("search interstellar on youtube");
        assertNotNull(ytMacro);
        assertTrue(ytMacro.trigger.contains("{query}"));
        assertTrue("Synthesized macro must contain gesture steps", ytMacro.getGestureCount() >= 2);
        assertNotNull(ytMacro.gestures.get(0).anchor);

        Macro camMacro = MacroSynthesizer.synthesize("open camera and take photo");
        assertNotNull(camMacro);
        assertEquals("take photo", camMacro.trigger);
        assertTrue(camMacro.getGestureCount() >= 1);
    }

    @Test
    public void testNeuralVlmEngineSemanticInspection() {
        String screenContext = "Order summary: 3 items. Total amount: 45000 UZS. Free delivery.";
        NeuralVlmEngine.InspectionResult res = NeuralVlmEngine.inspectScreen("Is total price below threshold?", screenContext);

        assertTrue(res.passed);
        assertEquals("45000", res.extractedData);
        assertTrue(res.confidence >= 0.90f);

        String summary = NeuralVlmEngine.summarizeAction("search lofi on youtube", 3);
        assertTrue(summary.contains("search lofi on youtube"));
        assertTrue(summary.contains("3 actions"));
    }

    @Test
    public void testPathOptimizerQUpdates() {
        PathOptimizer optimizer = new PathOptimizer();

        assertEquals(0.0, optimizer.getQValue("app_home", "prune_delay"), 0.001);

        // Positive reinforcement update
        optimizer.updateQValue("app_home", "prune_delay", 10.0, "app_next");
        double q1 = optimizer.getQValue("app_home", "prune_delay");
        assertTrue("Q-value must increase after positive reward: " + q1, q1 > 1.0);

        // Multiple updates converge
        for (int i = 0; i < 5; i++) {
            optimizer.updateQValue("app_home", "prune_delay", 10.0, "app_next");
        }
        double q2 = optimizer.getQValue("app_home", "prune_delay");
        assertTrue(q2 > q1);

        // Delay optimization pruning
        long originalDelay = 500;
        long pruned = optimizer.optimizeDelay(originalDelay, "app_home");
        assertTrue("High Q-value should prune delay: " + pruned, pruned < originalDelay);
    }

    @Test
    public void testRecipeCatalog() {
        List<Macro> recipes = RecipeCatalog.getCuratedRecipes();
        assertNotNull(recipes);
        assertFalse(recipes.isEmpty());

        Macro ytRecipe = RecipeCatalog.findRecipeByTrigger("search {query} on youtube");
        assertNotNull(ytRecipe);
        assertEquals("Media", ytRecipe.category);
        assertTrue(ytRecipe.locked);

        Macro camRecipe = RecipeCatalog.findRecipeByTrigger("take photo");
        assertNotNull(camRecipe);
        assertEquals("Camera", camRecipe.category);
    }
}
