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

        // --- OPTIMISATION : Accès au stockage protégé (Direct Boot) ---
        // On s'assure que le contexte utilisé pour le Helper peut écrire même si le tel est verrouillé
        Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? context.createDeviceProtectedStorageContext() 
            : context;

        for (Location location : locationResult.getLocations()) {
            
            // 1. Sauvegarde DIRECTE dans le fichier JSONL
            // On utilise le safeContext pour garantir l'écriture en toute circonstance
            JsonStorageHelper.saveLocation(
                safeContext, 
                location.getLatitude(), 
                location.getLongitude(), 
                location.getSpeed()
            );

            // 2. Notification au Plugin (Interface UI)
            // Note : Si l'app est fermée, ActivityRecognitionPlugin.instance sera null
            // et l'événement sera simplement ignoré (c'est le comportement voulu).
            JSObject data = JsonStorageHelper.locationToJSObject(location);
            ActivityRecognitionPlugin.onLocationEvent(data);
            
            Log.d("SmartPilot", "📍 Point GPS sauvegardé : " + location.getLatitude() + "," + location.getLongitude());
        }
    }
}