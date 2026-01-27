import type { PermissionState, PluginListenerHandle } from '@capacitor/core';
export interface PermissionStatus {
    activity: PermissionState;
    location: PermissionState;
}
export interface ActivityEvent {
    type: 'walking' | 'running' | 'cycling' | 'automotive' | 'stationary' | 'unknown';
    transition: 'ENTER' | 'EXIT';
}
export interface GpsLocation {
    lat: number;
    lng: number;
    timestamp: number;
}
export interface ActivityRecognitionPlugin {
    checkPermissions(): Promise<PermissionStatus>;
    requestPermissions(): Promise<PermissionStatus>;
    startTracking(options?: {
        interval?: number;
    }): Promise<void>;
    stopTracking(): Promise<void>;
    /**
     * get  GPS position
     */
    getSavedLocations(): Promise<{
        locations: GpsLocation[];
    }>;
    /**
     * clear JSON File
     */
    clearSavedLocations(): Promise<void>;
    /**
     * share JSON File
     */
    shareSavedLocations(): Promise<void>;
    addListener(eventName: 'activityChange', listenerFunc: (event: ActivityEvent) => void): Promise<PluginListenerHandle>;
    /**
     * live GPS position (if app foreground)
     */
    addListener(eventName: 'onLocationUpdate', listenerFunc: (event: GpsLocation) => void): Promise<PluginListenerHandle>;
    removeAllListeners(): Promise<void>;
    /**
     * On Android : active BootReceiver.
     * On iOS : activate Significant Location Change.
     */
    enableAutonomousMode(options: {
        enabled: boolean;
    }): Promise<void>;
}
