package uz.saydo.app.mock;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * High-fidelity ContextWrapper for JVM unit tests without Roboelectric or emulator.
 */
public class TestContext extends ContextWrapper {

    private final File filesDir;
    private final MockSharedPreferences prefs = new MockSharedPreferences();

    public TestContext(File filesDir) {
        super(null);
        this.filesDir = filesDir;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public String getPackageName() {
        return "uz.saydo.app";
    }

    @Override
    public File getFilesDir() {
        return filesDir;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs;
    }

    public static class MockSharedPreferences implements SharedPreferences {
        public final Map<String, Object> map = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return map;
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = map.get(key);
            return v != null ? String.valueOf(v) : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = map.get(key);
            return v instanceof Number ? ((Number) v).intValue() : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = map.get(key);
            return v instanceof Number ? ((Number) v).longValue() : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = map.get(key);
            return v instanceof Number ? ((Number) v).floatValue() : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MockEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        public class MockEditor implements Editor {
            @Override
            public Editor putString(String key, String value) {
                map.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                map.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                map.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                map.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                map.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                map.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                map.clear();
                return this;
            }

            @Override
            public boolean commit() {
                return true;
            }

            @Override
            public void apply() {}
        }
    }
}
