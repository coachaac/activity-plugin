import { WebPlugin } from '@capacitor/core';
import type { ActivityRecognitionPlugin, PermissionStatus, GpsLocation } from './definitions';
export declare class ActivityRecognitionWeb extends WebPlugin implements ActivityRecognitionPlugin {
    checkPermissions(): Promise<PermissionStatus>;
    requestPermissions(): Promise<PermissionStatus>;
    startTracking(_options?: {
        interval?: number;
    }): Promise<void>;
    stopTracking(): Promise<void>;
    getSavedLocations(): Promise<{
        locations: GpsLocation[];
    }>;
    clearSavedLocations(): Promise<void>;
    enableAutonomousMode(_options: {
        enabled: boolean;
    }): Promise<void>;
}
