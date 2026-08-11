package com.bluestacks.settings;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;


import android.util.Log;

public class Rooted {
	
	
	static public void exec(String[] cmds) {
        try {
            // String[] cmds = {"sync", "sync", "reboot"};
            String su1 = "/system";
            String su2 = "/xbin";
            String su3 = "/bstk";
            String su = "/su";
            Process p = Runtime.getRuntime().exec(su1 + su2 + su3 + su);
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            DataInputStream is = new DataInputStream(p.getInputStream());
            for (String tmpCmd : cmds) {
                Log.d(TAG, tmpCmd);
                os.writeBytes(tmpCmd + "\n");

            }
            os.writeBytes("exit\n");
            os.flush();
        }
		catch (Exception e) { 
			Log.d("XXX",e.toString());
		}
	}

	static public void exec(String cmd) {
		String[] cmds = new String[1];
		cmds[0] = cmd;
		exec(cmds);
	}

	static public boolean isRooted() {
	    String su1 = "/system";
        String su2 = "/xbin";
        String su3 = "/bstk";
        String su = "/su";
        File f = new File(su1 + su2 + su3 + su);
        return f.isFile();
	}
	
	static String TAG = L.TAG + "Rooted";
	
}
