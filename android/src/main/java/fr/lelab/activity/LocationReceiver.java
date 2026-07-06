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

        // --- Protected Access (Direct Boot) ---
        Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? context.createDeviceProtectedStorageContext() 
            : context;

        // retrieve last weather object
        JSObject currentWeather = JsonStorageHelper.getLastWeather();

        // retrieve last forground App
        JSObject appForegroundData = JsonStorageHelper.getLastForegroundApp();

        boolean isDistractionActive = DistractionEventEmitter.getCurrentlyDistracted();

        for (Location location : locationResult.getLocations()) {

            // keep only point with accuracy less than 20m
            if (location.getAccuracy() > 20) {
                continue; 
            }

            
            // call saveLocation once. 
            // if currentWeather === null, saveLocation or appForegroundData manage properly
            JsonStorageHelper.saveLocation(
                safeContext, 
                location.getLatitude(), 
                location.getLongitude(), 
                location.getSpeed(),
                location.getTime(),
                currentWeather,
                appForegroundData,
                isDistractionActive
            );

            // 2. Notify plugin (UI Interface)
            JSObject data = JsonStorageHelper.locationToJSObject(location);
            ActivityRecognitionPlugin.onLocationEvent(data);
            
            Log.d("Position", "📍 GPS Point saved : " + location.getLatitude() + "," + location.getLongitude() + (currentWeather != null ? " (with weather)" : ""));
        }
    }
}