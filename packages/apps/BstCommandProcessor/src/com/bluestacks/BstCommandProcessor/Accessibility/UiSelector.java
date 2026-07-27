package com.bluestacks.BstCommandProcessor.Accessibility;

import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;


public final class UiSelector {

    private static final String LOG_TAG = "UiSelector";

    private UiSelector() {
    }

    public static class Selector {
        public NodeMatch node_match;
        public String match_policy = "single"; // optional, default "single", possible values:
        // "single", "all", "first"
        public Integer index; // optional, zero-based

        public Selector(NodeMatch node_match, String match_policy, Integer index) {
            this.node_match = node_match;
            this.index = index;
            this.match_policy = match_policy;
        }
    }

    public static class NodeMatch {
        public TextMatch text;
        public TextMatch resource_id;
        public TextMatch content_desc;
        public TextMatch class_name;
        public TextMatch hint_text;
        public TextMatch tooltip_text;
        public Boolean enabled;
        public Boolean clickable;
        public Boolean focusable;
        public Boolean long_clickable;
        public Boolean scrollable;
        public Boolean editable;
        public Boolean checkable;
        public Boolean checked;
        public Boolean selected;
        public Boolean focused;

        public NodeMatch(TextMatch text, TextMatch resource_id, TextMatch content_desc,
                         TextMatch class_name, TextMatch hint_text, TextMatch tooltip_text, Boolean enabled,
                         Boolean clickable, Boolean focusable, Boolean long_clickable, Boolean scrollable,
                         Boolean editable, Boolean checkable, Boolean checked, Boolean selected,
                         Boolean focused) {
            this.text = text;
            this.resource_id = resource_id;
            this.content_desc = content_desc;
            this.class_name = class_name;
            this.hint_text = hint_text;
            this.tooltip_text = tooltip_text;
            this.enabled = enabled;
            this.clickable = clickable;
            this.focusable = focusable;
            this.long_clickable = long_clickable;
            this.scrollable = scrollable;
            this.editable = editable;
            this.checkable = checkable;
            this.checked = checked;
            this.selected = selected;
            this.focused = focused;
        }
    }

    public static class TextMatch {
        public String type; // "exact", "contains", "regex"
        public String value;
        public Boolean case_sensitive = true; // optional, default true

        public boolean matches(CharSequence text) {
            if (text == null)
                return false;

            String t = case_sensitive ? text.toString() : text.toString().toLowerCase();
            String v = case_sensitive ? value : value.toLowerCase();

            switch (type) {
                case "exact":
                    return v.equals(t);
                case "contains":
                    return t.contains(v);
                case "regex":
                    try {
                        return t.matches(v);
                    } catch (RuntimeException e) {
                        Log.d(LOG_TAG, String.format("TextMatch.matches: invalid regex '%s'", v));
                        return false;
                    }
                default:
                    return false;
            }
        }
    }

    private static boolean matches(AccessibilityNodeInfo node, NodeMatch match) {
        if (node == null) {
            return false;
        }
        if (match == null) {
            return true;
        }
        if (!node.isVisibleToUser())
            return false;
        if (match.text != null && !match.text.matches(node.getText()))
            return false;
        if (match.resource_id != null && !match.resource_id.matches(node.getViewIdResourceName()))
            return false;
        if (match.content_desc != null && !match.content_desc.matches(node.getContentDescription()))
            return false;
        if (match.class_name != null && !match.class_name.matches(node.getClassName()))
            return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (match.hint_text != null && !match.hint_text.matches(node.getHintText()))
                return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (match.tooltip_text != null && !match.tooltip_text.matches(node.getTooltipText()))
                return false;
        }
        if (match.enabled != null && match.enabled != node.isEnabled())
            return false;
        if (match.clickable != null && match.clickable != node.isClickable())
            return false;
        if (match.focusable != null && match.focusable != node.isFocusable())
            return false;
        if (match.long_clickable != null && match.long_clickable != node.isLongClickable())
            return false;
        if (match.scrollable != null && match.scrollable != node.isScrollable())
            return false;
        if (match.editable != null && match.editable != node.isEditable())
            return false;
        if (match.checkable != null && match.checkable != node.isCheckable())
            return false;
        if (match.checked != null && match.checked != node.isChecked())
            return false;
        if (match.selected != null && match.selected != node.isSelected())
            return false;
        if (match.focused != null && match.focused != node.isFocused())
            return false;
        return true;
    }

    private static void traverse(AccessibilityNodeInfo node, Selector selector,
                                 List<AccessibilityNodeInfo> out, boolean recycleSelf, AccessibilityNodeInfo ancNode) {
        if (node == null)
            return;

        boolean match = false;
        if (selector == null || matches(node, selector.node_match)) {
            out.add(node);
            if (ancNode != null) {
                Log.d(LOG_TAG, "traverse: removing ancestor node: " + ancNode + ", adding node: " + node);
                out.remove(ancNode);
            }
            match = true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            traverse(node.getChild(i), selector, out, true, match ? node : ancNode);
        }

        if (recycleSelf && !match) {
            node.recycle();
        }
    }

    private static List<AccessibilityNodeInfo> findAll(AccessibilityNodeInfo root,
                                                       Selector selector) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        // Do not recycle the root node; callers own it.
        traverse(root, selector, out, false, null);
        return out;
    }

    private static AccessibilityNodeInfo find(List<AccessibilityNodeInfo> nodes, int index) {
        if (index < 0) {
            throw new RuntimeException("find: invalid index " + index);
        }
        if (index >= nodes.size()) {
            throw new RuntimeException("find: index " + index + " out of bounds size=" + nodes
                    .size());
        }
        return nodes.get(index);
    }

    public static List<AccessibilityNodeInfo> findMatchingNodes(AccessibilityNodeInfo root,
                                                                Selector selector) {
        if (root == null) {
            throw new RuntimeException("findMatchingNodes: root is null");
        }
        if (selector == null) {
            throw new RuntimeException("findMatchingNodes: selector is null");
        }

        List<AccessibilityNodeInfo> matchingNodes = findAll(root, selector);
        if (matchingNodes.isEmpty()) {
            throw new RuntimeException("findMatchingNodes: no matching nodes found");
        }

        String policy = (selector.match_policy == null || selector.match_policy.isEmpty())
                ? "single" : selector.match_policy;
        int index = selector.index == null ? -1 : selector.index;

        List<AccessibilityNodeInfo> resultNodes = new ArrayList<>();

        try {
            switch (policy) {
                case "all":
                    if (index >= 0) {
                        resultNodes.add(find(matchingNodes, index));
                    } else {
                        resultNodes = matchingNodes;
                    }
                    break;
                case "first": {
                    resultNodes.add(matchingNodes.get(0));
                    break;
                }
                case "single": {
                    if (matchingNodes.size() != 1) {
                        throw new RuntimeException(
                                "findMatchingNodes: expected single match but got " + matchingNodes
                                        .size());
                    }
                    resultNodes.add(matchingNodes.get(0));
                    break;
                }
                default:
                    throw new RuntimeException(
                            "findMatchingNodes: Invalid match_policy: " + selector.match_policy);
            }
            return resultNodes;
        } finally {
            for (AccessibilityNodeInfo n : matchingNodes) {
                if (!resultNodes.contains(n)) {
                    n.recycle();
                }
            }
        }
    }
}
