package com.bluestacks.settings;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFrameLayout;
import android.preference.PreferenceGroup;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;



public class Utils
{
    static String TAG = G.TAG + "utils";
    public static void copyData(InputStream in, OutputStream out) throws IOException
    {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
            out.write(buffer, 0, read);
        }
    } 

    public static String getNewDirectory(Context context)
    {
        String outputDir = context.getExternalFilesDir(null) + G.CACHE_DIR + G.NEW_DIR;
        return outputDir;
    }

    public static String getDefaultDirectory(Context context)
    {
        String outputDir = context.getExternalFilesDir(null) + G.CACHE_DIR + G.DEFAULT_DIR;
        return outputDir;
    }

    public static boolean isInstalled(String pkg,Context context) {
        PackageManager pm = context.getPackageManager();
        boolean appInstalled = false;
        try {
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            appInstalled = true;
        } catch (PackageManager.NameNotFoundException e) {
            appInstalled = false;
        }
        return appInstalled;
    }


    public static boolean isEnabled(String pkg,Context context)
    {
        InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = (imis == null ? 0 : imis.size());
        for (int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            if (imi.getPackageName().equalsIgnoreCase(pkg))
            {
                return true;
            }
        }
        return false;
    }

    public static boolean isSelected(String pkg,Context context)
    {
        InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = (imis == null ? 0 : imis.size());
        for (int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            if (imi.getPackageName().equalsIgnoreCase(pkg))
            {
                if(imi.getId().equals(Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD)))
                    return true;
                else
                    return false;
            }
        }
        return false;
    }

    public static void copyAssets(Context context) {
        String PATH = Utils.getDefaultDirectory(context);
        File file = new File(PATH);
        if (file.isDirectory() && file.list().length > 0)
            return;
        file.mkdirs();
        AssetManager assetManager = context.getAssets();
        String[] files = null;
        try {
            files = assetManager.list("imes");
        } catch (IOException e) {
            e.printStackTrace();
        }
        for(String filename : files) {
            InputStream in = null;
            OutputStream out = null;
            try {
              in = assetManager.open("imes/" + filename);
              File outFile = new File(PATH,filename);
              out = new FileOutputStream(outFile);
              Utils.copyData(in,out);
              in.close();
              in = null;
              out.flush();
              out.close();
              out = null;
            } catch(IOException e) {
                e.printStackTrace();
            }       
        }
    }
    
    public static boolean isPackageInstalled(String packagename, PackageManager packageManager) {
        try {
            packageManager.getPackageInfo(packagename, 0);
            return true;
        } catch (NameNotFoundException e) {
            return false;
        }
    }
}
