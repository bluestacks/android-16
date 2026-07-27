package com.bluestacks.BstCommandProcessor.Accessibility;

import com.bluestacks.BstCommandProcessor.BstCommandProcessorApplication;
import android.content.Context;
import android.graphics.Point;
import android.view.Display;
import android.view.WindowManager;

public class DeviceSize {
    public int x;
    public int y;

    public DeviceSize(int x, int y) {
        this.x = x;
        this.y = y;
    }
    public static DeviceSize getDeviceSize() {
        WindowManager windowManager = (WindowManager) BstCommandProcessorApplication.getInstance().getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return new DeviceSize(0, 0); // Default value in case of failure
        }

        Display display = windowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return new DeviceSize(size.x, size.y); // x = width, y = height
    }
    @Override
    public String toString() {
        return x + "x" + y;
    }
}
