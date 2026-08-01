package com.bluestacks.BstCommandProcessor;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import com.bluestacks.BstCommandProcessor.R;
import com.bluestacks.BstCommandProcessor.UiAccessibilityService;
import com.bluestacks.os.BstHostCallManager;
 
public class GPAutoInstallHelper {
    private static final String TAG = "GPAutoInstallHelper";
    private static Thread currentInstallThread;
    
    // Command codes for sendCommand method
    private static final int COMMAND_INSTALL_SUCCESSFUL = 0;
    private static final int COMMAND_ITEM_NOT_FOUND = 1;
    private static final int COMMAND_ITEM_NOT_AVAILABLE = 2;
    private static final int COMMAND_DEVICE_NOT_COMPATIBLE = 3;
    private static final int COMMAND_ALREADY_INSTALLED = 4;
    private static final int COMMAND_INSTALLING = 5;
    private static final int COMMAND_OTHER_ERRORS = 6;
    private static volatile long lastClickTime = 0;
    public static void doClickInstall(String pkgName) {

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < 2000) {
            Log.d(TAG, "doClickInstall ignored: too frequent (last click at " + lastClickTime + ")");
            sendCommand(COMMAND_OTHER_ERRORS,"other errors");
            return ;
        }
        lastClickTime = currentTime;
        if (currentInstallThread != null && currentInstallThread.isAlive()) {
            currentInstallThread.interrupt();
        }
        currentInstallThread = new Thread(new Runnable() {
            @Override
            public void run() {

                int failedAttempts = 0;
                final int MAX_ATTEMPTS = 4;

                while (failedAttempts < MAX_ATTEMPTS) {
                    try {
                        int sleepTime = 2000;
                        Log.d(TAG, "Sleeping for " + sleepTime + "ms before next attempt");
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Thread wait interrupted: " + e.getMessage());
                        Thread.currentThread().interrupt();
                        break;
                    }
                    Log.d(TAG, "Attempting to click install button, attempt count: " + (failedAttempts + 1));
                    if (clickInstallButton()) {
                        Log.d(TAG, "Execution successful");
                        break;
                    } else {
                        failedAttempts++;
                        Log.d(TAG, "Install button click failed, failure count: " + failedAttempts);
                    }
                }

                if (failedAttempts >= MAX_ATTEMPTS) {
                    Log.d(TAG, "Maximum attempt limit reached (" + MAX_ATTEMPTS + " attempts), stopping attempts to click install button" );
                    sendCommand(COMMAND_OTHER_ERRORS,"other errors");
                }
            }
        });
        currentInstallThread.start();

    }
    static boolean topAppIsGooglePlay() {
        String topPkgName = SystemProperties.get("bst.config.top_package_name", "");
        return topPkgName.equals("com.android.vending");
    }
    public static AccessibilityNodeInfo findNodeInfoByText(String text) {

        UiAccessibilityService uiService = UiAccessibilityService.getInstance();
        if (uiService == null) {
            Log.w(TAG, "UI Accessibility Service instance not found");
            return null;
        }
        AccessibilityNodeInfo rootNode = uiService.getRootInActiveWindow();
        return rootNode != null ? findNodeByText(rootNode, text) : null;
    }
    public static AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo node, String text) {

        if (node == null || text == null) return null;

        try {
            if (node.getText() != null && node.getText().toString().equals(text)) {
                return node;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    AccessibilityNodeInfo result = findNodeByText(child, text);
                    if (result != null) {
                        return result;
                    }
                    child.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e.getMessage());
        }
        return null;
    }
    public static Map<String, String> getButtonRandomX_Y(String txt) {
        Map<String, String> coordinates = null;
        AccessibilityNodeInfo button = findNodeInfoByText(txt);

        if (button != null) {
            coordinates = new HashMap<>();
            Rect rect = new Rect();
            button.getBoundsInScreen(rect);

            int left = rect.left;
            int top = rect.top;
            int right = rect.right;
            int bottom = rect.bottom;

            // Create a Random instance
            Random random = new Random();

            // Generate random coordinates within the rect bounds, guarding against invalid sizes
            int width = right - left;
            int height = bottom - top;
            int X;
            if (width <= 0) {
                // Fallback to left edge if width is zero or negative
                X = left;
            } else {
                X = left + random.nextInt(width + 1);
            }
            int Y;
            if (height <= 0) {
                // Fallback to top edge if height is zero or negative
                Y = top;
            } else {
                Y = top + random.nextInt(height + 1);
            }

            Log.d(TAG, "button : (" + X + ", " + Y + ")");
            coordinates.put("X", String.valueOf(X));
            coordinates.put("Y", String.valueOf(Y));
        }
        return coordinates;
    }

    public static void execCommand(String command) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        String[] cmd = {"/system/xbin/bstk/su", "-c", command};
        Process proc = runtime.exec(cmd);
        BufferedReader bufferedreader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = bufferedreader.readLine()) != null) {
            sb.append(line).append('\n');
        }

        try {
            if (proc.waitFor() != 0) {
                Log.d(TAG, "exit: " + String.valueOf(proc.exitValue()));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "fail : " + e.getMessage());
        }
    }
    public static void clickAtCoordinates(String X, String Y) {
        try {
            execCommand("input tap " + X + " " + Y);
        } catch (IOException e) {
            Log.e(TAG, "error: " + e.getMessage());
        }
    }
    public static void sendCommand(int code ,String desc) {
        Log.d(TAG,"sendCommand code:"+code+" desc:"+desc);
        BstHostCallManager hCallManager = (BstHostCallManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_HOST_CALL);
        hCallManager.commonCommand(9, String.valueOf(code), desc);
    }

    public static  boolean clickInstallButton() {
        Context context = BstCommandProcessorApplication.getInstance().getAppContext();
        if (!topAppIsGooglePlay()) return false;
        String install = context.getResources().getString(R.string.install);

        Map<String, String> coordinates = getButtonRandomX_Y(install);
        if (coordinates != null) {
            clickAtCoordinates(coordinates.get("X"), coordinates.get("Y"));
            sendCommand(COMMAND_INSTALL_SUCCESSFUL,"install successful");
            return true;
        } else {
            String tryAgain = context.getResources().getString(R.string.try_again);
            AccessibilityNodeInfo button = findNodeInfoByText(tryAgain);
            if(button != null) {
                Log.d(TAG, "found try again tip: " + tryAgain);
                sendCommand(COMMAND_ITEM_NOT_FOUND,"Item not found");
                return true;
            }
            String itemNotAvailable = context.getResources().getString(R.string.not_available);
            if(itemNotAvailable != null) {
                AccessibilityNodeInfo itemNotAvailableNode = findNodeInfoByText(itemNotAvailable);
                if(itemNotAvailableNode != null) {
                    Log.d(TAG, "found item not available tip: " + itemNotAvailable);
                    sendCommand(COMMAND_ITEM_NOT_AVAILABLE,"item not available");
                    return true;
                }
            }
            String deviceNotCompatible = context.getResources().getString(R.string.device_not_compatible);
            if(deviceNotCompatible != null) {
                AccessibilityNodeInfo deviceNotCompatibleNode = findNodeInfoByText(deviceNotCompatible);
                if(deviceNotCompatibleNode != null) {
                    Log.d(TAG, "found device not compatible tip: " + deviceNotCompatible);
                    sendCommand(COMMAND_DEVICE_NOT_COMPATIBLE,"device not compatible");
                    return true;
                }
            }
            String uninstall = context.getResources().getString(R.string.uninstall);
            if(uninstall != null) {
                AccessibilityNodeInfo uninstallNode = findNodeInfoByText(uninstall);
                if(uninstallNode != null) {
                    Log.d(TAG, "found uninstall tip: " + uninstall);
                    sendCommand(COMMAND_ALREADY_INSTALLED,"already installed");
                    return true;
                }
            }
            String cancel = context.getResources().getString(R.string.cancel);
            if(cancel != null) {
                AccessibilityNodeInfo cancelNode = findNodeInfoByText(cancel);
                if(cancelNode != null) {
                    Log.d(TAG, "found cancel tip: " + cancel);
                    sendCommand(COMMAND_INSTALLING,"installing");
                    return true;
                }
            }
        }
        
        return false;
    }
}