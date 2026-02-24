package fr.lelab.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.ListenableWorker.Result;

public class TripUploadWorker extends Worker {

    private static final String TAG = "TripUploadWorker";

    public TripUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Log.d(TAG, "🚀 WorkManager START");

        // Test de lecture des SharedPreferences (Vérifie bien le nom "TripPrefs" ou "CapacitorStorage")
        SharedPreferences prefs = context.getSharedPreferences("TripPrefs", Context.MODE_PRIVATE);
        String url = prefs.getString("server_url", null);
        String token = prefs.getString("jwt_token", null);

        Log.d(TAG, "🔍 Debug Config - URL: " + (url != null ? url : "MISSING") + " | Token: " + (token != null ? "FOUND" : "MISSING"));

        if (url == null || token == null) {
            return Result.failure();
        }

        try {
            JsonStorageHelper.processAndUploadAutomotiveTrips(context, url, token);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "❌ Exception durant process: " + e.getMessage());
            e.printStackTrace();
            return Result.retry();
        }
    }
}