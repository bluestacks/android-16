package com.bluestacks.BstCommandProcessor;

import android.app.IntentService;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

import com.bluestacks.BstCommandProcessor.BstCommandProcessorApplication;
import com.bluestacks.BstCommandProcessor.Accessibility.KeyCommandExecutor;
import com.bluestacks.BstCommandProcessor.Accessibility.InputUtils;

/**
 * Intent receiver for closing IME sockets
 */
public class BstCommandProcessorIntentService extends IntentService {
    private static final String TAG = "BstCommandProcessorIntentService";
    private static final boolean DBG = SystemProperties.getInt("bst.debug.player", 0) > 0;
    public static final String ACTION_CLOSE_IME_SOCKETS = "com.bluestacks.BstCommandProcessor.CLOSE_IME_SOCKETS";

    public BstCommandProcessorIntentService(String name) {
        super(name);
    }

    public BstCommandProcessorIntentService() {
        this("BstCommandProcessorIntentService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "Null intent ");
            return;
        }

        String intentAction = intent.getAction();
        if (intentAction == null) {
            return;
        }

        try {
            if (ACTION_CLOSE_IME_SOCKETS.equals(intentAction)) {
                Log.d(TAG, "ACTION_CLOSE_IME_SOCKETS");
                closeImeSocket();
            }
        } catch (Exception exception) {
            if (DBG) exception.printStackTrace();
        }
    }

    private void closeImeSocket() {
        KeyCommandExecutor keyCommandExecutor = BstCommandProcessorApplication.getInstance().getKeyCommandExecutor();

        if (keyCommandExecutor != null) {
            keyCommandExecutor.closeImeSocket();
        }
    }
}
