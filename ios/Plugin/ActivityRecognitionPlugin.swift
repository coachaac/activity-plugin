import Foundation
import Capacitor
import CoreMotion

@objc(ActivityRecognitionPlugin)
public class ActivityRecognitionPlugin: CAPPlugin {
    private let activityManager = CMMotionActivityManager()

    // MARK: - Permissions
    
    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        let status: String
        
        if #available(iOS 11.0, *) {
            switch CMMotionActivityManager.authorizationStatus() {
            case .authorized:
                status = "granted"
            case .denied, .restricted:
                status = "denied"
            case .notDetermined:
                status = "prompt"
            @unknown default:
                status = "prompt"
            }
        } else {
            status = "granted" // Sur les vieux iOS, on considère comme granted
        }
        
        call.resolve([
            "activity": status
        ])
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        // Sur iOS, on ne peut pas forcer le popup de permission via une fonction.
        // On vérifie l'état actuel. Si c'est 'notDetermined', l'appel à 
        // startTracking déclenchera automatiquement le popup système.
        checkPermissions(call)
    }

    // MARK: - Tracking

    @objc func startTracking(_ call: CAPPluginCall) {
        guard CMMotionActivityManager.isActivityAvailable() else {
            call.reject("La reconnaissance d'activité n'est pas disponible sur cet appareil.")
            return
        }

        activityManager.startActivityUpdates(to: .main) { [weak self] (activity) in
            guard let self = self, let activity = activity else { return }
            
            // On transforme l'objet iOS en objet compatible avec votre interface TS
            let data = self.formatActivityData(activity)
            self.notifyListeners("activityChange", data: data)
        }
        call.resolve()
    }

    @objc func stopTracking(_ call: CAPPluginCall) {
        activityManager.stopActivityUpdates()
        call.resolve()
    }

    // MARK: - Helper

    private func formatActivityData(_ activity: CMMotionActivity) -> [String: Any] {
        var type = "unknown"
        
        // iOS donne des booléens pour chaque état
        if activity.walking { type = "walking" }
        else if activity.running { type = "running" }
        else if activity.cycling { type = "cycling" }
        else if activity.automotive { type = "automotive" }
        else if activity.stationary { type = "stationary" }

        return [
            "type": type,
            "transition": "ENTER" // iOS rafraîchit l'état actuel
        ]
    }
}