package fr.lelab.activity;

import android.content.Context;
import com.getcapacitor.JSArray;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat; 
import java.util.Date;          
import java.util.Locale;

public class JsonStorageHelper {
    private static final String FILE_NAME = "stored_locations.json";

    // Save GPS position
    public static synchronized void saveLocation(Context context, double lat, double lng) {
        try {
            JSONArray dataArray = loadData(context);
            JSONObject entry = new JSONObject();
            entry.put("type", "location");
            entry.put("lat", lat);
            entry.put("lng", lng);
            entry.put("timestamp", System.currentTimeMillis() / 1000.0);

            dataArray.put(entry);
            saveToFile(context, dataArray);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized void saveActivity(Context context, String activity, String transition) {
        try {
            JSONArray dataArray = loadData(context);
            JSONObject entry = new JSONObject();

            // Format to readable date
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String currentDate = sdf.format(new Date());

            entry.put("type", "activity");
            entry.put("activity", activity);     // ex: "automotive"
            entry.put("transition", transition); // ex: "ENTER"
            entry.put("date", currentDate); // more readable than timestamp
            entry.put("timestamp", System.currentTimeMillis() / 1000.0);

            dataArray.put(entry);
            saveToFile(context, dataArray);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Internal uitlity ---

    private static void saveToFile(Context context, JSONArray array) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static JSONArray loadData(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return new JSONArray();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new JSONArray(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // --- PLUGIN Method---

    public static JSArray loadLocationsAsJSArray(Context context) {
        try {
            return JSArray.from(loadData(context));
        } catch (Exception e) {
            return new JSArray();
        }
    }

    public static void clearLocations(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) file.delete();
    }
}