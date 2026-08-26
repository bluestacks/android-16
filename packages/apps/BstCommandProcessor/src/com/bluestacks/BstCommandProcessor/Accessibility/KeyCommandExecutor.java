package com.bluestacks.BstCommandProcessor.Accessibility;

import static android.view.KeyEvent.KEYCODE_C;
import static android.view.KeyEvent.KEYCODE_COPY;
import static android.view.KeyEvent.KEYCODE_CUT;
import static android.view.KeyEvent.KEYCODE_PASTE;
import static android.view.KeyEvent.KEYCODE_V;
import static android.view.KeyEvent.KEYCODE_X;
import static android.view.KeyEvent.META_ALT_ON;
import static android.view.KeyEvent.META_CAPS_LOCK_ON;
import static android.view.KeyEvent.META_CTRL_ON;
import static android.view.KeyEvent.META_META_ON;
import static android.view.KeyEvent.META_NUM_LOCK_ON;
import static android.view.KeyEvent.META_SCROLL_LOCK_ON;
import static android.view.KeyEvent.META_SHIFT_ON;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.util.Log;

import com.google.gson.JsonObject;

import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.bluestacks.BstCommandProcessor.BstCommandProcessorApplication;

public class KeyCommandExecutor {
    // <opcode><type><data> / <opcode><data>
    // TYPE_KEYCODE -> <javascriptKeyCode><metaState>
    // TYPE_UNICODE -> 2-byte unicode (UTF-16)
    // OP_IME - <dl> <en> <cp> <var_length array>
    public static final int OP_KEY_DOWN = 101;
    public static final int OP_KEY_UP = 102;
    public static final int OP_IME = 103;
    public static final int OP_IME_CLEAR_TEXT = 104;

    private static final int TYPE_KEYCODE = 1;
    private static final int TYPE_UNICODE = 2;

    private static final int META_SHIFT = 1;
    private static final int META_ALT = 2;
    private static final int META_CTRL = 4;
    private static final int META_META = 8;
    private static final int META_CAPS_LOCK = 16;
    private static final int META_SCROLL_LOCK = 32;
    private static final int META_NUM_LOCK = 64;
    private static final int MAX_RETRIES = 10;
    private static final long SLEEP_MILLIS = 500;
    private static final String TAG = "KeyCommandExecutor";
    //private static KeyCommandExecutor keyCommandExecutor;
    private static int[] keyMap;
    Instrumentation mInstrumentation;
    //private SessionOrchestrator mSessionOrchestrator;
    private final boolean DEBUG_COMMAND;
    private Socket mImeSocket = null;
    private DataOutputStream mImeDataOutputStream = null;
    private DataInputStream mImeDataInputStream = null;
    Map<Integer, Integer> ctrlMetaStateMapping;
    private final Object mImeSocketLock = new Object();

    private int mImeListenerPort = SystemProperties.getInt("ro.bst.ime_listener_port", -1);

    private static final String mBstLatinImePackage = "com.android.inputmethod.latin";

    public static String getCurrentInputMethod(Context context) {
        if (context == null) {
            Exception e = new Exception("Context is null in getCurrentInputMethod()");
            Log.d(TAG, e.getMessage(), e);
            return null;
        }

        String currentInputMethodId = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                currentInputMethodId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                if (currentInputMethodId != null && currentInputMethodId.contains("/")) {
                    return currentInputMethodId.split("/")[0];
                }
            } catch (Exception e) {
                Log.e(TAG, "getCurrentInputMethod exception: " + e.getMessage());
            }

            try {
                Thread.sleep(SLEEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Recover the interrupted state.
                Log.e(TAG, "Thread exception : " + e.getMessage());
                break;
            }
        }

        Exception e = new Exception("Failed to read current inputmethod from settings");
        Log.d(TAG, e.getMessage(), e);
        return null;
    }

    private boolean connectToImeServer() {
        try {
            closeImeSocket();
            mImeSocket = new Socket("localhost", mImeListenerPort);
            if (mImeSocket == null) {
                Exception e = new Exception("failed to connect socket with port = " + mImeListenerPort);
                Log.d(TAG, e.getMessage(), e);
                return false;
            }

            Log.d(TAG, "socket is : " + mImeSocket);
            try {
                mImeSocket.setSoTimeout(5000);
            } catch (Exception e) {
                Exception exception = new Exception("Failed to set socket timeout", e);
		Log.d(TAG, e.getMessage(), e);
                return false;
            }
            mImeDataOutputStream = new DataOutputStream(mImeSocket.getOutputStream());
            mImeDataInputStream = new DataInputStream(mImeSocket.getInputStream());
            if (mImeDataOutputStream == null || mImeDataInputStream == null) {
                Exception e = new Exception("failed to get ime data stream");
                Log.d(TAG, e.getMessage(), e);
                return false;
            }
        } catch (Exception ex) {
            Exception exception = new Exception("exception in setting socket : " + ex.getMessage());
            Log.d(TAG, exception.getMessage(), exception);
            if (DEBUG_COMMAND) ex.printStackTrace();

            try {
                if (mImeSocket != null) {
                    mImeSocket.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in closing : " + e.getMessage());
                if (DEBUG_COMMAND) e.getStackTrace();
            }
            return false;
        }
        return true;
    }

    public KeyCommandExecutor(Instrumentation instrumentation, boolean debug) {
        DEBUG_COMMAND = debug || SystemProperties.getBoolean("bst.debug.host_native_events", false);
        mInstrumentation = instrumentation;

        if (DEBUG_COMMAND) Log.d(TAG, "Connecting to port:" + mImeListenerPort);

        new Thread(() -> {
            String result = null;
            try {
                result = getCurrentInputMethod(BstCommandProcessorApplication.getInstance().getAppContext());
            } catch (Exception ex) {
                Log.e(TAG, "getCurrentInputMethod execution failed");
            }

            if (result != null && mBstLatinImePackage.equals(result)) {
                if (!connectToImeServer()) {
                    Log.e(TAG, "KeyCommandExecutor() - Failed to connect to server");
                }
            }
        }).start();

        keyMap = new int[256];
        keyMap[8] = 67;
        keyMap[9] = 61;
        keyMap[13] = 66;
        keyMap[16] = 59;
        keyMap[17] = 113;
        keyMap[18] = 57;
        keyMap[19] = 121;
        keyMap[20] = 115;
        keyMap[27] = 111;
        keyMap[32] = 62;
        keyMap[33] = 92;
        keyMap[34] = 93;
        keyMap[35] = 123;
        keyMap[36] = 3;
        keyMap[37] = 21;
        keyMap[38] = 19;
        keyMap[39] = 22;
        keyMap[40] = 20;
        keyMap[44] = 120;
        keyMap[45] = 124;
        keyMap[46] = 112;
        keyMap[48] = 7;
        keyMap[49] = 8;
        keyMap[50] = 9;
        keyMap[51] = 10;
        keyMap[52] = 11;
        keyMap[53] = 12;
        keyMap[54] = 13;
        keyMap[55] = 14;
        keyMap[56] = 15;
        keyMap[57] = 16;
        keyMap[65] = 29;
        keyMap[66] = 30;
        keyMap[67] = 31;
        keyMap[68] = 32;
        keyMap[69] = 33;
        keyMap[70] = 34;
        keyMap[71] = 35;
        keyMap[72] = 36;
        keyMap[73] = 37;
        keyMap[74] = 38;
        keyMap[75] = 39;
        keyMap[76] = 40;
        keyMap[77] = 41;
        keyMap[78] = 42;
        keyMap[79] = 43;
        keyMap[80] = 44;
        keyMap[81] = 45;
        keyMap[82] = 46;
        keyMap[83] = 47;
        keyMap[84] = 48;
        keyMap[85] = 49;
        keyMap[86] = 50;
        keyMap[87] = 51;
        keyMap[88] = 52;
        keyMap[89] = 53;
        keyMap[90] = 54;
        keyMap[91] = 171;
        keyMap[92] = 171;
        keyMap[93] = 109;
        keyMap[96] = 144;
        keyMap[97] = 145;
        keyMap[98] = 146;
        keyMap[99] = 147;
        keyMap[100] = 148;
        keyMap[101] = 149;
        keyMap[102] = 150;
        keyMap[103] = 151;
        keyMap[104] = 152;
        keyMap[105] = 153;
        keyMap[107] = 157;
        keyMap[109] = 156;
        keyMap[110] = 158;
        keyMap[111] = 154;
        keyMap[112] = 131;
        keyMap[113] = 132;
        keyMap[114] = 133;
        keyMap[115] = 134;
        keyMap[116] = 135;
        keyMap[117] = 136;
        keyMap[118] = 137;
        keyMap[119] = 138;
        keyMap[120] = 139;
        keyMap[121] = 140;
        keyMap[122] = 141;
        keyMap[123] = 142;
        keyMap[144] = 143;
        keyMap[145] = 116;
        keyMap[182] = 209;
        keyMap[183] = 210;
        keyMap[186] = 74;
        keyMap[187] = 70;
        keyMap[188] = 55;
        keyMap[189] = 69;
        keyMap[190] = 56;
        keyMap[191] = 76;
        keyMap[192] = 68;
        keyMap[219] = 71;
        keyMap[220] = 73;
        keyMap[221] = 72;
        keyMap[222] = 75;

        ctrlMetaStateMapping = new HashMap<>();
        ctrlMetaStateMapping.put(KEYCODE_C, KEYCODE_COPY);
        ctrlMetaStateMapping.put(KEYCODE_X, KEYCODE_CUT);
        ctrlMetaStateMapping.put(KEYCODE_V, KEYCODE_PASTE);
    }

    private int getMetaState(int metaData) {
        int metaState = 0;

        if ((metaData & META_SHIFT) > 0)
            metaState |= META_SHIFT_ON;
        if ((metaData & META_ALT) > 0)
            metaState |= META_ALT_ON;
        if ((metaData & META_CTRL) > 0)
            metaState |= META_CTRL_ON;
        if ((metaData & META_META) > 0)
            metaState |= META_META_ON;
        if ((metaData & META_CAPS_LOCK) > 0)
            metaState |= META_CAPS_LOCK_ON;
        if ((metaData & META_SCROLL_LOCK) > 0)
            metaState |= META_SCROLL_LOCK_ON;
        if ((metaData & META_NUM_LOCK) > 0)
            metaState |= META_NUM_LOCK_ON;

        return metaState;
    }

    private boolean writeDataToOutputStream(String data) {
        synchronized (mImeSocketLock) {
            try {
                if (DEBUG_COMMAND) Log.d(TAG, "write to output stream");
                    mImeDataOutputStream.writeUTF(data);
                    mImeDataOutputStream.flush();
                Log.d(TAG, "successfully written to output stream");
            } catch (Exception e) {
                Log.e(TAG, "writeDataToOutputStream: Failed to write data : " +  data + " to ime data output stream, exception : " + e.getMessage() + ", try connecting to server again.");
                if (!connectToImeServer()) {
                    Log.e(TAG, "writeDataToOutputStream: Failed to connect to server");
                    return false;
                }

                try {
                    mImeDataOutputStream.writeUTF(data);
                    mImeDataOutputStream.flush();
                } catch(Exception ex) {
                    Exception exception = new Exception("failed to write again to ime data output stream, exception : " + ex.getMessage());
                    Log.d("KeyCommandError", exception.getMessage(), exception);
                    return false;
                }
            }

            Log.d(TAG, "Waiting for writeUTF ack");
            try {
                String ack = mImeDataInputStream.readUTF();

                if (ack.equals("ERROR")) {
                   Exception exception = new Exception("Process Ime Data failed");
                   Log.d(TAG, exception.getMessage(), exception);
                   return false;
                }

                Log.d(TAG, "Process ime data ack received: " + ack);
                return true;
            } catch (Exception e) {
                Exception exception = new Exception("Exception occurred while waiting for ime ack");
                Log.d(TAG, exception.getMessage(), exception);
                return false;
            }
        }
    }

    public void execute(final ByteBuffer data) {
        if (DEBUG_COMMAND) {
            String str = "";
            for (int i = 0; i < data.remaining(); i++)
                str += (data.get(i) & 0xFF) + ",";

            Log.d(TAG, "execute() called with: command[" + data.remaining() + "] = [" + str + "]");
        }

        int action = -1;
        int opcode = data.get();

        if (opcode == OP_KEY_DOWN)
            action = MotionEvent.ACTION_DOWN;
        else if (opcode == OP_KEY_UP)
            action = MotionEvent.ACTION_UP;
        else if (opcode == OP_IME) {
            StringBuilder stringBuilder = null;
            try {
                int dl, en, cp;
                dl = data.get();
                en = data.get();
                cp = data.get();

                StringBuilder input = new StringBuilder();
                while (data.hasRemaining()) {
                    input.append(data.getChar());
                }
                writeInput(input, dl, en, cp);
                return;
            } catch (Exception e) {
                Exception ex = new Exception("exception in OP_IME: " + e.getMessage());
                Log.d("KeyCommandError", ex.getMessage(), ex);
                return;
            }
        } else if (opcode == OP_IME_CLEAR_TEXT) {
            handleImeClearTextCommand();
            return;
        } else{
            Log.e(TAG, "Invalid opcode=" + opcode);
            return;
        }

        int type = data.get();
        if (type == TYPE_KEYCODE) {
            Integer keyCode = keyMap[data.get() & 0xFF];
            int metaState = getMetaState(data.get() & 0xFF);

            if ((metaState & META_CTRL_ON) != 0 && ctrlMetaStateMapping.containsKey(keyCode)) {
                keyCode = ctrlMetaStateMapping.get(keyCode);
                metaState = 0;
            }
            injectKeyCode(action, keyCode, metaState);
        } else if (type == TYPE_UNICODE) {
            //injectChar(action, data.getChar());
            if (action == MotionEvent.ACTION_DOWN && mImeDataOutputStream != null) {
                try {
                    String str = Character.toString(data.getChar());
                    if (DEBUG_COMMAND) Log.d(TAG, "inject char=" + str);
                    if(!writeDataToOutputStream(str)) {
                        Log.e(TAG, "KeyCommandExecutor:execute() - Failed to write string: " + str + " to ime data output stream");
                    }
                } catch (Exception e) {
                    Exception ex = new Exception("exception while processing data type TYPE_UNICODE, exception : " + e.getMessage());
                    Log.d("KeyCommandError", ex.getMessage(), ex);
                }
            }
        } else {
            Log.e(TAG, "Invalid type=" + type);
        }

        if (action == MotionEvent.ACTION_DOWN) {
            //SessionOrchestrator.getInstance().onUserInteraction("keyCommand");
        }
    }

    public boolean writeInput(StringBuilder input, int dl, int en, int cp) {
        StringBuilder stringBuilder;
        stringBuilder = new StringBuilder();
        stringBuilder.append("s_");
        stringBuilder.append(input);
        stringBuilder.append("_e");
        stringBuilder.append(" dl=" + dl + " en=" + en + " cp=" + cp);
        if (DEBUG_COMMAND) Log.d(TAG, "inject char=" + stringBuilder);

        boolean writeSucceeded = writeDataToOutputStream(stringBuilder.toString() + "\n");

        if (!writeSucceeded) {
            Log.e(TAG, "KeyCommandExecutor:execute() - Failed to write string: " + stringBuilder.toString() + " to ime data output stream");
        }

        return writeSucceeded;
    }

    public boolean handleImeClearTextCommand() {
        try {
            String command = "clearText";
            if (!writeDataToOutputStream(command)) {
                Log.e(TAG, "KeyCommandExecutor:execute() - Failed to write string: " + command + " to ime data output stream");
                return false;
            }
        } catch (Exception e) {
            Exception ex = new Exception("exception in OP_IME_CLEAR_TEXT: " + e.getMessage(), e);
            Log.d(TAG, "KeyCommandError_OpImeClearTextException: " + ex.getMessage(), ex);
            return false;
        }
        return true;
    }

    public boolean injectKeyCode(int action, Integer keyCode, int metaState) {
        if (DEBUG_COMMAND) Log.d(TAG, "injectKeyCode, action = " + action + " keyCode = " + KeyEvent.keyCodeToString(keyCode) + " metaState = " + metaState);
        boolean result = false;
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        try {
            mInstrumentation.sendKeySync(event);
            result = true;
        } catch (Exception e) {
            Log.e(TAG, "Error in injecting keyCode: " + event.toString());
            e.printStackTrace();
        }

        return result;
    }

    public void closeImeSocket() {
        Log.d(TAG, "Close ime sockets");
        try {
            if (mImeDataOutputStream != null)
                mImeDataOutputStream.close();
            if (mImeDataInputStream != null)
                mImeDataInputStream.close();
            if (mImeSocket != null)
                mImeSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error in closing socket");
        }

        mImeDataOutputStream = null;
        mImeDataInputStream = null;
        mImeSocket = null;
    }
}
