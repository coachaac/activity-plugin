import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export interface PermissionStatus {
  activity: PermissionState;
  location: PermissionState; 
  backgroundLocation: PermissionState;
}

export interface ActivityEvent {
  activity: 'walking' | 'running' | 'cycling' | 'automotive' | 'stationary' | 'unknown';
  transition: 'ENTER' | 'EXIT';
}

export interface GpsLocation {
  lat: number;
  lng: number;
  timestamp: number;
}

export interface ActivityRecognitionPlugin {
  checkPermissions(): Promise<PermissionStatus>;
  requestPermissions(options?: { permissions: string[] }): Promise<PermissionStatus>;

  startTracking(options?: { debug?: boolean, url?: string, groupId?: string }): Promise<void>;
  stopTracking(): Promise<void>;

  forceUpload(): Promise<{ status: string }>;

  checkBatteryOptimization(): Promise<{ isIgnoring: boolean }>;
  requestIgnoreBatteryOptimization(): Promise<void>;
  
  testSettings(): Promise<{ 
    status: 'ok' | 'error'; 
    statusCode: number; 
    url: string; 
    groupId: string; 
    message?: string; 
  }>;

  isSyncing(): Promise<{ inProgress: boolean }>;

  // --- DEBUG Method ---
  
  /**
   * get  GPS position
   */
  getSavedLocations(): Promise<{ locations: GpsLocation[] }>;
  
  /**
   * clear JSON File 
   */
  clearSavedLocations(): Promise<void>;

  /**
   * share JSON File 
   */
  shareSavedLocations(): Promise<void>;

  /**
   * Suppress point older than timestamp
   * @param options { timestamp: number } - Timestamp in millisecondes
   */
  purgeLocationsBefore(options: { timestamp: number }): Promise<void>;

  /**
   * Suppress point between two timestamps
   * @param options { from: number, to:number} - in millisecondes
   */
  purgeLocationsBetween(options: { from: number, to:number }): Promise<void>;


  // --- EVENT LISTENER ---

  addListener(
    eventName: 'activityChange',
    listenerFunc: (event: ActivityEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * live GPS position (if app foreground)
   */
  addListener(
    eventName: 'onLocationUpdate',
    listenerFunc: (event: GpsLocation) => void
  ): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
  
}