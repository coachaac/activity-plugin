import type { PermissionState, PluginListenerHandle } from '@capacitor/core';
export interface PermissionStatus {
    activity: PermissionState;
}
export interface ActivityEvent {
    /**
     * Type d'activité (correspond au mapping getActivityNameForTs en Java)
     */
    type: 'walking' | 'running' | 'cycling' | 'automotive' | 'stationary' | 'unknown';
    /**
     * Indique si l'utilisateur commence (ENTER) ou arrête (EXIT) l'activité
     */
    transition: 'ENTER' | 'EXIT';
}
export interface ActivityRecognitionPlugin {
    checkPermissions(): Promise<PermissionStatus>;
    requestPermissions(): Promise<PermissionStatus>;
    startTracking(options?: {
        interval?: number;
    }): Promise<void>;
    stopTracking(): Promise<void>;
    addListener(eventName: 'activityChange', listenerFunc: (event: ActivityEvent) => void): Promise<PluginListenerHandle>;
    /**
     * Utile pour nettoyer les écouteurs lors de la destruction d'un composant
     */
    removeAllListeners(): Promise<void>;
}
