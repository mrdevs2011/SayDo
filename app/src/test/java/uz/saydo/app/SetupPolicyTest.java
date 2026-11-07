package uz.saydo.app;

import static org.junit.Assert.*;

import org.junit.Test;

import uz.saydo.app.util.SetupPolicy;

public class SetupPolicyTest {
    @Test public void everythingGrantedIsOk() {
        assertEquals(SetupPolicy.OK, SetupPolicy.firstMissing(true, true, true, true));
    }
    @Test public void orderIsOverlayAccessibilityNotif() {
        assertEquals(SetupPolicy.NEED_OVERLAY, SetupPolicy.firstMissing(false, false, true, false));
        assertEquals(SetupPolicy.NEED_ACCESSIBILITY, SetupPolicy.firstMissing(true, false, true, false));
        assertEquals(SetupPolicy.NEED_NOTIF, SetupPolicy.firstMissing(true, true, true, false));
    }
    @Test public void notificationNotRequiredBeforeAndroid13() {
        assertEquals(SetupPolicy.OK, SetupPolicy.firstMissing(true, true, false, false));
    }
    @Test public void batteryNeverBlocksStart() {
        // Nothing about battery is an input of firstMissing: START depends on the three items only.
        assertEquals(SetupPolicy.OK, SetupPolicy.firstMissing(true, true, true, true));
    }
    @Test public void batteryOfferedOnlyOnce() {
        assertTrue(SetupPolicy.shouldOfferBattery(34, false, false));
        assertFalse(SetupPolicy.shouldOfferBattery(34, false, true));
        assertFalse(SetupPolicy.shouldOfferBattery(34, true, false));
        assertFalse(SetupPolicy.shouldOfferBattery(22, false, false));
    }
}
