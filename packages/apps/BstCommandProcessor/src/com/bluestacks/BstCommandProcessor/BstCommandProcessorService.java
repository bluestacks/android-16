package com.bluestacks.BstCommandProcessor;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Service;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.storage.VolumeInfo;
import android.os.SystemProperties;
import android.os.Process;
import android.provider.Browser;
import android.provider.Settings;
import android.util.BstUtils;
import android.location.Criteria;
import android.location.LocationManager;
import android.location.Location;
import android.content.SharedPreferences;

import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import com.bluestacks.os.BstUtilsManager;
import com.bluestacks.os.BstFilterAppsManager;
import org.json.JSONObject;

import static com.bluestacks.BstCommandProcessor.BstCommandProcessorUtils.ChromeTabStructure;

public class BstCommandProcessorService extends Service {
    private static final String TAG = "BstCommandProcessor-Service";
    private static final String TAG_BST_REFERRAL = "BstCommandProcessor-Service-Affiliate";
    private static final String TAG_BST_IAP = "BstCommandProcessor-Service-GIAP";
    private static final boolean DBG = android.os.SystemProperties.getInt("bst.debug.bstcmdloop", 0) > 0;
    private static final boolean DBG_BST_REFERRAL = DBG || android.os.SystemProperties.getInt("bst.debug.referral", 0) > 0;
    private static final boolean DBG_BST_IAP = DBG || android.os.SystemProperties.getInt("bst.debug.iap", 0) > 0;

    private static BroadcastReceiver mPackageIntentReceiver = new BstPackageIntentsReceiver();
    private static BroadcastReceiver mBootCompletedReceiver = new BstBootCompletedReceiver();
    private static BroadcastReceiver mVolumeStateChangeReceiver = new BstVolumeStateChangeReceiver();

    private static final BstUtilsManager mBstUtilsManager = (BstUtilsManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_UTILS);
    private static final BstFilterAppsManager mBstFilterAppsManager = (BstFilterAppsManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.BST_FILTER_APPS);
    private static final LocationManager mLocationManager = (LocationManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.LOCATION_SERVICE);
    private static String delimiter = ";;";
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";

    /*
     * @hide
     */
    //Mapping between the packageName and there corresponding IAP info for which transaction was interrupted in between.
    public static HashMap<String, StringBuilder> giapPackageDescription = new HashMap<String, StringBuilder>();
    private BstCommandLoop cmdHandler = null;

    @Override
	public IBinder onBind(Intent arg0) {
        Log.d(TAG, "onBind called");
	    return null;
	}

    @Override
	public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");
        //Get the app handler and save service handler in its DS for future reference
        BstCommandProcessorApplication app = ((BstCommandProcessorApplication) getApplication());
        app.setServiceHandler(this);

        cmdHandler = new BstCommandLoop();
        app.setCommandHandler(cmdHandler);

        mBstUtilsManager.setBstProposedRotation(0);
        // Delete this file as we don't want to store the packages across sessions,
        // for which referrer was other than bluestacks.
        File file = new File(app.packageReferrerFile);
        if (file.exists()) {
            if (file.delete()) {
                if (DBG) Log.d(TAG, "File deleted successfully : " + app.packageReferrerFile);
            } else {
                Log.w(TAG, "Failed to delete file : " + app.packageReferrerFile);
            }
        } else {
            if (DBG) Log.d(TAG, "File does not exist : " + app.packageReferrerFile);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                app.completeBasicSetup();
                Context context = BstCommandProcessorService.this.getApplicationContext();
                // load the appReferralList and packageInstallTimeList
                 BstCommandProcessorApplication.mBstAppReferralList = (HashMap<String, String>) BstUtils.loadListFromFile(BstCommandProcessorApplication.appReferralListFile, BstCommandProcessorApplication.mBstAppReferralList);

                BstCommandProcessorApplication.mBstInstallTimeList = (HashMap<String, ArrayList<Long>>) BstUtils.loadListFromFile(BstCommandProcessorApplication.bstInstallTimeListPath, BstCommandProcessorApplication.mBstInstallTimeList);

                BstCommandProcessorApplication.mBstOfferPackageList = (HashSet<String>) BstUtils.loadListFromFile(BstCommandProcessorApplication.offerPackageListFile, BstCommandProcessorApplication.mBstOfferPackageList);

                /* Register the Broadcast receiver for app install/uninstall/upgrade notifications */
                IntentFilter pkgFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
                pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                //pkgFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
                pkgFilter.addAction(Intent.ACTION_PACKAGE_INSTALL);
                //pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                pkgFilter.addDataScheme("package");
                pkgFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
                registerReceiver(mPackageIntentReceiver, pkgFilter);

                IntentFilter bootCompletedFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
                registerReceiver(mBootCompletedReceiver, bootCompletedFilter);
                IntentFilter mVolumeStateChangeFilter = new IntentFilter(VolumeInfo.ACTION_VOLUME_STATE_CHANGED);
                registerReceiver(mVolumeStateChangeReceiver, mVolumeStateChangeFilter);

                setLocaleIfLocaleIsNotCorrect();
                cmdHandler.initImeStatus();
                cmdHandler.initInputDebuggingStatus();
                cmdHandler.initVolume();
                cmdHandler.getLocalTime();

                // Temp changes so that we set bst.config.ots_complete value, which is used for sending all topDisplayedActivityInfo call if setupwizard is running
                isOneTimeSetupComplete();


                // Checking if root access is available or not for this session.
                int bindvalue = SystemProperties.getInt("bst.enable_root_access", -1);

                //load list for blacklisted app that are currently installed
                cmdHandler.uninstallBlacklistedApps();

                if (bindvalue >= 0) {
                    SystemProperties.set("bst.config.bindmount", String.valueOf(bindvalue));
                }

                //setting airplane mode property active so that init.rc can trigger ifconfig eth0 down
                if (SystemProperties.getInt("bst.airplane_mode_active", 0) > 0)
                    SystemProperties.set("bst.airplane_mode_active", "1");

                ((BstCommandProcessorApplication) getApplication()).setGoogleAdIdInternal();
            }
        }).start();

    }

    @Override
	public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        BstCommandProcessorApplication app = (BstCommandProcessorApplication) getApplication();
        HandlerThread handlerThreadforHostCalls = app.getmHandlerThreadForHostCalls();
        if (handlerThreadforHostCalls != null) {
            handlerThreadforHostCalls.quit();
        }
	    super.onDestroy();
	}

    @Override
	public void onLowMemory() {
        Log.d(TAG, "onLowMemory called");
	    super.onLowMemory();
	}

    @Override
	public void onRebind(Intent intent) {
        Log.d(TAG, "onRebind called");
	    super.onRebind(intent);
	}

    @Override
	public void onStart(Intent intent, int startId) {
        Log.d(TAG, "onStart called");
	    super.onStart(intent, startId);

	}

    @Override
	public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind called");
	    return super.onUnbind(intent);
	}

    @Override
    public int onStartCommand(Intent intent,int flags,int startId)
    {
        Log.d(TAG, "onStartCommand called");
        BstCommandProcessorApplication app = ((BstCommandProcessorApplication) getApplication());
        BstCommandLoop cmdHandler = app.getCommandHandler();
        if ((intent != null) && (intent.getAction() != null))
        {
            String intentAction = intent.getAction();
            if (DBG) Log.d(TAG,"startService called with arg: " + intentAction);
            if (intentAction.equalsIgnoreCase("armAppCheck"))
            {
                Bundle bundle = intent.getExtras();
                String pkg = bundle.getString("pkg");
                sendStartAppIntent(pkg);
            }
            else if (intentAction.equalsIgnoreCase("launchAppStore"))
            {
                String packageName = intent.getStringExtra("packageName");
                String response = intent.getStringExtra("response");
                cmdHandler.launchRequiredAppStore("",packageName,"",response,true);
            }
            else if (intentAction.equalsIgnoreCase("reportNowggPlatformDetection"))
            {
                cmdHandler.reportNowggPltfDetectionToCloud(intent);
            }
            else if (intentAction.equalsIgnoreCase("createDesktopShortcut"))
            {
                Bundle bundle = intent.getExtras();
                String pkg = bundle.getString("pkg");
                app.makeHostCall(BstCommandProcessorApplication.CREATE_DESKTOP_SHORTCUT, pkg);
            }
            else if (intentAction.equals("importFilesCompleted"))
            {
                Bundle bundle = intent.getExtras();
                String folder = bundle.getString("folder");
                int status = bundle.getInt("status");
                String response = folder + "####" + status;
                app.makeHostCall(BstCommandProcessorApplication.IMPORT_FILES_COMPLETED, response);
            }
            else if (intentAction.equalsIgnoreCase("openUrl"))
            {
                Bundle bundle = intent.getExtras();
                String url = bundle.getString("url");
                app.makeHostCall(BstCommandProcessorApplication.OPEN_URL, url);
            }
            else if (intentAction.equalsIgnoreCase("walletMessage"))
            {
                Bundle bundle = intent.getExtras();
                String content = bundle.getString("content");
                app.makeHostCall(BstCommandProcessorApplication.WALLET_MESSAGE, content);
            }
            else if (intentAction.equalsIgnoreCase("adsInfoClick"))
            {
                app.makeHostCall(BstCommandProcessorApplication.ADS_INFO_CLICK, "");
            }
            else if (intentAction.equalsIgnoreCase("removeBootLoadingScreen"))
            {
                Bundle bundle = intent.getExtras();
                String isAdShown = bundle.getString("isAdShown");
                app.makeHostCall(BstCommandProcessorApplication.REMOVE_BOOT_LOADING_SCREEN, isAdShown);
            }
            else if (intentAction.equalsIgnoreCase("interstitialAdCompleted"))
            {
                Bundle bundle = intent.getExtras();
                String source = bundle.getString("source");
                String action = bundle.getString("action");
                String response = source + "####" + action;
                app.makeHostCall(BstCommandProcessorApplication.INTERSTITIAL_AD_COMPLETED, response);
            }
            else if (intentAction.equalsIgnoreCase("onNowbuxUpdated"))
            {
                app.makeHostCall(BstCommandProcessorApplication.ON_NOWBUX_UPDATED, "");
            }
            else if (intentAction.equalsIgnoreCase("onIAPCompleted"))
            {
                Bundle bundle = intent.getExtras();
                String source = bundle.getString("source");
                String data = bundle.getString("data");
                String response = source + "####" + data;
                app.makeHostCall(BstCommandProcessorApplication.ON_IAP_COMPLETED, response);
            }
            else if (intentAction.equalsIgnoreCase("updateQuestRules"))
            {
                Bundle bundle = intent.getExtras();
                String rules = bundle.getString("rules");
                app.makeHostCall(BstCommandProcessorApplication.UPDATE_QUEST_RULES, rules);
            }
            else if (intentAction.equalsIgnoreCase("consoleModeState"))
            {
                Bundle bundle = intent.getExtras();
                String state = bundle.getString("state");
                app.makeHostCall(BstCommandProcessorApplication.CONSOLE_MODE_STATE_CHANGED, state);
            }
            else if (intentAction.equalsIgnoreCase("showAppNotification"))
            {
                Bundle bundle = intent.getExtras();
                String url = bundle.getString("notificationData");
                app.makeHostCall(BstCommandProcessorApplication.SHOW_NOTIFICATION, url);
            }
            else if (intentAction.equalsIgnoreCase("launchBsx"))
            {
                Log.d(TAG, "launchBsx");
                app.makeHostCall(BstCommandProcessorApplication.LAUNCH_BSX, "");
            }
            else if (intentAction.equalsIgnoreCase("affiliateTrackingCompleted"))
            {
                Log.d(TAG, "affiliateTracking");
                String pkgName = intent.getStringExtra("pkg");
                app.makeHostCall(BstCommandProcessorApplication.AFFILIATE_TRACKING, pkgName);
            }
            else if (intentAction.equalsIgnoreCase("launchChrome"))
            {
                Log.d(TAG, "launchChrome");
                String pkgName = intent.getStringExtra("package_name");
                String className = intent.getStringExtra("class_name");
                launchChrome(pkgName, className);
            }
            else if (intentAction.equalsIgnoreCase("installApk"))
            {
                Bundle bundle = intent.getExtras();
                String path = bundle.getString("path");
                String source = bundle.getString("source");
                String attemptId = bundle.getString("attemptId");
                cmdHandler.bstInstallApk(path, true, attemptId, source);
            }
            else if (intentAction.equalsIgnoreCase("unzipFile"))
            {
                Bundle bundle = intent.getExtras();
                String fileName = bundle.getString("fileName");
                String source = bundle.getString("source");
                String attemptId = bundle.getString("attemptId");
                String pkgName = bundle.getString("pkgName");
                String response = fileName + "####" + attemptId + "####" + source + "####" + pkgName;
                app.makeHostCall(BstCommandProcessorApplication.UNZIP_FILE, response);
            }
            else if (intentAction.equalsIgnoreCase("installappgamecenter"))
            {
                Bundle bundle = intent.getExtras();
                String pkgName = bundle.getString("pkgName");
                app.makeHostCall(BstCommandProcessorApplication.INSTALL_APP_GAME_CENTER, pkgName);
            }
            else if (intentAction.equalsIgnoreCase("setBstLocation")) {
                setBstLocation(intent.getExtras());
            }
            else if (intentAction.equalsIgnoreCase("setBstImeGivenId"))
            {
                Bundle bundle = intent.getExtras();
                String imeId = bundle.getString("imeId");
                setBstImeGivenId(imeId);
            }
            else if (intentAction.equalsIgnoreCase("setGoogleAdId")) {
                ((BstCommandProcessorApplication) getApplication()).setGoogleAdId();
            }
            else if (intentAction.equalsIgnoreCase("setSoftKeyboardEnabled"))
            {
                setHardKeyboardStatus(false);
                /*
                try
                {
                    android.os.SystemProperties.set("bst.config.ishardkeyboard", "0");
                    Bundle bundle = intent.getExtras();
                    PendingIntent pendingIntent = null;
                    if (bundle != null)
                        pendingIntent = bundle.getParcelable("receiver");
                    if (pendingIntent != null)
                    {
                        Log.d(TAG,"pendingIntent ::" + pendingIntent);
                        pendingIntent.send();
                    }
                }
                catch(Exception e)
                {
                    Log.d(TAG,"exception e :" + e);
                    e.printStackTrace();
                }
                */
            }
            else if (intentAction.equalsIgnoreCase("setHardKeyboardEnabled"))
            {
                setHardKeyboardStatus(true);
            }
            else if (intentAction.equalsIgnoreCase("setServiceComponentState"))
            {
                Log.d (TAG, "enableServiceComponent called");
                Bundle bundle = intent.getExtras();
                String service = bundle.getString("service");
                boolean enabled = bundle.getBoolean("state");
                app.setServiceComponentState(service, enabled);
            }
            else if (intentAction.equalsIgnoreCase("BST_UPDATE_REFERRERLIST_ADD")) {
                String packageName = intent.getStringExtra("packageName");
                if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "intentAction = " + intentAction + ", packageName = " + packageName);
                if (packageName != null) {
                    BstCommandProcessorApplication.mBstPackageReferrerPresent.add(packageName);
                    BstUtils.writeListToFile(BstCommandProcessorApplication.mBstPackageReferrerPresent, BstCommandProcessorApplication.packageReferrerFile);
                    // Remove package from App Referrer Lists if third party referrer is present.
                    app.removePackageFromReferrerLists(packageName);
                }
            }
            else if (intentAction.equalsIgnoreCase("BST_UPDATE_REFERRERLIST_REMOVE")) {
                String packageName = intent.getStringExtra("packageName");
                if (DBG_BST_REFERRAL) Log.d(TAG_BST_REFERRAL, "intentAction = " + intentAction + ", packageName = " + packageName);
                if (packageName != null && BstCommandProcessorApplication.mBstPackageReferrerPresent.remove(packageName))
                    BstUtils.writeListToFile(BstCommandProcessorApplication.mBstPackageReferrerPresent, BstCommandProcessorApplication.packageReferrerFile);
                android.os.SystemProperties.set("bst.config.referrerpackage", "");
            }
            else if (intentAction.equalsIgnoreCase("startImportFiles")) {
                Log.d(TAG, "startImportFiles");
                String type = intent.getType();
                if (type == null || type.isEmpty())
                    type = "*/*";
                boolean isMultipleAllowed =  intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                String reqData = type + "," + String.valueOf(isMultipleAllowed);
                app.makeHostCall(BstCommandProcessorApplication.IMPORT_FILES, reqData);
            }
            //Intent action for informing host to create a temp folder for exporting files.
            //This is called when export to windows is clicked in BFM.
            else if (intentAction.equalsIgnoreCase("startExportFiles")) {
                Log.d(TAG, "startExportFiles");
                app.makeHostCall(BstCommandProcessorApplication.START_EXPORT_FILES, "");
            }
            //Intent action for informing host that all export files are copied to temp folder.
            else if (intentAction.equalsIgnoreCase("exportFiles")) {
                Log.d(TAG, "exportFiles");
                String folder = intent.getStringExtra("folder");
                app.makeHostCall(BstCommandProcessorApplication.EXPORT_FILES, folder);
            }
            else if (intentAction.equalsIgnoreCase("BST_OFFER_PACKAGE")) {
                 app.processOfferIntent(intent);
            }
            else if (intentAction.equalsIgnoreCase("BST_REFERRAL")) {
                 app.processReferralIntent(intent);
            }
            else if (intentAction.equalsIgnoreCase("updateStatsInterval")) {
                Log.d(TAG, "updateStatsInterval");
                Bundle bundle = intent.getExtras();
                String interval = String.valueOf(bundle.getInt("statsInterval"));
                app.makeHostCall(BstCommandProcessorApplication.UPDATE_STATS_INTERVAL, interval);
            }
            else if (intentAction.equalsIgnoreCase("setSmartDownloadEnabled"))
            {
                Bundle bundle = intent.getExtras();
                boolean enabled = (bundle != null) && bundle.getBoolean("enabled", false);
                Log.d(TAG, "setSmartDownloadEnabled enabled = " + enabled);
                cmdHandler.setSmartDownloadEnabled(enabled);
            }
            else if (intentAction.equalsIgnoreCase("nowggAccountAdded")) {
                Log.d(TAG, "nowggAccountAdded");
                String response = intent.getStringExtra("response");
                app.makeHostCall(BstCommandProcessorApplication.NOWGG_ACCOUNT_ADDED, response);
            }
            else if (intentAction.equalsIgnoreCase("getNowggAccount")) {
                Log.d(TAG, "getNowggAccount");
                String response = intent.getStringExtra("response");
                app.makeHostCall(BstCommandProcessorApplication.GET_NOWGG_ACCOUNT, response);
            }
            else if (intentAction.equalsIgnoreCase("differentImagePkgLaunch"))
            {
                Log.d(TAG, "differentImagePkgLaunch");
                Bundle bundle = intent.getExtras();
                String pkg = bundle.getString("pkg");
                app.makeHostCall(BstCommandProcessorApplication.DIFFERENT_IMAGE_PKG, pkg);
            }
            else if (intentAction.equalsIgnoreCase("GIAPTextContent"))
            {
                // This request comes from performGoogleIAPHack function in TextView.java
                // Called when vending app tries to set textview content in their payment dialog
                try {
                    Bundle bundle = intent.getExtras();
                    String item_description = bundle.getString("item_description");
                    String pkgname = bundle.getString("package");
                    if (DBG_BST_IAP) Log.d (TAG_BST_IAP, "New GIAP text data, item_description = " + item_description + ", pkgName = " + pkgname);

                    if (item_description != null && item_description.trim().length() > 0
                            && pkgname != null && pkgname.trim().length() > 0) {
                        // firstly check whether any IAP cheat engine like Freedom is enabled or not currently in the system.
                        // If any such app is enabled currently, mark this transaction as fake by putting empty value for this item.
                        boolean isFakeIAPEngineEnabled = false;
                        synchronized(BstCommandProcessorApplication.mLockFakeIAPEngineEnabled) {
                            isFakeIAPEngineEnabled = BstCommandProcessorApplication.isFakeIAPEngineEnabled;
                        }

                        if (isFakeIAPEngineEnabled) {
                            if (DBG_BST_IAP) Log.d (TAG_BST_IAP, "currently Fake/Cheat IAP engine is enabled in the system, so setting content as empty string.");
                            giapPackageDescription.remove(pkgname);
                            item_description = "";
                        }

                        if (!giapPackageDescription.containsKey(pkgname)) {
                            giapPackageDescription.put(pkgname, new StringBuilder(""));
                        }
                        StringBuilder sb = giapPackageDescription.get(pkgname);
                        if (!sb.toString().contains(item_description + delimiter)) {
                            sb.append(item_description);
                            sb.append(delimiter);
                        }
                        if (DBG_BST_IAP) Log.d(TAG_BST_IAP, "giapItemDescription = " + giapPackageDescription.get(pkgname).toString());
                    }

                    ((BstCommandProcessorApplication) getApplication()).setGoogleAdId();
                } catch (Exception ex) {
                    Log.d(TAG_BST_IAP, "Exception in GIAPTextContent " + ex.getMessage());
                    if (DBG_BST_IAP) ex.printStackTrace();
                }
            }
            else if (intentAction.equalsIgnoreCase("GIAPPurchaseData"))
            {
                // This request comes from performGoogleIAPHack function in Activity.java
                // Called when Purchase transaction is completed and vending is sending
                // result back to callingPackage via PendingIntent.
                //Bundle bundle = intent.getExtras();
                //String jsonPurchaseInfo = bundle.getString("purchaseData");
                try {
                    String jsonPurchaseInfo = intent.getStringExtra("purchaseData");
                    boolean success = intent.getBooleanExtra("success", false);
                    if(DBG_BST_IAP) Log.d (TAG_BST_IAP, "New GIAP Purchase data, success: " + success + " purchaseData: " + jsonPurchaseInfo);
                    if (success == false) {
                        // resetting google account related data so that for apps like LuckyPatcher,
                        // we shouldn't send last purchased data info to cloud.
                        String errorCodeDesc = intent.getStringExtra("responseCodeDesc");
                        int errorCode  = intent.getIntExtra("responseCode", -1);
                        String packageName = intent.getStringExtra("packageName");
                        if (jsonPurchaseInfo != null && jsonPurchaseInfo.trim().length() > 0) {
                            JSONObject o = new JSONObject(jsonPurchaseInfo);
                            packageName = o.isNull("packageName") ? packageName : o.optString("packageName");
                        }
                        if(DBG_BST_IAP) Log.d (TAG_BST_IAP, " errorCode = " + errorCode + ", errorCodeDesc = " + errorCodeDesc + ", packageName = " + packageName);

                        if (giapPackageDescription.containsKey(packageName)) {
                            JSONObject reqjson = new JSONObject();
                            reqjson.put("packageName", packageName);
                            StringBuilder item_description = giapPackageDescription.get(packageName);
                            reqjson.put("item_description", item_description != null ? item_description.toString() : "");
                            reqjson.put("locale", getResources().getConfiguration().locale);
                            reqjson.put("email", getGoogleAccountName());
                            reqjson.put("errorCode", errorCode);
                            reqjson.put("errorCodeDesc", errorCodeDesc);
                            reqjson.put("iap_status", false);
                            if (DBG_BST_IAP) Log.d(TAG_BST_IAP, "final IAP data content: " + reqjson);
                            sendGIAPDataToCloud(reqjson.toString());
                        }
                        giapPackageDescription.remove(packageName);
                        if (DBG_BST_IAP) Log.d(TAG_BST_IAP, "Removed " + packageName + " from giapPackageDescription");
                    } else {
                        //send this data to cloud for tracking purpose.
                        if(DBG_BST_IAP) Log.d (TAG_BST_IAP, "Sending GIAP data to cloud as transaction is completed successfully, purchaseData: " + jsonPurchaseInfo);
                        sendGIAPData(jsonPurchaseInfo);
                    }

                } catch(Exception ex) {
                    Log.e(TAG_BST_IAP, "Exception in GIAPPurchaseData action : " + ex.getMessage());
                    if (DBG_BST_IAP) ex.printStackTrace();
                }
            }
        }
        return Service.START_STICKY;
    }

    boolean isOneTimeSetupComplete()
    {
        int deviceProvisioned = Settings.Global.getInt(BstCommandProcessorApplication.getInstance().getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0);
        int userSetupComplete = Settings.Secure.getInt(BstCommandProcessorApplication.getInstance().getContentResolver() , Settings.Secure.USER_SETUP_COMPLETE ,0);

        if (deviceProvisioned == 1 && userSetupComplete == 1)
        {
            Log.d(TAG, "isOneTimeSetupComplete: true");
            SystemProperties.set("bst.config.ots_complete", "true");
            return true;
        }
        else
        {
            SystemProperties.set("bst.config.ots_complete", "false");
            Log.d(TAG, "isOneTimeSetupComplete: value of device_provisioned: " + deviceProvisioned + " value of user_setup_complete: " + userSetupComplete);
            return false;
        }
    }

    void setBstLocation(Bundle extras)
    {
        double latitude = Double.parseDouble(extras.getString("latitude"));
        double longitude = Double.parseDouble(extras.getString("longitude"));
        String data = latitude + "," + longitude;
        Log.d(TAG, "Saving new location: " + data);
        SystemProperties.set("bst.config.sysLoc", data);
        Location nwLocation = new Location(LocationManager.NETWORK_PROVIDER);
        mLocationManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false, false, false, true, false, false, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        mLocationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        nwLocation.setLatitude(latitude);
        nwLocation.setLongitude(longitude);
        nwLocation.setAltitude(4);
        nwLocation.setAccuracy(Criteria.ACCURACY_FINE);
        nwLocation.setTime(System.currentTimeMillis());
        nwLocation.setElapsedRealtimeNanos(System.nanoTime());
        mLocationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, nwLocation);
        saveLocationDataToSharedPrefs(data);
    }

    private void saveLocationDataToSharedPrefs(String data)
    {
        SharedPreferences sharedPref = BstCommandProcessorApplication.getInstance().getSharedPreferences("BstCmdPrefs", 0);
        SharedPreferences.Editor editor = sharedPref.edit();
        Log.d(TAG, "saveLocationDataToSharedPrefs : " + data);
        editor.putString("bstlocationdata", data);
        editor.commit();
    }

    void setHardKeyboardStatus(boolean usePhysicalKeyboard)
    {
        if (DBG) Log.d(TAG, "setHardKeyboardStatus usePhysicalKeyboard: " + usePhysicalKeyboard);
        mBstUtilsManager.setBstSoftKeyboardStatus(!usePhysicalKeyboard);
    }

    boolean setBstImeGivenId(String imeId)
    {
        if (DBG) Log.d(TAG, "setBstImeGivenId: " + imeId);
        InputMethodManager imm = (InputMethodManager)BstCommandProcessorApplication.getInstance().getSystemService(Context.INPUT_METHOD_SERVICE);
        String finalImeId = "";
        /*
        if (arg.equalsIgnoreCase("en"))
                imeId = "com.android.inputmethod.latin/.LatinIME";
        else if (arg.equalsIgnoreCase("zh-baidu"))
                imeId = "com.baidu.input/.ImeService";
        else if (arg.equalsIgnoreCase("zh-qq"))
                imeId = "com.tencent.qqpinyin/.QQPYInputMethodService";
        else if (arg.equalsIgnoreCase("zh"))
                imeId = "com.google.android.apps.inputmethod.zhuyin/.ZhuyinInputMethodService";
        */
        for (InputMethodInfo i : imm.getInputMethodList())
        {
            if (i.getId().equalsIgnoreCase(imeId))
                finalImeId = imeId;
        }
        if (finalImeId.trim().isEmpty())
        {
            Log.d(TAG,"setBstImeGivenId failed for id :" + imeId);
            return false;
        }
        //imm.setBstIME(finalImeId);
        return true;
    }

    void setLocaleIfLocaleIsNotCorrect() {
        String currentLocale =  SystemProperties.get("persist.sys.locale", "");
        String localeToSet = SystemProperties.get("bst.locale", "");

        // current Locale set is not same as locale to set,try to set locale.
        // Locale to set value will be present in bst.locale, windows code
        // will update the correct value in boot params.
        if (!localeToSet.equals(currentLocale))
        {
            Log.d(TAG, "setLocaleIfLocaleIsNotCorrect() called currentLocale : " + currentLocale + " --  localeToSet : " +  localeToSet);
            cmdHandler.setLocale(localeToSet);
        }
    }

    // Send a broadcast intent with packageName and current running mode (full/portrait_small/portrait_large/landscape_small)
    // as data for the intent. This is done by calling the getCurrentAppRunningMode() function of BstFilterApps file
    void sendStartAppIntent(String pkgName)
    {
        try {
            //getCurrentAppRunningMode() function is no longer available after new Orientation handling changes, so disbaling sending
            // the appmode information in START_APP broadcast.
            /*
               Method m = mActivityManager.getClass().getMethod("getCurrentAppRunningMode", String.class);
               Object retModeObj = m.invoke(mActivityManager,pkgName);
               if (VERBOSE) Log.d(TAG, "Current app is running in retModeObj: " + retModeObj);
               int retCurrAppMode = Integer.parseInt(retModeObj.toString());
               if (DBG) Log.d(TAG, "Current app is running in retMode: " + retCurrAppMode);
               */
            boolean armApp = isArmApp(pkgName);
            sendStartServiceIntent("com.bluestacks.home.svc","com.bluestacks.home");
            JSONObject dataJson = new JSONObject();
            dataJson.put("package", pkgName);
            dataJson.put("isArmApp", armApp);
            //dataJson.put("mode", retCurrAppMode);
            String intentData = dataJson.toString();
            Intent broadcastIntent = new Intent();
            broadcastIntent.setAction("com.bluestacks.action.START_APP");
            broadcastIntent.putExtra("data", intentData);
            sendBroadcastAsUser(broadcastIntent,Process.myUserHandle());
            Log.d(TAG, "Broadcasting START_APP intent with data: " + intentData + " isArmApp: " + armApp);
        } catch (Exception e) {
            Log.w(TAG, "Exception while sending startApp Broadcast intent for start package: " + pkgName + " msg: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean isArmApp(String pkgName)
    {
        boolean armApp = false;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo aInfo = pm.getApplicationInfo(pkgName, PackageManager.GET_META_DATA);
            boolean isArmAppListed = false;
            isArmAppListed = mBstFilterAppsManager.forceArmInstall(aInfo.packageName);
            // Check if the app is marked for ARM in our setting file or have ARM marker file present in its nativeLibrary folder
            if (aInfo.sourceDir.contains("/data/downloads") && isArmAppListed) {
                Log.d(TAG, "marked as arm app " + aInfo.processName);
                armApp = true;
            }
            else if (aInfo.nativeLibraryDir != null)
            {
                // A16: android.util.Features removed; the marker is a fixed filename (see A13 Features.GetArmAppMarker()).
                String pathName = aInfo.nativeLibraryDir +  "/" + "containsArmLibs.txt";
                File test = new File(pathName);

                if (test.exists()) {
                    Log.d(TAG, "contains arm marker file , app " + aInfo.processName);
                    armApp = true;
                }
            }
        } catch (Exception e)
        {
            Log.w(TAG, "Exception in checking whether " + pkgName + " is an arm app or not. Error Message: " + e.getMessage());
            e.printStackTrace();
        }
        return armApp;
    }

    private void sendStartServiceIntent(String svcName,String packageName)
    {
        try {
            Intent serviceIntent = new Intent();
            serviceIntent.setAction(svcName);
            serviceIntent.setPackage(packageName);
            startServiceAsUser(serviceIntent,Process.myUserHandle());
            Log.d(TAG, "Sending startService intent with data: " + serviceIntent);
        } catch (Exception e) {
            Log.w(TAG, "Exception while sending startService intent for service: " + svcName + " msg: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getGoogleAccountName()
    {
        Account[] accounts = AccountManager.get(BstCommandProcessorService.this.getApplicationContext()).getAccountsByType(GOOGLE_ACCOUNT_TYPE);
        if (accounts.length > 0) {
            return accounts[0].name;
        }
        return "";
    }

    private void sendGIAPData(String jsonPurchaseInfo) {
        BstCommandProcessorApplication app = ((BstCommandProcessorApplication) getApplication());
        Log.d(TAG_BST_IAP, "jsonPurchaseInfo = " + jsonPurchaseInfo);
        String packageName = null;
        try {
            JSONObject o = new JSONObject(jsonPurchaseInfo);
            JSONObject reqjson = new JSONObject();
            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo("com.android.vending", PackageManager.GET_META_DATA);
                String vendingPackageVersionName = pInfo.versionName;
                int vendingPackageVersionCode = pInfo.versionCode;
                reqjson.put("playStoreVersionName", vendingPackageVersionName);
                reqjson.put("playStoreVersionCode", String.valueOf(vendingPackageVersionCode));
            } catch (Exception ex) {
                Log.w(TAG_BST_IAP, "Failed to get vending package version: " + ex.getMessage());
                if (DBG_BST_IAP) ex.printStackTrace();
            }
            reqjson.put("orderId", o.optString("orderId"));
            reqjson.put("packageName", o.optString("packageName"));
            reqjson.put("productId", o.optString("productId"));
            reqjson.put("purchaseTime", o.optLong("purchaseTime"));
            reqjson.put("purchaseState", o.optInt("purchaseState"));
            //reqjson.put("developerPayload", o.optString("developerPayload"));
            //reqjson.put("token", o.optString("token", o.optString("purchaseToken")));
            reqjson.put("autoRenewing", o.optBoolean("autoRenewing"));
            // Get package IAP description from giapPackageDescription map for packageName.
            StringBuilder item_description = giapPackageDescription.get(o.optString("packageName"));
            reqjson.put("item_description", item_description != null ? item_description.toString() : "");
            reqjson.put("locale", getResources().getConfiguration().locale);

            //use email field only for google play payment as this doesn't have email id in item description
            reqjson.put("email", getGoogleAccountName());
            reqjson.put("iap_status", true);
            if (DBG_BST_IAP) Log.d(TAG_BST_IAP, "final IAP data content: " + reqjson);

            // resetting google account related data so that for apps like LuckyPatcher,
            // we shouldn't send last purchased data info to cloud. One can check whether
            // payment is from authorized channel or not by checking whether email is valid
            // one or not.
            packageName = o.optString("packageName");

            String source = "gplay";
            String data = reqjson.toString();
            String response = source + "####" + data;
            app.makeHostCall(BstCommandProcessorApplication.ON_IAP_COMPLETED, response);

            sendGIAPDataToCloud(data);
        } catch (Exception e) {
            Log.w(TAG_BST_IAP, "Error in parsing purchaseInfo Data: "  + e.getMessage());
            e.printStackTrace();
        } finally {
            if (packageName != null) {
                giapPackageDescription.remove(packageName);
                if (DBG_BST_IAP) Log.d(TAG_BST_IAP, "Removed " + packageName + " from giapPackageDescription");
            }
        }
    }

    void sendGIAPDataToCloud(String data) {
        try {
            BstCommandProcessorApplication app = BstCommandProcessorApplication.getInstance();
            final ContentValues values = new ContentValues();
            values.put("result", data);
            values.put("device_model", SystemProperties.get("ro.product.model", ""));
            values.put("device_carrier", SystemProperties.get("gsm.operator.alpha", ""));
            values.put("google_aid", BstCommandProcessorApplication.mGoogleAdId);
            values.put("imei", SystemProperties.get("bst.imei_id", ""));
            values.put("guid", SystemProperties.get("bst.guid", ""));
            values.put("install_id", SystemProperties.get("bst.install_id", ""));
            values.put("android_secure_id", SystemProperties.get("bst.android_id", ""));

            String host = SystemProperties.get("bst.bluestacks_cloud_url", "https://cloud.bluestacks.com");
            String url = host + "/purchase/AppPlayer";

            app.postRequestToCloud(url, values, DBG_BST_IAP, TAG_BST_IAP);
        } catch (Exception ex) {
            Log.e(TAG_BST_IAP, "Failed to send stat to cloud: " + ex.getMessage());
            if (DBG_BST_IAP) ex.printStackTrace();
        }
    }

    private void launchChrome(String pkgName, String className) {

        File file = null;
        String urlToLaunch = mBstFilterAppsManager.getBrowserUrl();
            try {
                String path = "/data/downloads/.tmp/tab_state0";
                file = new File(path);
                if (!file.exists()) {
                    file.createNewFile();
                    if (FileUtils.setPermissions(path,
                        FileUtils.S_IRUSR | FileUtils.S_IWUSR |
                        FileUtils.S_IRGRP | FileUtils.S_IROTH | FileUtils.S_IWGRP | FileUtils.S_IWOTH, -1, -1) != 0)
                    {
                        Log.e(TAG, "Failed to change permissions for the file: " + file.getPath());
                    }
                }
            } catch (Exception e) {
                if (DBG) e.printStackTrace();
            }
        ChromeTabStructure chromeTabStructure = BstCommandProcessorUtils.readSavedStateFile(file);
        if (DBG) Log.d(TAG, "readSavedStateFile() : " + chromeTabStructure.toString());

        Intent intent = new Intent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, getPackageName());
        intent.setComponent(new ComponentName(pkgName, className));
        intent.addCategory("android.intent.category.LAUNCHER");

        if (urlToLaunch == null || urlToLaunch.isEmpty() || urlToLaunch.trim().isEmpty()) {
            Log.e(TAG, "launchChrome: url is not a valid url");
        } else if (chromeTabStructure.count == 0) {
            intent.setData( Uri.parse(urlToLaunch));
        } else {
            Log.d(TAG, "launchChrome: tab switch, so not opening default url");
        }
        BstCommandProcessorService.this.getApplicationContext().startActivity(intent);
    }
}
