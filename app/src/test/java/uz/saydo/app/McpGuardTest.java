package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.kernel.data.NodeAnchor;

public class McpGuardTest {

    private static Gesture tap(String pkg, String text, boolean editable) {
        NodeAnchor a = text == null ? null
                : new NodeAnchor("", editable ? "android.widget.EditText" : "android.widget.TextView", text, "",
                0, 0, 10, 10, true, false, editable, 1);
        return new Gesture(Gesture.TYPE_TAP, "", 0.5, 0.5, 0, 20, 20, Collections.<uz.saydo.app.data.TouchPoint>emptyList(),
                a, "", "", pkg);
    }

    private static Macro macro(String trigger, Gesture... g) {
        return new Macro("id-" + trigger, trigger, Arrays.asList(g), false, 1000L, 0);
    }

    private static final MacroDescriber.Labels LABELS = new MacroDescriber.Labels() {
        @Override public String label(String pkg) {
            if (pkg.equals("com.google.android.youtube")) return "YouTube";
            if (pkg.equals("com.sec.android.app.launcher")) return "One UI Home";
            return null;
        }
    };

    // ---- money detection

    @Test
    public void moneyPackages() {
        assertTrue(McpGuard.isMoneyPackage("uz.dida.payme"));
        assertTrue(McpGuard.isMoneyPackage("air.com.ssdsoftwaresolutions.clickuz"));
        assertTrue(McpGuard.isMoneyPackage("uz.click.evo") );
        assertTrue(McpGuard.isMoneyPackage("uz.anorbank.mobile"));
        assertTrue(McpGuard.isMoneyPackage("com.google.android.apps.walletnfcrel"));
        assertTrue(McpGuard.isMoneyPackage("uz.uzum.bank"));
        assertFalse(McpGuard.isMoneyPackage("com.google.android.youtube"));
        assertFalse(McpGuard.isMoneyPackage("com.sec.android.app.launcher"));
        assertFalse(McpGuard.isMoneyPackage(""));
        assertFalse(McpGuard.isMoneyPackage(null));
    }

    @Test
    public void macroInMoneyAppIsSensitiveUntilOwnerReviews() {
        Macro m = macro("pay", tap("com.sec.android.app.launcher", "Payme", false), tap("uz.dida.payme", "Pay", false));
        assertTrue(McpGuard.touchesMoney(m));
        assertTrue(McpGuard.isSensitive(m));
        // owner looked and unmarked it: stays unmarked
        Macro unmarked = m.withInfo("", Collections.<String>emptyList(), false, true);
        assertFalse(McpGuard.isSensitive(unmarked));
        // owner marked a harmless macro
        Macro yt = macro("yt", tap("com.google.android.youtube", "Search", false));
        assertFalse(McpGuard.isSensitive(yt));
        assertTrue(McpGuard.isSensitive(yt.withSensitive(true)));
    }

    // ---- run decision

    @Test
    public void runDecisionTable() {
        assertEquals(McpGuard.RunDecision.ALLOW, McpGuard.decideRun(false, false, true));
        assertEquals(McpGuard.RunDecision.ALLOW, McpGuard.decideRun(false, true, true));
        assertEquals(McpGuard.RunDecision.NEED_CONFIRM, McpGuard.decideRun(true, false, true));
        assertEquals(McpGuard.RunDecision.NEED_CONFIRM, McpGuard.decideRun(true, false, false));
        assertEquals(McpGuard.RunDecision.NEED_PHONE_APPROVAL, McpGuard.decideRun(true, true, true));
        assertEquals(McpGuard.RunDecision.ALLOW, McpGuard.decideRun(true, true, false));
    }

    @Test
    public void screenActionRefusals() {
        assertNull(McpGuard.screenActionRefusal("com.google.android.youtube", false));
        assertNull(McpGuard.screenActionRefusal(null, false));
        assertNotNull(McpGuard.screenActionRefusal("uz.dida.payme", false));
        assertNull(McpGuard.screenActionRefusal("uz.dida.payme", true));
        assertNotNull(McpGuard.openAppRefusal("uz.dida.payme", false));
        assertNull(McpGuard.openAppRefusal("uz.dida.payme", true));
        assertNull(McpGuard.openAppRefusal("com.google.android.youtube", false));
    }

    @Test
    public void approvalTextHasNoStepsOrAmounts() {
        String t = McpGuard.approvalText("send 500000 to Ali");
        assertTrue(t.contains("send 500000 to Ali")); // trigger is the owner's own name for it
        assertTrue(t.length() < 120);
        assertTrue(McpGuard.approvalText(null).length() < 120);
    }

    // ---- description

    @Test
    public void descriptionFromSteps() {
        Macro m = macro("yt fav",
                tap("com.sec.android.app.launcher", "YouTube", false),
                tap("com.google.android.youtube", "Search", false),
                tap("com.google.android.youtube", "Library", false),
                tap("com.google.android.youtube", "Library", false),
                tap("com.google.android.youtube", "Favourites", false),
                tap("com.google.android.youtube", null, false));
        assertEquals("YouTube: Search > Library > Favourites (6 steps)", MacroDescriber.describe(m, LABELS));
        assertEquals(Collections.singletonList("YouTube"), MacroDescriber.appLabels(m, LABELS));
    }

    @Test
    public void descriptionNeverUsesTextFieldsOrNumbers() {
        Macro m = macro("x",
                tap("com.google.android.youtube", "secret search words", true),
                tap("com.google.android.youtube", "500 000 so'm", false),
                tap("com.google.android.youtube", "Pay 12345678", false),
                tap("com.google.android.youtube", "OK", false));
        String d = MacroDescriber.describe(m, LABELS);
        assertFalse(d, d.contains("secret"));
        assertFalse(d, d.contains("12345678"));
        assertTrue(d, d.contains("OK"));
        for (String line : MacroDescriber.stepLines(m, LABELS, 20)) assertFalse(line, line.contains("secret"));
        assertFalse(MacroDescriber.anchorTexts(m).contains("secret search words"));
    }

    @Test
    public void descriptionFallsBackToPackageAndIsCapped() {
        Macro m = macro("x", tap("org.unknown.app", "Hello", false));
        assertEquals("org.unknown.app: Hello (1 step)", MacroDescriber.describe(m, LABELS));
        List<Gesture> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(tap("com.google.android.youtube", "A very long button caption number " + i, false));
        }
        Macro big = new Macro("b", "big", many, false, 1000L, 0);
        String d = MacroDescriber.describe(big, LABELS);
        assertTrue(d, d.length() <= MacroDescriber.MAX_BODY + 20);
        assertTrue(d, d.endsWith("(40 steps)"));
        assertEquals("1 step", MacroDescriber.describe(macro("e", tap("", null, false)), LABELS));
    }

    // ---- JSON

    @Test
    public void oldMacroJsonLoadsUnchanged() throws Exception {
        JSONObject old = new JSONObject("{\"id\":\"a\",\"trigger\":\"Open Camera\",\"locked\":false,"
                + "\"created_at\":5,\"dur\":10,\"sensitive\":true,\"aliases\":[\"kamera\"],\"gestures\":[]}");
        Macro m = Macro.fromJson(old);
        assertEquals("", m.description);
        assertFalse(m.sensitiveReviewed);
        assertTrue(m.sensitive);
        assertEquals(Collections.singletonList("kamera"), m.aliases);
        JSONObject back = m.toJson();
        assertFalse(back.has("desc"));
        assertFalse(back.has("sens_rev"));
    }

    @Test
    public void newFieldsRoundTripAndSurviveEdits() throws Exception {
        Macro m = new Macro("a", "t", Collections.<Gesture>emptyList(), false, 5L, 0)
                .withInfo("Does a thing", Arrays.asList("alias one"), true, true);
        Macro back = Macro.fromJson(new JSONObject(m.toJson().toString()));
        assertEquals("Does a thing", back.description);
        assertTrue(back.sensitiveReviewed);
        assertTrue(back.sensitive);
        assertEquals(m, back);
        // copies keep the metadata
        assertEquals("Does a thing", back.incrementUsage().description);
        assertEquals("Does a thing", back.withGestures(Collections.<Gesture>emptyList()).description);
        assertEquals("Does a thing", back.withAliases(Collections.<String>emptyList()).description);
        assertEquals("Does a thing", back.withSensitive(false).description);
        assertTrue(back.withSensitive(false).sensitiveReviewed);
    }
}
