package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d("Activity Plugin", "🚀 Boot detected : Action = " + action);

        // BOOT_COMPLETED et LOCKED_BOOT_COMPLETED (Direct Boot)
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {

            // --- Safe context used Direct Boot ---
            Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
                ? context.createDeviceProtectedStorageContext() 
                : context;

            SharedPreferences prefs = safeContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
            boolean wasTrackingActive = prefs.getBoolean("tracking_active", false);
            boolean wasDriving = prefs.getBoolean("driving_state", false);

            if (wasTrackingActive) {
                Log.d("Activity Plugin", "✅ Activity detection engine re-activated...");
                
                // 1. Relancer la reconnaissance d'activité
                ActivityRecognition implementation = new ActivityRecognition(safeContext);
                implementation.startTracking();

                // 2. Relauch GPS tracking only if driving before reboot
                if (wasDriving) {
                    Log.d("Activity Plugin", "🚗 GPS Tracking restarted (driving before reboot)");
                    Intent serviceIntent = new Intent(safeContext, TrackingService.class);
                    serviceIntent.setAction(TrackingService.ACTION_START_TRACKING);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        safeContext.startForegroundService(serviceIntent);
                    } else {
                        safeContext.startService(serviceIntent);
                    }
                }
            } else {
                Log.d("Activity Plugin", "🛑 Tracking mode de-activated by user, sleep.");
            }
        }
    }
}