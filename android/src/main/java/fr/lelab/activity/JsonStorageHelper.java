package fr.lelab.activity;

import android.content.Context;
import android.os.Build;
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
    public static void saveLocation(Context context, double lat, double lng, float speed) {
        File file = new File(getSafeFilesDir(context), FILE_NAME);
        
        JSObject location = new JSObject();
        location.put("type", "location"); 
        location.put("lat", lat);
        location.put("lng", lng);
        location.put("speed", speed);
        location.put("date", getFormattedDate());
        location.put("timestamp", System.currentTimeMillis());

        String entry = location.toString() + "\n";

        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(entry.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "❌ Error during Append position writing", e);
        }
    }

    /**
     * Sauvegarde un changement d'activité
     */
    public static void saveActivity(Context context, String activityName, String transitionName) {
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
}