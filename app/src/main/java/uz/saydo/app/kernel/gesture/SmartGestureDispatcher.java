package uz.saydo.app.kernel.gesture;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedList;
import java.util.Queue;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.kernel.data.NodeAnchor;

/**
 * Self-Healing Replay Resolver & Smart Gesture Dispatcher.
 * Inspects active window hierarchy to locate UI anchors, compensates for dynamic layout shifts,
 * prefers direct accessibility clicks, and injects parametric text.
 */
public final class SmartGestureDispatcher {

    public static class ResolveResult {
        public final boolean handledDirectly;
        public final int adjustedX;
        public final int adjustedY;
        public final boolean foundAnchor;

        public ResolveResult(boolean handledDirectly, int adjustedX, int adjustedY, boolean foundAnchor) {
            this.handledDirectly = handledDirectly;
            this.adjustedX = adjustedX;
            this.adjustedY = adjustedY;
            this.foundAnchor = foundAnchor;
        }

        public static ResolveResult coordinateFallback(int rawX, int rawY) {
            return new ResolveResult(false, rawX, rawY, false);
        }
    }

    private SmartGestureDispatcher() {
    }

    /**
     * Resolves and executes a Gesture against active window hierarchy if an anchor matches.
     */
    public static ResolveResult resolveAndExecute(AccessibilityNodeInfo root, Gesture gesture,
                                                  int screenWidth, int screenHeight,
                                                  String injectedText) {
        int rawX = (int) Math.round(gesture.x * screenWidth);
        int rawY = (int) Math.round(gesture.y * screenHeight);

        if (root == null || gesture.anchor == null) {
            return ResolveResult.coordinateFallback(rawX, rawY);
        }

        AccessibilityNodeInfo matchedNode = findMatchingNode(root, gesture.anchor);
        if (matchedNode == null) {
            return ResolveResult.coordinateFallback(rawX, rawY);
        }

        try {
            Rect bounds = new Rect();
            matchedNode.getBoundsInScreen(bounds);
            int newCenterX = bounds.centerX();
            int newCenterY = bounds.centerY();

            // Text injection step
            if (gesture.type.equals(Gesture.TYPE_TEXT) || (injectedText != null && !injectedText.isEmpty())) {
                String textToSet = (injectedText != null && !injectedText.isEmpty()) ? injectedText : gesture.textToInject;
                if (textToSet != null && !textToSet.isEmpty()) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet);
                    boolean setSuccess = matchedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    if (setSuccess) {
                        return new ResolveResult(true, newCenterX, newCenterY, true);
                    }
                }
            }

            // Direct click execution for 100% reliability
            if (gesture.type.equals(Gesture.TYPE_TAP) && matchedNode.isClickable()) {
                boolean clickSuccess = matchedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (clickSuccess) {
                    return new ResolveResult(true, newCenterX, newCenterY, true);
                }
            }

            // Node moved or exists: provide adjusted coordinates for coordinate replay
            return new ResolveResult(false, newCenterX, newCenterY, true);
        } finally {
            try {
                matchedNode.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Traverses the Accessibility tree via BFS to find the highest-confidence matching node.
     * Invisible subtrees are skipped. Ties are broken by closeness to the recorded position.
     * The parent class is passed down, so no per-node parent lookup (one IPC each) is needed.
     */
    public static AccessibilityNodeInfo findMatchingNode(AccessibilityNodeInfo root, NodeAnchor anchor) {
        if (root == null || anchor == null) return null;

        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> parentHash = new java.util.ArrayDeque<>();
        queue.add(root);
        parentHash.add(0);

        AccessibilityNodeInfo bestNode = null;
        float bestScore = 0.50f; // Minimum acceptance threshold
        final boolean wantChild = !anchor.getChildText().isEmpty();
        Rect b = new Rect();

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo current = queue.poll();
            int pHash = parentHash.poll();
            if (current == null) continue;
            try {
                if (current != root && !current.isVisibleToUser()) continue;
                CharSequence cls = current.getClassName();
                CharSequence txt = current.getText();
                CharSequence desc = current.getContentDescription();
                String id = current.getViewIdResourceName();
                String childText = "";
                if (wantChild && current.isClickable()
                        && (txt == null || txt.length() == 0) && (desc == null || desc.length() == 0)) {
                    childText = firstLabel(current, 3);
                }
                current.getBoundsInScreen(b);
                NodeAnchor cand = new NodeAnchor(id, cls != null ? cls.toString() : "",
                        txt != null ? txt.toString() : "", desc != null ? desc.toString() : "",
                        b.left, b.top, b.right, b.bottom, current.isClickable(), current.isScrollable(),
                        current.isEditable(), pHash, childText);
                float score = anchor.matchScore(cand);
                if (score > 0.5f) score += 0.1f * anchor.proximity(b.left, b.top, b.right, b.bottom);

                if (score > bestScore) {
                    if (bestNode != null && bestNode != root) recycleQuietly(bestNode);
                    bestScore = score;
                    bestNode = AccessibilityNodeInfo.obtain(current);
                    if (score >= 1.098f) break; // exact match within about 50 px of the recorded place
                }

                int childCount = current.getChildCount();
                int myHash = cls != null ? cls.toString().hashCode() : 0;
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = current.getChild(i);
                    if (child != null) {
                        queue.add(child);
                        parentHash.add(myHash);
                    }
                }
            } finally {
                if (current != root && current != bestNode) recycleQuietly(current);
            }
        }
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n != null && n != root) recycleQuietly(n);
        }
        return bestNode;
    }

    private static void recycleQuietly(AccessibilityNodeInfo n) {
        try {
            n.recycle();
        } catch (Throwable ignored) {
        }
    }

    /** First non-empty text or description among the descendants, up to a depth; "" if none. */
    public static String firstLabel(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth <= 0) return "";
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            try {
                CharSequence t = c.getText();
                if (t != null && t.length() > 0) return t.toString();
                CharSequence d = c.getContentDescription();
                if (d != null && d.length() > 0) return d.toString();
                String deeper = firstLabel(c, depth - 1);
                if (!deeper.isEmpty()) return deeper;
            } finally {
                recycleQuietly(c);
            }
        }
        return "";
    }

    /**
     * Extracts a NodeAnchor fingerprint from an active AccessibilityNodeInfo.
     */
    public static NodeAnchor extractAnchor(AccessibilityNodeInfo node) {
        return extractAnchor(node, false);
    }

    /** As above; with withChild a label-less clickable container is also known by its first labelled child. */
    public static NodeAnchor extractAnchor(AccessibilityNodeInfo node, boolean withChild) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        String id = node.getViewIdResourceName();
        CharSequence cls = node.getClassName();
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();

        int ancestryHash = 0;
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            CharSequence pCls = parent.getClassName();
            if (pCls != null) {
                ancestryHash = pCls.toString().hashCode();
            }
            recycleQuietly(parent);
        }
        String childText = "";
        if (withChild && (txt == null || txt.length() == 0) && (desc == null || desc.length() == 0)) {
            childText = firstLabel(node, 3);
        }

        return new NodeAnchor(
                id != null ? id : "",
                cls != null ? cls.toString() : "",
                txt != null ? txt.toString() : "",
                desc != null ? desc.toString() : "",
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                node.isClickable(),
                node.isScrollable(),
                node.isEditable(),
                ancestryHash,
                childText
        );
    }
}
