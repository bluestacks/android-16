package com.bluestacks.BstCommandProcessor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.SystemClock;

public class UiAccessibilityService extends AccessibilityService {

    private static final String LOG_TAG = "UiAccessibilitySvc";
    private static final boolean DEBUG = SystemProperties.getBoolean("bst.debug.ui_dump_svc", false);
    private static final String ACTION_DUMP_UI_HIERARCHY = "com.bluestacks.BstCommandProcessor.accessibility_service.DUMP_UI";
    private static final boolean DBG = android.os.SystemProperties.getInt("bst.debug.bstcmdapp", 0) > 0;

    private static AccessibilityServiceInfo mServiceInfo;
    private BroadcastReceiver dumpReceiver;

    private static UiAccessibilityService mInstance;
    public static volatile long mLastUiEventTime = 0;

    public static UiAccessibilityService getInstance() {
        return mInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(LOG_TAG,"UiAccessibilityService onCreate() called");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        mServiceInfo = getServiceInfo();
        if (mServiceInfo != null) {
            Log.i(LOG_TAG,"Accessibility service connected and ready: %s" + mServiceInfo.toString());
        } else {
            Log.w(LOG_TAG,"Service info is null - configuration might be incorrect");
        }

        mInstance = this;
        // Register broadcast receiver for UI dump requests - for debugging only
        registerDumpReceiver();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        if (accessibilityEvent == null) {
            Log.w(LOG_TAG,"Received null accessibility event");
            return;
        }
        mLastUiEventTime = SystemClock.uptimeMillis();
        if (DEBUG) {
            Log.i(LOG_TAG,"=== EVENT RECEIVED ===");
            Log.i(LOG_TAG,"Event Source: %s" + (accessibilityEvent.getSource() != null ? "Available" : "null"));
            Log.i(LOG_TAG,"Package: %s" + accessibilityEvent.getPackageName());
            Log.i(LOG_TAG,"Accessibility Event: %s" + accessibilityEvent.toString());
        }
    }

    @Override
    public void onInterrupt() {
        Log.i(LOG_TAG,"Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unregister broadcast receiver
        unregisterDumpReceiver();
        Log.i(LOG_TAG,"Accessibility service destroyed");
    }

    /**
     * Registers a broadcast receiver to handle UI dump requests via ADB
     */
    private void registerDumpReceiver() {
        try {
            dumpReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_DUMP_UI_HIERARCHY.equals(intent.getAction())) {
                        Log.i(LOG_TAG,"Received UI dump request via broadcast");
                        getUiDump(true);
                    }
                }
            };

            IntentFilter filter = new IntentFilter(ACTION_DUMP_UI_HIERARCHY);
            // Android 13+ requires explicit RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dumpReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(dumpReceiver, filter);
            }
            Log.i(LOG_TAG,"Broadcast receiver registered for action: %s" + ACTION_DUMP_UI_HIERARCHY);
        } catch (Exception e) {
            Log.e(LOG_TAG,"Failed to register dump receiver: %s" + e.getMessage());
        }
    }

    /**
     * Unregisters the broadcast receiver
     */
    private void unregisterDumpReceiver() {
        try {
            if (dumpReceiver != null) {
                unregisterReceiver(dumpReceiver);
                dumpReceiver = null;
                Log.i(LOG_TAG,"Broadcast receiver unregistered");
            }
        } catch (Exception e) {
            Log.e(LOG_TAG,"Failed to unregister dump receiver: %s" + e.getMessage());
        }
    }

    /**
     * Handles the UI dump request
     */
    public String getUiDump(boolean dumpFile) {
        String lightweightJson = "{}";
        try {
            lightweightJson = dumpLightweightUiHierarchy();

            boolean hasLightweight = lightweightJson.length() > 2; // "{}" is length 2

            if (hasLightweight) {
                if (DEBUG) {
                    Log.i(LOG_TAG,"=== UI HIERARCHY DUMP START ===");

                    // Split large JSON into chunks for logcat (logcat has line length limits)
                    int chunkSize = 3000;
                    String logPrefix = "UI_DUMP_CHUNK_";

                    for (int i = 0; i < lightweightJson.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, lightweightJson.length());
                        String chunk = lightweightJson.substring(i, end);
                        Log.i(LOG_TAG, logPrefix + (i / chunkSize) + ": " + chunk);
                    }

                    Log.i(LOG_TAG,"=== UI HIERARCHY DUMP END ===");
                }

                if (dumpFile) {
                    android.accessibilityservice.AccessibilityServiceInfo serviceInfo = getServiceInfo();
                    if (serviceInfo != null) Log.d(LOG_TAG, "Service: %s" + serviceInfo.toString());
                    String defaultDir = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                    String basePath = defaultDir + "/ui_hierarchy_dump.json";
                    Log.i(LOG_TAG,"Using output path: %s" + basePath);

                    deleteExistingFile(basePath);
                    saveUiDumpToFile(lightweightJson, basePath);
                }
            } else {
                Log.w(LOG_TAG,"UI hierarchy dump failed - no data available from any dump method");
            }

        } catch (Exception e) {
            Log.i(LOG_TAG,e + "Error handling dump request: %s" + e.getMessage());
        }
        return lightweightJson;
    }

    /**
     * Deletes an existing file if it exists
     * @param filePath The path to the file to delete
     */
    private void deleteExistingFile(String filePath) {
        try {
            java.io.File file = new java.io.File(filePath);
            if (file.exists()) {
                boolean deleted = file.delete();
                if (deleted) {
                    Log.i(LOG_TAG,"Deleted existing file: %s" + filePath);
                } else {
                    Log.w(LOG_TAG,"Failed to delete existing file: %s" + filePath);
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG,"Error deleting existing file " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Saves UI dump data to a file
     * @param jsonData The JSON data to save
     * @param filePath The file path to save to
     */
    private void saveUiDumpToFile(String jsonData, String filePath) {
        try {
            java.io.File file = new java.io.File(filePath);
            java.io.File parentDir = file.getParentFile();

            // Create parent directories if they don't exist
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created) {
                    Log.w(LOG_TAG,"Could not create parent directories for: %s" + filePath);
                    return;
                }
            }

            java.io.FileWriter writer = new java.io.FileWriter(file);
            writer.write(jsonData);
            writer.close();

            Log.i(LOG_TAG,"UI dump saved to file: " + filePath + " (size: " + jsonData.length() + " chars)");

        } catch (Exception e) {
            Log.e(LOG_TAG,"Failed to save UI dump to file " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Creates a lightweight, LLM-optimized UI dump with enhanced automation context
     * @return Compact JSON string optimized for LLM processing with complete automation information
     */
    public String dumpLightweightUiHierarchy() {
        try {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) {
                Log.w(LOG_TAG,"Root node is null - cannot dump UI hierarchy");
                return "{}";
            }

            JSONObject uiDump = new JSONObject();

            // Add essential context information for LLM understanding
            if (rootNode.getPackageName() != null) {
                uiDump.put("app", rootNode.getPackageName().toString());
            }

            // Extract only actionable elements
            JSONArray actionableElements = extractActionableElements(rootNode);
            uiDump.put("elements", actionableElements);

            rootNode.recycle();

            String jsonString = uiDump.toString();
            Log.i(LOG_TAG,"Lightweight UI hierarchy dumped successfully, size: " + jsonString.length() + " characters");
            return jsonString;

        } catch (Exception e) {
            Log.e(LOG_TAG,e + "Failed to dump lightweight UI hierarchy: %s" + e.getMessage());
            return "{}";
        }
    }

    /**
     * Extracts only actionable/relevant elements from the UI tree
     * Significantly reduces output size by filtering out non-interactive containers
     * but preserves hierarchical relationships for context
     */
    private JSONArray extractActionableElements(AccessibilityNodeInfo rootNode) {
        JSONArray elements = new JSONArray();
        extractActionableElementsRecursive(rootNode, elements, 0, null);
        return elements;
    }

    /**
     * Recursively extracts actionable elements with enhanced filtering for LLM automation
     */
    private void extractActionableElementsRecursive(AccessibilityNodeInfo node, JSONArray elements, int depth, String parentId) {
        if (node == null) return;

        try {
            boolean isVisible = node.isVisibleToUser();
            Rect nodeBounds = new Rect();
            node.getBoundsInScreen(nodeBounds);

            Rect windowBounds = new Rect();
            AccessibilityWindowInfo window = node.getWindow();
            if (window == null) return;

            // Check intersection with display bounds
            window.getBoundsInScreen(windowBounds);
            boolean isInScreenBounds = Rect.intersects(nodeBounds, windowBounds);

            if (!isVisible || !isInScreenBounds) {
                if (DEBUG) {
                    Log.i(LOG_TAG,"Skipping invisible node at childCount %d, depth %d: Class=%s, node=%s" +
                            node.getChildCount() +
                            depth +
                            node.getClassName() +
                            node.toString() +
                            nodeBounds.toString() +
                            isVisible +
                            isInScreenBounds);
                }
                return;
            }

            boolean isDirectlyActionable = node.isClickable() || node.isLongClickable() ||
                    node.isCheckable() || node.isEditable() || node.isScrollable();

            boolean hasText = (node.getText() != null && !node.getText().toString().trim().isEmpty()) ||
                    (node.getContentDescription() != null && !node.getContentDescription().toString().trim().isEmpty());

            // Include relevant containers (structural or form-related)
            boolean isRelevantContainer = isRelevantContainer(node);

            // Include elements with meaningful resource IDs (exclude generic system IDs)
            boolean hasMeaningfulId = node.getViewIdResourceName() != null &&
                !node.getViewIdResourceName().contains("decor") &&
                !node.getViewIdResourceName().contains("statusBarBackground") &&
                !node.getViewIdResourceName().contains("navigationBarBackground");

            // Include elements with meaningful content descriptions
            boolean hasMeaningfulContentDescription = node.getContentDescription() != null &&
                    !node.getContentDescription().toString().trim().isEmpty();

            // Include elements with important accessibility properties
            boolean hasImportantState = node.isAccessibilityFocused() || node.isDismissable() ||
                    node.isFocused() || node.isSelected() || node.isChecked();

            // Include collection-related info (Lists, Grids, and their Items) - helpful for LLM to understand logical grouping (e.g. "Row 1", "Row 2")
            boolean hasCollectionInfo = node.getCollectionInfo() != null || node.getCollectionItemInfo() != null;

            // Include context menu items and popup windows
            boolean isContextualElement = node.isContextClickable() ||
                (node.getClassName() != null && node.getClassName().toString().contains("PopupWindow"));

            String currentElementId = null;

            boolean shouldInclude = isDirectlyActionable || hasText || isRelevantContainer || hasMeaningfulId ||
                    hasImportantState || hasCollectionInfo || isContextualElement;

            if (DEBUG) {
                Log.d(LOG_TAG,"shouldInclude=%b, childCount=%d, Depth=%d, Class=%s, Text=%s, ResourceName=%s, isDirectlyActionable=%b, hasText=%b, isRelevantContainer=%b, hasMeaningfulId=%b, hasImportantState=%b, hasCollectionInfo=%b, isContextualElement=%b, node=%s" +
                        shouldInclude +
                        node.getChildCount() +
                        depth +
                        node.getClassName() +
                        node.getText() +
                        node.getViewIdResourceName() +
                        isDirectlyActionable +
                        hasText +
                        isRelevantContainer +
                        hasMeaningfulId +
                        hasImportantState +
                        hasCollectionInfo +
                        isContextualElement +
                        node.toString());
            }

            if (shouldInclude) {
                JSONObject element = createLightweightElement(node, depth, parentId);
                if (element.length() > 0) {
                    elements.put(element);
                    currentElementId = element.optString("id");
                }
            } else if (DEBUG) {
                Log.i(LOG_TAG,"Excluding non-actionable node at depth %d: Class=%s, ResourceName=%s, ChildCount=%d, node=%s" +
                        depth +
                        node.getClassName() +
                        node.getViewIdResourceName() +
                        node.getChildCount() +
                        node.toString());
            }

            // Use current element ID as parent, or pass along the existing parent
            String parentForChildren = currentElementId != null ? currentElementId : parentId;

            // Recursively check children
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    extractActionableElementsRecursive(child, elements, depth + 1, parentForChildren);
                    child.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(LOG_TAG,e + "Error processing node at depth %d, childCount=%d, node=%s" + depth + node.getChildCount() + node.toString());
        }
    }

    private static final String[] STRUCTURAL_CONTAINER_HINTS = new String[]{
            "Toolbar",
            "ActionBar",
            "TabLayout",
            "NavigationView",
            "BottomNavigationView",
            "AppBarLayout",
            "CoordinatorLayout",
            "DrawerLayout",
            "ViewPager",
            "ViewPager2",
            "WebView",
            "SlidingPaneLayout",
            "SwipeRefreshLayout",
            "MotionLayout",
            "FragmentContainerView",
            "ComposeView",
            "FloatingActionButton"
    };

    private static final String[] FORM_CONTAINER_HINTS = new String[]{
            "LinearLayout",
            "RelativeLayout",
            "ConstraintLayout",
            "ViewGroup",
            "FrameLayout",
            "GridLayout",
            "TableLayout",
            "CardView",
            "ScrollView",
            "RecyclerView",
            "ListView",
            "GridView",
            "HorizontalScrollView",
            "VerticalScrollView",
            "NestedScrollView",
            "ViewSwitcher",
            "ViewFlipper",
            "AdapterView",
            "AbsListView"
    };

    /**
     * Determines if a node is a relevant container (structural or form-related) that should be included
     */
    private boolean isRelevantContainer(AccessibilityNodeInfo node) {
        if (node == null || node.getClassName() == null) return false;

        String className = node.getClassName().toString();

        for (String containerHint : STRUCTURAL_CONTAINER_HINTS) {
            if (className.contains(containerHint)) {
                return true;
            }
        }

        for (String containerHint : FORM_CONTAINER_HINTS) {
            if (className.contains(containerHint)) {
                return node.getChildCount() > 0;
            }
        }

        return node.isScrollable() && node.getChildCount() > 0;
    }

    /**
     * Creates a lightweight element representation optimized for LLM processing
     * Uses standard JSON patterns that LLMs are trained on for better interpretation
     */
    private JSONObject createLightweightElement(AccessibilityNodeInfo node, int depth, String parentId) {
        JSONObject element = new JSONObject();

        try {
            // Generate unique ID for this element
            String id = "e" + Math.abs(System.identityHashCode(node) % 10000);
            element.put("id", id);

            // Add child count to help LLM understand container complexity
            if (node.getChildCount() > 0) {
                element.put("childCount", node.getChildCount());
            }

            // Add parent relationship only if needed for hierarchy
            if (parentId != null && depth > 0) {
                element.put("parent", parentId);
            }
            if (depth > 0) {
                element.put("depth", depth);
            }

            // Simplify class names to reduce token usage and improve LLM understanding
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            if (!className.isEmpty()) {

                // ---- INPUT ----
                if (className.contains("EditText") || className.contains("AutoCompleteTextView") || className.contains("SearchView"))
                    element.put("type", "input");
                else if (className.contains("TextView") || className.contains("CheckedTextView"))
                    element.put("type", "text");
                else if (className.contains("Button") || className.contains("FloatingActionButton"))
                    element.put("type", "button");
                else if (className.contains("ImageView")) element.put("type", "image");
                else if (className.contains("CheckBox")) element.put("type", "checkbox");
                else if (className.contains("RadioButton")) element.put("type", "radio");
                else if (className.contains("Switch") || className.contains("ToggleButton"))
                    element.put("type", "toggle");
                else if (className.contains("RecyclerView") || className.contains("ListView") || className.contains("GridView") || className.contains("ViewPager"))
                    element.put("type", "list");
                else if (className.contains("ProgressBar")) element.put("type", "progress");
                else if (className.contains("SeekBar")) element.put("type", "slider");
                else if (className.contains("WebView")) element.put("type", "webview");
                else if (className.contains("Dialog") || className.contains("PopupWindow"))
                    element.put("type", "dialog");
                else if (className.contains("ComposeView")) element.put("type", "compose");
                else if (className.contains("Spinner")) element.put("type", "spinner");
                else if (className.contains("Layout") || className.contains("ViewGroup") || className.contains("ScrollView"))
                    element.put("type", "container");
            }

            element.put("className", className);
            if (node.getText() != null && !node.getText().toString().isEmpty()) {
                element.put("text", node.getText().toString());
            }
            if (node.getContentDescription() != null && !node.getContentDescription().toString().isEmpty()) {
                element.put("desc", node.getContentDescription().toString());
            }

            // Resource ID - useful for reliable targeting
            if (node.getViewIdResourceName() != null) {
                element.put("resId", node.getViewIdResourceName());
            }

            // Bounds and center coordinates - essential for clicking
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            // Center coordinates for easy automation targeting
            int centerX = (bounds.left + bounds.right) / 2;
            int centerY = (bounds.top + bounds.bottom) / 2;
            JSONArray centerArray = new JSONArray();
            centerArray.put(centerX);
            centerArray.put(centerY);
            element.put("centerCoords", centerArray);

            // Only include essential interaction properties (omit false/default values to save tokens)
            if (node.isClickable()) element.put("clickable", true);
            if (node.isLongClickable()) element.put("longClickable", true);
            if (node.isScrollable()) element.put("scrollable", true);
            if (node.isEditable()) element.put("editable", true);
            if (node.isCheckable()) element.put("checkable", true);
            if (node.isChecked()) element.put("checked", true);
            if (node.isSelected()) element.put("selected", true);
            if (node.isFocusable()) element.put("focusable", true);
            if (node.isFocused()) element.put("focused", true);
            if (!node.isEnabled()) element.put("disabled", true);
            if (!node.isVisibleToUser()) element.put("hidden", true);
            if (node.isPassword()) element.put("password", true);
            if (node.isDismissable()) element.put("dismissable", true);

            // Input field essentials only
            if (node.isEditable()) {
                // Include hint text for better LLM understanding of input purpose
                String hintText = node.getHintText() != null ? node.getHintText().toString() : "";
                if (!hintText.isEmpty()) element.put("hint", hintText);

                // Input type is crucial for LLM to understand input constraints
                element.put("inputType", node.getInputType());

                // Text selection info helps LLM understand current cursor/selection state
                int selStart = node.getTextSelectionStart();
                int selEnd = node.getTextSelectionEnd();
                if (selStart >= 0 && selEnd >= 0 && (selStart != 0 || selEnd != 0)) {
                    JSONArray selection = new JSONArray();
                    selection.put(selStart);
                    selection.put(selEnd);
                    element.put("selection", selection);
                }

                // Max length helps LLM understand input constraints
                if (node.getMaxTextLength() > 0) {
                    element.put("maxLength", node.getMaxTextLength());
                }

                // Multi-line info affects how LLM should handle text input
                if (node.isMultiLine()) {
                    element.put("multiLine", true);
                }
            }

            // Collection info - essential for navigating lists and grids
            AccessibilityNodeInfo.CollectionItemInfo itemInfo = node.getCollectionItemInfo();
            if (itemInfo != null && (itemInfo.getRowIndex() >= 0 || itemInfo.getColumnIndex() >= 0)) {
                if (itemInfo.getRowIndex() >= 0) element.put("row", itemInfo.getRowIndex());
                if (itemInfo.getColumnIndex() >= 0) element.put("col", itemInfo.getColumnIndex());
                // Only include span info if meaningful
                if (itemInfo.getRowSpan() > 1) element.put("rowSpan", itemInfo.getRowSpan());
                if (itemInfo.getColumnSpan() > 1) element.put("colSpan", itemInfo.getColumnSpan());
                // Selection state for list items
                if (itemInfo.isSelected()) element.put("selected", true);
                // Heading info - crucial for LLM to understand list structure (headers vs items)
                if (itemInfo.isHeading()) element.put("heading", true);
            }

            // Collection context helps LLM understand list/grid structure
            AccessibilityNodeInfo.CollectionInfo collInfo = node.getCollectionInfo();
            if (collInfo != null) {
                if (collInfo.getRowCount() > 0) element.put("totalRows", collInfo.getRowCount());
                if (collInfo.getColumnCount() > 0) element.put("totalCols", collInfo.getColumnCount());
                if (collInfo.isHierarchical()) element.put("hierarchical", true);
            }

            // Range info for sliders/progress - essential for automation
            AccessibilityNodeInfo.RangeInfo rangeInfo = node.getRangeInfo();
            if (rangeInfo != null) {
                JSONObject range = new JSONObject();
                range.put("current", rangeInfo.getCurrent());
                range.put("min", rangeInfo.getMin());
                range.put("max", rangeInfo.getMax());
                // Range type helps LLM understand the control (slider vs progress vs percent)
                int rangeType = rangeInfo.getType();
                if (rangeType == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) {
                    range.put("type", "int");
                } else if (rangeType == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT) {
                    range.put("type", "float");
                } else if (rangeType == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT) {
                    range.put("type", "percent");
                }
                element.put("range", range);
            }

        } catch (JSONException e) {
            Log.e(LOG_TAG, "Error creating lightweight element: %s" + e.getMessage());
        }

        return element;
    }
}
