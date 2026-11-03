package uz.saydo.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain model representing a voice-triggered automation macro.
 */
public final class Macro implements Serializable, Comparable<Macro> {
    private static final long serialVersionUID = 1L;

    public final String id;
    public final String trigger;
    public final List<Gesture> gestures;
    public final boolean locked;
    public final long createdAt;
    public final long duration;

    public Macro(String id, String trigger, List<Gesture> gestures, boolean locked, long createdAt, long duration) {
        this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : UUID.randomUUID().toString();
        this.trigger = (trigger != null) ? trigger.trim().toLowerCase() : "";
        this.locked = locked;
        this.createdAt = (createdAt > 0) ? createdAt : System.currentTimeMillis();

        if (gestures == null || gestures.isEmpty()) {
            this.gestures = Collections.emptyList();
        } else {
            List<Gesture> copy = new ArrayList<>(gestures.size());
            for (Gesture g : gestures) {
                if (g != null) copy.add(g);
            }
            this.gestures = Collections.unmodifiableList(copy);
        }

        if (duration > 0) {
            this.duration = duration;
        } else {
            long maxEnd = 0;
            for (Gesture g : this.gestures) {
                if (g.endTime > maxEnd) maxEnd = g.endTime;
            }
            this.duration = maxEnd;
        }
    }

    public String getId() {
        return id;
    }

    public String getTrigger() {
        return trigger;
    }

    public List<Gesture> getGestures() {
        return gestures;
    }

    public boolean isLocked() {
        return locked;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getDuration() {
        return duration;
    }

    public int getGestureCount() {
        return gestures.size();
    }

    public boolean hasGestures() {
        return !gestures.isEmpty();
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("trigger", trigger);
            obj.put("locked", locked);
            obj.put("created_at", createdAt);
            obj.put("dur", duration);

            JSONArray gArr = new JSONArray();
            for (Gesture g : gestures) {
                gArr.put(g.toJson());
            }
            obj.put("gestures", gArr);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static Macro fromJson(JSONObject obj) {
        if (obj == null) return null;
        String trigger = obj.optString("trigger", "").trim().toLowerCase();
        if (trigger.isEmpty()) return null;

        String id = obj.optString("id", "");
        if (id.isEmpty()) {
            // Generate stable UUID based on trigger and created_at if possible
            long created = obj.optLong("created_at", 0);
            if (created > 0) {
                id = UUID.nameUUIDFromBytes((trigger + "_" + created).getBytes()).toString();
            } else {
                id = UUID.randomUUID().toString();
            }
        }

        boolean locked = obj.optBoolean("locked", false);
        long createdAt = obj.optLong("created_at", System.currentTimeMillis());
        long duration = obj.optLong("dur", 0);

        List<Gesture> gestureList = new ArrayList<>();
        JSONArray gArr = obj.optJSONArray("gestures");
        if (gArr != null) {
            for (int i = 0; i < gArr.length(); i++) {
                JSONObject gObj = gArr.optJSONObject(i);
                if (gObj != null) {
                    gestureList.add(Gesture.fromJson(gObj));
                }
            }
        }

        return new Macro(id, trigger, gestureList, locked, createdAt, duration);
    }

    @Override
    public int compareTo(Macro other) {
        if (other == null) return 1;
        int timeCmp = Long.compare(this.createdAt, other.createdAt);
        if (timeCmp != 0) return timeCmp;
        return this.trigger.compareTo(other.trigger);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Macro)) return false;
        Macro macro = (Macro) o;
        return locked == macro.locked &&
               createdAt == macro.createdAt &&
               duration == macro.duration &&
               Objects.equals(id, macro.id) &&
               Objects.equals(trigger, macro.trigger) &&
               Objects.equals(gestures, macro.gestures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, trigger, gestures, locked, createdAt, duration);
    }

    @Override
    public String toString() {
        return "Macro{" +
                "id='" + id + '\'' +
                ", trigger='" + trigger + '\'' +
                ", gestures=" + gestures.size() +
                ", locked=" + locked +
                ", createdAt=" + createdAt +
                ", duration=" + duration +
                '}';
    }
}
