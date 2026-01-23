import { WebPlugin } from '@capacitor/core';
import type { ActivityRecognitionPlugin, PermissionStatus } from './definitions';

export class ActivityRecognitionWeb extends WebPlugin implements ActivityRecognitionPlugin {
  
  async checkPermissions(): Promise<PermissionStatus> {
    return { activity: 'denied' };
  }

  async requestPermissions(): Promise<PermissionStatus> {
    return { activity: 'denied' };
  }

  async startTracking(options?: { interval?: number }): Promise<void> {
    console.warn('Activity Recognition non disponible sur Web', options);
  }

  async stopTracking(): Promise<void> {
    console.log('Activity Recognition arrêté');
  }

  // On laisse WebPlugin gérer addListener automatiquement. 
  // Le cast dans definitions.ts suffira à TypeScript.
}