package com.bluestacks.BstCommandProcessor;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.util.HashMap;
import java.util.Map;

public class BstCommandProcessorUtils {
    private static final String TAG = "BstCommandProcessorUtils";
    private static final boolean DBG = false;
    private static final int SLEEP_TIME = 100;
    private static final int MAX_ATTEMPTS_ALLOWED = 28;
    private static final int MAX_MOUNT_FOLDER_ATTEMPTS_ALLOWED = 300;
    
    private static Service mService = BstCommandProcessorApplication.getInstance().getServiceHandler();
    private static final ActivityManager mActivityManager = (ActivityManager) BstCommandProcessorApplication.getInstance().getSystemService(BstCommandProcessorApplication.ACTIVITY_SERVICE);
    private static ClearUserDataObserver mClearDataObserver;

    BstCommandProcessorUtils() {
    }

    /* Observer for clearing UserData */
    static class ClearUserDataObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(final String packageName, final boolean succeeded) {
            if(succeeded)
            {
                Log.d(TAG, "UserData cleared successfully for package : " + packageName);
            }
            else
            {
                Log.d(TAG, "Error in clearing UserData for package : " + packageName);
            }
        }
    }

    private static boolean isSystemReady() {
        File dir = Environment.getExternalStorageDirectory();

        Boolean bool = Environment.isExternalStorageRemovable();
        String status = Environment.getExternalStorageState();
        Boolean boot_completed = isBootCompleted();
        int deviceProvisioned = Settings.Global.getInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0);
        //if (DBG)
        Log.d(TAG, "in isSystemReady, isBootCompleted " + boot_completed + " External storage status: " + status
                + "  External storage dir :" + dir + "  isExternalStorageRemovable:" + bool + " deviceProvisioned " + deviceProvisioned);
        return boot_completed && status.equals(Environment.MEDIA_MOUNTED) && (deviceProvisioned == 1);
    }

    private static boolean isBootCompleted() {
        return SystemProperties.get("bst.config.boot_completed", "0").equals("1")
                && SystemProperties.get("bst.config.screen_enabled", "0").equals("1");
    }

    public static boolean isMarketInstalled()
    {
        try
        {
            PackageManager pm = mService.getPackageManager();
            pm.getPackageInfo("com.google.android.gsf", 0);
            pm.getPackageInfo("com.google.android.gsf.login", 0);
            pm.getPackageInfo("com.android.vending", 0);
            if (DBG) Log.d(TAG, "market is installed on this device.");
            return true;
        }
        catch (PackageManager.NameNotFoundException e)
        {
            Log.w(TAG, "market is not installed on this device.");
            return false;
        }
    }

    // This function will copy file from sourceFile to destFile.
    public static boolean copyFile(File sourceFile, File destFile) {
        if (!sourceFile.exists()) {
            Log.d(TAG, "File " + sourceFile.getName() + " doesn't exist");
            return false;
        }

        try {
            if (!destFile.exists())
                destFile.createNewFile();
        } catch (Exception ex) {
            Log.w(TAG, "Exception while creating destination file : " + destFile.getPath());
            if (DBG) ex.printStackTrace();
            return false;
        }

        try (FileChannel source = new FileInputStream(sourceFile).getChannel();
                FileChannel destination = new FileOutputStream(destFile).getChannel()) {
            if (destination != null && source != null)
                destination.transferFrom(source, 0, source.size());
        } catch (Exception e) {
            Log.w(TAG,"Exception in copying file " + sourceFile + " to " + destFile + ": " + e.getMessage());
            if (DBG) e.printStackTrace();
            return false;
        }
        return true;
    }

    /*
     * A wrapper function for initiating clearing user data for packages.
     * It takes String array as input.
     */
    public static boolean initiateClearUserData(String[] packageName) {
        boolean res;
        for (int i=0; i<packageName.length; i++)
        {
            if (packageName[i] == null || packageName[i].equals(""))
                continue;
            res = initiateClearUserData(packageName[i]);
            if (!res) {
                Log.d(TAG, "Error: " + res + " in clearing data for package " + packageName[i]);
                return false ;
            }
        }
        return true ;
    }

    /*
     * Private method to initiate clearing user data for package pkgName
     */
    public static boolean initiateClearUserData(String packageName) {
        // Invoke clear user data for given package
        boolean isSystemReady = isSystemReady();
        if (DBG) Log.d(TAG, "initiateClearUserData, isSystemReady = " + isSystemReady);
        if (isSystemReady)
        {
            Log.i(TAG, "Clearing user data for package : " + packageName);
            if (mClearDataObserver == null) {
                mClearDataObserver = new ClearUserDataObserver();
            }
            boolean res = mActivityManager.clearApplicationUserData(packageName, mClearDataObserver);
            if (!res) {
                // Clearing data failed for some obscure reason. Just log error for now
                Log.w(TAG, "Could not clear application user data for package: " + packageName);
                return res;
            }
            return res;
        }
        else {
            Log.w(TAG, "Failed to clear data as system not ready");
            return false;
        }
    }

    //This function is to execute the root command
    public static String  execRootCmdSilent(String cmd) {
        String  readResult = "";
        DataOutputStream dos = null;
        DataInputStream is = null;
        try {
            final java.lang.Process p = Runtime.getRuntime().exec("/system/xbin/bstk/su");
            try {
                dos = new DataOutputStream(p.getOutputStream());
                is = new DataInputStream(p.getInputStream());
                dos.writeBytes(cmd + "\n");
                dos.flush();
                dos.writeBytes("exit\n");
                dos.flush();
                String line = null;
                while ((line = is.readLine()) != null) {
                    readResult += line;
                }
                p.waitFor();
                int result = -1;
                result = p.exitValue();
                if(result != 0){
                    Log.w(TAG, "execRootCmdSilent with unexpected  result " + result);
                    readResult = null;
                }
            } catch (Exception e) {
                if (DBG) e.printStackTrace();
            } finally {
                if (dos != null) {
                    try {
                        dos.close();
                    } catch (IOException e) {
                        if (DBG) e.printStackTrace();
                    }
                }
                if (p != null) {
                    p.destroy();
                }
            }
        } catch(Exception e) {
            Log.e(TAG, "Exception in execRootCmdSilent while creating/destroying process: " + e.getMessage());
            if (DBG) e.printStackTrace();
            return null;
        }
        return readResult;
    }

    static KeyEvent genKeyEvent(int action, int keyCode, int metaState) {
        long eventTime = 0;
        long downTime = eventTime;
        int repeatCount = 0;
        //metaState = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        KeyEvent newEvent = new KeyEvent(downTime, eventTime, action, keyCode, repeatCount, metaState);
        if(DBG) Log.d(TAG, "generated keyEvent: " + newEvent);
        return newEvent;
    }

    static void injectKeyEvent(KeyEvent event) {
        Instrumentation inst = new Instrumentation();
        inst.sendKeySync(event);
    }

    static boolean isSystemApp(ApplicationInfo ai) {
        int mask = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (ai.flags & mask) != 0;
    }

    static HashMap<String,String> readAppSettingsFile(String filePath)
    {
        HashMap<String, String> gameSettingMap = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] lineArray = line.split(";");
                if (lineArray.length == 2)
                    gameSettingMap.put(lineArray[0], lineArray[1]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return gameSettingMap;
    }

    static String readFile(String filePath)
    {
        BufferedReader br = null;
        String str = null;
        StringBuilder strb = new StringBuilder();
        try {
            br = new BufferedReader(new FileReader(filePath));
            while ((str = br.readLine()) != null) {
                strb.append(str);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return strb.toString();
    }

    static void saveFile(String filePath, String content)
    {
         try {
            FileWriter fileWriter = new FileWriter(filePath);
            fileWriter.write(content);
            if (fileWriter != null) {
                fileWriter.flush();
                fileWriter.close();
                File outFile = new File(filePath);
                if (FileUtils.setPermissions(outFile,
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                        FileUtils.S_IRGRP | FileUtils.S_IROTH | FileUtils.S_IWGRP | FileUtils.S_IWOTH, -1, -1) != 0)
                {
                    Log.e(TAG, "Failed to change permissions for the file: " + outFile.getPath());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void writeAppSettingsFile(String filePath, HashMap<String, String> gameSettingMap) {
        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : gameSettingMap.entrySet()) {
            stringBuilder.append(entry.getKey()).append(";").append(entry.getValue()).append("\n");
        }

        try {
            FileWriter fileWriter = new FileWriter(filePath);
            fileWriter.write(stringBuilder.toString());
            if (fileWriter != null) {
                fileWriter.flush();
                fileWriter.close();
                File outFile = new File(filePath);
                if (FileUtils.setPermissions(outFile,
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                        FileUtils.S_IRGRP | FileUtils.S_IROTH | FileUtils.S_IWGRP | FileUtils.S_IWOTH, -1, -1) != 0)
                {
                    Log.e(TAG, "Failed to change permissions for the file: " + outFile.getPath());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    static class ChromeTabStructure
    {
        int version;
        int count;
        int incognitoCount;
        int incognitoActiveIndex;
        int standardActiveIndex;
        LinkedHashMap<Integer,String> tabUrlMap;
        boolean status;

        @Override
        public String toString() {
            return "ChromeTabStructure{" +
                    "version=" + version +
                    ", count=" + count +
                    ", incognitoCount=" + incognitoCount +
                    ", incognitoActiveIndex=" + incognitoActiveIndex +
                    ", standardActiveIndex=" + standardActiveIndex +
                    ", tabUrlMap=" + tabUrlMap +
                    ", status=" + status +
                    '}';
        }
    }

    public static ChromeTabStructure readSavedStateFile(File file) {
        // code for reading this saved file taken from TabPersistentStore.java
        // https://cs.chromium.org/chromium/src/chrome/android/java/src/org/chromium/chrome/browser/tabmodel/TabPersistentStore.java
        // check readSavedStateFile function
        if (DBG) Log.d(TAG, "readSavedStateFile() called with: file = [" + file + "]");

        FileInputStream fileInputStream = null;
        BufferedInputStream bufferedInputStream = null;
        DataInputStream stream = null;
        boolean skipUrlRead = false;
        int nextId = 0;
        ChromeTabStructure chromeTabStructure = new ChromeTabStructure();
        try {
            final int SAVED_STATE_VERSION = 5;
            fileInputStream = new FileInputStream(file);
            bufferedInputStream = new BufferedInputStream(fileInputStream);
            stream = new DataInputStream(bufferedInputStream);

            boolean skipIncognitoCount = false;

            chromeTabStructure.version = stream.readInt();
            if (chromeTabStructure.version != SAVED_STATE_VERSION) {
                // We don't support restoring Tab data from before M18.
                if (chromeTabStructure.version < 3) return chromeTabStructure;
                // Older versions are missing newer data.
                if (chromeTabStructure.version < 5) skipIncognitoCount = true;
                if (chromeTabStructure.version < 4) skipUrlRead = true;
            }

            chromeTabStructure.count = stream.readInt();
            chromeTabStructure.incognitoCount = skipIncognitoCount ? -1 : stream.readInt();
            chromeTabStructure.incognitoActiveIndex = stream.readInt();
            chromeTabStructure.standardActiveIndex = stream.readInt();
            chromeTabStructure.tabUrlMap = new LinkedHashMap<>();

            for (int i = 0; i < chromeTabStructure.count; i++) {
                int id = stream.readInt();
                String tabUrl = skipUrlRead ? "" : stream.readUTF();

                chromeTabStructure.tabUrlMap.put(id, tabUrl);
                if (id >= nextId) nextId = id + 1;
                if (DBG) Log.d(TAG, "readSavedStateFile: tabUrl = " + tabUrl);
            }
            chromeTabStructure.status = true;
        } catch (Exception e) {
            if (DBG) e.printStackTrace();
        } finally {
            try {
                if (fileInputStream != null) {
                    fileInputStream.close();
                }
                if (bufferedInputStream != null) {
                    fileInputStream.close();
                }
                if (stream != null) {
                    fileInputStream.close();
                }
            } catch (IOException e) {
                if (DBG) e.printStackTrace();
            }
        }
        return chromeTabStructure;
    }

    public static void clearGoogleAppsData(String TAG) {
        boolean androidIdSet = Boolean.valueOf(SystemProperties.get("persist.sys.cleardata", "false"));
        Log.d(TAG, "Should clear google data? " + androidIdSet);
        // If true it means that this is the first boot in a cloned instance.
        if (androidIdSet) {
            String[] packageList = {"com.google.android.gsf", "com.google.android.gsf.login", "com.google.android.gms", "com.android.vending"};
            Log.d(TAG, "Clearing google apps data as new value of androidId is set");
            boolean res = BstCommandProcessorUtils.initiateClearUserData(packageList);
            if (res)
                SystemProperties.set("persist.sys.cleardata", "false");
        }
    }

    public static String getPackageInstallerName(Context context, String pkgName) {
            String installerPackageName = "";
            try {
                installerPackageName = context.getPackageManager().getInstallerPackageName(pkgName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        return installerPackageName;
    }
    public static boolean startActivity(Intent intent) {
        if (intent == null) {
            Log.w(TAG,"launchIntent: launchIntent is null");
            return false;
        } 
        try {
            mService.startActivityAsUser(intent, Process.myUserHandle()); 
            return true;

        } catch (Exception e) { 
            return false;
        }
    }

    public static boolean openApp(String packageName) { 
        try {

            PackageManager pm = mService.getPackageManager();

            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            return startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { 
            return false;
        }
    }
}
