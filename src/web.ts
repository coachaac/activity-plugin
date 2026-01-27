import { WebPlugin } from '@capacitor/core';
import type { ActivityRecognitionPlugin, PermissionStatus, GpsLocation } from './definitions';

export class ActivityRecognitionWeb extends WebPlugin implements ActivityRecognitionPlugin {
  
  async checkPermissions(): Promise<PermissionStatus> {
    return { 
      activity: 'granted',
      location: 'granted' 
    };
  }

  async requestPermissions(): Promise<PermissionStatus> {
    return { 
      activity: 'granted',
      location: 'granted' 
    };
  }

  async startTracking(_options?: { interval?: number }): Promise<void> {
    console.log('Tracking started on web');
  }

  async stopTracking(): Promise<void> {
    console.log('Tracking stopped on web');
  }

  async getSavedLocations(): Promise<{ locations: GpsLocation[] }> {
    return { locations: [] };
  }

  async clearSavedLocations(): Promise<void> {
    console.log('Clear locations on web');
  }

  async enableAutonomousMode(_options: { enabled: boolean }): Promise<void> {
    console.log('Autonomous mode toggled');
  }

  async shareSavedLocations(): Promise<void> {
    console.warn('File sharing not implemented on Web');
  }
}