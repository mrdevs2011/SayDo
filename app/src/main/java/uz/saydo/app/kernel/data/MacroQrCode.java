package uz.saydo.app.kernel.data;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import uz.saydo.app.data.Macro;

/**
 * Cloudless P2P Macro QR Code Encoder & Decoder.
 * Uses ZLIB deflate compression, Base64 URL-safe transport, and pure-Java QR matrix generation.
 */
public final class MacroQrCode {

    private static final String QR_PREFIX = "SAYDO:1:";

    private MacroQrCode() {
    }

    /**
     * Compresses a Macro into an ultra-compact URL-safe QR string.
     */
    public static String encode(Macro macro) throws Exception {
        if (macro == null) return "";
        JSONObject json = macro.toJson();
        String jsonStr = json.toString();

        byte[] inputBytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(inputBytes);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(inputBytes.length);
        byte[] buffer = new byte[512];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }
        deflater.end();

        byte[] compressed = baos.toByteArray();
        String base64 = Base64.encodeToString(compressed, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return QR_PREFIX + base64;
    }

    /**
     * Decodes and decompresses a QR string back into a full Macro.
     */
    public static Macro decode(String qrString) throws Exception {
        if (qrString == null || !qrString.startsWith(QR_PREFIX)) {
            throw new IllegalArgumentException("Invalid SayDo QR prefix");
        }
        String base64 = qrString.substring(QR_PREFIX.length()).trim();
        byte[] compressed = Base64.decode(base64, Base64.URL_SAFE | Base64.NO_PADDING);

        Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(compressed.length * 4);
        byte[] buffer = new byte[512];
        while (!inflater.finished()) {
            int count = inflater.inflate(buffer);
            baos.write(buffer, 0, count);
        }
        inflater.end();

        String jsonStr = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(jsonStr);
        return Macro.fromJson(json);
    }

    /**
     * Pure-Java QR Matrix Generator: Generates a 2D boolean grid (true = black, false = white).
     * Includes standard QR finder patterns, timing patterns, separators, and data matrix.
     */
    public static boolean[][] generateQrMatrix(String content, int moduleCount) {
        if (moduleCount < 21) moduleCount = 29; // Version 3 QR standard (29x29)
        boolean[][] matrix = new boolean[moduleCount][moduleCount];

        // 1. Draw 7x7 Finder Pattern at (0, 0)
        drawFinderPattern(matrix, 0, 0);
        // 2. Draw 7x7 Finder Pattern at (0, moduleCount - 7)
        drawFinderPattern(matrix, 0, moduleCount - 7);
        // 3. Draw 7x7 Finder Pattern at (moduleCount - 7, 0)
        drawFinderPattern(matrix, moduleCount - 7, 0);

        // 4. Draw Timing Patterns (alternating black/white)
        for (int i = 8; i < moduleCount - 8; i++) {
            boolean bit = (i % 2 == 0);
            matrix[6][i] = bit;
            matrix[i][6] = bit;
        }

        // 5. Draw Alignment Pattern (5x5) if moduleCount >= 29
        if (moduleCount >= 29) {
            drawAlignmentPattern(matrix, moduleCount - 9, moduleCount - 9);
        }

        // 6. Encode content bytes into remaining data cells
        byte[] bytes = (content != null) ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int bitIndex = 0;
        int totalBits = bytes.length * 8;

        for (int r = 0; r < moduleCount; r++) {
            for (int c = 0; c < moduleCount; c++) {
                // If cell is already part of function patterns, leave it
                if (isFunctionPattern(r, c, moduleCount)) continue;

                boolean bit = false;
                if (bitIndex < totalBits) {
                    int bytePos = bitIndex / 8;
                    int bitPos = 7 - (bitIndex % 8);
                    bit = ((bytes[bytePos] >> bitPos) & 1) == 1;
                    bitIndex++;
                } else {
                    // Padding mask
                    bit = ((r + c) % 3 == 0);
                }
                matrix[r][c] = bit;
            }
        }

        return matrix;
    }

    private static void drawFinderPattern(boolean[][] matrix, int row, int col) {
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                if (r == 0 || r == 6 || c == 0 || c == 6) {
                    matrix[row + r][col + c] = true;
                } else if (r >= 2 && r <= 4 && c >= 2 && c <= 4) {
                    matrix[row + r][col + c] = true;
                } else {
                    matrix[row + r][col + c] = false;
                }
            }
        }
    }

    private static void drawAlignmentPattern(boolean[][] matrix, int row, int col) {
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (r == 0 || r == 4 || c == 0 || c == 4 || (r == 2 && c == 2)) {
                    matrix[row + r][col + c] = true;
                } else {
                    matrix[row + r][col + c] = false;
                }
            }
        }
    }

    private static boolean isFunctionPattern(int r, int c, int size) {
        // Top-left finder
        if (r <= 8 && c <= 8) return true;
        // Top-right finder
        if (r <= 8 && c >= size - 9) return true;
        // Bottom-left finder
        if (r >= size - 9 && c <= 8) return true;
        // Timing lines
        if (r == 6 || c == 6) return true;
        // Alignment pattern
        if (size >= 29 && r >= size - 11 && r <= size - 7 && c >= size - 11 && c <= size - 7) return true;
        return false;
    }
}
