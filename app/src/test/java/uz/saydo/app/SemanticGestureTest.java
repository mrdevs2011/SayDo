package uz.saydo.app;

import org.json.JSONObject;
import org.junit.Test;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.kernel.data.NodeAnchor;

import static org.junit.Assert.*;

public class SemanticGestureTest {
    private static NodeAnchor row(String child) {
        return new NodeAnchor("", "android.widget.LinearLayout", "", "", 0, 100, 1000, 200,
                true, false, false, 7, child);
    }

    @Test public void childTextMakesLabellessContainerMatchable() {
        NodeAnchor withChild = row("Wi-Fi");
        NodeAnchor without = row("");
        assertTrue(withChild.canBeMatched());
        assertFalse(without.canBeMatched());
        assertTrue(withChild.matchScore(row("wi-fi")) > 0.5f);
        assertFalse(withChild.matchScore(row("Bluetooth")) > 0.5f);
    }

    @Test public void childTextSurvivesJson() throws Exception {
        NodeAnchor a = NodeAnchor.fromJson(row("Wi-Fi").toJson());
        assertEquals("Wi-Fi", a.getChildText());
        // old anchors have no ctx and read as empty
        JSONObject old = new JSONObject("{\"id\":\"x:id/y\",\"l\":1,\"t\":2,\"r\":3,\"b\":4}");
        assertEquals("", NodeAnchor.fromJson(old).getChildText());
    }

    @Test public void proximityFavoursNearbyNode() {
        NodeAnchor a = row("x");
        assertEquals(1.0f, a.proximity(0, 100, 1000, 200), 1e-6);
        assertTrue(a.proximity(0, 100, 1000, 200) > a.proximity(0, 1500, 1000, 1600));
    }

    @Test public void semanticFieldsRoundTrip() {
        Gesture g = Gesture.createSemantic(Gesture.TYPE_SCROLL, 0.5, 0.5, 100, 400, row("x"), "", "com.a", "Cls", 0, 600);
        Gesture back = Gesture.fromJson(g.toJson());
        assertTrue(back.semantic);
        assertEquals(600, back.scrollDy);
        assertEquals("Cls", back.winCls);
        assertEquals("com.a", back.screenPkg);
        assertEquals("scroll", back.type);
    }

    @Test public void textStepKeepsText() {
        Gesture g = Gesture.createSemantic(Gesture.TYPE_TEXT, 0.5, 0.5, 100, 150, row("x"), "wifi", "com.a", "", 0, 0);
        assertEquals("wifi", Gesture.fromJson(g.toJson()).textToInject);
    }

    @Test public void oldMacroJsonStaysNonSemantic() throws Exception {
        Gesture g = Gesture.fromJson(new JSONObject("{\"type\":\"tap\",\"x\":0.1,\"y\":0.2,\"st\":5,\"et\":25,\"dur\":20,\"pkg\":\"p\"}"));
        assertFalse(g.semantic);
        assertEquals(0, g.scrollDy);
        assertEquals("", g.winCls);
    }
}
