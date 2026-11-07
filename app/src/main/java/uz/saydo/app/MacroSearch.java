package uz.saydo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks the owner's macros against a goal written in natural language (pure Java, no Android, unit tested).
 *
 * The AI passes the goal in the owner's words ("youtube ni ochib yoqtirgan musiqamni qoy", "ютуб музыка"),
 * which rarely equals the trigger text. So the score looks at every piece of text a macro has:
 * trigger and aliases (weight 1), app labels (0.85), description (0.75), texts of clicked buttons (0.5),
 * category (0.3). Words are folded (case, accents, Latin/Cyrillic), a small table maps the same idea in
 * Uzbek, Russian and English to one concept ("musiqa", "музыка", "music"), and partial words, sound-alike
 * spellings and small typos still count. Word order does not matter.
 */
final class MacroSearch {
    private MacroSearch() {}

    /** Below this a candidate is not worth showing. */
    static final double MIN_SCORE = 0.25;
    /** The AI is told that this and above is a good match. */
    static final double GOOD = 0.6;

    /** Plain data of one macro for ranking. All lists may be empty, never null. */
    static final class Doc {
        final String id, trigger, description, category;
        final List<String> aliases, apps, anchors;
        final int usage;

        Doc(String id, String trigger, List<String> aliases, String description, List<String> apps,
            List<String> anchors, String category, int usage) {
            this.id = id == null ? "" : id;
            this.trigger = trigger == null ? "" : trigger;
            this.aliases = aliases == null ? Collections.<String>emptyList() : aliases;
            this.description = description == null ? "" : description;
            this.apps = apps == null ? Collections.<String>emptyList() : apps;
            this.anchors = anchors == null ? Collections.<String>emptyList() : anchors;
            this.category = category == null ? "" : category;
            this.usage = usage;
        }
    }

    static final class Hit {
        final Doc doc;
        final double score;

        Hit(Doc doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }

    // ------------------------------------------------------------------ vocabulary

    private static final Set<String> STOP = new HashSet<>();
    private static final Set<String> WEAK = new HashSet<>();
    private static final Map<String, String> CONCEPT = new HashMap<>();

    private static void concept(String canon, String... words) {
        CONCEPT.put(canon, canon);
        for (String w : words) CONCEPT.put(w, canon);
    }

    static {
        String[] stop = {
                // Uzbek
                "ni", "ga", "da", "dan", "va", "bilan", "uchun", "men", "mening", "menga", "mani", "bir", "iltimos",
                "kerak", "bor", "shu", "u", "bu", "ham", "yoki", "mi",
                // Russian (folded)
                "i", "v", "na", "s", "so", "moi", "moy", "moyu", "moyo", "mne", "pozhaluysta", "pozhaluista",
                "eto", "po", "dlya", "ili", "mi",
                // English
                "the", "a", "an", "to", "my", "me", "for", "and", "please", "of", "in", "on", "with", "i", "can",
                "you", "could", "want", "need", "mine", "some"};
        Collections.addAll(STOP, stop);
        STOP.remove("you"); // part of "you tube" spellings, handled below by skeleton; harmless as content
        // Verbs of "do it" say little about which macro is meant.
        Collections.addAll(WEAK, "open", "play", "start", "do", "make", "check", "show");

        concept("open", "och", "ochish", "ochib", "ochgin", "otkroi", "otkryt", "otkroy", "zapusti", "zapustit",
                "launch", "run", "ishga");
        concept("play", "qoy", "qoyish", "qoying", "chal", "chalish", "vklyuchi", "vkluchi", "pusti", "vosproizvedi",
                "vosproizvesti", "tinglash", "eshit", "listen");
        concept("music", "musiqa", "musiqam", "musiqamni", "musika", "muzika", "muzyka", "qoshiq", "qoshiqlar",
                "pesnya", "pesni", "song", "songs", "tracks", "mp3");
        concept("favorite", "favourite", "favorit", "favoritlar", "favourites", "favorites", "sevimli", "sevimlilar",
                "yoqtirgan", "yoqtirganlar", "yoqtirganim", "lyubimyi", "lyubimye", "lyubimoe", "izbrannoe",
                "izbrannye", "lyubim", "liked", "like", "likes");
        concept("payment", "pay", "tolov", "tolash", "oplata", "oplatit", "oplati", "payments", "perevod",
                "otkazma", "transfer");
        concept("message", "messages", "xabar", "xabarlar", "sms", "soobshenie", "soobsheniya", "chat", "chatlar");
        concept("send", "yubor", "yuborish", "yuboring", "otpravit", "otpravi");
        concept("search", "qidir", "qidirish", "qidiruv", "poisk", "naiti", "najti", "find");
        concept("settings", "setting", "sozlamalar", "sozlash", "nastroiki", "nastroyki");
        concept("camera", "kamera");
        concept("balance", "balans", "ostatok", "hisob", "schet");
        concept("delete", "remove", "ochir", "ochirish", "udali", "udalit");
        concept("photo", "photos", "rasm", "rasmlar", "foto", "fotografiya", "gallery", "galereya");
        concept("call", "qongiroq", "zvonok", "pozvoni", "calls");
        concept("group", "groups", "guruh", "guruhlar", "gruppa", "gruppy", "gruppi");
        concept("check", "tekshir", "tekshirish", "proveri", "proverit", "review");
        concept("video", "videos", "klip", "kino", "film");
        concept("youtube", "yutub", "yutube", "utube", "yt");
        concept("telegram", "telegramm", "tg");
        concept("wifi", "vayfay");
        concept("news", "yangilik", "yangiliklar", "novosti");
        concept("weather", "ob", "havo", "pogoda");
        // More everyday words in the three languages (folded forms: Cyrillic as Latin, no apostrophes).
        concept("alarm", "alarms", "budilnik", "budilniki", "signal", "uygotgich", "uygotuvchi", "soatsignal");
        concept("timer", "taymer", "timers");
        concept("volume", "ovoz", "ovozni", "ovozini", "gromkost", "gromkosti", "zvuk", "zvuka", "sound", "loud",
                "balandroq", "gromche", "tishe", "pastroq", "quieter", "louder");
        concept("mute", "silent", "ovozsiz", "bezzvuka", "tiho", "vibratsiya", "vibro");
        concept("flashlight", "torch", "chiroq", "fonar", "fonarik", "fonarik", "flashka");
        concept("brightness", "yorugliq", "yorugligi", "yarkost", "yarkosti", "bright", "dim");
        concept("bluetooth", "blyutuz", "blutuz", "bluetuz", "blyutus");
        concept("battery", "batareya", "batareyka", "quvvat", "zaryad", "zaryadka", "charge", "charging");
        concept("internet", "tarmoq", "internetga");
        concept("calculator", "kalkulyator", "kalkulator", "calc", "hisoblagich");
        concept("clock", "soat", "chasi", "chasy", "vaqt", "vremya", "time");
        concept("contacts", "contact", "kontakt", "kontaktlar", "kontakti", "rehbar", "telefonkitob", "telefonnaya");
        concept("mail", "email", "gmail", "pochta", "pochtasi", "xat", "xatlar", "pisma", "pismo", "letters");
        concept("maps", "map", "xarita", "xaritalar", "navigation", "navigator", "yonalish",
                "marshrut", "route", "directions");
        concept("taxi", "taksi", "yandextaxi", "uber", "ride", "yollash");
        concept("order", "orders", "buyurtma", "buyurtmalar", "zakaz", "zakazi", "zakazat");
        concept("food", "ovqat", "ovqatlar", "eda", "edu", "yemek", "meal", "dinner", "lunch", "tushlik");
        concept("shop", "store", "dokon", "dokonlar", "magazin", "magazini", "bozor", "market", "shopping");
        concept("notes", "note", "eslatma", "eslatmalar", "zametka", "zametki", "zametku", "memo");
        concept("calendar", "kalendar", "taqvim", "kalendarya", "kalendari", "events", "tadbir");
        concept("download", "downloads", "yuklab", "yuklash", "yuklanma", "yuklamalar", "skachat", "skachai",
                "zagruzki", "zagruzka");
        concept("profile", "profil", "profili", "akkaunt", "account", "hisobim");
        concept("login", "signin", "kirish", "kir", "vhod", "voyti", "voydi", "avtorizatsiya");
        concept("logout", "signout", "chiqish", "chiq", "vihod", "viyti", "viydi");
        concept("next", "keyingi", "keyingisi", "sleduyushiy", "dalshe", "skip", "otkazib");
        concept("stop", "toxtat", "toxtatish", "ostanovi", "ostanovit", "stoy", "halt");
        concept("pause", "pauza", "pauzaga", "pausa");
        concept("notifications", "notification", "xabarnoma", "xabarnomalar", "uvedomleniya", "uvedomlenie",
                "bildirishnoma", "bildirishnomalar", "alerts");
        concept("share", "ulashish", "ulash", "podelitsya", "podelis", "forward");
        concept("save", "saqlash", "saqla", "sohrani", "sohranit", "saved", "saqlanganlar");
        concept("history", "tarix", "istoriya", "istoriyu", "recent", "recents", "songgi");
        concept("subscribe", "obuna", "obunalar", "podpiska", "podpiski", "subscriptions", "subscribed");
        concept("playlist", "pleylist", "playlists", "pleylistlar", "pleylisti");
        concept("radio", "radiostantsiya", "fm");
        concept("podcast", "podcasts", "podkast", "podkasti");
        concept("book", "books", "kitob", "kitoblar", "kniga", "knigi", "audiokitob", "audiobook");
    }

    /** Endings of Uzbek and Russian words, longest first; removed only to find a known concept. */
    private static final String[] SUFFIXES = {
            "larini", "larni", "lari", "lar", "ning", "ini", "uyu", "yyu", "aya", "oye", "ogo", "ami", "ah",
            "ni", "ga", "da", "dan", "im", "ing", "gan", "ku", "ke", "ye", "oe", "ov", "u", "e", "i", "a", "y"};

    private static String conceptOf(String foldedWord) {
        String c = CONCEPT.get(foldedWord);
        if (c != null) return c;
        for (String suf : SUFFIXES) {
            if (foldedWord.length() - suf.length() >= 4 && foldedWord.endsWith(suf)) {
                String stem = foldedWord.substring(0, foldedWord.length() - suf.length());
                c = CONCEPT.get(stem);
                if (c == null) c = CONCEPT.get(stem + "a"); // "pochtani" -> "pochta"
                if (c != null) return c;
            }
        }
        return foldedWord;
    }

    // ------------------------------------------------------------------ word and field matching

    /** Quality 0..1 of one folded query word against one folded document word. */
    static double wordQuality(String q, String d) {
        if (q.isEmpty() || d.isEmpty()) return 0;
        String qc = conceptOf(q), dc = conceptOf(d);
        if (qc.equals(dc)) return 1.0;
        if (isNumber(qc) || isNumber(dc)) return 0;
        int ql = qc.length(), dl = dc.length();
        if (Math.min(ql, dl) < 3) return 0;
        // Partial word: "youtu" for "youtube", "musiqamni" for "musiqa" (suffixes are common in Uzbek).
        if (dc.startsWith(qc)) return ql >= 4 ? 0.8 : 0.7;
        if (dl >= 4 && qc.startsWith(dc)) return 0.8;
        int p = 0;
        while (p < ql && p < dl && qc.charAt(p) == dc.charAt(p)) p++;
        if (p >= 4 && p >= 0.7 * Math.min(ql, dl)) return 0.75;
        String qs = McpText.skeleton(qc), ds = McpText.skeleton(dc);
        if (qs.length() >= 3 && qs.equals(ds) && qc.charAt(0) == dc.charAt(0)) return 0.85;
        if (ql >= 5 && dl >= 5) {
            double sim = TriggerMatcher.sim(qc, dc);
            if (sim >= 0.8) return 0.9 * sim;
        }
        return 0;
    }

    private static boolean isNumber(String s) {
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return !s.isEmpty();
    }

    private static List<String> contentWords(String text) {
        List<String> out = new ArrayList<>();
        for (String w : McpText.words(text)) if (!STOP.contains(w)) out.add(w);
        return out;
    }

    private static double bestQuality(String q, List<String> docWords) {
        double best = 0;
        for (String d : docWords) {
            double v = wordQuality(q, d);
            if (v > best) best = v;
        }
        return best;
    }

    private static final double W_NAME = 1.0, W_APP = 0.85, W_DESC = 0.75, W_ANCHOR = 0.5, W_CAT = 0.3;

    /** Score 0..1 of one macro for the goal. */
    static double score(String query, Doc doc) {
        List<String> q = contentWords(query);
        if (q.isEmpty()) return 0;

        List<List<String>> names = new ArrayList<>();
        names.add(contentWords(doc.trigger));
        for (String a : doc.aliases) names.add(contentWords(a));
        List<String> nameWords = new ArrayList<>();
        for (List<String> n : names) nameWords.addAll(n);
        List<String> appWords = contentWords(join(doc.apps));
        List<String> descWords = contentWords(doc.description);
        List<String> anchorWords = contentWords(join(doc.anchors));
        List<String> catWords = "general".equalsIgnoreCase(doc.category) ? Collections.<String>emptyList()
                : contentWords(doc.category);

        double sum = 0, wsum = 0;
        boolean anyStrong = false;
        for (String w : q) {
            double weight = WEAK.contains(conceptOf(w)) ? 0.3 : 1.0;
            if (weight == 1.0) anyStrong = true;
            double best = Math.max(
                    Math.max(W_NAME * bestQuality(w, nameWords), W_APP * bestQuality(w, appWords)),
                    Math.max(Math.max(W_DESC * bestQuality(w, descWords), W_ANCHOR * bestQuality(w, anchorWords)),
                            W_CAT * bestQuality(w, catWords)));
            sum += weight * best;
            wsum += weight;
        }
        double qCov = wsum == 0 ? 0 : sum / wsum;
        if (!anyStrong) qCov *= 0.5;

        // How much of the macro's own name the goal covers (a macro "youtube" beats "youtube favorite music"
        // for the goal "youtube", and the long one wins when the goal mentions all of its words).
        double docCov = 0;
        for (List<String> n : names) {
            if (n.isEmpty()) continue;
            int hit = 0;
            for (String d : n) {
                for (String w : q) {
                    if (wordQuality(w, d) >= 0.7) {
                        hit++;
                        break;
                    }
                }
            }
            docCov = Math.max(docCov, hit / (double) n.size());
        }
        double s = qCov * (0.7 + 0.3 * docCov);

        // Whole phrase against the trigger or an alias, with the voice matcher's own tolerance.
        // Only when the goal is about as short as the name: a long sentence that merely contains the
        // trigger word ("youtube ... music") must not make the one-word macro win.
        String nq = TriggerMatcher.norm(query);
        for (String name : namesOf(doc)) {
            String nn = TriggerMatcher.norm(name);
            if (nn.length() < 3 || nq.isEmpty()) continue;
            if (nq.split(" ").length > nn.split(" ").length + 2) continue;
            double t = TriggerMatcher.score(nq, nn);
            if (t >= TriggerMatcher.FUZZY) s = Math.max(s, t);
        }

        if (s > 0.3 && doc.usage > 0) s += 0.03 * Math.min(1.0, doc.usage / 10.0);
        if (s > 1.0) s = 1.0;
        return Math.round(s * 100.0) / 100.0;
    }

    private static List<String> namesOf(Doc d) {
        List<String> n = new ArrayList<>();
        n.add(d.trigger);
        n.addAll(d.aliases);
        return n;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append(' ');
        return sb.toString();
    }

    /** Best candidates first; at most {@code limit} with a score of at least {@code minScore}. */
    static List<Hit> rank(String query, List<Doc> docs, int limit, double minScore) {
        List<Hit> hits = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return hits;
        for (Doc d : docs) {
            double s = score(query, d);
            if (s >= minScore) hits.add(new Hit(d, s));
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                int c = Double.compare(b.score, a.score);
                if (c != 0) return c;
                c = Integer.compare(b.doc.usage, a.doc.usage);
                if (c != 0) return c;
                return a.doc.trigger.compareTo(b.doc.trigger);
            }
        });
        if (limit > 0 && hits.size() > limit) return new ArrayList<>(hits.subList(0, limit));
        return hits;
    }
}
