package fr.lelab.activity;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import java.util.ArrayList;
import java.util.List;

public class ActivityRecognition {
    private Context context;
    private ActivityRecognitionClient activityClient;
    private PendingIntent activityPendingIntent;
    private static final String TAG = "ActivityRecognition";

    public ActivityRecognition(Context context) {
        this.context = context;
        this.activityClient = com.google.android.gms.location.ActivityRecognition.getClient(context);
    }

    public void startTracking() {
        setupActivityTransitions();
    }

    private void setupActivityTransitions() {
        List<ActivityTransition> transitions = new ArrayList<>();
        int[] activities = {DetectedActivity.WALKING, DetectedActivity.STILL, DetectedActivity.IN_VEHICLE, DetectedActivity.RUNNING};

        for (int activity : activities) {
            transitions.add(new ActivityTransition.Builder().setActivityType(activity)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
            transitions.add(new ActivityTransition.Builder().setActivityType(activity)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build());
        }

        Intent intent = new Intent(context, ActivityTransitionReceiver.class);
        intent.setAction("fr.lelab.activity.ACTION_PROCESS_ACTIVITY_TRANSITIONS");
        activityPendingIntent = createPendingIntent(intent);

        try {
            activityClient.requestActivityTransitionUpdates(new ActivityTransitionRequest(transitions), activityPendingIntent)
                .addOnSuccessListener(aVoid -> Log.i(TAG, "Sensors activated"));
        } catch (SecurityException e) { Log.e(TAG, "Missing permission", e); }
    }

    public void stopTracking() {
        if (activityClient != null && activityPendingIntent != null) {
            activityClient.removeActivityTransitionUpdates(activityPendingIntent);
        }
        ActivityRecognitionHelper.stopActivityTransitions(context);
    }

    private PendingIntent createPendingIntent(Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }
}