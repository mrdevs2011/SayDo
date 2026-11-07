package uz.saydo.app.safety;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Seals the sensitive steps (PIN taps) of a macro's JSON before it is written to disk, and opens
 * them again when it is read (pure Java, unit tested with a software SecretBox).
 * A sealed step keeps only its type "enc", its times (so the order survives) and the ciphertext.
 */
public final class SensitiveSteps {
    private SensitiveSteps() {}

    public static final String SEALED_TYPE = "enc";

    /** True when the macro JSON has at least one sensitive (plain) step. */
    public static boolean hasSensitive(JSONObject macro) {
        JSONArray g = macro.optJSONArray("gestures");
        for (int i = 0; g != null && i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o != null && o.optBoolean("sens", false)) return true;
        }
        return false;
    }

    /** True when the macro JSON has a sealed step. */
    public static boolean hasSealed(JSONObject macro) {
        JSONArray g = macro.optJSONArray("gestures");
        for (int i = 0; g != null && i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o != null && SEALED_TYPE.equals(o.optString("type"))) return true;
        }
        return false;
    }

    /** Returns a copy of the macro JSON in which every sensitive step is encrypted. */
    public static JSONObject seal(JSONObject macro, SecretBox box) throws GeneralSecurityException, JSONException {
        JSONObject out = new JSONObject(macro.toString());
        JSONArray g = out.optJSONArray("gestures");
        for (int i = 0; g != null && i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o == null || !o.optBoolean("sens", false)) continue;
            byte[] sealed = box.encrypt(o.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject s = new JSONObject();
            s.put("type", SEALED_TYPE);
            s.put("st", o.optLong("st", 0));
            s.put("et", o.optLong("et", 0));
            s.put("enc", Base64.encodeToString(sealed, Base64.NO_WRAP));
            g.put(i, s);
        }
        return out;
    }

    /** Returns a copy with every sealed step decrypted. Throws if a step cannot be opened (key lost or data changed). */
    public static JSONObject open(JSONObject macro, SecretBox box) throws GeneralSecurityException, JSONException {
        JSONObject out = new JSONObject(macro.toString());
        JSONArray g = out.optJSONArray("gestures");
        for (int i = 0; g != null && i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o == null || !SEALED_TYPE.equals(o.optString("type"))) continue;
            byte[] plain = box.decrypt(Base64.decode(o.optString("enc", ""), Base64.NO_WRAP));
            g.put(i, new JSONObject(new String(plain, StandardCharsets.UTF_8)));
        }
        return out;
    }
}
