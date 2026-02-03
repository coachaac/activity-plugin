package fr.lelab.activity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        
        // On normalise le nom pour le stockage et le JS
        String normalizedActivity = activityName.toLowerCase().replace("in_vehicle", "automotive");
        
        Log.d(TAG, "⚡ Traitement : " + normalizedActivity + " [" + transitionName + "]");

        // 1. Sauvegarde dans le fichier local (JSONL) via le Helper
        // On utilise le nom normalisé pour la cohérence
        JsonStorageHelper.saveActivity(context, normalizedActivity, transitionName);

        // 2. Notification en temps réel au JavaScript (Capacitor Listeners)
        JSObject data = new JSObject();
        data.put("activity", normalizedActivity);
        data.put("transition", transitionName);
        // MODIF : On reste en millisecondes (suppression du / 1000)
        data.put("timestamp", System.currentTimeMillis()); 
        
        ActivityRecognitionPlugin.onActivityEvent(data);

        // --- LOGIQUE DE GESTION DU SERVICE GPS ---

        // CAS 1 : On entre dans le véhicule -> Start GPS
        if (DetectedActivity.IN_VEHICLE == activityType && transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            Log.d(TAG, "🚗 Détection : Entrée en voiture. Start GPS.");
            cancelGraceAlarm(context);
            startTrackingService(context);
        } 

        // CAS 2 : On SORT du véhicule OU on commence une autre activité
        else if (
            (DetectedActivity.IN_VEHICLE == activityType && transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) || 
            (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER && DetectedActivity.IN_VEHICLE != activityType)
        ) {
            if (isServiceRunning(context)) {
                Log.d(TAG, "⏳ Détection : Fin de conduite probable (" + normalizedActivity + "). Timer 3min lancé.");
                scheduleGraceAlarm(context);
            }
        }
    }

    private void scheduleGraceAlarm(Context context) {
        Intent intent = new Intent(context, ActivityTransitionReceiver.class);
        intent.setAction(ACTION_STOP_GPS_GRACE);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        long triggerAt = System.currentTimeMillis() + STOP_DELAY;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Vérifie si on a le droit de programmer une alarme exacte
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                // Repli sur une alarme normale si la permission est refusée
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10000, pi);
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        Log.d(TAG, "⏰ Alarme programmée dans 3 min (Exact)");
    }

    private void cancelGraceAlarm(Context context) {
        Intent intent = new Intent(context, ActivityTransitionReceiver.class);
        intent.setAction(ACTION_STOP_GPS_GRACE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi);
    }


    private void startTrackingService(Context context) {
        Intent serviceIntent = new Intent(context, TrackingService.class);
        // On ajoute l'action explicite !
        serviceIntent.setAction("fr.lelab.activity.START_TRACKING"); 
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }


    private void stopTrackingService(Context context) {
        Log.d(TAG, "🛑 Tentative d'arrêt du service GPS...");
        Intent serviceIntent = new Intent(context, TrackingService.class);
        
        // 1. On demande l'arrêt au système
        boolean stopped = context.stopService(serviceIntent);
        
        // 2. Sécurité supplémentaire : On annule la notification directement depuis ici
        // au cas où le service mettrait trop de temps à mourir
        if (stopped) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(1); // Utilise l'ID de notification (1) défini dans ton service
            Log.d(TAG, "✅ Service stoppé et notification annulée.");
        }
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