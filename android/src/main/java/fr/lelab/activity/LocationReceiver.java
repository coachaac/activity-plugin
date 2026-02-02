package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.google.android.gms.location.LocationResult;

public class LocationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !LocationResult.hasResult(intent)) return;

        LocationResult locationResult = LocationResult.extractResult(intent);
        if (locationResult == null) return;

        for (Location location : locationResult.getLocations()) {
            // 1. On sauvegarde DIRECTEMENT dans le fichier (Append)
            // Cela garantit que même après un reboot/swipe, la donnée est écrite.
            JsonStorageHelper.saveLocation(
                context, 
                location.getLatitude(), 
                location.getLongitude(), 
                location.getSpeed()
            );

            // 2. On tente de prévenir le plugin (si l'app est ouverte) 
            // pour mettre à jour la carte ou l'interface en temps réel.
            JSObject data = JsonStorageHelper.locationToJSObject(location);
            ActivityRecognitionPlugin.onLocationEvent(data);
            
            Log.d("SmartPilot", "📍 Point GPS traité et sauvegardé en autonomie");
        }
    }
}