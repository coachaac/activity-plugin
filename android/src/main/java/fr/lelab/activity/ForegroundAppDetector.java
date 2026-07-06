package fr.lelab.activity;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.getcapacitor.JSObject;

public class ForegroundAppDetector {
    private static final String TAG = "ForegroundAppDetector";

    /**
     * Récupère le package de l'application actuellement au premier plan
     * et met à jour le stockage.
     */
    public static String getForegroundApp(Context context) {
        String foregroundApp = null;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long time = System.currentTimeMillis();
            
            // On regarde les événements des 10 dernières secondes
            UsageEvents events = usm.queryEvents(time - 10000, time);
            UsageEvents.Event event = new UsageEvents.Event();
            
            // On parcourt les événements pour trouver le plus récent qui est passé au premier plan
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foregroundApp = event.getPackageName();
                }
            }
        }
        
        if (foregroundApp != null) {
            Log.d(TAG, "📱 Active application : " + foregroundApp);
            
            // On prépare l'objet JSObject proprement pour le stockage
            JSObject navData = new JSObject();
            navData.put("name", foregroundApp);
            navData.put("accuracy", 1);

            JSObject rootObject = new JSObject();
            rootObject.put("usedApp", navData);

            // On sauvegarde l'objet structuré dans le helper
            JsonStorageHelper.setLastForegroundApp(rootObject);
        }

        return foregroundApp; // (ex: "com.google.android.apps.maps")
    }
}