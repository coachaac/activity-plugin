package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

public class ActivityTransitionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
            SharedPreferences prefs = context.getSharedPreferences("SmartPilotPrefs", Context.MODE_PRIVATE);

            for (ActivityTransitionEvent event : result.getTransitionEvents()) {
                String activityName = getActivityName(event.getActivityType());
                String transitionName = (event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) ? "ENTER" : "EXIT";

                JsonStorageHelper.saveActivity(context, activityName, transitionName);

                if (event.getActivityType() == DetectedActivity.IN_VEHICLE) {
                    if (event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        prefs.edit().putBoolean("is_driving", true).apply();
                        ActivityRecognitionHelper.setupActivityTransitions(context);
                    } else {
                        prefs.edit().putBoolean("is_driving", false).apply();
                        ActivityRecognitionHelper.stopActivityTransitions(context);
                    }
                } else if (event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    if (event.getActivityType() == DetectedActivity.WALKING || event.getActivityType() == DetectedActivity.STILL) {
                        ActivityRecognitionHelper.stopActivityTransitions(context);
                    }
                }
            }
        }
    }


    // Méthode de conversion des codes d'activité en texte lisible
    private String getActivityName(int activityType) {
        switch (activityType) {
            case com.google.android.gms.location.DetectedActivity.IN_VEHICLE:
                return "IN_VEHICLE";
            case com.google.android.gms.location.DetectedActivity.ON_BICYCLE:
                return "ON_BICYCLE";
            case com.google.android.gms.location.DetectedActivity.ON_FOOT:
                return "ON_FOOT";
            case com.google.android.gms.location.DetectedActivity.RUNNING:
                return "RUNNING";
            case com.google.android.gms.location.DetectedActivity.STILL:
                return "STILL";
            case com.google.android.gms.location.DetectedActivity.TILTING:
                return "TILTING";
            case com.google.android.gms.location.DetectedActivity.WALKING:
                return "WALKING";
            default:
                return "UNKNOWN_" + activityType;
        }
    }
}