package uz.saydo.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import uz.saydo.app.kernel.data.NodeAnchor;

/**
 * Immutable domain representation of a recorded or simulated screen interaction.
 * Supports self-healing UI node anchor matching and parametric variable injection.
 */
public final class Gesture implements Serializable {
    private static final long serialVersionUID = 2L;

    public static final String TYPE_TAP = "tap";
    public static final String TYPE_HOLD = "hold";
    public static final String TYPE_SWIPE = "swipe";
    public static final String TYPE_GLOBAL = "global";
    public static final String TYPE_TEXT = "text";

    public final String type;
    public final String action;
    public final double x;
    public final double y;
    public final long startTime;
    public final long endTime;
    public final long duration;
    public final List<TouchPoint> points;
    public final NodeAnchor anchor;
    public final String variableSlot;
    public final String textToInject;

    public Gesture(String type, String action, double x, double y,
                   long startTime, long endTime, long duration, List<TouchPoint> points) {
        this(type, action, x, y, startTime, endTime, duration, points, null, "", "");
    }

    public Gesture(String type, String action, double x, double y,
                   long startTime, long endTime, long duration, List<TouchPoint> points,
                   NodeAnchor anchor, String variableSlot, String textToInject) {
        this.type = (type != null && !type.trim().isEmpty()) ? type.trim().toLowerCase() : TYPE_TAP;
        this.action = (action != null) ? action.trim().toLowerCase() : "";
        if (Double.isNaN(x) || Double.isInfinite(x)) x = 0.5;
        if (Double.isNaN(y) || Double.isInfinite(y)) y = 0.5;
        this.x = clamp(x, 0.0, 1.0);
        this.y = clamp(y, 0.0, 1.0);
        this.startTime = Math.max(0, startTime);
        this.endTime = Math.max(this.startTime, endTime);
        long calcDur = this.endTime - this.startTime;
        this.duration = (duration > 0) ? duration : Math.max(1, calcDur);

        if (points == null || points.isEmpty()) {
            this.points = Collections.emptyList();
        } else {
            List<TouchPoint> copy = new ArrayList<>(points.size());
            for (TouchPoint pt : points) {
                if (pt != null) copy.add(pt);
            }
            this.points = Collections.unmodifiableList(copy);
        }
        this.anchor = anchor;
        this.variableSlot = variableSlot != null ? variableSlot : "";
        this.textToInject = textToInject != null ? textToInject : "";
    }

    private static double clamp(double val, double min, double max) {
        if (val < min) return min;
        if (val > max) return max;
        return val;
    }

    public static Gesture createTap(double x, double y, long startTime, long endTime) {
        return createTapWithAnchor(x, y, startTime, endTime, null);
    }

    public static Gesture createTapWithAnchor(double x, double y, long startTime, long endTime, NodeAnchor anchor) {
        List<TouchPoint> pts = new ArrayList<>(1);
        pts.add(new TouchPoint(x, y, startTime));
        return new Gesture(TYPE_TAP, "", x, y, startTime, Math.max(startTime + 20, endTime), 20, pts, anchor, "", "");
    }

    public static Gesture createHold(double x, double y, long startTime, long endTime) {
        return createHoldWithAnchor(x, y, startTime, endTime, null);
    }

    public static Gesture createHoldWithAnchor(double x, double y, long startTime, long endTime, NodeAnchor anchor) {
        List<TouchPoint> pts = new ArrayList<>(1);
        pts.add(new TouchPoint(x, y, startTime));
        long dur = Math.max(380, endTime - startTime);
        return new Gesture(TYPE_HOLD, "", x, y, startTime, startTime + dur, dur, pts, anchor, "", "");
    }

    public static Gesture createSwipe(List<TouchPoint> points, long startTime, long endTime) {
        double startX = (points != null && !points.isEmpty()) ? points.get(0).x : 0.5;
        double startY = (points != null && !points.isEmpty()) ? points.get(0).y : 0.5;
        long dur = Math.max(20, endTime - startTime);
        return new Gesture(TYPE_SWIPE, "", startX, startY, startTime, endTime, dur, points, null, "", "");
    }

    public static Gesture createGlobal(String action, long startTime) {
        return new Gesture(TYPE_GLOBAL, action, 0.5, 0.5, startTime, startTime + 100, 100, Collections.emptyList(), null, "", "");
    }

    public static Gesture createTextInput(String text, String variableSlot, long startTime) {
        return new Gesture(TYPE_TEXT, "", 0.5, 0.5, startTime, startTime + 50, 50, Collections.emptyList(), null, variableSlot, text);
    }

    public Gesture withAnchor(NodeAnchor newAnchor) {
        return new Gesture(type, action, x, y, startTime, endTime, duration, points, newAnchor, variableSlot, textToInject);
    }

    public Gesture withVariableSlot(String slot) {
        return new Gesture(type, action, x, y, startTime, endTime, duration, points, anchor, slot, textToInject);
    }

    public Gesture withText(String text) {
        return new Gesture(type, action, x, y, startTime, endTime, duration, points, anchor, variableSlot, text);
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", type);
            if (!action.isEmpty()) {
                obj.put("action", action);
            }
            obj.put("x", Math.round(x * 100000.0) / 100000.0);
            obj.put("y", Math.round(y * 100000.0) / 100000.0);
            obj.put("st", startTime);
            obj.put("et", endTime);
            obj.put("dur", duration);

            if (points != null && !points.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (TouchPoint p : points) {
                    arr.put(p.toJson());
                }
                obj.put("path", arr);
            }
            if (anchor != null) {
                obj.put("anchor", anchor.toJson());
            }
            if (!variableSlot.isEmpty()) {
                obj.put("var", variableSlot);
            }
            if (!textToInject.isEmpty()) {
                obj.put("txt", textToInject);
            }
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static Gesture fromJson(JSONObject obj) {
        if (obj == null) {
            return createTap(0.5, 0.5, 0, 20);
        }
        String type = obj.optString("type", TYPE_TAP);
        String action = obj.optString("action", "");
        double x = obj.optDouble("x", 0.5);
        double y = obj.optDouble("y", 0.5);
        long st = obj.optLong("st", 0);
        long et = obj.optLong("et", st);
        long dur = obj.optLong("dur", Math.max(0, et - st));

        List<TouchPoint> pointsList = new ArrayList<>();
        JSONArray pathArr = obj.optJSONArray("path");
        if (pathArr != null) {
            for (int i = 0; i < pathArr.length(); i++) {
                JSONObject pObj = pathArr.optJSONObject(i);
                if (pObj != null) {
                    pointsList.add(TouchPoint.fromJson(pObj));
                }
            }
        }

        NodeAnchor anchor = null;
        JSONObject ancObj = obj.optJSONObject("anchor");
        if (ancObj != null) {
            anchor = NodeAnchor.fromJson(ancObj);
        }

        String varSlot = obj.optString("var", "");
        String txt = obj.optString("txt", "");

        return new Gesture(type, action, x, y, st, et, dur, pointsList, anchor, varSlot, txt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Gesture)) return false;
        Gesture gesture = (Gesture) o;
        return Double.compare(gesture.x, x) == 0 &&
               Double.compare(gesture.y, y) == 0 &&
               startTime == gesture.startTime &&
               endTime == gesture.endTime &&
               duration == gesture.duration &&
               Objects.equals(type, gesture.type) &&
               Objects.equals(action, gesture.action) &&
               Objects.equals(points, gesture.points) &&
               Objects.equals(anchor, gesture.anchor) &&
               Objects.equals(variableSlot, gesture.variableSlot) &&
               Objects.equals(textToInject, gesture.textToInject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, action, x, y, startTime, endTime, duration, points, anchor, variableSlot, textToInject);
    }

    @Override
    public String toString() {
        return "Gesture{" +
                "type='" + type + '\'' +
                ", action='" + action + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", duration=" + duration +
                ", points=" + points.size() +
                ", anchor=" + (anchor != null) +
                ", var='" + variableSlot + '\'' +
                '}';
    }
}
