package com.bluestacks.BstCommandProcessor;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.Instrumentation;
import android.app.Service;
import android.app.backup.BackupManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.IntentSender;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.LocaleList;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;


import com.android.internal.app.LocalePicker;
import com.bluestacks.os.BstFilterAppsManager;
import com.bluestacks.os.BstHostCallManager;
import com.bluestacks.os.BstUtilsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.Enumeration;
import android.hardware.input.InputManager;

import libcore.io.IoUtils;

import static com.bluestacks.BstCommandProcessor.BstCommandProcessorApplication.BstCommandProcessorPath;
import com.bluestacks.BstCommandProcessor.UiAccessibilityService;
import com.bluestacks.BstCommandProcessor.Accessibility.UiAutomationExecutor;
import com.bluestacks.BstCommandProcessor.Accessibility.InputUtils;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import android.os.Handler;
import android.os.Looper;

class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
    boolean finished;
    boolean result;

    public void packageDeleted(String packageName, int returnCode) {
        synchronized (this) {
            finished = true;
        result = returnCode == PackageManager.DELETE_SUCCEEDED;
        notifyAll();
        }
    }
}

/* Main Class of Xapk Structure, Xapks are zip files having manifest.json, apk file and
   expansion files (obb)
*/
class XApk {
    // Result codes, keeping error code in range of -200, so that we can differentiate  between
    // these error codes and actual packagemanager error codes.
    /**
     * Installation return code: this is passed from the main function installXapk when xapk file
     * is invalid.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_INVALID_XAPK = -200;

    /**
     * Installation return code: this is passed from the installExpansions function if expansion
     * file is corrupt,zip entry is null
     *
     * @hide
     */
    public static final int INSTALL_FAILED_EXPANSION_FILE_INVALID = -201;
    /**
     * Installation return code: this is passed from the installExpansions when we get an
     * Exception while copying Expansion file
     *
     * @hide
     */
    public static final int INSTALL_FAILED_EXPANSION_FILE_FAILED = -202;
    /**
     * Installation return code: this is passed from the extractApk function when we get an
     * Exception while copying apk file
     *
     * @hide
     */
    public static final int INSTALL_FAILED_APK_EXTRACTION_FAILED = -203;
    /**
     * Installation return code: this is passed from the installExpansions function
     * when we get xapk version > 2
     *
     * @hide
     */
    public static final int INSTALL_FAILED_INVALID_XAPK_VERSION = -204;


    /**
     * Installation return code: this is passed from the extractApk function, when apk is
     * extracted successfully
     *
     * @hide
     */
    public static final int INSTALL_APK_EXTRACTION_SUCCEEDED = 1001;
    /**
     * Installation return code: this is passed from the function installExpansions, when
     * expansion file is successfully copied
     *
     * @hide
     */
    public static final int INSTALL_EXPANSION_SUCCEEDED = 1002;

    XApkManifest xApkManifest;
    ZipFile xapkFile;

    public static String installStatusToString(int status) {
        switch (status) {
            case INSTALL_FAILED_INVALID_XAPK:
                return "INSTALL_FAILED_INVALID_XAPK";
            case INSTALL_FAILED_EXPANSION_FILE_INVALID:
                return "INSTALL_FAILED_EXPANSION_FILE_INVALID";
            case INSTALL_FAILED_EXPANSION_FILE_FAILED:
                return "INSTALL_FAILED_EXPANSION_FILE_FAILED";
            case INSTALL_FAILED_APK_EXTRACTION_FAILED:
                return "INSTALL_FAILED_APK_EXTRACTION_FAILED";
            case INSTALL_FAILED_INVALID_XAPK_VERSION:
                return "INSTALL_FAILED_INVALID_XAPK_VERSION";
            case INSTALL_APK_EXTRACTION_SUCCEEDED:
                return "INSTALL_APK_EXTRACTION_SUCCEEDED";
            case INSTALL_EXPANSION_SUCCEEDED:
                return "INSTALL_EXPANSION_SUCCEEDED";
            default:
                return Integer.toString(status);
        }
    }
}

// Class for holding manifest values of xapk files
class XApkManifest
{
    List<XApkExpansion> expansions;
    List<XApkSplitApks> splitApks;

    String Label;
    Map<String, String> localesLabel;
    String maxSdkVersion;
    String minSdkVersion;
    String packageName;
    List<String> permissions;
    String targetSdkVersion;
    long totalSize;
    String versionCode;
    String versionName;
    int xApkVersion;

    File getExpansionFile(final XApkExpansion xApkExpansion) {
        if (isValidXapkVersion()) {
            final File externalStorageDirectory = Environment.getExternalStorageDirectory();
            final String name = new File(xApkExpansion.file).getName();
            if (name.toLowerCase().endsWith(".obb")) {
                final File file = new File(externalStorageDirectory, String.format("Android/obb/%s/%s", this.packageName, name));
                file.getParentFile().mkdirs();
                return file;
            }
        }
        return null;
    }

    public boolean isValidXapkVersion() {
        return (xApkVersion <= 2);
    }
}

// class for holding values of an expansion file in manifest of xapk file
class XApkExpansion
{
    String file; //contains info about file in xapk
    String installLocation; //contains info where to install; not using now using Environment.getExternalStorageDirectory
    String installPath; // contains info where to copy file
}

class XApkSplitApks
{
    String file;
    String id;
}

class APKInstallResponse
{
    int response;
    String errorString;
    String pkgName;
    String attemptId;
}

public class BstCommandLoop {
    private static final String TAG = "BstCommandProcessor-CommandHandler";
    private static final String TAG_BST_REFERRAL = "BstCommandProcessor-CommandHandler";
    private static final boolean DBG = SystemProperties.getInt("bst.debug.bstcmdloop", 0) > 0;
    private static final boolean DBG_BST_REFERRAL = DBG || SystemProperties.getInt("bst.debug.referral", 0) > 0;
    private static final boolean VERBOSE = SystemProperties.getInt("bst.debug.bstcmdloop", 0) > 1;
    private static final String PM_NOT_RUNNING_ERR = "Could not access the Package Manager. Is the system running?";

    private static final String OEM = SystemProperties.get("bst.oem", "");
    private static final String BST_SHARED_FOLDER_PATH = "/mnt/windows/BstSharedFolder/";
    private static final String INSTALLED_APPS_DATA_FILE = "InstalledAppsData";

    private static final String NOWGG_ACCOUNT_TYPE = "now.gg";

    private static final ActivityManager mActivityManager = (ActivityManager) BstCommandProcessorApplication.getInstance().getSystemService(BstCommandProcessorApplication.ACTIVITY_SERVICE);
    private static final BstFilterAppsManager mBstFilterAppsManager = (BstFilterAppsManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_FILTER_APPS);
    private static final BstUtilsManager mBstUtilsManager = (BstUtilsManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_UTILS);
    private static final BstHostCallManager mBstHostCallManagerService = (BstHostCallManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_HOST_CALL);
    private static final ClipboardManager mBstClipboardManager = (ClipboardManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.CLIPBOARD_SERVICE);
    private static final AudioManager mAudioManager = (AudioManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.AUDIO_SERVICE);
    private static Service mService = BstCommandProcessorApplication.getInstance().getServiceHandler();
    private static final Context mContext = BstCommandProcessorApplication.getInstance().getApplicationContext();
    private static boolean mIsVolumeMuted = mAudioManager.isStreamMute(AudioManager.STREAM_MUSIC);

    // file to store list of blacklisted packages still installed
    private static final String bstBlacklistedInstalledAppListPath = "/data/downloads/.tmp/.blc";

    private static final APKInstallResponse mApkInstallResponse = new APKInstallResponse();

    // define time variables for checking UI stability
    private static final int QUIET_DURATION_MS = 300;
    private static final int TIMEOUT_MS = 2000;
    private static final int POLL_INTERVAL_MS = 80;

    // defined in hd/Source/hcall/include/HcallCcCodes.h
    private static final int HCALL_CC_hcallGcallCmdResponse = 10;
    private static final int HCALL_CC_hcallSetSmartDownloadEnabled = 11; // 0xB, keep in sync with HcallCcCodes.h
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public String agentImportFilesClbk(String payload) {
        Log.d(TAG, "agentImportFilesClbk called with payload length: " + (payload != null ? payload.length() : "null"));
        JSONObject response = new JSONObject();
        JSONArray failedFiles = new JSONArray();
        String status = "success";

        try {
            JSONObject request = new JSONObject(payload);
            String sharedDir = request.getString("sharedDir");
            String guestDest = request.getString("guestDest");
            JSONArray files = request.getJSONArray("files");
            Log.d(TAG, "Parsed import request: sharedDir=" + sharedDir + ", guestDest=" + guestDest + ", files.length=" + files.length());

            for (int i = 0; i < files.length(); i++) {
                JSONObject fileInfo = files.getJSONObject(i);
                String src = fileInfo.getString("src");
                String dst = fileInfo.getString("dst");

                File srcDir = new File(BST_SHARED_FOLDER_PATH, sharedDir);
                File srcFile = new File(srcDir, src);
                File dstFile = new File(guestDest, dst);

                if (DBG) Log.d(TAG, "Importing: " + srcFile.getAbsolutePath() + " -> " + dstFile.getAbsolutePath());

                try {
                    if (srcFile.isDirectory()) {
                        if (!copyDirectory(srcFile, dstFile)) {
                            throw new IOException("Failed to copy directory from " + srcFile.getAbsolutePath() + " to " + dstFile.getAbsolutePath());
                        }
                    } else {
                        File parentDir = dstFile.getParentFile();
                        if (parentDir != null && !parentDir.exists()) {
                            if (!parentDir.mkdirs()) {
                                throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
                            }
                        }

                        if (!copyFile(srcFile, dstFile)) {
                            throw new IOException("Failed to copy file from " + srcFile.getAbsolutePath() + " to " + dstFile.getAbsolutePath());
                        }
                    }
                    Log.d(TAG, "Successfully imported " + src + " to " + dst);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to import " + src + ": " + e.getMessage(), e);
                    failedFiles.put(src);
                    status = "failure";
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse import payload: " + e.getMessage());
            status = "failure";
            try { response.put("error", "Invalid JSON payload"); } catch (JSONException je) {}
        }

        try {
            response.put("status", status);
            if (failedFiles.length() > 0) {
                response.put("failed_files", failedFiles);
            }
        } catch (JSONException e) {
            // Should not happen
        }

        String resultJson = response.toString();
        if (DBG) Log.d(TAG, "agentImportFilesClbk returning resultJson: " + resultJson);
        return resultJson;
    }

    public String agentExportFilesClbk(String payload) {
        if (DBG) Log.d(TAG, "agentExportFilesClbk called with payload: " + payload);
        JSONObject response = new JSONObject();
        JSONArray failedFiles = new JSONArray();
        String status = "success";

        try {
            JSONObject request = new JSONObject(payload);
            String sharedDir = request.getString("sharedDir");
            String hostDest = request.getString("hostDest");
            JSONArray files = request.getJSONArray("files");
            if (DBG) Log.d(TAG, "Parsed export request: sharedDir=" + sharedDir + ", hostDest=" + hostDest + ", files.length=" + files.length());

            File exportDir = new File(BST_SHARED_FOLDER_PATH, sharedDir);
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw new IOException("Failed to create export directory: " + exportDir.getAbsolutePath());
            }

            for (int i = 0; i < files.length(); i++) {
                JSONObject fileInfo = files.getJSONObject(i);
                String src = fileInfo.getString("src");
                String dst = fileInfo.getString("dst");

                File srcFile = new File(src);
                File dstFile = new File(exportDir, dst);

                if (DBG) Log.d(TAG, "Exporting: " + srcFile.getAbsolutePath() + " -> " + dstFile.getAbsolutePath());

                try {
                    if (srcFile.isDirectory()) {
                        if (!copyDirectory(srcFile, dstFile)) {
                            throw new IOException("Failed to copy directory from " + srcFile.getAbsolutePath() + " to " + dstFile.getAbsolutePath());
                        }
                    } else {
                        if (!copyFile(srcFile, dstFile)) {
                            throw new IOException("Failed to copy file from " + srcFile.getAbsolutePath() + " to " + dstFile.getAbsolutePath());
                        }
                    }
                    Log.d(TAG, "Successfully exported " + src + " to " + dst);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to export " + src + ": " + e.getMessage(), e);
                    failedFiles.put(src);
                    status = "failure";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to process export payload: " + e.getMessage());
            status = "failure";
            try { response.put("error", "Invalid payload or I/O error: " + e.getMessage()); } catch (JSONException je) {}
        }

        try {
            response.put("status", status);
            if (failedFiles.length() > 0) {
                response.put("failed_files", failedFiles);
            }
        } catch (JSONException e) {
            // Should not happen
        }

        String resultJson = response.toString();
        if (DBG) Log.d(TAG, "agentExportFilesClbk returning resultJson: " + resultJson);
        return resultJson;
    }

    public BstCommandLoop() {
        if (DBG) Log.d(TAG, "BstCommandLoop constructor called");
        // Loading native lib
        System.loadLibrary("gcall_jni");
        if (native_init() != 0) {
            Log.e(TAG, "Error in calling gcall_jni init function");
            throw new NullPointerException("Error in init gcall_jni init function");
        }
    }

    @Override
    protected void finalize() {
        if (DBG) Log.d(TAG, "BstCommandLoop destructor called");
        if (native_fini() != 0) {
            Log.e(TAG, "Error in calling gcall lib fini function");
        }
    }

    /*
     * HCALL wrappers
     */

    /*
     * @hide
     */
    public void initInputDebuggingStatus() {
        if (DBG) Log.d(TAG, "initInputDebuggingStatus function called");
        try {
            final ContentResolver mResolver = BstCommandProcessorApplication.getInstance().getContentResolver();
            boolean showTouches = Settings.System.getInt(mResolver, Settings.System.SHOW_TOUCHES, 0) == 1;
            boolean showPointerLocation = Settings.System.getInt(mResolver, Settings.System.POINTER_LOCATION, 0) == 1;
            Log.d(TAG, "calling native initInputDebuggingStatus function, showTouches: " + showTouches + " showPointerLocation : " + showPointerLocation);
            int rval = mBstHostCallManagerService.initInputDebuggingStatus(showTouches, showPointerLocation);
            Log.d(TAG, "native initInputDebuggingStatus method returned: " + rval);
        } catch (Exception e) {
            Log.e(TAG, "Exception in getting input debuging status");
            e.printStackTrace();
        }
    }

    /*
     * @hide
     */
    public void initImeStatus() {
        if (DBG) Log.d(TAG, "initImeStatus function called");
        String ime = null;
        //TODO: Move this code to BstHostCallService and get it called from InputMethodManagerService ??
        try {
            ime = Settings.Secure.getString(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (Exception e) {
            Log.e(TAG, "Exception in getting input debuging status: " + e.getMessage());
            if (DBG) e.printStackTrace();
        } finally {
            Log.d(TAG, "in initImeStatus function, ime received from settings: " + ime);
            if (ime == null) {
                ime = "com.android.inputmethod.latin/.LatinIME";
            }
            Log.d(TAG, "calling native initImeStatus function, ime: " + ime);
            int rval = mBstHostCallManagerService.initImeStatus(ime);
            Log.d(TAG, "native initImeStatus method returned: " + rval);
        }
    }

    /*
     * @hide
     */
    public void syncInstalledApps() {
        if (DBG) Log.d(TAG, "syncInstalledApps");
        int rval;
        FileWriter file = null;
        JSONObject jObject = null;
        String fileToWrite = "";
        String jsonData = getInstalledPackagesInfo();
        /* Making sure that InstalledApps data size is less than MaxHostBoundSize
        i.e 16384(16 kB). We compare size of jsonData wih 16367, because we add
        some extra bytes(31 bytes) to jsonData in hcallSyncInstalledAppsRpc
        packetSize = 8 + 4 + __size_jsonData + 1*4 + 1 byte for null char + 14 bytes for filename */
        try {
            // jsonData will get converted to UTF-8 inside jni
            if (jsonData.getBytes("UTF-8").length > 16353) {
                fileToWrite = INSTALLED_APPS_DATA_FILE + "_" + SystemProperties.get("bst.instance", "") + "_" + System.currentTimeMillis() + ".json";
                file = new FileWriter(BST_SHARED_FOLDER_PATH + fileToWrite);
                file.write(jsonData.toString());
                jObject = new JSONObject();
            } else {
                jObject = new JSONObject(jsonData);
            }
            jObject.put("filename", fileToWrite);
        } catch (Exception e) {
           Log.e(TAG, "Exception in writing Installed Apps data to Json file. " + e.getMessage());
           if (DBG) e.printStackTrace();
        } finally {
            try {
                if (file != null) {
                    file.flush();
                    file.close();
                }
            } catch (IOException e) {
                if (DBG) e.printStackTrace();
            }
        }
        if (jObject != null) {
            jsonData = jObject.toString();
            rval = mBstHostCallManagerService.syncInstalledApps(jsonData);
        }
        return;
    }

    /*
     * @hide
     */
    public void getLocalTime() {
        Log.d(TAG, "getLocalTime called");
        int rval = native_getLocalTime();
        Log.d(TAG, "native getLocalTime method returned: " + rval);
    }

    /*
     * @hide
     */
    public void onAppInstalled(String pkgName, String activity, String appLabel, int versionCode, String versionName, String iconFileName, String source, boolean isUpdate, boolean isHomeApp, String orientation) {
        Log.d(TAG, "calling native onAppInstalled function, pkgName: " + pkgName + " activity: " + activity + " appLabel: " + appLabel + ", versionCode: " + versionCode + ", versionName: " + versionName + " iconFileName: " + iconFileName + ", source: " + source + ", isUpdate: " + isUpdate + ", isHomeApp: " + isHomeApp + ", orientation: " + orientation);

        String attemptId ="";
        if (pkgName.equals(mApkInstallResponse.pkgName)) {
            attemptId = mApkInstallResponse.attemptId;
            mApkInstallResponse.attemptId = "";
        }
        String pkgInstallerName = BstCommandProcessorUtils.getPackageInstallerName(mService, pkgName);
        JSONObject appInstallData = new JSONObject();
        try {
            appInstallData.put("pkg", pkgName);
            appInstallData.put("activity", activity);
            appInstallData.put("label", appLabel);
            appInstallData.put("versionCode", versionCode);
            appInstallData.put("versionName", versionName);
            appInstallData.put("iconFileName", iconFileName);
            appInstallData.put("source", source);
            appInstallData.put("isUpdate", isUpdate);
            appInstallData.put("isHomeApp", isHomeApp);
            appInstallData.put("orientation", orientation);
            appInstallData.put("attemptId", attemptId);
            appInstallData.put("packageInstallerName", pkgInstallerName);
            ApplicationInfo info = mService.getPackageManager().getApplicationInfo(pkgName, PackageManager.GET_META_DATA);
            boolean isSystemApp = BstCommandProcessorUtils.isSystemApp(info);
            appInstallData.put("isSystemApp", isSystemApp);
        } catch (Exception e) {
            Log.d(TAG, "Exception in sending onAppInstalled");
            e.printStackTrace();
        }
        int rval = mBstHostCallManagerService.onAppInstalled(appInstallData.toString());
        Log.d(TAG, "native onAppInstalled method returned: " + rval);
    }

    /*
     * @hide
     */
    public void onAppUninstalled(String pkgName) {
        Log.d(TAG, "calling native onAppUninstalled function, pkgName: " + pkgName);
        int rval = mBstHostCallManagerService.onAppUninstalled(pkgName);
        Log.d(TAG, "native onAppUninstalled method returned: " + rval);
    }

    /**
     * Gcall CallBack functions
     */
    private int setDeviceProfileClbk (String deviceProfileCode, String deviceCarrierCode) {
        if (DBG) Log.d(TAG, "setDeviceProfileClbk function called with deviceProfileCode: " + deviceProfileCode + " deviceCarrierCode: " + deviceCarrierCode);
        int retval = -1;
        String caCode = SystemProperties.get("bst.device_country_code");
        String[] packages = {"com.android.vending", "com.google.android.gms"};

        String result = changeDeviceProfile(deviceProfileCode, caCode, deviceCarrierCode, null, false);
        if (result.equals("ok")) {
            SystemProperties.set("bst.device_carrier_code", deviceCarrierCode);
            SystemProperties.set("persist.sys.pcode", deviceProfileCode);
            BstCommandProcessorUtils.initiateClearUserData(packages);
            Log.i(TAG, "setDeviceProfileClbk executed successfully.");
            retval = 0;
        } else {
            Log.e(TAG, "ERROR in setDeviceProfileClbk, failure reason: " + result);
        }
        if (DBG) Log.d(TAG, "setDeviceProfileClbk returning " + retval);
        return retval;
    }

    private int setCustomDeviceProfileClbk(String deviceManufacturer, String deviceBrand, String deviceModel, String deviceCarrierCode) {
        if (DBG) Log.d(TAG, "setCustomDeviceProfileClbk function called with deviceManufacturer: " + deviceManufacturer + " deviceBrand: " + deviceBrand + " deviceModel: " + deviceModel + " deviceCarrierCode: " + deviceCarrierCode);
        int retval = -1;
        String[] packages = {"com.android.vending", "com.google.android.gms"};

        String result = changeDeviceProfile(deviceModel, deviceBrand, deviceManufacturer, deviceCarrierCode, true);
        if (result.equals("ok")) {
            SystemProperties.set("bst.device_carrier_code", deviceCarrierCode);
            SystemProperties.set("persist.sys.pcode", "custom");
            BstCommandProcessorUtils.initiateClearUserData(packages);
            Log.i(TAG, "setCustomDeviceProfileClbk executed successfully.");
            retval = 0;
        } else {
            Log.e(TAG, "ERROR in setCustomDeviceProfileClbk, failure reason: " + result);
        }
        if (DBG) Log.d(TAG, "setCustomDeviceProfileClbk returning " + retval);
        return retval;
    }

    private int setLocaleClbk(String locale) {
        if (DBG) Log.d(TAG, "setLocaleClbk function called with locale: " + locale);
        int retval = bstSetLocale(locale);

        if(DBG) Log.d(TAG, "setLocaleClbk returning " + retval);
        return retval;
    }

    private int rootDeviceClbk(int root) {
        if (DBG) Log.d(TAG, "rootDeviceClbk function called with root: " + root);
        SystemProperties.set("bst.config.bindmount", String.valueOf(root));
        return 0;
    }

    private int enableInputDebuggingClbk(boolean showTouches, boolean showPointerLocation) {
        if (DBG) Log.d(TAG, "enableInputDebuggingClbk function called with showTouches: " + showTouches + " showPointerLocation : " + showPointerLocation);
        if (showTouches) {
            Settings.System.putInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.System.SHOW_TOUCHES, 1);
            Log.i(TAG, "enabled show touch points");
        } else {
            Settings.System.putInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.System.SHOW_TOUCHES, 0);
            Log.i(TAG, "disabled show touch points");
        }
        if (showPointerLocation) {
            Settings.System.putInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.System.POINTER_LOCATION, 1);
            Log.i(TAG, "enabled display of pointer location");
        } else {
            Settings.System.putInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.System.POINTER_LOCATION, 0);
            Log.i(TAG, "disabled display of pointer location");
        }
        return 0;
    }

    private void setPreferredDeviceOrientationClbk(int orientation) {
        if (DBG) Log.d(TAG, "setPreferredDeviceOrientationClbk function called with orientation: " + orientation);

        if (orientation < 0 || orientation > 3) {
            Log.e(TAG, "setPreferredDeviceOrientationClbk invalid parameter value, orientation: " + orientation);
            return;
        }

        mBstUtilsManager.setBstProposedRotation(orientation);
        return;
    }

    private void setMaxFpsClbk(int maxFps) {
        if (DBG) Log.d(TAG, "setMaxFpsClbk function called with maxFps: " + maxFps);
        SystemProperties.set("bst.max_fps", String.valueOf(maxFps));
        return;
    }

    public int bstInstallApk(String apkFilePath, boolean sendBroadcast, String attemptId, String source) {
        return _bstInstallApk(apkFilePath, sendBroadcast, attemptId, source);
    }

    private int _bstInstallApk(String apkFilePath, boolean sendBroadcast, String attemptId, String source) {
        if (DBG) Log.d(TAG,"bstInstallApk function called with apkFilePath: " + apkFilePath + " sendBroadcast: " + sendBroadcast + " attemptId " + attemptId + " source " + source);

        attemptId = attemptId == null ? "" : attemptId;
        int rval = -1;
        mApkInstallResponse.response = -1;
        mApkInstallResponse.errorString = "";
        mApkInstallResponse.pkgName = "";
        mApkInstallResponse.attemptId = attemptId;
        int result = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        boolean xapkInstall = (apkFilePath != null && apkFilePath.toLowerCase().endsWith(".xapk"));
        // By default, install the apps in internal partition and don't install them in secure partition.
        // This will reduce the apk install time to almost half for secure/sdcard apps. No changes will
        // be there for internal apps.
        if (xapkInstall) {
            result = installXapk(apkFilePath);
        } else {
            result = installApk(apkFilePath);
        }

        if (result == PackageManager.INSTALL_SUCCEEDED)
            rval = 0;
        else
            rval = result;
        if (sendBroadcast)
            sendGameCenterBroadcast(apkFilePath, rval);
        Log.d (TAG, "apkFilePath : " + apkFilePath + " installation status : " + result + " return value : " + rval);

        String errorString = mApkInstallResponse.errorString == null ? "" : mApkInstallResponse.errorString;
        String packageName = mApkInstallResponse.pkgName == null ? "" : mApkInstallResponse.pkgName;
        mApkInstallResponse.response = rval;
        mBstHostCallManagerService.onInstallApkCompleted(rval, apkFilePath, errorString, attemptId, packageName);

        return rval;
    }

    private void sendGameCenterBroadcast(String apkFilePath, int rval) {
        Intent intent = new Intent();
        intent.setAction("com.bluestacks.action.INSTALL_APK_RESPONSE");
        intent.setComponent(new ComponentName("com.bluestacks.gamecenter", "com.bluestacks.gamecenter.ApkInstallResponseReceiver"));
        intent.putExtra("rval", rval);
        intent.putExtra("path", apkFilePath);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private void sendInstallApkProgressBroadcast(int isInstalling) {
        Intent intent = new Intent();
        intent.setAction("com.bluestacks.action.SHOW_INSTALL_VIEW");
        intent.setComponent(new ComponentName("com.uncube.launcher3", "com.bluestacks.launcher.receivers.BstAppInstallStartStopReceiver"));
        intent.putExtra("SHOW_INSTALL_LOADER", isInstalling);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    public void sendShowHideAppInLauncher(int show, String pkgName) {
        _sendShowHideAppInLauncher(show, pkgName);
    }

    public void sendShowHideAppInGameCenter(int show, String pkgName) {
        _sendShowHideAppInGameCenter(show, pkgName);
    }

    private void installApkClbk(String apkFileName, String attemptId, String source) {
        sendInstallApkProgressBroadcast(1);
        if (DBG) Log.d(TAG, "installApkClbk function called with apkFileName: " + apkFileName + " attemptId " + attemptId + " source " + source);
        int rval = _bstInstallApk(BST_SHARED_FOLDER_PATH + apkFileName, false, attemptId, source);
        Log.d(TAG, "apkFileName : " + apkFileName +  "return value : " + rval);
        sendInstallApkProgressBroadcast(0);
        //installStudioApkZipClbk("174f4452-c629-4aca-b1e9-e09228fb697a", "attempid", "dragdrop", "com.takeonecompany.bptg1");
    }

    private void installStudioApkZipClbk(String zipFolderName, String attemptId, String source, String pkgName) {
        if (DBG) Log.d(TAG, "installStudioApkZipClbk function called with zipFolderName: " + zipFolderName + " attemptId " + attemptId + " source " + source + " pkgName " + pkgName);
        String path = BST_SHARED_FOLDER_PATH + "/" + SystemProperties.get("bst.instance", "") + "/StudioZip/"  + zipFolderName;
        Log.d(TAG, "Path : " + path);
        int rval;
        FileWriter file = null;
        String fileToWrite = BstCommandProcessorApplication.getInstance().studioZipInstallFilePath + "/studio_app_install_config";
        try {
            JSONArray array;
            String fileContent = BstCommandProcessorUtils.readFile(fileToWrite);
            if (fileContent.equals(""))
                array = new JSONArray();
            else
                array = new JSONArray(fileContent);
            JSONObject obj = new JSONObject();
            obj.put("studio_app_path", path);
            obj.put("studio_app_package", pkgName);
            array.put(obj);
            BstCommandProcessorUtils.saveFile(fileToWrite, array.toString());
            JSONObject mapJSONObject = new JSONObject();
            mapJSONObject.put("attempt_id", attemptId);
            mapJSONObject.put("apk_file_name", zipFolderName);
            mapJSONObject.put("source", source);
            BstCommandProcessorApplication.getInstance().mStudioApkPkgMap.put(pkgName, mapJSONObject.toString());
            _sendShowHideAppInLauncher(0, pkgName);
            _sendShowHideAppInGameCenter(0, pkgName);
            //mBstHostCallManagerService.configVDNoFlush(true);
            SystemProperties.set("bst.install_studio_zip", "1");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void _sendShowHideAppInLauncher(int show, String pkgName) {
        Intent intent = new Intent();
        intent.setPackage("com.uncube.launcher3");
        intent.setAction("com.bluestacks.action.LAUNCHER_APP_SHOW_HIDE");
        intent.setComponent(new ComponentName("com.uncube.launcher3", "com.bluestacks.launcher.service.BstAppShowBroadcastReceiver"));
        intent.putExtra("show_icon", show);
        intent.putExtra("pkg_name", pkgName);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    // Notify the launcher a smart download started (it marks the pkg in-flight to show the loader).
    private void sendSmartDownloadStarted(String pkgName) {
        Intent intent = new Intent();
        intent.setAction("com.bluestacks.action.SMART_DOWNLOAD_STARTED");
        intent.setComponent(new ComponentName("com.uncube.launcher3", "com.bluestacks.launcher.receivers.SmartDownloadStartReceiver"));
        intent.putExtra("pkg_name", pkgName);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private void sendStopRecordingBroadCast() {
        Intent intent = new Intent();
        intent.setPackage("com.android.systemui");
        intent.setAction("com.bluestacks.action.STOP_RECORDING");
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private void sendSmartDownloadEnrollmentChanged(boolean enabled) {
        Intent intent = new Intent();
        intent.setAction("com.bluestacks.action.SMART_DOWNLOAD_ENROLLMENT_CHANGED");
        intent.setComponent(new ComponentName("com.uncube.launcher3", "com.bluestacks.launcher.receivers.SmartDownloadEnrollmentReceiver"));
        intent.putExtra("enabled", enabled);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private void _sendShowHideAppInGameCenter(int show, String pkgName) {
        Intent intent = new Intent();
        intent.setPackage("com.bluestacks.gamecenter");
        intent.setAction("com.bluestacks.action.GAME_CENTER_APP_SHOW_HIDE");
        intent.setComponent(new ComponentName("com.bluestacks.gamecenter", "com.bluestacks.gamecenter.broadcast.AppShowHideReceiver"));
        intent.putExtra("show_icon", show);
        intent.putExtra("pkg_name", pkgName);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private int uninstallAppClbk(String packageName) {
        Log.d(TAG, "uninstallAppClbk packageName = " + packageName);
        int rval = 0;
        if (!_uninstallApp(packageName)) {
            Log.e(TAG, "uninstallAppClbk: Failed to uninstall package: " + packageName);
            rval = -1;
        }
        return rval;
    }

    public int stopAppPackage(String packageName) {
        return stopAppClbk(packageName);
    }

    private int stopAppClbk(String packageName) {
        Log.d(TAG, "stopAppClbk packageName = " + packageName);
        int rval = 0;
        if (!stopApp(packageName)) {
            Log.e(TAG, "stopAppClbk: Failed to stop package: " + packageName);
            rval = -1;
        }
        return rval;
    }

    private void takeScreenshotClbk() {
        if (DBG) Log.d(TAG, "takeScreenshotClbk function called");
        try
        {
            BstCommandProcessorUtils.injectKeyEvent(BstCommandProcessorUtils.genKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYSRQ, 0));
            BstCommandProcessorUtils.injectKeyEvent(BstCommandProcessorUtils.genKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYSRQ, 0));
        } catch(Exception e) {
            Log.e(TAG,"error in taking screenshot : " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int _executeLaunchActivity(String pkgName, String activity, String extras) {
        if (DBG) Log.d(TAG, "_executeLaunchActivity function called, pkgName: " + pkgName + " activity: " + activity+ " extras: " + extras);
        SystemProperties.set("bst.accounts.package", pkgName);
        String className = getClassNameFromActivity(activity);
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        intent.setComponent(new ComponentName(pkgName, className));
        intent.addCategory("android.intent.category.LAUNCHER");
        if (extras != null && !extras.equals("")) {
            try {
                JSONObject object = new JSONObject(extras);
                Iterator<String> keys = object.keys();
                while(keys.hasNext()) {
                    String key = keys.next();
                    if (object.get(key) instanceof String) {
                        intent.putExtra(key, (String) object.get(key));
                    }
                    if (object.get(key) instanceof Integer) {
                        intent.putExtra(key, (Integer) object.get(key));
                    }
                    if (object.get(key) instanceof Boolean) {
                        intent.putExtra(key, (Boolean) object.get(key));
                    }
                    if (object.get(key) instanceof Long) {
                        intent.putExtra(key, (Long) object.get(key));
                    }
                    if (object.get(key) instanceof Float) {
                        intent.putExtra(key, (Float) object.get(key));
                    }
                    if (object.get(key) instanceof Double) {
                        intent.putExtra(key, (Double) object.get(key));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        int rval = -1;
        if (pkgName.equals("com.bluestacks.quest") || pkgName.equals("com.bluestacks.filemanager")) {
            SystemProperties.set("bst.config.calling_package", "com.bluestacks.BstCommandProcessor");
            boolean launchSuccess = BstCommandProcessorUtils.launchIntentAndWait(mContext, intent);
            if (!launchSuccess) {
                Log.e(TAG, "launchIntentAndWait failed for package: " + pkgName);
            }
            handleGcallCmdResponse("launchActivityClbk", launchSuccess);
            return launchSuccess ? 0 : -1;
        }

        //Case ROB-2964 : in case of set location dont go back to launcher instead go back to the previous app
        if (!pkgName.equals("com.location.provider"))
            intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME);

        try {
            //XXX: Assuming this call comes once system is in ready state.
            if (DBG) Log.d(TAG, "trying to launch an app " + pkgName + " intent: " + intent);

            int taskId = -1;

            final List<ActivityManager.RecentTaskInfo> recentTasks =
                mActivityManager.getRecentTasks(50, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

            int numTasks = recentTasks.size();
            if (DBG) Log.d(TAG, "recentTask.size(): " + numTasks);

            for (int i = 0; i < numTasks; i++) {
                ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);

                if (recentInfo.baseIntent.getComponent().getPackageName().equals(pkgName)) {
                    Log.d(TAG, "pkg found in recent tasks");
                    taskId = recentInfo.id;
                    break;
                }
            }

            SystemProperties.set("bst.config.calling_package", "com.bluestacks.BstCommandProcessor");
            boolean launchSuccess = false;
            if (taskId >= 0) {
                // This is an active task; it should just go to the foreground.
                Log.d(TAG, "moving " + taskId + " to front");
                mActivityManager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME);
                launchSuccess = true;
            } else {
                launchSuccess = BstCommandProcessorUtils.launchIntentAndWait(mContext, intent);
                if (!launchSuccess) {
                    Log.e(TAG, "launchIntentAndWait failed for package: " + pkgName);
                }
            }
            handleGcallCmdResponse("launchActivityClbk", launchSuccess);
            rval = launchSuccess ? 0 : -1;
        } catch (Exception e) {
            Log.e(TAG, "Exception while trying to launch pkg: " + pkgName + " using activity: " + activity);
            handleGcallCmdResponse("launchActivityClbk", false);
            rval = -1;
        }
        return rval;
    }

    private int launchActivityClbk(String pkgName, String activity, String extras) {
        if (DBG) Log.d(TAG, "launchActivityClbk function called, pkgName: " + pkgName + " activity: " + activity+ " extras: " + extras);
        return _executeLaunchActivity(pkgName, activity, extras);
    }

    private int reLaunchActivityClbk(String pkgName, String activity, String extras) {
        if (DBG) Log.d(TAG, "reLaunchActivityClbk function called, pkgName: " + pkgName + " activity: " + activity+ " extras: " + extras);
        // 1. Call the existing public stop method
        stopAppClbk(pkgName);

        // 2. Schedule delayed launch to avoid race conditions, as stop is async.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (DBG) Log.d(TAG, "Executing delayed launch for " + pkgName);
                launchActivityClbk(pkgName, activity, extras);
            }
        }, 500); // 500ms delay
        return 0;
    }

    private void launchUrlClbk (String url) {
        if (DBG) Log.d(TAG, "launchUrlClbk function called, url: " + url);
        try {
            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(uri);
            List<ResolveInfo> infos = mService.getPackageManager().queryIntentActivities(intent, 0);
            if (infos != null && infos.size() > 0) {
                String packageName = infos.get(0).activityInfo.packageName;
                ComponentName cn = new ComponentName(packageName, infos.get(0).activityInfo.name);
                for (ResolveInfo info : infos) {
                    packageName = info.activityInfo.packageName;
                    if (DBG) Log.d(TAG, "activityname : " + info.activityInfo.name + ", packageName = " + packageName);
                    if (packageName != null && packageName.equals("com.android.chrome")) {
                        cn = new ComponentName(packageName, info.activityInfo.name);
                        break;
                    }
                }
                if (DBG) Log.d(TAG, "componentName to set in intent = " + cn);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                intent.setComponent(cn);
                mService.startActivityAsUser(intent, Process.myUserHandle());
            } else {
                Log.e(TAG, "No activity resolved for intent : " + intent);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception while executing URL : " + url);
            if (DBG) ex.printStackTrace();
        }
    }

    private void importFilesClbk (String folder) {
        if (DBG) Log.d(TAG, "importFilesClbk function called, folder: " + folder);
        Intent intent = new Intent();
        ComponentName cn = new ComponentName("com.bluestacks.filemanager",
                "com.bluestacks.filemanager.service.GetFilesFromWindowsService");
        intent.setComponent(cn);
        intent.putExtra("folder", folder);
        mService.startServiceAsUser(intent, Process.myUserHandle());
    }

    private void exportFilesClbk(String folder) {
        if (DBG) Log.d(TAG, "exportFilesClbk function called, folder: " + folder);
        Intent intent = new Intent();
        ComponentName cn = new ComponentName("com.bluestacks.filemanager",
                "com.bluestacks.filemanager.service.GetExporFolderFromWindowsService");
        intent.setComponent(cn);
        intent.putExtra("folder", folder);
        mService.startServiceAsUser(intent, Process.myUserHandle());
    }

    private int enableAdbClbk(boolean enable) {
        if (DBG) Log.d(TAG, "enableAdbClbk function called, enable : " + enable);
        SystemProperties.set("bst.enable_adb_access", enable ? "1" : "0");
        return 0;
    }

    private void setClipboardClbk(String text) {
        if (DBG) Log.d(TAG, "setClipboardClbk function called");
        //Open ClipboardManager and set text to it..
        try {
            ClipData clip = ClipData.newPlainText("simpleText", text);
            mBstClipboardManager.setPrimaryClip(clip);
        } catch (Exception e) {
            Log.e (TAG, "Error in setting text for Clipboard : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getClipboardContent()
    {
        ClipData clipData = mBstClipboardManager.getPrimaryClip();
        if (clipData != null && clipData.getItemCount() > 0) {
            CharSequence text = clipData.getItemAt(0).getText();
            if (text != null) {
                return text.toString();
            }
        }
        return null;
    }

    private void setLocalTimeClbk(long msecFromEpoch, String timeZone) {
        SystemProperties.set("bst.host_timezone", timeZone);
        boolean auto_time_zone_enabled = Settings.Global.getInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.Global.AUTO_TIME_ZONE, 0) == 1;
        Log.d(TAG, "setLocalTimeClbk msecFromEpoch = " + msecFromEpoch + ", timeZone = " + timeZone+", auto_time_zone_enabled = "+ auto_time_zone_enabled);
        AlarmManager alarm = (AlarmManager)BstCommandProcessorApplication.getInstance().getSystemService(Context.ALARM_SERVICE);
        try {
            if (auto_time_zone_enabled)
                alarm.setTimeZone(timeZone);
        } catch (Exception ex) {
            Log.w(TAG,"Exception in setting timezone, setting defult TimeZone now, error message : " + ex.getMessage());
            alarm.setTimeZone("Asia/Calcutta");
        }

        try {
            alarm.setTime(msecFromEpoch);
        } catch (Exception ex) {
            Log.w(TAG, "Exception while setting time, error message : " + ex.getMessage());
            if (DBG) ex.printStackTrace();
        }
    }

    private int clearAppDataClbk(String packageList) {
        int rval = 0;
        boolean status;
        Log.d(TAG, "clearAppDataClbk packageList = " + packageList);
        String[] packages = packageList.split(",");
        for (String pkg : packages) {
            if(!BstCommandProcessorUtils.initiateClearUserData(pkg)) {
                Log.w(TAG, "Failed to clear app data for package: " + pkg);
                rval = -1;
            }
        }
        return rval;
    }

    private void launchAppStoreClbk(String store, String packageName, String extraData, String source) {
        //startUrlTracking(packageName, source);
        // Smart download (host gcall source tag): tell the launcher the package so it shows the loader.
        if ("smart_downloads".equals(source)) {
            sendSmartDownloadStarted(packageName);
        }
        if (OEM.equals("nxt_cn") && store.equals("com.bluestacks.gamecenter")) {
            launchGameCenter(packageName, extraData, "ApkInstallation", false, source);
            return;
        }
        String response = mBstUtilsManager.getApkDownloadSource(packageName, "new");
        _launchRequiredAppStore(store, packageName , extraData, response, false, source);
    }

    private void setVolumeClbk(boolean mute, int volume) {
        Log.d(TAG, "setVolumeClbk mute = " + mute + ", volume = " + volume);
        setVolume(mute, volume);
    }

    private void setGamepadStateClbk(boolean state) {
        Log.d(TAG, "setGamepadStateClbk state = " + state);
        Intent intent = new Intent();
        intent.setAction("com.bluestacks.action.GAMEPAD_STATE");
        intent.setComponent(new ComponentName("com.bluestacks.consolemode", "com.bluestacks.consolemode.GamepadStateReceiver"));
        intent.putExtra("state", state);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private void enableClickSoundClbk(boolean enable) {
        if (DBG) Log.d(TAG, "enableClickSoundClbk function called with enable: " + enable);
        if (enable) {
            Settings.System.putInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1);
            Log.i(TAG, "enabled click Sound");
        } else {
            Settings.System.putInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 0);
            Log.i(TAG, "disabled click Sound");
        }
    }

    private void showNativeMousePointerClbk(boolean show) {
        if (DBG) Log.d(TAG, "showNativeMousePointerClbk function called with show: " + show);
            // A16: InputManager.getInstance()/bstReloadPointerIcon() were removed (split into
            // InputManagerGlobal + PointerIconCache, no public reload API). Components that
            // observe the mouse pointer read the prop below, so setting it is sufficient here.
            SystemProperties.set("bst.config.show_mouse_ptr", show ? "true" : "false");
    }

    private void affiliateTrackingForPackageClbk(String pkgName, String source, String attemptId) {
        Log.d(TAG, "affiliateTrackingForPackageClbk pkgName = " + pkgName + " source = " + source + " attemptId = " + attemptId);
        Intent intent = new Intent();
        intent.setPackage("com.bluestacks.home");
        intent.setAction("com.bluestacks.home.AFFILIATE_HANDLER_HTML");
        intent.putExtra("app_pkg", pkgName);
        intent.putExtra("from_bsx", true);
        intent.putExtra("google_account", true);
        intent.putExtra("WINDOWS_SOURCE", source);
        intent.putExtra("attempt_id", attemptId);
        mService.startServiceAsUser(intent, Process.myUserHandle());
    }

    private void launchAppStoreSearchClbk(String store, String query) {
        Log.d(TAG, "launchAppStoreSearchClbk store = " + store + ", query = " + query);
        if (store == null) {
            Log.d(TAG, "store is null, using com.android.vending as store");
            store = "com.android.vending";
        }

        if (!isMarketInstalled(store)) {
            Log.w(TAG, "Market not installed yet, retry search after installing the same");
            return;
        }

        int result;
        switch (store.trim().toLowerCase()) {
            case "com.android.vending":
                result = searchOnGooglePlay(query);
                break;
            default:
                Log.e(TAG, "Error: invalid store: " + store);
                return;
        }
        if (result < 0) {
            Log.w(TAG, "Failed to search query: " + query + ", in store: " + store);
        }
        return;
    }

    private void getNowggAccountsClbk() {
        Log.d(TAG, "getNowggAccountsClbk");
        try {
            Intent intent = new Intent();
            intent.setPackage("gg.now.accounts");
            intent.setComponent(new ComponentName("gg.now.accounts", "gg.now.accounts.service.SendRefreshTokenHostService"));
            intent.setAction("SEND_REFRESHTOKEN_HOST");
            BstCommandProcessorApplication.getInstance().getAppContext().startServiceAsUser(intent, Process.myUserHandle());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addNowggAccountClbk(String accountInfoJson) {
        Log.d(TAG, "addNowggAccountClbk");
        try {
            JSONObject jsonObject = new JSONObject(accountInfoJson);
            String refreshToken = jsonObject.getString("refreshToken");
            String email = jsonObject.getString("email");
            Intent intent = new Intent();
            intent.setPackage("gg.now.accounts");
            intent.setComponent(new ComponentName("gg.now.accounts", "gg.now.accounts.service.AuthCodeService"));
            intent.setAction("REFRESH_TOKEN");
            intent.putExtra("refreshToken", refreshToken);
            intent.putExtra("email", email);
            BstCommandProcessorApplication.getInstance().getAppContext().startServiceAsUser(intent, Process.myUserHandle());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeNowggAccountClbk(String accountName) {
        Log.d(TAG, "removeNowggAccountClbk accountName: " + accountName);
        AccountManager accountManager = AccountManager.get(BstCommandProcessorApplication.getInstance().getAppContext());
        Account[] accounts = accountManager.getAccountsByType(NOWGG_ACCOUNT_TYPE);
        if (accounts.length == 0) {
            Log.d(TAG, "No account found on the device for account type: " + NOWGG_ACCOUNT_TYPE);
            return;
        }
        for (Account account : accounts) {
            if (account.name.equals(accountName)) {
                accountManager.removeAccount(account, null, null);
                break;
            }
        }
    }

    private void enableSignInPopupClbk(boolean enable) {
        Log.d(TAG, "enableSignInPopupClbk enable : " + enable);
        SystemProperties.set("bst.enable_sigin_gamelaunch", enable ? "1" : "0");
        return;
    }

    private void setDifferentImagePkgsClbk(String file) {
        Log.d(TAG, "setDifferentImagePkgsClbk file: " + file);
        if (file == null || file.isEmpty()) {
            Log.e(TAG, "setDifferentImagePkgsClbk called with null or empty file string.");
            return;
        }
        File inFile = new File(BST_SHARED_FOLDER_PATH + "/" + file);
        File outFile = new File("/data/downloads/.different_image_pkgs");
        boolean isCopyFile = copyFile(inFile, outFile);
        if (isCopyFile) {
            if (FileUtils.setPermissions(outFile,
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                        FileUtils.S_IRGRP | FileUtils.S_IROTH | FileUtils.S_IWGRP | FileUtils.S_IWOTH, -1, -1) != 0)
            {
                Log.e(TAG, "Failed to change permissions for the file: " + outFile.getPath());
            }
        }
    }

    private void setCustomAppOrientationClbk(String gameSettingJson) {
        Log.d(TAG, "setCustomAppOrientationClbk  file: " + gameSettingJson);
        String filePath = "/data/downloads/.app.settings";
        HashMap<String, String> gameSettingMap = BstCommandProcessorUtils.readAppSettingsFile(filePath);
        try {
            JSONArray array = new JSONArray(gameSettingJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = (JSONObject) array.get(i);
                String pkgName = object.getString("packageName");
                String mode = object.getString("orientation");
                if (mode.equalsIgnoreCase("landscape"))
                    gameSettingMap.put(pkgName, "full");
                else if (mode.equalsIgnoreCase("portrait"))
                    gameSettingMap.put(pkgName, "small");
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        BstCommandProcessorUtils.writeAppSettingsFile(filePath, gameSettingMap);
        mBstHostCallManagerService.onCustomAppOrientationCompleted(gameSettingJson);
    }

    private void setAirplaneModeClbk(boolean enable) {
        Log.d(TAG, "setAirplaneModeClbk enable : " + enable);
        SystemProperties.set("bst.airplane_mode_active", enable ? "1" : "0");
    }

    private void startRecordingClbk(boolean start) {
        Log.d(TAG, "startRecordingClbk start : " + start);
        SystemProperties.set("bst.config.getevents", start ? "1" : "0");
    }

    private void startUiDumpClbk() {
        Log.d(TAG, "startUiDumpClbk");
        try {
        UiAccessibilityService uiService = UiAccessibilityService.getInstance();
        if (uiService == null) {
            Log.w(TAG, "UI Accessibility Service instance not found");
            return;
        }
        String uiDump = uiService.getUiDump(false);
        String fileName = ".ui_dump_" + SystemProperties.get("bst.instance", "") + "_" + System.currentTimeMillis() + ".json";
        String filePath = BST_SHARED_FOLDER_PATH + fileName;
        BstCommandProcessorUtils.saveFile(filePath, uiDump);
        int rval = mBstHostCallManagerService.onUiDumpCompleted(fileName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Interface for input actions.
     * Used to wrap operations (tap, swipe, text) for deferred execution after UI stability checks.
     */
    public interface InputAction {
        boolean run();
    }

    /**
     * Waits for the UI to stabilize before executing the specified input action.
     * @param commandName The name of the command (used for logging and responses).
     * @param action      The specific input logic (returns a boolean indicating success).
     */
    private void executeWhenUiStable(String commandName, InputAction action) {
        Log.d(TAG, "Submitting task to executor: " + commandName);

        try {
             Log.i(TAG, "Wait for UI Quiet...");
             boolean isStable = UiAutomationExecutor.waitForGlobalUiQuiet(QUIET_DURATION_MS, TIMEOUT_MS, POLL_INTERVAL_MS);
             Log.i(TAG, "UI Stable result: " + isStable);

             boolean result = false;
             if (isStable) {
                 result = action.run();
             } else {
                 Log.w(TAG, "UI unstable, skipping execution");
             }

             handleGcallCmdResponse(commandName, result);
        } catch (Exception e) {
             Log.e(TAG, commandName + " execution failed", e);
             handleGcallCmdResponse(commandName, false);
        }
    }

    private void inputSwipeCommandClbk(int mStartX, int mStartY, int mEndX, int mEndY, int mDurationMsecs) {
        Log.d(TAG, "inputSwipeCommandClbk: mStartX=" + mStartX + ", mStartY=" + mStartY + ", mEndX=" + mEndX + ", mEndY=" + mEndY);
        executeWhenUiStable("swipe", () -> InputUtils.swipe(mStartX, mStartY, mEndX, mEndY, mDurationMsecs));
    }

    private void inputTapCommandClbk(int mX, int mY) {
        Log.d(TAG, "inputTapCommandClbk: mX=" + mX + ", mY=" + mY);
        executeWhenUiStable("tap", () -> InputUtils.tap(mX, mY));
    }

    private void inputPressKeyCommandClbk(int keyCode) {
        Log.d(TAG, "inputPressKeyCommandClbk: keyCode=" + keyCode);
        executeWhenUiStable("press_key", () -> InputUtils.pressKey(keyCode));
    }

    private void inputSetTextCommandClbk(String text) {
        Log.d(TAG, "inputSetTextCommandClbk: text=" + text);
        executeWhenUiStable("input_text", () -> InputUtils.setText(text));
    }

    private void enableAndroidAdsClbk(boolean enable, String extraData) {
        Log.d(TAG, "enableAndroidAds enable : " + enable + ", extraData : " + extraData);
        Intent intent = new Intent();
        intent.setAction("com.bluestacks.action.ENABLE_ANDROID_ADS");
        intent.setComponent(new ComponentName("com.uncube.launcher3", "com.bluestacks.launcher.receivers.GcallEnableAndroidAdsReceiver"));
        intent.putExtra("enable", enable);
        intent.putExtra("extraData", extraData);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private void androidInterstitialAdSettingClbk(String data) {
        Log.d(TAG, "androidInterstitialAdSettingClbk data: " + data);
        try {
            JSONObject dataJSON = new JSONObject(data);
            if (dataJSON.has("show_interstitial")) {
                boolean showInterstitial = dataJSON.getBoolean("show_interstitial");
                SystemProperties.set("bst.launcher.show_interstitial", String.valueOf(showInterstitial));
            }

            if (dataJSON.has("timeout_msecs")) {
                int timeout = dataJSON.getInt("timeout_msecs");
                SystemProperties.set("bst.launcher.timeout_msecs", String.valueOf(timeout));
            }

        } catch (JSONException e) {
            Log.d(TAG, "invalid json exception in androidInterstitialAdSettingClbk");
        }
    }

    private void commonCommandClbk(int code, String name, String data) {
        // creating just a function as we currently don't support split screen in android 13
        switch (code) {
            case BstCommandCCcodes.GCALL_CC_ShowNowggSignInPopUp:
                handleShowNowggSignInPopUp(name, data);
                break;
            case BstCommandCCcodes.GCALL_CC_GPAppInstall:
                handleGPAppInstall(name);
                break;
            case BstCommandCCcodes.GCALL_CC_AutoExecutor:
                handleAutoExecutor(name);
                break;
            case BstCommandCCcodes.GCALL_CC_SetSmartDownloadEnabled:
                Log.d(TAG, "commonCommandClbk SetSmartDownloadEnabled: " + name);
                boolean smartDownloadEnabled = "1".equals(name);
                SystemProperties.set("bst.enable_smart_downloads", smartDownloadEnabled ? "1" : "0");
                sendSmartDownloadEnrollmentChanged(smartDownloadEnabled);
                break;
            default:
                break;
        }
    }

    private void onUnzipFileCompletedClbk(int code, String folderName, String attemptId, String source, String pkgName) {
        Log.d(TAG, "onUnzipFileCompletedClbk code=%d, folderName: " + folderName + " attemptId: " + attemptId + " source: " + source + " pkgName: " + pkgName);
        installStudioApkZipClbk(folderName, attemptId, source, pkgName);
    }

    private void startInstallAppGameCenterClbk(String pkgName) {
        Log.d(TAG, "startInstallAppGameCenterClbk pkgName: " + pkgName);
        Intent intent = new Intent();
        intent.setPackage("com.bluestacks.gamecenter");
        intent.setAction("com.bluestacks.action.GAME_CENTER_INSTALL_APP");
        intent.setComponent(new ComponentName("com.bluestacks.gamecenter", "com.bluestacks.gamecenter.broadcast.InstallAppReceiver"));
        intent.putExtra("pkg_name", pkgName);
        BstCommandProcessorApplication.getInstance().getAppContext().sendBroadcastAsUser(intent, Process.myUserHandle());
    }

    private void handleShowNowggSignInPopUp(String pkgName, String data) {
        Log.d(TAG, "handleShowNowggSignInPopUp pkgName: " + pkgName + ", data: " + data);
        try {
            JSONObject rootObj = new JSONObject(data);
            String action = rootObj.optString("action", "launch");
            boolean showSkip = rootObj.optBoolean("showSkip", false);

            Intent intent = new Intent();
            intent.setPackage("com.bluestacks.home");
            intent.setAction("com.bluestacks.home.action.ACTION_SHOW_NOWGG_APP_SIGNIN_POPUP");
            intent.putExtra("app_pkg", pkgName);
            intent.putExtra("app_label", "");
            intent.putExtra("req_from", "bstcommandLoop");
            intent.putExtra("show_skip", showSkip);
            intent.putExtra("nowgg_action_type", action);
            BstCommandProcessorApplication.getInstance().getAppContext().startServiceAsUser(intent, Process.myUserHandle());
        } catch (Exception e) {
            Log.e(TAG, "Exception while handleShowNowggSignInPopUp : "+ e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleGPAppInstall(String pkgName) {
        Log.d(TAG, "handleGPAppInstall pkgName: " + pkgName);
        if(GPAutoInstallHelper.topAppIsGooglePlay()) {
            // Go back to home screen only if Google Play is on top
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mService.startActivityAsUser(homeIntent, Process.myUserHandle());
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse("market://details?id=" + pkgName));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mService.startActivityAsUser(intent, Process.myUserHandle());
                        GPAutoInstallHelper.doClickInstall(pkgName);
                    }
            }, 1000);
        } else {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("market://details?id=" + pkgName));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mService.startActivityAsUser(intent, Process.myUserHandle());
            GPAutoInstallHelper.doClickInstall(pkgName);
        }
    }
    private void handleAutoExecutor(String json) {
        Future<UiAutomationExecutor.AutomationResult> future = UiAutomationExecutor.executeAsync(json);
        CompletableFuture.supplyAsync(() -> {
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("fail:", e);
            }
        }).thenAccept(result -> {
            Log.d(TAG, "result:" + result.status);

            if ("FAILED".equals(result.status)) {
                Log.e(TAG, "failed_step_info:" + result.failed_step_info);
                Log.e(TAG, "failure_reason:" + result.failure_reason);
            }
            BstHostCallManager hCallManager = (BstHostCallManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_HOST_CALL);
            hCallManager.commonCommand(8, result.status, "info:"+result.failed_step_info+",reason:"+result.failure_reason);
        }).exceptionally(ex -> {
            Log.e(TAG, "error:" + ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }

    private void handleGcallCmdResponse(String action, boolean ret) {
        BstHostCallManager hCallManager = (BstHostCallManager) BstCommandProcessorApplication
            .getInstance().getSystemService(Context.BST_HOST_CALL);

        if (hCallManager == null) {
            Log.e(TAG, "Failed to obtain BstHostCallManager for action: " + action +
                "; cannot send result back to HD side!");
            return;
        }

        String status = ret ? "SUCCESS" : "FAILED";
        String message = action + " execution " + (ret ? "success." : "failed.");

        hCallManager.commonCommand(HCALL_CC_hcallGcallCmdResponse, status, message);
    }

    // Persist Smart Downloads opt-in to host conf bst.enable_smart_downloads (host writes + commits); name = "1"/"0".
    public void setSmartDownloadEnabled(boolean enabled) {
        Log.d(TAG, "setSmartDownloadEnabled enabled = " + enabled);
        // Keep the guest's boot-synced sysprop fresh too, so other guest readers (or a launcher
        // restart) see the new state without waiting for the next boot sync.
        SystemProperties.set("bst.enable_smart_downloads", enabled ? "1" : "0");
        mBstHostCallManagerService.commonCommand(HCALL_CC_hcallSetSmartDownloadEnabled, enabled ? "1" : "0", "");
    }

    /**
     *
     * Callback Helper functions.
     *
     */

    private int installApk(String apkFilePath) {
        int rval = -1;
        int result = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        int installFlags = PackageManager.INSTALL_REPLACE_EXISTING | PackageManager.INSTALL_INTERNAL | PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;


        if (DBG) Log.d(TAG, "pkg to install: " + apkFilePath);
        if (apkFilePath == null) {
            Log.e(TAG, "Error: Syntax error, no package specified");
            mApkInstallResponse.response = -1;
            mApkInstallResponse.errorString = "no package specified";
            return rval;
        }

        File pkg = new File(apkFilePath);
        if (!pkg.exists() || !pkg.canRead()) {
            Log.e(TAG, "installApk: cannot read apk at " + apkFilePath);
            mApkInstallResponse.response = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            mApkInstallResponse.errorString = "apk not readable: " + apkFilePath;
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }


        File[] files = new File[1];
        files[0] = pkg;

        result = apkInstallCore(files, files[0].getParent() + "/", installFlags);

        // Delete the temp apk file in any case
        if (pkg != null) {
            pkg.delete();
        }

        return result;
    }

    private int installXapk(String xapkFilePath)
    {
        int result = XApk.INSTALL_FAILED_INVALID_XAPK;
        mApkInstallResponse.response = XApk.INSTALL_FAILED_INVALID_XAPK;
        XApk xApk = new XApk();
        boolean backupOldObbDir = false;

        File xapkFile = new File(xapkFilePath);
        if (!xapkFile.exists()) {
            result = XApk.INSTALL_FAILED_APK_EXTRACTION_FAILED;
            mApkInstallResponse.errorString = "INSTALL_FAILED_APK_EXTRACTION_FAILED";
            return result;
        }
        String tempApkPath = BstCommandProcessorPath + "/xapk-install/";
        File tempApkFolder = new File(tempApkPath, "apks");

        if (tempApkFolder.exists()) {
            boolean deleteResult = FileUtils.deleteContents(tempApkFolder);
            if (!deleteResult) {
                result = XApk.INSTALL_FAILED_APK_EXTRACTION_FAILED;
                mApkInstallResponse.errorString = "INSTALL_FAILED_APK_EXTRACTION_FAILED";
                return result;
            }
        } else {
            boolean dirResult = tempApkFolder.mkdirs();
            if (!dirResult) {
                result = XApk.INSTALL_FAILED_APK_EXTRACTION_FAILED;
                mApkInstallResponse.errorString = "INSTALL_FAILED_APK_EXTRACTION_FAILED";
                return result;
            }
        }

        try {
            xApk.xapkFile = new ZipFile(xapkFilePath);
            final ZipEntry entry = xApk.xapkFile.getEntry("manifest.json");
            if (entry == null) {
                Log.d(TAG, "installXapk: unable to find manifest file");
                mApkInstallResponse.errorString = "manifest.json not found";
                return result;
            }
            xApk.xApkManifest = parseXApkManifest(xApk.xapkFile.getInputStream(entry));
            if (xApk.xApkManifest == null) {
                Log.d(TAG, "installXapk: unable to parse manifest file");
                mApkInstallResponse.errorString = "unable to parse manifest file";
                return result;
            }

            // move to orig
            backupOldObbDir = backupOldObbDir(xApk.xApkManifest.packageName);

            result = installExpansions(xApk.xApkManifest, xApk.xapkFile);
            if (result == XApk.INSTALL_EXPANSION_SUCCEEDED) {
                for (int i = 0; i < xApk.xApkManifest.splitApks.size(); i++) {

                    String apkToExtract = xApk.xApkManifest.splitApks.get(i).file;

                    File apkFile = new File(tempApkFolder, apkToExtract);

                    result = extractApk(apkToExtract, apkFile, xApk);

                    if (result == XApk.INSTALL_APK_EXTRACTION_SUCCEEDED) {
                        FileUtils.setPermissions(apkFile,
                                FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                                        FileUtils.S_IRGRP | FileUtils.S_IROTH | FileUtils.S_IWGRP | FileUtils.S_IWOTH, -1, -1);
                    } else {
                        Log.d(TAG, "installXapk: extractApk result " + XApk.installStatusToString(result));
                        mApkInstallResponse.errorString = XApk.installStatusToString(result);
                        return result;
                    }
                }
                File baseAPk = new File(tempApkFolder, xApk.xApkManifest.packageName + ".apk");
                if (!baseAPk.exists()) {
                    result = extractApk(xApk.xApkManifest.packageName + ".apk", baseAPk, xApk);

                    if (result == XApk.INSTALL_APK_EXTRACTION_SUCCEEDED) {
                        FileUtils.setPermissions(baseAPk,
                                FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                                        FileUtils.S_IRGRP | FileUtils.S_IROTH | FileUtils.S_IWGRP | FileUtils.S_IWOTH, -1, -1);
                    } else {
                        Log.d(TAG, "installXapk: extractApk result " + XApk.installStatusToString(result));
                        mApkInstallResponse.errorString = XApk.installStatusToString(result);
                        return result;
                    }
                }
                if (result == XApk.INSTALL_APK_EXTRACTION_SUCCEEDED) {
                    XApkManifest bstXApkManifest = null;
                    int assetsResult = XApk.INSTALL_EXPANSION_SUCCEEDED;
                    final ZipEntry bstEntry = xApk.xapkFile.getEntry("bst_assets.json");
                    if (bstEntry != null) {
                        Log.d(TAG, "installXapk: install bst_assets!");
                        bstXApkManifest = bstParseXApkManifest(xApk.xapkFile.getInputStream(bstEntry));
                        if (bstXApkManifest != null) {
                            assetsResult = bstInstallAssetsTmp(bstXApkManifest, xApk.xapkFile);
                            if (assetsResult != XApk.INSTALL_EXPANSION_SUCCEEDED)  {
                                Log.w(TAG, "install assets to temp fail!");
                                mApkInstallResponse.errorString = "install assets to temp fail";
                            }
                        }
                    }

                    if (assetsResult == XApk.INSTALL_EXPANSION_SUCCEEDED) {
                        if (xApk.xApkManifest.splitApks.size() == 0)
                            result = installApk(baseAPk.getAbsolutePath());
                        else
                            result = installSplitApk(tempApkFolder.getParent());

                        if (result == PackageManager.INSTALL_SUCCEEDED && bstXApkManifest != null) {
                                // If there is an error in this process we ignore it and let the process continue,
                                // because the apk has been installed at this stage.
                            assetsResult = bstMoveTmpAssets(bstXApkManifest);
                            if (assetsResult != XApk.INSTALL_EXPANSION_SUCCEEDED) {
                                Log.w(TAG, "move bst assets with unexpected result!");
                                mApkInstallResponse.errorString = "move bst assets with unexpected result";
                            }
                        }
                    }

                    if (bstXApkManifest != null) {
                        bstRemoveTmpAssets(bstXApkManifest);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if(xApk.xapkFile != null)
                    xApk.xapkFile.close();
                FileUtils.deleteContentsAndDir(tempApkFolder);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (result != PackageManager.INSTALL_SUCCEEDED) { // &&  result != PackageManager.INSTALL_FAILED_ALREADY_EXISTS) {
            cleanUpExpansionFiles(xApk.xApkManifest);
            restoreOldObbDir(xApk.xApkManifest.packageName);
        } else {
            //delete orig
            if (backupOldObbDir)
                removeOldObbDir(xApk.xApkManifest.packageName);
        }
        return result;
    }

    // Function for installing split apks, Provide apkFolder Path and JsonObject to store results.
    // return value is code retuened from PackageManager.
    private int installSplitApk(String apkFolderPath)
    {
        int result = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        if (DBG) Log.d(TAG, "pkg to install is present in : " + apkFolderPath);

        if (apkFolderPath == null) {
            Log.e(TAG, "Error: Syntax error, no package specified");
            return result;
        }

        int installFlags = PackageManager.INSTALL_REPLACE_EXISTING | PackageManager.INSTALL_INTERNAL;

        File folder = new File(apkFolderPath);
        // Assuming split apk dir to be present as the only file in the folder supplied.
        File apkDir =  folder.listFiles()[0];
        if (DBG) Log.d(TAG, "pkg to install is present in : " + apkDir.getAbsolutePath());

        File[] listOfFiles = apkDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getAbsolutePath().endsWith(".apk");
            }
        });

        return apkInstallCore(listOfFiles, apkDir.getPath() +"/", installFlags);

        //TODO: Add folder delete after installing the same
    }

    private boolean backupOldObbDir(String packageName)
    {
        File externalStorageDirectory = Environment.getExternalStorageDirectory();
        File file = new File(externalStorageDirectory, String.format("Android/obb/%s", packageName));
        File renamedFile = new File(externalStorageDirectory, String.format("Android/obb/%s%s", packageName, "_orig"));

        try {
            if(file.exists())
                return file.renameTo(renamedFile);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }


    private boolean removeOldObbDir(String packageName)
    {
        File externalStorageDirectory = Environment.getExternalStorageDirectory();
        File file = new File(externalStorageDirectory, String.format("Android/obb/%s%s", packageName, "_orig"));

        try {
            return FileUtils.deleteContentsAndDir(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean restoreOldObbDir(String packageName)
    {
        File externalStorageDirectory = Environment.getExternalStorageDirectory();
        File origFile = new File(externalStorageDirectory, String.format("Android/obb/%s%s", packageName, "_orig"));
        File file = new File(externalStorageDirectory, String.format("Android/obb/%s", packageName));

        try {
            if(origFile.exists())
                return origFile.renameTo(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    private XApkManifest parseXApkManifest(final InputStream inputStream) {
        XApkManifest xApkManifest = new XApkManifest();

        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String read;

            while ((read = br.readLine()) != null) {
                sb.append(read);
            }
            br.close();

            JSONObject jsonObject = new JSONObject(sb.toString());
            xApkManifest.Label = jsonObject.optString("name");
            xApkManifest.minSdkVersion = jsonObject.optString("min_sdk_version");
            xApkManifest.packageName = jsonObject.optString("package_name");
            xApkManifest.versionName = jsonObject.optString("version_name");
            xApkManifest.versionCode = jsonObject.optString("version_code");
            xApkManifest.targetSdkVersion = jsonObject.optString("target_sdk_version");
            xApkManifest.totalSize = jsonObject.optLong("total_size");

            JSONArray expansions = jsonObject.optJSONArray("expansions");
            List<XApkExpansion> expansionsList = new ArrayList<>();

            if (expansions != null) {
                for (int i = 0; i < expansions.length(); i++) {
                    XApkExpansion apkExpansion = new XApkExpansion();
                    apkExpansion.file = expansions.getJSONObject(i).getString("file");
                    apkExpansion.installLocation = expansions.getJSONObject(0).optString("install_location");
                    apkExpansion.installPath = expansions.getJSONObject(0).getString("install_path");

                    expansionsList.add(apkExpansion);
                }
            }
            xApkManifest.expansions = expansionsList;

            JSONArray splitApks = jsonObject.optJSONArray("split_apks");
            List<XApkSplitApks> xApkSplitApksList = new ArrayList<>();

            if (DBG) Log.d(TAG, "parseXApkManifest: splitApks" + splitApks);

            if (splitApks != null) {
                for (int i = 0; i < splitApks.length(); i++) {
                    XApkSplitApks splitApk = new XApkSplitApks();
                    if (DBG) Log.d(TAG, "parseXApkManifest: splitApks part is  " + splitApks.getJSONObject(i));
                    splitApk.file = splitApks.getJSONObject(i).getString("file");
                    splitApk.id = splitApks.getJSONObject(i).getString("id");

                    xApkSplitApksList.add(splitApk);
                }
            }
            xApkManifest.splitApks = xApkSplitApksList;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return xApkManifest;
    }

    private int extractApk(String zipEntry, final File file, XApk xapk) {
        final ZipEntry entry = xapk.xapkFile.getEntry(zipEntry);
        int result = XApk.INSTALL_FAILED_APK_EXTRACTION_FAILED;
        if (entry == null) {
            return result;
        }
        try {
            final InputStream inputStream = xapk.xapkFile.getInputStream(entry);
            final FileOutputStream fileOutputStream = new FileOutputStream(file);
            final byte[] array = new byte[65536];
            while (true) {
                final int read = inputStream.read(array);
                if (-1 == read) {
                    break;
                }
                fileOutputStream.write(array, 0, read);
            }
            fileOutputStream.flush();
            fileOutputStream.close();
            inputStream.close();
            result = XApk.INSTALL_APK_EXTRACTION_SUCCEEDED;
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return result;
    }

    private long getExpansionsSize(XApkManifest manifest, ZipFile xapkFile, String file) {
        long size = 0L;
        if (!manifest.expansions.isEmpty()) {
            final ZipEntry entry = xapkFile.getEntry(file);
            if (entry == null) {
                size = -1;
            } else {
                size = entry.getSize();
            }
        }
        return size;
    }

    private int installExpansions(XApkManifest manifest, ZipFile xapkFile) {
        int result = XApk.INSTALL_EXPANSION_SUCCEEDED;

        boolean sendStats = false;
        int count = 0;

        while (!Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED)) {
            sendStats = true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            count++;

            if (count >= 30)
                break;
        }

        if (sendStats)
            sendApkInstallExpCloud("inside MEDIA not mounted", String.valueOf(count));

        if (!manifest.isValidXapkVersion())
            return XApk.INSTALL_FAILED_INVALID_XAPK_VERSION;
        else {
            final byte[] array = new byte[65536];
            try {
                final Iterator<XApkExpansion> iterator = manifest.expansions.iterator();
                while (iterator.hasNext()) {
                    final XApkExpansion xApkExpansion = (XApkExpansion) iterator.next();

                    long size = getExpansionsSize(manifest, xapkFile, xApkExpansion.file);
                    if (size > 0) {
                        final InputStream inputStream = xapkFile.getInputStream(xapkFile.getEntry(xApkExpansion.file));
                        final FileOutputStream fileOutputStream = new FileOutputStream(manifest.getExpansionFile(xApkExpansion));

                        while (true) {
                            final int read = inputStream.read(array);
                            if (-1 == read) {
                                break;
                            }
                            fileOutputStream.write(array, 0, read);
                        }
                        fileOutputStream.flush();
                        fileOutputStream.close();
                        inputStream.close();
                    }
                    else if (size == 0)
                    {
                        File file = manifest.getExpansionFile(xApkExpansion);
                        file.createNewFile();
                        Log.d(TAG, "installExpansions: creating file, but not copying contents as size is not valid from zip entry");
                    } else  {
                        result = XApk.INSTALL_FAILED_EXPANSION_FILE_INVALID;
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                result = XApk.INSTALL_FAILED_EXPANSION_FILE_FAILED;
                sendApkInstallExpCloud("path1", Log.getStackTraceString(ex));
            }
            if (result != XApk.INSTALL_EXPANSION_SUCCEEDED) {
                cleanUpExpansionFiles(manifest);
            }
        }
        return result;
    }

    private void sendApkInstallExpCloud(String path, String exception) {
        BstHttpsConnectionHelper connhelper = null;
        String host = SystemProperties.get("bst.bluestacks_cloud_url", "https://cloud.bluestacks.com");
        String cloudUrl =  host + "/stats/miscellaneousstats";
        Log.d(TAG, "cloudUrl is : " + cloudUrl);
        ContentValues values = new ContentValues();
        values.put("tag", "ApkInstallException");
        values.put("arg1", path);
        values.put("arg2", exception);
        values.put("arg3", Environment.getExternalStorageState());
        try {
            connhelper = new BstHttpsConnectionHelper(cloudUrl,"POST");
            connhelper.openConnection();
            connhelper.writeValues(values);
            int responseCode = connhelper.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) { //success
                Log.d(TAG, "HTTP Connection established with " + cloudUrl);
            } else {
                Log.e(TAG, "Failed to establish connection :" + responseCode + ", at " + cloudUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while sending misellaneousstats : "+ e.getMessage());
            e.printStackTrace();
        } finally {
            connhelper.closeConnection();
        }

    }

    private XApkManifest bstParseXApkManifest(final InputStream inputStream) {
        XApkManifest xApkManifest = new XApkManifest();
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            String read;

            while ((read = br.readLine()) != null) {
                sb.append(read);
            }
            br.close();

            JSONObject jsonObject = new JSONObject(sb.toString());
            xApkManifest.packageName = jsonObject.optString("package_name");

            JSONArray expansions = jsonObject.optJSONArray("bst_expansions");
            List<XApkExpansion> expansionsList = new ArrayList<>();

            if (expansions != null) {
                for (int i = 0; i < expansions.length(); i++) {
                    XApkExpansion apkExpansion = new XApkExpansion();
                    apkExpansion.file = expansions.getJSONObject(i).getString("file");
                    apkExpansion.installPath = expansions.getJSONObject(0).getString("install_path");
                    expansionsList.add(apkExpansion);
                }
            }
            xApkManifest.expansions = expansionsList;
        } catch (Exception e) {
            if (DBG) e.printStackTrace();
            return null;
        }
        return xApkManifest;
    }

    private static String bstGetTmpDir(XApkExpansion xApkExpansion) {
        String str = xApkExpansion.installPath;  // ex: /data/data/com.a.b/
        if (str.charAt(str.length() - 1) == '/')
            str = str.substring(0, str.length() - 1);
        return str + "_tmp/";                    // ex: /data/data/com.a.b_tmp/
    }

    private int bstInstallAssetsTmp(XApkManifest bstManifest, ZipFile xapkFile) {
        int result = XApk.INSTALL_EXPANSION_SUCCEEDED;
        try {
            final Iterator<XApkExpansion> iterator = bstManifest.expansions.iterator();
            while (iterator.hasNext()) {
                final XApkExpansion xApkExpansion = (XApkExpansion) iterator.next();
                result = bstUnZipAssetsFile(xapkFile, xApkExpansion);
                if (result != XApk.INSTALL_EXPANSION_SUCCEEDED)
                    break;
            }
        } catch (Exception ex) {
            if (DBG) ex.printStackTrace();
            result = XApk.INSTALL_FAILED_EXPANSION_FILE_FAILED;
            sendApkInstallExpCloud("path2", Log.getStackTraceString(ex));
        }
        return result;
    }

    private void bstRemoveTmpAssets(XApkManifest bstManifest) {
        try {
            final Iterator<XApkExpansion> iterator = bstManifest.expansions.iterator();
            while (iterator.hasNext()) {
                final XApkExpansion xApkExpansion = (XApkExpansion) iterator.next();
                // ex: rm -rf/data/data/com.a.b_tmp/
                BstCommandProcessorUtils.execRootCmdSilent(String.format("rm -rf %s", bstGetTmpDir(xApkExpansion)));
            }
        } catch (Exception ex) {
            if (DBG) ex.printStackTrace();
        }
    }

    private int bstMoveTmpAssets(XApkManifest bstManifest) {
        int result = XApk.INSTALL_EXPANSION_SUCCEEDED;
        try {
            StringBuilder sb = new StringBuilder();
            final Iterator<XApkExpansion> iterator = bstManifest.expansions.iterator();
            while (iterator.hasNext()) {
                final XApkExpansion xapkExpansion = (XApkExpansion)iterator.next();

                // The content format of the following strings are guaranteed in the bstParseXApkManifest funciton
                // They all ends with '/'
                final String input   = xapkExpansion.file;         // ex: bst_asset_packs/files/assetpacks/
                final String dstRoot = xapkExpansion.installPath;  // ex: /data/data/com.a.b/

                // If there is an error executing these commands, we ignore them and let the process continue,
                // because the apk has been installed at this stage.
                try {
                    final int pos = input.indexOf('/');
                    // Notice: The following string variable object is not empty and may not ends with '/'
                    final String object    = input.substring(pos + 1);     // ex: files/assetpacks/
                    final String src       = bstGetTmpDir(xapkExpansion) + object; // ex: /data/data/com.a.b_tmp/files/assetpacks/
                    final String dst       = dstRoot + object;                     // ex: /data/data/com.a.b/files/assetpacks/

                    int filesPos = object.indexOf('/');
                    if (filesPos < 0)
                        filesPos = object.length();
                    String firstFolder = object.substring(0, filesPos); // ex: files
                    final String filesDir  = dstRoot + firstFolder;     // ex: /data/data/com.a.b/files

                    sb.append(String.format("src='%s'; dst='%s'; dstRoot='%s'; filesDir='%s'; \n", src, dst, dstRoot, filesDir));
                    sb.append("mkdir -p \"$dst\"; rm -rf \"$dst\"; chmod -R 771 \"$filesDir\" ; mv -f \"$src\" \"$dst\"; \n");
                    sb.append("user=$(ls -ald \"$dstRoot\" | awk '{ print $3 }'); \n");
                    sb.append("chown -R \"$user\":\"$user\" \"$filesDir\"; \n");
                } catch (Exception e) {
                    if (DBG) e.printStackTrace();
                    result = XApk.INSTALL_FAILED_EXPANSION_FILE_FAILED;
                    sendApkInstallExpCloud("path3", Log.getStackTraceString(e));
                }
            }
            BstCommandProcessorUtils.execRootCmdSilent(sb.toString());
        } catch (Exception ex) {
            if (DBG) ex.printStackTrace();
            result = XApk.INSTALL_FAILED_EXPANSION_FILE_FAILED;
            sendApkInstallExpCloud("path4", Log.getStackTraceString(ex));
        }
        return result;
    }

    private int bstUnZipAssetsFile(ZipFile xapkFile, XApkExpansion xapkExpansion) {
        // The content format of the xapkExpansion.file/xapkExpansion.installPath are guaranteed in the bstParseXApkManifest funciton
        // They all ends with '/'
        final String input     = xapkExpansion.file;                          // ex: bst_asset_packs/files/assetpacks/
        final String unZipPath = bstGetTmpDir(xapkExpansion);                 // ex: /data/data/com.a.b_tmp/
        final String prefix    = input.substring(0, input.indexOf('/') + 1);  // ex: bst_asset_packs/

        int result = XApk.INSTALL_EXPANSION_SUCCEEDED;
        File pathFile = new File(unZipPath);
        if (!pathFile.exists()) {
            pathFile.mkdirs();
        }

        ZipFile zip = null;
        InputStream is = null;
        OutputStream os = null;
        byte[] buf = new byte[65536];
        try {
            zip = xapkFile;
            for (Enumeration<?> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = (ZipEntry)entries.nextElement();
                String zipEntryName = entry.getName();
                try {
                    if (zipEntryName.startsWith(prefix) && zipEntryName.length() > prefix.length()){
                        String outPath = unZipPath + zipEntryName.substring(prefix.length());
                        File file = new File(outPath);
                        if (entry.isDirectory()) {
                            if (!file.exists()) {
                                file.mkdirs();
                            }
                            continue;
                        }
                        file.createNewFile();
                        is =  zip.getInputStream(entry);
                        os = new FileOutputStream(outPath);
                        int len;
                        while ((len = is.read(buf)) > 0) {
                            os.write(buf,0,len);
                        }
                    }
                } catch (IOException e) {
                    if (DBG) e.printStackTrace();
                } finally {
                    if (os != null){
                        os.close();
                        os = null;
                    }
                    if (is != null){
                        is.close();
                        is = null;
                    }
                }
            }
        } catch (IOException e) {
            if (DBG) e.printStackTrace();
            result = XApk.INSTALL_FAILED_EXPANSION_FILE_FAILED;
            sendApkInstallExpCloud("path5", Log.getStackTraceString(e));
        }
        return result;
    }

    private void cleanUpExpansionFiles(XApkManifest xApkManifest) {
        final Iterator<XApkExpansion> iterator = xApkManifest.expansions.iterator();
        while (iterator.hasNext()) {
            xApkManifest.getExpansionFile((XApkExpansion)iterator.next()).delete();
        }
    }

    private static class LocalIntentReceiver {
        private final SynchronousQueue<Intent> mResult = new SynchronousQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                             IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /*
      Function for committing the session, it waits for the apk installation and gets the packageName,
      errorCode, statusCode etc
      We are using the legacy result which provides the old PackageManager result code, not the new public
      status.
    */
    private int doCommitSession(PackageInstaller.Session session) {
        int status = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        mApkInstallResponse.response = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        String failureMessage = "";
        try {
            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());

            final Intent result = receiver.getResult();
            status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            int legacyResult  = result.getIntExtra("android.content.pm.extra.LEGACY_STATUS", -1000);

            if (status != PackageInstaller.STATUS_SUCCESS) {
                failureMessage = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.d(TAG, "doCommitSession: failureMessage: " + failureMessage + " errorCode: " + legacyResult);
                mApkInstallResponse.errorString = failureMessage;
            }
            else {
                mApkInstallResponse.pkgName = result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                Log.d(TAG, "doCommitSession: package: " + result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME));
            }
            Log.d(TAG, "doCommitSession: newStatusCode  = " + status);
            mApkInstallResponse.response = legacyResult;

            status = legacyResult;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return status;
    }

    /*
      Function for writing apks in a session, Pass a session, apk path, size of apk in bytes, and split apk name
      return value is 0 in case of success or -1 in case of error.
      if it fails.
    */
    private int doWriteSession(PackageInstaller.Session session, String inPath, long sizeBytes, String splitName) throws RemoteException {
        InputStream in = null;
        OutputStream out = null;
        try {
            if (inPath != null) {
                in = new FileInputStream(inPath);
            }

            out = session.openWrite(splitName, 0, sizeBytes);

            int total = 0;
            byte[] buffer = new byte[65536];
            int c;
            while ((c = in.read(buffer)) != -1) {
                total += c;
                out.write(buffer, 0, c);
            }
            session.fsync(out);

            if(DBG) Log.d(TAG, "Success: streamed " + total + " bytes");

            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error: failed to write; " + e.getMessage());
            if (DBG) e.printStackTrace();
            return -1;
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
        }
    }

    private static class InstallParams {
        PackageInstaller.SessionParams sessionParams;
    }

    /*
      Function to create install params, Pass total apk size, in case of split apks sum of sizes of all splits
      and the install flags.
      Return value is obj of InstallParams which contains the PackageInstaller.SessionParams obj
    */
    private InstallParams makeInstallParams(long totalSize, int installFlags) {
        final PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        final InstallParams params = new InstallParams();

        sessionParams.installFlags = installFlags;
        sessionParams.setSize(totalSize);

        params.sessionParams = sessionParams;
        return params;
    }

    /*
      Core function for apk install, Pass a list of apk files(in case of split apks) or a single apk file
      with the install flags and Json object to get the extra data for result
      Return value is PackageManager.INSTALL_SUCCEEDED in case of successfull apk install
      otherwise return code is Error code received from PackageManager.
    */
    private int apkInstallCore(File[] listOfFiles, String apkFolderPath, int installFlags)
    {
        HashMap<String, Long> nameSizeMap = new HashMap<>();
        long totalSize = 0;
        int sessionId = 0;
        int result = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        PackageInstaller.Session session = null;

        try {
            for (File listOfFile : listOfFiles) {
                if (listOfFile.isFile()) {
                    if (DBG) Log.d(TAG, "installApk: " + listOfFile.getName());
                    nameSizeMap.put(listOfFile.getName(), listOfFile.length());
                    totalSize += listOfFile.length();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return result;
        }

        final InstallParams installParams = makeInstallParams(totalSize, installFlags);
        try {
            if (installParams.sessionParams == null)
                throw new Exception("SessionParams is null");

            // Creating a session with the sessionParams created from makeInstallParams function
            sessionId = mService.getPackageManager().getPackageInstaller().createSession(installParams.sessionParams);
            if (DBG) Log.d(TAG, "Success: created install session [" + sessionId + "]");

            session = mService.getPackageManager().getPackageInstaller().openSession(sessionId);
            for(Map.Entry<String,Long> entry : nameSizeMap.entrySet())
            {
                result = doWriteSession(session, apkFolderPath + entry.getKey(), entry.getValue(), entry.getKey());
                if (result != 0) {
                    break;
                }
            }

            if (result == 0) {
                result = doCommitSession(session);
            } else {
                mApkInstallResponse.response = result;
                mApkInstallResponse.errorString = "INSTALL_FAILED_INTERNAL_ERROR";
                result = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IoUtils.closeQuietly(session);
        }
        return result;
    }

    private boolean runtimeExecCommands(String commands) {
        try {
            final java.lang.Process process = Runtime.getRuntime().exec(commands);
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream()));) {
                int read;
                char[] buffer = new char[4096];
                StringBuilder errorStringBuilder = new StringBuilder();
                StringBuilder outputStringBuilder = new StringBuilder();
                while ((read = out.read(buffer)) > 0) {
                    outputStringBuilder.append(buffer, 0, read);
                }
                String outputString = outputStringBuilder.toString();

                while ((read = err.read(buffer)) > 0) {
                    errorStringBuilder.append(buffer, 0, read);
                }
                String errorString = errorStringBuilder.toString();
                if (DBG) Log.d(TAG, "command: " + commands + " outputStream: " + outputString + ", errorStream: " + errorString);
                if (errorString != null && errorString.trim().length() > 0) {
                    Log.e(TAG, "errorString: " + errorString);
                    return false;
                }
            } catch (Exception ex) {
                Log.e(TAG, "Exception in runtimeExecCommands while executing command: " + ex.getMessage());
                if (DBG) ex.printStackTrace();
                return false;
            } finally {
                process.destroy();
            }
        } catch(Exception ex) {
            Log.e(TAG, "Exception in runtimeExecCommands while creating/destroying process: " + ex.getMessage());
            if (DBG) ex.printStackTrace();
            return false;
        }
        return true;
    }

    // Main function to change device profile,
    // in case of customProfile p1, p2, p3 and p4 will be model, brand, manufacturer and caSelector respectively
    // and in case of predefined profiles they will be pcode, caCode, caSelector and null.
    private String changeDeviceProfile(String p1, String p2, String p3, String p4, boolean customProfile) {
        String responseCode = "ok";
        String p1LC = p1.toLowerCase(Locale.ENGLISH);
        String p2LC = p2.toLowerCase(Locale.ENGLISH);
        String p3LC = p3.toLowerCase(Locale.ENGLISH);
        String p4LC = "";
        if (p4 != null) {
            p4LC = "." + p4.toLowerCase(Locale.ENGLISH);
        }
        File curBstPropFile = new File(BstCommandProcessorApplication.BS_PROP_FILE);
        File origBstPropFile = new File(BstCommandProcessorApplication.BS_PROP_FILE + ".orig");
        File newBstPropFile = new File(BstCommandProcessorApplication.BS_PROP_FILE + "." + p1LC  + "." + p2LC + "." + p3LC + p4LC);

        File curPropFile = new File(BstCommandProcessorApplication.PROP_FILE);
        File origPropFile = new File(BstCommandProcessorApplication.PROP_FILE + ".orig");
        File newPropFile = new File(BstCommandProcessorApplication.PROP_FILE + "." + p1LC + "." + p2LC + "." + p3LC + p4LC);

        File curAbiPropFile = new File(BstCommandProcessorApplication.ABI_PROP_FILE);
        File origAbiPropFile = new File(BstCommandProcessorApplication.ABI_PROP_FILE + ".orig");
        File newAbiPropFile = new File(BstCommandProcessorApplication.ABI_PROP_FILE + "." + p1LC + "." + p2LC + "." + p3LC + p4LC);

        File curDfPropFile = new File(BstCommandProcessorApplication.DFPROP);
        File origDfPropFile = new File(BstCommandProcessorApplication.DFPROP + ".orig");
        File newDfPropFile = new File(BstCommandProcessorApplication.DFPROP + "." + p1LC + "." + p2LC + "." + p3LC + p4LC);

        File curDefPropFile = new File(BstCommandProcessorApplication.DEF_PROP);
        File origDefPropFile = new File(BstCommandProcessorApplication.DEF_PROP + ".orig");
        File newDefPropFile = new File(BstCommandProcessorApplication.DEF_PROP + "." + p1LC + "." + p2LC + "." + p3LC + p4LC);

        saveOrRestoreLastState(true);
        // Check if we have saved the profile, before making any changes.
        // If not then save the current profile to orig propfiles.
        if (!origBstPropFile.exists() || !origPropFile.exists() || !origAbiPropFile.exists() || !origDfPropFile.exists() || !origDefPropFile.exists()) {
            if (!copyFile(curBstPropFile, origBstPropFile) || !copyFile(curPropFile, origPropFile) || !copyFile(curAbiPropFile, origAbiPropFile) || !copyFile(curDfPropFile, origDfPropFile) || !copyFile(curDefPropFile, origDefPropFile)) {
                Log.e(TAG, "Failed to save orig Prop files");
                return "failed to save orig prop files";
            }
        }

        // checks if device profile that is to be downloaded is already present or not.
        // If present then we copy that profile to our current profiles.
        if (newBstPropFile.exists()) {
            if (DBG) Log.d(TAG, "File already exists : " + newBstPropFile.getName());
            if (copyFile(newBstPropFile, curBstPropFile)
                    && copyFile(newPropFile, curPropFile)
                    && copyFile(newAbiPropFile, curAbiPropFile)
                    && copyFile(newDfPropFile, curDfPropFile)
                    && copyFile(newDefPropFile, curDefPropFile)) {
                if (parseFileAndSetProperties()) {
                    return "ok";
                } else {
                    Log.w(TAG, "Failed to set properties");
                    return "Failed to parse prop files and set properties";
                }
                    }
        }

        if (customProfile) {
            //Creating custom device profiles from the last saved State.
            if (!createNewCustomProfile(BstCommandProcessorApplication.BS_PROP_FILE + ".last", newBstPropFile.getPath(), p1, p2, p3, p4)
                    || !createNewCustomProfile(BstCommandProcessorApplication.PROP_FILE + ".last", newPropFile.getPath(), p1, p2, p3, p4)
                    || !createNewCustomProfile(BstCommandProcessorApplication.ABI_PROP_FILE + ".last", newAbiPropFile.getPath(), p1, p2, p3, p4)
                    || !createNewCustomProfile(BstCommandProcessorApplication.DFPROP + ".last", newDfPropFile.getPath(), p1, p2, p3, p4)
                    || !createNewCustomProfile(BstCommandProcessorApplication.DEF_PROP + ".last", newDefPropFile.getPath(), p1, p2, p3, p4))
                return "error creating new custom device profiles";
        } else {
            // Downloading the device profile from cloud.
            String retVal = downloadDeviceProfile(p1, p2, p3);
            if (!retVal.equals("ok"))
                return retVal;
            // Creating .dfprop and .def.prop files from the new .bluestacks.prop.<pcode>.<caCode>.<caSelector>
            if (!createDefaultPropFiles(p1LC, p2LC, p3LC)) {
                return "failed to create new default prop files";
            }
        }

        if (copyFile(newBstPropFile, curBstPropFile) && copyFile(newPropFile, curPropFile) && copyFile(newAbiPropFile, curAbiPropFile) && copyFile(newDfPropFile, curDfPropFile) && copyFile(newDefPropFile, curDefPropFile)) {
            if (parseFileAndSetProperties()) {
                return "ok";
            } else {
                responseCode = "Failed to parse prop files or set properties";
            }
        } else {
            responseCode = "Failed to copy new profile to current profile, restoring saved state";
        }

        Log.w(TAG, responseCode);
        saveOrRestoreLastState(false);
        return responseCode;
    }

    //This function will save the current state as last state or
    // will switch to the last state depending on saveLastState value.
    // saveLastState = true  (save current state as last state)
    // saveLastState = false (restore last state as current state)
    private boolean saveOrRestoreLastState(boolean saveLastState) {
        File curBstPropFile = new File(BstCommandProcessorApplication.BS_PROP_FILE);
        File lastBstPropFile = new File(BstCommandProcessorApplication.BS_PROP_FILE + ".last");

        File curPropFile = new File(BstCommandProcessorApplication.PROP_FILE);
        File lastPropFile = new File(BstCommandProcessorApplication.PROP_FILE + ".last");

        File curAbiPropFile = new File(BstCommandProcessorApplication.ABI_PROP_FILE);
        File lastAbiPropFile = new File(BstCommandProcessorApplication.ABI_PROP_FILE + ".last");

        File curDfPropFile = new File(BstCommandProcessorApplication.DFPROP);
        File lastDfPropFile = new File(BstCommandProcessorApplication.DFPROP + ".last");

        File curDefPropFile = new File(BstCommandProcessorApplication.DEF_PROP);
        File lastDefPropFile = new File(BstCommandProcessorApplication.DEF_PROP + ".last");

        if (saveLastState) {
            if (DBG) Log.d(TAG, "Saving current state as last state");
            return copyFile(curBstPropFile, lastBstPropFile) && copyFile(curPropFile, lastPropFile) && copyFile(curAbiPropFile, lastAbiPropFile) && copyFile(curDfPropFile, lastDfPropFile) && copyFile(curDefPropFile, lastDefPropFile);
        } else {
            if (DBG) Log.d(TAG, "Restoring last state as current state, as there might be some error while switching to new device profile");
            return copyFile(lastBstPropFile, curBstPropFile) && copyFile(lastPropFile, curPropFile) && copyFile(lastAbiPropFile, curAbiPropFile) && copyFile(lastDfPropFile, curDfPropFile) && copyFile(lastDefPropFile, curDefPropFile);
        }
    }

    private boolean parseFileAndSetProperties() {
        String fileName = BstCommandProcessorApplication.BS_PROP_FILE;
        try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            String read = br.readLine();
            String line = (read == null)? null: read.trim();
            while (line != null) {
                line = line.trim();
                if (!line.startsWith("#")) {
                    if (line.contains("ro.product.board")
                            || line.contains("ro.product.brand")
                            || line.contains("ro.product.device")
                            || line.contains("ro.product.manufacturer")
                            || line.contains("ro.product.name")
                            || line.contains("ro.build.fingerprint")
                            || line.contains("ro.build.platform")
                            || line.contains("ro.build.product")
                            || line.contains("ro.build.description")
                            || line.contains("ro.hardware")) {
                        String[] result = line.split("=");
                        SystemProperties.set("bst." + result[0], result[1]);
                        if (DBG) Log.d(TAG, "Set bst." + result[0] + " = " + result[1]);
                    } else if (line.contains("gsm.sim.operator.numeric")
                            || line.contains("gsm.sim.operator.alpha")
                            || line.contains("gsm.operator.alpha")
                            || line.contains("gsm.operator.numeric")
                            || line.contains("gsm.sim.bstserial")) {
                        String[] result = line.split("=");
                        SystemProperties.set(result[0], result[1]);
                        if (DBG) Log.d(TAG, "Set " + result[0] + " = " + result[1]);
                    } else if (line.contains("ro.product.model")) {
                        String[] result = line.split("=");
                        SystemProperties.set("bst." + result[0], result[1]);
                        Settings.Global.putString(BstCommandProcessorApplication.getInstance().getContentResolver(),
                                Settings.Global.DEVICE_NAME, result[1]);
                        if (DBG) Log.d(TAG, "Set bst." + result[0] + " and Settings.Global.DEVICE_NAME = " + result[1]);
                    }
                }
                line = br.readLine();

            }
        } catch (Exception ex) {
            Log.w(TAG, "Exception in parseFileAndSetProperties : " + ex.getMessage());
            if (DBG) ex.printStackTrace();
            return false;
        }
        return true;
    }

    // Reading src file and modifying properties composed of model, brand and manufacturer
    // before writing to dest file.
    private boolean createNewCustomProfile(String src, String dest, String model, String brand, String manufacturer, String caSelector) {

        String def_pcode = "ofpn";
        String caCode = SystemProperties.get("bst.device_country_code");
        String simPropFilePath = BstCommandProcessorApplication.BS_PROP_FILE + "." + def_pcode + "." + caCode + "." + caSelector;
        String retval = "ok";
        String simOperatorNumeric = null;
        String simOperatorAlpha = null;
        String operatorNumeric = null;
        String operatorAlpha = null;
        String bstSerial = null;
        boolean isSimChangedReq = !caSelector.equals(SystemProperties.get("bst.device_carrier_code")) & src.startsWith(BstCommandProcessorApplication.BS_PROP_FILE);

        if (isSimChangedReq) {
            if (!(new File(simPropFilePath)).exists())
                retval = downloadDeviceProfile(def_pcode, caCode, caSelector);
            if (!retval.equals("ok")) {
                Log.d(TAG, "Failed to download dummy device profile for sim - pcode: " +  def_pcode + " caCode: " + caCode + " caSelector: " + caSelector);
                return false;
            }

            try (BufferedReader br = new BufferedReader(new FileReader(simPropFilePath))) {
                String read = br.readLine();
                String line = (read == null)? null: read.trim();
                while (line != null) {
                    line = line.trim();
                    if (DBG) Log.d(TAG, "Line read = " + line);
                    if (!line.startsWith("#")) {
                        if (line.contains("gsm.operator.numeric"))
                            operatorNumeric = line.split("=")[1];
                        else if (line.contains("gsm.operator.alpha"))
                            operatorAlpha = line.split("=")[1];
                        else if (line.contains("gsm.sim.operator.numeric"))
                            simOperatorNumeric = line.split("=")[1];
                        else if (line.contains("gsm.sim.operator.alpha"))
                            simOperatorAlpha = line.split("=")[1];
                        else if (line.contains("gsm.sim.bstserial"))
                            bstSerial = line.split("=")[1];
                    }
                    line = br.readLine();
                }

                if (simOperatorNumeric == null || simOperatorAlpha == null || operatorNumeric == null ||
                        operatorAlpha == null || bstSerial == null) {
                    Log.w(TAG, "Required sim properties not present in propfile");
                    return false;
                        }

            } catch (Exception ex) {
                Log.e(TAG, "Exception while reading dummy sim propfile: (" + simPropFilePath + ") " + ex.getMessage());
                if (DBG) ex.printStackTrace();
                return false;
            }
        }

        try (BufferedReader br = new BufferedReader(new FileReader(src));
                BufferedWriter bw = new BufferedWriter(new FileWriter(dest))) {
            String read = br.readLine();
            String line = (read == null)? null: read.trim();
            while (line != null) {
                line = line.trim();
                if (DBG) Log.d(TAG, "Line read = " + line);
                String outLine = line;
                if (!line.startsWith("#")) {
                    String value = null;
                    if (line.contains("ro.product.brand"))
                        value = brand;
                    else if (line.contains("ro.product.model"))
                        value = model;
                    else if (line.contains("ro.product.manufacturer"))
                        value = manufacturer;
                    else if (line.contains("ro.build.fingerprint") || line.contains("ro.bootimage.build.fingerprint")) {
                        String[] result = line.split("=");
                        value = brand + result[1].substring(result[1].indexOf("/"));
                    }
                    if (isSimChangedReq) {
                        if (line.contains("gsm.operator.numeric")) {
                            value = operatorNumeric;
                        } else if (line.contains("gsm.operator.alpha")) {
                            value = operatorAlpha;
                        } else if (line.contains("gsm.sim.operator.numeric")) {
                            value = simOperatorNumeric;
                        } else if (line.contains("gsm.sim.operator.alpha")) {
                            value = simOperatorAlpha;
                        } else if (line.contains("gsm.sim.bstserial")) {
                            value = bstSerial;
                        }
                    }
                    if (value != null) {
                        String[] result = line.split("=");
                        outLine = result[0] + "=" + value;
                    }
                }

                if (DBG) Log.d(TAG, "OutputLine = " + outLine);
                bw.write(outLine);
                bw.newLine();
                line = br.readLine();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception while creating custom device profile : (" + dest + ")" + ex.getMessage());
            if (DBG) ex.printStackTrace();
            return false;
        }
        return true;
    }

    // This function will download the predefined profile from cloud.
    private String downloadDeviceProfile(String pcode, String caCode, String caSelector) {
        // get the url from where the file is to be downloaded
        String host = SystemProperties.get("bst.bluestacks_cloud_url", "https://cloud.bluestacks.com");
        String cloudUrl =  host + "/app_player/get_device_profile";
        if (DBG) Log.d(TAG, "cloudUrl is : " + cloudUrl);
        ContentValues values = new ContentValues();
        values.put("device_country_code", caCode);
        values.put("device_profile_code", pcode);
        values.put("device_carrier_code", caSelector);
        // Name of the downloaded zip file.
        String zipFilePath = "/sdcard/" + pcode + ".zip";
        File zipFileName = new File(zipFilePath);
        int status = downloadFile(cloudUrl, zipFileName,values);
        if (status < 0)
            return "error downloading file";

        // Unzip the downloaded zip file to the given path.
        if (!unzip(zipFilePath, "/data/", caCode, caSelector)) {
            return "error while extracting from zip file";
        }

        //Remove the zip file from the /data folder.
        if (zipFileName.delete())
            if (DBG) Log.d(TAG, "zip file deleted successfully");
            else
                Log.w(TAG, "failed to delete file :" + zipFileName.getName());
        return "ok";
    }

    // This will download the file by hitting the webUrl
    private int downloadFile(String cloudUrl, File downloadedFile, ContentValues values) {
        BstHttpsConnectionHelper connhelper = null;
        try {
            connhelper = new BstHttpsConnectionHelper(cloudUrl,"POST");
            connhelper.openConnection();
            connhelper.writeValues(values);
            int responseCode = connhelper.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) { //success
                if (DBG) Log.d(TAG, "HTTP Connection established with " + cloudUrl + " , downloadedFile : " + downloadedFile);
                HttpURLConnection conn = connhelper.getConnectionObj();
                try (BufferedInputStream bis = new BufferedInputStream(conn.getInputStream());
                        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(downloadedFile))){
                    byte[] b = new byte[1024];
                    int count;
                    while ((count = bis.read(b)) >= 0) {
                        bos.write(b, 0, count);
                    }
                    return 0;
                } catch (IOException ioe) {
                    Log.e(TAG, "");
                    ioe.printStackTrace();
                }
            } else {
                Log.e(TAG, "Failed to establish connection :" + responseCode + ", at " + cloudUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while downloading file : "+ e.getMessage());
            if (DBG) e.printStackTrace();
        } finally {
            connhelper.closeConnection();
        }
        return -1;
    }

    // This function will unzip the zipFilePath and will place the files in destDir
    private boolean unzip(String zipFilePath, String destDir, String caCode, String caSelector) {
        File dir = new File(destDir);
        // create output directory if it doesn't exist
        if(!dir.exists()) dir.mkdirs();
        ZipEntry ze = null;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try (FileInputStream fis = new FileInputStream(zipFilePath);
                ZipInputStream zis = new ZipInputStream(fis)){
            ze = zis.getNextEntry();
            int len;
            while(ze != null) {
                String fileName = ze.getName();
                if (DBG) Log.d(TAG, "fileName : " + fileName);
                if (!fileName.contains("bluestacks.prop")
                        && !fileName.contains("propfile")
                        && !fileName.contains("abipropfile")) {
                    //close this ZipEntry
                    zis.closeEntry();
                    ze = zis.getNextEntry();
                        }
                File newFile = new File(destDir + File.separator + fileName + "." + caCode + "." + caSelector);
                FileOutputStream fos = new FileOutputStream(newFile);
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // This function will modify the ro.bootimage.build.fingerprint value in .def.prop and .dfprop files.
    private boolean createDefaultPropFiles(String pcode, String caCode, String caSelector) {
        Log.d(TAG, "create default prop files(.dfprop and .def.prop)");
        String fileName = BstCommandProcessorApplication.BS_PROP_FILE + "." + pcode + "." + caCode + "." + caSelector;
        boolean retVal = false;
        try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            String read = br.readLine();
            String line = (read == null)? null: read.trim();
            while (line != null) {
                line = line.trim();
                if (!line.startsWith("#") && line.contains("ro.build.fingerprint")) {
                    String[] result = line.split("=");
                    retVal = modifyDefaultPropFiles(BstCommandProcessorApplication.DFPROP + ".last", BstCommandProcessorApplication.DFPROP + "." + pcode + "." + caCode + "." + caSelector, result[1]) && modifyDefaultPropFiles(BstCommandProcessorApplication.DEF_PROP + ".last", BstCommandProcessorApplication.DEF_PROP + "." + pcode + "." + caCode + "." + caSelector, result[1]);

                    break;
                }
                line = br.readLine();
            }
        } catch (Exception ex) {
            Log.w(TAG, "Exception in createDefaultPropFiles: " + ex.getMessage());
            if (DBG) ex.printStackTrace();
            return false;
        }
        return retVal;
    }

    // This function will copy src to dest and will modify the fingerprint value while writing to destination.
    private boolean modifyDefaultPropFiles(String src, String dest, String fingerprint) {
        if (DBG) Log.d(TAG, "modifyDefaultPropFiles src = " + src + ", dest = " + dest + ", fingerprint = " + fingerprint);
        try (BufferedReader br = new BufferedReader(new FileReader(src));
                BufferedWriter bw = new BufferedWriter(new FileWriter(dest))) {
            String read = br.readLine();
            String line = (read == null)? null: read.trim();
            while (line != null) {
                line = line.trim();
                if (DBG) Log.d(TAG, "Line read = " + line);
                String outLine = line;
                if (!line.startsWith("#")) {
                    if (line.contains("ro.bootimage.build.fingerprint")) {
                        String[] result = line.split("=");
                        outLine = result[0] + "=" + fingerprint;
                    }
                }

                if (DBG) Log.d(TAG, "OutputLine = " + outLine);
                bw.write(outLine);
                bw.newLine();
                line = br.readLine();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception while modifying default prop files : " + ex.getMessage());
            if (DBG) ex.printStackTrace();
            return false;
        }
        return true;
    }

    // SetLocale is actually called from 2 places.. one from callback function and another
    // from Service. Callback function gives an error if public function is called from it directly,
    // so creating a wrapper function. This will be used by service and callback function will call the
    // internal private function directly.
    public int setLocale(String arg)
    {
        return bstSetLocale(arg);
    }

    // Set the System Locale (Language) as per the argument value. If no match found, don't change
    // the current setting. We are using the best match scenario over here, as incase if exact match
    // is not found, we change the system setting to closest match(using language as key value).
    private int bstSetLocale(String arg)
    {
        int rval = -1;
        Locale newLocale = null;
        String newCountry = "";
        String newLanguage = "";
        String newScript = "";
        String matchedCountry = "", matchedLanguage = "", matchedScript = "";

        if (DBG) Log.d(TAG, "setLocale arg: " + arg);

        // Insert all system supported locales (read from frameworks/base/core/res/res/values/locale_config.xml)
        String[] locales = LocalePicker.getSupportedLocales(BstCommandProcessorApplication.getInstance().getAppContext());

        Arrays.sort(locales);
        final int size = locales.length;

        //Get language and country code from the command argument
        //Windows locale code is of format en-US while in android, it is of format en_US
        String[] newStr = arg.split("-");
        newLanguage = newStr[0].toLowerCase(Locale.ENGLISH);
        if (newStr.length > 2) {
            newScript = newStr[1].toLowerCase(Locale.ENGLISH);
            newCountry = newStr[2].toLowerCase(Locale.ENGLISH);
        }
        else
            newCountry = newStr[1].toLowerCase(Locale.ENGLISH);

        if (DBG)
        {
            Log.d(TAG, "new locale langauage code: " + newLanguage + " country code: " + newCountry);
            Log.d(TAG, "number of supported locales: " + size);
            Log.d(TAG, "locales: " + Arrays.toString(locales));
        }

        // For some of the languages (like indonesian, herbew) windows lang_code differs from java/android lang_code,
        // so handling such special cases over here by converting the windows lang_code to android/java lang_code value.
        if (newLanguage.equalsIgnoreCase("he"))
            newLanguage = "iw";
        else if (newLanguage.equalsIgnoreCase("id"))
            newLanguage = "in";
        else if (newLanguage.equalsIgnoreCase("yi"))
            newLanguage = "ji";


        for (int i = 0 ; i < size; i++ ) {
            String s = locales[i];
            int len = s.length();

            String[] str = s.split("-");
            if (len > 0) {
                String language = str[0];
                String script = "";
                String country = "";
                if (str.length == 2)
                    country = str[1];

                else {
                    country = str[2];
                    script = str[1];
                }
                // So, firstly try to match the whole arg with our set, if it matches its fine else take
                // the one whose lang_code value matches with our locale options. Also, for some of the
                // languages like indonesian, herbew, windows lang_code differs from java/android, so
                // using the special conditions for that.
                if (newLanguage.equalsIgnoreCase(language) && newCountry.equalsIgnoreCase(country) && newScript.equalsIgnoreCase(script)) {
                    matchedLanguage = language;
                    matchedCountry = country;
                    matchedScript = script;
                    Log.d(TAG, "newLocale fully matched: language: " + matchedLanguage + " country: "
                            + matchedCountry + " script: " + matchedScript );
                    break;
                } else if (newLanguage.equalsIgnoreCase(language) && newCountry.equalsIgnoreCase(country)) {
                    matchedLanguage = language;
                    matchedCountry = country;
                    matchedScript = script;
                    Log.d(TAG, "newLocale fully matched: language: " + matchedLanguage + " country: "
                            + matchedCountry + " script: " + matchedScript );
                    break;
                } else if (language.equalsIgnoreCase(newLanguage)) {
                    matchedLanguage = language;
                    matchedCountry = country;
                    matchedScript = script;
                    Log.d(TAG, "newLocale partial(language) matched: language: " + matchedLanguage + " country: "
                            + matchedCountry + " script " + matchedScript );
                }
            }
        }

        if (matchedLanguage != "" && matchedCountry != "") {
            newLocale = new Locale.Builder().setLanguage(matchedLanguage).setScript(matchedScript).setRegion(matchedCountry).build();
            Log.d(TAG, "newLocale : language: " + newLocale.getDisplayLanguage() + " country: "
                    + newLocale.getDisplayCountry() + " name: " + newLocale.getDisplayName() + " locale: " + newLocale.toString());
        }
        if (newLocale != null)
        {
            try {
                IActivityManager am = ActivityManagerNative.getDefault();
                Configuration config = am.getConfiguration();
                Log.d(TAG, "setting newlocale: " + newLocale + " current locale: " + Locale.getDefault().toString());

                if (!newLocale.equals(config.locale)) {
                    // Will set userSetLocale to indicate this isn't some passing default - the user
                    // wants this remembered
                    Log.d(TAG,"setting locale :" + newLocale.toString());
                    config.setLocales(new LocaleList(newLocale, config.getLocales()));
                    // Need to set this so that locale persists across boot.
                    config.userSetLocale = true;
                    am.updatePersistentConfiguration(config);
                    SystemProperties.set("bst.locale", arg);
                    // Trigger the dirty bit for the Settings Provider.
                    BackupManager.dataChanged("com.android.providers.settings");
                } else {
                    // Same locale in a different tag form (bst.locale=zh-CN vs
                    // persist.sys.locale=zh-Hans-CN). Applying it would dispatch a spurious
                    // LOCALE_CHANGED at every boot and pull receiver apps (com.bluestacks.home,
                    // and transitively gms.persistent) into the boot critical window.
                    Log.d(TAG, "locale unchanged (" + newLocale + "), skip updatePersistentConfiguration");
                }
                Log.d(TAG, "newLocale set: " + Locale.getDefault().toString());
                rval = 0;
            } catch (RemoteException e) {
                Log.e(TAG, "exception while setting new locale " + newLocale + " error: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            Log.d(TAG, "No match found for locale: " + arg);
        }
        return rval;
    }

    // This function will copy file from sourceFile to destFile.
    private boolean copyFile(File sourceFile, File destFile) {
        return BstCommandProcessorUtils.copyFile(sourceFile, destFile);
    }

    // This function will copy directory from srcDir to dstDir.
    private boolean copyDirectory(File srcDir, File dstDir) {
        return BstCommandProcessorUtils.copyDirectory(srcDir, dstDir);
    }

    void uninstallBlacklistedApps() {
        File blacklistAppFile = new File(bstBlacklistedInstalledAppListPath);
        if (!blacklistAppFile.exists())
            return;
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(bstBlacklistedInstalledAppListPath))) {
            for(String pkg : bufferedReader.readLine().split(";")) {
                if (DBG) Log.d(TAG, "uninstalling blacklisted app " + pkg);
                _uninstallApp(pkg);
            }
        } catch(Exception e) {
            Log.e(TAG, "Exception in reading file for uninstall blacklist apps");
            if (DBG) e.printStackTrace();
        } finally {
            blacklistAppFile.delete();
        }
    }

    public boolean uninstallApp(String pkg) {
        return _uninstallApp(pkg);
    }


    // Function to uninstall an app and delete its relevant files.
    private boolean _uninstallApp(String pkg) {
        boolean response = false;
        int unInstallFlags = 0;
        if (DBG) Log.d(TAG, "pkg: " + pkg);
        if (pkg == null) {
            Log.e(TAG, "Error: no package specified for uninstallation");
            return response;
        }
        try {
            response = deletePackage(pkg, unInstallFlags);
        } catch (Exception e) {
            Log.e(TAG, "Exception while deleting package " + pkg);
            if (DBG) e.printStackTrace();
        }
        if (response) {
            if (DBG) Log.d(TAG, "Application (" + pkg + ") uninstalled successfully");
        } else {
            Log.e(TAG, "Failure in Uninstalling App: " + pkg);
        }

        return response;
    }

    private boolean deletePackage(String pkg, int unInstallFlags) {
        PackageDeleteObserver obs = new PackageDeleteObserver();
        IPackageManager mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));

        if (mPm == null)
        {
            Log.e(TAG, PM_NOT_RUNNING_ERR);
        }
        else
        {
            try {
                mPm.deletePackageAsUser(pkg, PackageManager.VERSION_CODE_HIGHEST, obs, UserHandle.myUserId(), unInstallFlags);

                synchronized (obs) {
                    while (!obs.finished) {
                        try {
                            obs.wait();
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Error in PackageDeleteObserver: " + e.getMessage());
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error in Uninstalling the package(" + pkg + "): " + e.toString());
                e.printStackTrace();
            }
        }
        return obs.result;
    }

    // Function to stop an app.
    private boolean stopApp(String pkgName) {
        // Check if package is installed on the system or not before issuing stopApp command
        if (pkgName != null && !pkgName.isEmpty() && !pkgName.trim().isEmpty() && checkIfPackageInstalled(pkgName)) {
            boolean isInRunningTaskstack = false;
            int persistenttaskId = 0, num_task = 0;
            //Calling am.removeTask to kill the task - making functionality similar to handle_swipe to kill the taskrecord and associated process
            List<ActivityManager.RunningTaskInfo> runningTasks = mActivityManager.getRunningTasks(50);
            num_task = runningTasks.size();

            List<Integer> tasksToRemove = new ArrayList<>();
            for (int i = 0; i < num_task; i++) {
                ActivityManager.RunningTaskInfo info = runningTasks.get(i);
                if (info.baseActivity != null && info.baseActivity.getPackageName().equalsIgnoreCase(pkgName)) {
                    tasksToRemove.add(info.id);
                    isInRunningTaskstack = true;
                }
            }

            // Now, remove the tasks after the iteration is complete
            for (Integer taskId : tasksToRemove) {
                if (DBG) Log.d(TAG, "Calling removetask for pkg:" + pkgName + " ,taskId:" + taskId);
                // A16: removeTaskWrapper was an A13 BlueStacks addition; AOSP exposes
                // ActivityTaskManager.removeTask(int) (uid system holds MANAGE_ACTIVITY_TASKS).
                ActivityTaskManager.getInstance().removeTask(taskId);
            }

            if (!isInRunningTaskstack) {
                if (DBG) Log.d(TAG, "Force stopping package as not present in the recent task list");
                mActivityManager.forceStopPackage(pkgName);
            }

            return true;
        } else {
            Log.e(TAG, "StopApp argument is either not in proper format or given package is not installed currently, pkgName: " + pkgName);
        }
        return false;
    }

    private boolean checkIfPackageInstalled(String packageName)
    {
        try {
            PackageManager pm = mService.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            return true;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "checkIfPackageInstalled, Package not found: " + packageName);
            return false;
        }
    }

    /**
     * Returns list of packages in the following format:
     * {
     *  apps:
     *  [
     *      {
     *          package: "com.foo.bar"
     *          activity: ".MainActivity"
     *          appLabel: "foo"
     *          versionCode: 10
     *      },
     *      {
     *          package: "com.bzx"
     *          activity: ".MainActivity"
     *          appLabel: "bzx"
     *          versionCode: 2
     *      },
     *  ]
     * }
     *
     * @hide
     */
    String getInstalledPackagesInfo() {
        PackageManager pm = mService.getPackageManager();

        // --- Optimization: batch-query all packages with CATEGORY_LAUNCHER in one IPC ---
        // Previously we did queryIntentActivities(CATEGORY_LAUNCHER) per package (114 IPCs),
        // 98 of which returned empty lists and were discarded. Now we do a single query
        // without setPackage() to get all launcher activities across all packages at once,
        // then build a lookup map. This reduces 114 IPCs to 1.
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> allLauncherActivities = pm.queryIntentActivities(launcherIntent, 0);
        // Map: packageName -> ResolveInfo (first launcher activity per package)
        Map<String, ResolveInfo> launcherActivityMap = new HashMap<>();
        for (ResolveInfo ri : allLauncherActivities) {
            String pkg = ri.activityInfo.packageName;
            if (!launcherActivityMap.containsKey(pkg)) {
                launcherActivityMap.put(pkg, ri);
            }
        }
        if (DBG) Log.d(TAG, "getInstalledPackagesInfo: found " + launcherActivityMap.size()
                + " packages with launcher activities");

        // Also get home app list in the same initial batch (1 IPC, was 1 IPC before — unchanged)
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        Set<String> homeAppPackages = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(homeIntent, 0)) {
            homeAppPackages.add(ri.activityInfo.packageName);
        }

        JSONObject json = new JSONObject();
        try {
            JSONArray array = new JSONArray();
            // Single getInstalledApplications call (1 IPC, was 1 IPC before — unchanged)
            List<ApplicationInfo> appsInfo = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo app : appsInfo) {
                String packageName = app.packageName;

                // Skip packages without launcher activity — no IPC needed (was 2 wasted IPCs before)
                ResolveInfo launcherInfo = launcherActivityMap.get(packageName);
                if (launcherInfo == null) {
                    continue;
                }

                boolean isSystemApp = BstCommandProcessorUtils.isSystemApp(app);
                if (DBG && isSystemApp) Log.d(TAG, "packageName: " + packageName + ", is a system app");

                String activity = launcherInfo.activityInfo.name;
                String appLabel = (String) launcherInfo.loadLabel(pm);
                if (appLabel == null) {
                    appLabel = packageName;
                }

                String versionName = "";
                int versionCode = 0;
                try {
                    PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS);
                    versionCode = packageInfo.versionCode;
                    if (packageInfo.versionName != null) {
                        versionName = packageInfo.versionName;
                    }
                } catch (Exception iex) {
                    Log.w(TAG, "Failed to retrieve packageInfo : " + iex.getMessage());
                    if (DBG) iex.printStackTrace();
                }

                JSONObject appJson = new JSONObject();
                appJson.put("package", packageName);
                appJson.put("activity", activity);
                appJson.put("appLabel", appLabel);
                appJson.put("versionCode", versionCode);
                appJson.put("versionName", versionName);
                appJson.put("isHomeApp", homeAppPackages.contains(packageName));
                appJson.put("isSystemApp", isSystemApp);

                if (mBstFilterAppsManager.isRotateDisabled(packageName)) {
                    appJson.put("orientation", "Disabled");
                }
                array.put(appJson);
            }

            json.put("apps", array);
        } catch (Exception ex) {
            Log.d(TAG, "Exception while fetching installed packages list : " + ex.getMessage());
            if (DBG) ex.printStackTrace();
        }
        return json.toString();
    }

    private boolean isMarketInstalled(String store) {
        try {
            PackageManager pm = mService.getPackageManager();
            switch (store.trim().toLowerCase()) {
                case "com.android.vending":
                    pm.getPackageInfo("com.google.android.gsf", 0);
                    pm.getPackageInfo("com.google.android.gsf.login", 0);
                    pm.getPackageInfo("com.android.vending", 0);
                    break;
                default:
                    pm.getPackageInfo(store, 0);
            }
            if (DBG) Log.d(TAG, "market is installed on this device.");
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "market is not installed on this device.");
            return false;
        }
    }

    //Start url tracking for package packageName.
    private void openTrackingUrl(String packageName, String source) {
        try {
            if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "Launch Google Play for package = " + packageName + " Source : " + source);
            Intent bstIntent = new Intent();
            bstIntent.setPackage("com.bluestacks.home");
            bstIntent.setAction("com.bluestacks.home.AFFILIATE_HANDLER_HTML");
            bstIntent.putExtra("app_pkg", packageName);
            bstIntent.putExtra("referrer_only", "true");
            if (source.equals(""))
                source = "unknown";
            bstIntent.putExtra("WINDOWS_SOURCE", source);
            if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "Source : " + source);
            bstIntent.putExtra("is_foreground", true);
            mService.startServiceAsUser(bstIntent, Process.myUserHandle());
        } catch (Exception ex) {
            Log.w(TAG_BST_REFERRAL, "Exception in openTrackingUrl handler, packageName = " + packageName);
            if (DBG_BST_REFERRAL) ex.printStackTrace();
        }
    }

    // Function to return true/false based on account of the specified type is present or not
    private boolean isAccountRegistered(String type)
    {
        Account[] accounts;
        boolean result = false;
        if (type == null)
        {
            accounts = AccountManager.get(BstCommandProcessorApplication.getInstance().getAppContext()).getAccounts();
        }
        else {
            accounts = AccountManager.get(BstCommandProcessorApplication.getInstance().getAppContext()).getAccountsByType(type);
        }
        if (accounts.length > 0)
             result = true;

        return result;
    }

    private int launchAppStore(String store, String packageName, String appLaunchData, String source) {
        if (packageName == null || packageName.isEmpty() || packageName.trim().isEmpty()) {
            Log.w(TAG, "invalid packageName: " + packageName + " to appStore");
            return -1;
        }

        Log.d(TAG, "Launching appStore Activity without changes");
        openTrackingUrl(packageName, source);
        return 0;
    }

    private int launchGameCenter(String packageName, String extraData, String section, boolean isUpdate, String source) {
        if (packageName == null || packageName.isEmpty() || packageName.trim().isEmpty()) {
            Log.w(TAG, "invalid packageName: " + packageName + " to launchGameCenter");
            return -1;
        }

        Log.d(TAG, "Launching gamecenter Activity without changes");

        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.bluestacks.gamecenter","com.bluestacks.gamecenter.AppCenterActivity"));
        intent.putExtra("package_name", packageName);
        intent.putExtra("extras", extraData);
        intent.putExtra("section", section);
        intent.putExtra("source", source);
        if(isUpdate)
            intent.putExtra("is_package_update",true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        if (DBG) Log.d(TAG, "Launching gamecenter Activity, intent: " + intent);
        mService.startActivityAsUser(intent, Process.myUserHandle());
        return 0;
    }

    // Function similar to ComponentName.unflattenFromString()
    // Instead of componentName it return className.
    private static String getClassNameFromActivity(String str) {
        int sep = str.indexOf('/');
        if (sep < 0 || (sep+1) >= str.length()) {
            return str;
        }
        String pkg = str.substring(0, sep);
        String cls = str.substring(sep+1);
        if (cls.length() > 0 && cls.charAt(0) == '.') {
            cls = pkg + cls;
        }
        return cls;
    }

    public void initVolume() {
        int volume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        SharedPreferences sharedPref = BstCommandProcessorApplication.getInstance().getSharedPreferences("BstCmdPrefs", 0);
        if (SystemProperties.getInt("bst.mute_all_instances", 0) == 1 || sharedPref.getBoolean("muteState", false))
            setVolume(true, 0);

        mBstHostCallManagerService.initVolume(mIsVolumeMuted, volume, maxVolume);
    }

    private void setVolume(boolean mute, int volume) {
        if (DBG) Log.d(TAG, "setVolume: mute = " + mute + " volume = " + volume);

        final int[] streamTypes = new int[] {
            AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_VOICE_CALL
        };

        float maxVolumeLevel = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        for (int stream : streamTypes) {
            if (mIsVolumeMuted != mute) {
                mAudioManager.adjustStreamVolume(stream, mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            }
            if (!mute) {
                int streamMaxVolume = mAudioManager.getStreamMaxVolume(stream);
                int volumeTobeSet = (int) Math.ceil(streamMaxVolume * (volume / maxVolumeLevel));
                mAudioManager.setStreamVolume(stream, volumeTobeSet, 0);
            }
        }

        if (mIsVolumeMuted != mute) {
            mIsVolumeMuted = mute;
            SharedPreferences sharedPref = BstCommandProcessorApplication.getInstance().getSharedPreferences("BstCmdPrefs", 0);
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putBoolean("muteState", mIsVolumeMuted);
            editor.commit();
        }
    }

    private int searchOnGooglePlay(String query) {
        if (query == null || query.isEmpty() || query.trim().isEmpty()) {
            Log.w(TAG, "query string is empty");
            return -1;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setPackage("com.android.vending");
        intent.setData(Uri.parse("http://play.google.com/store/search?q=" + query + "&c=apps"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        if (DBG) Log.d(TAG, "Launching gPlay search Activity, intent: " + intent);
        mService.startActivityAsUser(intent, Process.myUserHandle());
        return 0;
    }

    public void launchRequiredAppStore(String store, String packageName, String extraData, String response, boolean isUpdate) {
        _launchRequiredAppStore(store, packageName, extraData, response, isUpdate, "");
    }

    private void _launchRequiredAppStore(String store, String packageName, String extraData, String response, boolean isUpdate, String source) {
        String action = "OpenAppStore";
        String appLaunchData = "http://play.google.com/store/apps/details?id=";
        String section = "";
        if (response != null && response.trim().length() > 0) {
            try {
                JSONObject jsonObject = new JSONObject(response);
                action = jsonObject.getString("action");
                String appStore = jsonObject.optString("app_store");
                store = (appStore == null || appStore.trim().length() <=0) ? store : appStore;
                appLaunchData = jsonObject.optString("app_launch_data");
            } catch (Exception ex) {
                Log.w(TAG, "Exception in launchRequiredAppStore : " + ex.getMessage());
                if (DBG) ex.printStackTrace();
            }
        }

        if (action.equals("OpenGameCenter")) {
            Log.d(TAG, "app is downloaded from CDN");
            section = "ApkInstallation";
            store = "com.bluestacks.gamecenter";
        }

        if (store == null || store.trim().length() <= 0) {
            Log.d(TAG, "store is null");
            store = "com.android.vending";
        }

        if (!isMarketInstalled(store)) {
            if (store != "com.android.vending" && isMarketInstalled("com.android.vending")) {
                Log.w(TAG, "Market not installed yet, try play store");
                store = "com.android.vending";
                appLaunchData = "http://play.google.com/store/apps/details?id=";
            } else {
                Log.w(TAG, "store = " + store + " and play store not installed");
                return;
            }
        }

        int result;
        switch (store.trim().toLowerCase()) {
            case "com.bluestacks.gamecenter":
                result = launchGameCenter(packageName, extraData, section, isUpdate, source);
                break;
            case "com.android.vending":
                result = launchAppStore(store, packageName, appLaunchData, source);
                break;
            default:
                result = launchAppStore(store, packageName, appLaunchData, source);
                if (result < 0) {
                    Log.d(TAG, "Try google play store: " + store);
                    result = launchAppStore("com.android.vending", packageName, "http://play.google.com/store/apps/details?id=", source);
                }
        }

        if (result < 0) {
            Log.w(TAG, "Failed to launch package: " + packageName + ", in store: " + store);
        }
        return;
    }

    public void reportNowggPltfDetectionToCloud (Intent intent) {
        BstHttpsConnectionHelper connhelper = null;
        String host = SystemProperties.get("bst.bluestacks_cloud_url", "https://cloud.bluestacks.com");
        String cloudUrl =  host + "/app_player/report_nowgg_platform_detection";
        if (DBG) Log.d(TAG, "cloudUrl is : " + cloudUrl);
        ContentValues values = new ContentValues();
        values.put("app_package", intent.getStringExtra("pkgname"));
        values.put("app_versionCode", intent.getStringExtra("versionname"));
        values.put("app_versionName", intent.getStringExtra("versioncode"));
        try {
            connhelper = new BstHttpsConnectionHelper(cloudUrl,"POST");
            connhelper.openConnection();
            connhelper.writeValues(values);
            int responseCode = connhelper.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) { //success
                if (DBG) Log.d(TAG, "HTTP Connection established with " + cloudUrl);
            } else {
                Log.e(TAG, "Failed to establish connection :" + responseCode + ", at " + cloudUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while downloading file : "+ e.getMessage());
            if (DBG) e.printStackTrace();
        } finally {
            connhelper.closeConnection();
        }
    }

    private native final int native_init();
    private native final int native_fini();
    private native final int native_getLocalTime();
    }
