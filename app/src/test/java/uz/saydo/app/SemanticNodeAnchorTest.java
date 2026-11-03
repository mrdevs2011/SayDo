package uz.saydo.app;

import org.json.JSONObject;
import org.junit.Test;

import uz.saydo.app.kernel.data.NodeAnchor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive Unit Test Suite for Semantic UI-Tree Node Anchors (Pillar 1).
 */
public class SemanticNodeAnchorTest {

    @Test
    public void testAnchorCreationAndGetters() {
        NodeAnchor anchor = new NodeAnchor(
                "com.example:id/submit",
                "android.widget.Button",
                "Submit Order",
                "Submit your order now",
                100, 200, 400, 300,
                true, false, false, 42
        );

        assertEquals("com.example:id/submit", anchor.getViewIdResourceName());
        assertEquals("android.widget.Button", anchor.getClassName());
        assertEquals("Submit Order", anchor.getText());
        assertEquals("Submit your order now", anchor.getContentDescription());
        assertEquals(100, anchor.getLeft());
        assertEquals(200, anchor.getTop());
        assertEquals(400, anchor.getRight());
        assertEquals(300, anchor.getBottom());
        assertEquals(250, anchor.getCenterX());
        assertEquals(250, anchor.getCenterY());
        assertTrue(anchor.isClickable());
        assertFalse(anchor.isScrollable());
        assertFalse(anchor.isEditable());
        assertEquals(42, anchor.getAncestryHash());
    }

    @Test
    public void testNullSafetyInConstructor() {
        NodeAnchor anchor = new NodeAnchor(null, null, null, null, 0, 0, 0, 0, false, false, false, 0);
        assertEquals("", anchor.getViewIdResourceName());
        assertEquals("", anchor.getClassName());
        assertEquals("", anchor.getText());
        assertEquals("", anchor.getContentDescription());
    }

    @Test
    public void testPerfectMatchScore() {
        NodeAnchor a1 = new NodeAnchor("id/btn", "Button", "Click", "Click here", 10, 10, 50, 50, true, false, false, 123);
        NodeAnchor a2 = new NodeAnchor("id/btn", "Button", "Click", "Click here", 10, 10, 50, 50, true, false, false, 123);

        float score = a1.matchScore(a2);
        assertTrue("Perfect score should be 1.0f, was: " + score, score >= 0.99f);
    }

    @Test
    public void testResourceIdOnlyMatch() {
        NodeAnchor a1 = new NodeAnchor("id/btn", "Button", "Click", "Click here", 10, 10, 50, 50, true, false, false, 123);
        NodeAnchor a2 = new NodeAnchor("id/btn", "TextView", "Different", "", 100, 100, 150, 150, false, false, false, 456);

        float score = a1.matchScore(a2);
        assertTrue("Resource ID match alone should give >= 0.40 score", score >= 0.40f);
    }

    @Test
    public void testTextMatchWithDifferentId() {
        NodeAnchor a1 = new NodeAnchor("id/btn1", "Button", "Confirm", "", 0, 0, 10, 10, true, false, false, 1);
        NodeAnchor a2 = new NodeAnchor("id/btn2", "Button", "Confirm", "", 0, 0, 10, 10, true, false, false, 1);

        float score = a1.matchScore(a2);
        assertTrue("Text + Class + Ancestry match should be >= 0.55", score >= 0.55f);
    }

    @Test
    public void testContentDescriptionMatch() {
        NodeAnchor a1 = new NodeAnchor("", "ImageView", "", "Close Dialog", 0, 0, 10, 10, true, false, false, 0);
        NodeAnchor a2 = new NodeAnchor("", "ImageView", "", "Close Dialog", 0, 0, 10, 10, true, false, false, 0);

        float score = a1.matchScore(a2);
        assertTrue("Desc + Class match should score >= 0.45", score >= 0.45f);
    }

    @Test
    public void testZeroMatchScore() {
        NodeAnchor a1 = new NodeAnchor("id/a", "Button", "A", "A", 0, 0, 10, 10, true, false, false, 1);
        NodeAnchor a2 = new NodeAnchor("id/b", "TextView", "B", "B", 0, 0, 10, 10, false, false, false, 2);

        float score = a1.matchScore(a2);
        assertEquals(0.0f, score, 0.001f);
    }

    @Test
    public void testMatchScoreWithNull() {
        NodeAnchor a1 = new NodeAnchor("id/a", "Button", "A", "A", 0, 0, 10, 10, true, false, false, 1);
        assertEquals(0.0f, a1.matchScore(null), 0.001f);
    }

    @Test
    public void testJsonRoundtrip() throws Exception {
        NodeAnchor original = new NodeAnchor(
                "com.test:id/action_send",
                "android.widget.ImageButton",
                "Send",
                "Send message",
                100, 200, 300, 400,
                true, false, true, 9999
        );

        JSONObject json = original.toJson();
        assertNotNull(json);

        NodeAnchor restored = NodeAnchor.fromJson(json);
        assertNotNull(restored);
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
    }

    @Test
    public void testFromJsonNull() {
        assertNull(NodeAnchor.fromJson(null));
    }

    @Test
    public void testEqualsAndHashCode() {
        NodeAnchor a1 = new NodeAnchor("id", "cls", "txt", "desc", 1, 2, 3, 4, true, false, false, 7);
        NodeAnchor a2 = new NodeAnchor("id", "cls", "txt", "desc", 1, 2, 3, 4, true, false, false, 7);
        NodeAnchor a3 = new NodeAnchor("other", "cls", "txt", "desc", 1, 2, 3, 4, true, false, false, 7);

        assertEquals(a1, a2);
        assertEquals(a1.hashCode(), a2.hashCode());
        assertFalse(a1.equals(a3));
        assertFalse(a1.equals(null));
        assertFalse(a1.equals("string"));
    }
}
