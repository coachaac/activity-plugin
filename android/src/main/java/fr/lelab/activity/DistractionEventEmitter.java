package fr.lelab.activity;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import android.os.Handler;
import android.os.Looper;

public class DistractionEventEmitter implements SensorEventListener {
    private static final String TAG = "DistractionEmitter";

    private final SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor linearAccelerometer;

    private boolean isDetecting = false;
    private static boolean isCurrentlyDistracted = false;

    // ⏳ DEBOUNCE management 5S
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable stopRunnable = null;
    private static final long FIVE_SECONDS_MS = 5000;

    // Seuils de calibrage (Ajustables selon vos tests sur route)
    private final double rotationThreshold = 1.5; 
    private final double accelerationThreshold = 0.5;

    // Variables pour éviter de spammer les logs de calculs continuels (toutes les 500ms max)
    private long lastLogTime = 0;
    private static final long LOG_INTERVAL_MS = 500;

    // Interface pour communiquer avec votre gestionnaire de trajet/upload
    public interface DistractionListener {
        void onDistractionEvent(String eventType, long timestamp);
    }

    private DistractionListener listener;

    public DistractionEventEmitter(Context context, DistractionListener listener) {
        this.listener = listener;
        
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
                
        if (sensorManager != null) {
            this.gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            this.linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            Log.d(TAG, "🔧 [Constructeur] Sensor initilised. Gyro: " + (gyroscope != null) + " | Accel: " + (linearAccelerometer != null));
        } else {
            Log.e(TAG, "❌ [Constructeur] SensorManager access not available on the device.");
        }
    }

    public void startMonitoring() {
        if (isDetecting || sensorManager == null) {
            Log.w(TAG, "⚠️ [startMonitoring] monitoring already activated or SensorManager.");
            return;
        }
        
        isDetecting = true;
        isCurrentlyDistracted = false;
        Log.i(TAG, "🚀 [startMonitoring] activate motion sensors.");

        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            Log.w(TAG, "⚠️ [startMonitoring] Gyroscope missing on this device.");
        }
        
        if (linearAccelerometer != null) {
            sensorManager.registerListener(this, linearAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            Log.w(TAG, "⚠️ [startMonitoring] linear Accelerometer missing on this device.");
        }
    }

    public void stopMonitoring() {
        if (!isDetecting) {
            Log.w(TAG, "⚠️ [stopMonitoring] stop when monitoring not activated.");
            return;
        }

        // 🛡️ Sécurité : On annule immédiatement tout compte à rebours en cours si on coupe les capteurs
        if (stopRunnable != null) {
            debounceHandler.removeCallbacks(stopRunnable);
            stopRunnable = null;
        }
        
        
        // Si l'application s'arrête alors que l'utilisateur manipulait le téléphone,
        // on clôture proprement l'événement avant de couper les capteurs
        if (isCurrentlyDistracted && listener != null) {
            long timestamp = System.currentTimeMillis();
            Log.d(TAG, "📱 [stopMonitoring]: STOP_DISTRACTION à " + timestamp);

            listener.onDistractionEvent("STOP_DISTRACTION", timestamp);
        }

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        isDetecting = false;
        isCurrentlyDistracted = false;
    }

    private float[] lastGyroValues = new float[3];
    private float[] lastAccelValues = new float[3];

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isDetecting) return;

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            lastGyroValues = event.values.clone();
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            lastAccelValues = event.values.clone();
        }

        double rotationMagnitude = Math.sqrt(
            Math.pow(lastGyroValues[0], 2) + Math.pow(lastGyroValues[1], 2) + Math.pow(lastGyroValues[2], 2)
        );
        double accelerationMagnitude = Math.sqrt(
            Math.pow(lastAccelValues[0], 2) + Math.pow(lastAccelValues[1], 2) + Math.pow(lastAccelValues[2], 2)
        );

        // 🎯 VÉRIFICATION DES SEUILS
        if (rotationMagnitude > rotationThreshold && accelerationMagnitude > accelerationThreshold) {
            
            // 🛑 ANNULATION DU TIMER : L'utilisateur bouge à nouveau !
            if (stopRunnable != null) {
                Log.d(TAG, "🔄 Nouvelle distraction détectée avant la fin des 5s : Annulation du timer STOP.");
                debounceHandler.removeCallbacks(stopRunnable);
                stopRunnable = null;
            }

            // Déclenchement classique du START
            if (!isCurrentlyDistracted) {
                isCurrentlyDistracted = true;
                long timestamp = System.currentTimeMillis();
                Log.i(TAG, "🚨 [EVENT] START_DISTRACTION envoyé au backend à " + timestamp);
                if (listener != null) {
                    listener.onDistractionEvent("START_DISTRACTION", timestamp);
                }
            }
            
        } else {
            // Le téléphone est redevenu immobile, mais on était en distraction
            if (isCurrentlyDistracted && stopRunnable == null) {
                
                Log.w(TAG, "⏳ Téléphone stable. Lancement du compte à rebours de 5 secondes avant l'envoi du STOP...");
                
                // ⏱️ Création du compte à rebours
                stopRunnable = new Runnable() {
                    @Override
                    public void run() {
                        // Ce bloc ne s'exécutera QUE si les 5 secondes se sont écoulées sans nouveau mouvement
                        isCurrentlyDistracted = false;
                        long timestamp = System.currentTimeMillis();
                        Log.i(TAG, "📱 [EVENT] 5 secondes écoulées en continu ! STOP_DISTRACTION envoyé à " + timestamp);
                        
                        if (listener != null) {
                            listener.onDistractionEvent("STOP_DISTRACTION", timestamp);
                        }
                        stopRunnable = null; // Reset du timer
                    }
                };
                
                // On programme l'exécution dans 5000 millisecondes (5s)
                debounceHandler.postDelayed(stopRunnable, FIVE_SECONDS_MS);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "🎯 [AccuracyChanged] Précision modifiée pour " + sensor.getName() + " -> " + accuracy);
    }

    public static boolean getCurrentlyDistracted() {
        return isCurrentlyDistracted;
    }
}