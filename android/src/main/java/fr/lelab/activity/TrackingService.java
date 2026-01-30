package fr.lelab.activity;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // --- LA SÉCURITÉ EST ICI ---
        // Si le service démarre sans l'action explicite (ex: redémarrage automatique d'Android)
        // On ne lance pas la notification et on arrête le service.
        if (intent == null || !ACTION_START_TRACKING.equals(intent.getAction())) {
            Log.d("TrackingService", "⚠️ Démarrage fantôme détecté (START_STICKY), arrêt immédiat.");
            stopSelf();
            return START_NOT_STICKY; // On dit à Android de ne plus essayer de le relancer seul
        }

        createNotificationChannel();
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Coach AAC")
                .setContentText("Trajet en cours d'enregistrement...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (locationPendingIntent == null) {
            startLocationUpdates();
        }

        return START_STICKY; 
    }

    private void startLocationUpdates() {
        // Utilisation du Builder (obligatoire pour Android 14-16)
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000) // Maximum 1 point toutes les 2s
                .setMinUpdateDistanceMeters(10)   // Minimum 10 mètres entre deux points
                .setWaitForAccurateLocation(true)
                .build();

        Intent intent = new Intent(this, LocationReceiver.class);
        // On garde FLAG_MUTABLE car Google Play Services doit injecter la "Location" dans l'Intent
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        
        locationPendingIntent = PendingIntent.getBroadcast(this, 1, intent, flags);

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
            channel.setDescription("Utilisé pour le suivi GPS en conduite");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}