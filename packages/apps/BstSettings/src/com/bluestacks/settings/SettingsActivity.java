package com.bluestacks.settings;

import android.accounts.AuthenticatorDescription;
import android.accounts.AccountManager;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


public class SettingsActivity extends Activity {

    private BroadcastReceiver homeAppsBroadcastReceiver;
    private ArrayList<Integer> deviceList, personalList, bluestacksList, systemList;

    int mOldImeVersion = 0,mNewImeVersion = 0;
    /** Called when the activity is first created. */
    Intent storage_intent = null;
    static String oem;

    final static String IME_VERSION = "ImeVersion";
   // final static String SYSTEM_PROPERTIES_OEM = "bst.oem";
    final static String VERSION="version";
    String TAG = G.TAG + getClass().getSimpleName();
    private static final String IME_INFO_PATH = SystemProperties.get("bst.bluestacks_ime_info_path", "nougat");
    private static final String CDN_IME_URL = SystemProperties.get("bst.bluestacks_cdn_url", "https://cdn3.bluestacks.com") + "/public/appsettings/ime/";
    public static String IME_ZIPURL = CDN_IME_URL + IME_INFO_PATH + "/inputmethods.zip";
    public static String IME_VERSIONURL = CDN_IME_URL + IME_INFO_PATH + "/ver.json";

    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
	    setContentView(R.layout.activity_main);

        ActionBar actionBar = getActionBar();
        if(actionBar!=null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(0xFF263238));
            actionBar.setTitle("              " + getResources().getString(R.string.app_name));
        }

        ((TextView)findViewById(R.id.bluestacks_settings_title))
            .setText(String.format(
                        getResources().getString(R.string.bluestacks_settings_title),
                        getResources().getString(R.string.app_name)));

	    initViewLists();
    	homeAppsBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                modifyUIForHomeSetting();
            }
        };

	IntentFilter intentFilterForHomeApps = new IntentFilter();
        intentFilterForHomeApps.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilterForHomeApps.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilterForHomeApps.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilterForHomeApps.addCategory(Intent.CATEGORY_HOME);
        intentFilterForHomeApps.addDataScheme(Constants.PACKAGE_DATA_SCHEME);
        registerReceiver(homeAppsBroadcastReceiver, intentFilterForHomeApps);

        SharedPreferences appSettings = this.getSharedPreferences(G.APP_PREF_FILE,0);
        mOldImeVersion = appSettings.getInt(IME_VERSION,0);

        if (mOldImeVersion == 0)
            Utils.copyAssets(getApplicationContext());

        Log.d(TAG,"getting version file");
        new getVersion().execute(new String[]{IME_VERSIONURL});
       
        ViewGroup rootView = (ViewGroup)findViewById(android.R.id.content);
        List<TextView> textViews = new ArrayList<TextView>();
        findTextViews(rootView, textViews);
        for(TextView textView : textViews) {
            textView.setSingleLine();
            textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            textView.setMarqueeRepeatLimit(-1); //marquee forever
            textView.setFocusable(true);
            textView.setFocusableInTouchMode(true);
            textView.setSelected(true);
        }
    }

    private void findTextViews(ViewGroup rootView, List<TextView> textViews) {
        for(int i =0; i < rootView.getChildCount(); i++) {
            if(rootView.getChildAt(i) instanceof TextView) {
                textViews.add((TextView) rootView.getChildAt(i));
            }
            else if(rootView.getChildAt(i) instanceof ViewGroup) {
                findTextViews((ViewGroup) rootView.getChildAt(i), textViews);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        modifyUIForHomeSetting();
        modifyUIForGoogleAccountSettings();
    }
   
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(homeAppsBroadcastReceiver);
    }

	 /*
    * onClick events for each setting.*/
    public void onClick(View view) {

        Intent intent = new Intent();

        switch (view.getId()) {
            case R.id.storage_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME, Constants.STORAGE_ACTIVITY));
                break;
            case R.id.applications_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME, Constants.APPLICATIONS_ACTIVITY));
                break;
            case R.id.home_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_PERMISSION_CONTROLLER_APP_PACKAGE_NAME, Constants.HOME_ACTIVITY));
                break;
            case R.id.location_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME,Constants.LOCATION_ACTIVITY));
                break;
            case R.id.language_input_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME, Constants.LANGUAGE_INPUT_ACTIVITY));
                break;
            case R.id.accounts_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME, Constants.ADD_ACCOUNT_ACTIVITY));
                break;
            case R.id.google_account_settings:
                intent.setComponent(new ComponentName(Constants.GMS_PACKAGE_NAME, Constants.GOOGLE_ACCOUNT_ACTIVITY));
                break;
            case R.id.select_ime_settings: intent.setComponent(new ComponentName(getApplicationContext().getPackageName(), getApplicationContext().getPackageName()+".InputMethodSettings"));
                break;
            case R.id.manage_contacts_settings:
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(ContactsContract.Contacts.CONTENT_URI);
                break;
            case R.id.import_windows_files_settings:
                intent.setAction(Constants.IMPORT_FROM_WINDOWS_ACTION);
                intent.addCategory(Constants.CATEGORY_DEFAULT);
                intent.setPackage(Constants.MEDIA_MANAGER_PACKAGE_NAME);
                break;
            case R.id.date_time_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME, Constants.DATE_TIME_ACTIVITY));
                break;
            case R.id.accessibility_settings:
                intent.setComponent(new ComponentName(Constants.ANDROID_SETTINGS_APP_PACKAGE_NAME, Constants.ACCESSIBILITY_ACTIVITY));
                break;
        }

        Log.i(TAG,"Opening stock android app as a new task.");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private boolean multipleHomeApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homeApps = getPackageManager().queryIntentActivities(intent, 0);
        return (homeApps.size()>=2);
    }

	private void modifyUIForHomeSetting() {
        LinearLayout linearLayout = (LinearLayout) findViewById(R.id.home_settings);
        if(multipleHomeApps()) {
            if(linearLayout.getVisibility()!=View.VISIBLE) {
                linearLayout.setVisibility(View.VISIBLE);
                deviceList.add(R.id.home_settings);
                setFocusChangeListener(R.id.home_settings);
            }
        } else {
            if(linearLayout.getVisibility()==View.VISIBLE) {
                linearLayout.setVisibility(View.GONE);
                deviceList.remove(R.id.home_settings);
            }
        }
    }

    private void modifyUIForGoogleAccountSettings() {
        if(isPlayStoreInstalled()) {
            LinearLayout linearLayout = (LinearLayout) findViewById(R.id.google_account_settings);
            linearLayout.setVisibility(View.VISIBLE);
            personalList.add(R.id.google_account_settings);
            Drawable mDrawable = getResources().getDrawable(R.drawable.ic_google_logo);
            //converting drawable from black color to green color.
            mDrawable.setColorFilter(Color.parseColor("#019688"), PorterDuff.Mode.SRC_ATOP);
            ((ImageView)linearLayout.findViewById(R.id.google_account_settings_image_view)).setImageDrawable(mDrawable);
            oemSpecificUIModifications(personalList, R.id.google_account_settings);
        } else {
            Log.i(TAG, "google account not present.");
            LinearLayout linearLayout = (LinearLayout) findViewById(R.id.google_account_settings);
            if(linearLayout.getVisibility()==View.VISIBLE) {
                linearLayout.setVisibility(View.GONE);
            }
        }
    }

    /*
    * This function does oem specific modifications. For example for a specific oem if we need to enable `Security and fingetprint` option,
    * then we need to hide the separator lines for `Accounts` and `Change language` settings.*/
    void oemSpecificUIModifications(ArrayList<Integer> categoryList, int settingId) {
        int index = categoryList.indexOf(settingId);
        View view;

        setFocusChangeListener(settingId);

        if(index%2==0) {
            if(index-1>=0) {
                view = findViewById(categoryList.get(index-1)).findViewWithTag(getResources().getString(R.string.separator_tag));
                if(view.getVisibility()!=View.VISIBLE) {
                    view.setVisibility(View.VISIBLE);
                }
            }

            if(index-2>=0) {
                view = findViewById(categoryList.get(index-2)).findViewWithTag(getResources().getString(R.string.separator_tag));
                if(view.getVisibility()!=View.VISIBLE) {
                    view.setVisibility(View.VISIBLE);
                }
            }
        }
    }


    /*
    * Array lists containing view separator ids (ex: data_time_settings_separator).
    * These are used when we add new setting in any of the categories and will make changes in UI accordingly.*/
    private void initViewLists() {
        deviceList = new ArrayList<>();
        deviceList.add(R.id.storage_settings);
        deviceList.add(R.id.applications_settings);
        setFocusChangeListener(deviceList);

        personalList = new ArrayList<>();
        personalList.add(R.id.location_settings);
        personalList.add(R.id.language_input_settings);
        personalList.add(R.id.accounts_settings);
        setFocusChangeListener(personalList);

        bluestacksList = new ArrayList<>();
        bluestacksList.add(R.id.select_ime_settings);
        bluestacksList.add(R.id.manage_contacts_settings);
        bluestacksList.add(R.id.import_windows_files_settings);
        setFocusChangeListener(bluestacksList);

        systemList = new ArrayList<>();
        systemList.add(R.id.date_time_settings);
        systemList.add(R.id.accessibility_settings);
        setFocusChangeListener(systemList);
    }

    private void setFocusChangeListener(ArrayList<Integer> list) {
        for(int i=0; i<list.size(); i++) {
            setFocusChangeListener(list.get(i));
        }
    }

    private void setFocusChangeListener(int viewId) {
        findViewById(viewId).setOnFocusChangeListener(focusChangeListener);
    }

    View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if(hasFocus) {
                v.setBackgroundColor(getResources().getColor(R.color.setting_background));
            } else {
                v.setBackgroundColor(getResources().getColor(R.color.white));
            }
        }
    };

    boolean isPlayStoreInstalled() {
        return Utils.isPackageInstalled("com.android.vending", getPackageManager());
    }

    

    private class getZIP extends DownloadURL {
        getZIP() {
            super(G.CACHE_DIR,getApplicationContext());
        }

        @Override
        protected void onPostExecute(String result) {
            String path = result;
            String filename;
            FileInputStream is;
            ZipInputStream zis;
            try
            {
                is = new FileInputStream(path);
                zis = new ZipInputStream(is);
                ZipEntry ze;

                String PATH = Utils.getNewDirectory(getApplicationContext());
                File file = new File(PATH);
                file.mkdirs();

                while((ze = zis.getNextEntry()) != null)
                {
                    if (ze.isDirectory())
                        continue;
                    filename = ze.getName();
                    if (filename.contains("/"))
                        filename = filename.substring(filename.indexOf("/") + 1);
                    FileOutputStream fout = new FileOutputStream(PATH + File.separator + filename);

                    Utils.copyData(zis,fout);

                    zis.closeEntry();
                    fout.close();
                    fout.flush();
                    fout = null;
                }
                zis.close();
                zis = null;
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private class getVersion extends DownloadURL {
        getVersion() {
            super(G.CACHE_DIR,getApplicationContext());
       }

        @Override
        protected void onPostExecute(String result)
        {
            if (result != null)
            {
                String json = "";
                File jsonPath = new File(result);
                if (jsonPath.exists() && jsonPath.length() != 0)
                {
                    try
                    {
                        FileReader fr = new FileReader(jsonPath);
                        BufferedReader br = new BufferedReader(fr);
                        StringBuilder sb = new StringBuilder();
                        int count;
                        char buff[] = new char[4096];
                        while ((count = br.read(buff)) > 0)
                        {
                            sb.append(buff, 0, count);
                        }
                        json = sb.toString();
                        br.close();

                        JSONArray jsonArray = new JSONArray(json);
                        int numItems = jsonArray.length();
                        for (int i = 0; i < numItems; i++)
                        {
                            JSONObject item = jsonArray.getJSONObject(i);
                            String version = item.getString(VERSION);
                            mNewImeVersion = Integer.parseInt(version);
                        }

                        if (mNewImeVersion > mOldImeVersion)
                        {
                            SharedPreferences appSettings = getApplicationContext().getSharedPreferences(G.APP_PREF_FILE,0);
                            SharedPreferences.Editor editor = appSettings.edit();
                            editor.putInt(IME_VERSION, mNewImeVersion);
                            editor.commit();
                            Log.d(TAG,"higher version so getting zip");
                            new getZIP().execute(new String[]{IME_ZIPURL});
                        }

                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


}
