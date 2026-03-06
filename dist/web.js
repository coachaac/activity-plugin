import { WebPlugin } from '@capacitor/core';
export class ActivityRecognitionWeb extends WebPlugin {
    async checkPermissions() {
        return {
            activity: 'granted',
            location: 'granted',
            backgroundLocation: 'granted'
        };
    }
    async requestPermissions() {
        return {
            activity: 'granted',
            location: 'granted',
            backgroundLocation: 'granted'
        };
    }
    async checkBatteryOptimization() {
        // not available on web simulate "OK"
        return { isIgnoring: true };
    }
    async requestIgnoreBatteryOptimization() {
        console.warn('Battery optimization check is not available on web');
        return;
    }
    async startTracking(_options) {
        console.log('Tracking started on web');
    }
    async stopTracking() {
        console.log('Tracking stopped on web');
    }
    async forceUpload() {
        console.log('Tracking stopped on web');
        return { status: "not managed on web" };
    }
    async testSettings() {
        console.warn('testSettings n’est pas disponible sur le Web. Simulation d’un succès.');
        return {
            status: 'ok',
            statusCode: 200,
            url: 'http://localhost/web-simulated',
            groupId: 'web-token-demo'
        };
    }
    async isSyncing() {
        // Sur le web, on considère qu'aucune synchro n'est en cours
        return { inProgress: false };
    }
    async getSavedLocations() {
        return { locations: [] };
    }
    async clearSavedLocations() {
        console.log('Clear locations on web');
    }
    async shareSavedLocations() {
        console.warn('File sharing not implemented on Web');
    }
    async purgeLocationsBefore(options) {
        console.log('🌐 Web implementation: purgeLocationsBefore called with timestamp:', options.timestamp);
    }
    async purgeLocationsBetween(options) {
        console.log('🌐 Web implementation: purgeLocationsBetween called with from: ', options.from, ' to: ', options.to);
    }
}
//# sourceMappingURL=web.js.map