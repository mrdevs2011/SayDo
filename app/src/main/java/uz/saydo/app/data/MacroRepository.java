package uz.saydo.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import uz.saydo.app.safety.KeystoreBox;
import uz.saydo.app.safety.SecretBox;
import uz.saydo.app.safety.SensitiveSteps;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe, reactive repository managing Macro persistence with atomic file writes,
 * automatic backup recovery (.bak), legacy schema migration, and main-thread change notifications.
 */
public final class MacroRepository {
    private static final String TAG = "MacroRepository";
    private static final String FILE_NAME = "macros_v2.json";
    private static final String TMP_FILE_NAME = "macros_v2.json.tmp";
    private static final String BAK_FILE_NAME = "macros_v2.json.bak";
    private static final String LEGACY_PREFS = "saydo";
    private static final String LEGACY_KEY_MACROS = "macros";

    private static volatile MacroRepository sInstance;

    private final Context context;
    private final File primaryFile;
    private final File tmpFile;
    private final File bakFile;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final List<OnMacrosChangedListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // In-memory cache keyed by unique Macro ID
    private final Map<String, Macro> cache = new LinkedHashMap<>();

    /**
     * Encrypts the steps marked sensitive (PIN taps) on disk. Null when the Keystore is unavailable;
     * then macros with such steps are kept in memory only and never written (fail closed).
     */
    private SecretBox secretBox;

    public interface OnMacrosChangedListener {
        void onMacrosChanged(List<Macro> macros);
    }

    private MacroRepository(Context context) {
        this.context = context.getApplicationContext();
        File dir = this.context.getFilesDir();
        this.primaryFile = new File(dir, FILE_NAME);
        this.tmpFile = new File(dir, TMP_FILE_NAME);
        this.bakFile = new File(dir, BAK_FILE_NAME);
        SecretBox box = null;
        try {
            box = KeystoreBox.tryCreate();
        } catch (Throwable ignored) {
            // no Android Keystore (unit tests): sensitive macros stay in memory only
        }
        this.secretBox = box;

        loadInitial();
    }

    public static MacroRepository getInstance(Context context) {
        if (sInstance == null) {
            synchronized (MacroRepository.class) {
                if (sInstance == null) {
                    sInstance = new MacroRepository(context);
                }
            }
        }
        return sInstance;
    }

    /** Unit tests only: a software key in place of the Android Keystore. */
    public void reloadWithBoxForTest(SecretBox box) {
        this.secretBox = box;
        loadInitial();
    }

    /**
     * One macro as read from disk: sealed steps are opened. Null = unreadable (no key, key lost, data
     * changed); such a macro is dropped instead of being run with missing steps.
     */
    private JSONObject openIfSealed(JSONObject obj) {
        if (!SensitiveSteps.hasSealed(obj)) return obj;
        if (secretBox == null) {
            Log.w(TAG, "Sealed macro skipped: no Keystore");
            return null;
        }
        try {
            return SensitiveSteps.open(obj, secretBox);
        } catch (Throwable t) {
            Log.w(TAG, "Sealed macro skipped: cannot be decrypted (" + t.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public void addListener(OnMacrosChangedListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            // Immediately notify listener of current state
            final List<Macro> current = getAll();
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onMacrosChanged(current);
                }
            });
        }
    }

    public void removeListener(OnMacrosChangedListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyChanged() {
        final List<Macro> snapshot = getAll();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnMacrosChangedListener l : listeners) {
                    try {
                        l.onMacrosChanged(snapshot);
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in OnMacrosChangedListener", t);
                    }
                }
            }
        });
    }

    /**
     * Loads macros into memory from primary file, backup file, or legacy preferences.
     */
    private void loadInitial() {
        rwLock.writeLock().lock();
        try {
            cache.clear();
            boolean loaded = false;

            // 1. Try reading primary file
            if (primaryFile.exists() && primaryFile.length() > 0) {
                loaded = readFromFile(primaryFile);
            }

            // 2. If primary file missing or corrupt, try backup file
            if (!loaded && bakFile.exists() && bakFile.length() > 0) {
                Log.w(TAG, "Primary file invalid. Recovering from backup: " + bakFile.getName());
                loaded = readFromFile(bakFile);
                if (loaded) {
                    // Restore backup to primary
                    persistLocked();
                }
            }

            // 3. If neither exists, attempt legacy migration from SharedPreferences
            if (!loaded) {
                migrateFromLegacyPreferences();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private boolean readFromFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read <= 0) return false;
            String jsonStr = new String(data, 0, read, StandardCharsets.UTF_8).trim();
            if (!jsonStr.startsWith("[") || !jsonStr.endsWith("]")) {
                return false;
            }
            JSONArray arr = new JSONArray(jsonStr);
            cache.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) obj = openIfSealed(obj);
                if (obj != null) {
                    Macro m = Macro.fromJson(obj);
                    if (m != null) {
                        cache.put(m.id, m);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed reading macros from " + file.getAbsolutePath(), t);
            return false;
        }
    }

    private void migrateFromLegacyPreferences() {
        try {
            SharedPreferences sp = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
            String legacyJson = sp.getString(LEGACY_KEY_MACROS, null);
            if (legacyJson != null && !legacyJson.trim().isEmpty() && !legacyJson.equals("[]")) {
                JSONArray arr = new JSONArray(legacyJson.trim());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) {
                        Macro m = Macro.fromJson(obj);
                        if (m != null) {
                            cache.put(m.id, m);
                        }
                    }
                }
                persistLocked();
                Log.i(TAG, "Successfully migrated " + cache.size() + " macros from legacy preferences");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error migrating legacy preferences", t);
        }
    }

    /**
     * Persists in-memory cache to disk atomically using temporary file and backup rotation.
     * MUST be called while holding writeLock.
     */
    private boolean persistLocked() {
        try {
            JSONArray arr = new JSONArray();
            for (Macro m : cache.values()) {
                JSONObject j = m.toJson();
                if (SensitiveSteps.hasSensitive(j)) {
                    if (secretBox == null) {
                        Log.w(TAG, "Macro with sensitive steps not written: no Keystore");
                        continue;
                    }
                    j = SensitiveSteps.seal(j, secretBox);
                }
                arr.put(j);
            }
            byte[] bytes = arr.toString().getBytes(StandardCharsets.UTF_8);

            // 1. Write to tmp file
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                fos.write(bytes);
                fos.flush();
                fos.getFD().sync();
            }

            // 2. Rotate existing primary file to backup
            if (primaryFile.exists()) {
                if (bakFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    bakFile.delete();
                }
                if (!primaryFile.renameTo(bakFile)) {
                    Log.w(TAG, "Failed to rotate primary to backup file");
                }
            }

            // 3. Rename tmp file to primary file
            if (!tmpFile.renameTo(primaryFile)) {
                Log.e(TAG, "Failed to rename tmp to primary file");
                // Attempt to restore backup if rename failed
                if (bakFile.exists() && !primaryFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    bakFile.renameTo(primaryFile);
                }
                return false;
            }

            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Error persisting macros atomically", t);
            return false;
        }
    }

    /**
     * Returns an unmodifiable snapshot of all macros.
     */
    public List<Macro> getAll() {
        rwLock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(cache.values()));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int count() {
        rwLock.readLock().lock();
        try {
            return cache.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Macro getById(String id) {
        if (id == null) return null;
        rwLock.readLock().lock();
        try {
            return cache.get(id);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Macro getByTrigger(String trigger) {
        if (trigger == null) return null;
        String norm = trigger.trim().toLowerCase();
        rwLock.readLock().lock();
        try {
            for (Macro m : cache.values()) {
                if (m.trigger.equals(norm)) {
                    return m;
                }
            }
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void save(Macro macro) {
        if (macro == null || macro.trigger.isEmpty()) return;
        rwLock.writeLock().lock();
        try {
            // If duplicate trigger exists under another ID, replace or update
            Iterator<Map.Entry<String, Macro>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Macro> entry = it.next();
                if (entry.getValue().trigger.equals(macro.trigger) && !entry.getKey().equals(macro.id)) {
                    it.remove();
                }
            }
            cache.put(macro.id, macro);
            persistLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    public void saveAll(List<Macro> macros) {
        if (macros == null) return;
        rwLock.writeLock().lock();
        try {
            cache.clear();
            for (Macro m : macros) {
                if (m != null && !m.trigger.isEmpty()) {
                    cache.put(m.id, m);
                }
            }
            persistLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    public boolean delete(String id) {
        if (id == null) return false;
        boolean removed;
        rwLock.writeLock().lock();
        try {
            removed = (cache.remove(id) != null);
            if (removed) {
                persistLocked();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    public void deleteAll() {
        rwLock.writeLock().lock();
        try {
            cache.clear();
            persistLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    public void reorder(List<String> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) return;
        rwLock.writeLock().lock();
        try {
            Map<String, Macro> reordered = new LinkedHashMap<>();
            for (String id : orderedIds) {
                Macro m = cache.get(id);
                if (m != null) {
                    reordered.put(id, m);
                }
            }
            // Add any remaining items not in the reorder list
            for (Map.Entry<String, Macro> entry : cache.entrySet()) {
                if (!reordered.containsKey(entry.getKey())) {
                    reordered.put(entry.getKey(), entry.getValue());
                }
            }
            cache.clear();
            cache.putAll(reordered);
            persistLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
    }

    /**
     * Exports all macros as a JSON array string. Macros with sensitive steps (PIN) are left out.
     */
    public String toJsonString() {
        rwLock.readLock().lock();
        try {
            JSONArray arr = new JSONArray();
            for (Macro m : cache.values()) {
                if (m.hasSensitiveSteps()) continue;
                arr.put(m.toJson());
            }
            return arr.toString();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Imports macros from a JSON array string, replacing current macros.
     */
    public boolean importFromJsonString(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false;

        rwLock.writeLock().lock();
        try {
            JSONArray arr = new JSONArray(trimmed);
            Map<String, Macro> newItems = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && !SensitiveSteps.hasSealed(obj) && !SensitiveSteps.hasSensitive(obj)) {
                    Macro m = Macro.fromJson(obj);
                    if (m != null) {
                        newItems.put(m.id, m);
                    }
                }
            }
            // Export leaves PIN macros out, so an import must not delete them.
            Map<String, Macro> keep = new LinkedHashMap<>();
            for (Macro m : cache.values()) if (m.hasSensitiveSteps()) keep.put(m.id, m);
            cache.clear();
            cache.putAll(newItems);
            cache.putAll(keep);
            persistLocked();
        } catch (Throwable t) {
            Log.e(TAG, "Error importing JSON", t);
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyChanged();
        return true;
    }
}
