import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export interface PermissionStatus {
  activity: PermissionState;
  location: PermissionState; // Ajouté pour le GPS
}

export interface ActivityEvent {
  type: 'walking' | 'running' | 'cycling' | 'automotive' | 'stationary' | 'unknown';
  transition: 'ENTER' | 'EXIT';
}

// Nouvelle interface pour les points GPS stockés
export interface GpsLocation {
  lat: number;
  lng: number;
  timestamp: number;
}

export interface ActivityRecognitionPlugin {
  checkPermissions(): Promise<PermissionStatus>;
  requestPermissions(): Promise<PermissionStatus>;
  
  // Modifié pour inclure le tracking GPS
  startTracking(options?: { debug?: boolean }): Promise<void>;
  stopTracking(): Promise<void>;
  
  // --- NOUVELLES MÉTHODES POUR LE STOCKAGE ---
  
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