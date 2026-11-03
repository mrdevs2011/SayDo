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
     */
    public static AccessibilityNodeInfo findMatchingNode(AccessibilityNodeInfo root, NodeAnchor anchor) {
        if (root == null || anchor == null) return null;

        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.offer(root);

        AccessibilityNodeInfo bestNode = null;
        float bestScore = 0.50f; // Minimum acceptance threshold

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo current = queue.poll();
            if (current == null) continue;

            NodeAnchor currentAnchor = extractAnchor(current);
            float score = anchor.matchScore(currentAnchor);

            if (score > bestScore) {
                if (bestNode != null && bestNode != root) {
                    try {
                        bestNode.recycle();
                    } catch (Throwable ignored) {
                    }
                }
                bestScore = score;
                bestNode = AccessibilityNodeInfo.obtain(current);

                // Early exit on near-perfect match
                if (score >= 0.85f) {
                    break;
                }
            }

            int childCount = current.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = current.getChild(i);
                if (child != null) {
                    queue.offer(child);
                }
            }
        }

        return bestNode;
    }

    /**
     * Extracts a NodeAnchor fingerprint from an active AccessibilityNodeInfo.
     */
    public static NodeAnchor extractAnchor(AccessibilityNodeInfo node) {
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
            try {
                parent.recycle();
            } catch (Throwable ignored) {
            }
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
                ancestryHash
        );
    }
}
