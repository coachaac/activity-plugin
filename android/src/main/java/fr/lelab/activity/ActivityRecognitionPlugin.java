package fr.lelab.activity;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

@CapacitorPlugin(
    name = "ActivityRecognition",
    permissions = {
        @Permission(
            alias = "activity",
            strings = { 
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                "android.permission.ACCESS_BACKGROUND_LOCATION"
            }
        )
    }
)
public class ActivityRecognitionPlugin extends Plugin {

    // ✅ DÉCLARATION MANQUANTE :
    private ActivityRecognition implementation;
    private static ActivityRecognitionPlugin instance;

    @Override
    public void load() {
        super.load();
        instance = this;
        
        // ✅ Maintenant "implementation" est reconnu par le compilateur
        implementation = new ActivityRecognition(getContext());

        // Sécurité : On s'assure qu'aucun service ne traîne au démarrage
        ActivityRecognitionHelper.stopActivityTransitions(getContext());
    }

    // Appelé par le Receiver pour envoyer au JS en temps réel
    public static void onLocationEvent(JSObject data) {
        if (instance != null) {
            instance.notifyListeners("onLocationUpdate", data);
        }
    }

    public static void onActivityEvent(JSObject data) {
        if (instance != null) {
            instance.notifyListeners("activityChange", data);
        }
    }

    @PluginMethod
    public void startTracking(PluginCall call) {
        // ✅ On utilise l'implémentation pour armer les capteurs proprement
        implementation.startTracking();
        call.resolve();
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        // ✅ On arrête tout via l'implémentation
        implementation.stopTracking();
        call.resolve();
    }

    @PluginMethod
    public void getSavedLocations(PluginCall call) {
        JSArray locations = JsonStorageHelper.loadLocationsAsJSArray(getContext());
        JSObject ret = new JSObject();
        ret.put("locations", locations);
        call.resolve(ret);
    }

    @PluginMethod
    public void clearSavedLocations(PluginCall call) {
        JsonStorageHelper.clearLocations(getContext());
        call.resolve();
    }

    @PluginMethod
    public void enableAutonomousMode(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", false);
        SharedPreferences prefs = getContext().getSharedPreferences("SmartPilotPrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("autonomous_enabled", enabled).apply();

        if (enabled) {
            implementation.startTracking();
        } else {
            implementation.stopTracking();
        }
        call.resolve();
    }
}