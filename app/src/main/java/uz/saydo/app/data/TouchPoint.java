package uz.saydo.app.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable touch coordinate at a normalized screen position and relative timestamp.
 */
public final class TouchPoint implements Serializable {
    private static final long serialVersionUID = 1L;

    public final double x;
    public final double y;
    public final long t;

    public TouchPoint(double x, double y, long t) {
        if (Double.isNaN(x) || Double.isInfinite(x)) x = 0.0;
        if (Double.isNaN(y) || Double.isInfinite(y)) y = 0.0;
        this.x = clamp(x, 0.0, 1.0);
        this.y = clamp(y, 0.0, 1.0);
        this.t = Math.max(0, t);
    }

    private static double clamp(double val, double min, double max) {
        if (val < min) return min;
        if (val > max) return max;
        return val;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("x", Math.round(x * 100000.0) / 100000.0);
            obj.put("y", Math.round(y * 100000.0) / 100000.0);
            obj.put("t", t);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static TouchPoint fromJson(JSONObject obj) {
        if (obj == null) return new TouchPoint(0, 0, 0);
        double x = obj.optDouble("x", 0.0);
        double y = obj.optDouble("y", 0.0);
        long t = obj.optLong("t", 0);
        return new TouchPoint(x, y, t);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TouchPoint)) return false;
        TouchPoint that = (TouchPoint) o;
        return Double.compare(that.x, x) == 0 &&
               Double.compare(that.y, y) == 0 &&
               t == that.t;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, t);
    }

    @Override
    public String toString() {
        return "TouchPoint{" +
                "x=" + x +
                ", y=" + y +
                ", t=" + t +
                '}';
    }
}
