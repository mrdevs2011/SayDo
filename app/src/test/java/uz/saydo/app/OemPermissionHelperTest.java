package uz.saydo.app;

import android.provider.Settings;

import org.junit.Test;

import java.util.List;

import uz.saydo.app.util.OemPermissionHelper;

import static org.junit.Assert.*;

/**
 * Unit Tests for OEM Power & Autostart Diagnostics Helper.
 */
public class OemPermissionHelperTest {

    @Test
    public void testIsManufacturerNullSafe() {
        assertFalse(OemPermissionHelper.isManufacturer(null));
    }

    @Test
    public void testCanResolveIntentNullSafe() {
        assertFalse(OemPermissionHelper.canResolveIntent(null, null));
    }

    /*
     * The Intent list itself cannot be inspected in JVM tests: with returnDefaultValues the
     * android.jar Intent stubs return null from every builder call. The ranking is therefore
     * tested on the pure Target list that getVendorAutostartIntents() turns into Intents.
     */

    private static int indexOfAction(List<OemPermissionHelper.Target> t, String action) {
        for (int i = 0; i < t.size(); i++) if (action.equals(t.get(i).action)) return i;
        return -1;
    }

    @Test
    public void testFallbackHierarchyEndsWithAppDetails() {
        for (String mfr : new String[]{null, "Google", "Xiaomi", "samsung", "HUAWEI", "OPPO", "vivo", "OnePlus"}) {
            List<OemPermissionHelper.Target> t = OemPermissionHelper.autostartTargets(mfr, 34);
            assertFalse(t.isEmpty());
            OemPermissionHelper.Target last = t.get(t.size() - 1);
            assertEquals("App Info must be the last resort for " + mfr,
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, last.action);
            assertTrue(last.packageUri);
            assertEquals(t.size() - 3, indexOfAction(t, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS));
        }
    }

    @Test
    public void testVendorScreensComeFirst() {
        List<OemPermissionHelper.Target> xiaomi = OemPermissionHelper.autostartTargets("Xiaomi", 34);
        assertEquals("com.miui.securitycenter", xiaomi.get(0).pkg);
        assertEquals(6, xiaomi.size());

        List<OemPermissionHelper.Target> redmi = OemPermissionHelper.autostartTargets("Redmi", 34);
        assertEquals("com.miui.securitycenter", redmi.get(0).pkg);

        List<OemPermissionHelper.Target> samsung = OemPermissionHelper.autostartTargets("samsung", 34);
        assertEquals("com.samsung.android.lool", samsung.get(0).pkg);

        List<OemPermissionHelper.Target> honor = OemPermissionHelper.autostartTargets("HONOR", 34);
        assertEquals("com.huawei.systemmanager", honor.get(0).pkg);
    }

    @Test
    public void testUnknownManufacturerGetsOnlyStandardScreens() {
        List<OemPermissionHelper.Target> t = OemPermissionHelper.autostartTargets("Google", 34);
        assertEquals(3, t.size());
        for (OemPermissionHelper.Target x : t) assertNull(x.pkg);
    }

    @Test
    public void testOldAndroidSkipsDozeScreens() {
        List<OemPermissionHelper.Target> t = OemPermissionHelper.autostartTargets(null, 22);
        assertEquals(1, t.size());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, t.get(0).action);
    }
}
