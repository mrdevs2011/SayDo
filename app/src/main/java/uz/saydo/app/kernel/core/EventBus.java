package uz.saydo.app.kernel.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-speed thread-safe internal Event Bus for micro-kernel event dispatching.
 */
public final class EventBus {

    public interface EventListener<T> {
        void onEvent(T event);
    }

    private static final EventBus INSTANCE = new EventBus();

    public static EventBus getDefault() {
        return INSTANCE;
    }

    private final Map<Class<?>, CopyOnWriteArrayList<EventListener<?>>> listeners = new ConcurrentHashMap<>();

    private EventBus() {
    }

    @SuppressWarnings("unchecked")
    public <T> void subscribe(Class<T> eventClass, EventListener<T> listener) {
        if (eventClass == null || listener == null) return;
        CopyOnWriteArrayList<EventListener<?>> list = listeners.get(eventClass);
        if (list == null) {
            list = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<EventListener<?>> existing = listeners.putIfAbsent(eventClass, list);
            if (existing != null) {
                list = existing;
            }
        }
        list.add(listener);
    }

    public <T> void unsubscribe(Class<T> eventClass, EventListener<T> listener) {
        if (eventClass == null || listener == null) return;
        CopyOnWriteArrayList<EventListener<?>> list = listeners.get(eventClass);
        if (list != null) {
            list.remove(listener);
            if (list.isEmpty()) {
                listeners.remove(eventClass, list);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void publish(T event) {
        if (event == null) return;
        Class<?> clazz = event.getClass();
        CopyOnWriteArrayList<EventListener<?>> list = listeners.get(clazz);
        if (list != null && !list.isEmpty()) {
            for (EventListener<?> l : list) {
                try {
                    ((EventListener<T>) l).onEvent(event);
                } catch (Throwable t) {
                    android.util.Log.e("EventBus", "Error delivering event " + clazz.getSimpleName(), t);
                }
            }
        }
    }

    public void clear() {
        listeners.clear();
    }
}
