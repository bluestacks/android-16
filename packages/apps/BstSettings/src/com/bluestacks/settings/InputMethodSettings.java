package com.bluestacks.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.app.ActionBar;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.bluestacks.os.BstUtilsManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.json.*;

public class InputMethodSettings extends Activity {
    Context mContext;
    AlertDialog alert;
    public static List<String> mImePackages;
    ListView lv;
    private BstUtilsManager mBstUtilsManager = null;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        m_activity = this;

        mContext = this;

        mBstUtilsManager = (BstUtilsManager) mContext.getSystemService(Context.BST_UTILS);
 
        setContentView(R.layout.main);
        
        ActionBar actionBar = getActionBar();
        if(actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(0xFF263238));
        }

        mInputMethodChangeReceiver = new InputMethodChangeReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED);
        registerReceiver(mInputMethodChangeReceiver, filter);

        lv = (ListView) this.findViewById(R.id.listView1);
        m_data = new DataAdapter();
        SharedPreferences appSettings = this.getSharedPreferences(G.APP_PREF_FILE,0);
        int mOldImeVersion = appSettings.getInt("ImeVersion",0);

        if (mOldImeVersion == 0)
            Utils.copyAssets(getApplicationContext());

        new PopulateInputMethods().execute("");

    }

    @Override
    protected void onResume()
    {
        super.onResume();
        m_data.notifyDataSetChanged();
    }

    class DataAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return m_cache.size();
        }

        @Override
        public Object getItem(int arg0) {
            return m_cache.get(arg0);
        }

        @Override
        public long getItemId(int arg0) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                LayoutInflater vi = (LayoutInflater) m_activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                v = vi.inflate(R.layout.item, parent, false);
            }
            ImageView iv = (ImageView) v.findViewById(R.id.imageView1);
            TextView tv1 = (TextView) v.findViewById(R.id.textView1);
            TextView tv2 = (TextView) v.findViewById(R.id.textView2);
            Switch sw = (Switch) v.findViewById(R.id.switch1);
            sw.setVisibility(View.GONE);
            sw.setTag(null);
            sw.setOnCheckedChangeListener(null);
            ItemData id = m_cache.get(position);
    
            tv1.setText(id.labelStr);

            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) tv1.getLayoutParams();

            if(id.descStr == null || id.descStr.equals("")) {
                tv2.setVisibility(View.GONE);
                params.addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE);
            }
            else {
                params.removeRule(RelativeLayout.CENTER_VERTICAL);
                tv2.setVisibility(View.VISIBLE);
                tv2.setText(id.descStr);
            }

            if (id.pkg.equalsIgnoreCase("enablephysicalkeyboard") || id.pkg.equalsIgnoreCase("enablesoftkeyboard") || id.pkg.equalsIgnoreCase("enabledefaultime"))
            {
                iv.setImageResource(R.drawable.ic_input_method);
                boolean isBstUtilSoftkeyboardEnabled = mBstUtilsManager.isBstSoftKeyboardEnabled();
                if(id.pkg.equalsIgnoreCase("enablephysicalkeyboard")) {
                    sw.setVisibility(View.VISIBLE);
                    sw.setTag("enablephysicalkeyboard");
                    sw.setChecked(false);
                    if(isPhysicalKeyboardEnabled && !isScreenKeyboardEnabled) {
                        sw.setChecked(true);
                    }

                    else if(!isPhysicalKeyboardEnabled && !isScreenKeyboardEnabled){
                        if(!isBstUtilSoftkeyboardEnabled) {
                            sw.setChecked(true);
                        }
                    }
                }
                else if(id.pkg.equalsIgnoreCase("enablesoftkeyboard")) {
                    sw.setVisibility(View.VISIBLE);
                    sw.setTag("enablesoftkeyboard");
                    sw.setChecked(false);
                    if(!isPhysicalKeyboardEnabled && isScreenKeyboardEnabled) {
                        sw.setChecked(true);
                    }

                    else if(!isPhysicalKeyboardEnabled && !isScreenKeyboardEnabled){
                        if(isBstUtilSoftkeyboardEnabled) {
                            sw.setChecked(true);
                        }
                    }
 
                }
                sw.setOnCheckedChangeListener(onSwitchChangeListener);

                if(id.pkg.equalsIgnoreCase("enabledefaultime")) {
                    params.removeRule(RelativeLayout.CENTER_VERTICAL);

                    String currentIme = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                    Intent imeQueryIntent = new Intent();
                    imeQueryIntent.setAction("android.view.InputMethod");
                    List<ResolveInfo> imeServices = getPackageManager().queryIntentServices(imeQueryIntent, 0);
                    for(ResolveInfo resolveInfo : imeServices) {
                        if(resolveInfo != null) {
                            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
                            if(serviceInfo != null && serviceInfo.packageName != null && serviceInfo.name != null) {
                                if(appendShortClassName(serviceInfo.packageName, serviceInfo.name).equals(currentIme)) {
                                    tv2.setVisibility(View.VISIBLE);
                                    tv2.setText(resolveInfo.loadLabel(getPackageManager()));
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            else
            {
                String imageName = null;
                imageName = id.pkg + ".png";
                File storagePath = new File(Utils.getNewDirectory(getApplicationContext()));
                if (!(storagePath.isDirectory() && storagePath.list().length > 0))
                    storagePath = new File(Utils.getDefaultDirectory(getApplicationContext()));
                File imagePath = new File(storagePath, imageName);
                if (imagePath.exists())
                {
                    Bitmap bm = BitmapFactory.decodeFile(imagePath.toString());
                    iv.setImageBitmap(bm);
                }

                if (Utils.isSelected(id.pkg,getApplicationContext())) {
                    tv2.setText(R.string.selected);
                }
                else if (Utils.isEnabled(id.pkg,getApplicationContext())) {
                    tv2.setText(R.string.enabled);
                }
                else if (Utils.isInstalled(id.pkg,getApplicationContext())) {
                    tv2.setText(R.string.installed_ime);
                }
                
            }
            return v;
        }
    }
   
    class PopulateInputMethods extends  AsyncTask<String, String, String>
    {
        protected String doInBackground(String...url)
        {
            String json = "";
            File storagePath = new File(Utils.getNewDirectory(getApplicationContext()));
            if (!(storagePath.isDirectory() && storagePath.list().length > 0))
                storagePath = new File(Utils.getDefaultDirectory(getApplicationContext()));
            String jsonName = G.JSON_FILENAME;
            File jsonPath = new File(storagePath, jsonName);
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
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }    
            return json;
        }

        protected void onPostExecute(String json)
        {
                m_cache.add(new ItemData(getResources().getString(R.string.enable_physical_keyboard), "", "enablephysicalkeyboard", "enablephysicalkeyboard", false));
                m_cache.add(new ItemData(getResources().getString(R.string.enable_soft_keyboard), "", "enablesoftkeyboard", "enablesoftkeyboard", false));
                m_cache.add(new ItemData(getResources().getString(R.string.select_default_ime), "", "enabledefaultime", "enabledefaultime", false));
                        
            try
            {
                JSONArray jsonArray = new JSONArray(json);
                int numItems = jsonArray.length();
                mImePackages = new ArrayList<String>();
                for (int i = 0; i < numItems; i++)
                {
                    try
                    {
                        JSONObject item = jsonArray.getJSONObject(i);
                        String pkgName = item.getString("pkg");
                        mImePackages.add(pkgName);
                        String label = item.getString("label");
                        String desc = item.getString("desc");
                        String url = item.getString("url");
                        int source = item.getInt("source");
                        m_cache.add(new ItemData(label, desc, pkgName, url, false, source));
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

                //ListView lv = (ListView) mContext.findViewById(R.id.listView1);
                lv.setAdapter(m_data);
                lv.setOnItemClickListener(new OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long Id) {
                        ItemData item = m_cache.get(position);
                        try {
                            Intent intent = null;
                             if (item.pkg.equalsIgnoreCase("enabledefaultime"))
                            {
                                Log.d(TAG,"popup select ime");
                                InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                                imm.showInputMethodPicker();
                            }
                            else
                            {
                                intent = new Intent(getApplicationContext(),EnableIME.class);
                                intent.putExtra("package",item.pkg);
                                intent.putExtra("label",item.labelStr);
                                intent.putExtra("cdn_url",item.cdn_url);
                                intent.putExtra("source",item.source);
                                startActivity(intent);
                            }
                        } catch (Exception e) {
                            Log.d(TAG, e.toString());
                            e.printStackTrace();
                        }
                    }
                });
        }
    }

    Activity m_activity;
    DataAdapter m_data;
    String TAG = G.TAG + getClass().getSimpleName();
    List<ItemData> m_cache = new ArrayList<ItemData>();

    private boolean isPhysicalKeyboardEnabled = false;
    private boolean isScreenKeyboardEnabled = false;
    private InputMethodChangeReceiver mInputMethodChangeReceiver;
    private CompoundButton.OnCheckedChangeListener onSwitchChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            String tag = (String)buttonView.getTag();
            if(tag == null) {
                return;
            }
            Intent intent = null;
            if(tag.equalsIgnoreCase("enablephysicalkeyboard")) {
                if(isChecked) {
                    sendEnablePhysicalKeyboardIntent();
                    isPhysicalKeyboardEnabled = true;
                    isScreenKeyboardEnabled = false;
                }
                else {
                    sendEnableSoftKeyboardIntent();
                    isPhysicalKeyboardEnabled = false;
                    isScreenKeyboardEnabled = true;
                } 
            }
            else if(tag.equalsIgnoreCase("enablesoftkeyboard")) {
                if(isChecked) {
                    sendEnableSoftKeyboardIntent();
                    isPhysicalKeyboardEnabled = false;
                    isScreenKeyboardEnabled = true;
                }
                else {
                    sendEnablePhysicalKeyboardIntent();
                    isPhysicalKeyboardEnabled = true;
                    isScreenKeyboardEnabled = false;
                } 
            }

            m_data.notifyDataSetChanged();
        }
    };

    private void sendEnableSoftKeyboardIntent() {
        Intent intent = new Intent();
        ComponentName cn = new ComponentName("com.bluestacks.BstCommandProcessor", 
                "com.bluestacks.BstCommandProcessor.BstCommandProcessorService");
        intent.setComponent(cn);
        intent.setAction("setSoftKeyboardEnabled");
        startService(intent);
//        Toast.makeText(mContext,R.string.enabling_soft_keyboard,Toast.LENGTH_SHORT).show();
    }

    private void sendEnablePhysicalKeyboardIntent() {
        Intent intent = new Intent();
        ComponentName cn = new ComponentName("com.bluestacks.BstCommandProcessor", 
                "com.bluestacks.BstCommandProcessor.BstCommandProcessorService");
        intent.setComponent(cn);
        intent.setAction("setHardKeyboardEnabled");
        startService(intent);
//        Toast.makeText(mContext,R.string.enabling_physical_keyboard,Toast.LENGTH_SHORT).show();

    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        m_data = null;
        if(mInputMethodChangeReceiver != null) {
            unregisterReceiver(mInputMethodChangeReceiver);
        }
        if(mBstUtilsManager != null) {
            mBstUtilsManager = null;
        }
    }

    public class InputMethodChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_INPUT_METHOD_CHANGED)) {
                if(m_data != null) {
                    isPhysicalKeyboardEnabled = false;
                    isScreenKeyboardEnabled = false;
                    m_data.notifyDataSetChanged();

                }
            }
        }
    }

    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        Log.d(TAG,"onWindowFocusChanged :: hasFocus :" + hasFocus);
        if (hasFocus == true) {
            if(m_data != null) {
                isPhysicalKeyboardEnabled = false;
                isScreenKeyboardEnabled = false;
                m_data.notifyDataSetChanged();

            }
        }
    }

    private String appendShortClassName(String packageName, String className) {
        StringBuilder sb = new StringBuilder(packageName);
        sb.append("/");

        if (className.startsWith(packageName)) {
            int PN = packageName.length();
            int CN = className.length();
            if (CN > PN && className.charAt(PN) == '.') {
                sb.append(className, PN, CN);
                    return sb.toString();
                }
            }
        sb.append(className);
        return sb.toString();
   }
    
}
