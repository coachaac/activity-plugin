package fr.lelab.activity;

import java.io.File;
import android.net.Uri;
import android.content.Intent;
import androidx.core.content.FileProvider;
import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.os.Vibrator;
import android.os.VibrationEffect;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.google.android.gms.location.DetectedActivity;

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

    private boolean debugMode = false;

    // vibrate helper
    private void triggerVibration(int count) {
        Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        
        if (count == 2) {
            long[] pattern = {0, 200, 100, 200}; // Pause, Vib, Pause, Vib
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    // --- GRACE PERIOD LOGIC---
    private boolean isDriving = false;
    private final Handler graceHandler = new Handler(Looper.getMainLooper());
    private final long STOP_DELAY = 180000; // 3 minutes

    // Executed task after 3min not driving
    private final Runnable stopGPSRunnable = () -> {
        Log.d("SmartPilot", "⏱ end of grace delay : stop GPS");
        isDriving = false;
        if (implementation != null) {
            // Stop high precision GPS
            implementation.stopGPSUpdates(); 
        }

        // vibrate once
        if (debugMode) 
            triggerVibration(1);
    };

    @Override
    public void load() {
        super.load();
        instance = this;
        
        implementation = new ActivityRecognition(getContext());

        // Sécurity : no activity remains at startup
        ActivityRecognitionHelper.stopActivityTransitions(getContext());
    }

    // Detect Event location
    public static void onLocationEvent(JSObject data) {
        if (instance != null) {
            // Notify GPS point only if automotive or grace period
            if (instance.isDriving) {

                try {
                // On tente de lire et sauvegarder
                JsonStorageHelper.saveLocation(
                    instance.getContext(),
                    data.getDouble("lat"),
                    data.getDouble("lng"),
                    data.getInteger("speed", 0).floatValue()
                );
                } catch (Exception e) {
                    // Si une clé est manquante (ex: lat), on log l'erreur sans crasher
                    Log.e("SmartPilot", "Erreur lors de la lecture des données GPS pour sauvegarde", e);
                }

                // notify JS interface with location
                instance.notifyListeners("onLocationUpdate", data);
            }
        }
    }

    public static void onActivityEvent(JSObject data) {
        if (instance != null) {
            String activityType = data.getString("activity");
            instance.handleActivityLogic(activityType);
            instance.notifyListeners("activityChange", data);
        }
    }


    private void handleActivityLogic(String activityType) {
        if ("automotive".equals(activityType)) {
            Log.d("SmartPilot", "🚗 Automotive detected : stop timer");
            graceHandler.removeCallbacks(stopGPSRunnable);
            
            if (!isDriving) {
                isDriving = true;
                implementation.startGPSUpdates(); // launch high precision GPS

                // if debug mode vibrate twice
                if (debugMode) 
                    triggerVibration(2);
            }
        } 
        else {
            // Si on est en "walking", "stationary", etc.
            if (isDriving) {
                Log.d("SmartPilot", "⏳ Exit automotive: 3min Timer launched");
                // isDriving not reset  ! 
                // wait for end of timer for that.
                graceHandler.removeCallbacks(stopGPSRunnable);
                graceHandler.postDelayed(stopGPSRunnable, STOP_DELAY);
            }
        }
    }


    @PluginMethod
    public void startTracking(PluginCall call) {
        // get value of debug Mode
        this.debugMode = call.getBoolean("debug", false);

        // ✅ start activity sensor
        implementation.startTracking();
        call.resolve();
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        graceHandler.removeCallbacks(stopGPSRunnable);
        isDriving = false;
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