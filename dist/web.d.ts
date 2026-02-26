import { WebPlugin } from '@capacitor/core';
import type { ActivityRecognitionPlugin, PermissionStatus, GpsLocation } from './definitions';
export declare class ActivityRecognitionWeb extends WebPlugin implements ActivityRecognitionPlugin {
    checkPermissions(): Promise<PermissionStatus>;
    requestPermissions(): Promise<PermissionStatus>;
    startTracking(_options?: {
        debug?: boolean;
        url?: string;
        groupId?: string;
    }): Promise<void>;
    stopTracking(): Promise<void>;
    forceUpload(): Promise<{
        status: string;
    }>;
    getSavedLocations(): Promise<{
        locations: GpsLocation[];
    }>;
    clearSavedLocations(): Promise<void>;
    shareSavedLocations(): Promise<void>;
    purgeLocationsBefore(options: {
        timestamp: number;
    }): Promise<void>;
    purgeLocationsBetween(options: {
        from: number;
        to: number;
    }): Promise<void>;
}
