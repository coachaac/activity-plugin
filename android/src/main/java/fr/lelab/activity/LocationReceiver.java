package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.google.android.gms.location.LocationResult;

public class LocationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !LocationResult.hasResult(intent)) return;

        LocationResult locationResult = LocationResult.extractResult(intent);
        if (locationResult == null) return;

        // --- Protexted Access (Direct Boot) ---
        Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? context.createDeviceProtectedStorageContext() 
            : context;

        for (Location location : locationResult.getLocations()) {
            
            // 1. Save in JSON file
            JsonStorageHelper.saveLocation(
                safeContext, 
                location.getLatitude(), 
                location.getLongitude(), 
                location.getSpeed()
            );

            // 2. Notify plugin (UI Interface)
            // Note : if App closed ActivityRecognitionPlugin.instance is null
            // event ignored (as requested).
            JSObject data = JsonStorageHelper.locationToJSObject(location);
            ActivityRecognitionPlugin.onLocationEvent(data);
            
            Log.d("Poisition", "📍 GPS Point saved : " + location.getLatitude() + "," + location.getLongitude());
        }
    }
}