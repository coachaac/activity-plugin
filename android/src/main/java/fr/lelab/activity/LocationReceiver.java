package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.google.android.gms.location.LocationResult;

public class LocationReceiver extends BroadcastReceiver {
    private static final String TAG = "LocationReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !LocationResult.hasResult(intent)) return;

        // VÉRIFICATION : Est-on en voiture ?
        boolean isDriving = context.getSharedPreferences("SmartPilotPrefs", Context.MODE_PRIVATE)
                                   .getBoolean("is_driving", false);

        if (!isDriving) {
            Log.d("LocationReceiver", "Ignoired Position(not in automotive mode)");
            return; 
        }

        LocationResult locationResult = LocationResult.extractResult(intent);
        for (Location location : locationResult.getLocations()) {
            // Sauvegarde seulement si isDriving est vrai
            JsonStorageHelper.saveLocation(context, location.getLatitude(), location.getLongitude());
            
            // Notification temps réel (optionnel)
            JSObject data = new JSObject();
            data.put("lat", location.getLatitude());
            data.put("lng", location.getLongitude());
            ActivityRecognitionPlugin.onLocationEvent(data);
        }
    }
}