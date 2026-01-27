import { WebPlugin } from '@capacitor/core';
export class ActivityRecognitionWeb extends WebPlugin {
    async checkPermissions() {
        return {
            activity: 'granted',
            location: 'granted'
        };
    }
    async requestPermissions() {
        return {
            activity: 'granted',
            location: 'granted'
        };
    }
    async startTracking(_options) {
        console.log('Tracking started on web');
    }
    async stopTracking() {
        console.log('Tracking stopped on web');
    }
    async getSavedLocations() {
        return { locations: [] };
    }
    async clearSavedLocations() {
        console.log('Clear locations on web');
    }
    async enableAutonomousMode(_options) {
        console.log('Autonomous mode toggled');
    }
    async shareSavedLocations() {
        console.warn('File sharing not implemented on Web');
    }
}
//# sourceMappingURL=web.js.map