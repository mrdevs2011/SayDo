package uz.saydo.app.kernel.vision;

/**
 * On-Device Computer Vision & Visual Grounding Anchor Engine.
 * Provides 64-bit Perceptual Hash (dHash/pHash) and Normalized Cross-Correlation (NCC)
 * template matching for apps with obfuscated/disabled Accessibility trees (Flutter, Canvas, WebViews).
 */
public final class VisualAnchorEngine {

    public static class VisualMatch {
        public final boolean found;
        public final int x;
        public final int y;
        public final float confidence;

        public VisualMatch(boolean found, int x, int y, float confidence) {
            this.found = found;
            this.x = x;
            this.y = y;
            this.confidence = confidence;
        }

        public static VisualMatch notFound() {
            return new VisualMatch(false, 0, 0, 0.0f);
        }
    }

    private VisualAnchorEngine() {
    }

    /**
     * Computes a 64-bit Difference Hash (dHash) from an 8x9 grayscale pixel matrix.
     * Each bit indicates whether adjacent horizontal pixels increase in luminance.
     */
    public static long computeDifferenceHash(int[] grayscale8x9) {
        if (grayscale8x9 == null || grayscale8x9.length < 72) return 0L;
        long hash = 0L;
        int bitIndex = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int left = grayscale8x9[row * 9 + col];
                int right = grayscale8x9[row * 9 + (col + 1)];
                if (left > right) {
                    hash |= (1L << bitIndex);
                }
                bitIndex++;
            }
        }
        return hash;
    }

    /**
     * Calculates Hamming Distance between two 64-bit perceptual hashes (0 to 64).
     */
    public static int hammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    /**
     * Returns perceptual hash similarity in range [0.0, 1.0].
     * Distance <= 5 indicates structural visual equivalence (> 0.92 similarity).
     */
    public static float hashSimilarity(long hash1, long hash2) {
        int dist = hammingDistance(hash1, hash2);
        return 1.0f - (float) dist / 64.0f;
    }

    /**
     * Fast Normalized Cross-Correlation (NCC) template matcher over 2D luminance buffers.
     * Finds highest correlation match of template within screen image.
     */
    public static VisualMatch matchTemplate(int[] screenLum, int screenW, int screenH,
                                            int[] templateLum, int tplW, int tplH,
                                            float minConfidenceThreshold) {
        if (screenLum == null || templateLum == null || tplW <= 0 || tplH <= 0 ||
                screenW < tplW || screenH < tplH) {
            return VisualMatch.notFound();
        }

        // Calculate template mean and variance
        double tplSum = 0;
        int tplCount = tplW * tplH;
        for (int v : templateLum) {
            tplSum += v;
        }
        double tplMean = tplSum / tplCount;

        double tplVar = 0;
        for (int v : templateLum) {
            double diff = v - tplMean;
            tplVar += diff * diff;
        }
        double tplStd = Math.sqrt(tplVar);
        if (tplStd < 0.0001) tplStd = 1.0;

        float bestScore = -1.0f;
        int bestX = 0;
        int bestY = 0;

        int step = (screenW < 64) ? 1 : 4;
        int sampleStep = (tplW < 8 || tplH < 8) ? 1 : 2;

        for (int y = 0; y <= screenH - tplH; y += step) {
            for (int x = 0; x <= screenW - tplW; x += step) {
                double scrSum = 0;
                int sampledCount = 0;
                for (int ty = 0; ty < tplH; ty += sampleStep) {
                    int offset = (y + ty) * screenW + x;
                    for (int tx = 0; tx < tplW; tx += sampleStep) {
                        scrSum += screenLum[offset + tx];
                        sampledCount++;
                    }
                }
                if (sampledCount <= 0) sampledCount = 1;
                double scrMean = scrSum / sampledCount;

                double crossProduct = 0;
                double scrVar = 0;
                double tplSubVar = 0;

                for (int ty = 0; ty < tplH; ty += sampleStep) {
                    int scrOffset = (y + ty) * screenW + x;
                    int tplOffset = ty * tplW;
                    for (int tx = 0; tx < tplW; tx += sampleStep) {
                        double sVal = screenLum[scrOffset + tx] - scrMean;
                        double tVal = templateLum[tplOffset + tx] - tplMean;
                        crossProduct += sVal * tVal;
                        scrVar += sVal * sVal;
                        tplSubVar += tVal * tVal;
                    }
                }

                double denom = Math.sqrt(scrVar * tplSubVar);
                if (denom > 0.0001) {
                    float ncc = (float) (crossProduct / denom);
                    if (ncc > bestScore) {
                        bestScore = ncc;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
        }

        if (bestScore >= minConfidenceThreshold) {
            int centerX = bestX + (tplW / 2);
            int centerY = bestY + (tplH / 2);
            return new VisualMatch(true, centerX, centerY, bestScore);
        }

        return VisualMatch.notFound();
    }
}
