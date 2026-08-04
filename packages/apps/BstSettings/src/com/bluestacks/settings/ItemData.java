package com.bluestacks.settings;

import android.content.Intent;

class ItemData {
    int labelResId;
    int iconResId;
    int descResId;
    String className;
    String activityName;
    String action;
    String category;
    boolean waitforresult;
    Intent intent;
    // attributes specific to IMEs
    String labelStr; // label for IME
    String descStr;  // desc for IME
    String pkg; // pkg - for InputMethodDataItems
    String cdn_url; // cdn_url - url to download it from cdn
    int source ; // Download source of Ime [0 - from Playstore , 1 - from CDN]

    ItemData(int i, int i2, int d, String c, String a, String a2, String c2, boolean w) {
        labelResId = i;
        iconResId = i2;
        descResId = d;
        className = c;
        activityName = a;
        action = a2;
        category = c2;
        waitforresult = w;
    }

    ItemData(Intent intent, int i, int i2, int d, boolean w) {
        this.intent = intent;
        labelResId = i;
        iconResId = i2;
        descResId = d;
        waitforresult = w;
    }

    // constructor to cater needs of IMEs
    ItemData(String label, String desc, String pkg, String url, boolean w) {
        labelStr = label;
        descStr = desc;
        this.pkg = pkg;
        cdn_url = url;
        waitforresult = w;
    }

    ItemData(String label, String desc, String pkg, String url, boolean w, int source) {
        labelStr = label;
        descStr = desc;
        this.pkg = pkg;
        cdn_url = url;
        waitforresult = w;
        this.source = source;
    }
}
