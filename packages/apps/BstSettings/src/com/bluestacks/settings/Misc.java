package com.bluestacks.settings;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

public class Misc {
	
    static private void startBstServiceLocked(String packageName, Context m_context) {
        Intent intent = new Intent();
        ComponentName cn = new ComponentName("com.bluestacks.BstCommandProcessor", 
                "com.bluestacks.BstCommandProcessor.BstCommandProcessorService");
        intent.setComponent(cn);
        intent.setAction("bundledApp");
        intent.putExtra("pkg", packageName);
        m_context.startService(intent);
    }
    
	static public  void installAPK(String apkPath, Context context) {
        //Check if this file is a special apk or not. For special apks, check
        //if it is already installed or not. If installed, reinstall this
        //app only if new file version is greater than the previous one.
		PackageManager pm = context.getPackageManager();
        PackageInfo fileInfo = pm.getPackageArchiveInfo(apkPath, 0);

        if (fileInfo == null) {
            Log.e(TAG, "Error: Invalid package archive file: " + apkPath);
            return;
        }
        try {
            int newVersionCode = fileInfo.versionCode;
            String pkgName = fileInfo.packageName;
            startBstServiceLocked(pkgName, context);
            Log.d(TAG, "Package to be installed: pkgName : " + pkgName + " versionCode: " + newVersionCode);
            if (pkgName.equalsIgnoreCase("mpi.v23")) {
                   Log.d(TAG, "Checking if mpi package is already installed or not");
                PackageInfo packageInfo = pm.getPackageInfo(pkgName, 0);
                int currentVersionCode = packageInfo.versionCode;
                Log.d(TAG, "mpi package is already installed, installed app versionCode: " + currentVersionCode + " new file version : " + newVersionCode);
                if (currentVersionCode >= newVersionCode) {
                    Log.e(TAG, "Error: new file version is older or same as that of the current installed version, so not reinstalling it");
                    // Since, mpi apks is already installed but market is not yet installed on the device, so running this APK 
					if (!isLatestMarketInstalled(context) && Rooted.isRooted()) {
						Log.d(TAG,"Starting Market Connector...");
						Rooted.exec("am start -n mpi.v23/.AMI");
					}
                    return;
                }
            }
        } catch (Exception e) {
            Log.d (TAG, "Package is currently not installed, install this app now");
        }
		
        // this should ultimately done by Master.java as transaction

		if (Rooted.isRooted()) {
			// use pm to install the app
			Rooted.exec("pm install -r "+apkPath);

		} else {
			Intent intent= new Intent();
			intent.setDataAndType(Uri.fromFile(new File(apkPath)),
					"application/vnd.android.package-archive");

			if (m_installerPkg == null)
				getInstallerClass(context);

			if (m_installerPkg != null) 
				intent.setClassName(m_installerPkg, m_installerActivity);
			else
				intent.setClassName("com.android.packageinstaller","com.android.packageinstaller.PackageInstallerActivity");

			intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK );
			context.startActivity(intent);
		}
	}

	static public  void getInstallerClass(Context context) {
		Intent intent = new Intent();
		intent.addCategory("android.intent.category.DEFAULT");
		//intent.setType("application/vnd.android.package-archive");
		intent.setDataAndType(Uri.parse("file:///dummy"),"application/vnd.android.package-archive");


		PackageManager packageManager = context.getPackageManager();
		// final Intent intent = new Intent(action);
		List resolveInfo =
				packageManager.queryIntentActivities(intent,0);
		if ((resolveInfo != null) && (resolveInfo.size() > 0)) {

			for (Object l: resolveInfo){
				ResolveInfo ri = (ResolveInfo)l;
				// Log.d(TAG,ri.activityInfo.packageName +":"+ri.activityInfo.name);
				if (ri.activityInfo.packageName.contains("package")) {
					m_installerPkg = ri.activityInfo.packageName;
					m_installerActivity = ri.activityInfo.name;
				}

			}
		}

	}
   
    public static boolean isLatestMarketInstalled(Context context) {
        int i = 0;
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo("com.google.android.gsf", 0);
            i++;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH)
            {
                pm.getPackageInfo("com.google.android.gsf.login", 0);
                i++;
            }
            PackageInfo packageInfo = pm.getPackageInfo("com.android.vending", 0);
            i++;
            int versionCode = packageInfo.versionCode;
            Log.d(TAG, "p2dm: market installed, versionCode: " + versionCode);
            //version code = 8011015 (3.5.15)
            //8016014
            if (versionCode >= 8011015)
                return true;
        } catch (Exception e) {
           // Log.d(TAG, "p2dm: market not installed");
        }

        Log.d(TAG, "p2dm: market not installed");
        return false;
    }
	
	static String m_installerPkg = null;
	static String m_installerActivity = null;
	static String TAG = L.TAG + "Misc";
}
