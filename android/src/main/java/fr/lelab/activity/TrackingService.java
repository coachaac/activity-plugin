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

        // Si le système relance le service (intent null) mais que l'état n'était pas "conduite"
        if (intent == null && !wasDriving) {
            Log.d("TrackingService", "⚠️ Relance système hors conduite, arrêt.");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d("TrackingService", "⚡ Service démarré ou restauré. Conduite: " + wasDriving);
        
        createNotificationChannel();
        Notification notification = createNotification();

        // Android 14 impose de spécifier le type FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // On ne relance pas les updates si elles tournent déjà
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
        // Configuration optimisée pour la conduite
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .setMinUpdateDistanceMeters(5) // On descend à 5m pour plus de précision en ville
                .setWaitForAccurateLocation(true)
                .build();

        Intent intent = new Intent(this, LocationReceiver.class);
        
        // FLAG_MUTABLE est impératif pour que le système injecte la position
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        }
        
        locationPendingIntent = PendingIntent.getBroadcast(this, 1, intent, pendingFlags);

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationPendingIntent);
            Log.d("TrackingService", "✅ GPS Updates requested via PendingIntent");
        } catch (SecurityException e) {
            Log.e("TrackingService", "❌ Permission refusée pour le GPS : " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        Log.d("TrackingService", "🛑 Arrêt du service");
        
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
                "Suivi de trajet", 
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}