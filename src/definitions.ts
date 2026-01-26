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
  startTracking(options?: { interval?: number }): Promise<void>;
  stopTracking(): Promise<void>;
  
  // --- NOUVELLES MÉTHODES POUR LE STOCKAGE ---
  
  /**
   * Récupère les positions GPS enregistrées en arrière-plan
   */
  getSavedLocations(): Promise<{ locations: GpsLocation[] }>;
  
  /**
   * Vide le stockage local (JSON/SQLite)
   */
  clearSavedLocations(): Promise<void>;

  // --- ÉCOUTEURS D'ÉVÉNEMENTS ---

  addListener(
    eventName: 'activityChange',
    listenerFunc: (event: ActivityEvent) => void
  ): Promise<PluginListenerHandle>;

  /**
   * Écoute les positions GPS en temps réel (si l'app est au premier plan)
   */
  addListener(
    eventName: 'onLocationUpdate',
    listenerFunc: (event: GpsLocation) => void
  ): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
  
  /**
   * Sur Android : active le BootReceiver. 
   * Sur iOS : cette méthode peut rester vide ou activer le Significant Location Change.
   */
  enableAutonomousMode(options: { enabled: boolean }): Promise<void>;
}