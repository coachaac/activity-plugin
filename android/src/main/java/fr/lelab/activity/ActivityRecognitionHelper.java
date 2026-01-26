package fr.lelab.activity;

import java.io.File;
import java.io.FileOutputStream;
import android.util.Log;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ActivityRecognitionHelper {
    public static void setupActivityTransitions(Context context) {
        Intent intent = new Intent(context, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopActivityTransitions(Context context) {
        Intent intent = new Intent(context, TrackingService.class);
        context.stopService(intent);
    }


    // Dans ActivityRecognitionHelper.java
    public static void saveEventToFile(Context context, String type, String details) {
        try {
            File file = new File(context.getFilesDir(), "track_log.txt");
            String timestamp = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
            String entry = String.format("[%s] %s: %s\n", timestamp, type, details);
            
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
            fos.write(entry.getBytes());
            fos.close();
        } catch (Exception e) {
            android.util.Log.e("StorageHelper", "Erreur écriture log", e);
        }
    }
}