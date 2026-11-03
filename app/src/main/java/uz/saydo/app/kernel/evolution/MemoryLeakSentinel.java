package uz.saydo.app.kernel.evolution;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memory Leak Sentinel & Zero-Leak Verification Engine.
 * Monitored weak-reference registry ensuring zero retained view/context allocations after teardown.
 */
public final class MemoryLeakSentinel {

    private static final MemoryLeakSentinel INSTANCE = new MemoryLeakSentinel();

    public static MemoryLeakSentinel getInstance() {
        return INSTANCE;
    }

    private final Map<String, WeakReference<Object>> watchedObjects = new ConcurrentHashMap<>();
    private int totalTracked = 0;

    private MemoryLeakSentinel() {
    }

    /**
     * Registers an object to monitor for lifecycle deallocation.
     */
    public synchronized void watch(String tag, Object object) {
        if (object == null) return;
        String key = tag + "_" + System.identityHashCode(object);
        watchedObjects.put(key, new WeakReference<>(object));
        totalTracked++;
    }

    /**
     * Executes a GC sweep and returns the number of retained objects.
     */
    public synchronized int runLeakSweep() {
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        System.gc();

        int retained = 0;
        Iterator<Map.Entry<String, WeakReference<Object>>> it = watchedObjects.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WeakReference<Object>> entry = it.next();
            if (entry.getValue().get() == null) {
                it.remove();
            } else {
                retained++;
            }
        }
        return retained;
    }

    /**
     * Computes retained leak ratio [0.0 to 1.0]. Zero indicates 0.00% leak index.
     */
    public synchronized float getLeakIndex() {
        if (totalTracked == 0) return 0.0f;
        int retained = runLeakSweep();
        return (float) retained / (float) totalTracked;
    }

    public synchronized void clear() {
        watchedObjects.clear();
        totalTracked = 0;
    }
}
