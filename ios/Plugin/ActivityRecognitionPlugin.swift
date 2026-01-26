import Foundation
import Capacitor
import CoreMotion
import CoreLocation

@objc(ActivityRecognitionPlugin)
public class ActivityRecognitionPlugin: CAPPlugin, CLLocationManagerDelegate {
    
    private let activityManager = CMMotionActivityManager()
    private let locationManager = CLLocationManager()
    private let fileName = "stored_locations.json"

    override public func load() {
        locationManager.delegate = self
    }

    // MARK: - Permissions
    
    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        var activityStatus = "prompt"
        
        if #available(iOS 11.0, *) {
            switch CMMotionActivityManager.authorizationStatus() {
            case .authorized: activityStatus = "granted"
            case .denied, .restricted: activityStatus = "denied"
            default: activityStatus = "prompt"
            }
        }
        
        // On vérifie aussi le GPS
        let locationStatus = CLLocationManager.authorizationStatus()
        
        call.resolve([
            "activity": activityStatus,
            "location": locationStatus == .authorizedAlways ? "granted" : "prompt"
        ])
    }

    // MARK: - Tracking

    @objc func startTracking(_ call: CAPPluginCall) {
        // 1. Démarrage de l'activité (Ton code original)
        guard CMMotionActivityManager.isActivityAvailable() else {
            call.reject("Reconnaissance d'activité non disponible.")
            return
        }

        activityManager.startActivityUpdates(to: .main) { [weak self] (activity) in
            guard let self = self, let activity = activity else { return }
            let data = self.formatActivityData(activity)
            self.notifyListeners("activityChange", data: data)
        }

        // 2. Démarrage du GPS Background (Nouveau)
        DispatchQueue.main.async {
            self.locationManager.requestAlwaysAuthorization() // Demande "Toujours"
            self.locationManager.allowsBackgroundLocationUpdates = true
            self.locationManager.pausesLocationUpdatesAutomatically = false
            self.locationManager.desiredAccuracy = kCLLocationAccuracyBest
            
            // Réveil auto après reboot ou kill
            self.locationManager.startMonitoringSignificantLocationChanges()
            self.locationManager.startUpdatingLocation()
        }

        call.resolve()
    }

    @objc func stopTracking(_ call: CAPPluginCall) {
        activityManager.stopActivityUpdates()
        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
        call.resolve()
    }

    // MARK: - GPS Delegate & Stockage

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        
        let newPoint: [String: Any] = [
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "timestamp": location.timestamp.timeIntervalSince1970
        ]
        
        saveLocationToJSON(point: newPoint)
        self.notifyListeners("onLocationUpdate", data: newPoint)
    }

    private func saveLocationToJSON(point: [String: Any]) {
        let url = getFilePath()
        var points = loadStoredPoints()
        points.append(point)
        
        if let data = try? JSONSerialization.data(withJSONObject: points, options: []) {
            try? data.write(to: url)
        }
    }

    @objc func getSavedLocations(_ call: CAPPluginCall) {
        let points = loadStoredPoints()
        call.resolve(["locations": points])
    }

    @objc func clearSavedLocations(_ call: CAPPluginCall) {
        try? FileManager.default.removeItem(at: getFilePath())
        call.resolve()
    }

    // MARK: - Helpers

    private func loadStoredPoints() -> [[String: Any]] {
        guard let data = try? Data(contentsOf: getFilePath()),
              let json = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return json
    }

    private func getFilePath() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(fileName)
    }

    private func formatActivityData(_ activity: CMMotionActivity) -> [String: Any] {
        var type = "unknown"
        if activity.walking { type = "walking" }
        else if activity.running { type = "running" }
        else if activity.cycling { type = "cycling" }
        else if activity.automotive { type = "automotive" }
        else if activity.stationary { type = "stationary" }

        return [
            "type": type,
            "transition": "ENTER"
        ]
    }
}