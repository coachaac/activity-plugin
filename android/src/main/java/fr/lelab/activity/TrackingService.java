package fr.lelab.activity;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log; // ✅ IMPORT MANQUANT

import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.*;

public class TrackingService extends Service {
    private static final String CHANNEL_ID = "SmartPilot_Channel";
    private PendingIntent locationPendingIntent;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SmartPilot")
                .setContentText("Trajet en cours d'enregistrement...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notification);
        }

        startLocationUpdates();
        return START_STICKY;
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build();
        Intent intent = new Intent(this, LocationReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        locationPendingIntent = PendingIntent.getBroadcast(this, 1, intent, flags);

        try {
            LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(locationRequest, locationPendingIntent);
        } catch (SecurityException ignored) {}
    }

    @Override
    public void onDestroy() {
        Log.d("TrackingService", "Stopping service and removing location updates");
        
        // IMPORTANT : On retire l'écouteur GPS avant de fermer
        if (locationPendingIntent != null) {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(locationPendingIntent)
                .addOnCompleteListener(task -> Log.d("TrackingService", "GPS updates removed"));
        }
        
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Suivi de trajet", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}