import { WebPlugin } from '@capacitor/core';
import type { ActivityRecognitionPlugin, PermissionStatus } from './definitions';
export declare class ActivityRecognitionWeb extends WebPlugin implements ActivityRecognitionPlugin {
    checkPermissions(): Promise<PermissionStatus>;
    requestPermissions(): Promise<PermissionStatus>;
    startTracking(options?: {
        interval?: number;
    }): Promise<void>;
    stopTracking(): Promise<void>;
}
