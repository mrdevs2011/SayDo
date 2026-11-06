package uz.saydo.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Per-device MCP settings and secrets (generated on the phone, never leave it except to the relay):
 *  - deviceId + deviceSecret: the phone's identity towards the relay
 *  - clientToken: what the user gives to the AI; selects and unlocks this phone
 * The relay stores only SHA-256 hashes.
 */
final class McpConfig {
    /** Default relay. Can be changed in the MCP screen (self-hosting). */
    static final String DEFAULT_RELAY = "https://saydo-relay.vercel.app";

    private static final String PREFS = "saydo_mcp";
    private static final String K_ENABLED = "enabled";
    private static final String K_FULL = "full_control";
    private static final String K_AI_NOTIF = "ai_notifications";
    private static final String K_PHONE_APPROVAL = "approve_sensitive_on_phone";
    private static final String K_MONEY_APPS = "allow_ai_money_apps";
    private static final String K_RELAY = "relay";
    private static final String K_ID = "device_id";
    private static final String K_SECRET = "device_secret";
    private static final String K_TOKEN = "client_token";
    private static final String K_NAME = "name";
    private static final String K_REGISTERED = "registered_for";

    private static final SecureRandom RNG = new SecureRandom();

    private final SharedPreferences p;

    McpConfig(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains(K_ID)) {
            p.edit()
                    .putString(K_ID, random(16))
                    .putString(K_SECRET, random(32))
                    .putString(K_TOKEN, random(32))
                    .commit();
        }
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    boolean enabled() { return p.getBoolean(K_ENABLED, false); }
    void setEnabled(boolean on) { p.edit().putBoolean(K_ENABLED, on).apply(); }

    /** Screen control (read, tap, type, screenshot). Off = only status + macros. */
    boolean fullControl() { return p.getBoolean(K_FULL, true); }
    void setFullControl(boolean on) { p.edit().putBoolean(K_FULL, on).apply(); }

    /** Lets the AI read notifications (read_notifications). Off by default; they may hold private messages. */
    boolean aiNotifications() { return p.getBoolean(K_AI_NOTIF, false); }
    void setAiNotifications(boolean on) { p.edit().putBoolean(K_AI_NOTIF, on).apply(); }

    /** Sensitive macros asked for by the AI also need an Allow on the phone (default on). */
    boolean approveSensitiveOnPhone() { return p.getBoolean(K_PHONE_APPROVAL, true); }
    void setApproveSensitiveOnPhone(boolean on) { p.edit().putBoolean(K_PHONE_APPROVAL, on).apply(); }

    /** Lets act/tap/click/type work while a money app is in front (default off). */
    boolean allowAiInMoneyApps() { return p.getBoolean(K_MONEY_APPS, false); }
    void setAllowAiInMoneyApps(boolean on) { p.edit().putBoolean(K_MONEY_APPS, on).apply(); }

    String relay() {
        String r = p.getString(K_RELAY, DEFAULT_RELAY);
        if (r == null || r.trim().isEmpty()) r = DEFAULT_RELAY;
        r = r.trim();
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    void setRelay(String url) { p.edit().putString(K_RELAY, url == null ? "" : url.trim()).apply(); }

    String deviceId() { return p.getString(K_ID, ""); }
    String deviceSecret() { return p.getString(K_SECRET, ""); }
    String clientToken() { return p.getString(K_TOKEN, ""); }

    String name() {
        String n = p.getString(K_NAME, null);
        if (n == null || n.trim().isEmpty()) n = Build.MANUFACTURER + " " + Build.MODEL;
        return n.trim();
    }

    void setName(String n) { p.edit().putString(K_NAME, n == null ? "" : n.trim()).apply(); }

    /** New AI token: the old one stops working after the next registration. */
    void rotateToken() { p.edit().putString(K_TOKEN, random(32)).apply(); }

    /** Fingerprint of what the relay last accepted; differs -> must register again. */
    String registrationKey() { return relay() + "|" + sha256(clientToken()) + "|" + name(); }
    boolean isRegistered() { return registrationKey().equals(p.getString(K_REGISTERED, "")); }
    void markRegistered() { p.edit().putString(K_REGISTERED, registrationKey()).apply(); }
    void clearRegistered() { p.edit().remove(K_REGISTERED).apply(); }

    /** URL form (token in the path) for clients that only accept a URL. */
    String mcpUrlWithToken() { return relay() + "/mcp/" + clientToken(); }

    /** Endpoint for clients that support an Authorization header. */
    String mcpEndpoint() { return relay() + "/api/mcp"; }

    String serverSlug() {
        String s = name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (s.isEmpty()) s = "phone";
        if (s.length() > 24) s = s.substring(0, 24);
        return "saydo-" + s;
    }

    String claudeCodeCommand() {
        return "claude mcp add --transport http " + serverSlug() + " " + mcpEndpoint()
                + " --header \"Authorization: Bearer " + clientToken() + "\"";
    }
}
