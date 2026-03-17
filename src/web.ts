import { WebPlugin } from '@capacitor/core';
import type { ActivityRecognitionPlugin, PermissionStatus, GpsLocation } from './definitions';

export class ActivityRecognitionWeb extends WebPlugin implements ActivityRecognitionPlugin {
  
  async checkPermissions(): Promise<PermissionStatus> {
    return { 
      activity: 'granted',
      location: 'granted',
      backgroundLocation: 'granted'
    };
  }

  async requestPermissions(): Promise<PermissionStatus> {
    return { 
      activity: 'granted',
      location: 'granted',
      backgroundLocation: 'granted'
    };
  }

  async checkBatteryOptimization(): Promise<{ isIgnoring: boolean }> {
    // not available on web simulate "OK"
    return { isIgnoring: true };
  }

  async requestIgnoreBatteryOptimization(): Promise<void> {
    console.warn('Battery optimization check is not available on web');
    return;
  }

  async startTracking(_options?: { debug?: boolean, url?: string, groupId?: string, weatherUrl?: string, weatherAPIkey?: string}): Promise<void> {
    console.log('Tracking started on web');
  }

  async stopTracking(): Promise<void> {
    console.log('Tracking stopped on web');
  }

  async forceUpload(): Promise<{ status: string }>{
    console.log('Tracking stopped on web');
    return {status: "not managed on web"}
  }

  async testSettings(): Promise<{ status: 'ok' | 'error'; statusCode: number; url: string; groupId: string }> {
    console.warn('testSettings n’est pas disponible sur le Web. Simulation d’un succès.');
    return {
      status: 'ok',
      statusCode: 200,
      url: 'http://localhost/web-simulated',
      groupId: 'web-token-demo'
    };
  }

  async isSyncing(): Promise<{ inProgress: boolean }> {
    // Sur le web, on considère qu'aucune synchro n'est en cours
    return { inProgress: false };
  }
  

  async getSavedLocations(): Promise<{ locations: GpsLocation[] }> {
    return { locations: [] };
  }

  async clearSavedLocations(): Promise<void> {
    console.log('Clear locations on web');
  }

  async shareSavedLocations(): Promise<void> {
    console.warn('File sharing not implemented on Web');
  }

  async purgeLocationsBefore(options: { timestamp: number }): Promise<void> {
    console.log('🌐 Web implementation: purgeLocationsBefore called with timestamp:', options.timestamp);
  }

  async purgeLocationsBetween(options: { from: number, to:number }): Promise<void> {
    console.log('🌐 Web implementation: purgeLocationsBetween called with from: ',options.from, ' to: ',options.to);
  }
  
}