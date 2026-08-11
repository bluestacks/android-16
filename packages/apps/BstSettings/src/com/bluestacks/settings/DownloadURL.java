package com.bluestacks.settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.StrictMode;
import android.util.Log;

public class DownloadURL extends AsyncTask<String, Integer, String> {

	public DownloadURL(String cacheDir,Context context) {
        mContext = context;
		m_cacheDir = cacheDir;
		m_UA=null;
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
	}
	public DownloadURL(String cacheDir, String UA,Context context) {
        mContext = context;
		m_cacheDir = cacheDir;
		m_UA = UA;
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
	}

	@Override
	protected String doInBackground(String... urls) {
		return downloadURL(urls[0]);
	}

	@Override
	protected void onPostExecute(String result) {
		//
	}

	protected void setMax(int max) {

	}

	@Override
	protected  void onProgressUpdate(Integer...progress) {
	}

    protected String downloadURL(String apkurl) {
        HttpURLConnection  c=null;
        try {
            Log.d(TAG, "URL: "+ apkurl);
            URL url = new URL(apkurl);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            //c.setDoOutput(true);

            if (m_UA != null) {
                c.addRequestProperty("User-Agent", m_UA);
            }

            c.connect();

            int responseCode = c.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                String location = c.getHeaderField("Location");
                if (location != null) {
                    Log.d(TAG, "Moved to:" + location);
                    return downloadURL(location);
                } else
                    return null;
            }

            String PATH = mContext.getExternalFilesDir(null) + m_cacheDir;
            File file = new File(PATH);
            file.mkdirs();
            String filename = url.hashCode() + "";
            File outputFile = new File(file, filename);

            FileOutputStream fos = new FileOutputStream(outputFile);

            InputStream is = c.getInputStream();

            Log.d(TAG,"DownloadURL: " + PATH + filename+" Size:"+
                    c.getContentLength()+" Content-Type:"+c.getContentType());

            setMax(c.getContentLength());

            byte[] buffer = new byte[1024];
            int len1 = 0;
            int total=0;
            while ((len1 = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len1);
                total=total+len1;
                publishProgress(total);
            }
            fos.close();
            is.close();

            return PATH + filename;

        } catch (IOException e) {
            try {
                Log.e(TAG, "Request responsecode: " + c.getResponseCode());
                if ( (c.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP) ||
                        (c.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM)) {
                    String location = c.getHeaderField("Location");
                    if (location != null) {
                        Log.d(TAG,"Moved to:"+location);
                        return downloadURL(location);
                    } else
                        return null;
                        }
            } catch (Exception e2) {
            }
            Log.d(TAG, "downloadURL failed for: " + apkurl + " Error: " +e.toString());
            if(false)
            {
                Log.e(TAG, "ThreadPolicy: " + StrictMode.getThreadPolicy().toString());
                e.printStackTrace();
            }
        }
        return null;
    }

    String m_cacheDir, m_UA;
    Context mContext;
    String TAG = L.TAG + getClass().getSimpleName();
    //task.execute(new String[] { "http://www.vogella.de" });
}

