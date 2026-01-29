package fr.lelab.activity;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;
import java.util.List;

public class ActivityRecognition {
    private Context context;
    private ActivityRecognitionClient activityClient;
    private PendingIntent activityPendingIntent;

    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;

    private static final String TAG = "ActivityRecognition";

    public ActivityRecognition(Context context) {
        this.context = context;
        this.activityClient = com.google.android.gms.location.ActivityRecognition.getClient(context);
        
        // Initialisation du GPS
        this.locationClient = LocationServices.getFusedLocationProviderClient(context);
        setupLocationCallback();
    }


    // --- GPS Management---

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                // On délègue l'enregistrement au Plugin (qui gère le filtrage isDriving)
                ActivityRecognitionPlugin.onLocationEvent(
                    JsonStorageHelper.locationToJSObject(locationResult.getLastLocation())
                );
            }
        };
    }

    public void startGPSUpdates() {
        Log.d(TAG, "🚀 Activation du GPS Haute Précision");
        try {
            // Configuration : Haute précision, 5 secondes entre chaque point
            LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                    .setMinUpdateDistanceMeters(10)
                    .build();

            locationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Erreur permission GPS", e);
        }
    }

    public void stopGPSUpdates() {
        Log.d(TAG, "🔋 Réduction précision GPS (Basse consommation)");
        try {
            locationClient.removeLocationUpdates(locationCallback);
            
            // On peut optionnellement relancer un mode très basse consommation ici
            // ou simplement laisser le tracker d'activité réveiller le GPS plus tard.
        } catch (Exception e) {
            Log.e(TAG, "Erreur arrêt GPS", e);
        }
    }


    // ACtivity Management
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
        stopGPSUpdates();
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