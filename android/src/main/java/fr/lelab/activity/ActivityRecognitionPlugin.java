package fr.lelab.activity;

import java.io.File;
import android.net.Uri;
import android.content.Intent;
import androidx.core.content.FileProvider;
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

    private ActivityRecognition implementation;
    private static ActivityRecognitionPlugin instance;

    @Override
    public void load() {
        super.load();
        instance = this;
        
        implementation = new ActivityRecognition(getContext());

        // Sécurity : no activity remains at startup
        ActivityRecognitionHelper.stopActivityTransitions(getContext());
    }

    // live location send to js
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
        // ✅ start activity sensor
        implementation.startTracking();
        call.resolve();
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        // ✅ stop
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

    @PluginMethod
    public void shareSavedLocations(PluginCall call) {
        try {
            File file = new File(getContext().getFilesDir(), "stored_locations.json");

            if (!file.exists()) {
                call.reject("Fichier introuvable.");
                return;
            }

            // Utilisation de l'utilitaire Capacitor pour le partage
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            
            // Création de l'URI sécurisée via FileProvider
            Uri fileUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                file
            );

            intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            getContext().startActivity(Intent.createChooser(intent, "Partager les positions"));
            call.resolve();
        } catch (Exception e) {
            call.reject("Erreur lors du partage : " + e.getLocalizedMessage());
        }
    }
}