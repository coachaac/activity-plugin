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
    public static final String ACTION_UPDATE_NOTIF = "fr.lelab.activity.UPDATE_NOTIF";
    
    public static final String EXTRA_STATE = "extra_state";
    public static final String STATE_ACTIVITY_ONLY = "ACTIVITY_ONLY";
    public static final String STATE_GPS_TRACKING = "GPS_TRACKING";
    private PendingIntent locationPendingIntent;
    private FusedLocationProviderClient fusedLocationClient;

    private final android.os.Handler watchdogHandler = new android.os.Handler();
    private Runnable watchdogRunnable;
    private static final long WATCHDOG_INTERVAL = 60000;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // start watch dog for notif verification
        startWatchdog();
    }

    private Context getSafeContext() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            ? createDeviceProtectedStorageContext() 
            : this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSafeContext().getSharedPreferences("CapacitorStorage", MODE_PRIVATE);
        
        // 1. On détermine l'état souhaité (soit via l'Intent, soit via les Prefs au reboot)
        String action = (intent != null) ? intent.getAction() : null;
        boolean shouldBeDriving = prefs.getBoolean("driving_state", false);

        Log.d("TrackingService", "🔍 Intent Received - Action: " + action);

        // Gestion du cas spécifique UPDATE_NOTIF
        if (intent != null && "fr.lelab.activity.UPDATE_NOTIF".equals(action)) {
            String stateExtra = intent.getStringExtra(EXTRA_STATE);
            if (stateExtra != null) {
                shouldBeDriving = stateExtra.equals("GPS_TRACKING");
            }
        }

        // 2. Sécurité Reboot : Si le système relance le service (intent null) mais qu'on n'était pas en trajet
        if (intent == null && !shouldBeDriving) {
            Log.d("TrackingService", "⚠️ Relaunch system outside automotive, Stop.");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.d("TrackingService", "⚡ Service state update: GPS active = " + shouldBeDriving);
        
        // 3. Notification (Mandatory for Foreground)
        createNotificationChannel();

        // send state for notif title/icon
        Notification notification = createNotification(shouldBeDriving ? "GPS_TRACKING" : "ACTIVITY_ONLY");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                e instanceof ForegroundServiceStartNotAllowedException) {
                Log.e("TrackingService", "Lancement interdit en arrière-plan : " + e.getMessage());
            }
            // On arrête le service proprement pour éviter de laisser un service "fantôme" sans notification
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);

        // 4. Gestion dynamique du GPS
        if (shouldBeDriving) {
            if (locationPendingIntent == null) {
                Log.d("TrackingService", "🛰️ Starting GPS Updates");
                startLocationUpdates();
            }
        } else {
            if (locationPendingIntent != null) {
                Log.d("TrackingService", "🛑 Stopping GPS Updates (Back to Idle)");
                stopLocationUpdates(); 
            }
        }

        return START_STICKY; 
    }

    private Notification createNotification(String state) {
        String title;
        String content;
        int icon;

        // create intent to open app on click
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            PendingIntent pendingIntent = null;
            
            if (intent != null) {
                // FLAG_ACTIVITY_SINGLE_TOP évite de recréer une nouvelle instance si l'app est déjà ouverte
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                
                // FLAG_IMMUTABLE est requis pour Android 12+ (API 31+)
                pendingIntent = PendingIntent.getActivity(
                    this, 
                    0, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
            }

        // adapt content depending on state
        if ("GPS_TRACKING".equals(state)) {
            title = "Trajet en cours";
            content = "Enregistrement de vos positions GPS...";
            icon = android.R.drawable.ic_menu_directions; 
        } else {
            title = "En attente de trajet";
            content = "Analyse d'activité en cours...";
            icon = android.R.drawable.ic_menu_mylocation; 
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW);

            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent);
            }

            return builder.build();
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
        
        // FLAG_MUTABLE mandatory for the system to inject position
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

    private void stopLocationUpdates() {
        if (locationPendingIntent != null) {
            Log.d("TrackingService", "🛰️ Removing GPS updates");
            fusedLocationClient.removeLocationUpdates(locationPendingIntent)
                .addOnCompleteListener(task -> {
                    Log.d("TrackingService", "✅ GPS updates successfully removed");
                    locationPendingIntent = null; 
                });
        }
    }

    @Override
    public void onDestroy() {
        Log.d("TrackingService", "🛑 Service Destroyed");
        
        // 1. On arrête le GPS proprement
        stopLocationUpdates();
        
        // 2. On retire la notification de premier plan
        // true = enlève aussi la notification de la barre d'état
        stopForeground(true);
        
        // 3. Sécurité supplémentaire pour nettoyer la notif
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }

        watchdogHandler.removeCallbacks(watchdogRunnable);
        
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


    // watch dog to verify if notification still displayed, if not restore it
    private void startWatchdog() {
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndRestoreNotification();
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL);
            }
        };
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL);
    }

    private void checkAndRestoreNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean isNotifActive = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.service.notification.StatusBarNotification[] activeNotifications = manager.getActiveNotifications();
            for (android.service.notification.StatusBarNotification sbn : activeNotifications) {
                if (sbn.getId() == NOTIFICATION_ID) {
                    isNotifActive = true;
                    break;
                }
            }
        }

        if (!isNotifActive) {
            Log.d("TrackingService", "⚠️ Notif missing detected by Watchdog. Restore...");
            // get current state from SharedPreferences
            SharedPreferences prefs = getSafeContext().getSharedPreferences("CapacitorStorage", MODE_PRIVATE);
            boolean shouldBeDriving = prefs.getBoolean("driving_state", false);
            
            Notification notification = createNotification(shouldBeDriving ? STATE_GPS_TRACKING : STATE_ACTIVITY_ONLY);
            
            // On relance le foreground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        }
    }
}