package fr.lelab.activity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
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

    private static boolean saveOnlyAutomotive = true;

    private static final String FILE_NAME = "stored_locations.json";
    private static final String TAG = "JsonStorageHelper";

    private static final String PREF_NAME = "ActivityPluginPrefs";
    private static final String KEY_DRIVING = "driving_state";


    public static boolean syncInProgress = false;

    private static JSObject lastWeather = null;
    
    private static boolean lastLockStatus = true;

    private static JSObject lastForegroundApp = null;

    private static DistractionEventEmitter activeEmitter = null;

    /**
     * get/update driving state in persistent storage
     */
    public static boolean getDrivingState(Context context) {
        Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? context.createDeviceProtectedStorageContext() : context;
        SharedPreferences prefs = safeContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DRIVING, false);
    }

    public static void setDrivingState(Context context, boolean isDriving) {
        Context safeContext = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? context.createDeviceProtectedStorageContext() : context;
        safeContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DRIVING, isDriving)
                .commit(); // commit() immediate writing
    }


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


    private static double sanitizeDouble(double value, double defaultValue) {
        return (Double.isNaN(value) || Double.isInfinite(value)) ? defaultValue : value;
    }

    /**
     * Save location APPEND mode
     */
    public static void saveLocation(Context context, double lat, double lng, float speed, long timestamp, JSObject weather, JSObject appUsed, Boolean isDistraction) {


        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            Log.e(TAG, "❌ GPS point ignored :  NaN ");
            return;
        }

        if (saveOnlyAutomotive) {
            if (!getDrivingState(context)) {
                return;
            }
        }

        if (Float.isNaN(speed))
            speed = 0;


       synchronized (ActivityRecognitionPlugin.fileLock) {
           File file = new File(getSafeFilesDir(context), FILE_NAME);
            
            JSObject location = new JSObject();
            location.put("type", "location"); 
            location.put("lat", lat);
            location.put("lng", lng);
            location.put("speed", speed);
            location.put("timestamp", timestamp);

            if (weather != null) {
                location.put("weather", weather);
            }

            if (appUsed != null) {
                location.put("appUsed", appUsed);
            }

            if (!getLockStatus()){
                location.put("phoneUnlock", true);
            }

            if (isDistraction != null){
                location.put("distractionDetect", isDistraction);
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
     * Save Activity change
     */
    public static void saveActivity(Context context, String activityName, String transitionName) {

        if (saveOnlyAutomotive)
        {
            if (!"automotive".equalsIgnoreCase(activityName)) {
                return;
            }
        }

        // is inside ENTER/EXIT automotive?
        if ("automotive".equalsIgnoreCase(activityName)) {
            if ("ENTER".equalsIgnoreCase(transitionName)) {
                setDrivingState(context, true);
                Log.d(TAG, "🚗 Driving Mode: ON (Persistent)");
            } else if ("EXIT".equalsIgnoreCase(transitionName)) {
                Log.d(TAG, "🛑 Driving Mode: OFF (Persistent), but keep location until end of grace timer");
            }
        }


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

                // Configuration
                long FIVE_MINUTES_MS = 5 * 60 * 1000;
                long MIN_DURATION_MS = 30 * 1000;

                List<JSArray> finishedTrips = new ArrayList<>();
                JSArray currentMeasures = new JSArray();
                boolean isTrackingAutomotive = false;

                for (int i = 0; i < allEntries.length(); i++) {
                    JSONObject current = allEntries.getJSONObject(i);
                    String type = current.optString("type");
                    String activity = current.optString("activity");
                    String transition = current.optString("transition");
                    long timestamp = current.optLong("timestamp");
                    double speed = Double.isNaN(current.optDouble("speed")) ? 0.0 : current.optDouble("speed");

                    // 1. Manage activity state
                    if ("activity".equals(type) && "automotive".equals(activity)) {

                        // ENTER : start tracking automotive
                        if ("ENTER".equals(transition) && !isTrackingAutomotive) {
                            isTrackingAutomotive = true;
                            currentMeasures = new JSArray(); // Reset array pour nouveau trajet
                        }

                        // EXIT : "Bad Stop verification"
                        if ("EXIT".equals(transition) && isTrackingAutomotive) {
                            boolean hasReEntered = false;

                            for (int j = i + 1; j < allEntries.length(); j++) {
                                JSONObject next = allEntries.getJSONObject(j);
                                long nextTs = next.optLong("timestamp");

                                // Si on dépasse le délai de 5 min, on arrête de chercher
                                if ((nextTs - timestamp) > FIVE_MINUTES_MS) break;

                                if ("activity".equals(next.optString("type")) &&
                                    "automotive".equals(next.optString("activity")) &&
                                    "ENTER".equals(next.optString("transition"))) {
                                    hasReEntered = true;
                                    break;
                                }
                            }

                            if (!hasReEntered) {
                                // --- End trip validated ---
                                if (currentMeasures.length() > 0) {
                                    long firstTs = currentMeasures.getJSONObject(0).optLong("timestamp");
                                    long lastTs = currentMeasures.getJSONObject(currentMeasures.length() - 1).optLong("timestamp");
                                    long duration = lastTs - firstTs;

                                    if (duration >= MIN_DURATION_MS) {
                                        // clone to add to already finished trips
                                        finishedTrips.add(currentMeasures);
                                    }
                                }
                                // Reset complet
                                isTrackingAutomotive = false;
                                currentMeasures = new JSArray();
                            }
                        }
                    }

                    // 2. Add location point if in automotive mode 
                    if ("location".equals(type) && isTrackingAutomotive) {

                        double lat = current.optDouble("lat");
                        double lng = current.optDouble("lng");
                        timestamp = current.optLong("timestamp", 0);

                        if (!Double.isNaN(lat) && !Double.isNaN(lng) && timestamp > 0)
                        {

                            JSONObject measure = new JSONObject();
                            measure.put("lat", current.optDouble("lat"));
                            measure.put("lng", current.optDouble("lng"));
                            measure.put("speed", speed);
                            measure.put("timestamp", timestamp);
                            measure.put("type", "location");

                            // check if meteo exist and add
                            if (current.has("weather") && !current.isNull("weather")) {
                                JSONObject weatherData = current.optJSONObject("weather");
                                if (weatherData != null) {
                                    JSONObject weatherPoint = new JSONObject();
                                    weatherPoint.put("type", weatherData.optString("type"));
                                    weatherPoint.put("temp", weatherData.optDouble("temp"));
                                    
                                    measure.put("weather", weatherPoint);
                                }
                            }

                            // check if unlock exist and add
                            if (current.has("phoneUnlock")) {
                                measure.put("phoneUnlock", current.optBoolean("phoneUnlock"));
                            }

                            if (current.has("appUsed") && !current.isNull("appUsed")) {
                                JSONObject appUsedData = current.optJSONObject("appUsed");
                                if (appUsedData != null) {
                                    JSONObject appUsedPoint = new JSONObject();
                                    appUsedPoint.put("type", appUsedData.optString("name"));
                                    appUsedPoint.put("accuracy", 1);
                                    
                                    measure.put("appUsed", appUsedPoint);
                                }
                            }

                            if (current.has("distractionDetect")) {
                                measure.put("distractionDetect", current.optBoolean("distractionDetect"));
                            }
                            
                            currentMeasures.put(measure);
                        }
                    }
                }

                // 'finishedTrips' contain all trips ready to upload

                int tripNumber = 1;

                for (JSArray tripMeasures : finishedTrips) {
                    try {
                        if (tripMeasures.length() == 0) continue;

                        // 1. gets timestamps for purge
                        JSONObject firstPoint = tripMeasures.getJSONObject(0);
                        JSONObject lastPoint = tripMeasures.getJSONObject(tripMeasures.length() - 1);
                        
                        long tripStart = firstPoint.getLong("timestamp");
                        long tripEnd = lastPoint.getLong("timestamp");

                        // do not process trip less than 1km or less than 1 min

                        if (isTripSignificant(tripMeasures)) {

                            // 2. Try to upload
                            boolean success = uploadSingleTrip(serverUrl, token, tripMeasures);

                            if (success) {
                                Log.d(TAG, "✅ Trip sent Success" + tripNumber);

                                if (tripNumber == 1) {
                                    JsonStorageHelper.purgeLocationsBefore(context, tripEnd + 1);
                                } else {
                                    JsonStorageHelper.purgeLocationsBetween(context, tripStart - 1, tripEnd + 1);
                                }
                            } else {
                                Log.e(TAG, "❌ fail to upload trip starting at : " + tripStart + ". Remains in file.");
                            }
                        } else {
                            // trjat not significant : purge witout upload
                            Log.i(TAG, "⚠️ Trip ignored (too short)");
                            if (tripNumber == 1) {
                                JsonStorageHelper.purgeLocationsBefore(context, tripEnd + 1);
                            } else {
                                JsonStorageHelper.purgeLocationsBetween(context, tripStart - 1, tripEnd + 1);
                            }
                        }


                    } catch (Exception e) {
                        Log.e(TAG, "💥 Error during trip processing " + tripNumber + ": " + e.getMessage());
                    }
                    
                    tripNumber++;
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ Error during processing : ", e);
            } finally {
                syncInProgress = false;
            }
        }
    }

    private static JSArray trimPedestrianStart(JSArray trip, double speedThreshold) throws JSONException {
        int firstMotorizedIndex = -1;

        // get first real automotive point
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
        return new JSArray(); // if no automotive --> empty
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
        
        if (!significant) Log.d(TAG, "🗑️ Trip ignored (too small or not fast enough)");
        
        return significant;
    }

    /**
     * Suppress point if not detected automotive 
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
     *  HTTP POST for a trip
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

                    // check if meteo exist and add
                    if (entry.has("weather") && !entry.isNull("weather")) {
                        JSONObject weatherData = entry.optJSONObject("weather");
                        if (weatherData != null) {
                            JSONObject weatherPoint = new JSONObject();
                            weatherPoint.put("type", weatherData.optString("type"));
                            weatherPoint.put("temp", weatherData.optDouble("temp"));
                            
                            point.put("weather", weatherPoint);
                        }
                    }

                    // check if unlock exist and add
                    boolean isPhoneUnlocked = false;
                    if (entry.has("phoneUnlock")) {
                        isPhoneUnlocked = entry.optBoolean("phoneUnlock");
                        point.put("phoneUnlock", isPhoneUnlocked);
                    }

                    // get app info if exists
                    JSONObject appUsed = null;

                    if (entry.has("appUsed") && !entry.isNull("appUsed")) {
                        appUsed = entry.optJSONObject("appUsed");
                    }

                    // send app only if phone unlocked
                    if (isPhoneUnlocked) {
                        if (appUsed != null) {
                            JSONObject appUsedPoint = new JSONObject();
                            appUsedPoint.put("type", appUsed.optString("name"));
                            appUsedPoint.put("accuracy", appUsed.optDouble("accuracy"));
                            
                            point.put("appUsed", appUsedPoint);
                        }

                        boolean isDistraction = false;
                            if (entry.has("distractionDetect")) {
                                isDistraction = entry.optBoolean("distractionDetect");
                                point.put("distractionDetect", isDistraction);
                            }
                    } else {
                        Log.d(TAG, "📱 phone locked : injection of usedApp ignored.");
                    }

                    Log.d(TAG, "📍 Point ajouté à measuresArray : " + point.toString());

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
            conn.setReadTimeout(15000);

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

    // trip definitively ended, do not record any location
    setDrivingState(context, false);

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


    //
    // set lock status
    // 
    public static void setLockStatus(boolean status) {
        lastLockStatus = status;
    }

    //
    // get lock status
    // 
    public static boolean getLockStatus() {
        return lastLockStatus;
    }

    //
    // set Last forground App
    //
    public static void setLastForegroundApp(JSObject appData) {
        lastForegroundApp = appData;
    }


    //
    // get Last foreground App
    //
    public static JSObject getLastForegroundApp() {
        return lastForegroundApp;
    }

    /**
     * get distraction status
     */
    public static boolean getDistractionStatus() {
        return DistractionEventEmitter.getCurrentlyDistracted(); 
    }
    
}