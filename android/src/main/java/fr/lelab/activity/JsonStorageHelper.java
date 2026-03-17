package fr.lelab.activity;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import org.json.JSONException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import org.json.JSONObject;
import org.json.JSONArray;
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
     * Sauvegarde un changement d'activité
     */
    public static void saveActivity(Context context, String activityName, String transitionName) {
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

    private static String getFormattedDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
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
                // nothing remain supprress all
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
            Log.d(TAG, "🧹 Purge betwwen " + from + " and " + to + " ended.");
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

                List<JSArray> completedTrips = new ArrayList<>();
                JSArray currentSegment = new JSArray();
                int lastProcessedIndex = -1;

                // 1. Segmentation avec "Look-ahead"
                // On ne valide un EXIT que s'il y a des points après (stabilisation)
                for (int i = 0; i < allEntries.length(); i++) {
                    JSObject entry = JSObject.fromJSONObject(allEntries.getJSONObject(i));
                    currentSegment.put(entry);

                    boolean isExit = "activity".equals(entry.optString("type")) && 
                                     "EXIT".equals(entry.optString("transition")) && 
                                     "automotive".equals(entry.optString("activity"));

                    if (isExit) {
                        // On vérifie s'il y a au moins 3 points après l'EXIT pour confirmer la fin
                        if (i < allEntries.length() - 3) {
                            JSArray cleaned = trimPedestrianStart(currentSegment, 1.4);
                            if (isTripSignificant(cleaned)) {
                                completedTrips.add(cleaned);
                            }
                            currentSegment = new JSArray(); // Reset pour le prochain trajet
                            lastProcessedIndex = i; // On marque jusqu'où on a traité
                        } else {
                            // Pas assez de recul, on s'arrête là pour ce cycle
                            break; 
                        }
                    }
                }

                // 2. Upload des trajets complétés
                List<JSArray> failedUploads = new ArrayList<>();
                for (JSArray trip : completedTrips) {
                    if (!uploadSingleTrip(serverUrl, token, trip)) {
                        failedUploads.add(trip); // On garde en mémoire les échecs réseau
                    }
                }

                // 3. Reconstruction des données restantes à sauvegarder
                // On veut garder : les échecs d'upload + tout ce qui n'a pas été segmenté
                JSArray dataToKeep = new JSArray();

                // Ajouter les trajets qui ont échoué à l'upload
                for (JSArray failed : failedUploads) {
                    for (int j = 0; j < failed.length(); j++) {
                        dataToKeep.put(failed.get(j));
                    }
                }

                // Ajouter tout ce qui se trouve après le dernier EXIT validé
                for (int k = lastProcessedIndex + 1; k < allEntries.length(); k++) {
                    dataToKeep.put(allEntries.get(k));
                }

                // 4. RÉÉCRITURE UNIQUE ET FINALE
                // On passe une liste vide en 3ème paramètre car dataToKeep contient déjà tout
                rewriteFileWithRemainingData(context, dataToKeep, new ArrayList<>());

            } catch (Exception e) {
                Log.e(TAG, "❌ Erreur critique de traitement", e);
            } finally {
                syncInProgress = false;
            }
        }
    }

    // exclude small trips
    private static boolean isTripSignificant(JSArray trip) {
        if (trip.length() < 5) return false;

        double totalDistance = 0;
        long durationSeconds = 0;

        try {
            // 1. Calcul de la distance cumulée
            for (int i = 0; i < trip.length() - 1; i++) {
                JSObject p1 = JSObject.fromJSONObject(trip.getJSONObject(i));
                JSObject p2 = JSObject.fromJSONObject(trip.getJSONObject(i + 1));

                // On ne calcule la distance que si ce sont des points de type location
                if ("location".equals(p1.optString("type")) && "location".equals(p2.optString("type"))) {
                    totalDistance += calculateDistance(
                        p1.getDouble("lat"), p1.getDouble("lng"),
                        p2.getDouble("lat"), p2.getDouble("lng")
                    );
                }
            }

            // 2. Calcul de la durée totale
            JSObject first = JSObject.fromJSONObject(trip.getJSONObject(0));
            JSObject last = JSObject.fromJSONObject(trip.getJSONObject(trip.length() - 1));
            durationSeconds = (last.getLong("timestamp") - first.getLong("timestamp")) / 1000;

            Log.d("TripValidation", "Route réelle: " + totalDistance + "m en " + durationSeconds + "s");

            // > 500m and > 60s
            return (totalDistance > 350 && durationSeconds > 45);

        } catch (JSONException e) {
            return false;
        }
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

    /**
     * Parcourt le segment et supprime tous les points au début 
     * tant qu'on n'a pas détecté de mouvement motorisé.
     */
    private static JSArray trimPedestrianStart(JSArray trip, double speedThreshold) throws JSONException {
        JSArray cleaned = new JSArray();
        int firstMotorizedIndex = -1;

        // 1. Trouver l'index du premier déclencheur "voiture"
        for (int i = 0; i < trip.length(); i++) {
            JSObject step = JSObject.fromJSONObject(trip.getJSONObject(i));
            
            boolean isAutomotiveType = "automotive".equals(step.optString("activity"));
            boolean isFast = "location".equals(step.optString("type")) && step.optDouble("speed", 0) > speedThreshold;

            if (isAutomotiveType || isFast) {
                firstMotorizedIndex = i;
                break;
            }
        }

        // 2. Si on a trouvé un début motorisé, on garde tout à partir de là
        if (firstMotorizedIndex != -1) {
            // Optionnal : we can go back from some point 1, 2 or 3 to get points just before start 
            int pointBefore = 3; // here 3 points
            int startIndex = Math.max(0, firstMotorizedIndex - pointBefore); 
            for (int i = startIndex; i < trip.length(); i++) {
                cleaned.put(trip.get(i));
            }
        }

        return cleaned;
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
    private static void rewriteFileWithRemainingData(Context context, JSArray currentTrip, List<JSArray> remainingTrips) {
        File file = new File(getSafeFilesDir(context), FILE_NAME);
        
        // On utilise un BufferedOutputStream pour plus d'efficacité
        try (FileOutputStream fos = new FileOutputStream(file, false);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            
            // 1. Réécrire les trajets segmentés qui n'ont pas encore été envoyés (échecs d'upload)
            if (remainingTrips != null) {
                for (JSArray trip : remainingTrips) {
                    for (int i = 0; i < trip.length(); i++) {
                        String line = trip.getJSONObject(i).toString() + "\n";
                        bos.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
            
            // 2. Réécrire les points du trajet actuel (ceux après le dernier EXIT validé)
            if (currentTrip != null) {
                for (int i = 0; i < currentTrip.length(); i++) {
                    String line = currentTrip.getJSONObject(i).toString() + "\n";
                    bos.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            
            bos.flush();
            Log.d(TAG, "💾 Fichier réécrit avec succès. Données conservées pour le prochain cycle.");
        } catch (Exception e) {
            Log.e(TAG, "❌ Erreur critique lors de la réécriture du fichier: " + e.getMessage());
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
        // 1. Créer un NOUVEL objet immédiatement (pour éviter le NullPointerException)
        JSObject weatherBrief = new JSObject();
        
        try {
            if (weatherData != null && weatherData.has("weather")) {
                JSONArray weatherArray = weatherData.getJSONArray("weather");
                JSONObject firstWeather = weatherArray.getJSONObject(0);
                int weatherCondition = firstWeather.getInt("id");
                
                JSONObject mainObj = weatherData.getJSONObject("main");
                double temperature = mainObj.getDouble("temp");

                String condition = "sunny"; // Valeur par défaut

                if ((weatherCondition >= 200 && weatherCondition < 300) || (weatherCondition >= 900 && weatherCondition < 903))
                    condition = "stormy";
                else if ((weatherCondition >= 300 && weatherCondition < 400) || (weatherCondition >= 500 && weatherCondition < 600))
                    condition = "rainy";
                else if (weatherCondition >= 600 && weatherCondition < 700)
                    condition = "snowy";
                else if (weatherCondition >= 700 && weatherCondition < 800)
                    condition = "foggy";
                else if (weatherCondition >= 800 && weatherCondition < 900)
                    condition = "sunny";

                weatherBrief.put("type", condition);
                weatherBrief.put("temp", temperature);
            } else {
                weatherBrief.put("type", "unknown");
                weatherBrief.put("temp", -999);
            }
        } catch (Exception e) {
            e.printStackTrace();
            weatherBrief.put("type", "unknown");
            weatherBrief.put("temp", -999);
        }

        // 2. Assigner l'objet local rempli à la variable statique de la classe
        lastWeather = weatherBrief; 
        Log.d("JsonStorageHelper", "☀️ Météo updated in cache : " + lastWeather.toString());
    }

    //
    // get last json weather received
    // 
    public static JSObject getLastWeather() {
        return lastWeather;
    }
    
}