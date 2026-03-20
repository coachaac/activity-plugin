package fr.lelab.activity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class JsonStorageHelper {

    private static final String FILE_NAME = "stored_locations.json";
    private static final String TAG = "JsonStorageHelper";

    public static boolean syncInProgress = false;

    private static JSObject lastWeather = null;

    /**
    * Return Device Protected Storage
     * Allow read/write even before pin verification
     * */
    private static File getSafeFilesDir(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.createDeviceProtectedStorageContext().getFilesDir();
        }
        return context.getFilesDir();
    }


    /**
     * Save location APPEND mode
     */
    public static void saveLocation(Context context, double lat, double lng, float speed, JSObject weather) {
       synchronized (ActivityRecognitionPlugin.fileLock) {
           File file = new File(getSafeFilesDir(context), FILE_NAME);
            
            JSObject location = new JSObject();
            location.put("type", "location"); 
            location.put("lat", lat);
            location.put("lng", lng);
            location.put("speed", speed);
            location.put("date", getFormattedDate());
            location.put("timestamp", System.currentTimeMillis());

            if (weather != null) {
                location.put("weather", weather);
            }

            String entry = location.toString() + "\n";

            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(entry.getBytes());
            } catch (IOException e) {
                Log.e(TAG, "❌ Error during Append position writing", e);
            }
        }
    }


    /**
     * Sauvegarde d'un log file 
     */
    public static void logToFile(Context context, String message) {
        File logFile = new File(getSafeFilesDir(context), "debug_log.txt");
        
        // --- Auto-vide : Si le fichier dépasse 1 Mo (1024 * 1024 octets) ---
        if (logFile.exists() && logFile.length() > 1024 * 1024) {
            logFile.delete(); 
            // On recrée le fichier avec une petite note
            try (FileOutputStream fos = new FileOutputStream(logFile)) {
                fos.write("--- Log reset (Taille max atteinte) ---\n".getBytes());
            } catch (IOException ignored) {}
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(new Date());
        String entry = timestamp + " : " + message + "\n";
        
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            fos.write(entry.getBytes());
        } catch (IOException e) {
            Log.e("DEBUG", "Error writing to debug log", e);
        }
    }

    /**
     * Sauvegarde un changement d'activité
     */
    public static void saveActivity(Context context, String activityName, String transitionName) {
        
        // log during debug TO BE REMOVED START
        String logString = "Activitée reçue: " + activityName + " transition: " +transitionName;
        logToFile(context, logString);
        // log during debug TO BE REMOVED END

        synchronized (ActivityRecognitionPlugin.fileLock) {
            File file = new File(getSafeFilesDir(context), FILE_NAME);
            
            JSObject activity = new JSObject();
            activity.put("type", "activity");
            activity.put("activity", activityName);
            activity.put("transition", transitionName);
            activity.put("date", getFormattedDate());
            activity.put("timestamp", System.currentTimeMillis());
            
            String entry = activity.toString() + "\n";

            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(entry.getBytes());
                Log.d(TAG, "🏃 Activity registered : " + activityName);
            } catch (IOException e) {
                Log.e(TAG, "❌ Error during Append activity writing", e);
            }
        }
    }

    public static JSArray loadLocationsAsJSArray(Context context) {
        File file = new File(getSafeFilesDir(context), FILE_NAME);
        JSArray locations = new JSArray();

        if (!file.exists()) return locations;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    try {
                        locations.put(new JSObject(line));
                    } catch (JSONException e) {
                        Log.e(TAG, "malformed JSON line ignored", e);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Erreor reading file", e);
        }

        return locations;
    }

    public static void clearLocations(Context context) {
        File file = new File(getSafeFilesDir(context), FILE_NAME);
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "🗑️ File deleted : " + deleted);
        }
    }

    private static final SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.FRANCE);

    private static String getFormattedDate() {
        return sdf.format(new Date());
    }

    public static JSObject locationToJSObject(android.location.Location location) {
        JSObject obj = new JSObject();
        obj.put("lat", location.getLatitude());
        obj.put("lng", location.getLongitude());
        obj.put("speed", location.getSpeed());
        obj.put("timestamp", location.getTime());
        return obj;
    }

    /**
     * Secure Purge of old data
     */
    public static void purgeLocationsBefore(Context context, long timestampLimit) 
    {
        File file = new File(getSafeFilesDir(context), FILE_NAME);
        if (!file.exists()) return;

        File tempFile = new File(getSafeFilesDir(context), "temp_purge.json");
        boolean hasDataLeft = false;

        try (BufferedReader br = new BufferedReader(new FileReader(file));
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                try {
                    JSObject obj = new JSObject(line);
                    long pointTimestamp = obj.getLong("timestamp");

                    if (pointTimestamp >= timestampLimit) {
                        fos.write((line + "\n").getBytes());
                        hasDataLeft = true;
                    }
                } catch (Exception e) {
                    // Ignorer les lignes corrompues
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ Error during purge", e);
        }

        // Remplace file
        if (tempFile.exists()) {
            if (hasDataLeft) {
                if (file.delete()) {
                    tempFile.renameTo(file);
                }
            } else {
                // nothing remain suppress all
                file.delete();
                tempFile.delete();
            }
            Log.d(TAG, "🧹 Purge ended.");
        }
    }


    /**
     * Secure partial purge
     */
    public static void purgeLocationsBetween(Context context, long from, long to) {
        File safeDir = getSafeFilesDir(context); 
        File file = new File(safeDir, FILE_NAME);
        if (!file.exists()) return;

        File tempFile = new File(safeDir, "temp_purge_between.json");
        boolean hasDataLeft = false;

        try (BufferedReader br = new BufferedReader(new FileReader(file));
             FileOutputStream fos = new FileOutputStream(tempFile)) {
            
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                try {
                    JSObject obj = new JSObject(line);
                    long pointTimestamp = obj.getLong("timestamp");

                    // keep line not between from and to
                    if (pointTimestamp < from || pointTimestamp > to) {
                        fos.write((line + "\n").getBytes());
                        hasDataLeft = true;
                    }
                } catch (Exception e) {
                    // securitycorrupted line not kept
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "❌ Error during purge Between", e);
        }

        // Remplacement du fichier original
        if (file.delete()) {
            if (hasDataLeft) {
                tempFile.renameTo(file);
            } else {
                tempFile.delete();
            }
            Log.d(TAG, "🧹 Purge between " + from + " and " + to + " ended.");
        }
    }


    /**
     * Analyse le fichier, segmente les trajets terminés (EXIT) et les envoie.
     */
 
    public static void processAndUploadAutomotiveTrips(Context context, String serverUrl, String token) {
        synchronized (ActivityRecognitionPlugin.fileLock) {
            if (syncInProgress) return;
            syncInProgress = true;

            try {
                JSArray allEntries = loadLocationsAsJSArray(context);
                if (allEntries.length() == 0) {
                    syncInProgress = false;
                    return;
                }

                // 1. Tri des données par timestamp
                List<JSONObject> entryList = new ArrayList<>();
                for (int i = 0; i < allEntries.length(); i++) {
                    entryList.add(allEntries.getJSONObject(i));
                }
                Collections.sort(entryList, (a, b) -> Long.compare(a.optLong("timestamp"), b.optLong("timestamp")));

                // Réassignation pour le traitement
                allEntries = new JSArray();
                for (JSONObject obj : entryList) {
                    allEntries.put(obj);
                }

                // 2. Segmentation des trajets
                List<JSArray> finishedTrips = new ArrayList<>();
                JSArray currentSegment = new JSArray();
                boolean isInsideAutomotive = false;
                int lastProcessedIndex = -1;

                long GRACE_TIMER_MS = 3 * 60 * 1000; // 3 minutes
                double SPEED_THRESHOLD = 1.7;        // ~6-8 km/h

                for (int i = 0; i < allEntries.length(); i++) {
                    JSONObject entry = allEntries.getJSONObject(i);
                    String type = entry.optString("type", "");
                    String activity = entry.optString("activity", "");
                    String transition = entry.optString("transition", "");
                    long timestamp = entry.optLong("timestamp", 0);
                    double speed = entry.optDouble("speed", 0);

                    if ("activity".equals(type) && "automotive".equals(activity) && "ENTER".equals(transition)) {
                        isInsideAutomotive = true;
                    }

                    if (isInsideAutomotive) {
                        currentSegment.put(entry);
                    }

                    boolean shouldCloseTrip = false;
                    if ("activity".equals(type) && "automotive".equals(activity) && "EXIT".equals(transition)) {
                        shouldCloseTrip = true;
                    } else if (isInsideAutomotive && i < allEntries.length() - 1) {
                        JSONObject nextEntry = allEntries.getJSONObject(i + 1);
                        long gap = nextEntry.optLong("timestamp", 0) - timestamp;
                        if (gap > GRACE_TIMER_MS && speed < SPEED_THRESHOLD) {
                            shouldCloseTrip = true;
                        }
                    }

                    if (shouldCloseTrip && currentSegment.length() > 0) {
                        JSArray cleaned = trimPedestrianStart(currentSegment, SPEED_THRESHOLD);
                        cleaned = trimPedestrianEnd(cleaned, SPEED_THRESHOLD);

                        if (isTripSignificant(cleaned)) {
                            finishedTrips.add(cleaned);
                        }
                        currentSegment = new JSArray();
                        isInsideAutomotive = false;
                        lastProcessedIndex = i; // On marque jusqu'où on a "consommé"
                    }
                }

                // 3. Tentative d'Upload et gestion des échecs
                List<JSArray> failedTrips = new ArrayList<>();
                for (JSArray trip : finishedTrips) {
                    boolean success = uploadSingleTrip(serverUrl, token, trip);
                    if (!success) {
                        Log.w(TAG, "⚠️ Échec upload d'un trajet, sera conservé pour plus tard.");
                        failedTrips.add(trip);
                    }
                }

                // 4. Reconstruction du fichier avec les données à conserver
                JSArray dataToKeep = new JSArray();

                // A. On remet les trajets qui ont échoué
                for (JSArray failed : failedTrips) {
                    for (int j = 0; j < failed.length(); j++) {
                        dataToKeep.put(failed.get(j));
                    }
                }

                // B. On ajoute tout ce qui n'a pas été traité (trajet en cours ou data post-segmentation)
                for (int k = lastProcessedIndex + 1; k < allEntries.length(); k++) {
                    dataToKeep.put(allEntries.get(k));
                }

                // Réécriture physique du fichier
                rewriteFileWithRemainingData(context, dataToKeep);

            } catch (Exception e) {
                Log.e(TAG, "❌ Erreur durant le traitement : ", e);
            } finally {
                syncInProgress = false;
            }
        }
    }

    private static JSArray trimPedestrianStart(JSArray trip, double speedThreshold) throws JSONException {
        int firstMotorizedIndex = -1;

        // On cherche le premier point où l'on roule vraiment
        for (int i = 0; i < trip.length(); i++) {
            JSONObject step = trip.getJSONObject(i);
            String activity = step.optString("activity", "");
            double speed = step.optDouble("speed", 0.0);
            String type = step.optString("type", "");

            boolean isAutomotive = "automotive".equals(activity);
            boolean isFast = "location".equals(type) && speed > speedThreshold;

            if (isAutomotive || isFast) {
                firstMotorizedIndex = i;
                break;
            }
        }

        if (firstMotorizedIndex != -1) {
            JSArray cleaned = new JSArray();
            // start from motorized point (goback n_rec if start context needed)
            int n_rec = 0; // set n to zero
            int startIndex = Math.max(0, firstMotorizedIndex - n_rec);
            for (int i = startIndex; i < trip.length(); i++) {
                cleaned.put(trip.get(i));
            }
            return cleaned;
        }
        return new JSArray(); // Si rien de moteur n'est trouvé, on vide
    }

    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371e3; // Rayon de la Terre en mètres
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) +
                   Math.cos(phi1) * Math.cos(phi2) *
                   Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }


    private static boolean isTripSignificant(JSArray trip) throws JSONException {
        if (trip.length() < 5) return false;

        double totalDistance = 0;
        JSONObject lastLoc = null;

        for (int i = 0; i < trip.length(); i++) {
            JSONObject step = trip.getJSONObject(i);
            if ("location".equals(step.optString("type"))) {
                if (lastLoc != null) {
                    totalDistance += calculateDistance(
                        lastLoc.optDouble("lat"), lastLoc.optDouble("lng"),
                        step.optDouble("lat"), step.optDouble("lng")
                    );
                }
                lastLoc = step;
            }
        }

        long startTime = trip.getJSONObject(0).optLong("timestamp", 0);
        long endTime = trip.getJSONObject(trip.length() - 1).optLong("timestamp", 0);
        long durationSeconds = (endTime - startTime) / 1000;

        double averageSpeedMS = durationSeconds > 0 ? (totalDistance / durationSeconds) : 0;

        Log.d(TAG, "📊 Analyse trajet: " + (int)totalDistance + "m, " + durationSeconds + "s, Moy: " + String.format("%.2f", averageSpeedMS) + "m/s");

        // SEUILS : > 500m ET > 60s ET vitesse moyenne > 7km/h (2.0 m/s)
        boolean significant = (totalDistance > 500 && durationSeconds > 60 && averageSpeedMS > 2.0);
        
        if (!significant) Log.d(TAG, "🗑️ Trajet ignoré (trop court ou trop lent)");
        
        return significant;
    }

    /**
     * Parcourt le segment et supprime tous les points au début 
     * tant qu'on n'a pas détecté de mouvement motorisé.
     */
    private static JSArray trimPedestrianEnd(JSArray trip, double speedThreshold) throws JSONException {
        int lastMotorizedIndex = -1;

        // On remonte depuis la fin
        for (int i = trip.length() - 1; i >= 0; i--) {
            JSONObject step = trip.getJSONObject(i);
            String activity = step.optString("activity", "");
            double speed = step.optDouble("speed", 0.0);
            String type = step.optString("type", "");

            if ("automotive".equals(activity) || ("location".equals(type) && speed > speedThreshold)) {
                lastMotorizedIndex = i;
                break;
            }
        }

        if (lastMotorizedIndex != -1) {
            JSArray cleaned = new JSArray();
            int endIndex = Math.min(trip.length(), lastMotorizedIndex + 2);
            for (int i = 0; i < endIndex; i++) {
                cleaned.put(trip.get(i));
            }
            return cleaned;
        }
        return trip;
    }

    /**
     * Envoi HTTP POST d'un trajet
     */
   private static boolean uploadSingleTrip(String urlStr, String token, JSArray tripData) {
        try {
            // 1. Construction de la structure { "measures": [...] } avec JSONObject natif
            JSONObject payload = new JSONObject();
            JSONArray measuresArray = new JSONArray();

            for (int i = 0; i < tripData.length(); i++) {
                // Lecture depuis le JSArray de Capacitor
                JSObject entry = JSObject.fromJSONObject(tripData.getJSONObject(i));
                
                if ("location".equalsIgnoreCase(entry.optString("type"))) {
                    JSONObject point = new JSONObject();
                    point.put("lat", entry.optDouble("lat"));
                    point.put("lng", entry.optDouble("lng"));
                    point.put("speed", entry.optDouble("speed"));
                    point.put("timestamp", entry.optLong("timestamp"));
                    measuresArray.put(point);
                }
            }

            if (measuresArray.length() == 0) {
                Log.d(TAG, "ℹ️ No GPS measures found in trip segment, skipping upload.");
                return true; 
            }

            payload.put("measures", measuresArray);
            String jsonOutput = payload.toString();

            // Log de débogage avec limite de taille
            if (jsonOutput.length() > 500) {
                String preview = jsonOutput.substring(0, 250) + " ... [TRUNCATED] ... " + jsonOutput.substring(jsonOutput.length() - 100);
                Log.i(TAG, "📤 JSON Payload (Large - " + jsonOutput.length() + " chars): " + preview);
            } else {
                Log.i(TAG, "📤 JSON Payload: " + jsonOutput);
            }

            // 2. Configuration de la connexion
            URL url = new URL(urlStr);
            Log.i(TAG, "ℹ️ url: " + urlStr);
            
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            // Ajout explicite du charset dans le header
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);

            // 3. Envoi du JSON
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonOutput.getBytes("utf-8");
                os.write(input, 0, input.length);
                os.flush(); // On force l'envoi
            }

            // 4. Analyse de la réponse
            int code = conn.getResponseCode();
            Log.d(TAG, "📡 Upload Response Code: " + code);

            if (code >= 400) {
                InputStream es = conn.getErrorStream();
                if (es != null) {
                    try (Scanner s = new Scanner(es).useDelimiter("\\A")) {
                        String errorResponseBody = s.hasNext() ? s.next() : "";
                        Log.e(TAG, "❌ Server Error Message: " + errorResponseBody);
                    }
                }
            }
            
            return (code >= 200 && code <= 299);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Upload failed", e);
            return false;
        }
    }

    /**
     * rewrite file keeping remaining data
     */
    private static void rewriteFileWithRemainingData(Context context, JSArray dataToKeep) {
        File file = new File(getSafeFilesDir(context), FILE_NAME);
        // Utilisation du mode écrasement (false)
        try (FileOutputStream fos = new FileOutputStream(file, false);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            
            for (int i = 0; i < dataToKeep.length(); i++) {
                String line = dataToKeep.getJSONObject(i).toString() + "\n";
                bos.write(line.getBytes(StandardCharsets.UTF_8));
            }
            bos.flush();
            Log.d(TAG, "💾 File updated: " + dataToKeep.length() + " entries kept.");
        } catch (Exception e) {
            Log.e(TAG, "❌ Critical rewrite error: " + e.getMessage());
        }
    }

    //
    // manage schedule to get weather every 5 min
    //
    public static void scheduleNextWeatherUpdate(Context context) {

        Log.i(TAG, "ℹ️ launch weather update: ");

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, WeatherReceiver.class);
        
        PendingIntent pi = PendingIntent.getBroadcast(context, 1002, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long fiveMinutes = 5 * 60 * 1000;
        long triggerAt = System.currentTimeMillis() + fiveMinutes;

        if (am != null) {
            // setWindow est parfait ici : 5min avec 30s de marge
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 30000, pi);
        }
    }

    //
    // cancel 5 min schedule for weather update
    //
    public static void cancelWeatherUpdates(Context context) {
    Intent intent = new Intent(context, WeatherReceiver.class);
    
    PendingIntent pi = PendingIntent.getBroadcast(
        context, 
        1002, 
        intent, 
        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
    );

    if (pi != null) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pi);
            pi.cancel(); 
            Log.d("JsonStorageHelper", "🛑 Météo : Alarms ended");
        }
    }
}

    //
    // save last json weather received
    // 
    public static void setLastWeather(JSObject weatherData) {
        JSObject weatherBrief = new JSObject();
        // Valeurs par défaut
        String condition = "unknown";
        double temperature = -999.0;

        try {
            if (weatherData != null && weatherData.has("weather") && weatherData.has("main")) {
                int weatherCondition = weatherData.getJSONArray("weather").getJSONObject(0).getInt("id");
                temperature = weatherData.getJSONObject("main").getDouble("temp");

                if (weatherCondition >= 200 && weatherCondition < 300) condition = "stormy";
                else if (weatherCondition >= 300 && weatherCondition < 600) condition = "rainy";
                else if (weatherCondition >= 600 && weatherCondition < 700) condition = "snowy";
                else if (weatherCondition >= 700 && weatherCondition < 800) condition = "foggy";
                else if (weatherCondition >= 800 && weatherCondition < 900) {
                    condition = (weatherCondition == 800) ? "sunny" : "cloudy";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather", e);
        }

        weatherBrief.put("type", condition);
        weatherBrief.put("temp", temperature);
        lastWeather = weatherBrief;
    }

    //
    // get last json weather received
    // 
    public static JSObject getLastWeather() {
        return lastWeather;
    }
    
}