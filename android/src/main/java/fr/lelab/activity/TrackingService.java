package fr.lelab.activity;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log; 

import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.*;

public class TrackingService extends Service {
    private static final String CHANNEL_ID = "SmartPilot_Channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START_TRACKING = "fr.lelab.activity.START_TRACKING";

    private PendingIntent locationPendingIntent;
    private FusedLocationProviderClient fusedLocationClient;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    private Context getSafeContext() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? createDeviceProtectedStorageContext() 
            : this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Utilisation du stockage protégé pour vérifier l'état au reboot
        SharedPreferences prefs = getSafeContext().getSharedPreferences("CapacitorStorage", MODE_PRIVATE);
        boolean wasDriving = prefs.getBoolean("driving_state", false);

        // System re-launch service (intent null) but state was not  "automotive"
        if (intent == null && !wasDriving) {
            Log.d("TrackingService", "⚠️ Relaunch system outside automotive, Stop.");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d("TrackingService", "⚡ Service strated or restarted: automotive: " + wasDriving);
        
        createNotificationChannel();
        Notification notification = createNotification();

        // Android 14+ need type FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Do not relaunch update if already launched
        if (locationPendingIntent == null) {
            startLocationUpdates();
        }

        return START_STICKY; 
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Coach AAC")
                .setContentText("Trajet en cours d'enregistrement...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void startLocationUpdates() {
        // optimised for automotive use case
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setIntervalMillis(1000) // best effor 1 point by second
                .setMinUpdateIntervalMillis(1000)
                .setMinUpdateDistanceMeters(5) // 5 meters more precision in Town
                .setWaitForAccurateLocation(true)
                .build();

        Intent intent = new Intent(this, LocationReceiver.class);
        
        // FLAG_MUTABLE mandatory for th essystem to inject position
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        }
        
        locationPendingIntent = PendingIntent.getBroadcast(this, 1, intent, pendingFlags);

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationPendingIntent);
            Log.d("TrackingService", "✅ GPS Updates requested via PendingIntent");
        } catch (SecurityException e) {
            Log.e("TrackingService", "❌ Permission denied for GPS : " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        Log.d("TrackingService", "🛑 Stop service");
        
        if (locationPendingIntent != null) {
            fusedLocationClient.removeLocationUpdates(locationPendingIntent)
                .addOnCompleteListener(task -> Log.d("TrackingService", "GPS updates removed"));
        }
        
        stopForeground(true);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }

        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, 
                "Suivi de trajet", // improvement TBD should be given on start by App
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}