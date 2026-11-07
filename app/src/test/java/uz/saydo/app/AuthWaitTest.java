package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import uz.saydo.app.safety.AuthDetector;
import uz.saydo.app.safety.AuthRecordLogic;
import uz.saydo.app.safety.AuthRecordLogic.Action;
import uz.saydo.app.safety.WaitUserLogic;
import uz.saydo.app.safety.WaitUserLogic.State;

public class AuthWaitTest {

    // ------------------------------------------------------------ detector

    @Test
    public void detectsBiometricAndCredentialScreens() {
        assertTrue(AuthDetector.isAuthWindow("com.android.systemui", "com.android.systemui.biometrics.BiometricPromptDialog", ""));
        assertTrue(AuthDetector.isAuthWindow("com.samsung.android.biometrics.app.setting", "android.widget.FrameLayout", ""));
        assertTrue(AuthDetector.isAuthWindow("com.android.settings", "com.android.settings.password.ConfirmLockPassword", ""));
        assertTrue(AuthDetector.isAuthWindow("com.android.systemui", "android.widget.FrameLayout", "Touch the fingerprint sensor"));
        assertTrue(AuthDetector.isAuthWindow("com.android.systemui", "android.widget.FrameLayout", "Barmoq izini tasdiqlang"));
        assertTrue(AuthDetector.isAuthWindow("com.android.systemui", "x.KeyguardBouncer", ""));
    }

    @Test
    public void ordinaryScreensAreNotAuth() {
        assertFalse(AuthDetector.isAuthWindow("com.google.android.youtube", "com.google.android.youtube.HomeActivity", ""));
        // An app may call its own screen "Unlock"; only system UI titles count.
        assertFalse(AuthDetector.isAuthWindow("com.some.app", "x.Main", "Unlock premium"));
        // The pull-down shade is not a lock screen.
        assertFalse(AuthDetector.isAuthWindow("com.android.systemui", "com.android.systemui.shade.NotificationShadeWindowView", ""));
        assertFalse(AuthDetector.isAuthWindow("", "", ""));
        assertFalse(AuthDetector.isAuthWindow(null, null, null));
        assertFalse(AuthDetector.isAuthWindow("com.android.settings", "com.android.settings.Settings", ""));
    }

    @Test
    public void neutralWindowsDuringAuth() {
        assertTrue(AuthDetector.isNeutralDuringAuth("com.android.systemui", "android.widget.FrameLayout"));
        assertTrue(AuthDetector.isNeutralDuringAuth("com.google.android.inputmethod.latin", "x"));
        assertTrue(AuthDetector.isNeutralDuringAuth("com.app", "android.widget.Toast$TN"));
        assertFalse(AuthDetector.isNeutralDuringAuth("com.google.android.youtube", "x.Home"));
    }

    // ------------------------------------------------------------ recording

    private static final String SYS = "com.android.systemui";
    private static final String PROMPT = "com.android.systemui.biometrics.BiometricPrompt";

    @Test
    public void passedWithoutTouchEmitsWaitUser() {
        AuthRecordLogic l = new AuthRecordLogic();
        assertEquals(Action.PASS, l.onWindow("com.bank.app", "x.Login", "", 100));
        assertEquals(Action.CONSUME, l.onWindow(SYS, PROMPT, "", 200));
        assertTrue(l.inAuth());
        assertEquals(200, l.openedAt());
        // a plain system window inside the prompt does not end it
        assertEquals(Action.CONSUME, l.onWindow(SYS, "android.widget.FrameLayout", "", 300));
        assertTrue(l.inAuth());
        assertEquals(Action.EMIT_WAIT_USER, l.onWindow("com.bank.app", "x.Home", "", 3000));
        assertFalse(l.inAuth());
        // only once
        assertEquals(Action.PASS, l.onWindow("com.bank.app", "x.Home", "", 3100));
    }

    @Test
    public void pinTapsMeanNoWaitStep() {
        AuthRecordLogic l = new AuthRecordLogic();
        l.onWindow(SYS, PROMPT, "", 200);
        l.onTouchInAuth();
        assertEquals(Action.CONSUME, l.onWindow("com.bank.app", "x.Home", "", 3000));
        assertFalse(l.inAuth());
    }

    @Test
    public void touchOutsideAuthIsIgnoredAndStateResets() {
        AuthRecordLogic l = new AuthRecordLogic();
        l.onTouchInAuth(); // no prompt open: nothing
        l.onWindow(SYS, PROMPT, "", 10);
        assertEquals(Action.EMIT_WAIT_USER, l.onWindow("a.b", "c", "", 20));
        l.onWindow(SYS, PROMPT, "", 30);
        l.reset();
        assertFalse(l.inAuth());
        assertEquals(Action.PASS, l.onWindow("a.b", "c", "", 40));
    }

    @Test
    public void secondPromptAfterFirstIsRecordedAgain() {
        AuthRecordLogic l = new AuthRecordLogic();
        l.onWindow(SYS, PROMPT, "", 10);
        assertEquals(Action.EMIT_WAIT_USER, l.onWindow("a.b", "c", "", 20));
        l.onWindow(SYS, PROMPT, "", 30);
        l.onTouchInAuth();
        assertEquals(Action.CONSUME, l.onWindow("a.b", "c", "", 40));
    }

    // ------------------------------------------------------------ replay

    @Test
    public void continuesWhenPromptGoesAway() {
        WaitUserLogic w = new WaitUserLogic(1000, false);
        assertEquals(State.WAITING_PROMPT, w.tick(1500));
        assertFalse(w.shouldNotify());
        w.onWindow(true, 2000);
        assertEquals(State.WAITING_USER, w.tick(2100));
        assertTrue(w.shouldNotify());
        assertFalse("tell the owner only once", w.shouldNotify());
        assertEquals(State.WAITING_USER, w.tick(30_000));
        w.onWindow(false, 31_000);
        assertEquals(State.DONE, w.tick(31_100));
    }

    @Test
    public void promptAlreadyVisibleAtStart() {
        WaitUserLogic w = new WaitUserLogic(1000, true);
        assertTrue(w.shouldNotify());
        assertEquals(State.WAITING_USER, w.tick(1100));
        w.onWindow(false, 5000);
        assertEquals(State.DONE, w.tick(5000));
    }

    @Test
    public void timesOutAfterSixtySeconds() {
        WaitUserLogic w = new WaitUserLogic(0, true);
        assertEquals(State.WAITING_USER, w.tick(59_999));
        assertEquals(State.TIMEOUT, w.tick(60_000));
    }

    @Test
    public void timeoutCountsFromWhenThePromptShowed() {
        WaitUserLogic w = new WaitUserLogic(0, false);
        w.onWindow(true, 4000);
        assertEquals(State.WAITING_USER, w.tick(63_999));
        assertEquals(State.TIMEOUT, w.tick(64_000));
    }

    @Test
    public void noPromptAtAllContinuesAfterGrace() {
        WaitUserLogic w = new WaitUserLogic(0, false);
        assertEquals(State.WAITING_PROMPT, w.tick(WaitUserLogic.GRACE_MS - 1));
        assertEquals(State.DONE, w.tick(WaitUserLogic.GRACE_MS));
        assertFalse(w.promptWasShown());
    }

    @Test
    public void windowChangeBeforePromptDoesNotEndTheWait() {
        WaitUserLogic w = new WaitUserLogic(0, false);
        w.onWindow(false, 500); // the app redraws first
        assertEquals(State.WAITING_PROMPT, w.tick(600));
        w.onWindow(true, 1000);
        assertEquals(State.WAITING_USER, w.tick(1100));
    }
}
