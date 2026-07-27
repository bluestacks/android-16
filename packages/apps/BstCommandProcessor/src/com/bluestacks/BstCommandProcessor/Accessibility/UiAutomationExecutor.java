package com.bluestacks.BstCommandProcessor.Accessibility;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.bluestacks.BstCommandProcessor.BstCommandProcessorApplication;
import com.bluestacks.BstCommandProcessor.BstCommandProcessorUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import com.bluestacks.BstCommandProcessor.UiAccessibilityService;
import static com.bluestacks.BstCommandProcessor.Accessibility.DeviceSize.getDeviceSize;

public final class UiAutomationExecutor {

    private static final Gson GSON = new Gson();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String LOG_TAG = "UiAutomationExecutor";

    private static UiAccessibilityService mUiAccessibilityServiceInstance;

    private static final long DEFAULT_POLL_INTERVAL_MS = 80;
    private static final long DEFAULT_WAIT_QUIET_MS = 300;
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 2000;
    private static final long DEFAULT_POST_STEP_WAIT_DURATION_MS = 1000;
    private static final long MIN_TOUCHABLE_SIZE_PX = 5;

    private UiAutomationExecutor() {
    }

    // =========================
    // DATA MODELS
    // =========================
    public static class AutomationRequest {
        public String description;
        public List<AutomationStep> steps;
    }

    public static class AutomationStep {
        public String description;

        // open_app, assert_screen, tap, long_press, swipe, set_text,
        // clear_text, if_screen, sleep, for_each_node, read_clipboard, extract,
        // uninstall_app, force_stop_app, clear_app_data
        public String action;
        public AutomationStep node_action;

        public Map<String, String> params;
        public UiSelector.Selector selector;
        public String package_name; // Optional

        public ScreenCheck screen;

        public List<AutomationStep> then;
        public List<AutomationStep> else_steps;

        public WaitPolicy wait_for;
    }

    public static class ScreenCheck {
        public List<UiSelector.Selector> must_exist;
        public List<UiSelector.Selector> must_not_exist;
    }

    public static class WaitPolicy {
        public String mode; // "node_stable", "global_ui_quiet", "none"
        public Long quiet_ms = DEFAULT_WAIT_QUIET_MS;
        public Long timeout_ms = DEFAULT_WAIT_TIMEOUT_MS;
        public Long poll_interval_ms = DEFAULT_POLL_INTERVAL_MS;
        public Long post_step_wait_time_ms = DEFAULT_POST_STEP_WAIT_DURATION_MS;
    }

    public static class AutomationResult {
        public String status; // "SUCCESS" or "FAILED"
        public String failed_step_info;
        public String failure_reason;
        public JsonObject result_json;
    }

    enum UiActionType {
        CLEAR_APP_DATA, CLEAR_TEXT, FORCE_STOP_APP, LONG_PRESS, OPEN_APP, PRESS_KEY, SET_TEXT,
        SWIPE, TAP, UNINSTALL_APP
    }

    // =========================
    // ENTRY POINT
    // =========================
    public static Future<AutomationResult> executeAsync(String json) {
        return EXECUTOR.submit(() -> {
            AutomationResult result = new AutomationResult();

            Log.d(LOG_TAG,"executeAsync: parsing request");

            AutomationRequest request = GSON.fromJson(json, AutomationRequest.class);

            if (request == null || request.steps == null || request.steps.isEmpty()
                    || request.description == null || request.description.trim().isEmpty()) {
                result.status = "FAILED";
                result.failed_step_info = "N/A";
                result.failure_reason = "Invalid or empty request";
                Log.d(LOG_TAG,"executeAsync: invalid or empty request");
                return result;
            }

            JsonObject taskJsonOutput = new JsonObject();
            Log.d(LOG_TAG,String.format("executeAsync: starting task: %s", request.description));
            for (int i = 0; i < request.steps.size(); i++) {
                AutomationStep step = request.steps.get(i);
                Log.d(LOG_TAG,String.format("Step %d/%d: %s (action=%s)", i, request.steps.size(),
                        step.description, step.action));
                try {
                    JsonObject stepResult = executeStep(step);
                    // Merge step results into collected results
                    if (stepResult != null && stepResult.size() > 0) {
                        mergeJsonObjects(taskJsonOutput, stepResult);
                    }
                } catch (Exception e) {
                    result.status = "FAILED";
                    result.failed_step_info = String.format("Step %d/%d: %s (action=%s) failed", i,
                            request.steps.size(), step.description, step.action);
                    result.failure_reason = e.getMessage();
                    Log.e(LOG_TAG,
                            result.failed_step_info + " ; failure_reason: " + result.failure_reason);
                    return result;
                }
            }
            result.status = "SUCCESS";
            result.result_json = taskJsonOutput.size() > 0 ? taskJsonOutput : null;
            Log.d(LOG_TAG,String.format("executeAsync: task %s completed successfully, result: %s",
                    request.description, result.result_json != null ? result.result_json.toString()
                            : "null"));
            return result;
        });
    }

    // =========================
    // STEP EXECUTION
    // =========================

    private static JsonObject executeStep(AutomationStep step) {
        JsonObject jsonStepResponse = new JsonObject();
        List<AccessibilityNodeInfo> targetNodes = new ArrayList<>();

        step.wait_for = prepareWaitPolicy(step.action, step.wait_for);
        long postStepWaitMs = step.wait_for.post_step_wait_time_ms;

        try {
            Log.d(LOG_TAG, String.format("Executing step: %s, using wait policy: %s",
                    step.description, GSON.toJson(step.wait_for)));
            targetNodes = waitAndGetTargetNodes(step);
            if (targetNodes.isEmpty()) {
                throw new IllegalStateException(String.format("No target nodes found for step %s", step.description));
            }

            for (AccessibilityNodeInfo targetNode : targetNodes) {
                Log.d(LOG_TAG, String.format("Executing step action on target node: %s", step.description));
                JsonObject jsonNodeResponse = executeStepAction(targetNode, step);
                if (jsonNodeResponse != null && jsonNodeResponse.size() > 0) {
                    mergeJsonObjects(jsonStepResponse, jsonNodeResponse);
                }
                sleep(postStepWaitMs);
            }
        } finally {
            recycleNodes(targetNodes);
        }
        return jsonStepResponse;
    }

    private static JsonObject executeStepAction(AccessibilityNodeInfo targetNode,
                                                AutomationStep step) {
        switch (step.action) {
            case "assert_screen":
                if (!checkScreen(targetNode, step)) {
                    throw new IllegalStateException("Screen assertion failed");
                }
                Log.d(LOG_TAG, "Screen assertion passed");
                return null;

            case "clear_app_data":
                handleClearAppData(step.params, step.wait_for);
                Log.d(LOG_TAG, "Clear app data action performed");
                return null;

            case "clear_text":
                handleClearText(targetNode, step.wait_for);
                Log.d(LOG_TAG, "Clear text action performed");
                return null;

            case "extract":
                JsonObject extractResult = handleExtract(targetNode, step.params, step.wait_for);
                Log.d(LOG_TAG, "Extract action performed");
                return extractResult;

            case "force_stop_app":
                handleForceStopApp(step.params, step.wait_for);
                Log.d(LOG_TAG, "Force stop app action performed");
                return null;

            case "for_each_node":
                JsonObject forEachNodeResult = handleForEachNode(targetNode, step);
                Log.d(LOG_TAG, "For each node action performed");
                return forEachNodeResult;

            case "if_screen":
                if (checkScreen(targetNode, step)) {
                    Log.d(LOG_TAG, "If screen condition passed");
                    if (step.then != null) {
                        JsonObject thenResults = new JsonObject();
                        for (AutomationStep s : step.then) {
                            JsonObject stepResult = executeStep(s);
                            if (stepResult != null && stepResult.size() > 0) {
                                mergeJsonObjects(thenResults, stepResult);
                            }
                        }
                        Log.d(LOG_TAG, "If screen then steps executed");
                        return thenResults.size() > 0 ? thenResults : null;
                    }
                } else {
                    if (step.else_steps != null) {
                        JsonObject elseResults = new JsonObject();
                        for (AutomationStep s : step.else_steps) {
                            JsonObject stepResult = executeStep(s);
                            if (stepResult != null && stepResult.size() > 0) {
                                mergeJsonObjects(elseResults, stepResult);
                            }
                        }
                        Log.d(LOG_TAG, "If screen else steps executed");
                        return elseResults.size() > 0 ? elseResults : null;
                    }
                }
                return null;

            case "long_press":
                handleLongPress(targetNode, step.params, step.wait_for);
                Log.d(LOG_TAG, "Long press action performed");
                return null;

            case "open_app":
                handleOpenApp(step.params, step.wait_for);
                Log.d(LOG_TAG, "Open app action performed");
                return null;

            case "press_key":
                handlePressKey(step.params, step.wait_for);
                Log.d(LOG_TAG, "Press key action performed");
                return null;

            case "read_clipboard":
                JsonObject clipboardResult = handleReadClipboard(step.params, step.wait_for);
                Log.d(LOG_TAG, "Read clipboard action performed");
                return clipboardResult;

            case "set_text":
                handleSetText(targetNode, step.params, step.wait_for);
                Log.d(LOG_TAG, "Set text action performed");
                return null;

            case "sleep":
                sleep(Long.parseLong(step.params.get("duration_ms")));
                Log.d(LOG_TAG, "Sleep action performed");
                return null;

            case "swipe":
                handleSwipe(step.params, step.wait_for);
                Log.d(LOG_TAG, "Swipe action performed");
                return null;

            case "tap":
                handleTap(targetNode, step.wait_for);
                Log.d(LOG_TAG, "Tap action performed");
                return null;

            case "uninstall_app":
                handleUninstallApp(step.params, step.wait_for);
                Log.d(LOG_TAG, "Uninstall app action performed");
                return null;

            default:
                throw new IllegalArgumentException("Unsupported action: " + step.action);
        }
    }

    // ======================================
    // STEP ACTION HANDLERS
    // =====================================

    private static void handleClearText(AccessibilityNodeInfo targetNode, WaitPolicy waitPolicy) {
        if (targetNode == null) {
            throw new IllegalStateException("handleClearText: node is null");
        }

        if (!targetNode.isEditable()) {
            throw new IllegalStateException("handleClearText: target node not editable");
        }

        if (!waitForActionReadiness(targetNode, waitPolicy)) {
            throw new IllegalStateException("handleClearText: target node not ready for action");
        }

        // Focus first
        if (!sendTapEvent(targetNode, 0)) {
            throw new IllegalStateException("handleClearText: failed to focus target node");
        }

        if (!InputUtils.clearText(targetNode)) {
            throw new IllegalStateException("handleClearText: failed to clear text");
        }

        humanDelay(UiActionType.CLEAR_TEXT);
    }

    private static JsonObject handleForEachNode(AccessibilityNodeInfo root, AutomationStep step) {
        if (root == null) {
            throw new IllegalStateException("handleForEachNode: root node is null");
        }

        if (step.selector == null) {
            throw new IllegalArgumentException("handleForEachNode: selector is null");
        }

        if (step.node_action == null || step.node_action.selector != null) {
            throw new IllegalArgumentException(
                    "handleForEachNode: node_action is null or node_action selector is set to some value");
        }

        if (step.then == null || step.then.isEmpty()) {
            Log.d(LOG_TAG, "handleForEachNode: no then steps provided");
        }

        WaitPolicy waitPolicy = step.wait_for;
        long waitTimeoutMs = waitPolicy.timeout_ms;
        long pollIntervalMs = waitPolicy.poll_interval_ms;
        String exceptionString = "";
        long startTime = SystemClock.uptimeMillis();

        do {
            try {
                if (!waitForActionReadiness(root, waitPolicy)) {
                    throw new IllegalStateException("handleForEachNode: UI not ready for action");
                }

                List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                int maxNodes = step.params != null && step.params.get("max_nodes") != null ? Integer
                        .parseInt(step.params.get("max_nodes")) : 0;

                JsonObject forEachResults = new JsonObject();
                try {
                    nodes = UiSelector.findMatchingNodes(root, step.selector);

                    Log.d(LOG_TAG, String.format("handleForEachNode: found %d matching nodes, maxNodes %d", nodes.size(), maxNodes));
                    maxNodes = (maxNodes > 0 && maxNodes < nodes.size()) ? maxNodes : nodes.size();
                    for (int i = 0; i < maxNodes; i++) {
                        Log.d(LOG_TAG, String.format("handleForEachNode: processing node %d/%d", i + 1, maxNodes));
                        AccessibilityNodeInfo iterationNode = nodes.get(i);
                        // We need to perform the action on this node.
                        AutomationStep nodeActionStep = step.node_action;
                        nodeActionStep.selector = step.selector;
                        nodeActionStep.wait_for = prepareWaitPolicy(nodeActionStep.action, nodeActionStep.wait_for);
                        JsonObject result = executeStepAction(iterationNode, nodeActionStep);
                        if (result != null && result.size() > 0) {
                            mergeJsonObjects(forEachResults, result);
                        }
                        sleep(waitPolicy.post_step_wait_time_ms);

                        // Execute additional steps and collect results
                        for (AutomationStep s : step.then) {
                            JsonObject stepResult = executeStep(s);
                            if (stepResult != null && stepResult.size() > 0) {
                                mergeJsonObjects(forEachResults, stepResult);
                            }
                        }
                    }

                    return forEachResults.size() > 0 ? forEachResults : null;
                } finally {
                    recycleNodes(nodes);
                }
            } catch (Exception e) {
                exceptionString = e.getMessage();
                Log.e(LOG_TAG, String.format("handleForEachNode: exception occurred during execution: %s",
                        exceptionString));
                sleep(pollIntervalMs);
                continue;
            }
        } while (SystemClock.uptimeMillis() - startTime < waitTimeoutMs);

        Log.e(LOG_TAG, String.format("handleForEachNode: timeout after %d ms, returning error %s",
                SystemClock.uptimeMillis() - startTime, exceptionString));
        throw new IllegalStateException("handleForEachNode: " + exceptionString);
    }

    private static void handleOpenApp(Map<String, String> params,
                                      WaitPolicy waitPolicy) {
        if (params == null) {
            throw new IllegalArgumentException("handleOpenApp: open app params are required");
        }
        String type = params.get("type");
        if (type == null) {
            throw new IllegalArgumentException("handleOpenApp: open app type is required");
        }

        if (!waitForActionReadiness(null, waitPolicy)) {
            throw new IllegalStateException("handleOpenApp: UI not ready for action");
        }

        switch (type) {

            case "component": {
                String packageName = params.get("package_name");
                String component = params.get("component");
                if (packageName == null || component == null) {
                    throw new IllegalArgumentException(
                            "handleOpenApp: package_name and component required for launch type component");
                }
                Log.d(LOG_TAG, String.format("handleOpenApp: launching component %s/%s", packageName,
                        component));
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(packageName, component));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // Optional action
                if (params.get("action") != null) {
                    intent.setAction(params.get("action"));
                }


                BstCommandProcessorUtils.startActivity(intent);
                humanDelay(UiActionType.OPEN_APP);
                return;
            }

            case "package": {
                String packageName = params.get("package_name");
                if (packageName == null) {
                    throw new IllegalArgumentException(
                            "handleOpenApp: package_name required for launch type package");
                }
                Log.d(LOG_TAG, String.format("handleOpenApp: launching package %s", packageName));
                if (!BstCommandProcessorUtils.openApp(packageName)) {
                    throw new IllegalArgumentException(
                            "handleOpenApp: failed to launch package: " + packageName);
                }

                humanDelay(UiActionType.OPEN_APP);
                return;
            }

            case "uri": {
                String uri = params.get("uri");
                if (uri == null) {
                    throw new IllegalArgumentException(
                            "handleOpenApp: uri required for launch type uri");
                }

                Log.d(LOG_TAG, String.format("handleOpenApp: launching uri %s", uri));
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (params.get("package_name") != null) {
                    intent.setPackage(params.get("package_name"));
                }

                BstCommandProcessorUtils.startActivity(intent);
                humanDelay(UiActionType.OPEN_APP);
                return;
            }

            default:
                throw new IllegalArgumentException(
                        "handleOpenApp: Unsupported launch type: " + type);
        }
    }

    private static boolean checkScreen(AccessibilityNodeInfo root, AutomationStep step) {
        ScreenCheck screen = step.screen;
        WaitPolicy waitPolicy = step.wait_for;

        if (screen == null)
            return true;

        if ((screen.must_exist == null || screen.must_exist.isEmpty())
                && (screen.must_not_exist == null || screen.must_not_exist.isEmpty())) {
            Log.w(LOG_TAG, "checkScreen: At least one of must_exist or must_not_exist must contain at least one selector");
            return false;

        }

        if (root == null) {
            Log.w(LOG_TAG, "checkScreen: root is null");
            return false;
        }

        long startTime = SystemClock.uptimeMillis();

        do {
            try {
                if (!waitForActionReadiness(root, waitPolicy)) {
                    throw new IllegalStateException("checkScreen: UI not ready for action");
                }

                if (screen.must_exist != null) {
                    for (UiSelector.Selector selector : screen.must_exist) {
                        List<AccessibilityNodeInfo> nodes = UiSelector.findMatchingNodes(root,
                                selector);
                        if (!nodes.isEmpty()) {
                            // nodes found, good
                            recycleNodes(nodes);
                        }
                    }
                }

                if (screen.must_not_exist != null) {
                    for (UiSelector.Selector selector : screen.must_not_exist) {
                        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                        try {
                            nodes = UiSelector.findMatchingNodes(root, selector);
                            if (!nodes.isEmpty()) {
                                throw new IllegalStateException(
                                        "checkScreen: must_not_exist selector found: " + selector);
                            }
                        } catch (Exception ignored) {
                            // nodes not found, good
                        } finally {
                            recycleNodes(nodes);
                        }
                    }
                }
                return true;
            } catch (Exception e) {
                Log.w(LOG_TAG, String.format("checkScreen: Exception during check: %s", e.getMessage()));
                sleep(waitPolicy.poll_interval_ms);
                continue;
            }
        } while (SystemClock.uptimeMillis() - startTime < waitPolicy.timeout_ms);

        Log.w(LOG_TAG, String.format("checkScreen: timeout waiting for screen conditions after %d ms",
                SystemClock.uptimeMillis() - startTime));
        return false;
    }

    private static void handleSwipe(Map<String, String> params, WaitPolicy waitPolicy) {
        if (params == null) {
            throw new IllegalArgumentException("handleSwipe: params cannot be null");
        }
        // Parse percentage inputs
        float fromPctX = Float.parseFloat(params.get("from_pct.x"));
        float fromPctY = Float.parseFloat(params.get("from_pct.y"));
        float toPctX = Float.parseFloat(params.get("to_pct.x"));
        float toPctY = Float.parseFloat(params.get("to_pct.y"));

        if (fromPctX < 0f || fromPctX > 1f || fromPctY < 0f || fromPctY > 1f || toPctX < 0f
                || toPctX > 1f || toPctY < 0f || toPctY > 1f) {
            throw new IllegalArgumentException(
                    "handleSwipe: Swipe percentages must be between 0.0 and 1.0");
        }

        if (!waitForActionReadiness(null, waitPolicy)) {
            throw new IllegalStateException("handleSwipe: UI not ready for action");
        }

        // Screen dimensions (PIXELS)
        DeviceSize deviceSize = getDeviceSize();
        int screenWidth = deviceSize.x;
        int screenHeight = deviceSize.y;

        // Convert to PIXELS
        float startX = fromPctX * screenWidth;
        float startY = fromPctY * screenHeight;
        float endX = toPctX * screenWidth;
        float endY = toPctY * screenHeight;

        long durationMs = Long.parseLong(params.get("duration_ms"));

        float translatedStartX = startX / InputUtils.getTranslationFactorX();
        float translatedStartY = startY / InputUtils.getTranslationFactorY();
        float translatedEndX = endX / InputUtils.getTranslationFactorX();
        float translatedEndY = endY / InputUtils.getTranslationFactorY();
        Log.d(LOG_TAG, String.format("handleSwipe: swiping from (%.1f,%.1f) to (%.1f,%.1f)", startX,
                startY, endX, endY));
        if (!InputUtils.swipe((int)translatedStartX, (int)translatedStartY, (int)translatedEndX, (int)translatedEndY, durationMs)) {
            throw new IllegalStateException("handleSwipe: Swipe action failed");
        }
    }

    private static void handleTap(AccessibilityNodeInfo targetNode, WaitPolicy waitPolicy) {
        if (targetNode == null) {
            throw new IllegalStateException("handleTap: target node is null");
        }

        if (!waitForActionReadiness(targetNode, waitPolicy)) {
            throw new IllegalStateException("handleTap: Node not ready for action");
        }

        if (!sendTapEvent(targetNode, 0)) {
            throw new IllegalStateException("handleTap: Tap action failed");
        }
    }

    private static boolean sendTapEvent(AccessibilityNodeInfo node, int duration) {
        Point p = getHumanLikeClickPoint(node);
        if (p == null) {
            Log.w(LOG_TAG, "sendTapEvent: click point null");
            return false;
        }

        // Translate coordinates
        int translatedX = (int) ((float) p.x / InputUtils.getTranslationFactorX());
        int translatedY = (int) ((float) p.y / InputUtils.getTranslationFactorY());
        Log.d(LOG_TAG, String.format("sendTapEvent: tapping at translated (%d,%d) original (%d,%d)",
                translatedX, translatedY, p.x, p.y));

        // XXX: for more real human-like taps, add slight movement
        if(duration > 0) {
            InputUtils.swipe(translatedX, translatedY, translatedX, translatedY, duration);
        } else
            InputUtils.tap(translatedX, translatedY);
        Log.d(LOG_TAG, String.format("sendTapEvent: tapped at (%d,%d)", translatedX, translatedY));

        humanDelay(UiActionType.TAP);
        return true;
    }

    private static void handleLongPress(AccessibilityNodeInfo targetNode,
                                        Map<String, String> params, WaitPolicy waitPolicy) {
        if (targetNode == null) {
            throw new IllegalStateException("handleLongPress: node is null");
        }

        if (params == null || params.get("duration_ms") == null) {
            throw new IllegalArgumentException("handleLongPress: duration_ms param is required");
        }

        int durationMs = Integer.parseInt(params.get("duration_ms"));

        if (!waitForActionReadiness(targetNode, waitPolicy)) {
            throw new IllegalStateException("handleLongPress: node not ready for action");
        }

        if (!sendTapEvent(targetNode, durationMs)) {
            throw new IllegalStateException("handleLongPress: LongPress action failed");
        }
    }

    public static void handlePressKey(Map<String, String> params, WaitPolicy waitPolicy) {
        if (params == null || params.get("key") == null || params.get("key").isEmpty()) {
            throw new IllegalArgumentException("handlePressKey: 'key' param is required");
        }

        if (!waitForActionReadiness(null, waitPolicy)) {
            throw new IllegalStateException("handlePressKey: UI not ready for action");
        }

        String keyCodeStr = params.get("key");

        int keyCode = 0;
        // volume_up, volume_down, home, back, enter, escape
        switch (keyCodeStr) {
            case "ENTER":
                keyCode = KeyEvent.KEYCODE_ENTER;
                break;
            case "BACK":
                keyCode = KeyEvent.KEYCODE_BACK;
                break;
            case "HOME":
                keyCode = KeyEvent.KEYCODE_HOME;
                break;
            case "VOLUME_UP":
                keyCode = KeyEvent.KEYCODE_VOLUME_UP;
                break;
            case "VOLUME_DOWN":
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
                break;
            case "ESCAPE":
                keyCode = KeyEvent.KEYCODE_ESCAPE;
                break;
            default:
                throw new IllegalArgumentException(
                        "handlePressKey: Invalid key param: " + keyCodeStr);
        }

        if (!InputUtils.pressKey(keyCode)) {
            throw new IllegalStateException("handlePressKey: Failed to press key code " + keyCode);
        }
        humanDelay(UiActionType.PRESS_KEY);
    }

    public static JsonObject handleReadClipboard(Map<String, String> params, WaitPolicy waitPolicy) {
        if (params == null || params.get("store_as") == null || params.get("store_as").isEmpty()) {
            throw new IllegalArgumentException("handleReadClipboard: store_as param is required");
        }

        if (!waitForActionReadiness(null, waitPolicy)) {
            throw new IllegalStateException("handleReadClipboard: UI not ready for action");
        }

        String storeAs = params.get("store_as");
        JsonObject result = new JsonObject();
        String clipboardText = BstCommandProcessorApplication.getInstance().getCommandHandler().getClipboardContent();
        result.addProperty(storeAs, clipboardText != null ? clipboardText : "");
        Log.d(LOG_TAG, "handleReadClipboard: clipboard text read successfully");
        return result;
    }

    public static void handleSetText(AccessibilityNodeInfo targetNode, Map<String, String> params,
                                     WaitPolicy waitPolicy) {
        if (targetNode == null) {
            throw new IllegalStateException("handleSetText: node is null");
        }

        if (!targetNode.isEditable()) {
            throw new IllegalStateException("handleSetText: target node is not editable");
        }

        String text = params.get("text");
        if (text == null) {
            throw new IllegalArgumentException("handleSetText: text parameter is null");
        }

        boolean enter = false; // default: do not press ENTER after setting text
        if (params.get("enter") != null) {
            enter = Boolean.parseBoolean(params.get("enter"));
        }

        boolean clear = true; // default: clear existing text before setting new text
        if (params.get("clear") != null) {
            clear = Boolean.parseBoolean(params.get("clear"));
        }

        Log.d(LOG_TAG, String.format("handleSetText: setting text '%s'", params.get("text")));

        if (!waitForActionReadiness(targetNode, waitPolicy)) {
            throw new IllegalStateException("handleSetText: node is not ready for action");
        }

        // Focus first
        if (!sendTapEvent(targetNode, 0)) {
            throw new IllegalStateException("handleSetText: failed to focus target node");
        }

        if (clear) {
            if (!InputUtils.clearText(targetNode)) {
                throw new IllegalStateException("handleSetText: failed to clear existing text");
            }
            humanDelay(UiActionType.CLEAR_TEXT);
        }

        for (char c : text.toCharArray()) {
            InputUtils.setText(String.valueOf(c));
            humanDelay(UiActionType.SET_TEXT);
        }

        if (enter) {
            if (!InputUtils.pressKey(KeyEvent.KEYCODE_ENTER)) {
                throw new IllegalStateException("handleSetText: failed to send ENTER key");
            }
            humanDelay(UiActionType.PRESS_KEY);
        }

        Log.d(LOG_TAG, String.format("handleSetText: text set: '%s'", text));
    }

    private static JsonObject handleExtract(AccessibilityNodeInfo targetNode,
                                            Map<String, String> params, WaitPolicy waitPolicy) {
        if (targetNode == null) {
            throw new IllegalStateException("handleExtract: node is null");
        }

        if (params == null) {
            throw new IllegalArgumentException("handleExtract: params is null");
        }

        String storeAs = params.get("store_as");
        if (storeAs == null || storeAs.isEmpty()) {
            throw new IllegalArgumentException(
                    "handleExtract: store_as param is required for extract action");
        }

        // Parse comma-separated properties
        String[] properties;
        String propertiesStr = params.get("properties");
        if (propertiesStr == null || propertiesStr.isEmpty() || propertiesStr.equals("*")) {
            // Extract all supported properties
            properties = new String[] { "text", "resource_id", "content_desc", "class_name",
                    "package_name", "enabled", "clickable", "focusable", "focused", "scrollable",
                    "long_clickable", "selected", "checkable", "checked", "editable", "bounds",
                    "hint_text", "tooltip_text", "child_count" };
        } else {
            properties = propertiesStr.split(",");
            for (int i = 0; i < properties.length; i++) {
                properties[i] = properties[i].trim();
            }
        }

        JsonObject nodeData = new JsonObject();

        if (!waitForActionReadiness(targetNode, waitPolicy)) {
            throw new IllegalStateException("handleExtract: target node not ready for action");
        }

        for (String property : properties) {
            JsonElement value = getNodeProperty(targetNode, property);
            if (value != null) {
                nodeData.add(property, value);
            }
        }

        Log.d(LOG_TAG, String.format("handleExtract: extracted %d items", nodeData.size()));

        JsonObject result = new JsonObject();
        result.add(storeAs, nodeData);
        return result;
    }

    private static void handleUninstallApp(Map<String, String> params, WaitPolicy waitPolicy) {
        if (params == null) {
            throw new IllegalArgumentException("handleUninstallApp: params are required");
        }

        String packageName = params.get("package_name");
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("handleUninstallApp: package_name is required");
        }

        if (!waitForActionReadiness(null, waitPolicy)) {
            throw new IllegalStateException("handleUninstallApp: UI not ready for action");
        }

        Log.d(LOG_TAG, String.format("Uninstalling app: %s", packageName));
        if (!BstCommandProcessorApplication.getInstance().getCommandHandler().uninstallApp(packageName)) {
            throw new IllegalStateException(
                    "handleUninstallApp: Failed to uninstall app: " + packageName);
        }
        Log.d(LOG_TAG, String.format("Successfully uninstalled app: %s", packageName));
        humanDelay(UiActionType.UNINSTALL_APP);
    }

    private static void handleForceStopApp(Map<String, String> params, WaitPolicy waitPolicy) {
        if (params == null) {
            throw new IllegalArgumentException("handleForceStopApp: params are required");
        }

        String packageName = params.get("package_name");
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("handleForceStopApp: package_name is required");
        }

        if (!waitForActionReadiness(null, waitPolicy)) {
            throw new IllegalStateException("handleForceStopApp: UI not ready for action");
        }

        Log.d(LOG_TAG, String.format("Stopping app: %s", packageName));

        if (BstCommandProcessorApplication.getInstance().getCommandHandler().stopAppPackage(packageName) != 0) {
            throw new IllegalStateException(
                    "handleForceStopApp: Failed to stop app: " + packageName);
        }

        Log.d(LOG_TAG, String.format("handleForceStopApp: Successfully stopped app: %s", packageName));
        humanDelay(UiActionType.FORCE_STOP_APP);
    }

    private static void handleClearAppData(Map<String, String> params, WaitPolicy waitPolicy) {
        if (params == null) {
            throw new IllegalArgumentException("handleClearAppData: params are required");
        }

        String packageName = params.get("package_name");
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("handleClearAppData: package_name is required");
        }

        if (!waitForActionReadiness(null, waitPolicy)) {
            throw new IllegalStateException("handleClearAppData: UI not ready for action");
        }

        Log.d(LOG_TAG, String.format("Clearing app data: %s", packageName));

        if (!BstCommandProcessorUtils.initiateClearUserData(packageName)) {
            throw new IllegalStateException(
                    "handleClearAppData: Failed to clear app data: " + packageName);
        }

        Log.d(LOG_TAG, String.format("handleClearAppData: Successfully cleared app data: %s", packageName));
        humanDelay(UiActionType.CLEAR_APP_DATA);
    }

    /**
     * Extract a specific property from an AccessibilityNodeInfo
     */
    private static JsonElement getNodeProperty(AccessibilityNodeInfo node, String property) {
        try {
            switch (property.toLowerCase()) {
                case "text":
                    CharSequence text = node.getText();
                    return text != null ? new JsonPrimitive(text.toString()) : new JsonPrimitive(
                            "");

                case "resource_id":
                    String resourceId = node.getViewIdResourceName();
                    return resourceId != null ? new JsonPrimitive(resourceId) : new JsonPrimitive(
                            "");

                case "content_desc":
                    CharSequence contentDesc = node.getContentDescription();
                    return contentDesc != null ? new JsonPrimitive(contentDesc.toString())
                            : new JsonPrimitive("");

                case "class_name":
                    CharSequence className = node.getClassName();
                    return className != null ? new JsonPrimitive(className.toString())
                            : new JsonPrimitive("");

                case "package_name":
                    CharSequence packageName = node.getPackageName();
                    return packageName != null ? new JsonPrimitive(packageName.toString())
                            : new JsonPrimitive("");

                case "enabled":
                    return new JsonPrimitive(node.isEnabled());

                case "clickable":
                    return new JsonPrimitive(node.isClickable());

                case "focusable":
                    return new JsonPrimitive(node.isFocusable());

                case "focused":
                    return new JsonPrimitive(node.isFocused());

                case "scrollable":
                    return new JsonPrimitive(node.isScrollable());

                case "long_clickable":
                    return new JsonPrimitive(node.isLongClickable());

                case "selected":
                    return new JsonPrimitive(node.isSelected());

                case "checkable":
                    return new JsonPrimitive(node.isCheckable());

                case "checked":
                    return new JsonPrimitive(node.isChecked());

                case "editable":
                    return new JsonPrimitive(node.isEditable());

                case "bounds":
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    return new JsonPrimitive(bounds.toShortString());

                case "hint_text":
                    CharSequence hintText = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        hintText = node.getHintText();
                    }
                    return hintText != null ? new JsonPrimitive(hintText.toString())
                            : new JsonPrimitive("");

                case "tooltip_text":
                    CharSequence tooltipText = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        tooltipText = node.getTooltipText();
                    }
                    return tooltipText != null ? new JsonPrimitive(tooltipText.toString())
                            : new JsonPrimitive("");

                case "child_count":
                    return new JsonPrimitive(node.getChildCount());

                default:
                    Log.w(LOG_TAG, String.format("getNodeProperty: unknown property '%s'", property));
                    return null;
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, String.format("getNodeProperty: error extracting property '%s': %s", property, e
                    .getMessage()));
            return null;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================
    // UTILITY METHODS
    // =========================

    private static AccessibilityNodeInfo getCurrentRootNode() {
        if (mUiAccessibilityServiceInstance == null) {
            mUiAccessibilityServiceInstance = UiAccessibilityService.getInstance();
        }
        return mUiAccessibilityServiceInstance.getRootInActiveWindow();
    }

    private static boolean checkNodeStale(AccessibilityNodeInfo node) {
        if (node == null)
            return true;
        AccessibilityNodeInfo current = null;
        try {
            current = getCurrentRootNode();
            return current == null || node.getWindowId() != current.getWindowId();
        } finally {
            recycleNode(current);
        }
    }

    private static boolean areBoundsStable(AccessibilityNodeInfo node, long quietDurationMs,
                                           long pollIntervalMs) {
        if (node == null || checkNodeStale(node)) {
            return false;
        }

        Rect prev = new Rect();
        node.getBoundsInScreen(prev);

        long end = SystemClock.uptimeMillis() + quietDurationMs;

        while (SystemClock.uptimeMillis() < end) {
            sleep(pollIntervalMs);

            if (checkNodeStale(node)) {
                return false;
            }
            Rect curr = new Rect();
            node.getBoundsInScreen(curr);

            if (!curr.equals(prev)) {
                return false;
            }
        }
        return true;
    }

    private static boolean checkNodeBasicValidity(AccessibilityNodeInfo node) {
        if (node == null) {
            Log.d(LOG_TAG, "checkNodeBasicValidity: node is null");
            return false;
        }
        if (checkNodeStale(node)) {
            Log.d(LOG_TAG, "checkNodeBasicValidity: node is stale");
            return false;
        }
        if (!node.isVisibleToUser()) {
            Log.d(LOG_TAG, "checkNodeBasicValidity: node not visible to user");
            return false;
        }
        if (!node.isEnabled()) {
            Log.d(LOG_TAG, "checkNodeBasicValidity: node disabled");
            return false;
        }

        Rect nodeBounds = new Rect();
        node.getBoundsInScreen(nodeBounds);
        if (nodeBounds.width() < MIN_TOUCHABLE_SIZE_PX || nodeBounds.height() < MIN_TOUCHABLE_SIZE_PX) {
            Log.d(LOG_TAG, String.format("checkNodeBasicValidity: node bounds too small %s", nodeBounds.toShortString()));
            return false;
        }

        Rect windowBounds = new Rect();
        // Check intersection with display bounds
        AccessibilityWindowInfo window = node.getWindow();
        if (window == null) {
            Log.d(LOG_TAG, "checkNodeBasicValidity: node window is null");
            return false;
        }
        try {
            window.getBoundsInScreen(windowBounds);
            if (!Rect.intersects(nodeBounds, windowBounds)) {
                Log.d(LOG_TAG, String.format(
                        "checkNodeBasicValidity: node not intersecting window bounds %s vs %s",
                        nodeBounds.toShortString(), windowBounds.toShortString()));
                return false;
            }
            return true;
        } finally {
            window.recycle();
        }
    }

    /**
     * Wait for global UI to be quiet (no window/content events) for quiet duration.
     */
    private static boolean waitForGlobalUiQuiet(long quietDurationMs, long timeoutMs, long pollIntervalMs) {
        long startTime = SystemClock.uptimeMillis();

        do {
            long now = SystemClock.uptimeMillis();
            boolean uiQuiet = now - UiAccessibilityService.mLastUiEventTime >= quietDurationMs;

            if (uiQuiet)
                return true;

            sleep(pollIntervalMs);
        } while (SystemClock.uptimeMillis() - startTime < timeoutMs);

        Log.w(LOG_TAG, String.format("waitForGlobalUiQuiet: timeout after %d ms", SystemClock
                .uptimeMillis() - startTime));
        return false;
    }

    /**
     * Returns the default wait policy for a given action. Different actions have different
     * stability requirements by default.
     */
    private static WaitPolicy prepareWaitPolicy(String action, WaitPolicy waitPolicy) {
        WaitPolicy newPolicy = new WaitPolicy();
        String mode = null;
        Long quiet_ms = null;
        Long timeout_ms = null;
        Long poll_interval_ms = null;
        Long post_step_wait_time_ms = null;

        if (waitPolicy != null) {
            mode = waitPolicy.mode;
            quiet_ms = waitPolicy.quiet_ms;
            timeout_ms = waitPolicy.timeout_ms;
            poll_interval_ms = waitPolicy.poll_interval_ms;
            post_step_wait_time_ms = waitPolicy.post_step_wait_time_ms;
        }

        if (mode != null && !mode.trim().isEmpty() && !mode.equals("global_ui_quiet") && !mode
                .equals("node_stable") && !mode.equals("none")) {
            throw new IllegalArgumentException("Invalid wait policy mode: " + mode);
        }

        if (mode == null || mode.trim().isEmpty()) {
            switch (action) {
                case "assert_screen":
                case "if_screen":
                case "swipe":
                    mode = "global_ui_quiet";
                    break;

                case "clear_text":
                case "extract":
                case "for_each_node":
                case "long_press":
                case "set_text":
                case "tap":
                    mode = "node_stable";
                    break;

                case "clear_app_data":
                case "open_app":
                case "press_key":
                case "read_clipboard":
                case "sleep":
                case "force_stop_app":
                case "uninstall_app":
                    mode = "none";
                    break;

                default:
                    throw new IllegalArgumentException("Unsupported action: " + action);
            }
        }

        newPolicy.mode = mode;
        newPolicy.quiet_ms = quiet_ms != null && quiet_ms >= 0 ? quiet_ms : DEFAULT_WAIT_QUIET_MS;
        newPolicy.timeout_ms = timeout_ms != null && timeout_ms >= 0 ? timeout_ms
                : DEFAULT_WAIT_TIMEOUT_MS;
        newPolicy.poll_interval_ms = poll_interval_ms != null && poll_interval_ms >= 0 ? poll_interval_ms
                : DEFAULT_POLL_INTERVAL_MS;
        newPolicy.post_step_wait_time_ms = post_step_wait_time_ms != null && post_step_wait_time_ms >= 0 ? post_step_wait_time_ms
                : DEFAULT_POST_STEP_WAIT_DURATION_MS;

        return newPolicy;
    }

    // Waits for a step to be ready and returns the target nodes after applying selector.
    private static List<AccessibilityNodeInfo> waitAndGetTargetNodes(AutomationStep step) {
        WaitPolicy waitPolicy = step.wait_for;
        long maxWaitDurationMs = waitPolicy.timeout_ms;
        long pollIntervalMs = waitPolicy.poll_interval_ms;

        List<AccessibilityNodeInfo> targetNodes = new ArrayList<>();
        long startTime = SystemClock.uptimeMillis();

        Log.d(LOG_TAG, String.format("waitAndGetTargetNodes: mode=%s", waitPolicy.mode));

        do {
            AccessibilityNodeInfo rootNode = getCurrentRootNode();
            if (rootNode == null) {
                Log.d(LOG_TAG, "waitAndGetTargetNodes: root node is null");
                sleep(pollIntervalMs);
                continue;
            }

            boolean recycleRootAfterUse = true;
            try {
                if (step.package_name != null) {
                    CharSequence pkg = rootNode.getPackageName();
                    if (pkg == null || !step.package_name.equals(pkg.toString())) {
                        Log.d(LOG_TAG, String.format("package mismatch expected=%s actual=%s",
                                step.package_name, pkg));
                        sleep(pollIntervalMs);
                        continue;
                    }
                }

                // Global UI quiet if requested
                if ("global_ui_quiet".equals(waitPolicy.mode)) {
                    if (!waitForGlobalUiQuiet(waitPolicy.quiet_ms, maxWaitDurationMs, pollIntervalMs))
                        continue; // retry until timeout
                }

                if (step.selector == null || "for_each_node".equals(step.action) || "assert_screen"
                        .equals(step.action)) {
                    // No selector or for_each_node/assert_screen action, return root node
                    targetNodes.add(rootNode);
                    recycleRootAfterUse = false; // do not recycle root yet
                } else {
                    targetNodes = UiSelector.findMatchingNodes(rootNode, step.selector);
                }

                Log.d(LOG_TAG, String.format("waitAndGetTargetNodes: found %d matching nodes", targetNodes
                        .size()));
                // Nodes ready for action
                return targetNodes;
            } catch (Exception e) {
                Log.w(LOG_TAG, String.format("waitAndGetTargetNodes: exception: %s", e.getMessage()));
                sleep(pollIntervalMs);
                continue;
            } finally {
                if (recycleRootAfterUse) {
                    recycleNode(rootNode);
                }
            }

        } while (SystemClock.uptimeMillis() - startTime < maxWaitDurationMs);

        throw new IllegalStateException("waitAndGetTargetNodes: timeout waiting for target nodes");
    }

    private static boolean waitForActionReadiness(AccessibilityNodeInfo targetNode, WaitPolicy waitPolicy) {
        if (waitPolicy == null) {
            Log.d(LOG_TAG, "waitForActionReadiness: no wait policy, returning true");
            return true;
        }

        String mode = waitPolicy.mode;
        long quietDurationMs = waitPolicy.quiet_ms;
        long maxWaitDurationMs = waitPolicy.timeout_ms;
        long pollIntervalMs = waitPolicy.poll_interval_ms;

        if (mode.equals("none")) {
            Log.d(LOG_TAG, "waitForActionReadiness: no wait required, mode is 'none'");
            return true;
        }

        if (targetNode == null) {
            Log.d(LOG_TAG, "waitForActionReadiness: target node is null");
            return false;
        }

        long start = SystemClock.uptimeMillis();

        do {
            if (!checkNodeBasicValidity(targetNode))
                return false;

            boolean boundsStable = areBoundsStable(targetNode, quietDurationMs, pollIntervalMs);

            boolean isQuiet = false;
            switch (mode) {
                case "global_ui_quiet":
                    isQuiet = waitForGlobalUiQuiet(quietDurationMs, maxWaitDurationMs, pollIntervalMs);
                    break;

                case "node_stable":
                    // wait for quiet window after bounds stable and then just check again if node
                    // is still valid or not
                    sleep(quietDurationMs);
                    isQuiet = true;
                    break;

                default:
                    Log.d(LOG_TAG, String.format("waitForActionReadiness: invalid preActionWait mode: %s",
                            mode));
                    return false;
            }
            if (isQuiet && boundsStable && !checkNodeStale(targetNode)) {
                return true;
            }

            sleep(pollIntervalMs);
        } while (SystemClock.uptimeMillis() - start < maxWaitDurationMs);

        Log.w(LOG_TAG,String.format("waitForActionReadiness: timeout after %d ms", (SystemClock
                .uptimeMillis() - start)));
        return false;
    }

    /**
     * Generates a human-like delay.
     */
    private static void humanDelay(UiActionType action) {
        ThreadLocalRandom r = ThreadLocalRandom.current();

        double baseVar; // how inconsistent the user is
        double hesitationP; // probability of a "thinking pause"
        long baseMs; // typical reaction time

        switch (action) {
            case CLEAR_TEXT:
                baseMs = 220;
                baseVar = 0.30;
                hesitationP = 0.10;
                break;
            case LONG_PRESS:
                baseMs = 350;
                baseVar = 0.40;
                hesitationP = 0.12;
                break;
            case CLEAR_APP_DATA:
            case FORCE_STOP_APP:
            case UNINSTALL_APP:
            case OPEN_APP:
                baseMs = 800;
                baseVar = 0.40;
                hesitationP = 0.12;
                break;
            case PRESS_KEY:
                baseMs = 90;
                baseVar = 0.18;
                hesitationP = 0.03;
                break;
            case SET_TEXT:
                baseMs = 220;
                baseVar = 0.35;
                hesitationP = 0.10;
                break;
            case SWIPE:
                baseMs = 180;
                baseVar = 0.28;
                hesitationP = 0.06;
                break;
            case TAP:
                baseMs = 120;
                baseVar = 0.22;
                hesitationP = 0.03;
                break;
            default:
                baseMs = 150;
                baseVar = 0.25;
                hesitationP = 0.05;
        }

        // Small per-call variability drift
        double variability = baseVar + r.nextDouble(baseVar * 0.25);

        // Core delay: log-normal-ish
        long delay = (long) (baseMs * Math.exp(variability * r.nextGaussian()));

        // Occasional hesitation (people pause to think)
        if (r.nextDouble() < hesitationP) {
            delay += r.nextLong(baseMs / 2, baseMs * 2);
        }

        // Clamp to sane bounds
        delay = Math.max(80, Math.min(delay, baseMs * 3));

        sleep(delay);

        return;
    }

    private static Point getHumanLikeClickPoint(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        if (bounds.isEmpty()) {
            return null;
        }

        // Calculate 15% padding
        int paddingX = (int) ((bounds.width()) * 0.15f);
        int paddingY = (int) ((bounds.height()) * 0.15f);

        int safeLeft = bounds.left + paddingX;
        int safeRight = bounds.right - paddingX;
        int safeTop = bounds.top + paddingY;
        int safeBottom = bounds.bottom - paddingY;

        // Fallback if bounds are too small
        if (safeLeft >= safeRight || safeTop >= safeBottom) {
            return new Point(bounds.centerX(), bounds.centerY());
        }

        ThreadLocalRandom r = ThreadLocalRandom.current();

        int x = safeLeft + r.nextInt(safeRight - safeLeft);
        int y = safeTop + r.nextInt(safeBottom - safeTop);

        return new Point(x, y);
    }

    private static void recycleNode(AccessibilityNodeInfo node) {
        if (node != null) {
            try {
                node.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    private static void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null)
            return;
        for (AccessibilityNodeInfo n : nodes) {
            recycleNode(n);
        }
    }

    /**
     * Merge source JsonObject into target JsonObject. If a key already exists, convert it to an
     * array and append the new value.
     */
    private static void mergeJsonObjects(JsonObject target, JsonObject source) {
        for (String key : source.keySet()) {
            JsonElement newValue = source.get(key);

            if (target.has(key)) {
                // Key already exists - handle as array
                JsonElement existingValue = target.get(key);

                if (existingValue.isJsonArray()) {
                    // Already an array, check if new value is also an array
                    if (newValue.isJsonArray()) {
                        // Append all elements from new array
                        for (JsonElement element : newValue.getAsJsonArray()) {
                            existingValue.getAsJsonArray().add(element);
                        }
                    } else {
                        // Just add the new value
                        existingValue.getAsJsonArray().add(newValue);
                    }
                } else {
                    // Convert to array with both old and new values
                    JsonArray array = new JsonArray();
                    array.add(existingValue);

                    if (newValue.isJsonArray()) {
                        // Add all elements from new array
                        for (JsonElement element : newValue.getAsJsonArray()) {
                            array.add(element);
                        }
                    } else {
                        array.add(newValue);
                    }

                    target.add(key, array);
                }

                Log.d(LOG_TAG, String.format("mergeJsonObjects: key '%s' repeated, stored as array", key));
            } else {
                // New key, just add it
                target.add(key, newValue);
            }
        }
    }

}
