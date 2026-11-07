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
 * Immutable domain model representing an advanced voice-triggered automation macro.
 * Supports aliases, biometric sensitivity flags, parametric variables, and usage telemetry.
 */
public final class Macro implements Serializable, Comparable<Macro> {
    private static final long serialVersionUID = 2L;

    public final String id;
    public final String trigger;
    public final List<Gesture> gestures;
    public final boolean locked;
    public final long createdAt;
    public final long duration;
    public final List<String> aliases;
    public final boolean sensitive;
    public final String category;
    public final int usageCount;
    public final long lastUsedAt;
    /** Free text: what the macro does, in the owner's words (empty = none). Shown to AI assistants. */
    public final String description;
    /** True once the owner has looked at the sensitive flag in the app; automatic money detection then stays out. */
    public final boolean sensitiveReviewed;

    public Macro(String id, String trigger, List<Gesture> gestures, boolean locked, long createdAt, long duration) {
        this(id, trigger, gestures, locked, createdAt, duration, Collections.emptyList(), false, "General", 0, 0);
    }

    public Macro(String id, String trigger, List<Gesture> gestures, boolean locked, long createdAt, long duration,
                 List<String> aliases, boolean sensitive, String category, int usageCount, long lastUsedAt) {
        this(id, trigger, gestures, locked, createdAt, duration, aliases, sensitive, category, usageCount, lastUsedAt,
                "", false);
    }

    public Macro(String id, String trigger, List<Gesture> gestures, boolean locked, long createdAt, long duration,
                 List<String> aliases, boolean sensitive, String category, int usageCount, long lastUsedAt,
                 String description, boolean sensitiveReviewed) {
        this.description = description != null ? description.trim() : "";
        this.sensitiveReviewed = sensitiveReviewed;
        this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : UUID.randomUUID().toString();
        this.trigger = (trigger != null) ? trigger.trim().toLowerCase() : "";
        this.locked = locked;
        this.createdAt = (createdAt > 0) ? createdAt : System.currentTimeMillis();
        this.category = (category != null && !category.trim().isEmpty()) ? category.trim() : "General";
        this.usageCount = Math.max(0, usageCount);
        this.lastUsedAt = Math.max(0, lastUsedAt);

        if (gestures == null || gestures.isEmpty()) {
            this.gestures = Collections.emptyList();
        } else {
            List<Gesture> copy = new ArrayList<>(gestures.size());
            for (Gesture g : gestures) {
                if (g != null) copy.add(g);
            }
            this.gestures = Collections.unmodifiableList(copy);
        }

        // A macro with a PIN step is always sensitive; it cannot be cleared by withSensitive(false).
        boolean pinStep = false;
        for (Gesture g : this.gestures) if (g.sensitive) { pinStep = true; break; }
        this.sensitive = sensitive || pinStep;

        if (aliases == null || aliases.isEmpty()) {
            this.aliases = Collections.emptyList();
        } else {
            List<String> aCopy = new ArrayList<>(aliases.size());
            for (String a : aliases) {
                if (a != null && !a.trim().isEmpty()) {
                    aCopy.add(a.trim().toLowerCase());
                }
            }
            this.aliases = Collections.unmodifiableList(aCopy);
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

    public List<String> getAliases() {
        return aliases;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public String getCategory() {
        return category;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public long getLastUsedAt() {
        return lastUsedAt;
    }

    public int getGestureCount() {
        return gestures.size();
    }

    /** True when a step holds a secret (PIN). Such a macro is stored encrypted, hidden from AI and from export. */
    public boolean hasSensitiveSteps() {
        for (Gesture g : gestures) if (g.sensitive) return true;
        return false;
    }

    public boolean hasGestures() {
        return !gestures.isEmpty();
    }

    public Macro incrementUsage() {
        return new Macro(id, trigger, gestures, locked, createdAt, duration,
                aliases, sensitive, category, usageCount + 1, System.currentTimeMillis(), description, sensitiveReviewed);
    }

    public Macro withAliases(List<String> newAliases) {
        return new Macro(id, trigger, gestures, locked, createdAt, duration,
                newAliases, sensitive, category, usageCount, lastUsedAt, description, sensitiveReviewed);
    }

    public Macro withGestures(List<Gesture> newGestures) {
        return new Macro(id, trigger, newGestures, locked, createdAt, 0,
                aliases, sensitive, category, usageCount, lastUsedAt, description, sensitiveReviewed);
    }

    public Macro withSensitive(boolean isSensitive) {
        return new Macro(id, trigger, gestures, locked, createdAt, duration,
                aliases, isSensitive, category, usageCount, lastUsedAt, description, sensitiveReviewed);
    }

    public Macro withDescription(String newDescription) {
        return new Macro(id, trigger, gestures, locked, createdAt, duration,
                aliases, sensitive, category, usageCount, lastUsedAt, newDescription, sensitiveReviewed);
    }

    /** Owner or AI edit of the metadata only (never the steps). */
    public Macro withInfo(String newDescription, List<String> newAliases, boolean newSensitive, boolean reviewed) {
        return new Macro(id, trigger, gestures, locked, createdAt, duration,
                newAliases, newSensitive, category, usageCount, lastUsedAt, newDescription, reviewed);
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("trigger", trigger);
            obj.put("locked", locked);
            obj.put("created_at", createdAt);
            obj.put("dur", duration);
            if (sensitive) {
                obj.put("sensitive", true);
            }
            if (!"General".equals(category)) {
                obj.put("cat", category);
            }
            if (usageCount > 0) {
                obj.put("use_cnt", usageCount);
            }
            if (lastUsedAt > 0) {
                obj.put("last_used", lastUsedAt);
            }

            if (!description.isEmpty()) {
                obj.put("desc", description);
            }
            if (sensitiveReviewed) {
                obj.put("sens_rev", true);
            }

            if (aliases != null && !aliases.isEmpty()) {
                JSONArray aArr = new JSONArray();
                for (String a : aliases) {
                    aArr.put(a);
                }
                obj.put("aliases", aArr);
            }

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
        boolean sensitive = obj.optBoolean("sensitive", false);
        String category = obj.optString("cat", "General");
        int usageCount = obj.optInt("use_cnt", 0);
        long lastUsed = obj.optLong("last_used", 0);

        List<String> aliasList = new ArrayList<>();
        JSONArray aArr = obj.optJSONArray("aliases");
        if (aArr != null) {
            for (int i = 0; i < aArr.length(); i++) {
                String a = aArr.optString(i, "").trim().toLowerCase();
                if (!a.isEmpty()) {
                    aliasList.add(a);
                }
            }
        }

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

        return new Macro(id, trigger, gestureList, locked, createdAt, duration, aliasList, sensitive, category,
                usageCount, lastUsed, obj.optString("desc", ""), obj.optBoolean("sens_rev", false));
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
               sensitive == macro.sensitive &&
               usageCount == macro.usageCount &&
               Objects.equals(id, macro.id) &&
               Objects.equals(trigger, macro.trigger) &&
               Objects.equals(gestures, macro.gestures) &&
               sensitiveReviewed == macro.sensitiveReviewed &&
               Objects.equals(description, macro.description) &&
               Objects.equals(aliases, macro.aliases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, trigger, gestures, locked, createdAt, duration, aliases, sensitive, usageCount);
    }

    @Override
    public String toString() {
        return "Macro{" +
                "id='" + id + '\'' +
                ", trigger='" + trigger + '\'' +
                ", gestures=" + gestures.size() +
                ", locked=" + locked +
                ", sensitive=" + sensitive +
                ", usageCount=" + usageCount +
                '}';
    }
}
