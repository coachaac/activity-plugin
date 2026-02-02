package fr.lelab.activity;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

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
    private static final String TAG = "ActivityRecognition";
    private static final String PREFS_NAME = "CapacitorStorage";

    public ActivityRecognition(Context context) {
        this.context = context;
        this.activityClient = com.google.android.gms.location.ActivityRecognition.getClient(context);
    }

    // --- Persistence de l'état ---
    private void saveState(String key, boolean value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(key, value).apply();
    }

    // --- GPS Management ---
    public void startGPSUpdates() {
        Log.d(TAG, "🚗 Tentative de démarrage du Tracking Service");
        saveState("driving_state", true);

        Intent intent = new Intent(context, TrackingService.class);
        intent.setAction(TrackingService.ACTION_START_TRACKING);
        
        // On lance le Service qui, lui, gère le GPS de manière persistante
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public void stopGPSUpdates() {
        Log.d(TAG, "🔋 Arrêt du mode conduite");
        saveState("driving_state", false);
        
        Intent intent = new Intent(context, TrackingService.class);
        context.stopService(intent);
    }

    // --- Activity Management ---
    public void startTracking() {
        Log.d(TAG, "📡 Activation de la reconnaissance d'activité");
        saveState("tracking_active", true);
        setupActivityTransitions();
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
            ).addOnSuccessListener(aVoid -> Log.i(TAG, "✅ Capteurs d'activité OK"));
        } catch (SecurityException e) { 
            Log.e(TAG, "❌ Erreur permissions", e); 
        }
    }

    public void stopTracking() {
        saveState("tracking_active", false);
        stopGPSUpdates();
        if (activityClient != null && activityPendingIntent != null) {
            activityClient.removeActivityTransitionUpdates(activityPendingIntent);
        }
    }

    private PendingIntent createPendingIntent(Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }
}