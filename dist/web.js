import { WebPlugin } from '@capacitor/core';
export class ActivityRecognitionWeb extends WebPlugin {
    async checkPermissions() {
        return { activity: 'denied' };
    }
    async requestPermissions() {
        return { activity: 'denied' };
    }
    async startTracking(options) {
        console.warn('Activity Recognition non disponible sur Web', options);
    }
    async stopTracking() {
        console.log('Activity Recognition arrêté');
    }
}
//# sourceMappingURL=web.js.map