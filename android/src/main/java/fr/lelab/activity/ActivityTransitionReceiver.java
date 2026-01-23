package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.google.android.gms.location.ActivityTransition; // IMPORT IMPORTANT
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

public class ActivityTransitionReceiver extends BroadcastReceiver {
    private static final String TAG = "ActivityReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
            if (result != null) {
                for (ActivityTransitionEvent event : result.getTransitionEvents()) {
                    String activityType = getActivityName(event.getActivityType());
                    
                    // 1. On déclare la variable "transitionType" ici
                    String transitionType = (event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) ? "ENTER" : "EXIT";
                    
                    Log.i(TAG, "Transition détectée: " + activityType + " (" + transitionType + ")");
                    
                    JSObject data = new JSObject();
                    data.put("type", activityType);
                    
                    // 2. On utilise BIEN "transitionType" ici pour remplir la clé "transition"
                    data.put("transition", transitionType); 
                    
                    ActivityRecognitionPlugin.onActivityEvent(data);
                }
            }
        }
    }

    private String getActivityName(int type) {
        switch (type) {
            case DetectedActivity.STILL: return "stationary"; // Correspond au TS
            case DetectedActivity.WALKING: return "walking";
            case DetectedActivity.RUNNING: return "running";
            case DetectedActivity.ON_BICYCLE: return "cycling";
            case DetectedActivity.IN_VEHICLE: return "automotive";
            default: return "unknown";
        }
}
}