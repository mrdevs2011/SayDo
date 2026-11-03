package uz.saydo.app;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.junit.Test;

import java.util.List;

import uz.saydo.app.mock.TestContext;
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

    @Test
    public void testVendorAutostartIntentsFallbackHierarchy() {
        Context mockContext = new TestContext(new java.io.File("/tmp"));

        List<Intent> intents = OemPermissionHelper.getVendorAutostartIntents(mockContext);
        assertNotNull(intents);
        assertFalse("Vendor intents list should not be empty", intents.isEmpty());

        // Verify that standard application details setting is always included in the hierarchy
        boolean hasAppDetails = false;
        for (Intent intent : intents) {
            if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(intent.getAction())) {
                hasAppDetails = true;
                break;
            }
        }
        assertTrue("Fallback hierarchy must include ACTION_APPLICATION_DETAILS_SETTINGS", hasAppDetails);
    }
}
