package fr.lelab.activity;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.getcapacitor.JSObject;
import android.app.usage.UsageStats;

public class ForegroundAppDetector {
    private static final String TAG = "ForegroundAppDetector";

    /**
     * Récupère le package de l'application actuellement au premier plan
     * et met à jour le stockage.
     */
    public static String saveForegroundApp(Context context) {
        String foregroundApp = null;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long time = System.currentTimeMillis();
            
            // --- APPROCHE 1 : Recherche via les événements des 5 dernières minutes ---
            // (Note : ACTIVITY_RESUMED remplace avantageusement MOVE_TO_FOREGROUND car il est plus fiable sur les Android récents)
            UsageEvents events = usm.queryEvents(time - (5 * 60 * 1000), time);
            UsageEvents.Event event = new UsageEvents.Event();
            long lastEventTime = 0;
            
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED || 
                    event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    
                    if (event.getTimeStamp() > lastEventTime) {
                        foregroundApp = event.getPackageName();
                        lastEventTime = event.getTimeStamp();
                    }
                }
            }

            // --- APPROCHE 2 : Si aucun événement récent, recherche de l'application déjà ouverte ---
            if (foregroundApp == null) {
                Log.d(TAG, "⚠️ Aucun événement récent trouvé. Analyse de l'application déjà active...");
                
                java.util.List<UsageStats> stats = usm.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, time - (60 * 60 * 1000), time);
                
                if (stats != null && !stats.isEmpty()) {
                    long maxLastTimeUsed = 0;
                    for (UsageStats usageStats : stats) {
                        if (usageStats.getLastTimeUsed() > maxLastTimeUsed) {
                            maxLastTimeUsed = usageStats.getLastTimeUsed();
                            foregroundApp = usageStats.getPackageName();
                        }
                    }
                }
            }
        }
        
        // --- TRAITEMENT ET SAUVEGARDE DU RÉSULTAT ---
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
        else {
            Log.d(TAG, "📱 no Active application found ");
        }

        return foregroundApp; // (ex: "com.google.android.apps.maps")
    }
}