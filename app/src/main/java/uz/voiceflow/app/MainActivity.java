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
 * "Android" JS ko'prigi orqali makrolar saqlanadi, servislar boshqariladi.
 */
public class MainActivity extends Activity {

    static volatile MainActivity current;

    private static final int REQ_AUDIO = 101;
    private static final int REQ_NOTIF = 102;

    private WebView web;
    private boolean askedNotif = false;
    private boolean destroyed = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;

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
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(false);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request == null || request.getUrl() == null
                        || !request.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/index.html");
        applyImmersive();
    }

    @Override
    protected void onStart() {
        super.onStart();
        current = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        // Overlay ruxsati berilgan bo'lsa — float ni qayta ko'rsat
        if (Store.isEnabled(this) && FloatService.canOverlay(this)) {
            FloatService.show(this);
            // Master ON, lekin tinglash to'xtagan bo'lsa — qayta ishga tushirish
            if (!VoiceService.running
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow();
            }
        }
        pushState();
    }

    @Override
    protected void onStop() {
        if (current == this) current = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (current == this) current = null;
        if (web != null) {
            try { web.removeJavascriptInterface("Android"); } catch (Exception ignored) { }
            try {
                web.loadUrl("about:blank");
                web.stopLoading();
                web.setWebViewClient(null);
                web.destroy();
            } catch (Exception ignored) { }
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
        if (web == null || destroyed) {
            super.onBackPressed();
            return;
        }
        try {
            web.evaluateJavascript(
                    "(function(){try{return window.vfBack?window.vfBack():false}catch(e){return false}})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            if (!"true".equals(v) && !destroyed) moveTaskToBack(true);
                        }
                    });
        } catch (Exception e) {
            moveTaskToBack(true);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        try {
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
        } catch (Exception ignored) { }
    }

    private boolean isAccessibilityEnabled() {
        try {
            if (GestureService.instance != null) return true;
            String enabled = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName me = new ComponentName(this, GestureService.class);
            for (String part : enabled.split(":")) {
                ComponentName c = ComponentName.unflattenFromString(part);
                if (me.equals(c)) return true;
            }
        } catch (Exception ignored) { }
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
            o.put("enabled", Store.isEnabled(this));
            o.put("recording", FloatService.recording);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void pushState() {
        if (web == null || destroyed) return;
        try {
            web.evaluateJavascript("window.vfState&&window.vfState(" + stateJson() + ")", null);
        } catch (Exception ignored) { }
    }

    private void toast(final String m) {
        if (destroyed || m == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!destroyed) Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startListeningFlow() {
        if (destroyed) return;
        if (!Store.isEnabled(this)) return;
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
            Intent i = new Intent(this, VoiceService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            toast("Tinglashni boshlab bo'lmadi");
        }
        pushState();
    }

    private void stopListening() {
        try { stopService(new Intent(this, VoiceService.class)); } catch (Exception ignored) { }
        VoiceService.running = false;
        pushState();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (destroyed) return;
        if (code == REQ_AUDIO) {
            if (results != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow();
            } else {
                toast("Mikrofon ruxsati kerak");
            }
        } else if (code == REQ_NOTIF) {
            startListeningFlow();
        }
    }

    private void applyMaster(boolean on) {
        if (destroyed) return;
        Store.setEnabled(this, on);
        if (on) {
            if (!FloatService.canOverlay(this)) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    toast("Boshqa ilovalar ustida ko'rsatish ruxsatini yoqing");
                } catch (Exception e) {
                    toast("Sozlamalarni ochib bo'lmadi");
                }
            } else {
                FloatService.show(this);
            }
            startListeningFlow();
            maybeRequestBatteryOpt();
        } else {
            FloatService.hide(this);
            stopListening();
        }
        pushState();
    }

    private void maybeRequestBatteryOpt() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            String pkg = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg)));
            }
        } catch (Exception ignored) { }
    }

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

        @JavascriptInterface
        public int run(int index) {
            try {
                JSONArray arr = new JSONArray(Store.getMacros(MainActivity.this));
                if (index < 0 || index >= arr.length()) return Runner.INVALID;
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
                    if (destroyed) return;
                    if (VoiceService.running) stopListening();
                    else {
                        if (!Store.isEnabled(MainActivity.this)) {
                            toast("Avval ON qiling");
                            return;
                        }
                        startListeningFlow();
                    }
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
                    if (destroyed) return;
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        toast("Sozlamalarni ochib bo'lmadi");
                    }
                }
            });
        }

        @JavascriptInterface
        public void openAppInfo() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        toast("Sozlamalarni ochib bo'lmadi");
                    }
                }
            });
        }

        @JavascriptInterface
        public void setEnabled(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!destroyed) applyMaster(on);
                }
            });
        }

        @JavascriptInterface
        public void ensureFloat() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    if (Store.isEnabled(MainActivity.this) && FloatService.canOverlay(MainActivity.this)) {
                        FloatService.show(MainActivity.this);
                    }
                    pushState();
                }
            });
        }

        @JavascriptInterface
        public void toggleFloat() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    if (!FloatService.canOverlay(MainActivity.this)) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                            toast("Boshqa ilovalar ustida ko'rsatish ruxsatini yoqing");
                        } catch (Exception e) {
                            toast("Sozlamalarni ochib bo'lmadi");
                        }
                        return;
                    }
                    if (FloatService.showing) FloatService.hide(MainActivity.this);
                    else FloatService.show(MainActivity.this);
                    if (web != null) {
                        web.postDelayed(new Runnable() {
                            @Override public void run() { pushState(); }
                        }, 300);
                    }
                }
            });
        }
    }
}
