package uz.saydo.app;

import org.junit.Test;

import uz.saydo.app.kernel.vision.OfflineOcrMatcher;
import uz.saydo.app.kernel.vision.VisualAnchorEngine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit Test Suite for Computer Vision Grounding & Perceptual Hashing (Omni Dimension 1).
 */
public class VisualAnchorTest {

    @Test
    public void testDifferenceHashComputation() {
        int[] lum72 = new int[72];
        for (int i = 0; i < 72; i++) {
            lum72[i] = (i % 2 == 0) ? 200 : 50;
        }

        long hash = VisualAnchorEngine.computeDifferenceHash(lum72);
        assertTrue(hash != 0L);

        // Identical input must produce identical hash
        long hash2 = VisualAnchorEngine.computeDifferenceHash(lum72);
        assertEquals(hash, hash2);
    }

    @Test
    public void testHammingDistanceAndSimilarity() {
        long h1 = 0b10101010L;
        long h2 = 0b10101010L;
        assertEquals(0, VisualAnchorEngine.hammingDistance(h1, h2));
        assertEquals(1.0f, VisualAnchorEngine.hashSimilarity(h1, h2), 0.001f);

        long h3 = 0b10101011L; // 1 bit different
        assertEquals(1, VisualAnchorEngine.hammingDistance(h1, h3));
        assertEquals(1.0f - (1.0f / 64.0f), VisualAnchorEngine.hashSimilarity(h1, h3), 0.001f);
    }

    @Test
    public void testTemplateMatching() {
        int screenW = 20;
        int screenH = 20;
        int[] screen = new int[screenW * screenH];

        // Background luminance 20
        for (int i = 0; i < screen.length; i++) screen[i] = 20;

        // Place a high contrast 4x4 square at (8, 8)
        int tplW = 4;
        int tplH = 4;
        int[] template = new int[tplW * tplH];
        for (int ty = 0; ty < tplH; ty++) {
            for (int tx = 0; tx < tplW; tx++) {
                int val = ((tx + ty) % 2 == 0) ? 240 : 40;
                template[ty * tplW + tx] = val;
                screen[(8 + ty) * screenW + (8 + tx)] = val;
            }
        }

        VisualAnchorEngine.VisualMatch match = VisualAnchorEngine.matchTemplate(
                screen, screenW, screenH,
                template, tplW, tplH,
                0.70f
        );

        assertTrue("Template should be detected", match.found);
        // Center of (8, 8) with size 4x4 is (10, 10)
        assertEquals(10, match.x);
        assertEquals(10, match.y);
        assertTrue("Confidence should be high", match.confidence >= 0.70f);
    }

    @Test
    public void testOfflineOcrMatcher() {
        OfflineOcrMatcher.TextRegion skipBtn = OfflineOcrMatcher.findTextButton("Skip Ad", 1080, 2400);
        assertNotNull(skipBtn);
        assertTrue(skipBtn.x > 500); // Top-right

        OfflineOcrMatcher.TextRegion confirmBtn = OfflineOcrMatcher.findTextButton("Confirm Payment", 1080, 2400);
        assertNotNull(confirmBtn);
        assertTrue(confirmBtn.y > 1800); // Bottom region

        OfflineOcrMatcher.TextRegion searchBtn = OfflineOcrMatcher.findTextButton("Search", 1080, 2400);
        assertNotNull(searchBtn);
        assertTrue(searchBtn.y < 400); // Top region
    }
}
