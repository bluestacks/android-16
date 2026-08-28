package com.bluestacks.BstCommandProcessor;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.ServiceManager;
import android.os.storage.VolumeInfo;
import android.os.SystemClock;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.content.BroadcastReceiver;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.BstUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;
import android.content.SharedPreferences;

import com.bluestacks.os.BstHostCallManager;
import com.bluestacks.os.BstFilterAppsManager;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TimeZone;

import java.util.regex.Pattern;
import com.bluestacks.BstCommandProcessor.UiAccessibilityService;
import com.bluestacks.BstCommandProcessor.Accessibility.KeyCommandExecutor;

/**
 *
 * @author suman
 * Intent receiver for all package additions/deletions/updation
 */
class BstBootCompletedReceiver extends BroadcastReceiver
{
    private static final String TAG = "BstCommandProcessor-BootCompletedReceiver";
    public void onReceive(Context context, Intent intent)
    {
        BstCommandProcessorUtils.clearGoogleAppsData(TAG);

        // Offload syncInstalledApps to background thread to avoid blocking
        // BOOT_COMPLETED ordered broadcast dispatch (~457ms of Binder IPCs).
        // The underlying JNI call (native_syncInstalledApps) is already fire-and-forget
        // via xthrPoolAddTask, so only the getInstalledPackagesInfo() gathering blocks.
        final BstCommandProcessorApplication app = BstCommandProcessorApplication.getInstance();
        app.postToHostCallThread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "syncInstalledApps");
                app.getCommandHandler().syncInstalledApps();
            }
        });
        app.setLocationData();

        if (SystemProperties.get("bst.bluestacks_account_id").isEmpty()) {
            String email = SystemProperties.get("persist.sys.user.email");
            if (!email.isEmpty()) {
                SystemProperties.set("bst.bluestacks_account_id", email);
                // if bluestacks account id is empty but user email is present then set bluestacks_account id by calling google signin hcall
                BstHostCallManager hCallManager = (BstHostCallManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_HOST_CALL);
                hCallManager.onGoogleLoginCompleted(email);
            }
        }
    }
}

class BstVolumeStateChangeReceiver extends BroadcastReceiver
{
    private static final String TAG = "BstCommandProcessor-BstVolumeStateChangeReceiver";
    public void onReceive(Context context, Intent intent)
    {
        String volumeId = intent.getStringExtra(VolumeInfo.EXTRA_VOLUME_ID);
        boolean isExternaStorage = volumeId != null && volumeId.contains("emulated;0");
        int state = intent.getIntExtra(VolumeInfo.EXTRA_VOLUME_STATE, -1);
        Log.d(TAG, "in onReceive, volumeId " + volumeId + " isExternaStorage: " + isExternaStorage + "  state :" + state);
        if (isExternaStorage && state == VolumeInfo.STATE_MOUNTED) {
            BstCommandProcessorUtils.clearGoogleAppsData(TAG);
        }
    }
}

class BstPackageIntentsReceiver extends BroadcastReceiver
{
    private static final String TAG = "BstCommandProcessor-PackageIntentReceiver";
    private static final boolean DBG = true || android.os.SystemProperties.getInt("bst.debug.bstcmdapp", 0) > 0;
    @Override
    public void onReceive(Context context, Intent intent)
    {
        String intentAction = intent.getAction();
        Uri packageUri = intent.getData();
        String pkgName = packageUri.getSchemeSpecificPart();
        if(DBG) Log.d(TAG, "pkg is: " + pkgName + " event is " + intentAction);

        boolean updated = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

        BstCommandProcessorApplication.getInstance().setGoogleAdId();
        if (intentAction.equals(Intent.ACTION_PACKAGE_ADDED)) {
            if(!BstCommandProcessorApplication.getInstance().mStudioApkPkgMap.containsKey(pkgName)) {
                BstCommandProcessorApplication.getInstance().updateInstallTimeListForPackage(pkgName, updated);
                BstCommandProcessorApplication.getInstance().sendAppInstallRequest(pkgName, updated);
            }
        } else if (!updated && intentAction.equals(Intent.ACTION_PACKAGE_REMOVED)) {
            BstCommandProcessorApplication.getInstance().removePackageFromIl2cppList(pkgName);
            BstCommandProcessorApplication.getInstance().removePackageFromReferrerLists(pkgName);
            BstCommandProcessorApplication.getInstance().sendAppUninstallRequest(pkgName);
            BstCommandProcessorApplication.getInstance().removeEntryFromAppSetting(pkgName);
        }
    }
}

public class BstCommandProcessorApplication extends Application {
    private static final String TAG = "BstCommandProcessor-Application";
    private static final String TAG_BST_REFERRAL = "BstCommandProcessor-Application-Affiliate";
    private static final String TAG_BST_IAP = "BstCommandProcessor-Application-GIAP";
    private static final boolean DBG = true || android.os.SystemProperties.getInt("bst.debug.bstcmdapp", 0) > 0;
    private static final boolean VERBOSE = android.os.SystemProperties.getInt("bst.debug.bstcmdapp", 0) > 1;
    private static final boolean DBG_BST_REFERRAL = DBG || SystemProperties.getInt("bst.debug.referral", 0) > 0;
    private static final boolean DBG_BST_IAP = DBG || SystemProperties.getInt("bst.debug.iap", 0) > 0;
    private static final boolean DBG_COMMANDS = DBG || SystemProperties.getInt("bst.debug.commands", 0) > 0;

    private static BstCommandProcessorApplication singleton;
    private static Context appContext;
    private static Service mService;
    private static KeyCommandExecutor mKeyCommandExecutor;

    // creating a new thread for handling all host calls
    private HandlerThread mHandlerThreadForHostCalls = new HandlerThread("threadForHostCalls");
    private Handler mHandlerForHostCalls = null;

    private static BstCommandLoop mCmdHandler = null;  //handler for CommandLoop

    private static FileObserver mObserver;  //observer for app crashes
    private static FileObserver dataDownloadsObserver;  //observer for data downloads list
    private static FileObserver affiliateFileObserver;  //observer for affiliate files 
    private static FileObserver hostsFileObserver;  //observer for system hosts file
    private static FileObserver studioApkFileObserver;  //observer for installing zip file from studio
    private static final int OBSERVER_EVENTS = FileObserver.CLOSE_WRITE;
    private static final int SUPPRESSED_OBSERVER_EVENTS = FileObserver.CREATE | FileObserver.DELETE | FileObserver.CLOSE_WRITE |
                                FileObserver.MOVED_FROM | FileObserver.MOVED_TO; // | FileObserver.MODIFY;

    // path prefix for temporary usage for this application
    private static final String configFilePath = "/data/downloads/";
    public static final String BstCommandProcessorPath = configFilePath + ".tmp/";
    public static final String affiliateFilePath = configFilePath + ".aff/";
    public static final String studioZipInstallFilePath = BstCommandProcessorPath + ".studio_install/";
    public static final String studioZipLastFilePath = BstCommandProcessorPath + ".studio_install_last/";

    private static final String BstSharedFolderPath = "/mnt/windows/BstSharedFolder";

    // file to store referral data information
    public static final String appReferralListFile = affiliateFilePath + ".fl";
    // file to store packages information that have offers
    public static final String offerPackageListFile = affiliateFilePath + ".opf";
    // file to store packages for which referrer is some 3rd party app.
    public static final String packageReferrerFile = affiliateFilePath + ".prf";
    // file to indicate whether stat information for the referral needs to be sent for specific package or not.
    private static final String bstInstallReferrerPath = affiliateFilePath + ".ir";
    private static final String bstOtherReasonPath = affiliateFilePath + ".or";
    private static final String bstAppInstallReferrerReqStatPath = affiliateFilePath + ".airr";
    // stores the list containing firstInstallTime data.
    public static final String bstInstallTimeListPath = affiliateFilePath + ".pit";

    public static final int perms_666 = FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP | FileUtils.S_IROTH | FileUtils.S_IWGRP | FileUtils.S_IWOTH;

    static final int IMPORT_FILES               = 0;
    static final int EXPORT_FILES               = 1;
    static final int CREATE_DESKTOP_SHORTCUT    = 2;
    static final int IMPORT_FILES_COMPLETED     = 3;
    static final int OPEN_URL                   = 4;
    static final int LAUNCH_BSX                 = 5;
    static final int UPDATE_STATS_INTERVAL      = 6;
    static final int START_EXPORT_FILES         = 7;
    static final int CONSOLE_MODE_STATE_CHANGED = 8;
    static final int AFFILIATE_TRACKING         = 9;
    static final int NOWGG_ACCOUNT_ADDED        = 10;
    static final int UPDATE_QUEST_RULES         = 11;
    static final int DIFFERENT_IMAGE_PKG        = 12;
    static final int SHOW_NOTIFICATION          = 13;
    static final int WALLET_MESSAGE             = 14;
    static final int ADS_INFO_CLICK             = 15;
    static final int REMOVE_BOOT_LOADING_SCREEN = 16;
    static final int INTERSTITIAL_AD_COMPLETED  = 17;
    static final int ON_NOWBUX_UPDATED          = 18;
    static final int ON_IAP_COMPLETED           = 19;
    static final int UNZIP_FILE                 = 20;
    static final int GET_NOWGG_ACCOUNT          = 21;
    static final int INSTALL_APP_GAME_CENTER    = 22;
    private Handler mHandler = null;
    // for storing referralData, ArrayList contains referral, referralClickedTime, install referrer statSent, referrer begin statSent, install begin statSent, delay and skipReferral info in the given order.
    public static HashMap<String, String> mBstAppReferralList = new HashMap<String, String>();

    //Stores firstInstallTime for the packages downloaded.
    public static HashMap<String, ArrayList<Long>> mBstInstallTimeList = new HashMap<String, ArrayList<Long>>();
    //Stores packages that have an offer.
    public static HashSet<String> mBstOfferPackageList = new HashSet<String>();

    //Stores packages for which referrer is some 3rd party app. We will not send our click eent for these apps.
    static HashSet<String> mBstPackageReferrerPresent = new HashSet<String>();
    public static HashMap<String, String> mStudioApkPkgMap = new HashMap<String, String>();

    // Shared Preference file to check if AndroidSecureId sent to cloud
    static final String BST_COMMAND_PROCESSOR_PREFS = "BstCommandProcessorPrefs";
    static final String CONFIG_VERSION_PREFS_KEY = "configVersion";
    static final String BS_PROP_FILE = "/data/.bluestacks.prop";
    static final String PROP_FILE = "/data/.propfile";
    static final String ABI_PROP_FILE = "/data/.abipropfile";
    static final String DFPROP = "/data/.dfprop";
    static final String DEF_PROP = "/data/.def.prop";

    // path to default system hosts file.
    private static final String SYSTEM_HOSTS_FILE = "/system/etc/hosts";
    // Some cheat engines add loopback entry for this fqdn to fake IAP transaction data.
    private static final String FAKE_IAP_ENTRY = "android.clients.google.com";

    static boolean isFakeIAPEngineEnabled = false;
    static final Object mLockFakeIAPEngineEnabled = new Object();
    static String mGoogleAdId = SystemProperties.get("bst.android_google_ad_id", "");
    static String mProdVersion = SystemProperties.get("bst.version", "");

    //Increment CURRENT_VERSION value when a new field is to be sent to cloud,
    //while sending ANDROID_ID.
    public static final int CURRENT_VERSION = 2;

    BstHostCallManager mBstHostCallManagerService;

    BstFilterAppsManager mBstFilterAppsManager;
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged called");
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate called");
        super.onCreate();
        singleton = this;
        appContext = getApplicationContext();
        Instrumentation instrumentation = new Instrumentation();
        mKeyCommandExecutor = new KeyCommandExecutor(instrumentation, DBG_COMMANDS);
        mHandler = new Handler(getMainLooper());
        mHandlerThreadForHostCalls.start();
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        try {
            createStatDir();

            createFile(bstInstallReferrerPath, perms_666);
            createFile(bstOtherReasonPath, perms_666);
            createFile(bstAppInstallReferrerReqStatPath, perms_666);
            // Check if last shutdown was a proper shutdown or not, If shutdown was not proper
            // try of fix some values in xml files
            checkIfLastShutdownWasProper();

            startBstService();

            monitorDataDownloadsList();

            //monitor app crashes, kill zygote request and notification manager requests
            //and send their information to the windows agent
            monitorBstDataPath();

            enableAccessibilityService();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    cleanUpStudioZipFiles();
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Exception in BstCommandProcessorApplication: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void enableAccessibilityService() {
        if (DBG)
            Log.i(TAG, "enableAccessibilityService called");
        String service = "com.bluestacks.BstCommandProcessor/.UiAccessibilityService";
        try {
            // read accessibility settings
            String enabledServices = Settings.Secure.getString(appContext.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || enabledServices.isEmpty()) {
                enabledServices = service;
            } else if (!enabledServices.contains(service)) {
                enabledServices = enabledServices + ":" + service;
            }

            boolean success1 = Settings.Secure.putString(appContext.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledServices);
            if (DBG) Log.d(TAG, "success1 " + success1);
            boolean success2 = Settings.Secure.putInt(appContext.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            if (DBG) Log.d(TAG, "success2 " + success2);
            if (!success1 || !success2) {
                if (DBG) Log.e(TAG, "Failed to write to secure settings. WRITE_SECURE_SETTINGS permission missing or restricted.");
                return;
            }

            if (DBG) Log.d(TAG, "Accessibility service enabled successfully");
        } catch (Exception e) {
            Log.d(TAG, "Unexpected error: " + e.getMessage());
            e.printStackTrace();
            return;
        }
        UiAccessibilityService uiService = UiAccessibilityService.getInstance();
        if (uiService == null) {
            Log.w(TAG, "UI Accessibility Service instance not found");
            return;
        }
    }

    private void cleanUpStudioZipFiles() {
        Log.d(TAG, "cleanUpStudioZipFiles called");
        int count = 0;
        while (!Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED) || mCmdHandler == null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            count++;

            if (count >= 50)
                break;
        }
        Log.d(TAG, "count " + count);
        if (count >= 50) {
            return;
        }
        Log.d(TAG, "cleanUpStudioZipFiles called 2");
        //reading _last folder
        File lastStudioFolder = new File(studioZipLastFilePath);
        if (!lastStudioFolder.isDirectory()) {
            Log.d(TAG, "studioZipLastFilePath directory not present");
            return;
        }
        // directory exists hence try reading the studio_install_file
        File studioZipInfoFile = new File (studioZipLastFilePath + "/studio_app_install_config");
        if (!studioZipInfoFile.exists()) {
            Log.d(TAG, "studioZipInfoFile doesn't exists");
            return;
        }

        String contents = BstCommandProcessorUtils.readFile(studioZipInfoFile.getAbsolutePath());
        if (contents.equals("")) {
            Log.d(TAG, "content of studioZipInfoFile empty");
            return;
        }

        try {
            JSONArray configPackages = new JSONArray(contents);
            for (int i = 0; i < configPackages.length(); i++) {
                JSONObject object = configPackages.getJSONObject(i);
                String pkg = object.optString("studio_app_package");
                String statusFileName = "studio_app_install_status_" + pkg;
                Log.d(TAG, "pkg " + pkg + "  statusFileName " + statusFileName);
                File statusFile = new File(studioZipLastFilePath + "/" + statusFileName);
                Log.d(TAG, "statusFile " + statusFile.getAbsolutePath());
                if (!statusFile.exists()) {
                    Log.d(TAG, " statusFile doesn't exist " + pkg);
                    mCmdHandler.uninstallApp(pkg);
                } else {
                    Log.d(TAG, "status file exists for package " + pkg);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createStatDir() {
        File affiliateFileDir = new File(affiliateFilePath);
        if (!affiliateFileDir.exists()) {
            if (affiliateFileDir.mkdir()) {
                Log.d(TAG_BST_REFERRAL, "Successfully created affiliate files directory");
                if (FileUtils.setPermissions(affiliateFileDir,
                            FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IXUSR |
                            FileUtils.S_IRGRP | FileUtils.S_IWGRP | FileUtils.S_IXGRP |
                            FileUtils.S_IROTH | FileUtils.S_IWOTH | FileUtils.S_IXOTH, -1, -1) != 0) {
                    Log.e(TAG, "Failed to change permissions for the file");
                            }
            } else {
                Log.e(TAG_BST_REFERRAL, "Failed to create affiliate files directory");
            }
        }
    }

    private void createFile(String filePath, int perms) {
        try {
            File file= new File(filePath);
            //file != null is always true
            if (!file.exists()) {
                file.createNewFile();
                if (FileUtils.setPermissions(filePath, perms, -1, -1) != 0) {
                    Log.e(TAG, "Failed to change permissions for the file");
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception in createFile : " + ex.getMessage());
            if (DBG) ex.printStackTrace();
        }
    }

    public void completeBasicSetup()
    {
        try {
            checkMockLocationPermission();

            disableProvisionAppIfRequired();

            affiliateFileObserver.startWatching();
            studioApkFileObserver.startWatching();
            mObserver.startWatching();

            if (mBstHostCallManagerService == null)
                mBstHostCallManagerService = (BstHostCallManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_HOST_CALL);

            if (mBstFilterAppsManager == null)
                mBstFilterAppsManager = (BstFilterAppsManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_FILTER_APPS);

            //load system hosts file and start file observer on it
            initHostsFile();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void processOfferIntent(Intent intent) {
        TaskProcessOfferIntent asyncTask = new TaskProcessOfferIntent(intent);
        asyncTask.execute();
    }

    public void processReferralIntent(Intent intent) {
        TaskProcessReferralIntent asyncTask = new TaskProcessReferralIntent( intent);
        asyncTask.execute();
    }

    private void disableProvisionAppIfRequired()
    {
        String provisionApp = "com.android.provision/.DefaultActivity";
        int deviceProvisioned = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0);
        if ((deviceProvisioned == 1)) {
            setServiceComponentState(provisionApp, false);
        }
    }

    private boolean startBstService() {
        try {
            Intent intent = new Intent();
            ComponentName cn = new ComponentName("com.bluestacks.BstCommandProcessor",
                    "com.bluestacks.BstCommandProcessor.BstCommandProcessorService");
            intent.setAction("startBstServiceFromApplication");
            intent.setComponent(cn);
            BstCommandProcessorApplication.getAppContext().startServiceAsUser(intent, UserHandle.SYSTEM);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.e(TAG, "onLowMemory called");
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        Log.e(TAG, "onTerminate called");
    }

    private void setServiceComponentState(IPackageManager mPm, ComponentName cn, int newState, int flags, int userId) {
        try {
            // A16: IPackageManager.setComponentEnabledSetting gained a callingPackage arg.
            mPm.setComponentEnabledSetting(cn, newState, flags, userId, getPackageName());
            Log.d(TAG, "Component " + cn.toShortString() + " new state: "
                    + enabledSettingToString(mPm.getComponentEnabledSetting(cn, userId)));
        } catch (Exception e) {
            Log.e(TAG, "Error in changing state of service : " + cn.flattenToString());
            e.printStackTrace();
        }
    }

    private boolean setServiceApplicationState(IPackageManager mPm, String pkgName, int newState, int flags, int userId) {
        try {
            mPm.setApplicationEnabledSetting(pkgName, newState, flags, userId, getAppContext().getPackageName());
            Log.d(TAG, "pkgName " + pkgName + " new state: "
                    + enabledSettingToString(mPm.getApplicationEnabledSetting(pkgName, userId)));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in changing state of pkgName : " + pkgName);
            e.printStackTrace();
        }
        return false;
    }

    private void sendBstCmdReferrerStats(String eventType, String pkgName, JSONObject miscData) {
        if (DBG_BST_REFERRAL) Log.d(TAG, "sendBstCmdReferrerStats, eventType = " + eventType + ", pkgName = " + pkgName);
        try {
            String host = SystemProperties.get("bst.bluestacks_cloud_url", "https://cloud.bluestacks.com");
            String cloudUrl =  host + "/affiliate/stats";

            String homeAppVersion = appContext.getPackageManager().getPackageInfo("com.bluestacks.home", 0).versionName;

            ContentValues values = new ContentValues();
            miscData.put("system_utc_time", getCurrentUtcTime());
            miscData.put("system_boot_time", getSystemBootTime());
            miscData.put("system_current_time", getSystemCurrentTime());
            values.put("home_app_ver", homeAppVersion);
            values.put("app_pkg", pkgName);
            values.put("misc_data", miscData.toString());
            values.put("device_id", mGoogleAdId);
            values.put("prod_ver", mProdVersion);
            values.put("pkg_name", pkgName);
            values.put("event_type", eventType);
            postRequestToCloud(cloudUrl, values, DBG_BST_REFERRAL, TAG_BST_REFERRAL);
        } catch (Exception ex) {
            Log.e(TAG_BST_REFERRAL, "Exception in sending stat to cloud " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void sendAppInstallReferrerRequest(String filePath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String host = SystemProperties.get("bst.bluestacks_cloud_url", "https://cloud.bluestacks.com");
            String cloudUrl =  host + "/affiliate/stats";

            HashMap<String,String> map = new HashMap<String,String>();
            map = (HashMap<String,String>) BstUtils.loadListFromFile(filePath, map);
            if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "map : " + Arrays.asList(map));
            JSONObject data = new JSONObject(map.get("data"));
            if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "data : " + data.toString());
            String pkgName  = data.optString("pkg_name", "");
            SharedPreferences sharedPref = BstCommandProcessorApplication.getInstance().getSharedPreferences("BstCmdPrefs", 0);
            String pkgsCommaString = sharedPref.getString("airr_pkgs", "");
            if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "pkgsCommaString " + pkgsCommaString);
            String[] pkgsArray = pkgsCommaString.split(",");
            if (pkgsArray != null && Arrays.asList(pkgsArray).contains(pkgName)) {
                // we have already send the stat for this hence return
                if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "not sending stat as pkgsArray is not null and has pkgName " + pkgName);
                return;
            }

            String installerPackage = ((pkgName != null) ? getPackageManager().getInstallerPackageName(pkgName) : "");
            String homeAppVersion = appContext.getPackageManager().getPackageInfo("com.bluestacks.home", 0).versionName;

            String eventType  = data.optString("event_name", "app_install_referrer_request");
            JSONObject miscData = new JSONObject();
            miscData.put("system_utc_time", getCurrentUtcTime());
            miscData.put("system_current_time", getSystemCurrentTime());
            miscData.put("system_boot_time", getSystemBootTime());
            miscData.put("orig_install_referrer", data.optString("orig_install_referrer", ""));

            ContentValues values = new ContentValues();
            values.put("home_app_ver", homeAppVersion);
            values.put("app_pkg", pkgName);
            values.put("misc_data", miscData.toString());
            values.put("device_id", mGoogleAdId);
            values.put("prod_ver", mProdVersion);
            values.put("pkg_name", pkgName);
            values.put("event_type", eventType);
            values.put("system_local_time_epoch", getSystemCurrentTime());
            values.put("system_boot_time", getSystemBootTime());
            values.put("installer_package", installerPackage);

            postRequestToCloud(cloudUrl, values, DBG_BST_REFERRAL, TAG_BST_REFERRAL);
            // saving it to sharedprefs
            sharedPref.edit().putString("airr_pkgs", pkgsCommaString + "," + pkgName).commit();
        } catch (Exception ex) {
            Log.e(TAG_BST_REFERRAL, "Exception in sending stat to cloud " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void sendReferrerStatToCloud(String filePath) {
        JSONObject jsonObject = new JSONObject();
        String packageName = "";
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String host = SystemProperties.get("bst.bluestacks_cloud_url", "https://cloud.bluestacks.com");
            String cloudUrl =  host + "/tracker/generic/install_referrer";

            StringBuilder sb = new StringBuilder();
            JSONObject reqjson = new JSONObject();
            String encoded;
            while((encoded = reader.readLine()) != null) {
                sb.append(encoded);
            }
            byte[] decoded = Base64.decode(sb.toString().getBytes(), 0);

            String dataToSend = new String(decoded);
            if (dataToSend.trim().length() <= 0) {
                Log.e(TAG_BST_REFERRAL, "datatoSend is empty hence returning");
                return;
            }
            jsonObject.put("data_to_send", dataToSend);

            JSONObject miscdata = new JSONObject(dataToSend);
            packageName = miscdata.optString("package", "");
            String referrerSource = miscdata.optString("referrer_source", "");

            if (packageName == null || packageName.trim().length() <= 0) {
                jsonObject.put("app_pkg", "NULL");
                Log.e(TAG_BST_REFERRAL, "Failed sending stat to cloud for packageName = " + packageName +  ", referrerSource = " + referrerSource);
                return;
            }
            jsonObject.put("app_pkg", packageName);
            jsonObject.put("referrer_source", referrerSource);

            String referral = miscdata.optString("mod_install_referrer", "");
            jsonObject.put("referral", referral);
            String referralData = mBstAppReferralList.getOrDefault(packageName, "{}");
            if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "bstMonitorReferralPath send http request package=" + packageName + ", market_link=" + referral);

            jsonObject.put("market_link", referral);

            JSONObject json = new JSONObject(referralData);
            if (json.optBoolean(referrerSource + "_stat", false)) {
                Log.w(TAG_BST_REFERRAL, "Stat already sent so returning, packageName = " + packageName + ", referrerSource = " + referrerSource);
                return;
            }

            String installerPackage = ((packageName != null) ? getPackageManager().getInstallerPackageName(packageName) : "");
            String home_app_ver = appContext.getPackageManager().getPackageInfo("com.bluestacks.home", 0).versionName;
            jsonObject.put("home_app_ver", home_app_ver);
            miscdata.put("system_utc_time", getCurrentUtcTime());
            miscdata.put("system_current_time", getSystemCurrentTime());
            miscdata.put("system_boot_time", getSystemBootTime());
            miscdata.put("installer_package", installerPackage);
            jsonObject.put("device_id", mGoogleAdId);
            jsonObject.put("prod_ver", mProdVersion);

            ContentValues values = new ContentValues();
            values.put("home_app_ver", home_app_ver);
            values.put("referrer_source", referrerSource);
            values.put("app_pkg", packageName);
            values.put("market_link", referral);
            values.put("misc_data", miscdata.toString());
            values.put("device_id", mGoogleAdId);
            values.put("prod_ver", mProdVersion);

            postRequestToCloud(cloudUrl, values, DBG_BST_REFERRAL, TAG_BST_REFERRAL);

            json.put(referrerSource + "_stat", true);

            mBstAppReferralList.put(packageName, json.toString());
            if (BstUtils.writeListToFile(mBstAppReferralList, appReferralListFile)) {
                if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, appReferralListFile + " successfully updated");
            }
        } catch (Exception ex) {
            Log.e(TAG_BST_REFERRAL, "Exception in sending stat to cloud " + ex.getMessage());
            try {
            jsonObject.put("is_exception", true);
            jsonObject.put("stackTrace", Log.getStackTraceString(ex));
            } catch(Exception e) {
                if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "exception before sending other_install_referrer");
            }
            BstCommandProcessorApplication.getInstance().sendBstCmdReferrerStats("other_install_referrer", packageName, jsonObject);
            ex.printStackTrace();
        }
    }

    private static String getCurrentUtcTime() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MMM-dd HH:mm:ss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return simpleDateFormat.format(new Date());
    }

    private String getSystemCurrentTime() {
        return String.valueOf(System.currentTimeMillis()/1000);
    }

    private String getSystemBootTime() {
        return String.valueOf((SystemClock.elapsedRealtime())/1000);
    }

    public void setServiceComponentState(String service, boolean enabled) {
        Log.d(TAG, "setServiceComponentState called with args, service : " + service + " state, enabled: " + enabled);
        if (service == null || service.length() == 0)
            return;

        IPackageManager mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (mPm == null) {
            Log.e(TAG, "Error: Could not access the Package Manager.  Is the system running?");
            return;
        }

        ComponentName cn = ComponentName.unflattenFromString(service);
        String pkgName = cn.getPackageName();
        if (!isPackageInstalled(pkgName)) {
            Log.d(TAG, "Package " + pkgName + " not installed");
            return;
        }

        int curState = -1;
        try {
            curState = mPm.getComponentEnabledSetting(cn, getAppContext().getUserId());
        } catch (Exception e) {
            Log.e(TAG, "Exception in getComponentEnabledSetting : " + e.getMessage());
        }

        int newState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (enabled)
            newState = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        else
            newState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        if (cn == null) {
            Log.d(TAG,"invalid componentname/service passed...returning");
            return;
        }

        if (curState != newState) {
            setServiceComponentState(mPm, cn, newState, PackageManager.DONT_KILL_APP, getAppContext().getUserId());
        }
        return;
    }

    private String enabledSettingToString(int state) {
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return "default";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return "enabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "disabled";
        }
        return "unknown";
    }

    /*
     * This function tells if we have a google account logged in or not.
     * @hide
     */
    boolean deviceHasGoogleAccount(){
        AccountManager accMan = AccountManager.get(getAppContext());
        Account[] accArray = accMan.getAccountsByType("com.google");
        return accArray.length >= 1;
    }

    private void checkIfLastShutdownWasProper()
    {
        boolean isFirstBoot = false ;
        Log.d(TAG, "checkIfLastShutdownWasProper() called");
        /*int fixFiles = android.os.SystemProperties.getInt("bst.config.fix_corruptfiles",0);

        if (fixFiles <= 0) {
            Log.d(TAG, "checkIfLastShutdownWasProper: fixFile " + fixFiles);
            return;
        }*/

       IPackageManager mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
       try {
           isFirstBoot = mPm.isFirstBoot();
            Log.d(TAG, "isFirstBoot : "+ isFirstBoot);
       } catch (Exception e) {
            e.printStackTrace();
       }
       boolean accountPresent = deviceHasGoogleAccount();

        boolean setupWizardPresent = isPackageInstalled("com.google.android.setupwizard");

        int deviceProvisioned = Settings.Global.getInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0);
        int userSetupComplete = Settings.Secure.getInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0);

        // If account is present and deviceProvisioned or userSetupComplete values are wrong correct them
        // If account is not present,and setupWizard is also not present(means Provision.apk was bundled)
        // correct the value if values are wrong.
        if ( !isFirstBoot && (accountPresent || !setupWizardPresent)) {
            if ((deviceProvisioned == 0 || userSetupComplete == 0)) {
                // this would mean we have deleted Settings.Global or Settings.Secure file,so putting correct values so that setupwizard is not shown
                Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);
            }
        }
    }

    private boolean isPackageInstalled(String packageName)
    {
        try {
            PackageManager pm = getPackageManager();
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            Log.e (TAG, "Error in isPackageInstalled : " + e.getMessage());
        }
        return false;
    }

    // Monitor utility for /data/downloads/.tmp location to observe
    // killZygote request or app crashes notification or notification
    // manager requests
    private void monitorBstDataPath()
    {
        if(DBG) Log.d(TAG, "Starting the monitorBstDataPath() function.\n");

        try {
            File tmpdir = new File(BstCommandProcessorPath);
            tmpdir.mkdirs();
            FileUtils.setPermissions(tmpdir.getCanonicalPath(), FileUtils.S_IRWXO|FileUtils.S_IRWXG|FileUtils.S_IRWXU, -1, -1);
        }
        catch (Exception e)
        {
            Log.e (TAG, "Exception in monitorBstDataPath: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        mObserver = new FileObserver(BstCommandProcessorPath, OBSERVER_EVENTS) {
            public void onEvent(int event, String path) {
                if (path.equals(".GL11Toast")) {
                    String fullPath = BstCommandProcessorPath + path;
                    File f = new File(fullPath);
                    Scanner input;
                    String pkg = null;
                    ApplicationInfo ai;
                    PackageManager pm = getAppContext().getPackageManager();
                    try {
                        input = new Scanner(f);
                        pkg = input.nextLine().trim();
                        ai = pm.getApplicationInfo( pkg, 0);
                    } catch(Exception e) {
                        ai = null;
                        e.printStackTrace();
                    }
                    final String applicationName = (String) (ai != null ? pm.getApplicationLabel(ai) : "This app");
                    Log.d(TAG, "GL 1.1 crash, pkg: " + pkg + " applicationName :" + applicationName);
                    String msg = applicationName + " is requesting graphics APIs that are not available on your system and has been terminated.";
                    showAlertDialog(msg);
                    f.delete();
                }
                else if (path.equals(".killZygote"))
                {
                    Log.e(TAG, "System observed an unrecoverable error. Restarting system_server.. \n");
                    String fullPath = BstCommandProcessorPath + path;
                    File f = new File(fullPath);
                    f.delete();
                    // This will be called from android internals when there are
                    // no other graceful options left to recover like in case of
                    // sharedBufferStack LockError
                    int pid = getProcessPid("system", Process.SYSTEM_UID);
                    Log.d(TAG, "PID of system_server:  " + pid);
                    if (pid > 0)
                    {
                        android.os.Process.killProcess(pid);
                    }
                }
            }
        };
    }

    /**
     * Load and parse system hosts file.
     *
     * Some IAP cheat apps like Freedom add entries for google urls to loopback address
     * and act as a proxy to fake IAP transaction status. So, we check for any such entry
     * to determine whether such an app is currently working or not on the system. Depending
     * upon this, we take a call to declare IAP transaction as fake or not while reporting to
     * cloud.
     */
    private void checkIfFakeIAPEngineEnabled(String path)
    {
        synchronized(mLockFakeIAPEngineEnabled) {
            final Pattern WHITESPACES = Pattern.compile("[ \t]+");
            BufferedReader buff = null;

            try {
                File file = new File(path);
                // file != null is always true
                if (file.exists() && file.isFile()) {
                    buff = (new BufferedReader(new FileReader(file)));
                }

                if (buff == null) {
                    // either file doesn't exist or not able to read file properly, in
                    // any case, return empty map
                    Log.d(TAG_BST_IAP, "returning from loadHostsFileEntries as either hosts file is not present or not accessible");
                    return;
                }

                String line = null;
                while ((line = buff.readLine()) != null) {
                    // remove comment
                    int commentPosition = line.indexOf('#');
                    if (commentPosition != -1) {
                        line = line.substring(0, commentPosition);
                    }
                    // skip empty lines
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    // split
                    List<String> lineParts = new ArrayList<String>();
                    for (String s: WHITESPACES.split(line)) {
                        if (!s.isEmpty()) {
                            lineParts.add(s);
                        }
                    }

                    // a valid line should be [IP, hostname, alias*]
                    if (lineParts.size() < 2) {
                        // skip invalid line
                        Log.d(TAG_BST_IAP, "ignoring invalid line : " + line + " , as it doesn't contain both ip and hostname");
                        continue;
                    }

                    if (lineParts.contains(FAKE_IAP_ENTRY)) {
                        isFakeIAPEngineEnabled = true;
                        break;
                    }

                    isFakeIAPEngineEnabled = false;
                }
            } catch (Exception e) {
                Log.e(TAG_BST_IAP, "Exeption while parsing systes hosts entries: " + e);
                e.printStackTrace();
            } finally {
                try {
                    buff.close();
                } catch (IOException e) {
                    Log.w(TAG_BST_IAP, "Failed to close reader: " + e);
                }
            }

            if (DBG_BST_IAP) Log.d(TAG_BST_IAP, "hosts file read successfully, isFakeIAPEngineEnabled: " + isFakeIAPEngineEnabled);
        }
        return;
    }

    /*
     * Monitor changes to System Hosts File
     */
    private void monitorHostsFile()
    {
        if(DBG_BST_IAP) Log.d(TAG_BST_IAP, "Starting the HostFile monitor");

        hostsFileObserver = new FileObserver(SYSTEM_HOSTS_FILE, FileObserver.CLOSE_WRITE | FileObserver.DELETE) {
            public void onEvent(int event, String path) {
                if(DBG_BST_IAP) Log.i(TAG_BST_IAP, "System Hosts file has been modified.(" + event + ") Reloading ...\n");
                checkIfFakeIAPEngineEnabled(SYSTEM_HOSTS_FILE);
            }
        };
        hostsFileObserver.startWatching();
    }

    /*
     * Load System Hosts File and start FileObserver on it
     */
    private void initHostsFile()
    {
        if(DBG_BST_IAP) Log.d(TAG_BST_IAP, "Initialize System Hosts File Entries...");
        checkIfFakeIAPEngineEnabled(SYSTEM_HOSTS_FILE);
        monitorHostsFile();
    }

    //return the package PID
    private int getProcessPid(String procName, int procUid) {
        RunningAppProcessInfo info=null;
        int retPid = -1;
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        List <RunningAppProcessInfo> l = am.getRunningAppProcesses();

        if (l == null)
        {
            Log.w(TAG, "getProcessPid not able to find any RunningAppProcess");
            return retPid;
        }

        Iterator <RunningAppProcessInfo> i = l.iterator();
        while(i.hasNext())
        {
            info = i.next();
            if (VERBOSE) Log.d(TAG, " getProcessPid Process: " + info.processName + " pid: " + info.pid + " importance: " + info.importance + " uid: " + info.uid);
            if(info.uid == procUid && info.processName.startsWith(procName))
            {
                if (DBG) Log.d(TAG, " getProcessPid returns Process: " + info.processName + " pid: " + info.pid + " uid: " + info.uid);
                retPid = info.pid;
                break;
            }
        }
        return retPid;
    }

    // TODO - replace call to this function with getApplication or getApplicationContext API
    // depending upon whether you need an Application object or Context object in return
    public static BstCommandProcessorApplication getInstance() {
        return singleton;
    }

    void showToastMessage(final String msg, final int duration) {
        showToastMessage(msg,duration,Gravity.BOTTOM);
    }

    private void showToastMessage(final String msg, final int duration, final int gravity) {
        if (DBG) Log.d (TAG, "in showToastMessage, msg: " + msg + " duration: " + duration + " (mHandler != null) = " + (mHandler != null));
        final Context c = getAppContext();
        if (mHandler != null && c != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast toast = Toast.makeText(c, msg, duration);
                    toast.setGravity(gravity, 0, 0);
                    toast.show();
                }
            });
        }
    }

    private void showAlertDialog(final String msg)
    {
        if (DBG) Log.d (TAG, "in showAlertDialog, msg: " + msg + " (mHandler != null) = " + (mHandler != null));
        final Context c = getAppContext();
        if (mHandler != null && c != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    AlertDialog alert;
                    AlertDialog.Builder builder = new AlertDialog.Builder(getAppContext());
                    builder.setMessage(msg);
                    builder.setNeutralButton("Ok", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    });
                    alert = builder.create();
                    alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                    alert.show();
                }
            });
        }
    }

    public static KeyCommandExecutor getKeyCommandExecutor() {
        return mKeyCommandExecutor;
    }

    public static Context getAppContext() {
        return appContext;
    }

    void setServiceHandler(Service service) {
        mService = service;
    }

    Service getServiceHandler() {
        return mService;
    }

    // set handler for commandLoop
    public void setCommandHandler(BstCommandLoop cmdHandler) {
        mCmdHandler = cmdHandler;
    }

    public BstCommandLoop getCommandHandler() {
        return mCmdHandler;
    }

    /*
     * Monitor changes to /data/downloads path
     */
    private void monitorDataDownloadsList()
    {
        if (DBG) Log.d(TAG, "Starting the suppressedAppList monitor");
        studioApkFileObserver = new FileObserver(studioZipInstallFilePath, SUPPRESSED_OBSERVER_EVENTS) {
            public void onEvent(int event, String path) {
                if (path == null) {
                    return;
                }
                if (path.startsWith("studio_app_install_status_")) {
                    if(event == FileObserver.MOVED_TO) {
                        String filePath = studioZipInstallFilePath + path;
                        String pkgName = path.replace("studio_app_install_status_", "");
                        Log.d(TAG, "filePath : " + filePath + " pkgName : " + pkgName);
                        //mBstHostCallManagerService.configVDNoFlush(true);
                        String content = BstCommandProcessorUtils.readFile(filePath);
                        try {
                            JSONObject object = new JSONObject(mStudioApkPkgMap.get(pkgName));
                            if (content.equals("")) {
                                mCmdHandler.sendShowHideAppInLauncher(-1, pkgName);
                                mCmdHandler.sendShowHideAppInGameCenter(-1, pkgName);
                                mBstHostCallManagerService.onInstallApkCompleted(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, object.optString("apk_file_name"), "", object.optString("attempt_id"), pkgName);
                                return;
                            }

                            JSONObject obj = new JSONObject(content);
                            if (obj.optBoolean("success", false)) {
                                mCmdHandler.sendShowHideAppInLauncher(1, pkgName);
                                mCmdHandler.sendShowHideAppInGameCenter(1, pkgName);
                                if(mStudioApkPkgMap.containsKey(pkgName)) {
                                    BstCommandProcessorApplication.getInstance().updateInstallTimeListForPackage(pkgName, false);
                                    BstCommandProcessorApplication.getInstance().sendAppInstallRequest(pkgName, false);
                                }
                                mBstHostCallManagerService.onInstallApkCompleted(0, object.optString("apk_file_name"), "", object.optString("attempt_id"), pkgName);
                            } else {
                                mCmdHandler.sendShowHideAppInLauncher(-1, pkgName);
                                mCmdHandler.sendShowHideAppInGameCenter(-1, pkgName);
                                mBstHostCallManagerService.onInstallApkCompleted(obj.optInt("error_code"), object.optString("apk_file_name"), obj.optString("error_message"), object.optString("attempt_id"), pkgName);
                            }
                        } catch(Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        };

        affiliateFileObserver = new FileObserver(affiliateFilePath, SUPPRESSED_OBSERVER_EVENTS) {
            public void onEvent(int event, String path) {
                if (path == null) {
                    return;
                }
                // #TODO check for rc and ib
                if (path.equals(".ir") || path.equals(".rc") || path.equals(".ib") || path.equals(".or")) {
                    if(event == FileObserver.CLOSE_WRITE) {
                        String filePath = affiliateFilePath + path;
                        sendReferrerStatToCloud(filePath);
                        try {
                            File file = new File(filePath);
                            if (file.exists()) {
                                stopWatching();
                                try (FileOutputStream writer = new FileOutputStream(filePath)) {
                                    writer.write(("").getBytes());
                                    writer.close();
                                } catch (Exception ex) {
                                    Log.w(TAG, "Exception : " + ex.getMessage());
                                    ex.printStackTrace();
                                }
                                startWatching();
                            } else {
                                Log.e(TAG, "Failed to delete/create file");
                            }
                        } catch (Exception ex) {
                            Log.e(TAG, "Exception " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }
                } else if (path.equals(".airr")) {
                    if (event == FileObserver.CLOSE_WRITE) {
                        String filePath = affiliateFilePath + path;
                        Log.d(TAG, "filePath : " + filePath);
                        sendAppInstallReferrerRequest(filePath);
                    }
                }
            }
        };
    }

    HandlerThread getmHandlerThreadForHostCalls() {
        return mHandlerThreadForHostCalls;
    }

    private Bitmap convertToBitmap(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    //save icon to file
    private String saveIcontoFile(Drawable d, PackageManager pm, String fileName) {
        String filePath = "";
        if (d == null)
            return "";

        String timestamp = String.valueOf(System.currentTimeMillis());
        Bitmap bitmap = convertToBitmap(d);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, bytes);

        File iconDir = new File(BstSharedFolderPath);
        if (!iconDir.exists()) {
            Log.e(TAG, BstSharedFolderPath + " does not exists, check mounts");
            return "";
        }
        try {
            FileUtils.setPermissions(iconDir.getCanonicalPath(), FileUtils.S_IRWXO|FileUtils.S_IRWXG|FileUtils.S_IRWXU, -1, -1);
        } catch (Exception e) {
            Log.e (TAG, "Exception in setting FilePermission: " + e.getMessage());
            e.printStackTrace();
            //ignore it for time being
        }

        String filePathTemp = BstSharedFolderPath + File.separator + fileName + ".tmp." + timestamp;
        filePath = BstSharedFolderPath + File.separator + fileName;
        if (DBG) Log.d(TAG, "in saveIcontoFile, iconFilePath : " + filePath);
        File f = new File(filePathTemp);
        try {
            f.createNewFile();
            //write the bytes in file
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(bytes.toByteArray());
            fo.flush();
            fo.close();
            f.renameTo(new File(filePath));
        } catch (Exception e) {
            Log.e(TAG, "in saveIcontoFile, Exception while writing file: " + filePath);
            e.printStackTrace();
            return "";
        }
        if (DBG) Log.d(TAG, "Icon file: " + fileName + " saved, returning ");
        return fileName;
    }

    // Get default icon for a given package
    private Drawable getDefaultIconFromPackage(String packageName, PackageManager pm)  {
        ApplicationInfo appInfo = null;
        try {
            appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (Exception e) {
            Log.e(TAG, "in getDefaultIconFromPackage, packageName : " + packageName);
            e.printStackTrace();
            return null;
        }
        return appInfo.loadIcon(pm);
    }

    private String getPackageSource(String packageName) {
        String installerPackage = getPackageManager().getInstallerPackageName(packageName);
        if (installerPackage != null) {
            if ("com.bluestacks.BstCommandProcessor".equals(installerPackage))
                return "local-apk";
            else if ("com.sec.android.app.samsungapps".equals(installerPackage))
                return "samsung";
            else if ("com.skt.skaf.A000Z00040".equals(installerPackage))
                return "onestore";
            else
                return installerPackage;
        }
        return "user";
    }

    void updateInstallTimeListForPackage(String pkgName, boolean updated) {
        // Add package's firstInstallTime to the list along with the information whether this is a fresh install case or upgrade case. This information will be used for Affiliate related code in BaseBundle file.
        long firstInstallTime = 0L;
        PackageInfo pi = null;
        try {
            pi = mService.getPackageManager().getPackageInfo(pkgName, 0);
            firstInstallTime = pi.firstInstallTime;
        } catch (Exception ex) {
            Log.e(TAG, "Exception while fetching first install time from packagemanager");
        }
        try {
            ArrayList<Long> list = new ArrayList<Long>();
            list.add(firstInstallTime / 1000);
            if (updated)
                list.add(Long.valueOf(1));
            else
                list.add(Long.valueOf(0));
            mBstInstallTimeList.put(pkgName, list);
            BstUtils.writeListToFile(mBstInstallTimeList, bstInstallTimeListPath);
        } catch (Exception ex) {
            Log.e(TAG, "Exception while adding to mBstInstallTimeList");
        }
    }

    void sendAppUninstallRequest(String pkgName) {
        try {
            PackageManager pm = mService.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkgName, 0);
            if (BstCommandProcessorUtils.isSystemApp(ai)) {
                if (DBG) Log.d(TAG, "sendAppUninstallRequest pkgName: " + pkgName + " is a system app so returning");
                return;
            }
        } catch (Exception ex) {
            if (DBG) Log.d(TAG, "sendAppUninstallRequest pkgName: " + pkgName + " is not a system app");
        }
        getCommandHandler().onAppUninstalled(pkgName);
    }

    void sendAppInstallRequest(String pkgName, boolean updated) {
        if(DBG) Log.d(TAG, "in sendAppInstallRequest, pkgName: " + pkgName + " updated: " + updated);

        //This is the case for Package Installation, get the App info like
        //PackageName, Launchable activities, icon file, label name etc
        try {
            PackageManager pm = mService.getPackageManager();

            ApplicationInfo ai = pm.getApplicationInfo(pkgName, 0);
            if (BstCommandProcessorUtils.isSystemApp(ai)) {
                if (DBG) Log.d(TAG, "pkgName: " + pkgName + " is a system app so returning");
                return;
            }

            String source = getPackageSource(pkgName);

            PackageInfo pi = pm.getPackageInfo(pkgName, 0);
            int versionCode = (pi != null) ? pi.versionCode : 0;
            String versionName = (pi != null) ? pi.versionName : "";

            Intent  mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.setPackage(pkgName);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            String activity = "";
            String appLabel = "";
            String iconFileName = "";

            /* Populate the list of Apps that matches the query data */
            List<ResolveInfo> infoList = pm.queryIntentActivities(mainIntent, 0);
            if (infoList == null) {
                Log.e(TAG, "No matching package found by PM for the given data: " + pkgName);
                return;
            }

            //TODO: Currently we are sending only one launchable activity to host even if there are
            // multiple launchable activities in the package.
            for (ResolveInfo info : infoList) {
                activity = info.activityInfo.name;
                appLabel = (String) info.loadLabel(pm);
                String fileName = pkgName + ".png";
                if (DBG) Log.d(TAG, "Got app info - Package: " + pkgName + " Activity: " + activity);
                
                Drawable icon = null;
                android.content.res.Resources res = null;
                
                int[] dpiLevels = {
                    android.util.DisplayMetrics.DENSITY_XXXHIGH,
                    android.util.DisplayMetrics.DENSITY_XXHIGH,
                    android.util.DisplayMetrics.DENSITY_XHIGH,
                    android.util.DisplayMetrics.DENSITY_HIGH,
                    android.util.DisplayMetrics.DENSITY_MEDIUM
                };

                try {
                    res = pm.getResourcesForApplication(pkgName);
                    res.updateConfiguration(res.getConfiguration(), res.getDisplayMetrics());
                    
                    // Try to get icon from ActivityInfo
                    if (info.activityInfo.icon != 0) {
                        int iconResId = info.activityInfo.icon;
                        for (int dpi : dpiLevels) {
                            icon = res.getDrawableForDensity(iconResId, dpi, null);
                            if (icon != null) break;
                        }
                    }
                    
                    // Try ApplicationInfo if needed
                    if (icon == null && ai.icon != 0) {
                        for (int dpi : dpiLevels) {
                            icon = res.getDrawableForDensity(ai.icon, dpi, null);
                            if (icon != null) break;
                        }
                    }
                    
                    // Try PackageManager as last resort
                    if (icon == null) {
                        icon = pm.getApplicationIcon(ai);
                        
                        if (icon == null) {
                            icon = pm.getApplicationIcon(pkgName);
                        }
                    }
                    
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get app icon: " + e.getMessage());
                }
                
                // Use default icon if all attempts failed
                if (icon == null) {
                    icon = getDefaultIconFromPackage(pkgName, pm);
                }
                
                iconFileName = saveIcontoFile(icon, pm, fileName);
            }

            boolean isHomeApp = false;

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> launcherPkgList = pm.queryIntentActivities(intent, 0);

            if (!launcherPkgList.isEmpty()) {
                for (ResolveInfo resolveInfo : launcherPkgList) {
                    if (resolveInfo.activityInfo.packageName.equals(pkgName)) {
                        isHomeApp = true;
                        break;
                    }
                }
            }

            String orientation = "";
            if (mBstFilterAppsManager.isRotateDisabled(pkgName)) {
                orientation = "Disabled";
            }

            getCommandHandler().onAppInstalled(pkgName, activity, appLabel, versionCode, versionName, iconFileName, source, updated, isHomeApp, orientation);
        } catch (Exception ex) {
            Log.w(TAG, "Exception while sending onAppInstalled request for package: " + pkgName + ", message: " + ex.getMessage());
            if (DBG) ex.printStackTrace();
        }
    }

    void setLocationData()
    {
        SharedPreferences sharedPref = BstCommandProcessorApplication.getInstance().getSharedPreferences("BstCmdPrefs", 0);
        String data = sharedPref.getString("bstlocationdata", "");
        Log.d(TAG, "setLocationData on boot receiver" + data);
        SystemProperties.set("bst.config.sysLoc", data);
    }
    
    void removeEntryFromAppSetting(String pkgName) {
        String filePath = "/data/downloads/.app.settings";
        HashMap<String,String> appSettings = BstCommandProcessorUtils.readAppSettingsFile(filePath);
        if (appSettings.containsKey(pkgName)) {
            appSettings.remove(pkgName);
            BstCommandProcessorUtils.writeAppSettingsFile(filePath, appSettings);
        }
    }

     
    private class makeHostCallsSeparateThread implements Runnable {

        final int reqType;
        final String reqData;
        makeHostCallsSeparateThread(final int reqType, final String reqData)
        {
            this.reqType = reqType;
            this.reqData = reqData;
            Thread t = new Thread(this);
            t.start();
        }

        public void run()
        {
            makeHostCallInternal(reqType, reqData);
        }
    }

    private static class TaskProcessReferralIntent extends AsyncTask<Void, Void, Void>  {
        private final boolean DBG = true || android.os.SystemProperties.getInt("bst.debug.bstcmdapp", 0) > 0;
        private final boolean DBG_BST_REFERRAL = DBG || SystemProperties.getInt("bst.debug.referral", 0) > 0;
        private final String TAG = "BstCommandProcessor-TaskProcessReferralIntent-Affiliate";
        private final Intent intent;

        private TaskProcessReferralIntent(Intent intent) {
            this.intent = intent;
        }

        @Override
        protected Void doInBackground(Void... params) {
            String action = intent.getAction();
            if (!action.equals("BST_REFERRAL")) {
                if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "action is not BST_REFERRAL");
                return null;
            }

            boolean isException = false;
            String stackTrace = "";
            JSONObject jsonObject = new JSONObject();
            String pkgName = "";
            boolean result = false;
            try {
                BstCommandProcessorApplication.getInstance().setGoogleAdId();
                PackageInfo pi = null;
                pkgName = intent.getStringExtra("pkg_name");
                jsonObject.put("app_pkg", pkgName);
                String pkgReferral = intent.getStringExtra("pkg_referral");
                jsonObject.put("pkg_referral", pkgReferral);
                boolean pkgSkipReferral = intent.getBooleanExtra("skip_referral", false);
                jsonObject.put("skip_referrer", pkgSkipReferral);
                long referrer_click_timestamp_seconds = intent.getLongExtra("bst_referrer_click_timestamp_seconds", 0)/1000;
                jsonObject.put("bst_referrer_click_timestamp_seconds", referrer_click_timestamp_seconds);
                String request_begin_time = String.valueOf(intent.getLongExtra("request_begin_time", 0));
                String first_url_hit_time = String.valueOf(intent.getLongExtra("first_url_hit_time", 0));
                String final_url_hit_time = String.valueOf(intent.getLongExtra("final_url_hit_time", 0));
                jsonObject.put("request_begin_time", request_begin_time);
                jsonObject.put("first_url_hit_time", first_url_hit_time);
                jsonObject.put("final_url_hit_time", final_url_hit_time);
                String callingSource = intent.getStringExtra("calling_source");
                jsonObject.put("calling_source", callingSource);
                try {
                    pi = BstCommandProcessorApplication.getAppContext().getPackageManager().getPackageInfo(pkgName, 0);
                } catch (Exception ex) {
                    Log.w(TAG, "Exception: " + ex.getMessage());
                    if (DBG) ex.printStackTrace();
                }
                // Check if package is already installed or not, if installed referrer_click_timestamp will be
                // more than the app install time, so will drop the referrer in this case.
                if (pi != null && (pi.firstInstallTime <= (referrer_click_timestamp_seconds + 1))) {
                    jsonObject.put("error", "package installed before click recorded firstInstallTime " + pi.firstInstallTime + "  referrer_click_timestamp_seconds : " + referrer_click_timestamp_seconds);
                    BstCommandProcessorApplication.getInstance().removePackageFromReferrerLists(pkgName);
                    Log.w(TAG, "Package installed before the click recorded, so returning...");
                } else {
                    //Removing package from thirdPartyReferredPackage list.
                    BstCommandProcessorApplication.mBstPackageReferrerPresent.remove(pkgName);
                    BstUtils.writeListToFile(BstCommandProcessorApplication.mBstPackageReferrerPresent, BstCommandProcessorApplication.packageReferrerFile);
                    android.os.SystemProperties.set("bst.config.referrerpackage", "");

                    JSONObject json = new JSONObject();


                    // Create file that will store all the modified data.
                    File modifiedDataFile = File.createTempFile(".mdf_", null, new File(affiliateFilePath));
                    String modifiedDataFilePath = modifiedDataFile.getAbsolutePath();
                    if (FileUtils.setPermissions(modifiedDataFilePath, perms_666, -1, -1) != 0) {
                        Log.e(TAG, "Failed to change permissions for the file");
                        jsonObject.put("error", "failed to change permissions for file " + affiliateFilePath);
                    } else {
                        json.put("referrer", pkgReferral);
                        json.put("mod_referrer_click_timestamp", String.valueOf(referrer_click_timestamp_seconds));
                        json.put("gplay_install_referrer_stat", false);
                        json.put("gplay_other_install_referrer_stat", false);
                        json.put("skip_referrer", pkgSkipReferral);
                        json.put("request_begin_time", request_begin_time);
                        json.put("first_url_hit_time", first_url_hit_time);
                        json.put("final_url_hit_time", final_url_hit_time);
                        json.put("calling_source", callingSource);
                        json.put("mod_data_file_path", modifiedDataFilePath);
                        jsonObject.put("mod_data_file_path", modifiedDataFilePath);
                        BstCommandProcessorApplication.mBstAppReferralList.put(pkgName, json.toString());

                        if (DBG_BST_REFERRAL) Log.d(TAG, "pkgSkipReferral = " + pkgSkipReferral + ", referrer_click_timestamp_seconds = " + referrer_click_timestamp_seconds + ", pkgName = " + pkgName);
                    }
                }
                //dump the list into a file /data/downloads/.tmp/.fl
                result = BstUtils.writeListToFile(BstCommandProcessorApplication.mBstAppReferralList, BstCommandProcessorApplication.appReferralListFile);
                if (result) {
                    if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "file successfully updated");
                }
            } catch (Exception ex) {
                isException = true;
                stackTrace = Log.getStackTraceString(ex);
                if (DBG_BST_REFERRAL) Log.d(TAG, "Exception ex: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                try {
                    jsonObject.put("is_exception", isException);
                    jsonObject.put("stackTrace", stackTrace);
                    jsonObject.put("write_to_file", result);
                } catch(Exception e) {
                    if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "JSONException in finally in BST_REFERRAL");
                }
                if (SystemProperties.getInt("bst.feature.send_offer_stats", 0) >= 1) {
                    BstCommandProcessorApplication.getInstance().sendBstCmdReferrerStats("bstcmd_referrer_info", pkgName, jsonObject);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
        }
    }

    private static class TaskProcessOfferIntent extends AsyncTask<Void, Void, Void>  {
        private final boolean DBG = true || android.os.SystemProperties.getInt("bst.debug.bstcmdapp", 0) > 0;
        private final boolean DBG_BST_REFERRAL = DBG || SystemProperties.getInt("bst.debug.referral", 0) > 0;
        private final String TAG = "BstCommandProcessor-TaskProcessOfferIntent-Affiliate";
        private final Intent intent;

        private TaskProcessOfferIntent(Intent intent) {
            this.intent = intent;
        }

        @Override
        protected Void doInBackground(Void... params) {
            //#TODO add try catch and send stat in finally block ,, event name : bstcmd_app_offer
            //Data : pkgName, local time and boot time, current mBstOfferPackageList
            //get is_offer and send value in stat as well, result from writeListToFile
            boolean isException = false;
            String stackTrace = "";
            String pkgName = "";
            boolean writeToList = false;
            JSONObject jsonObject = new JSONObject();
            try {
                String action = intent.getAction();
                if (DBG_BST_REFERRAL) Log.d(TAG, "intent=" + action + " received");
                if (action == null || !action.equals("BST_OFFER_PACKAGE")) {
                    Log.e(TAG, "action is not BST_OFFER_PACKAGE");
                    return null;
                }
                if (action.equals("BST_OFFER_PACKAGE")) {
                    pkgName = intent.getStringExtra("pkg_name");
                    jsonObject.put("app_pkg", pkgName);
                    boolean isOffer = intent.getBooleanExtra("is_offer", false);
                    jsonObject.put("is_offer", isOffer);
                    if (isOffer) {
                        BstCommandProcessorApplication.mBstOfferPackageList.add(pkgName);
                    } else {
                        if (intent.getExtras().containsKey("reason"))
                            jsonObject.put("reason", intent.getStringExtra("reason"));
                        BstCommandProcessorApplication.mBstOfferPackageList.remove(pkgName);
                    }

                    if (BstUtils.writeListToFile(BstCommandProcessorApplication.mBstOfferPackageList, BstCommandProcessorApplication.offerPackageListFile)) {
                        writeToList = true;
                        if (DBG_BST_REFERRAL) Log.d(TAG, "intent = " + action + " received, is_offer : " + isOffer + " for pkgName " + pkgName + " mBstOfferPackageList updated");
                    }
                }
            } catch(Exception e) {
                isException = true;
                stackTrace = Log.getStackTraceString(e);
                e.printStackTrace();
            } finally {
                try {
                    jsonObject.put("is_exception", isException);
                    jsonObject.put("stacktrace", stackTrace);
                    jsonObject.put("write_to_list", writeToList);
                    String currentOfferList = Arrays.toString(BstCommandProcessorApplication.mBstOfferPackageList.toArray());
                    jsonObject.put("current_offerlist", currentOfferList);
                } catch(Exception e) {
                    if (DBG_BST_REFERRAL) Log.d(TAG, "Exception in finally in BST_OFFER_PACKAGE");
                }
                if (SystemProperties.getInt("bst.feature.send_offer_stats", 0) >= 1) {
                    BstCommandProcessorApplication.getInstance().sendBstCmdReferrerStats("bstcmd_app_offer", pkgName, jsonObject);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
        }
    }

    /**
     * Post a task to the background threadForHostCalls HandlerThread.
     * @hide
     */
    void postToHostCallThread(Runnable task) {
        if (mHandlerForHostCalls == null) {
            mHandlerForHostCalls = new Handler(mHandlerThreadForHostCalls.getLooper());
        }
        if (mHandlerForHostCalls != null) {
            mHandlerForHostCalls.post(task);
        } else {
            Log.e(TAG, "postToHostCallThread: mHandlerForHostCalls is null, running on caller thread");
            task.run();
        }
    }

    // This function calls makeHostCallInternal with the reqType and reqData received on the
    // threadForHostCalls thread, so that main thread is not blocked if some network call
    // takes some time.
    /*
     * @hide
     * */
    void makeHostCall(final int reqType, final String reqData) {
        if (mHandlerForHostCalls == null) {
            mHandlerForHostCalls = new Handler(mHandlerThreadForHostCalls.getLooper());
        }

        if (mHandlerForHostCalls != null) {
            mHandlerForHostCalls.post(new Runnable() {
                @Override
                public void run() {
                    if (DBG) Log.d(TAG, "In makeHostCall, call makeHostCallsSeparateThread reqType=" + reqType);
                    new makeHostCallsSeparateThread(reqType, reqData);
                }
            });
        } else {
            Log.e(TAG, "makeHostCall: mHandlerForHostCalls is null not sending request for: reqType = [" + reqType + "]");
        }
    }

    // Helper function to send Http request to the windows agent.
    // URL to which to send the request depends upon the requestType
    // Currently supported requestTypes:
    private void makeHostCallInternal(int reqType, String reqData) {
        if (DBG) Log.d(TAG, "makeHostCallInternal() called with: reqType = [" + reqType + "]");
        int rval = -1;
        try
        {
            if (mBstHostCallManagerService == null)
                mBstHostCallManagerService = (BstHostCallManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_HOST_CALL);
            switch (reqType) {
                case IMPORT_FILES:
                    String[] data  = reqData.split(",");
                    Log.d(TAG, "calling BstHostCallManagerService startImportFiles type : "+ data[0] + " isAllowMultiple : "+ Boolean.parseBoolean(data[1]));
                    mBstHostCallManagerService.startImportFiles(data[0], Boolean.parseBoolean(data[1]));
                    break;
                case EXPORT_FILES:
                    Log.d(TAG, "calling BstHostCallManagerService exportFiles: folder " + reqData);
                    mBstHostCallManagerService.exportFiles(reqData);
                    break;
                case CREATE_DESKTOP_SHORTCUT:
                    Log.d(TAG, "calling BstHostCallManagerService createDesktopShortcut: package " + reqData);
                    mBstHostCallManagerService.createDesktopShortcut(reqData);
                    break;
                case IMPORT_FILES_COMPLETED:
                    Log.d(TAG, "calling BstHostCallManagerService importFilesCompleted: folder" + reqData);
                    String[] array  = reqData.split("####");
                    mBstHostCallManagerService.importFilesCompleted(Integer.parseInt(array[1]), array[0]);
                    break;
                case OPEN_URL:
                    Log.d(TAG, "calling BstHostCallManagerService openUrl : url " + reqData);
                    mBstHostCallManagerService.openUrl(reqData);
                    break;
                case LAUNCH_BSX:
                    Log.d(TAG, "calling BstHostCallManagerService launchBsx");
                    mBstHostCallManagerService.launchBsx();
                    break;
                case UPDATE_STATS_INTERVAL:
                    Log.d(TAG, "calling BstHostCallManagerService setUsageStatsUpdateInterval");
                    int interval = Integer.parseInt(reqData);
                    mBstHostCallManagerService.setUsageStatsUpdateInterval(interval);
                    break;
                case START_EXPORT_FILES:
                    Log.d(TAG, "calling BstHostCallManagerService startExportFiles");
                    mBstHostCallManagerService.startExportFiles();
                    break;
                case CONSOLE_MODE_STATE_CHANGED:
                    Log.d(TAG, "calling BstHostCallManagerService consoleModeStateChanged : state " + reqData);
                    mBstHostCallManagerService.onConsoleModeStateChanged(Boolean.parseBoolean(reqData));
                    break;
                case AFFILIATE_TRACKING:
                    Log.d(TAG, "calling BstHostCallManagerService affiliateTrackingCompleted : pkg " + reqData);
                    mBstHostCallManagerService.affiliateTrackingCompleted(reqData);
                    break;
                case NOWGG_ACCOUNT_ADDED:
                    Log.d(TAG, "calling BstHostCallManagerService onNowggAccountAdded");
                    mBstHostCallManagerService.onNowggAccountAdded(reqData);
                    break;
                case UPDATE_QUEST_RULES:
                    Log.d(TAG, "calling BstHostCallManagerService onUpdateQuestRules : rules " + reqData);
                    mBstHostCallManagerService.onUpdateQuestRules(reqData);
                    break;
                case DIFFERENT_IMAGE_PKG:
                    Log.d(TAG, "calling BstHostCallManagerService onDifferentImagePkgClicked : pkg " + reqData);
                    mBstHostCallManagerService.onDifferentImagePkgClicked(reqData);
                    break;
                case SHOW_NOTIFICATION:
                    Log.d(TAG, "calling BstHostCallManagerService : onAppNotificationReceived data " + reqData);
                    mBstHostCallManagerService.onAppNotificationReceived(reqData);
                    break;
                case WALLET_MESSAGE:
                    Log.d(TAG, "calling BstHostCallManagerService : onWalletMessage : content " + reqData);
                    mBstHostCallManagerService.onWalletMessage(reqData);
                    break;
                case ADS_INFO_CLICK:
                    Log.d(TAG, "calling BstHostCallManagerService : onAdsInfoClick : content " + reqData);
                    mBstHostCallManagerService.onAdsInfoClick();
                    break;
                case REMOVE_BOOT_LOADING_SCREEN:
                    Log.d(TAG, "calling BstHostCallManagerService : removeBootLoadingScreen : isAdShown " + reqData);
                    mBstHostCallManagerService.removeBootLoadingScreen(Boolean.parseBoolean(reqData));
                    break;
                case INTERSTITIAL_AD_COMPLETED:
                    Log.d(TAG, "calling BstHostCallManagerService : interstitialAdCompleted : content " + reqData);
                    String[] splitArray  = reqData.split("####");
                    mBstHostCallManagerService.interstitialAdCompleted(splitArray[0], splitArray[1]);
                    break;
                case ON_NOWBUX_UPDATED:
                    Log.d(TAG, "calling BstHostCallManagerService : onNowbuxUpdated");
                    mBstHostCallManagerService.onNowbuxUpdated();
                    break;
                case ON_IAP_COMPLETED:
                    Log.d(TAG, "calling BstHostCallManagerService : onIAPCompleted : content " + reqData);
                    String[] iapSplitArray  = reqData.split("####");
                    mBstHostCallManagerService.onIAPCompleted(iapSplitArray[0], iapSplitArray[1]);
                    break;
                case UNZIP_FILE:
                    Log.d(TAG, "calling BstHostCallManagerService : unzipFile : content " + reqData);
                    String[] splitArrayUnzip  = reqData.split("####");
                    mBstHostCallManagerService.unzipFile(splitArrayUnzip[0], splitArrayUnzip[1], splitArrayUnzip[2], splitArrayUnzip[3]);
                    break;
                case GET_NOWGG_ACCOUNT:
                    Log.d(TAG, "calling BstHostCallManagerService onNowggAccountAdded");
                    mBstHostCallManagerService.onGetNowggAccount(reqData);
                    break;
                case INSTALL_APP_GAME_CENTER:
                    Log.d(TAG, "calling BstHostCallManagerService allowInstallApkGameCenter");
                    mBstHostCallManagerService.allowInstallApkGameCenter(reqData);
                default:
                    Log.d(TAG, "Invalid request");
            }

        }
        catch(Exception e)
        {
            Log.e(TAG, "Exception in makeHostCall of type: " + reqType + " error: " + e.getMessage());
            e.printStackTrace();
        }
        return;
    }

    void checkMockLocationPermission() {
        if (Settings.Secure.getInt(appContext.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION, 0) <= 0) {
            Settings.Secure.putInt(appContext.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION, 1);
        }
    }

    void removePackageFromIl2cppList(String packageName) {
        if (mBstFilterAppsManager == null)
            mBstFilterAppsManager = (BstFilterAppsManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_FILTER_APPS);
        mBstFilterAppsManager.updateIl2cppPkgs("~" + packageName);
    }

    void removePackageFromReferrerLists(String packageName) {
        if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "Removing package : " + packageName + " from lists");
        if (mBstAppReferralList.containsKey(packageName)) {
            String modDataFilePath = "";
            try {
                JSONObject referralDataJsonObj = new JSONObject(mBstAppReferralList.getOrDefault(packageName, ""));
                modDataFilePath = referralDataJsonObj.optString("mod_data_file_path", "");

                if (modDataFilePath != null && modDataFilePath.length() > 0) {
                    File modDataFile = new File(modDataFilePath);
                    if (modDataFile.exists()) {
                        if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "Removing modDataFilePath " + modDataFilePath);
                        modDataFile.delete();
                    }
                }
            } catch (Exception ex) {
                Log.w(TAG_BST_REFERRAL, "Failed to get modDataFilePath: " + ex.getMessage());
                if (DBG_BST_REFERRAL) ex.printStackTrace();
            }

            mBstAppReferralList.remove(packageName);
            BstUtils.writeListToFile(mBstAppReferralList, appReferralListFile);
        }

        if (mBstInstallTimeList.containsKey(packageName)) {
            mBstInstallTimeList.remove(packageName);
            BstUtils.writeListToFile(mBstInstallTimeList, bstInstallTimeListPath);
        }

        if (mBstOfferPackageList.contains(packageName)) {
            mBstOfferPackageList.remove(packageName);
            BstUtils.writeListToFile(mBstOfferPackageList, offerPackageListFile);
        }
    }


    public void postRequestToCloud(final String url, final ContentValues values) {
        postRequestToCloud(url, values, DBG, TAG);
    }

    public void postRequestToCloud(final String url, final ContentValues values, boolean debugLog, String logTag) {
        if (debugLog) Log.d(logTag, "postRequestToCloud, url: " + url);

        Thread thread = new Thread() {
            public void run() {
                BstHttpsConnectionHelper connhelper = null;
                try {
                    connhelper = new BstHttpsConnectionHelper(url,"POST");
                    connhelper.openConnection();
                    connhelper.writeValues(values);
                    int responseCode = connhelper.getResponseCode();
                    if (debugLog) Log.d(logTag, "Response Code for url " + url + " responseCode " + responseCode);

                    if (responseCode == HttpURLConnection.HTTP_OK) { //success
                        if (debugLog) {
                            HttpURLConnection conn = connhelper.getConnectionObj();
                            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            String inputLine;
                            StringBuffer response = new StringBuffer();

                            while ((inputLine = in.readLine()) != null) {
                                response.append(inputLine);
                            }
                            in.close();

                            // print result
                            Log.d(logTag, "postRequestToCloud url: " + url + ", response: " + response.toString());
                        }
                    } else {
                        Log.w(logTag, "ERROR in sending POST request for url: " + url + ", responseCode = " + responseCode);
                    }
                } catch (Exception e) {
                    Log.e(logTag, "Exception in sending POST request for url: " + url + ", exception: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    connhelper.closeConnection();
                }

                return;
            }
        };
        thread.start();

        if(debugLog) Log.d(logTag, "Returning from postRequestToCloud");
    }

    /*
     * @hide
     * */
    void setGoogleAdId() {

        Thread thread = new Thread() {
            public void run() {
                setGoogleAdIdInternal();
                return;
            }
        };
        thread.start();

        return;
    }

    void setGoogleAdIdInternal() {
        try {
            AdvertisingIdClient.Info idInfo = AdvertisingIdClient.getAdvertisingIdInfo(BstCommandProcessorApplication.getAppContext());
            if (idInfo == null ) {
                Log.d(TAG, "setGoogleAdIdInternal AdvertisingIdClient.getAdvertisingIdInfo return null");
                return;
            }
            String newGoogleAdId = idInfo.getId();
            if (DBG) Log.d(TAG, "setGoogleAdIdInternal mGoogleAdId = " + mGoogleAdId + ", newGoogleAdId = " + newGoogleAdId);
            if (!newGoogleAdId.equals(mGoogleAdId)) {
                mGoogleAdId = newGoogleAdId;
                SystemProperties.set("bst.android_google_ad_id", mGoogleAdId);
                mBstHostCallManagerService.setGoogleAdId(newGoogleAdId);
                if (DBG) Log.d(TAG, "setGoogleAdId: mGoogleAdId = " + mGoogleAdId); 
            } else {
                if (DBG) Log.d(TAG, "setGoogleAdId: mGoogleAdId already set to " + mGoogleAdId); 
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
