package uz.saydo.app.kernel.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * Semantic UI-Tree Node Anchor fingerprint for self-healing gesture replay.
 * Captures view ID, class name, text, bounds, and hierarchy ancestry hash.
 */
public final class NodeAnchor {

    private final String viewIdResourceName;
    private final String className;
    private final String text;
    private final String contentDescription;
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;
    private final boolean clickable;
    private final boolean scrollable;
    private final boolean editable;
    private final int ancestryHash;

    public NodeAnchor(String viewIdResourceName,
                      String className,
                      String text,
                      String contentDescription,
                      int left,
                      int top,
                      int right,
                      int bottom,
                      boolean clickable,
                      boolean scrollable,
                      boolean editable,
                      int ancestryHash) {
        this.viewIdResourceName = viewIdResourceName != null ? viewIdResourceName : "";
        this.className = className != null ? className : "";
        this.text = text != null ? text : "";
        this.contentDescription = contentDescription != null ? contentDescription : "";
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.clickable = clickable;
        this.scrollable = scrollable;
        this.editable = editable;
        this.ancestryHash = ancestryHash;
    }

    public String getViewIdResourceName() {
        return viewIdResourceName;
    }

    public String getClassName() {
        return className;
    }

    public String getText() {
        return text;
    }

    public String getContentDescription() {
        return contentDescription;
    }

    public int getLeft() {
        return left;
    }

    public int getTop() {
        return top;
    }

    public int getRight() {
        return right;
    }

    public int getBottom() {
        return bottom;
    }

    public int getCenterX() {
        return (left + right) / 2;
    }

    public int getCenterY() {
        return (top + bottom) / 2;
    }

    public boolean isClickable() {
        return clickable;
    }

    public boolean isScrollable() {
        return scrollable;
    }

    public boolean isEditable() {
        return editable;
    }

    public int getAncestryHash() {
        return ancestryHash;
    }

    /**
     * Calculates semantic similarity confidence score [0.0, 1.0] against another candidate node.
     */
    public float matchScore(NodeAnchor other) {
        if (other == null) return 0.0f;
        float score = 0.0f;

        // Resource ID match is strongest anchor (40 points)
        if (!viewIdResourceName.isEmpty() && viewIdResourceName.equals(other.viewIdResourceName)) {
            score += 0.40f;
        }

        // Text or Content Description match (30 points)
        if (!text.isEmpty() && text.equalsIgnoreCase(other.text)) {
            score += 0.30f;
        } else if (!contentDescription.isEmpty() && contentDescription.equalsIgnoreCase(other.contentDescription)) {
            score += 0.30f;
        }

        // Class Name match (15 points)
        if (!className.isEmpty() && className.equals(other.className)) {
            score += 0.15f;
        }

        // Clickable/Editable attribute match (5 points)
        if (clickable == other.clickable && editable == other.editable) {
            score += 0.05f;
        }

        // Ancestry hierarchy hash match (10 points)
        if (ancestryHash != 0 && ancestryHash == other.ancestryHash) {
            score += 0.10f;
        }

        return score;
    }

    /**
     * True if this anchor can ever be matched: the finder needs a score above 0.5, and an anchor
     * with no id, no text and no description tops out at 0.3 even against itself.
     */
    public boolean canBeMatched() {
        return matchScore(this) > 0.5f;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        if (!viewIdResourceName.isEmpty()) obj.put("id", viewIdResourceName);
        if (!className.isEmpty()) obj.put("cls", className);
        if (!text.isEmpty()) obj.put("txt", text);
        if (!contentDescription.isEmpty()) obj.put("desc", contentDescription);
        obj.put("l", left);
        obj.put("t", top);
        obj.put("r", right);
        obj.put("b", bottom);
        if (clickable) obj.put("clk", true);
        if (scrollable) obj.put("scr", true);
        if (editable) obj.put("edt", true);
        if (ancestryHash != 0) obj.put("anc", ancestryHash);
        return obj;
    }

    public static NodeAnchor fromJson(JSONObject obj) {
        if (obj == null) return null;
        return new NodeAnchor(
                obj.optString("id", ""),
                obj.optString("cls", ""),
                obj.optString("txt", ""),
                obj.optString("desc", ""),
                obj.optInt("l", 0),
                obj.optInt("t", 0),
                obj.optInt("r", 0),
                obj.optInt("b", 0),
                obj.optBoolean("clk", false),
                obj.optBoolean("scr", false),
                obj.optBoolean("edt", false),
                obj.optInt("anc", 0)
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeAnchor)) return false;
        NodeAnchor that = (NodeAnchor) o;
        return left == that.left &&
                top == that.top &&
                right == that.right &&
                bottom == that.bottom &&
                clickable == that.clickable &&
                scrollable == that.scrollable &&
                editable == that.editable &&
                ancestryHash == that.ancestryHash &&
                Objects.equals(viewIdResourceName, that.viewIdResourceName) &&
                Objects.equals(className, that.className) &&
                Objects.equals(text, that.text) &&
                Objects.equals(contentDescription, that.contentDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hash(viewIdResourceName, className, text, contentDescription,
                left, top, right, bottom, clickable, scrollable, editable, ancestryHash);
    }
}
