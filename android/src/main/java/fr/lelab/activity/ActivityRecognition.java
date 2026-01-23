package fr.lelab.activity;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
// RETIRÉ : import com.google.android.gms.location.ActivityRecognition; (Conflit de nom)
import java.util.ArrayList;
import java.util.List;

public class ActivityRecognition {
    private Context context;
    private ActivityRecognitionClient client;
    private PendingIntent pendingIntent;

    public ActivityRecognition(Context context) {
        this.context = context;
        // On utilise le nom complet du package pour éviter le conflit
        this.client = com.google.android.gms.location.ActivityRecognition.getClient(context);
    }

    public void startTracking(int interval) {
        List<ActivityTransition> transitions = new ArrayList<>();

        int[] activities = {
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE
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

        ActivityTransitionRequest request = new ActivityTransitionRequest(transitions);
        
        Intent intent = new Intent(context, ActivityTransitionReceiver.class);
        intent.setAction("fr.lelab.activity.ACTION_PROCESS_ACTIVITY_TRANSITIONS");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags);

        client.requestActivityTransitionUpdates(request, pendingIntent)
            .addOnSuccessListener(aVoid -> android.util.Log.i("ActivityRecognition", "Tracking activé !"))
            .addOnFailureListener(e -> android.util.Log.e("ActivityRecognition", "Erreur d'activation", e));
    }

    public void stopTracking() {
        if (client != null && pendingIntent != null) {
            client.removeActivityTransitionUpdates(pendingIntent);
        }
    }
}