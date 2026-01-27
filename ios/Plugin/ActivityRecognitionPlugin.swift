import Foundation
import Capacitor
import CoreMotion
import CoreLocation
import UIKit

@objc(ActivityRecognitionPlugin)
public class ActivityRecognitionPlugin: CAPPlugin, CLLocationManagerDelegate {
    
    private let activityManager = CMMotionActivityManager()
    private let locationManager = CLLocationManager()
    private let fileName = "stored_locations.json"
    
    private var isDriving = false
    private var stopTimer: Timer?
    private let stopDelay: TimeInterval = 180 // 3 minutes
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid

    @objc public override func load() {
        locationManager.delegate = self
        locationManager.pausesLocationUpdatesAutomatically = false
        
        // Réveil automatique si un monitoring était déjà actif
        if CLLocationManager.significantLocationChangeMonitoringAvailable() {
            startActivityDetection() 
        }
    }

    // MARK: - Helper Date
    private func getFormattedDate() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.string(from: Date())
    }

    // MARK: - Logic Centralisée
    private func startActivityDetection() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        
        activityManager.startActivityUpdates(to: .main) { [weak self] (activity) in
            guard let self = self, let activity = activity else { return }
            self.handleActivityUpdate(activity)
            
            let data = self.formatActivityData(activity)
            self.notifyListeners("activityChange", data: data)
        } 
    }  

    private func handleActivityUpdate(_ activity: CMMotionActivity) {
        if activity.automotive {
            // ✅ On roule : On annule le compte à rebours de coupure
            stopTimer?.invalidate()
            stopTimer = nil
            endBackgroundTask()
            
            if !self.isDriving {
                self.isDriving = true
                self.startHighPrecisionGPS()
            }
        } else if activity.stationary || activity.walking {
            // ⏳ Arrêt ou marche : On lance le timer avant de couper le GPS
            if self.isDriving && stopTimer == nil {
                self.startStopTimer()
            }
        }
    }

    // MARK: - Protection Timer (Background Task)
    private func startStopTimer() {
        // Demande du temps à iOS pour que le timer tourne écran éteint
        self.backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: "StopGPSTimer") {
            self.endBackgroundTask()
        }

        DispatchQueue.main.async {
            self.stopTimer = Timer.scheduledTimer(withTimeInterval: self.stopDelay, repeats: false) { [weak self] _ in
                guard let self = self else { return }
                print("💤 Délai de 3min atteint : arrêt du GPS")
                self.isDriving = false
                self.stopHighPrecisionGPS()
                self.stopTimer = nil
                self.endBackgroundTask()
            }
        }
    }

    private func endBackgroundTask() {
        if self.backgroundTaskID != .invalid {
            UIApplication.shared.endBackgroundTask(self.backgroundTaskID)
            self.backgroundTaskID = .invalid
        }
    }

    // MARK: - GPS Management
    private func startHighPrecisionGPS() {
        DispatchQueue.main.async {
            self.locationManager.desiredAccuracy = kCLLocationAccuracyBest
            self.locationManager.allowsBackgroundLocationUpdates = true
            if #available(iOS 11.0, *) {
                self.locationManager.showsBackgroundLocationIndicator = true
            }
            self.locationManager.startUpdatingLocation()
        }
    }

    private func stopHighPrecisionGPS() {
        DispatchQueue.main.async {
            self.locationManager.stopUpdatingLocation()
            self.locationManager.allowsBackgroundLocationUpdates = false
            if #available(iOS 11.0, *) {
                self.locationManager.showsBackgroundLocationIndicator = false
            }
        }
    }

    // MARK: - Plugin Methods (Interface JS)
    @objc public func startTracking(_ call: CAPPluginCall) {
        startActivityDetection()
        // Surveillance discrète pour le réveil auto
        locationManager.startMonitoringSignificantLocationChanges()
        call.resolve()
    }

    @objc public func stopTracking(_ call: CAPPluginCall) {
        stopTimer?.invalidate()
        stopTimer = nil
        endBackgroundTask()

        activityManager.stopActivityUpdates()
        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
        
        locationManager.allowsBackgroundLocationUpdates = false
        self.isDriving = false
        
        call.resolve()
    }

    @objc public func getSavedLocations(_ call: CAPPluginCall) {
        let points = loadStoredPoints()
        call.resolve(["locations": points])
    }

    @objc public func clearSavedLocations(_ call: CAPPluginCall) {
        try? FileManager.default.removeItem(at: getFilePath())
        call.resolve()
    }

    @objc public override func checkPermissions(_ call: CAPPluginCall) {
        var activityStatus = "prompt"
        if #available(iOS 11.0, *) {
            switch CMMotionActivityManager.authorizationStatus() {
                case .authorized: activityStatus = "granted"
                case .denied, .restricted: activityStatus = "denied"
                default: activityStatus = "prompt"
            }
        }
        let locationStatus = CLLocationManager.authorizationStatus()
        call.resolve([
            "activity": activityStatus,
            "location": locationStatus == .authorizedAlways ? "granted" : "prompt"
        ])
    }

    @objc public override func requestPermissions(_ call: CAPPluginCall) {
        // Souvent géré par Capacitor, mais nécessaire si déclaré dans le .m
        call.resolve()
    }

    // MARK: - Delegate Location
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard isDriving, let location = locations.last else { return }
        
        let newPoint: [String: Any] = [
            "type": "location",
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "date": getFormattedDate(),
            "timestamp": location.timestamp.timeIntervalSince1970
        ]
        
        saveLocationToJSON(point: newPoint)
        self.notifyListeners("onLocationUpdate", data: newPoint)
    }

    // MARK: - Stockage & JSON
    private func saveLocationToJSON(point: [String: Any]) {
        let url = getFilePath()
        var dataArray = loadStoredPoints()
        dataArray.append(point)
        
        if let data = try? JSONSerialization.data(withJSONObject: dataArray, options: [.prettyPrinted]) {
            try? data.write(to: url)
        }
    }

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
        var type = "stationary"
        if activity.walking { type = "walking" }
        else if activity.running { type = "running" }
        else if activity.cycling { type = "cycling" }
        else if activity.automotive { type = "automotive" }
        
        return [
            "type": "activity",
            "activity": type,
            "transition": "ENTER",
            "date": getFormattedDate(),
            "timestamp": Date().timeIntervalSince1970
        ]
    }

    @objc func shareSavedLocations(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let fileURL = self.getFilePath()
            print("📍 try to share file : \(fileURL.path)")

            if FileManager.default.fileExists(atPath: fileURL.path) {
                // do not share empty file)
                if let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
                   let size = attributes[.size] as? UInt64, size > 0 {
                    
                    // set URL ios determine it is a json
                    let activityVC = UIActivityViewController(activityItems: [fileURL], applicationActivities: nil)
                    
                    if let popoverController = activityVC.popoverPresentationController {
                        popoverController.sourceView = self.bridge?.viewController?.view
                        popoverController.sourceRect = CGRect(x: UIScreen.main.bounds.midX, y: UIScreen.main.bounds.midY, width: 0, height: 0)
                        popoverController.permittedArrowDirections = []
                    }
                    
                    self.bridge?.viewController?.present(activityVC, animated: true) {
                        call.resolve()
                    }
                } else {
                    print("❌ file exists but empty")
                    call.reject("Le fichier de données est vide.")
                }
            } else {
                print("❌ file not found at path given.")
                call.reject("file not found, register positions first")
            }
        }
    }
}