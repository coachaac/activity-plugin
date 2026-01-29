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

    private var debugMode = false

    @objc public override func load() {
        locationManager.delegate = self
        locationManager.pausesLocationUpdatesAutomatically = false

        // Optionnal: allow more robust retart if app is closed
        locationManager.allowsBackgroundLocationUpdates = true
        
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

    private func triggerVibration(double: Bool) {
        let generator = UIImpactFeedbackGenerator(style: .heavy)
        generator.prepare()
        generator.impactOccurred()
        
        if double {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                generator.impactOccurred()
            }
        }
    }

    private func handleActivityUpdate(_ activity: CMMotionActivity) {
        if activity.automotive {
            // ✅ Driving : re-init all
            self.cancelStopTimer()
            
            if !self.isDriving {
                print("🚗 Automotive START : GPS High Precision started")
                self.isDriving = true
                self.startHighPrecisionGPS()

                // vibrate twice start high precision GPS tracking
                if debugMode 
                    { self.triggerVibration(double: true) }
            }
        } else if activity.walking || activity.stationary || activity.cycling {
            // ⏳ Transition : count down after driving
            if self.isDriving && stopTimer == nil {
                print("🚶 Activity transition detected : start 3min timer")
                self.startStopTimer()
            }
        }
    }

    // MARK: - Protection Timer (Background Task)
    private func startStopTimer() {
        // Sécurity : prevent multiple background task
        self.endBackgroundTask()
        
        // Request background to iOS (crucial for Timer)
        self.backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: "StopGPSTimer") {
            self.endBackgroundTask()
        }

        DispatchQueue.main.async {
            self.stopTimer = Timer.scheduledTimer(withTimeInterval: self.stopDelay, repeats: false) { [weak self] _ in
                guard let self = self else { return }
                print("💤 Délai de 3min reached : switch to low consumption")
                self.isDriving = false
                self.stopHighPrecisionGPS() // GSP high precision stop (baterry saving)
                self.stopTimer = nil
                self.endBackgroundTask()

                // vibrate once end of high precision GPS tracking
                if debugMode 
                    { self.triggerVibration(double: false) }
            }
        }
    }

    private func cancelStopTimer() {
        stopTimer?.invalidate()
        stopTimer = nil
        endBackgroundTask()
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
            self.locationManager.distanceFilter = 10 // no location update if move is less than 10m
            self.locationManager.allowsBackgroundLocationUpdates = true
            if #available(iOS 11.0, *) {
                self.locationManager.showsBackgroundLocationIndicator = true
            }
            self.locationManager.startUpdatingLocation()
        }
    }

    private func stopHighPrecisionGPS() {
        DispatchQueue.main.async {
            // 🔋 Instead of stop  : switch to low precision to save battery
            // but stay awake for nex activity change
            self.locationManager.desiredAccuracy = kCLLocationAccuracyThreeKilometers 
            self.locationManager.distanceFilter = 99999
            
            if #available(iOS 11.0, *) {
                self.locationManager.showsBackgroundLocationIndicator = false
            }
            // do not stop AllowsBackgroundLocationUpdates to keep monitoring alive
        }
    }

    // MARK: - Plugin Methods (Interface JS)
    @objc public func startTracking(_ call: CAPPluginCall) {

        self.debugMode = call.getBool("debug") ?? false

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
        // 1. Activity permission Verification
        var activityStatus = "prompt"
        if #available(iOS 11.0, *) {
            switch CMMotionActivityManager.authorizationStatus() {
            case .authorized:
                activityStatus = "granted"
            case .denied, .restricted:
                activityStatus = "denied"
            case .notDetermined:
                activityStatus = "prompt"
            @unknown default:
                activityStatus = "prompt"
            }
        }

        // 2. Location permission verification
        var locationStatus: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            locationStatus = locationManager.authorizationStatus
        } else {
            locationStatus = CLLocationManager.authorizationStatus()
        }

        var locationResult = "prompt"
        switch locationStatus {
        case .authorizedAlways:
            locationResult = "granted"
        case .authorizedWhenInUse:
            // Pour ton app, "Always" est préférable, mais on peut considérer "WhenInUse" 
            // comme accordé pour le tracking immédiat.
            locationResult = "authorizedWhenInUse" 
        case .denied, .restricted:
            locationResult = "denied"
        case .notDetermined:
            locationResult = "prompt"
        @unknown default:
            locationResult = "prompt"
        }

        // 3. Retour des résultats à Capacitor
        call.resolve([
            "activity": activityStatus,
            "location": locationResult
        ])
    }

    @objc public override func requestPermissions(_ call: CAPPluginCall) {
        // 1. Demande pour le mouvement (CoreMotion)
        // CoreMotion n'a pas de méthode "request" explicite, 
        // l'alerte s'affiche automatiquement au premier 'startActivityUpdates'
        
        // 2. Demande pour la localisation
        DispatchQueue.main.async {
            let status: CLAuthorizationStatus
            if #available(iOS 14.0, *) {
                status = self.locationManager.authorizationStatus
            } else {
                status = CLLocationManager.authorizationStatus()
            }

            if status == .notDetermined {
                // Première demande : on demande "Toujours"
                // Note: iOS affichera d'abord une alerte "Pendant l'utilisation"
                self.locationManager.requestAlwaysAuthorization()
            } else if status == .authorizedWhenInUse {
                // Si déjà autorisé "Pendant", on tente de promouvoir vers "Toujours"
                self.locationManager.requestAlwaysAuthorization()
            }
            
            // On répond à Capacitor que la demande est lancée
            call.resolve(["location": "requested"])
        }
    }

    // MARK: - Delegate Location
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // On enregistre si on conduit OU si on est dans la période de grâce des 3 min
        guard isDriving, let location = locations.last else { return }
        
        // Filtre de précision (ignore les points trop imprécis > 100m)
        if location.horizontalAccuracy > 100 { return }

        let newPoint: [String: Any] = [
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "speed": location.speed, // Utile pour tes futures analyses
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