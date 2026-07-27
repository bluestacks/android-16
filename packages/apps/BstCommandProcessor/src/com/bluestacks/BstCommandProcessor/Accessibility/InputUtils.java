package com.bluestacks.BstCommandProcessor.Accessibility;


import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
public class InputUtils {
    private static final String TAG = "InputUtils";


    private static final float FAST_FLING_SPEED_THRESHOLD = 1.5f; // pixels per millisecond
    private static final int WAIT_FOR_UI_SETTLE_MS = 1000; // milliseconds


    public static float getTranslationFactorX() {
        return translationFactorX;
    }

    public static void setTranslationFactorX(float translationFactorX) {
        InputUtils.translationFactorX = translationFactorX;
    }

    public static float getTranslationFactorY() {
        return translationFactorY;
    }

    public static void setTranslationFactorY(float translationFactorY) {
        InputUtils.translationFactorY = translationFactorY;
    }

    private static float translationFactorX = 1f;


    private static float translationFactorY = 1f;


    public static void execCommand(String command) throws IOException {
        StringBuilder sb = new StringBuilder();
        Runtime runtime = Runtime.getRuntime();
        String[] cmd = {"/system/xbin/bstk/su", "-c", command};
        Process proc = runtime.exec(cmd);

        //Use try-with-resources to automatically close the stream and prevent leaks
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }

        try {
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new IOException("Command failed with exit code: " + exitCode + " for command: " + command);
            }
        } catch (InterruptedException e) {
            // Restore interrupted status
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted", e);
        } finally {
            // Release process resources
            proc.destroy();
        }
    }

    public static boolean pressKey(int keyCode) {

        try {
            Instrumentation inst = new Instrumentation();
            inst.sendKeyDownUpSync(keyCode);

            return true;
        } catch (Exception e) {
            Log.e(TAG, String.format("Error injecting key: %s", keyCode));
            return false;
        }
    }

    public static boolean setText(String text) {
        // 1. Parameter validation: The text cannot be empty.
        if (text == null) {
            Log.w(TAG, "setText failed: text is null");
            return false;
        }

        // 2. Handle special chars.
        String processedText = text.replace(" ", "%s")    // handle space
                                   .replace("\"", "\\\"") // handle "
                                   .replace("$", "\\$");  // handle $
        String command = "input text \"" + processedText + "\"";

        Log.d(TAG, "setText() called with: text = [" + command + "]");

        try {
            execCommand(command);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error performing setText with: " + text, e);
            return false;
        }
    }

    public static boolean clearText(AccessibilityNodeInfo view) {
        Log.d(TAG,"clearText() called");
        if (view == null) {
            Log.d(TAG, "View is null");
            return false;
        }

        if (!view.isEditable()) {
            Log.d(TAG, "View is not editable");
            return false;
        }

        try {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            boolean success = view.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Clear text exception: " + e.getMessage(), e);
            return false;
        }
    }

    public static float randomInRange(float min, float max) {
        return min + (float) Math.random() * (max - min);
    }

    // Static utility methods
    public static boolean tap(int x, int y) {
        if (x < 0 || y < 0) {
            Log.w(TAG, "Invalid tap coordinates: (" + x + ", " + y + "). Coordinates must be non-negative.");
            return false;
        }

        try {
            execCommand("input tap " + x + " " + y);
        } catch (Exception e) {
            Log.e(TAG, "Error performing tap at (" + x + "," + y + "): " + e.getMessage());
            return false;
        }

        return true;
    }

    public static boolean swipe(int startX, int startY, int endX, int endY, long duration) {
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            Log.w(TAG, "Invalid swipe coordinates. All coordinates must be non-negative.");
            return false;
        }

        try {
            execCommand("input swipe " + startX + " " + startY + " " + endX + " " + endY + " " + duration);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error performing swipe from (" + startX + "," + startY + ") to (" + endX + "," + endY + ")");
            return false;
        }
    }

    public static boolean deleteText(int count, int delay) {
        Log.d(TAG,"deleteText() called with: count = [" + count + "], delay = [" + delay + "]");
        try {
            // Send DEL/backspace key events for the specified count
            for (int i = 0; i < count; i++) {
                // Send DEL key down and key up events
                pressKey(KeyEvent.KEYCODE_DEL);

                // Add delay between keystrokes if specified
                if (delay > 0 && i < count - 1) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Error deleting text: " + e.getMessage());
            return false;
        }
    }
}

