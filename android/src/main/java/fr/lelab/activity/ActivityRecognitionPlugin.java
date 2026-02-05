package fr.lelab.activity;

import java.io.File;
import android.net.Uri;
import android.content.Intent;
import androidx.core.content.FileProvider;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.PowerManager;
import android.location.LocationManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.PermissionState;

@CapacitorPlugin(
    name = "ActivityRecognition",
    permissions = {
        @Permission(
            alias = "activity",
            strings = { 
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }
        ),
        @Permission(
            alias = "backgroundLocation",
            strings = { "android.permission.ACCESS_BACKGROUND_LOCATION" }
        )
    }
)
public class ActivityRecognitionPlugin extends Plugin {

    private ActivityRecognition implementation;
    private static ActivityRecognitionPlugin instance;
    private boolean debugMode = false;
    private boolean isDriving = false;

    // --- HELPER : Stockage protégé pour le reboot (Direct Boot) ---
    private Context getSafeContext() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? getContext().createDeviceProtectedStorageContext() 
            : getContext();
    }

    @Override
    public void load() {
        super.load();
        instance = this;
        implementation = new ActivityRecognition(getContext());

        // On utilise getSafeContext() pour que l'état survive même si le tel n'est pas déverrouillé
        SharedPreferences prefs = getSafeContext().getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        boolean wasTracking = prefs.getBoolean("tracking_active", false);
        boolean wasDriving = prefs.getBoolean("driving_state", false);

        if (wasTracking) {
            Log.d("SmartPilot", "🔄 Relance automatique de la détection d'activité");
            implementation.startTracking();
            
            if (wasDriving) {
                Log.d("SmartPilot", "🚗 Reprise du tracking GPS (Foreground Service)");
                Intent intent = new Intent(getContext(), TrackingService.class);
                intent.setAction("fr.lelab.activity.START_TRACKING");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getContext().startForegroundService(intent);
                } else {
                    getContext().startService(intent);
                }
            }
        }
    }

    // --- VIBRATION HELPER (Inchangé) ---
    private void triggerVibration(int count) {
        Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (count == 2) {
                long[] pattern = {0, 200, 100, 200};
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } else {
            v.vibrate(count == 2 ? 500 : 300);
        }
    }

    // --- EVENTS (Appelés par les Receivers) ---
    public static void onLocationEvent(JSObject data) {
        if (instance != null) {
            instance.notifyListeners("onLocationUpdate", data);
        }
    }

    public static void onActivityEvent(JSObject data) {
        if (instance != null) {
            String activityType = data.getString("activity");
            String transition = data.getString("transition");
            
            if ("automotive".equals(activityType) && "ENTER".equals(transition)) {
                instance.isDriving = true;
                if (instance.debugMode) instance.triggerVibration(2);
            } else if ("automotive".equals(activityType) && "EXIT".equals(transition)) {
                if (instance.debugMode) instance.triggerVibration(1);
            }
            // Nom du listener harmonisé avec le JS
            instance.notifyListeners("activityChange", data);
        }
    }

    // --- PLUGIN METHODS (Interface JS) ---

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        JSObject ret = new JSObject();
        
        // Récupération des états précis
        String activityState = getPermissionState("activity").toString();
        String locationState = getPermissionState("backgroundLocation").toString();
        
        // On renvoie un objet détaillé
        ret.put("activity", activityState); // renverra "granted", "denied" ou "prompt"
        ret.put("location", locationState);
        
        // On garde 'granted' pour la compatibilité globale si besoin
        ret.put("granted", getPermissionState("activity") == com.getcapacitor.PermissionState.GRANTED 
                && getPermissionState("backgroundLocation") == com.getcapacitor.PermissionState.GRANTED);
        
        call.resolve(ret);
    }

    @PluginMethod
    public void startTracking(PluginCall call) {
        if (!isSystemReady()) {
            call.reject("Le système n'est pas prêt. Vérifiez les permissions et le GPS.");
            return;
        }
        this.debugMode = call.getBoolean("debug", false);
        this.isDriving = false;

        // Sauvegarde de l'état "Actif"
        getSafeContext().getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .edit().putBoolean("tracking_active", true).apply();

        implementation.startTracking();
        call.resolve();
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        isDriving = false;
        // Mise à jour de l'état "Inactif"
        getSafeContext().getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .edit().putBoolean("tracking_active", false)
            .putBoolean("driving_state", false).apply();

        implementation.stopTracking();
        call.resolve();
    }

    @PluginMethod
    public void getSavedLocations(PluginCall call) {
        // Lecture dans le stockage sécurisé (Direct Boot Aware)
        JSArray locations = JsonStorageHelper.loadLocationsAsJSArray(getSafeContext());
        JSObject ret = new JSObject();
        ret.put("locations", locations);
        call.resolve(ret);
    }

    @PluginMethod
    public void clearSavedLocations(PluginCall call) {
        JsonStorageHelper.clearLocations(getSafeContext());
        call.resolve();
    }

    @PluginMethod
    public void shareSavedLocations(PluginCall call) {
        try {
            File file = new File(getSafeContext().getFilesDir(), "stored_locations.json");
            if (!file.exists()) {
                call.reject("Fichier introuvable.");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            Uri fileUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
            intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(Intent.createChooser(intent, "Partager les positions"));
            call.resolve();
        } catch (Exception e) {
            call.reject("Erreur lors du partage : " + e.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void purgeLocationsBefore(PluginCall call) {
        Long timestampLimit = call.getLong("timestamp");
        if (timestampLimit == null) timestampLimit = call.getLong("before");

        if (timestampLimit != null) {
            JsonStorageHelper.purgeLocationsBefore(getSafeContext(), timestampLimit);
            call.resolve();
        } else {
            call.reject("Le paramètre 'timestamp' ou 'before' est obligatoire.");
        }
    }

    // --- SYSTEM CHECK (Permissions et Capteurs) ---
    private boolean isSystemReady() {
        Context context = getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        }
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false;

        return true;
    }
}