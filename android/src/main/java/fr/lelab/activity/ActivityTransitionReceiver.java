package fr.lelab.activity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

public class ActivityTransitionReceiver extends BroadcastReceiver {
    private static final String TAG = "ActivityReceiver";
    public static final String ACTION_STOP_GPS_GRACE = "fr.lelab.activity.ACTION_STOP_GPS_GRACE";
    private static final long STOP_DELAY = 180000; // 3 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        
        // --- OPTIMISATION : Log d'état pour le Debugging en mode veille ---
        Log.d("SmartPilot", "📩 RECU : Action = " + action);

        // --- 1. GESTION DE L'ALARME (ARRÊT GRACE PERIOD) ---
        if (ACTION_STOP_GPS_GRACE.equals(action)) {
            Log.d(TAG, "⏱ AlarmManager : Fin du délai de grâce, arrêt définitif du service.");
            stopTrackingService(context);
            return;
        }

        // --- 2. GESTION DE LA SIMULATION ADB ---
        if (intent.hasExtra("com.google.android.gms.location.EXTRA_ACTIVITY_RESULT")) {
            int activityType = intent.getIntExtra("com.google.android.gms.location.EXTRA_ACTIVITY_RESULT", -1);
            int transitionType = intent.getIntExtra("com.google.android.gms.location.EXTRA_TRANSITION_TYPE", 0);
            Log.d("SmartPilot", "🧪 Simulation ADB détectée !");
            handleTransition(context, activityType, transitionType);
            return;
        }

        // --- 3. GESTION DU MODE RÉEL ---
        if (ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
            for (ActivityTransitionEvent event : result.getTransitionEvents()) {
                handleTransition(context, event.getActivityType(), event.getTransitionType());
            }
        }
    }

    private void handleTransition(Context context, int activityType, int transitionType) {
        String activityName = getActivityName(activityType);
        String transitionName = (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) ? "ENTER" : "EXIT";
        String normalizedActivity = activityName.toLowerCase().replace("in_vehicle", "automotive");
        
        Log.d(TAG, "⚡ Traitement : " + normalizedActivity + " [" + transitionName + "]");

        // 1. Sauvegarde dans le fichier local JSONL (On utilise le SafeContext pour le reboot)
        Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? context.createDeviceProtectedStorageContext() : context;
        
        JsonStorageHelper.saveActivity(safeContext, normalizedActivity, transitionName);

        // 2. Notification en temps réel au JavaScript
        JSObject data = new JSObject();
        data.put("activity", normalizedActivity);
        data.put("transition", transitionName);
        data.put("timestamp", System.currentTimeMillis()); 
        ActivityRecognitionPlugin.onActivityEvent(data);

        // --- LOGIQUE DE GESTION DU SERVICE GPS ---

        if (DetectedActivity.IN_VEHICLE == activityType && transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            Log.d(TAG, "🚗 Détection : Entrée en voiture. Start GPS.");
            updateDrivingState(safeContext, true); // Persistance Direct Boot
            cancelGraceAlarm(context);
            startTrackingService(context);
        } 
        else if (
            (DetectedActivity.IN_VEHICLE == activityType && transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) || 
            (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER && DetectedActivity.IN_VEHICLE != activityType)
        ) {
            if (isServiceRunning(context)) {
                Log.d(TAG, "⏳ Détection : Fin de conduite probable. Timer 3min lancé.");
                scheduleGraceAlarm(context);
            }
        }
    }

    // --- MISE A JOUR DE L'ETAT POUR LE REBOOT ---
    private void updateDrivingState(Context safeContext, boolean isDriving) {
        safeContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .edit().putBoolean("driving_state", isDriving).apply();
    }

    private void scheduleGraceAlarm(Context context) {
        Intent intent = new Intent(context, ActivityTransitionReceiver.class);
        intent.setAction(ACTION_STOP_GPS_GRACE);
        
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE 
            : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        // Calcul du moment de déclenchement (3 minutes)
        long triggerAt = System.currentTimeMillis() + STOP_DELAY;

        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Version "Play Store Friendly" : Inexacte mais fonctionne pendant le mode Doze
                // Le système peut décaler un peu pour grouper les alarmes et économiser la batterie.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                Log.d(TAG, "⏰ Alarme programmée (Inexacte/AllowWhileIdle) - Compatible Play Store");
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }
    }

    private void cancelGraceAlarm(Context context) {
        Intent intent = new Intent(context, ActivityTransitionReceiver.class);
        intent.setAction(ACTION_STOP_GPS_GRACE);
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);
    }

    private void startTrackingService(Context context) {
        Intent serviceIntent = new Intent(context, TrackingService.class);
        serviceIntent.setAction("fr.lelab.activity.START_TRACKING"); 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    private void stopTrackingService(Context context) {
        Log.d(TAG, "🛑 Arrêt du service GPS...");
        Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? context.createDeviceProtectedStorageContext() : context;
        updateDrivingState(safeContext, false);

        Intent serviceIntent = new Intent(context, TrackingService.class);
        context.stopService(serviceIntent);
        
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(1); // On force la suppression de la notification
    }

    private boolean isServiceRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (TrackingService.class.getName().equals(service.service.getClassName())) return true;
        }
        return false;
    }

    private String getActivityName(int activityType) {
        switch (activityType) {
            case DetectedActivity.IN_VEHICLE: return "automotive";
            case DetectedActivity.ON_BICYCLE: return "cycling";
            case DetectedActivity.ON_FOOT: return "walking";
            case DetectedActivity.RUNNING: return "running";
            case DetectedActivity.STILL: return "stationary";
            case DetectedActivity.WALKING: return "walking";
            default: return "UNKNOWN_" + activityType;
        }
    }
}