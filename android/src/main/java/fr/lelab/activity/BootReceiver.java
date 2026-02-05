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
        Log.d("SmartPilot", "🚀 Boot détecté : Action = " + action);

        // On accepte BOOT_COMPLETED et LOCKED_BOOT_COMPLETED (pour le Direct Boot)
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {

            // --- CRITIQUE : Utilisation du SafeContext pour le Direct Boot ---
            Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
                ? context.createDeviceProtectedStorageContext() 
                : context;

            SharedPreferences prefs = safeContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
            boolean wasTrackingActive = prefs.getBoolean("tracking_active", false);
            boolean wasDriving = prefs.getBoolean("driving_state", false);

            if (wasTrackingActive) {
                Log.d("SmartPilot", "✅ Réactivation du moteur de détection d'activité...");
                
                // 1. Relancer la reconnaissance d'activité
                ActivityRecognition implementation = new ActivityRecognition(safeContext);
                implementation.startTracking();

                // 2. Relancer le GPS uniquement si on était en conduite active
                if (wasDriving) {
                    Log.d("SmartPilot", "🚗 Reprise du suivi GPS (Conduite active avant reboot)");
                    Intent serviceIntent = new Intent(safeContext, TrackingService.class);
                    serviceIntent.setAction(TrackingService.ACTION_START_TRACKING);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        safeContext.startForegroundService(serviceIntent);
                    } else {
                        safeContext.startService(serviceIntent);
                    }
                }
            } else {
                Log.d("SmartPilot", "🛑 Mode tracking désactivé par l'utilisateur, repos.");
            }
        }
    }
}