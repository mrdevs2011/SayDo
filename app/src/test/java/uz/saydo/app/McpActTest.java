package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.junit.Test;

import java.util.List;

public class McpActTest {

    private static List<McpAct.Step> ok(String json) throws Exception {
        return McpAct.parse(new JSONArray(json));
    }

    private static String bad(String json) throws Exception {
        try {
            McpAct.parse(new JSONArray(json));
            fail("expected rejection: " + json);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Test
    public void parsesEveryStepKind() throws Exception {
        List<McpAct.Step> s = ok("[{\"click\":3},{\"click\":\"Yuborish\"},{\"tap\":[10,20]},"
                + "{\"long_press\":[1,2,900]},{\"swipe\":[1,2,3,4,300]},{\"type\":\"salom\",\"index\":5,\"append\":true},"
                + "{\"press\":\"back\"},{\"open_app\":\"org.telegram.messenger\"},{\"wait\":{\"text\":\"Chat\"}}]");
        assertEquals(9, s.size());
        assertEquals(McpAct.Kind.CLICK_INDEX, s.get(0).kind);
        assertEquals(3, s.get(0).index);
        assertEquals(McpAct.Kind.CLICK_TEXT, s.get(1).kind);
        assertEquals("Yuborish", s.get(1).text);
        assertArrayEquals(new int[]{10, 20}, s.get(2).n);
        assertArrayEquals(new int[]{1, 2, 900}, s.get(3).n);
        assertArrayEquals(new int[]{1, 2, 3, 4, 300}, s.get(4).n);
        assertEquals(5, s.get(5).index);
        assertTrue(s.get(5).append);
        assertEquals("back", s.get(6).text);
        assertEquals("org.telegram.messenger", s.get(7).text);
        assertEquals("Chat", s.get(8).waitText);
        assertEquals(McpAct.DEFAULT_TIMEOUT_MS, s.get(8).timeoutMs);
    }

    @Test
    public void defaultsForOptionalDurations() throws Exception {
        List<McpAct.Step> s = ok("[{\"long_press\":[1,2]},{\"swipe\":[1,2,3,4]},{\"type\":\"x\"}]");
        assertEquals(800, s.get(0).n[2]);
        assertEquals(350, s.get(1).n[4]);
        assertEquals(-1, s.get(2).index);
        assertFalse(s.get(2).append);
    }

    @Test
    public void rejectsEmptyAndTooManySteps() throws Exception {
        assertEquals("steps is empty", bad("[]"));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 16; i++) sb.append(i > 0 ? "," : "").append("{\"press\":\"back\"}");
        assertTrue(bad(sb.append("]").toString()).contains("max 15"));
        sb = new StringBuilder("[");
        for (int i = 0; i < 15; i++) sb.append(i > 0 ? "," : "").append("{\"press\":\"back\"}");
        assertEquals(15, ok(sb.append("]").toString()).size());
    }

    @Test
    public void exactlyOneActionPerStep() throws Exception {
        assertTrue(bad("[{\"click\":1,\"tap\":[1,2]}]").startsWith("step 1: more than one"));
        assertTrue(bad("[{}]").contains("no action"));
        assertTrue(bad("[{\"click\":1,\"index\":2}]").contains("unknown key 'index'"));
        assertTrue(bad("[{\"press\":\"back\"},\"x\"]").startsWith("step 2:"));
    }

    @Test
    public void rejectsBadArguments() throws Exception {
        assertTrue(bad("[{\"click\":-1}]").contains("range"));
        assertTrue(bad("[{\"click\":\"\"}]").contains("number or text"));
        assertTrue(bad("[{\"click\":true}]").contains("number or text"));
        assertTrue(bad("[{\"tap\":[1]}]").contains("tap"));
        assertTrue(bad("[{\"tap\":[-1,5]}]").contains("range"));
        assertTrue(bad("[{\"long_press\":[1,2,10]}]").contains("300-5000"));
        assertTrue(bad("[{\"swipe\":[1,2,3,4,9]}]").contains("50-5000"));
        assertTrue(bad("[{\"press\":\"volume\"}]").contains("press key"));
        assertTrue(bad("[{\"open_app\":\"a b\"}]").contains("package"));
        assertTrue(bad("[{\"type\":5}]").contains("text"));
        assertTrue(bad("[{\"type\":\"a\",\"append\":\"yes\"}]").contains("append"));
    }

    @Test
    public void waitNeedsAConditionAndClampsTimes() throws Exception {
        assertTrue(bad("[{\"wait\":{}}]").contains("needs"));
        assertTrue(bad("[{\"wait\":{\"foo\":1}}]").contains("unknown key"));
        assertTrue(bad("[{\"wait\":{\"text\":\"\"}}]").contains("text"));
        List<McpAct.Step> s = ok("[{\"wait\":{\"ms\":99999}},{\"wait\":{\"gone\":\"x\",\"app\":\"a.b.c\",\"timeout\":99999}}]");
        assertEquals(McpAct.MAX_SLEEP_MS, s.get(0).sleepMs);
        assertEquals(McpAct.MAX_TIMEOUT_MS, s.get(1).timeoutMs);
        assertEquals("x", s.get(1).waitGone);
        assertEquals("a.b.c", s.get(1).waitApp);
    }

    @Test
    public void resultIsOneCompactLine() {
        McpAct.Result r = new McpAct.Result();
        r.add(1, null);
        r.add(2, null);
        assertFalse(r.failed());
        r.add(3, "no element 'Yuborish'");
        assertTrue(r.failed());
        assertEquals("1 ok 2 ok 3 FAIL no element 'Yuborish'", r.toString());
    }
}
