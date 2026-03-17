package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.location.Location;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

import com.getcapacitor.JSObject;


public class WeatherReceiver extends BroadcastReceiver {

    private static final String TAG = "WeatherReceiver";


    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("WeatherTask", "🌦️ start update weather");
        
        // 1. execute fect in separate thread)
        fetchWeatherData(context, false);

        // 2. new planned in 5 min
        JsonStorageHelper.scheduleNextWeatherUpdate(context);
    }

    public static void fetchWeatherData(Context context, boolean force) {
    FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);

        try {
            fusedLocationClient.getLastLocation()
                .addOnSuccessListener(new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location location) {
                        if (location != null) {
                            // --- LOGIQUE DE DISTANCE ---
                            SharedPreferences prefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE);
                            
                            // Récupération de la dernière position connue du fetch météo
                            float lastLat = prefs.getFloat("last_weather_lat", 0f);
                            float lastLon = prefs.getFloat("last_weather_lon", 0f);
                            
                            Location lastLocation = new Location("");
                            lastLocation.setLatitude(lastLat);
                            lastLocation.setLongitude(lastLon);

                            // Calcul de la distance en mètres
                            float distance = location.distanceTo(lastLocation);
                            
                            if (force || lastLat == 0 || distance >= 2000 || true) {
                                Log.d(TAG, force ? "Weather fetch FORCED" : "Weather fetch: move of " + Math.round(distance) + "m");
                                new Thread(() -> {
                                    try{
                                        makeWeatherApiCall(context, location.getLatitude(), location.getLongitude());
                                    }catch (Exception e) {
                                        Log.e("WeatherError", "Crash dans le thread météo", e);
                                    }
                                    
                                }).start();
                            } else {
                                Log.d(TAG, "Skipping weather fetch: only " + Math.round(distance) + "m moved.");
                            }
                            
                        } else {
                            Log.w(TAG, "❓ Position inconnue pour la météo");
                        }
                    }
                });
        } catch (SecurityException e) {
            Log.e(TAG, "🚫 Permissions manquantes", e);
        }
    }

    public static void makeWeatherApiCall(Context context, double lat, double lon){

        SharedPreferences prefs = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE);
        String apiKey = prefs.getString("weather_api_key", null);
        String baseUrl = prefs.getString("weather_base_url", null);

        if (apiKey == null || baseUrl == null) {
            Log.e(TAG, "❌ Weather config missing in SharedPreferences");
            return;
        }
        // setup URL mode GET (standard for this API)
        String urlStr = baseUrl + "?lat=" + lat + "&lon=" + lon + "&units=metric&appid=" + apiKey;

           try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET"); 
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);

            int code = conn.getResponseCode();
            Log.d(TAG, "📡 Weather Response Code: " + code);

            if (code >= 200 && code <= 299) {
                // read response
                try (InputStream is = conn.getInputStream();
                     Scanner s = new Scanner(is).useDelimiter("\\A")) {
                    
                    String responseBody = s.hasNext() ? s.next() : "";
                    
                    // Conversion de la String en JSObject pour Capacitor
                    com.getcapacitor.JSObject weatherJson = new com.getcapacitor.JSObject(responseBody);
                    
                    // Mise à jour du cache dans le Helper
                    JsonStorageHelper.setLastWeather(weatherJson);
                    Log.d(TAG, "✅ success update weather condition");

                    SharedPreferences.Editor editor = context.getSharedPreferences("tracking_prefs", Context.MODE_PRIVATE).edit();
                    editor.putFloat("last_weather_lat", (float) lat);
                    editor.putFloat("last_weather_lon", (float) lon);
                    editor.apply();

                    Log.d(TAG, "✅ save weather and position");             
                }
            } else {
                // Log d'erreur détaillé si code != 200
                Log.e(TAG, "❌ Server returned code: " + code);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ get weather failed", e);
        }
    }

}