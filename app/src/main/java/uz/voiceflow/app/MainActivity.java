package uz.voiceflow.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Interfeys (assets/index.html) WebView ichida ishlaydi.
 * "Android" nomli JS ko'prigi orqali makrolar saqlanadi, servislar boshqariladi.
 */
public class MainActivity extends Activity {

    /** Ilova ekranda ko'rinib turgan paytdagina null emas. */
    static volatile MainActivity current;

    private static final int REQ_AUDIO = 101;
    private static final int REQ_NOTIF = 102;

    private WebView web;
    private boolean askedNotif = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFFF5F5F5);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(false);
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return true; }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);          // assets baribir ochiladi
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Faqat o'z sahifamiz; tashqi havolalarga o'tmaymiz.
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/index.html");
        applyImmersive();
    }

    // ------------------------------------------------------------------ hayot sikli

    @Override
    protected void onStart() {
        super.onStart();
        current = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        pushState();
    }

    @Override
    protected void onStop() {
        if (current == this) current = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return window.vfBack?window.vfBack():false}catch(e){return false}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (!"true".equals(v)) moveTaskToBack(true);
                    }
                });
    }

    // ------------------------------------------------------------------ to'liq ekran

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ------------------------------------------------------------------ holat

    private boolean isAccessibilityEnabled() {
        if (GestureService.instance != null) return true;
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return false;
        ComponentName me = new ComponentName(this, GestureService.class);
        for (String part : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(part);
            if (me.equals(c)) return true;
        }
        return false;
    }

    private String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("acc", isAccessibilityEnabled());
            o.put("listening", VoiceService.running);
            o.put("lang", Store.getLang(this));
            o.put("float", FloatService.showing);
            o.put("overlay", FloatService.canOverlay(this));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void pushState() {
        if (web == null) return;
        web.evaluateJavascript("window.vfState&&window.vfState(" + stateJson() + ")", null);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ tinglash

    private void startListeningFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        try {
            startForegroundService(new Intent(this, VoiceService.class));
        } catch (Exception e) {
            toast("Tinglashni boshlab bo'lmadi");
        }
        pushState();
    }

    private void stopListening() {
        stopService(new Intent(this, VoiceService.class));
        VoiceService.running = false;
        pushState();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow();
            } else {
                toast("Mikrofon ruxsati kerak");
            }
        } else if (code == REQ_NOTIF) {
            startListeningFlow();   // bildirishnoma ruxsati rad etilsa ham davom etamiz
        }
    }

    // ------------------------------------------------------------------ JS ko'prigi

    private final class Bridge {

        @JavascriptInterface
        public String getMacros() {
            return Store.getMacros(MainActivity.this);
        }

        @JavascriptInterface
        public void saveMacros(String json) {
            Store.saveMacros(MainActivity.this, json);
        }

        @JavascriptInterface
        public String getState() {
            return stateJson();
        }

        /** @return 0 — boshlandi, 1 — Accessibility yoqilmagan, 2 — noto'g'ri makro */
        @JavascriptInterface
        public int run(int index) {
            try {
                JSONArray arr = new JSONArray(Store.getMacros(MainActivity.this));
                JSONObject m = arr.optJSONObject(index);
                return Runner.run(MainActivity.this, m, null);
            } catch (Exception e) {
                return Runner.INVALID;
            }
        }

        @JavascriptInterface
        public void toggleListening() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (VoiceService.running) stopListening();
                    else startListeningFlow();
                }
            });
        }

        @JavascriptInterface
        public String cycleLang() {
            return Store.nextLang(MainActivity.this);
        }

        @JavascriptInterface
        public void openAccessibility() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        toast("Sozlamalarni ochib bo'lmadi");
                    }
                }
            });
        }

        /** Android 13+ da yoqib bo'lmasa: Ilova ma'lumotlari → ⋮ → "Cheklangan sozlamalarga ruxsat". */
        @JavascriptInterface
        public void openAppInfo() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Sozlamalarni ochib bo'lmadi");
                    }
                }
            });
        }

        /** Suzuvchi tugmani yoqish/o'chirish. Overlay ruxsati yo'q bo'lsa sozlamani ochadi. */
        @JavascriptInterface
        public void toggleFloat() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!FloatService.canOverlay(MainActivity.this)) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                            toast("Boshqa ilovalar ustida korsatish ruxsatini yoqing");
                        } catch (Exception e) {
                            toast("Sozlamalarni ochib bo'lmadi");
                        }
                        return;
                    }
                    if (FloatService.showing) {
                        FloatService.hide(MainActivity.this);
                    } else {
                        FloatService.show(MainActivity.this);
                    }
                    // Holatni biroz kechiktirib yangilaymiz
                    web.postDelayed(new Runnable() {
                        @Override public void run() { pushState(); }
                    }, 300);
                }
            });
        }
    }
}
