package com.bluestacks.BstCommandProcessor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

import android.os.SystemProperties;
import android.content.ContentValues;
import android.util.Log;


/*Helper class for creating HttpsConnection*/
class BstHttpsConnectionHelper {

    private String mUrl;
    private HttpURLConnection mConnection;
    private String mRequestMethod;

    public BstHttpsConnectionHelper(String url, String requestMethod){
        this.mUrl = url;
        this.mRequestMethod = requestMethod;
    }

    public void openConnection() throws Exception {
        URL url = new URL(mUrl);
        mConnection = (HttpURLConnection) url.openConnection();
        mConnection.setRequestMethod(mRequestMethod);
        mConnection.setConnectTimeout(5000);
        mConnection.setDoOutput(true);
    }

    public void writeValues(ContentValues values) throws Exception {
        ContentValues valuesToWrite  = getCommonQueryParam();
        for (Map.Entry<String, Object> entry : values.valueSet())
            valuesToWrite.put(entry.getKey().toString(),entry.getValue().toString());
        OutputStream os = mConnection.getOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
        writer.write(getQuery(valuesToWrite));
        writer.flush();
        writer.close();
        os.close();
    }

    public HttpURLConnection getConnectionObj() {
        return mConnection;
    }

    public int getResponseCode() throws Exception {
        return mConnection.getResponseCode();
    }

    public void closeConnection() {
        mConnection.disconnect();
    }

    public ContentValues getCommonQueryParam() {
        ContentValues values = new ContentValues();
        values.put("android_id", SystemProperties.get("bst.android_id", ""));
        values.put("android_image", SystemProperties.get("bst.android_image", ""));
        values.put("bluestacks_account_id", SystemProperties.get("bst.bluestacks_account_id", ""));
        values.put("campaign_hash", SystemProperties.get("bst.campaign_hash", ""));
        values.put("country", SystemProperties.get("bst.country", ""));
        values.put("guid", SystemProperties.get("bst.guid", ""));
        values.put("hypervisor", SystemProperties.get("bst.status.hypervisor", ""));
        values.put("install_id", SystemProperties.get("bst.install_id", ""));
        values.put("instance", SystemProperties.get("bst.instance", ""));
        values.put("locale", SystemProperties.get("bst.locale", ""));
        values.put("machine_id", SystemProperties.get("bst.machine_id", ""));
        values.put("oem", SystemProperties.get("bst.oem", ""));
        values.put("player_version", SystemProperties.get("bst.version", ""));
        values.put("session_id", SystemProperties.get("bst.status.session_id", ""));
        values.put("version_machine_id", SystemProperties.get("bst.version_machine_id", ""));
        return values;
    }

    public String getQuery(ContentValues values) throws IOException
    {
        StringBuilder result = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, Object> entry : values.valueSet())
        {
            if (first)
                first = false;
            else
                result.append("&");

            result.append(URLEncoder.encode(entry.getKey().toString(), "UTF-8"));
            result.append("=");
            if (entry.getValue() != null)
                result.append(URLEncoder.encode(entry.getValue().toString(), "UTF-8"));
        }
        return result.toString();
    }
}   
