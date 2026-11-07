package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SmartSearchTest {

    private static MacroSearch.Doc doc(String id, String trigger, List<String> aliases, String desc, String... apps) {
        return new MacroSearch.Doc(id, trigger, aliases, desc, Arrays.asList(apps),
                Collections.<String>emptyList(), "General", 0);
    }

    private static List<MacroSearch.Doc> catalog() {
        List<MacroSearch.Doc> l = new ArrayList<>();
        l.add(doc("fav", "favorite music", Collections.<String>emptyList(), "YouTube Music: Library > Favourites", "YouTube Music"));
        l.add(doc("alarm", "morning alarm", Collections.<String>emptyList(), "Clock: Alarm > 07:00 (4 steps)", "Clock"));
        l.add(doc("torch", "flashlight", Collections.<String>emptyList(), "Quick settings > Flashlight", "Settings"));
        l.add(doc("taxi", "taxi home", Arrays.asList("yandex go"), "Yandex Go: Home (3 steps)", "Yandex Go"));
        l.add(doc("notes", "notes", Collections.<String>emptyList(), "Samsung Notes: New note (2 steps)", "Samsung Notes"));
        l.add(doc("mail", "check mail", Collections.<String>emptyList(), "Gmail: Inbox (2 steps)", "Gmail"));
        l.add(doc("dl", "downloads", Collections.<String>emptyList(), "Files: Downloads (2 steps)", "Files"));
        return l;
    }

    private static String top(String q) {
        List<MacroSearch.Hit> h = MacroSearch.rank(q, catalog(), 3, MacroSearch.MIN_SCORE);
        return h.isEmpty() ? "-" : h.get(0).doc.id;
    }

    @Test
    public void ownersExampleUzbekToEnglish() {
        assertEquals("fav", top("yoqtirgan musiqa"));
        assertEquals("fav", top("yoqtirgan musiqamni qo'y"));
        assertEquals("fav", top("sevimli musiqalarim"));
        assertEquals("fav", top("любимая музыка"));
        assertEquals("fav", top("play my favourite songs"));
    }

    @Test
    public void alarmAndTimerInThreeLanguages() {
        assertEquals("alarm", top("budilnik qo'y"));
        assertEquals("alarm", top("поставь будильник"));
        assertEquals("alarm", top("set an alarm"));
        assertEquals("alarm", top("ertalabki signal"));
    }

    @Test
    public void flashlightVariants() {
        assertEquals("torch", top("chiroqni yoq"));
        assertEquals("torch", top("включи фонарик"));
        assertEquals("torch", top("torch"));
    }

    @Test
    public void taxiNotesMailDownloads() {
        assertEquals("taxi", top("taksi chaqir"));
        assertEquals("taxi", top("вызови такси домой"));
        assertEquals("notes", top("eslatma yoz"));
        assertEquals("notes", top("новая заметка"));
        assertEquals("mail", top("pochtani tekshir"));
        assertEquals("mail", top("проверь почту"));
        assertEquals("dl", top("yuklamalar"));
        assertEquals("dl", top("загрузки"));
    }

    @Test
    public void unrelatedStillGivesNothing() {
        assertEquals("-", top("call mom"));
        assertEquals("-", top("the a of"));
    }

    @Test
    public void conceptsDoNotCollideWithExistingOnes() {
        // words that already had a meaning keep it
        assertEquals(MacroSearch.wordQuality("tolov", "payment"), 1.0, 0.0001);
        assertEquals(MacroSearch.wordQuality("yubor", "send"), 1.0, 0.0001);
        assertEquals(MacroSearch.wordQuality("otpravit", "send"), 1.0, 0.0001);
        assertEquals(MacroSearch.wordQuality("hisob", "balance"), 1.0, 0.0001);
        assertTrue(MacroSearch.wordQuality("kartam", "payment") < 1.0);
    }

    @Test
    public void stopReasonsAreClear() {
        assertTrue(StopReason.explain("ko'rinmadi").contains("not found on screen"));
        assertTrue(StopReason.explain("kutilgan ilova ochilmadi").contains("did not come to the front"));
        assertTrue(StopReason.explain(WaitUserPlayerText.TIMEOUT).contains("60 s"));
        assertTrue(StopReason.explain("").contains("interrupted"));
        assertTrue(StopReason.explain(null).contains("interrupted"));
        assertTrue(StopReason.explain("something new").startsWith("reason:"));
        String m = StopReason.message("youtube", 2, 7, "3 tap 'Search'", "ko'rinmadi", "; app in front: com.x");
        assertTrue(m, m.startsWith("stopped \"youtube\" at step 3 of 7 (3 tap 'Search'): "));
        assertTrue(m, m.contains("; app in front: com.x. read_screen"));
        assertTrue(StopReason.message("a", -1, 5, "", "", "").startsWith("stopped \"a\": it was interrupted"));
    }

    /** The timeout text lives in an Android class; the test repeats its key phrase. */
    private static final class WaitUserPlayerText {
        static final String TIMEOUT = "kutish vaqti tugadi (60 s): barmoq izi yoki ekran qulfi tasdiqlanmadi";
    }
}
