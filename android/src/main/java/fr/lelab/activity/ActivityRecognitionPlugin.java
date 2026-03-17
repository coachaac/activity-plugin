package fr.lelab.activity;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
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
import android.provider.Settings;

import androidx.core.app.ActivityCompat;

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
            strings = { Manifest.permission.ACTIVITY_RECOGNITION }
        ),
        @Permission(
            alias = "location", // Alias standard pour la position au premier plan
            strings = { 
                Manifest.permission.ACCESS_FINE_LOCATION, 
                Manifest.permission.ACCESS_COARSE_LOCATION 
            }
        ),
        @Permission(
            alias = "backgroundLocation",
            strings = { Manifest.permission.ACCESS_BACKGROUND_LOCATION }
        )
    }
)


public class ActivityRecognitionPlugin extends Plugin {

    private ActivityRecognition implementation;
    private static ActivityRecognitionPlugin instance;
    private boolean debugMode = false;
    private boolean isDriving = false;

    private static final String TAG = "ActivityRecognitionPlugin";

    public static final Object fileLock = new Object();

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

        // Use getSafeContext() accessible even if phone not unlocked
        SharedPreferences prefs = getSafeContext().getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        boolean wasTracking = prefs.getBoolean("tracking_active", false);
        boolean wasDriving = prefs.getBoolean("driving_state", false);

        boolean hasPerms = ActivityCompat.checkSelfPermission(getContext(), 
                       Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (wasTracking && hasPerms) {

            Log.d("SmartPilot", "🔄 Automatic activity detection re-launched");
            implementation.startTracking();
            
            if (wasDriving) {
                Log.d("SmartPilot", "🚗 Restart GPS tracking (Foreground Service)");
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

    // --- EVENTS (Called by Receivers) ---
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

                Log.d(TAG, "🚗 Automotive ENTER detected: Forcing weather update.");
                WeatherReceiver.fetchWeatherData(instance.getContext(), true); 

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

        // On récupère les états de manière sécurisée
        String activityState = getSafePermissionState("activity");
        String locationState = getSafePermissionState("location");
        String bgLocationState = getSafePermissionState("backgroundLocation");

        ret.put("activity", activityState);
        ret.put("location", locationState);
        ret.put("backgroundLocation", bgLocationState);

        // Un flag 'granted' global pour simplifier le JS
        boolean allOk = "granted".equals(activityState) && 
                        "granted".equals(locationState) && 
                        "granted".equals(bgLocationState);
                        
        ret.put("granted", allOk);

        call.resolve(ret);
    }

    // Méthode helper pour éviter le NullPointerException
    private String getSafePermissionState(String alias) {
        com.getcapacitor.PermissionState state = getPermissionState(alias);
        if (state == null) {
            return "prompt"; // default id state not yet known
        }
        return state.toString();
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        // Si l'utilisateur demande le background sur Android 11+
        // on peut ajouter une logique ici, mais Capacitor gère déjà 
        // l'affichage des popups correspondantes aux alias déclarés dans l'annotation.
        super.requestPermissions(call);
    }

    @PluginMethod
    public void startTracking(PluginCall call) {

        SharedPreferences trackPrefs = getContext().getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        boolean wasTracking = trackPrefs.getBoolean("tracking_active", false);

        this.debugMode = call.getBoolean("debug", false);
        this.isDriving = false;

        String url = call.getString("url");
        String token = call.getString("groupId");

        String weatherKey = call.getString("weatherAPIkey");
        String weatherUrl = call.getString("weatherUrl");

        if (url == null || token == null) {
            // no send to server managed
            url = "";
            token = "";
        }

        Log.d(TAG, "🔍 Debug Config - URL: " + (url != null ? url : "MISSING") + " | Token: " + (token != null ? token : "MISSING"));

        // Persistent saving for server url/token 
        if (url != null && token != null) {
            SharedPreferences prefs = getContext().getSharedPreferences("TripPrefs", Context.MODE_PRIVATE);
            prefs.edit()
                .putString("server_url", url)
                .putString("jwt_token", token)
                .apply();
            Log.d("SmartPilot", "🌐 Server config saved for background sync");
        }

        // Save Active state
        getSafeContext().getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .edit().putBoolean("tracking_active", true).apply();

        // Save weather info
        SharedPreferences prefs = getContext().getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("weather_api_key", weatherKey);
        editor.putString("weather_base_url", weatherUrl);
        editor.apply();

        if (wasTracking)
        {
            // do not relaunch but say ok as already launched
            call.resolve();
            return;
        }

        if (!isSystemReady()) {
            call.reject("System not ready. Verify permissions and GPS.");
            return;
        }

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

        // Réinitialisation des filtres météo
        SharedPreferences prefs = getContext().getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("last_weather_lat");
        editor.remove("last_weather_lon");
        editor.apply();

        // On arrête aussi les alarmes planifiées
        JsonStorageHelper.cancelWeatherUpdates(getContext());

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

            Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
                ? getContext().createDeviceProtectedStorageContext() 
                : getContext();

            File sourceFile = new File(safeContext.getFilesDir(), "stored_locations.json");
            
            if (!sourceFile.exists()) {
                call.reject("File not Found!");
                return;
            }

            // create sharing zone accessible outside plugin
            File tempFile = new File(getContext().getCacheDir(), "stored_locations.json");
        
            // copy file
            java.nio.file.Files.copy(
                sourceFile.toPath(), 
                tempFile.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            // share from cache
            Uri contentUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                tempFile
            );

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/json");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(shareIntent, "Share File");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);

            call.resolve();
        } catch (Exception e) {
            call.reject("Share file error : " + e.getLocalizedMessage());
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
            call.reject(" 'timestamp' parameter mandatory");
        }
    }

    @PluginMethod
        public void purgeLocationsBetween(PluginCall call) {
            Long from = call.getLong("from");
            Long to = call.getLong("to");

            if (from != null && to != null) {
                JsonStorageHelper.purgeLocationsBetween(getSafeContext(), from, to);
                call.resolve();
            } else {
                call.reject("Parameters 'from' and 'to' (timestamps) are mandatory");
            }
        }


    @PluginMethod
        public void forceUpload(PluginCall call) {
            Context context = getContext();
            
            // Get infos from prefs
            SharedPreferences prefs = context.getSharedPreferences("TripPrefs", Context.MODE_PRIVATE);
            String url = prefs.getString("server_url", null);
            String token = prefs.getString("jwt_token", null);

            if (url == null || url.trim().isEmpty() || token == null || token.trim().isEmpty()) {
                call.reject("Configuration manquante (URL ou Token)");
                return;
            }

            try {
                // On lance le traitement synchrone
                JsonStorageHelper.processAndUploadAutomotiveTrips(context, url, token);
                
                JSObject ret = new JSObject();
                ret.put("status", "success");
                call.resolve(ret);
            } catch (Exception e) {
                Log.e("ActivityPlugin", "Erreur forceUpload: " + e.getMessage());
                call.reject("Erreur lors de l'upload: " + e.getMessage());
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


    private void triggerUpload() {
        androidx.work.Constraints constraints = new androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build();

        androidx.work.OneTimeWorkRequest uploadRequest = new androidx.work.OneTimeWorkRequest.Builder(TripUploadWorker.class)
                .setConstraints(constraints)
                .build();

        androidx.work.WorkManager.getInstance(getContext()).enqueue(uploadRequest);
        Log.d("SmartPilot", "📡 Upload worker enqueued");
    }


    @PluginMethod
        public void checkBatteryOptimization(PluginCall call) {
            Context context = getContext();
            String packageName = context.getPackageName();
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            
            JSObject ret = new JSObject();
            if (pm != null) {
                // Return true if app already ignore battery optimisation
                boolean isIgnoring = pm.isIgnoringBatteryOptimizations(packageName);
                ret.put("isIgnoring", isIgnoring);
            } else {
                ret.put("isIgnoring", true);
            }
            call.resolve(ret);
        }

    @PluginMethod
        public void requestIgnoreBatteryOptimization(PluginCall call) {
            Context context = getContext();
            String packageName = context.getPackageName();
            
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            try {
                context.startActivity(intent);
                call.resolve();
            } catch (Exception e) {
                // if fail open menu
                Intent generalIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                generalIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(generalIntent);
                call.resolve();
            }
        }


        @PluginMethod
            public void testSettings(PluginCall call) {
                // 1. Récupération des réglages depuis TripPrefs
                SharedPreferences prefs = getContext().getSharedPreferences("TripPrefs", Context.MODE_PRIVATE);
                String savedUrl = prefs.getString("server_url", "");
                String savedToken = prefs.getString("jwt_token", "");

                if (savedUrl.isEmpty()) {
                    call.reject("URL non configurée dans TripPrefs");
                    return;
                }

                // 2. Test de connexion en arrière-plan
                new Thread(() -> {
                    try {
                        HttpURLConnection connection = (HttpURLConnection) new URL(savedUrl).openConnection();
                        connection.setRequestMethod("HEAD");
                        connection.setConnectTimeout(5000); // 5 secondes max
                        
                        if (!savedToken.isEmpty()) {
                            // On utilise jwt_token pour l'autorisation
                            connection.setRequestProperty("Authorization", "Bearer " + savedToken);
                        }

                        int responseCode = connection.getResponseCode();
                        
                        JSObject ret = new JSObject();
                        ret.put("status", (responseCode >= 200 && responseCode < 300) ? "ok" : "error");
                        ret.put("statusCode", responseCode);
                        ret.put("url", savedUrl);
                        ret.put("groupId", savedToken); // On renvoie le token pour vérification côté JS
                        
                        call.resolve(ret);
                    } catch (Exception e) {
                        JSObject errorRet = new JSObject();
                        errorRet.put("status", "error");
                        errorRet.put("message", e.getMessage());
                        errorRet.put("url", savedUrl);
                        errorRet.put("groupId", savedToken);
                        call.resolve(errorRet);
                    }
                }).start();
            }

            @PluginMethod
            public void isSyncing(PluginCall call) {
                // Cette variable doit être mise à jour par ton service d'upload
                // Par exemple via une variable statique dans ton implémentation
                boolean syncing = JsonStorageHelper.syncInProgress; 
                
                JSObject ret = new JSObject();
                ret.put("inProgress", syncing);
                call.resolve(ret);
            }


}