package uz.saydo.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.kernel.data.MacroQrCode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive Unit Test Suite for Cloudless P2P QR Code Sharing & Compression (Pillar 8).
 */
public class MacroCompressorTest {

    @Test
    public void testEncodeAndDecodeRoundtrip() throws Exception {
        List<Gesture> gestures = new ArrayList<>();
        gestures.add(Gesture.createTap(0.25, 0.75, 100, 120));
        gestures.add(Gesture.createHold(0.50, 0.50, 300, 700));

        Macro original = new Macro(
                "test-uuid-1234",
                "open camera",
                gestures,
                false,
                1600000000000L,
                700
        );

        String qrString = MacroQrCode.encode(original);
        assertNotNull(qrString);
        assertTrue(qrString.startsWith("SAYDO:1:"));

        Macro decoded = MacroQrCode.decode(qrString);
        assertNotNull(decoded);
        assertEquals(original.trigger, decoded.trigger);
        assertEquals(original.gestures.size(), decoded.gestures.size());
        assertEquals(original.duration, decoded.duration);
        assertEquals(original.gestures.get(0).x, decoded.gestures.get(0).x, 0.001);
        assertEquals(original.gestures.get(0).y, decoded.gestures.get(0).y, 0.001);
    }

    @Test
    public void testQrMatrixGeneration() {
        boolean[][] matrix = MacroQrCode.generateQrMatrix("SAYDO:1:TESTPAYLOAD", 29);
        assertNotNull(matrix);
        assertEquals(29, matrix.length);
        assertEquals(29, matrix[0].length);

        // Finder patterns must be true at outer borders
        assertTrue(matrix[0][0]);
        assertTrue(matrix[0][6]);
        assertTrue(matrix[6][0]);
        assertTrue(matrix[6][6]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidQrPrefixThrows() throws Exception {
        MacroQrCode.decode("INVALID_PREFIX_DATA");
    }
}
