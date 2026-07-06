package fr.lelab.activity;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;

import com.getcapacitor.JSObject;

import androidx.annotation.NonNull;

import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.List;

public class ActivityRecognition {
    private Context context;
    private ActivityRecognitionClient activityClient;
    private PendingIntent activityPendingIntent;

    private BroadcastReceiver screenReceiver;
    private static DistractionEventEmitter distractionEmitter;

    private static final String TAG = "ActivityRecognition";
    private static final String PREFS_NAME = "CapacitorStorage";

    private static boolean touchDetect = false;

    public ActivityRecognition(Context context) {
        this.context = context;
        this.activityClient = com.google.android.gms.location.ActivityRecognition.getClient(context);
    }

    // --- state Persistence ---
    private void saveState(String key, boolean value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(key, value).apply();
    }

    // --- GPS Management ---
    public void startGPSUpdates() {
        Log.d(TAG, "🚗 switch to GPS Tracking Service");
        saveState("driving_state", true);

        Intent intent = new Intent(context, TrackingService.class);
        intent.setAction(TrackingService.ACTION_UPDATE_NOTIF);
        intent.putExtra(TrackingService.EXTRA_STATE, TrackingService.STATE_GPS_TRACKING); 

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public void stopGPSUpdates() {
        Log.d(TAG, "🔋 Stop activity automotive return to activityonly");
        saveState("driving_state", false);
        
        Intent intent = new Intent(context, TrackingService.class);
        intent.setAction(TrackingService.ACTION_UPDATE_NOTIF);
        intent.putExtra(TrackingService.EXTRA_STATE, TrackingService.STATE_ACTIVITY_ONLY);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    // --- Activity Management ---
    public void startTracking() {
        Log.d(TAG, "📡 Start Activity Recognition");
        saveState("tracking_active", true);

        Intent intent = new Intent(context, TrackingService.class);
        saveState("driving_state", false); 
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }

        setupActivityTransitions();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT); // Écran déverrouillé

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    Log.d(TAG, "📱 Screen ON (Background)");

                } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    Log.d(TAG, "🔓 phone unlocked");
                    JsonStorageHelper.setLockStatus(false);

                    // check current app
                    // 1. On vérifie d'abord si l'utilisateur a donné l'autorisation
                    android.app.AppOpsManager appOps = (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
                    int mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, 
                            android.os.Process.myUid(), context.getPackageName());
                    
                    boolean isGranted = (mode == android.app.AppOpsManager.MODE_ALLOWED);

                    // 2. Si oui, on peut chercher l'application au premier plan
                    if (isGranted) {
                        Log.d(TAG, "✅ Autorisation UsageStats granted. Try to get active app...");
                        String appActive = ForegroundAppDetector.getForegroundApp(context);
                        
                        // Optionnel : vous pouvez stocker l'application active ou lever un drapeau ici
                    } else {
                        Log.w(TAG, "⚠️ Unable to get active application : Autorisation missing (denied)");
                    }

                } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Log.d(TAG, "🔒 Phone locked");
                    JsonStorageHelper.setLockStatus(true);
                }

            }
        };

        context.registerReceiver(screenReceiver, filter);
    }


    private void setupActivityTransitions() {
        List<ActivityTransition> transitions = new ArrayList<>();
        int[] activities = {
            DetectedActivity.IN_VEHICLE, 
            DetectedActivity.WALKING, 
            DetectedActivity.STILL
        };

        for (int activity : activities) {
            transitions.add(new ActivityTransition.Builder()
                .setActivityType(activity)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build());
            transitions.add(new ActivityTransition.Builder()
                .setActivityType(activity)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build());
        }

        Intent intent = new Intent(context, ActivityTransitionReceiver.class);
        intent.setAction("fr.lelab.activity.ACTION_PROCESS_ACTIVITY_TRANSITIONS");
        
        activityPendingIntent = createPendingIntent(intent);

        try {
            activityClient.requestActivityTransitionUpdates(
                new ActivityTransitionRequest(transitions), 
                activityPendingIntent
            ).addOnSuccessListener(aVoid -> Log.i(TAG, "✅ Activity sensor OK"));
        } catch (SecurityException e) { 
            Log.e(TAG, "❌  permissions Error", e); 
        }
    }

    public void stopTracking() {
        Log.d(TAG, "🛑 Stop Global Activity Tracking");
        saveState("tracking_active", false);
        saveState("driving_state", false); 

        if (screenReceiver != null) {
            try {
                context.unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Receiver already registered or not found", e);
            }
            screenReceiver = null;
        }

        // 1. Stop transition sensor
        if (activityClient != null && activityPendingIntent != null) {
            activityClient.removeActivityTransitionUpdates(activityPendingIntent);
            activityClient.removeActivityUpdates(activityPendingIntent);
        }

        // 2. kill foreground service (and so notification)
        Intent intent = new Intent(context, TrackingService.class);
        context.stopService(intent);

    }

    private PendingIntent createPendingIntent(Intent intent) {
        int requestCode = 4242;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }


    // Start distraction
    public static void startDistractionFromReceiver(Context context) {
        if (distractionEmitter == null) {
            Log.i("ActivityRecognition", "🚗 Mode véhicule détecté : Démarrage des capteurs de distraction.");
            
            // On crée l'émetteur (vous pouvez passer null pour le listener si vous lisez uniquement la valeur via le JsonStorageHelper)
            distractionEmitter = new DistractionEventEmitter(context, new DistractionEventEmitter.DistractionListener() {
                @Override
                public void onDistractionEvent(String eventType, long timestamp) {
                    Log.d("ActivityRecognition", "📥 Événement distraction : " + eventType);
                }
            });
            distractionEmitter.startMonitoring();
        }
    }

    // Stop distraction
    public static void stopDistractionFromReceiver() {
        if (distractionEmitter != null) {
            Log.i("ActivityRecognition", "🚶 Sortie de véhicule détectée : Arrêt des capteurs de distraction.");
            distractionEmitter.stopMonitoring();
            distractionEmitter = null;
        }
    }


}