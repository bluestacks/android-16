package com.bluestacks.BstCommandProcessor.Accessibility;


import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.SecurityException;
import java.util.Locale;

import com.bluestacks.BstCommandProcessor.BstCommandProcessorApplication;

public class InputUtils {
    private static final String TAG = "InputUtils";

    private static final float FAST_FLING_SPEED_THRESHOLD = 1.5f; // pixels per millisecond
    private static final int WAIT_FOR_UI_SETTLE_MS = 1000; // milliseconds
    private static final boolean DBG_COMMANDS = SystemProperties.getInt("bst.debug.commands", 0) > 0;
    private static final String LATIN_IME_ID = "com.android.inputmethod.latin/.LatinIME";
    private static Context context = BstCommandProcessorApplication.getAppContext();
    private static KeyCommandExecutor keyCommandExecutor = BstCommandProcessorApplication.getInstance().getKeyCommandExecutor();
    private static final String BST_SHARED_FOLDER_PATH = "/mnt/windows/BstSharedFolder/";


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

    private static boolean checkIfLatinImePreferred() {
        String imeId = Settings.Secure.getString(context.getContentResolver(),
                               Settings.Secure.DEFAULT_INPUT_METHOD);
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        boolean isLatinIme = LATIN_IME_ID.equals(imeId);

        // Having LatinIME as default IME is enough,
        // locale check can be added in case of requirement in the future.
        // boolean isUsLocale = Locale.US.equals(locale);

        return isLatinIme;
    }


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

    // This function acts as a fallback if LatinIME input processing fails.
    private static boolean setTextViaAdb(String text) {
        // 1. Parameter validation: The text cannot be empty.
        if (text == null || text.isEmpty()) {
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

    // Verify if text is a specific file path and read the file content when matched.
    // File name (e.g. ai_input_<id>.tmp)
    private static boolean isInputTempFilePath(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String name = new File(text).getName();

        return name.startsWith("ai_input_") && name.endsWith(".tmp");
    }

    // Check and make sure the file path is valid
    // File path (e.g. /mnt/windows/BstSharedFolder/ai_input_<id>.tmp)
    private static boolean isValidFilePath(File file) {
        try {
            Log.d(TAG, "isValidFilePath, fileName: " + file.getName());

            String filePath = file.getCanonicalPath();
            String basePath = new File(BST_SHARED_FOLDER_PATH).getCanonicalPath();
            return filePath.startsWith(basePath + File.separator) || filePath.equals(basePath);
        } catch (IOException e) {
            Log.e(TAG, "isValidFilePath: Failed to validate file path", e);
            return false;
        }
    }

    public static boolean setText(String text) {
        String content = text;
        if (isInputTempFilePath(text)) {
            try {
                String fileName = new File(text).getName();
                File file = new File(BST_SHARED_FOLDER_PATH + fileName);

                if (!isValidFilePath(file)) {
                    Log.e(TAG, "setText: Invalid file path");
                    return false;
                }

                if (file.exists() && file.isFile()) {
                    content = new String(
                        java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8
                    );
                    return setText(content, 0, 0, 0);
                } else {
                    Log.e(TAG, "setText: Failed to read content from file: " + file.getName());
                    return false;
                }
            } catch (IOException e) {
                Log.e(TAG, "setText: IOException while reading the file ", e);
                return false;
            } catch (SecurityException e) {
                Log.e(TAG, "setText: SecurityException - access denied to file ", e);
                return false;
            }
        }

        return setText(content, 0, 0, 0);
    }

    public static boolean setText(String text, int delete, int enter, int compose) {
        Log.d(TAG, "setText() called with: text = [" + text + "], delete = [" + delete + "], enter = [" + enter + "], compose = [" + compose + "]");

        if (checkIfLatinImePreferred()) {
            if (keyCommandExecutor != null) {
                boolean writeSucceeded = keyCommandExecutor.writeInput(new StringBuilder(text), delete, enter, compose);
                if (writeSucceeded) {
                    return true;
                }
                Log.d(TAG, "keyCommandExecutor writeInput failed, falling back to adb input text");
            } else {
                Log.d(TAG, "keyCommandExecutor init failed, falling back to adb input text");
            }
        }

        return setTextViaAdb(text);
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

