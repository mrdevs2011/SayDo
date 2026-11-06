package uz.saydo.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MacroSearchTest {

    private static MacroSearch.Doc doc(String id, String trigger, String desc, String... apps) {
        return new MacroSearch.Doc(id, trigger, Collections.<String>emptyList(), desc, Arrays.asList(apps),
                Collections.<String>emptyList(), "General", 0);
    }

    /** Made-up catalog; not the owner's macros. */
    private static List<MacroSearch.Doc> catalog() {
        List<MacroSearch.Doc> l = new ArrayList<>();
        l.add(doc("1", "youtube favorite music", "YouTube: Library > Favourites (6 steps)", "YouTube"));
        l.add(doc("2", "youtube", "YouTube (1 step)", "YouTube"));
        l.add(new MacroSearch.Doc("3", "payme", Arrays.asList("oplata", "pay"), "Payme: Pay > Utilities (4 steps)",
                Arrays.asList("Payme"), Arrays.asList("Utilities"), "General", 2));
        l.add(doc("4", "wifi settings", "Settings: Connections > Wi-Fi (3 steps)", "Settings"));
        l.add(doc("5", "telegram groups", "Telegram: Chats > Groups (3 steps)", "Telegram"));
        l.add(doc("6", "calculator", "Calculator: 7 > + > 8 (5 steps)", "Calculator"));
        return l;
    }

    private static String top(String query) {
        List<MacroSearch.Hit> h = MacroSearch.rank(query, catalog(), 3, MacroSearch.MIN_SCORE);
        return h.isEmpty() ? "-" : h.get(0).doc.id;
    }

    @Test
    public void uzbekSentenceMatchesEnglishTrigger() {
        List<MacroSearch.Hit> h = MacroSearch.rank("youtube ni ochib yoqtirgan musiqamni qoy", catalog(), 3,
                MacroSearch.MIN_SCORE);
        assertEquals("1", h.get(0).doc.id);
        assertTrue("score " + h.get(0).score, h.get(0).score >= MacroSearch.GOOD);
    }

    @Test
    public void cyrillicMatchesLatin() {
        assertEquals("2", top("ютуб"));
        assertEquals("1", top("ютуб любимая музыка"));
        assertEquals("1", top("включи мою любимую музыку на ютубе"));
    }

    @Test
    public void russianAndEnglishPaymentGoals() {
        assertEquals("3", top("оплата через payme"));
        assertEquals("3", top("pay with Payme"));
        assertEquals("3", top("payme orqali tolov qilish"));
    }

    @Test
    public void wordOrderAndPartialWords() {
        assertEquals("1", top("music favorite youtube"));
        assertEquals("1", top("youtu fav mus"));
        assertEquals("5", top("telegram guruhlarini tekshir"));
    }

    @Test
    public void appLabelAndAnchorTextCount() {
        // "Utilities" only appears as a clicked button of the Payme macro.
        assertEquals("3", top("utilities"));
        // "wi-fi" in the description of macro 4
        assertEquals("4", top("wi-fi"));
    }

    @Test
    public void unrelatedGoalGivesNothing() {
        assertEquals("-", top("book a taxi to the airport"));
        assertEquals("-", top(""));
        assertEquals("-", top("ni va bilan"));
    }

    @Test
    public void shorterNameWinsForShortGoal() {
        assertEquals("2", top("youtube"));
        assertEquals("2", top("open youtube"));
    }

    @Test
    public void limitAndOrder() {
        List<MacroSearch.Hit> h = MacroSearch.rank("youtube", catalog(), 1, MacroSearch.MIN_SCORE);
        assertEquals(1, h.size());
        List<MacroSearch.Hit> all = MacroSearch.rank("youtube", catalog(), 10, MacroSearch.MIN_SCORE);
        assertTrue(all.size() >= 2);
        assertTrue(all.get(0).score >= all.get(1).score);
    }

    @Test
    public void goodMatchesReachTheGoodThreshold() {
        for (String q : new String[]{"youtube ni ochib yoqtirgan musiqamni qoy", "включи любимую музыку на ютубе",
                "ютуб", "pay with Payme", "telegram guruhlarini tekshir"}) {
            List<MacroSearch.Hit> h = MacroSearch.rank(q, catalog(), 3, MacroSearch.MIN_SCORE);
            assertTrue(q + " -> " + h.size(), !h.isEmpty() && h.get(0).score >= MacroSearch.GOOD);
        }
    }

    @Test
    public void foldingBasics() {
        assertEquals("yutub", McpText.fold("Ютуб"));
        assertEquals("yuborish", McpText.fold("Юбориш"));
        assertEquals("ozbekcha", McpText.fold("O'zbekcha"));
        assertEquals("cafe", McpText.fold("Café"));
        assertEquals(McpText.skeleton("youtube"), McpText.skeleton(McpText.fold("ютуб")));
        assertTrue(McpText.containsFolded("Юбориш", "Yuborish"));
        assertTrue(McpText.containsFolded("Send MESSAGE", "message"));
    }
}
