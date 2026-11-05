package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

import uz.saydo.app.data.Gesture;

/** The recorded screen package must survive save/load and edits, or playback loses its safety check. */
public class GestureScreenPkgTest {

    @Test
    public void pkgSurvivesJsonRoundTrip() throws Exception {
        JSONObject o = new JSONObject("{type:'tap', x:0.5, y:0.5, st:100, et:150, dur:50, pkg:'com.google.android.youtube'}");
        Gesture g = Gesture.fromJson(o);
        assertEquals("com.google.android.youtube", g.screenPkg);
        assertEquals("com.google.android.youtube", g.toJson().getString("pkg"));
        assertEquals("com.google.android.youtube", Gesture.fromJson(g.toJson()).screenPkg);
    }

    @Test
    public void oldMacrosWithoutPkgStayTimeBased() throws Exception {
        Gesture g = Gesture.fromJson(new JSONObject("{type:'tap', x:0.1, y:0.2, st:0, et:20}"));
        assertEquals("", g.screenPkg);
        assertFalse(g.toJson().has("pkg"));
    }

    @Test
    public void pkgSurvivesCopies() throws Exception {
        Gesture g = Gesture.fromJson(new JSONObject("{type:'tap', x:0.5, y:0.5, st:0, et:20, pkg:'com.x'}"));
        assertEquals("com.x", g.withText("hi").screenPkg);
        assertEquals("com.x", g.withVariableSlot("v").screenPkg);
        assertEquals("com.x", g.withAnchor(null).screenPkg);
    }
}
