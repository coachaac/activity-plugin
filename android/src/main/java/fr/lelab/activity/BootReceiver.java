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
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("SmartPilot", "🚀 Boot détecté : vérification de l'état du tracking");

            // On récupère l'état sauvegardé par Capacitor
            SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
            boolean wasTrackingActive = prefs.getBoolean("tracking_active", false);
            boolean wasDriving = prefs.getBoolean("driving_state", false);

            if (wasTrackingActive) {
                Log.d("SmartPilot", "✅ Le tracking était actif, réactivation...");
                
                // 1. Relancer la reconnaissance d'activité (pour détecter les futurs trajets)
                ActivityRecognition implementation = new ActivityRecognition(context);
                implementation.startTracking();

                // 2. Si on était en plein trajet lors de l'extinction, on relance le GPS immédiatement
                if (wasDriving) {
                    Log.d("SmartPilot", "🚗 On était en conduite, relance du Foreground Service");
                    Intent serviceIntent = new Intent(context, TrackingService.class);
                    serviceIntent.setAction(TrackingService.ACTION_START_TRACKING);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                }
            } else {
                Log.d("SmartPilot", "🛑 Le tracking n'était pas actif, on ne fait rien.");
            }
        }
    }
}