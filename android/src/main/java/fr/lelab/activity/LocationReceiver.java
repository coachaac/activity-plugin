package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import com.getcapacitor.JSObject;
import com.google.android.gms.location.LocationResult;

public class LocationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !LocationResult.hasResult(intent)) return;

        LocationResult locationResult = LocationResult.extractResult(intent);
        if (locationResult == null) return;

        for (Location location : locationResult.getLocations()) {
            // On convertit le point en JSObject
            JSObject data = JsonStorageHelper.locationToJSObject(location);
            
            // On envoie tout au Plugin. 
            // C'est LUI qui vérifie 'isDriving' et qui appelle 'saveLocation'.
            ActivityRecognitionPlugin.onLocationEvent(data);
        }
    }
}