package com.bluestacks.settings;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;

public class EnableIME extends Activity {
    private String mPackage;
    private String mLabel;
    private String mCdnUrl;
    private int mSource;

    private ViewGroup[] mStepsContainer;
    private Context mContext;

    String TAG = G.TAG + getClass().getSimpleName();
    private BroadcastReceiver mPackageIntentReceiver ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.enable_ime_layout);

        getActionBar().setBackgroundDrawable(new ColorDrawable(0xFF263238));
        getActionBar().setDisplayHomeAsUpEnabled(false);
        getActionBar().setDisplayUseLogoEnabled(false);

        mContext = this;

        mPackage = getIntent().getStringExtra("package");
        mLabel = getIntent().getStringExtra("label");
        mCdnUrl = getIntent().getStringExtra("cdn_url");
        mSource = getIntent().getIntExtra("source", 0);
        if (mPackage == null || mLabel == null || mCdnUrl == null)
        {
            Log.d(TAG,"improper data -> intent :" + getIntent());
            finish();
        }

        mStepsContainer = new ViewGroup[3];

        mStepsContainer[0] = (ViewGroup) findViewById(R.id.step1);
        mStepsContainer[1] = (ViewGroup) findViewById(R.id.step2);
        mStepsContainer[2] = (ViewGroup) findViewById(R.id.step3); 

        LinearLayout linearLayout = (LinearLayout) mStepsContainer[2];
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) linearLayout.getLayoutParams();
        params.bottomMargin = 10;
        linearLayout.setLayoutParams(params);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPackageIntentReceiver = new BstPackageIntentsReceiver();
        IntentFilter pkgFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addDataScheme("package");
        pkgFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(mPackageIntentReceiver, pkgFilter);
    }

    public void openMarketPage(String pkg) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setPackage("com.android.vending");
        intent.setData(Uri.parse("market://details?id=" + pkg));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        //startActivityForResult(intent,2);
        startActivity(intent);
    }

    private class getAPK extends DownloadURL {
        getAPK() 
        {
            super(G.CACHE_DIR,getApplicationContext());
        }

        @Override
        protected void onPreExecute()
        {
            Toast.makeText(mContext,R.string.wait_installingapk,Toast.LENGTH_LONG).show();
            Button stepActionOne = (Button)mStepsContainer[0].findViewById(R.id.stepActionButton);

            stepActionOne.setText(getResources().getString(R.string.installing));
            stepActionOne.setEnabled(false);
        }

        @Override
        protected void onPostExecute(String result) 
        {
            if (result != null) 
            {
                Log.d(TAG,"apk downloaded from cdn ,now installing");
                Misc.installAPK(result, getApplicationContext());
                Toast.makeText(mContext,R.string.install_complete,Toast.LENGTH_SHORT).show();
                Button stepActionOne = (Button)mStepsContainer[0].findViewById(R.id.stepActionButton);

                stepActionOne.setText(getResources().getString(R.string.installed_ime));
                stepActionOne.setEnabled(true);
                refreshIcons();
            }
            else
            {
                Log.d(TAG,"apk download from cdn FAILED");
                Toast.makeText(mContext,R.string.install_failed,Toast.LENGTH_SHORT).show();
                Button stepActionOne = (Button)mStepsContainer[0].findViewById(R.id.stepActionButton);

                stepActionOne.setText(getResources().getString(R.string.installing));
                stepActionOne.setEnabled(true);
            }
        }
    }

    // Install button click handler - Goto Play or download from cdn -- button1
    public void downloadAndInstall(View view)
    {
        String oem = SystemProperties.get("bst.oem", "");
        Log.d(TAG,"install ime , Package : " + mPackage + "  oem :" + oem);
        if (!oem.toLowerCase().equals("china") && Misc.isLatestMarketInstalled(getApplicationContext()) && (mSource == Constants.SOURCE_PLAY_STORE))
        { 
            Log.d(TAG,"market is already there, so open it");
            openMarketPage(mPackage);
        }
        else
        {
            Log.d(TAG,"cdn comes into role for downloading");
            new getAPK().execute(new String[]{mCdnUrl});
        }
    }
   
    // Enable button click handler - enable this ime and comeback -- button2
    public void enable(View view)
    {
        Log.d(TAG,"enabling ime , package : " + mPackage);
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("fromBstIMESettings",true);
        intent.putExtra("package",mPackage);
        startActivity(intent);
    }

    // Select button click handler - open the alertdialog for final selection -- button3
    public void select(View view)
    {
        Log.d(TAG,"selecting ime , package : " + mPackage);
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showInputMethodPicker();
    }

    public void refreshIcons()
    {
        
        ((TextView)findViewById(R.id.title)).setText(getResources().getString(R.string.setup) + " " + mLabel + " " + getResources().getString(R.string.input));
        ((TextView)mStepsContainer[0].findViewById(R.id.stepDescriptionTextView)).setText(getResources().getString(R.string.install) + " " + mLabel + " " + getResources().getString(R.string.input_apk));
        ((TextView)mStepsContainer[1].findViewById(R.id.stepDescriptionTextView)).setText(getResources().getString(R.string.enable_ime) + " " + mLabel + " " + getResources().getString(R.string.input));
        ((TextView)mStepsContainer[2].findViewById(R.id.stepDescriptionTextView)).setText(getResources().getString(R.string.select) + " " + mLabel + " " + getResources().getString(R.string.input));
       
        int primaryColor = Color.parseColor("#00897B");
        int disabledColor = Color.parseColor("#AAAAAA");
        int primaryTextColor = Color.parseColor("#000000");
        int secondaryTextColor = Color.parseColor("#888888");

        TextView stepOne = ((TextView)mStepsContainer[0].findViewById(R.id.stepCounterTextView));
        TextView stepTwo = ((TextView)mStepsContainer[1].findViewById(R.id.stepCounterTextView));
        TextView stepThree  = ((TextView)mStepsContainer[2].findViewById(R.id.stepCounterTextView));

        ImageView stepCounterOne = (ImageView)mStepsContainer[0].findViewById(R.id.stepCounterImageView);
        ImageView stepCounterTwo = (ImageView)mStepsContainer[1].findViewById(R.id.stepCounterImageView);
        ImageView stepCounterThree = (ImageView)mStepsContainer[2].findViewById(R.id.stepCounterImageView);

        Button stepActionOne = (Button)mStepsContainer[0].findViewById(R.id.stepActionButton);
        Button stepActionTwo = (Button)mStepsContainer[1].findViewById(R.id.stepActionButton);
        Button stepActionThree = (Button)mStepsContainer[2].findViewById(R.id.stepActionButton);

        stepOne.setText(String.format(getResources().getString(R.string.step), 1)); 
        stepTwo.setText(String.format(getResources().getString(R.string.step), 2)); 
        stepThree.setText(String.format(getResources().getString(R.string.step), 3)); 

        if(!Utils.isInstalled(mPackage, mContext)) {
            stepCounterOne.setImageResource(R.drawable.ic_one_circle);
            stepCounterOne.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
            stepActionOne.setVisibility(View.VISIBLE);
            stepOne.setTextColor(primaryTextColor);

            stepCounterTwo.setImageResource(R.drawable.ic_two_circle);
            stepCounterTwo.setColorFilter(disabledColor, PorterDuff.Mode.SRC_IN);
            stepActionTwo.setVisibility(View.GONE);
            stepTwo.setTextColor(secondaryTextColor);

            stepCounterThree.setImageResource(R.drawable.ic_three_circle);
            stepCounterThree.setColorFilter(disabledColor, PorterDuff.Mode.SRC_IN);
            stepActionThree.setVisibility(View.GONE);
            stepThree.setTextColor(secondaryTextColor);
        }
        else {
            stepCounterOne.setImageResource(R.drawable.ic_check_circle);
            stepCounterOne.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
            stepActionOne.setVisibility(View.GONE);
            stepOne.setTextColor(secondaryTextColor);
            
            if (!Utils.isEnabled(mPackage,getApplicationContext())) {
                stepCounterTwo.setImageResource(R.drawable.ic_two_circle);
                stepCounterTwo.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
                stepActionTwo.setVisibility(View.VISIBLE);
                stepTwo.setTextColor(primaryTextColor);

                stepCounterThree.setImageResource(R.drawable.ic_three_circle);
                stepCounterThree.setColorFilter(disabledColor, PorterDuff.Mode.SRC_IN);
                stepActionThree.setVisibility(View.GONE);
                stepThree.setTextColor(secondaryTextColor);
            }

            else {
                stepCounterTwo.setImageResource(R.drawable.ic_check_circle);
                stepCounterTwo.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
                stepActionTwo.setVisibility(View.GONE);
                stepTwo.setTextColor(secondaryTextColor);
                
                if (!Utils.isSelected(mPackage,getApplicationContext())) {
                    stepCounterThree.setImageResource(R.drawable.ic_three_circle);
                    stepCounterThree.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
                    stepActionThree.setVisibility(View.VISIBLE);
                    stepThree.setTextColor(primaryTextColor);
                }
                else {
                    stepCounterThree.setImageResource(R.drawable.ic_check_circle);
                    stepCounterThree.setColorFilter(primaryColor, PorterDuff.Mode.SRC_IN);
                    stepActionThree.setVisibility(View.GONE);
                    stepThree.setTextColor(secondaryTextColor);
                }
            }
        }

        if(stepActionOne.getText() == null || stepActionOne.getText().equals("")){
            stepActionOne.setText(getResources().getString(R.string.install));
        }

        if(stepActionTwo.getText() == null || stepActionTwo.getText().equals("")){
            stepActionTwo.setText(getResources().getString(R.string.enable_ime));
        }

        if(stepActionThree.getText() == null || stepActionThree.getText().equals("")){
            stepActionThree.setText(getResources().getString(R.string.input_method_settings));
        }

        stepActionOne.setTag("1");
        stepActionTwo.setTag("2");
        stepActionThree.setTag("3");

        stepActionOne.setOnClickListener(mClickListener);
        stepActionTwo.setOnClickListener(mClickListener);
        stepActionThree.setOnClickListener(mClickListener);
    }

    private View.OnClickListener mClickListener = new View.OnClickListener() {
        @Override
            public void onClick(View v) {
                if(v.getTag() == null || ! (v.getTag() instanceof String))
                    return;
                String tag = (String)v.getTag();
                if(tag.equals("1")) {
                    downloadAndInstall(v);
                }
                else if(tag.equals("2")) {
                    enable(v);
                }
                else if(tag.equals("3")) {
                    select(v);
                }
            }
        };

    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        Log.d(TAG,"onWindowFocusChanged :: hasFocus :" + hasFocus);
        if (hasFocus == true)
            refreshIcons();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(mPackageIntentReceiver);
    }

    class BstPackageIntentsReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent)
        {
            String intentAction = intent.getAction();
            Uri packageUri = intent.getData();
            String pkgName = packageUri.getSchemeSpecificPart();
            if (intentAction.equals(Intent.ACTION_PACKAGE_ADDED)) {
                if(mPackage.equals(pkgName))
                    refreshIcons();
            }
        }
    }
}
