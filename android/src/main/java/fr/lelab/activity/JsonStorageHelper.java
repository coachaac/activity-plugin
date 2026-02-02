package fr.lelab.activity;

import android.content.Context;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import org.json.JSONException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class JsonStorageHelper {

    private static final String FILE_NAME = "stored_locations.json";
    private static final String TAG = "JsonStorageHelper";

    /**
     * Sauvegarde une position en mode APPEND
     */
    public static void saveLocation(Context context, double lat, double lng, float speed) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        
        JSObject location = new JSObject();
        location.put("lat", lat);
        location.put("lng", lng);
        location.put("speed", speed);
        location.put("date", getFormattedDate());
        // MODIF : On garde les millisecondes (suppression du / 1000)
        location.put("timestamp", System.currentTimeMillis());

        String entry = location.toString() + "\n";

        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(entry.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "❌ Erreur lors de l'écriture Append position", e);
        }
    }

    /**
     * AJOUT : Sauvegarde un changement d'activité pour ActivityTransitionReceiver
     */
    public static void saveActivity(Context context, String activityName, String transitionName) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        
        JSObject activity = new JSObject();
        activity.put("type", "activity");
        activity.put("activity", activityName);
        activity.put("transition", transitionName);
        activity.put("date", getFormattedDate());
        activity.put("timestamp", System.currentTimeMillis());
        
        String entry = activity.toString() + "\n";

        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(entry.getBytes());
            Log.d(TAG, "🏃 Activité enregistrée : " + activityName);
        } catch (IOException e) {
            Log.e(TAG, "❌ Erreur lors de l'écriture Append activité", e);
        }
    }

    public static JSArray loadLocationsAsJSArray(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        JSArray locations = new JSArray();

        if (!file.exists()) return locations;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    try {
                        locations.put(new JSObject(line));
                    } catch (JSONException e) {
                        Log.e(TAG, "Ligne JSON malformée ignorée", e);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Erreur lors de la lecture du fichier", e);
        }

        return locations;
    }

    public static void clearLocations(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "🗑️ Fichier supprimé : " + deleted);
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
        // MODIF : On garde les millisecondes (suppression du / 1000)
        obj.put("timestamp", location.getTime());
        return obj;
    }

    public static void purgeLocationsBefore(Context context, long timestampLimit) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return;

        File tempFile = new File(context.getFilesDir(), "temp_purge.json");

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
                    }
                } catch (Exception e) {
                    // Ligne corrompue ignorée
                }
            }
        } catch (IOException e) {
            Log.e("SmartPilot", "❌ Erreur lors de la purge par timestamp", e);
        }

        if (tempFile.exists()) {
            if (file.delete()) {
                tempFile.renameTo(file);
            }
        }
    }
}