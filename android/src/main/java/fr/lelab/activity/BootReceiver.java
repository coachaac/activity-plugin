package fr.lelab.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("SmartPilot", "🚀 Boot terminé : réactivation de la reconnaissance d'activité");
            // Relance uniquement les capteurs de transition
            // Cela permettra de détecter l'entrée en voiture plus tard sans afficher de notif maintenant
            ActivityRecognition implementation = new ActivityRecognition(context);
            implementation.startTracking(); 
        }
    }
}