package uz.saydo.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import uz.saydo.app.kernel.data.NodeAnchor;

/** Playback only waits for and scans for anchors that can actually be found. */
public class NodeAnchorUsableTest {

    private static NodeAnchor a(String id, String cls, String text, String desc, boolean clickable) {
        return new NodeAnchor(id, cls, text, desc, 0, 0, 10, 10, clickable, false, false, 123);
    }

    @Test
    public void anchorWithNoIdTextOrDescriptionIsNeverUsable() {
        // class 0.15 + clickable 0.05 + ancestry 0.10 = 0.30
        assertFalse(a("", "android.view.ViewGroup", "", "", true).canBeMatched());
    }

    @Test
    public void textAnchorIsUsable() {
        // text 0.30 + class 0.15 + clickable 0.05 + ancestry 0.10 = 0.60 > 0.5 -> usable
        assertTrue(a("", "android.widget.TextView", "Display", "", true).canBeMatched());
    }

    @Test
    public void idAnchorIsUsable() {
        assertTrue(a("com.x:id/btn", "android.widget.Button", "", "", true).canBeMatched());
    }

    @Test
    public void contentDescriptionAnchorIsUsable() {
        assertTrue(a("", "android.widget.ImageView", "", "Settings", true).canBeMatched());
    }
}
