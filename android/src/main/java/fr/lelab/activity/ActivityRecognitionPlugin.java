package fr.lelab.activity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import android.Manifest;
import android.os.Build;

@CapacitorPlugin(
    name = "ActivityRecognition",
    permissions = {
        @Permission(
            alias = "activity",
            strings = { 
                // Pour Android 10 (API 29) et plus
                "android.permission.ACTIVITY_RECOGNITION" 
            }
        )
    }
)
public class ActivityRecognitionPlugin extends Plugin {

    // 1. On déclare seulement la variable ici
    private ActivityRecognition implementation;
    private static ActivityRecognitionPlugin instance;

    @Override
    public void load() {
        // 2. On l'initialise ICI avec le contexte, car le constructeur le demande
        implementation = new ActivityRecognition(getContext());
        instance = this;
    }

    /**
     * Méthode appelée par ActivityTransitionReceiver pour envoyer les données vers JS.
     */
    public static void onActivityEvent(JSObject data) {
        if (instance != null) {
            instance.notifyListeners("activityChange", data);
        }
    }

    @PluginMethod
    public void startTracking(PluginCall call) {
        // On vérifie si la permission est accordée avant de démarrer
        if (getPermissionState("activity") != com.getcapacitor.PermissionState.GRANTED) {
            call.reject("Permission de reconnaissance d'activité non accordée");
            return;
        }

        int interval = call.getInt("interval", 10000);
        try {
            implementation.startTracking(interval);
            call.resolve();
        } catch (Exception e) {
            call.reject("Erreur lors du démarrage du tracking: " + e.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        try {
            implementation.stopTracking();
            call.resolve();
        } catch (Exception e) {
            call.reject("Erreur lors de l'arrêt du tracking: " + e.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        super.checkPermissions(call);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        super.requestPermissions(call);
    }
}