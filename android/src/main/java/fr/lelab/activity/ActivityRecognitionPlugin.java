package fr.lelab.activity;

import java.io.File;
import android.net.Uri;
import android.content.Intent;
import androidx.core.content.FileProvider;
import android.Manifest;
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

    @Override
    public void load() {
        super.load();
        instance = this;
        implementation = new ActivityRecognition(getContext());

        // 1. Sécurité : On arrête la reconnaissance d'activité
        ActivityRecognitionHelper.stopActivityTransitions(getContext());

        // 2. CRUCIAL : On arrête le service de tracking au démarrage
        // Cela supprime la notification "fantôme" héritée du dernier lancement
        Intent intent = new Intent(getContext(), TrackingService.class);
        getContext().stopService(intent);
        
        Log.d("SmartPilot", "🧹 Nettoyage : Service de tracking arrêté au lancement");
    }


    // vibrate helper
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
            // Retro compatibility Android 7
            v.vibrate(count == 2 ? 500 : 300);
        }
    }


    // Detect Event location
    public static void onLocationEvent(JSObject data) {
        if (instance != null) {
            // On ne sauvegarde et on ne notifie le JS que si le mode conduite est actif
            // Note: Le service GPS peut tourner pendant la Grace Period (gérée par le Receiver)
            try {
                JsonStorageHelper.saveLocation(
                    instance.getContext(),
                    data.getDouble("lat"),
                    data.getDouble("lng"),
                    data.getInteger("speed", 0).floatValue()
                );

                instance.notifyListeners("onLocationUpdate", data);
            } catch (Exception e) {
                Log.e("SmartPilot", "Erreur sauvegarde location", e);
            }
        }
    }


    public static void onActivityEvent(JSObject data) {
        if (instance != null) {
            String activityType = data.getString("activity");
            String transition = data.getString("transition");
            
            // Mise à jour de l'état local pour le filtrage
            if ("automotive".equals(activityType) && "ENTER".equals(transition)) {
                instance.isDriving = true;
                if (instance.debugMode) instance.triggerVibration(2);
            } else if ("automotive".equals(activityType) && "EXIT".equals(transition)) {
                // On garde isDriving à true jusqu'à la fin réelle du service (Grace Period)
                // Ou on laisse le Receiver gérer l'arrêt complet.
                if (instance.debugMode) instance.triggerVibration(1);
            }

            instance.notifyListeners("activityChange", data);
        }
    }



    @PluginMethod
    public void startTracking(PluginCall call) {
        // check if all Ok
        if (!isSystemReady()) {
            call.reject("Le système n'est pas prêt. Vérifiez les permissions (Activité et Position 'Toujours') et activez le GPS.");
            return;
        }

        // get value of debug Mode
        this.debugMode = call.getBoolean("debug", false);

        this.isDriving = false;

        // ✅ start activity sensor
        implementation.startTracking();
        call.resolve();
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
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

    private boolean isSystemReady() {
        Context context = getContext();
        
        // 1. Check Activité Physique (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.e("SmartPilot", "❌ Permission Activité Physique manquante");
                return false;
            }
        }
        
        // 2. Check Background Location (Android 11+)
        // Crucial pour que le GPS ne se coupe pas quand on verrouille l'écran
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.e("SmartPilot", "❌ Permission Localisation en arrière-plan manquante");
                return false;
            }
        }

        // 3. Check si le GPS est activé au niveau système
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.e("SmartPilot", "❌ Le GPS est désactivé dans les réglages système");
            return false;
        }

        // 4. Check Optimisation Batterie (Doze Mode)
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = context.getPackageName();
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.w("SmartPilot", "⚠️ L'application est soumise à l'optimisation de batterie.");
                // Ici, on ne bloque pas forcément le démarrage (return false), 
                // mais on peut envoyer un avertissement ou ouvrir les réglages.
                requestIgnoreBatteryOptimizations();
            }
        }

        Log.d("SmartPilot", "✅ Système prêt pour le tracking (Android " + Build.VERSION.RELEASE + ")");
        return true;
    }


    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent();
                intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Exception e) {
                // Si l'intent direct échoue, on ouvre la liste globale
                Intent intent = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
        }
    }
}