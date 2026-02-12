import Foundation
import Capacitor
import CoreMotion
import CoreLocation
import UIKit
import AudioToolbox

@objc(ActivityRecognitionPlugin)
public class ActivityRecognitionPlugin: CAPPlugin, CLLocationManagerDelegate {
    
    private let activityManager = CMMotionActivityManager()
    private let locationManager = CLLocationManager()
    private let fileName = "stored_locations.json"
    
    private var isDriving = false
    private var lastAutomotiveDate: Date?
    private let stopDelay: TimeInterval = 180 // 3 minutes
    private var debugMode = false

    private var lastSavedActivityType: String? = nil
    private var lastActivityForExit: String? = nil
    private var lastDebouncedType: String? = nil
    private var lastActivityChangeTime: Date = Date()
    private var lastLoggedActivities: [String: Date] = [:]

    private let activityLock = NSLock()

    // Clés pour le stockage
    private let kIsTrackingActive = "tracking_active"
    private let kIsDrivingState = "driving_state"

    @objc public override func load() {
        locationManager.delegate = self
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.allowsBackgroundLocationUpdates = true
        
        // On vérifie si l'utilisateur avait activé le tracking avant le crash/swipe
        let wasTracking = UserDefaults.standard.bool(forKey: kIsTrackingActive)
        let wasDriving = UserDefaults.standard.bool(forKey: kIsDrivingState)

        if wasTracking {
            print("🔄 Re-activate following phone restart ort App swipe")
            startActivityDetection()
            
            // if was running restore highPrecision GPS
            if wasDriving {
                self.isDriving = true
                startHighPrecisionGPS()
            }
        }

        // if app relaunched after location event
        if let _ = UserDefaults.standard.object(forKey: kIsTrackingActive) as? Bool {
            // App is launched by iOS in background if killed even if monitoring was activated
            locationManager.startMonitoringSignificantLocationChanges()
        }
    }

    // Helper Date
    private func getFormattedDate() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.string(from: Date())
    }

    // CEntraliseed Logic 
    private func startActivityDetection() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        
        activityManager.startActivityUpdates(to: .main) { [weak self] (activity) in
            guard let self = self, let activity = activity else { return }
            
            // Tout se passe à l'intérieur de handleActivityUpdate maintenant.
            // On ne fait plus de notification ou d'écriture ici !
            self.handleActivityUpdate(activity)
        }
    }

    private func triggerVibration(double: Bool) {
        // Vibration système (fonctionne en background et téléphone verrouillé)
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        
        if double {
            // Un petit délai pour simuler une double vibration
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            }
        }
    }

    private func handleActivityUpdate(_ activity: CMMotionActivity) {
        activityLock.lock()
        defer { activityLock.unlock() }

        let currentType = getActivityName(activity)
        let now = Date()
        
        // 1. Duplicate management
        if currentType == self.lastSavedActivityType { return }

        // 2. "STICKY" LOGIC while driving
        // if currently driving and receive stationary, save event but keep on Driving mode
        if self.isDriving && currentType == "stationary" {
            print("⏳ Red light or small stop : stationary saved, GPS remains.")
            // save stationary ENTER but keep isDriving
            let data = formatActivityData(type: "stationary", transition: "ENTER")
            saveLocationToJSON(point: data)
            self.notifyListeners("activityChange", data: data)
            
            self.lastSavedActivityType = currentType
            return
        }

        // 3. Activity detection toi activate driving
        let isMovingFast = (locationManager.location?.speed ?? 0) > 5.0
        if activity.automotive || isMovingFast {
            self.lastAutomotiveDate = now
            
            if self.isDriving && self.lastSavedActivityType == "stationary" {
                let exitData = formatActivityData(type: "stationary", transition: "EXIT")
                saveLocationToJSON(point: exitData)
                self.notifyListeners("activityChange", data: exitData)
                // ENTER automotive manage in the following
            }

            if !self.isDriving {
                print("🚗 Automotive START")
                self.isDriving = true
                UserDefaults.standard.set(true, forKey: kIsDrivingState)
                startHighPrecisionGPS()
            }
        }

        // 4. TRANSITION  (EXIT OLD / ENTER NEW)
        let oldType = self.lastSavedActivityType
        self.lastSavedActivityType = currentType

        if let old = oldType {
            let exitData = formatActivityData(type: old, transition: "EXIT")
            saveLocationToJSON(point: exitData)
            self.notifyListeners("activityChange", data: exitData)
        }

        let enterData = formatActivityData(type: currentType, transition: "ENTER")
        saveLocationToJSON(point: enterData)
        self.notifyListeners("activityChange", data: enterData)
    }

    // --- 3. Format entry ---
    private func formatActivityData(type: String, transition: String) -> [String: Any] {
        return [
            "type": "activity",
            "activity": type,
            "transition": transition,
            "date": getFormattedDate(),
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ]
    }

    
    private func processRembobinage(locations: [CLLocation], startDate: Date) {
        print("⏪ try to get position before : \(startDate)")
        
        for location in locations {
            // keep only point with timestamp after real activity start and precision ok 
            if location.timestamp >= startDate && location.horizontalAccuracy <= 20 {
                
                let backfillPoint: [String: Any] = [
                    "type": "location",
                    "lat": location.coordinate.latitude,
                    "lng": location.coordinate.longitude,
                    "speed": location.speed,
                    "date": getFormattedDateFrom(location.timestamp),
                    "timestamp": location.timestamp.timeIntervalSince1970 * 1000,
                    "isBackfill": true 
                ]
                
                print("📍 save restored point (\(location.timestamp))")
                saveLocationToJSON(point: backfillPoint)
            }
        }
    }

    // MARK: - Helpers Date 
    private func getFormattedDateFrom(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.string(from: date)
    }


    private func getActivityName(_ activity: CMMotionActivity) -> String {
        if activity.automotive { return "automotive" }
        if activity.walking { return "walking" }
        if activity.running { return "running" }
        if activity.cycling { return "cycling" }
        if activity.stationary { return "stationary" }
        return "unknown"
    }



    // MARK: - GPS Management
    private func startHighPrecisionGPS() {
        let status = CLLocationManager.authorizationStatus()
        if status == .authorizedAlways || status == .authorizedWhenInUse {
            DispatchQueue.main.async {
                self.locationManager.desiredAccuracy = kCLLocationAccuracyBest
                self.locationManager.distanceFilter = 5 // no location update if move is less than 5m
                self.locationManager.allowsBackgroundLocationUpdates = true
                self.locationManager.activityType = .automotiveNavigation
                self.locationManager.pausesLocationUpdatesAutomatically = false

                if #available(iOS 11.0, *) {
                    self.locationManager.showsBackgroundLocationIndicator = true
                }
                self.locationManager.startUpdatingLocation()
            }
        }
    }

    private func stopHighPrecisionGPS() {
        DispatchQueue.main.async {
            // battery saving stop location engine
            self.locationManager.stopUpdatingLocation()
            
            if #available(iOS 11.0, *) {
                self.locationManager.showsBackgroundLocationIndicator = false
            }
            print("🔋 High Precision GPS stop")
        }
    }

    // MARK: - Plugin Methods (JS Interfaces)
    @objc public func startTracking(_ call: CAPPluginCall) {
        UserDefaults.standard.set(true, forKey: kIsTrackingActive)
        self.debugMode = call.getBool("debug") ?? false

        startActivityDetection()
        locationManager.startMonitoringSignificantLocationChanges()
        call.resolve()
    }

    @objc public func stopTracking(_ call: CAPPluginCall) {
        UserDefaults.standard.set(false, forKey: kIsTrackingActive)
        UserDefaults.standard.set(false, forKey: kIsDrivingState)

        // reset state
        lastAutomotiveDate = nil 
        self.isDriving = false
        
        // Stop engines
        activityManager.stopActivityUpdates()
        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
        
        locationManager.allowsBackgroundLocationUpdates = false
        
        print("🛑 All Trackings stopped")
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
        // 1. CoreMotion
        // CoreMotion no request method, 
        // Automatic alert on first request 'startActivityUpdates'
        
        // 2. Ask for location
        DispatchQueue.main.async {
            let status: CLAuthorizationStatus
            if #available(iOS 14.0, *) {
                status = self.locationManager.authorizationStatus
            } else {
                status = CLLocationManager.authorizationStatus()
            }

            if status == .notDetermined {
                // First request, ask  "Always"
                // Note: iOS display first "during use"
                self.locationManager.requestAlwaysAuthorization()
            } else if status == .authorizedWhenInUse {
                // if currently "during use", request "Always"
                self.locationManager.requestAlwaysAuthorization()
            }
            
            // answer to capacitor that Request done
            call.resolve(["location": "requested"])
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }

        if location.speed > 2.0 {
            self.lastAutomotiveDate = Date()
        }
        // --- Exit WATCHDOG Logic ---
        if self.isDriving {
            if let lastAuto = lastAutomotiveDate {
                let timeSinceLastAuto = Date().timeIntervalSince(lastAuto)
                if timeSinceLastAuto > stopDelay {
                    self.forceStopDriving()
                    return
                }
            }
        }

        // --- Save Location ---
        guard isDriving && location.speed >= 0 && location.horizontalAccuracy <= 20 else { return }

        let newPoint: [String: Any] = [
            "type": "location",
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "speed": location.speed,
            "date": getFormattedDate(),
            "timestamp": location.timestamp.timeIntervalSince1970 * 1000
        ]
        
        saveLocationToJSON(point: newPoint)
        self.notifyListeners("onLocationUpdate", data: newPoint)
    }

    // Proicess end
    private func forceStopDriving() {
        print("🛑 forceStopDriving : End of trp conirmed (3min Timeout)")

        // 1. Save EXIT automotive in JSON
        let exitData = formatActivityData(type: "automotive", transition: "EXIT")
        self.saveLocationToJSON(point: exitData)
        self.notifyListeners("activityChange", data: exitData)

        // 2. Save ENTER in next state (stationary)
        let enterData = formatActivityData(type: "stationary", transition: "ENTER")
        self.saveLocationToJSON(point: enterData)
        self.notifyListeners("activityChange", data: enterData)

        // 3. reset interna states
        self.isDriving = false
        self.lastAutomotiveDate = nil
        self.lastSavedActivityType = "stationary"
        self.lastActivityChangeTime = Date()

        // 4. Persist status (recover reboot/swipe)
        UserDefaults.standard.set(false, forKey: kIsDrivingState)

        // 5. Stop GPS to save battery
        self.stopHighPrecisionGPS()
        
        // 6. User Feedback in debug mode
        if debugMode { 
            self.triggerVibration(double: false) 
        }
        
        print("✅ End of driving")
    }

    // MARK: - Saving & JSON
    private func saveLocationToJSON(point: [String: Any]) {
        let url = getFilePath()
        
        // convert dictionnary to Data (one line JSON)
        guard let jsonData = try? JSONSerialization.data(withJSONObject: point),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }
        
        // add EOF to separate trips
        let line = jsonString + "\n"
        guard let dataToAppend = line.data(using: .utf8) else { return }

        if FileManager.default.fileExists(atPath: url.path) {
            // if file exixts open handle to write at the end
            if let fileHandle = try? FileHandle(forWritingTo: url) {
                fileHandle.seekToEndOfFile()
                fileHandle.write(dataToAppend)
                fileHandle.closeFile()
            }
        } else {
            // else create and write
            try? dataToAppend.write(to: url, options: .noFileProtection)
        }
    }

    private func loadStoredPoints() -> [[String: Any]] {
        let url = getFilePath()
        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            return []
        }
        
        // parsing JSON
        let lines = content.components(separatedBy: .newlines)
        return lines.compactMap { line in
            guard !line.isEmpty,
                  let data = line.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return nil
            }
            return json
        }
    }

    private func getFilePath() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(fileName)
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
                    call.reject("file is empty")
                }
            } else {
                print("❌ file not found at path given.")
                call.reject("file not found, register positions first")
            }
        }
    }


    @objc public func purgeLocationsBefore(_ call: CAPPluginCall) {
        // Récupération du timestamp limite (en ms) envoyé par le JS
        guard let timestampLimit = call.getDouble("timestamp") else {
            call.reject("Le paramètre 'timestamp' est manquant ou invalide")
            return
        }
        
        let url = getFilePath()
        
        // no more file nothing to purge
        guard FileManager.default.fileExists(atPath: url.path) else {
            call.resolve()
            return
        }
        
        do {
            // 1. Read JSONL file
            let content = try String(contentsOf: url, encoding: .utf8)
            let lines = content.components(separatedBy: .newlines)
            
            // 2. Filter lines
            let filteredLines = lines.filter { line in
                // empty lines ignored
                if line.trimmingCharacters(in: .whitespaces).isEmpty { return false }
                
                guard let data = line.data(using: .utf8),
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let pointTimestamp = json["timestamp"] as? Double else {
                    // bad line discarded (security)
                    return false
                }
                
                // keep more recent or equal timestamp
                return pointTimestamp >= timestampLimit
            }
            
            // 3. Rebuild content (JSONL)
            let newContent = filteredLines.joined(separator: "\n") + (filteredLines.isEmpty ? "" : "\n")
            
            // 4. atomic writing to prevent file corruption in case of crash
            try newContent.write(to: url, atomically: true, encoding: .utf8)
            
            print("🧹 iOS Purge : \(lines.count - filteredLines.count) points supprimés")
            call.resolve()
            
        } catch {
            call.reject("Error during purge : \(error.localizedDescription)")
        }
    }



}