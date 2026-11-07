package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.content.Context;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.kernel.data.NodeAnchor;
import uz.saydo.app.mock.TestContext;
import uz.saydo.app.safety.SecretBox;
import uz.saydo.app.safety.SensitiveSteps;

public class SensitiveStepsTest {

    /** Same format as KeystoreBox (IV + AES-GCM), with an ordinary in-memory key. */
    static final class SoftBox implements SecretBox {
        private final SecretKey key;
        private final SecureRandom rnd = new SecureRandom();

        SoftBox() throws Exception {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            key = kg.generateKey();
        }

        @Override public byte[] encrypt(byte[] plain) throws GeneralSecurityException {
            byte[] iv = new byte[12];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        }

        @Override public byte[] decrypt(byte[] sealed) throws GeneralSecurityException {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 0, 12)));
            return c.doFinal(sealed, 12, sealed.length - 12);
        }
    }

    private static Gesture pinTap(String digit, long st) {
        NodeAnchor a = new NodeAnchor("", "android.widget.Button", digit, "", 0, 0, 10, 10, true, false, false, 1);
        return Gesture.createSemantic(Gesture.TYPE_TAP, 0.5, 0.5, st, st + 20, a, "", "com.android.systemui", "", 0, 0)
                .withSensitive(true);
    }

    private static Gesture plainTap(String label, long st) {
        NodeAnchor a = new NodeAnchor("", "android.widget.Button", label, "", 0, 0, 10, 10, true, false, false, 1);
        return Gesture.createSemantic(Gesture.TYPE_TAP, 0.5, 0.5, st, st + 20, a, "", "com.app", "", 0, 0);
    }

    private static Macro pinMacro() {
        List<Gesture> g = new ArrayList<>();
        g.add(plainTap("Open", 0));
        g.add(pinTap("4", 1000));
        g.add(pinTap("7", 1500));
        g.add(Gesture.createWaitUser("biometric", 2000));
        g.add(plainTap("Done", 3000));
        return new Macro("pin-1", "bank pin", g, false, 1000L, 0);
    }

    @Test
    public void sealHidesTheDigitsAndOpenRestoresThem() throws Exception {
        SoftBox box = new SoftBox();
        JSONObject plain = pinMacro().toJson();
        assertTrue(SensitiveSteps.hasSensitive(plain));
        JSONObject sealed = SensitiveSteps.seal(plain, box);
        String text = sealed.toString();
        assertFalse(SensitiveSteps.hasSensitive(sealed));
        assertTrue(SensitiveSteps.hasSealed(sealed));
        // the digits' labels and the package of the pin pad are not readable on disk
        assertFalse(text.contains("\"txt\"") && text.contains("android.widget.Button\",\"text\":\"4\""));
        assertFalse(text.contains("com.android.systemui"));
        JSONArray g = sealed.getJSONArray("gestures");
        assertEquals("enc", g.getJSONObject(1).getString("type"));
        assertEquals(1000, g.getJSONObject(1).getLong("st"));
        // plain steps stay as they were
        assertEquals("tap", g.getJSONObject(0).getString("type"));
        assertEquals("wait_user", g.getJSONObject(3).getString("type"));

        JSONObject opened = SensitiveSteps.open(sealed, box);
        assertEquals(plain.toString(), opened.toString());
        Macro back = Macro.fromJson(opened);
        assertTrue(back.hasSensitiveSteps());
        assertEquals(5, back.gestures.size());
        assertTrue(back.gestures.get(1).sensitive);
        assertEquals("4", back.gestures.get(1).anchor.getText());
    }

    @Test(expected = GeneralSecurityException.class)
    public void wrongKeyCannotOpen() throws Exception {
        JSONObject sealed = SensitiveSteps.seal(pinMacro().toJson(), new SoftBox());
        SensitiveSteps.open(sealed, new SoftBox());
    }

    @Test(expected = GeneralSecurityException.class)
    public void changedCiphertextCannotOpen() throws Exception {
        SoftBox box = new SoftBox();
        JSONObject sealed = SensitiveSteps.seal(pinMacro().toJson(), box);
        JSONObject step = sealed.getJSONArray("gestures").getJSONObject(1);
        byte[] raw = java.util.Base64.getDecoder().decode(step.getString("enc"));
        raw[raw.length - 1] ^= 1;
        step.put("enc", java.util.Base64.getEncoder().encodeToString(raw));
        SensitiveSteps.open(sealed, box);
    }

    @Test
    public void macroWithPinStepIsAlwaysSensitiveAndHidden() {
        Macro m = pinMacro();
        assertTrue(m.sensitive);
        assertTrue(m.hasSensitiveSteps());
        assertTrue(McpGuard.hiddenFromAi(m));
        assertTrue(McpGuard.isSensitive(m));
        // the owner cannot "clear" it while the PIN steps are in it
        assertTrue(m.withSensitive(false).sensitive);
        assertTrue(m.withInfo("d", Collections.<String>emptyList(), false, true).sensitive);
    }

    @Test
    public void ordinaryMacroIsUnaffected() {
        Macro m = new Macro("x", "open", Arrays.asList(plainTap("A", 0)), false, 1000L, 0);
        assertFalse(m.sensitive);
        assertFalse(McpGuard.hiddenFromAi(m));
        assertFalse(m.toJson().toString().contains("\"sens\""));
        assertFalse(McpGuard.hiddenFromAi(null));
    }

    @Test
    public void descriptionNeverContainsPinLabels() {
        Macro m = pinMacro();
        String d = MacroDescriber.describe(m, pkg -> null);
        assertFalse(d, d.contains("4") && d.contains("7") && d.contains(">"));
        assertEquals("", MacroDescriber.anchorLabel(m.gestures.get(1)));
        for (String t : MacroDescriber.anchorTexts(m)) assertFalse(t.equals("4") || t.equals("7"));
    }

    @Test
    public void waitUserStepRoundTrips() {
        Gesture w = Gesture.createWaitUser("biometric", 500);
        Gesture back = Gesture.fromJson(w.toJson());
        assertEquals(Gesture.TYPE_WAIT_USER, back.type);
        assertEquals("biometric", back.action);
        assertFalse(back.sensitive);
    }

    // ------------------------------------------------------------ repository

    private File dir;
    private Context ctx;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("saydo_sens").toFile();
        ctx = new TestContext(dir);
        reset();
    }

    @After
    public void tearDown() {
        reset();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
    }

    private static void reset() {
        try {
            Field f = MacroRepository.class.getDeclaredField("sInstance");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable ignored) {
        }
    }

    private MacroRepository repo(SecretBox box) throws Exception {
        Constructor<MacroRepository> c = MacroRepository.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        MacroRepository r = c.newInstance(ctx);
        r.reloadWithBoxForTest(box);
        return r;
    }

    @Test
    public void fileOnDiskHoldsNoPinAndItComesBack() throws Exception {
        SoftBox box = new SoftBox();
        MacroRepository r = repo(box);
        r.save(pinMacro());
        r.save(new Macro("plain", "open maps", Arrays.asList(plainTap("Maps", 0)), false, 2000L, 0));

        String disk = new String(Files.readAllBytes(new File(dir, "macros_v2.json").toPath()), StandardCharsets.UTF_8);
        assertTrue(disk.contains("\"enc\""));
        assertFalse(disk.contains("com.android.systemui"));
        assertTrue(disk.contains("open maps"));

        MacroRepository again = repo(box);
        Macro m = again.getById("pin-1");
        assertNotNull(m);
        assertEquals(5, m.gestures.size());
        assertTrue(m.gestures.get(2).sensitive);
        assertEquals("7", m.gestures.get(2).anchor.getText());
    }

    @Test
    public void macroIsDroppedWhenTheKeyIsLost() throws Exception {
        MacroRepository r = repo(new SoftBox());
        r.save(pinMacro());
        MacroRepository other = repo(new SoftBox()); // a different key
        assertNull(other.getById("pin-1"));
    }

    @Test
    public void withoutKeystoreNothingSensitiveIsWritten() throws Exception {
        MacroRepository r = repo(null);
        r.save(pinMacro());
        r.save(new Macro("plain", "open maps", Arrays.asList(plainTap("Maps", 0)), false, 2000L, 0));
        String disk = new String(Files.readAllBytes(new File(dir, "macros_v2.json").toPath()), StandardCharsets.UTF_8);
        assertFalse(disk.contains("bank pin"));
        assertFalse(disk.contains("\"enc\""));
        assertTrue(disk.contains("open maps"));
    }

    @Test
    public void exportLeavesPinMacrosOutAndImportKeepsThem() throws Exception {
        MacroRepository r = repo(new SoftBox());
        r.save(pinMacro());
        r.save(new Macro("plain", "open maps", Arrays.asList(plainTap("Maps", 0)), false, 2000L, 0));
        String export = r.toJsonString();
        assertFalse(export.contains("bank pin"));
        assertFalse(export.contains("\"sens\""));
        assertTrue(export.contains("open maps"));

        assertTrue(r.importFromJsonString(export));
        assertNotNull("import must not delete the PIN macro", r.getById("pin-1"));
        // an import file that carries sensitive or sealed steps is not accepted for those macros
        String evil = "[" + pinMacro().toJson() + "]";
        assertTrue(r.importFromJsonString(evil));
        assertEquals(1, r.count());
    }
}
