import Foundation
import Capacitor
import CoreMotion
import CoreLocation
import UIKit
import AudioToolbox

@objc(ActivityRecognitionPlugin)
public class ActivityRecognitionPlugin: CAPPlugin, CLLocationManagerDelegate {

    private var serverUrl: String?
    private var groupId: String?
    
    private let activityManager = CMMotionActivityManager()
    private let locationManager = CLLocationManager()
    private let fileName = "stored_locations.json"
    
    private var isDriving = false
    private var lastAutomotiveDate: Date?
    private let stopDelay: TimeInterval = 180 // 3 minutes
    private var debugMode = false
    private var syncInProgress = false

    private var lastSavedActivityType: String? = nil
    private var lastActivityForExit: String? = nil
    private var lastDebouncedType: String? = nil
    private var lastActivityChangeTime: Date = Date()
    private var lastLoggedActivities: [String: Date] = [:]

    private let activityLock = NSLock()

    private let syncLock = NSLock()

    // Clés pour le stockage
    private let kIsTrackingActive = "tracking_active"
    private let kIsDrivingState = "driving_state"


    private var weatherApiKey: String?
    private var weatherBaseUrl: String?

    private static var lastWeatherCache: [String: Any]? = nil
    private var lastWeatherFetchDate: Date? = nil
    private let weatherUpdateInterval: TimeInterval = 300 // 5 minutes

    private var lastWeatherLocation: CLLocation? = nil
    private let weatherDistanceThreshold: CLLocationDistance = 2000 // 2 km

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
                self.lastAutomotiveDate = Date()
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

    // Centraliseed Logic 
    private func startActivityDetection() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        
        activityManager.startActivityUpdates(to: .main) { [weak self] (activity) in
            guard let self = self, let activity = activity else { return }
            
            // All managed by handleActivityUpdate .
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

                if let currentLoc = locationManager.location {
                    print("🌤️ Automotive detect: triggering initial weather fetch")
                    // Init for didUpdateLocations logic
                    self.lastWeatherLocation = currentLoc
                    self.lastWeatherFetchDate = now

                    self.fetchWeatherData(lat: currentLoc.coordinate.latitude, lon: currentLoc.coordinate.longitude)
                }

                startHighPrecisionGPS()
                activityLock.unlock()
                return
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

        // get debug parameter
        self.debugMode = call.getBool("debug") ?? false

        // get url parameter
        let url = call.getString("url");
        print("🌐 destination url set to : \(url)")

        // get groupId parameter
        let groupId = call.getString("groupId");
        print("🌐 groupId set to : \(groupId)")

        UserDefaults.standard.set(url, forKey: "trip_server_url")
        UserDefaults.standard.set(groupId, forKey: "group_id_token")

        self.serverUrl = url
        self.groupId = groupId

        self.weatherApiKey = call.getString("weatherKey")
        if let customWeatherUrl = call.getString("weatherUrl") {
            self.weatherBaseUrl = customWeatherUrl
        }

        // Persistance pour le redémarrage (Optionnel)
        UserDefaults.standard.set(self.weatherApiKey, forKey: "weather_api_key")
        UserDefaults.standard.set(self.weatherBaseUrl, forKey: "weather_base_url")

        if !CMMotionActivityManager.isActivityAvailable() {
            print("❌ Motion Activity not available on this device")
        }
        
        // Relancer proprement les updates
        activityManager.stopActivityUpdates() // Clean start
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

        self.syncInProgress = false
        
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
            case .authorized: activityStatus = "granted"
            case .denied, .restricted: activityStatus = "denied"
            default: activityStatus = "prompt"
            }
        }

        // 2. Location permission verification
        let checker = CLLocationManager()

        let locationStatus: CLAuthorizationStatus

        if #available(iOS 14.0, *) {
            locationStatus = checker.authorizationStatus
        } else {
            locationStatus = CLLocationManager.authorizationStatus()
        }

        // Mappage pour Capacitor
        var locationResult = "prompt"
        var backgroundStatus = "denied"

        switch locationStatus {
        case .authorizedAlways:
            locationResult = "granted"

            checker.allowsBackgroundLocationUpdates = true
            checker.pausesLocationUpdatesAutomatically = false

            // verify if iOS accept previous configuration 
            if checker.allowsBackgroundLocationUpdates == false {
                backgroundStatus = "denied" 
            } else {
                backgroundStatus = "granted"
            }
        case .authorizedWhenInUse:
            if #available(iOS 14.0, *) {
                locationResult = "granted"
                backgroundStatus = "denied"
            } else {
                locationResult = "granted"
                backgroundStatus = "granted"
            }
            case .denied, .restricted:
                locationResult = "denied"
                backgroundStatus = "denied"
            case .notDetermined:
                locationResult = "prompt"
                backgroundStatus = "prompt"
            @unknown default:
                locationResult = "prompt"
            }

        // 3. Précision (Optionnel mais recommandé pour le GPS)
        var precision = "full"
        if #available(iOS 14.0, *) {
            if checker.accuracyAuthorization == .reducedAccuracy {
                precision = "reduced"
            }
        }

        call.resolve([
            "activity": activityStatus,
            "location": locationResult, // Return "granted" si WhenInUse ou Always
            "backgroundLocation": backgroundStatus, // if denied should request "Always"
            "precision": precision
        ])
    }

    @objc public override func requestPermissions(_ call: CAPPluginCall) {
        let permissions = call.getArray("permissions", String.self) ?? []
        
        DispatchQueue.main.async {
            // --- 1. ACTIVITÉ PHYSIQUE ---
            if permissions.contains("activity") {
                let today = Date()
                self.activityManager.queryActivityStarting(from: today, to: today, to: .main) { (_, _) in }
            }

            // --- 2. LOCALISATION ---
            if permissions.contains("location") || permissions.contains("backgroundLocation") {
                let status: CLAuthorizationStatus
                if #available(iOS 14.0, *) {
                    status = self.locationManager.authorizationStatus
                } else {
                    status = CLLocationManager.authorizationStatus()
                }

                switch status {
                case .notDetermined:
                    // Premier passage : On demande l'autorisation de base
                    self.locationManager.requestAlwaysAuthorization()
                    
                case .authorizedWhenInUse:
                    // DEUXIÈME PASSAGE : L'utilisateur a déjà mis "Pendant l'utilisation"
                    // Apple autorise maintenant l'escalade vers "Toujours"
                    self.locationManager.requestAlwaysAuthorization()
                    
                default:
                    break
                }
            }
            
            call.resolve(["location": "requested", "activity": "requested"])
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let now = Date()

        // 1. Detection BOOSTER (speed > 14 km/h)
        // Precision (<= 20m) prevent bad GPS position during fix
        if location.speed > 4 && location.horizontalAccuracy <= 20 { 
            self.lastAutomotiveDate = now
            
            if !self.isDriving {
                print("🚀 High speed detected (\(location.speed)m/s). Forcing Driving mode.")
                self.isDriving = true
                UserDefaults.standard.set(true, forKey: kIsDrivingState)
                
                // Initialisation immédiate des compteurs météo pour éviter un double appel
                self.lastWeatherLocation = location
                self.lastWeatherFetchDate = now
                fetchWeatherData(lat: location.coordinate.latitude, lon: location.coordinate.longitude)
                
                // Log de l'événement ENTER pour le trajet
                let enterData = formatActivityData(type: "automotive", transition: "ENTER")
                saveLocationToJSON(point: enterData)
                self.notifyListeners("activityChange", data: enterData)
                
                startHighPrecisionGPS()
            }
        }

        // 2. Rfresh weather if already driving
        if self.isDriving {
            // new weather after 10 min
            let weatherTimeThreshold: TimeInterval = 600 
            let timeSinceLastFetch = lastWeatherFetchDate == nil ? weatherTimeThreshold : now.timeIntervalSince(lastWeatherFetchDate!)
            let isExpired = timeSinceLastFetch >= weatherTimeThreshold

            // Or distance >2 km
            let distanceSinceLastFetch = lastWeatherLocation?.distance(from: location) ?? weatherDistanceThreshold
            
            // request if : First time Or Distance or elapse time 
            if lastWeatherFetchDate == nil || distanceSinceLastFetch >= weatherDistanceThreshold || isExpired {
                 self.lastWeatherLocation = location
                 self.lastWeatherFetchDate = now 
                 fetchWeatherData(lat: location.coordinate.latitude, lon: location.coordinate.longitude)
            }
        }

        // 3. WATCHDOG (end trip detection)
        if location.speed > 2.0 {
            self.lastAutomotiveDate = now
        } else if self.isDriving {
            if let lastAuto = lastAutomotiveDate {
                let timeSinceLastAuto = now.timeIntervalSince(lastAuto)
                if timeSinceLastAuto > stopDelay {
                    self.forceStopDriving()
                    return 
                }
            }
        }

        // 4. Save GPS points if driving speed and accuracy correct
        guard isDriving && location.speed >= 0 && location.horizontalAccuracy <= 20 else { return }

        var newPoint: [String: Any] = [
            "type": "location",
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "speed": location.speed,
            "date": getFormattedDate(),
            "timestamp": Int64(location.timestamp.timeIntervalSince1970 * 1000)
        ]

        // add weather from cache (format brief)
        if let weather = ActivityRecognitionPlugin.lastWeatherCache {
            newPoint["weather_temp"] = weather["temp"]
            newPoint["weather_type"] = weather["type"]
        } else {
            print("⏳ Weather not yet available for this point")
        }

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

        // 3. reset internal states
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

        self.processAndUploadAutomotiveTripsOnly()

        self.lastWeatherLocation = nil 
        self.lastWeatherFetchDate = nil
        
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



    //
    // Upload to server from js
    //
    @objc public func forceUpload(_ call: CAPPluginCall) {
        // 1. Protect regarding multiple call to sync process
        guard !syncInProgress else {
            print("⚠️ Upload in progress request ignored.")
            call.resolve([
                "status": "upload_in_progress",
                "message": "Sync in progress."
            ])
            return
        }

        print("📲 Force upload triggered from JS")
        
        // 2. LAunch process as not currently in progress
        self.processAndUploadAutomotiveTripsOnly()
        
        // 3. Send answer to jsJS
        call.resolve([
            "status": "upload_initiated"
        ])
    }

    //
    // Upload to server
    //
    func processAndUploadAutomotiveTripsOnly() {
        syncLock.lock()
        if syncInProgress {
            syncLock.unlock()
            return
        }
        syncInProgress = true
        syncLock.unlock()

        let allEntries = self.getAllStoredLocations()
        guard !allEntries.isEmpty else { 
            self.syncInProgress = false
            return 
        }

        var finishedTrips = [[[String: Any]]]()
        var currentSegment = [[String: Any]]()
        var isInsideAutomotive = false
        var lastProcessedIndex = -1
        
        let GRACE_TIMER_MS: Double = 3 * 60 * 1000 // 3 minutes
        let SPEED_THRESHOLD = 1.7 // ~6 km/h (Vitesse plancher pour maintien)

        for (index, entry) in allEntries.enumerated() {
            let type = entry["type"] as? String ?? ""
            let activity = entry["activity"] as? String ?? ""
            let transition = entry["transition"] as? String ?? ""
            let timestamp = entry["timestamp"] as? Double ?? 0
            let speed = entry["speed"] as? Double ?? 0

            // --- DÉTECTION DU DÉBUT (ENTER) ---
            if type == "activity" && activity == "automotive" && transition == "ENTER" {
                isInsideAutomotive = true
            }

            if isInsideAutomotive {
                currentSegment.append(entry)
            }

            // --- DÉTECTION DE LA FIN (EXIT ou GRACE TIMER) ---
            var shouldCloseTrip = false
            
            // Cas 1 : EXIT explicite
            if type == "activity" && activity == "automotive" && transition == "EXIT" {
                shouldCloseTrip = true
            } 
            // Cas 2 : Grace Timer (On regarde le point suivant s'il existe)
            else if isInsideAutomotive && index < allEntries.count - 1 {
                let nextEntry = allEntries[index + 1]
                let nextTimestamp = nextEntry["timestamp"] as? Double ?? 0
                let gap = nextTimestamp - timestamp
                
                // Si le trou est > 3 min ET que la vitesse actuelle est faible
                if gap > GRACE_TIMER_MS && speed < SPEED_THRESHOLD {
                    shouldCloseTrip = true
                }
            }

            if shouldCloseTrip && !currentSegment.isEmpty {
                // Nettoyage et Validation (Nettoie la marche avant/après)
                var cleaned = trimPedestrianStart(currentSegment, speedThreshold: SPEED_THRESHOLD)
                cleaned = trimPedestrianEnd(cleaned, speedThreshold: SPEED_THRESHOLD)

                if isTripSignificant(trip: cleaned) {
                    finishedTrips.append(cleaned)
                }

                // Reset pour le prochain trajet
                currentSegment = []
                isInsideAutomotive = false
                lastProcessedIndex = index
            }
        }

        // --- GESTION DES RÉSIDUS ---
        var remainingData = [[String: Any]]()
        if lastProcessedIndex < allEntries.count - 1 {
            for i in (lastProcessedIndex + 1)..<allEntries.count {
                remainingData.append(allEntries[i])
            }
        }

        // Upload
        self.uploadTripsSequentially(tripsToUpload: finishedTrips, remainingData: remainingData) {
            self.syncLock.lock()
            self.syncInProgress = false
            self.syncLock.unlock()
        }
    }


    private func trimPedestrianStart(_ trip: [[String: Any]], speedThreshold: Double) -> [[String: Any]] {
        var firstMotorizedIndex = -1

        // Find the first point where we are actually moving or in automotive mode
        for (index, step) in trip.enumerated() {
            let activity = step["activity"] as? String ?? ""
            let speed = step["speed"] as? Double ?? 0.0
            let type = step["type"] as? String ?? ""

            let isAutomotive = activity == "automotive"
            let isFast = type == "location" && speed > speedThreshold

            if isAutomotive || isFast {
                firstMotorizedIndex = index
                break
            }
        }

        if firstMotorizedIndex != -1 {
            // We take from the motorized point (back up 1 for start context if possible)
            let n_rec = 0
            let startIndex = max(0, firstMotorizedIndex - n_rec)
            return Array(trip[startIndex..<trip.count])
        }
        
        return [] // Nothing motorized found
    }

    private func trimPedestrianEnd(_ trip: [[String: Any]], speedThreshold: Double) -> [[String: Any]] {
        var lastMotorizedIndex = -1

        // Reverse search from the end
        for (index, step) in trip.enumerated().reversed() {
            let activity = step["activity"] as? String ?? ""
            let speed = step["speed"] as? Double ?? 0.0
            let type = step["type"] as? String ?? ""

            if activity == "automotive" || (type == "location" && speed > speedThreshold) {
                lastMotorizedIndex = index
                break
            }
        }

        if lastMotorizedIndex != -1 {
            let endIndex = min(trip.count, lastMotorizedIndex + 2)
            return Array(trip[0..<endIndex])
        }
        
        return trip
    }

   

    private func saveRemainingDataOnly(_ currentTrip: [[String: Any]], otherTrips: [[[String: Any]]]) {

        let fileURL = getFilePath()
        
        // aggregate all remaining data 
        var allPointsToKeep = [[String: Any]]()
        
        // 1. add trip not already uploaded
        for trip in otherTrips {
            allPointsToKeep.append(contentsOf: trip)
        }
        
        // 2. Add current trip (no EXIT yet)
        allPointsToKeep.append(contentsOf: currentTrip)

        if allPointsToKeep.isEmpty {
            try? FileManager.default.removeItem(at: fileURL)
            return
        }

        var jsonlString = ""

        // Rewrite JSONL 
        for point in allPointsToKeep {
            if let jsonData = try? JSONSerialization.data(withJSONObject: point, options: []),
               let jsonString = String(data: jsonData, encoding: .utf8) {
                jsonlString += jsonString + "\n"
            }
        }

        do {
            // atomic write to prevent corrupted file true, encoding: .utf8)
            try jsonlString.write(to: fileURL, atomically: true, encoding: .utf8)
            print("💾 local file updated : \(allPointsToKeep.count) remaing points.")
        } catch {
            print("❌ Error while writting file : \(error)")
        }
    }


    private func isTripSignificant(trip: [[String: Any]]) -> Bool {
        
        var totalDistance: Double = 0
        var lastLocation: CLLocation?

        // 1. Distance
        for step in trip {
            guard let type = step["type"] as? String, type == "location",
                  let lat = step["lat"] as? Double,
                  let lng = step["lng"] as? Double else { continue }
            
            let currentLocation = CLLocation(latitude: lat, longitude: lng)
            
            if let previous = lastLocation {
                totalDistance += currentLocation.distance(from: previous)
            }
            lastLocation = currentLocation
        }

        // 2. Duration
        let startTime = trip.first?["timestamp"] as? Double ?? 0
        let endTime = trip.last?["timestamp"] as? Double ?? 0
        let durationSeconds = (endTime - startTime) / 1000

        print("🛣️ Trajet réel: \(Int(totalDistance))m, Durée: \(Int(durationSeconds))s")

        //  > 500m  and > 60s
        let isSignificant = (totalDistance > 500 && durationSeconds > 60)
        if !isSignificant {
            print("🗑️ trip ignored as non significant.")
        }
        return isSignificant
    }

    private func uploadTripsSequentially(tripsToUpload: [[[String: Any]]], remainingData: [[String: Any]], completion: @escaping () -> Void) {
        
        var tripsToRetry = [[[String: Any]]]() // keep only those who need a retry
        var queue = tripsToUpload

        func uploadNext() {
            guard !queue.isEmpty else {
                // uploads trys : failed and trip in progress
                self.finalizeLocalStorage(failedTrips: tripsToRetry, remainingData: remainingData)
                completion()
                return
            }

            let trip = queue.removeFirst()
            
            // --- API Upload call ---
            self.uploadTripToServer(points: trip) { success in
                if !success {
                    print("❌ Trip upload failed, keeping in local")
                    tripsToRetry.append(trip) // retry next time
                } else {
                    print("✅ Trip upload success")
                }
                uploadNext()
            }
        }

        uploadNext()
    }

    private func finalizeLocalStorage(failedTrips: [[[String: Any]]], remainingData: [[String: Any]]) {
        var dataToKeep = [[String: Any]]()
        
        // 1. On remet les trajets qui ont échoué à l'upload
        for trip in failedTrips {
            dataToKeep.append(contentsOf: trip)
        }
        
        // 2. On ajoute les points du trajet en cours (ou non stabilisé)
        dataToKeep.append(contentsOf: remainingData)

        let fileURL = getFilePath()
        
        if dataToKeep.isEmpty {
            try? FileManager.default.removeItem(at: fileURL)
            print("🗑️ Fichier purgé (tout est envoyé)")
        } else {
            var jsonlString = ""
            for point in dataToKeep {
                if let jsonData = try? JSONSerialization.data(withJSONObject: point),
                   let jsonString = String(data: jsonData, encoding: .utf8) {
                    jsonlString += jsonString + "\n"
                }
            }
            // Écriture atomique : Swift écrit dans un fichier temporaire puis remplace l'ancien
            // C'est très sûr contre les corruptions.
            try? jsonlString.write(to: fileURL, atomically: true, encoding: .utf8)
            print("💾 Fichier mis à jour : \(dataToKeep.count) points conservés.")
        }
    }

    func uploadTripToServer(points: [[String: Any]], completion: @escaping (Bool) -> Void) {
    
        // 1. Récupération des credentials (mémoire ou UserDefaults)
        let token = self.groupId ?? UserDefaults.standard.string(forKey: "group_id_token")
        let urlString = self.serverUrl ?? UserDefaults.standard.string(forKey: "trip_server_url")

        guard let urlStr = urlString, let url = URL(string: urlStr), let jwt = token else {
            print("❌ Impossible d'envoyer : Token ou URL introuvable.")
            completion(false)
            return
        }

        // 2. Préparation du payload {"measures": [...]}
        // On ne garde que les points GPS, on ignore les événements d'activité
        let measures = points.compactMap { (dict) -> [String: Any]? in
            guard let type = dict["type"] as? String, type == "location" else { return nil }
            return [
                "lat": dict["lat"] ?? 0.0,
                "lng": dict["lng"] ?? 0.0,
                "speed": dict["speed"] ?? 0.0,
                "timestamp": dict["timestamp"] ?? 0
            ]
        }

        // Si après filtrage le trajet est vide, on considère l'envoi "réussi" pour purger le fichier
        if measures.isEmpty {
            print("ℹ️ Aucun point GPS trouvé dans ce segment (uniquement activité). Envoi ignoré.")
            completion(true)
            return
        }

        // Structure finale identique à Android
        let payload: [String: Any] = ["measures": measures]

        // 3. Sérialisation JSON et Log limité pour Xcode
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
            print("❌ Erreur de sérialisation JSON")
            completion(false)
            return
        }

        let jsonString = String(data: jsonData, encoding: .utf8) ?? ""
        if jsonString.count > 500 {
            let start = String(jsonString.prefix(250))
            let end = String(jsonString.suffix(100))
            print("📤 iOS JSON (Large - \(jsonString.count) chars): \(start) ... [TRUNCATED] ... \(end)")
        } else {
            print("📤 iOS JSON: \(jsonString)")
        }

        // 4. Configuration de la requête HTTP
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(jwt)", forHTTPHeaderField: "Authorization")
        request.httpBody = jsonData
        request.timeoutInterval = 30 // Sécurité réseau

        // 5. Exécution de l'envoi
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                print("❌ Erreur réseau iOS : \(error.localizedDescription)")
                completion(false)
                return
            }

            if let httpResponse = response as? HTTPURLResponse {
                print("📡 iOS Response Code: \(httpResponse.statusCode)")
                completion((200...299).contains(httpResponse.statusCode))
            } else {
                completion(false)
            }
        }
        task.resume()
    }

    private func getAllStoredLocations() -> [[String: Any]] {
        let fileURL = getFilePath() // Utilise ta méthode existante getFilePath()
        var locations: [[String: Any]] = []
        
        // On tente de lire le contenu du fichier
        guard let content = try? String(contentsOf: fileURL, encoding: .utf8) else {
            return []
        }
        
        // On découpe par ligne (format JSONL)
        let lines = content.components(separatedBy: .newlines)
        for line in lines where !line.isEmpty {
            if let data = line.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                locations.append(json)
            }
        }
        return locations
    }


    private func clearLocalJSON() {
        let url = getFilePath()
        try? FileManager.default.removeItem(at: url)
        print("🗑️ Local JSON file cleared after successful upload.")
    }


    @objc func testSettings(_ call: CAPPluginCall) {
        // 1. Récupération des valeurs stockées que le plugin utilise réellement
        let urlString = UserDefaults.standard.string(forKey: "trip_server_url") ?? ""
        let groupId = UserDefaults.standard.string(forKey: "group_id_token") ?? ""

        guard let url = URL(string: urlString), !urlString.isEmpty else {
            call.reject("URL non configurée ou invalide")
            return
        }

        // 2. Création d'une requête de test (HEAD est plus rapide qu'un GET)
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 5.0 // Timeout court pour un test de santé
        
        if !groupId.isEmpty {
            // Adapté selon si ton serveur attend le token en Header ou en paramètre
            request.setValue("Bearer \(groupId)", forHTTPHeaderField: "Authorization")
        }

        let task = URLSession.shared.dataTask(with: request) { (_, response, error) in
            if let error = error {
                call.resolve([
                    "status": "error",
                    "message": error.localizedDescription,
                    "url": urlString,
                    "groupId": groupId
                ])
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                call.resolve([
                    "status": httpResponse.statusCode == 200 ? "ok" : "error",
                    "statusCode": httpResponse.statusCode,
                    "url": urlString,
                    "groupId": groupId
                ])
            }
        }
        task.resume()
    }

    @objc func isSyncing(_ call: CAPPluginCall) {
        call.resolve([
            "inProgress": self.syncInProgress
        ])
    }

    // Helper pour ton service de synchro
    func setSyncStatus(_ status: Bool) {
        self.syncInProgress = status
    }


    private func fetchWeatherData(lat: Double, lon: Double) {
        // 1. Fetch only if necessary
        if let lastFetch = lastWeatherFetchDate, Date().timeIntervalSince(lastFetch) < weatherUpdateInterval {
            return
        }

        // 2. Récupération robuste des clés (UserDefaults + Fallback)
        let apiKey = UserDefaults.standard.string(forKey: "weather_api_key") ?? self.weatherApiKey
        let baseUrl = UserDefaults.standard.string(forKey: "weather_base_url") ?? self.weatherBaseUrl
        
        guard let key = apiKey, let base = baseUrl else {
            print("❌ iOS Weather config missing")
            return
        }

        let urlString = "\(base)?lat=\(lat)&lon=\(lon)&units=metric&appid=\(key)"
        guard let url = URL(string: urlString) else { return }

        let task = URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            // 3. Vérification du statut HTTP
            guard let data = data, error == nil,
                  let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
                print("❌ iOS Weather API Error or Invalid Status")
                return 
            }

            do {
                if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    
                    var brief: [String: Any] = [:]
                    
                    // Extraction Température
                    if let main = json["main"] as? [String: Any], let temperature = main["temp"] as? Double {
                        brief["temp"] = temperature
                    }

                    // Extraction Condition
                    if let weatherArray = json["weather"] as? [[String: Any]],
                       let first = weatherArray.first,
                       let conditionId = first["id"] as? Int {
                        
                        var condition = "sunny"
                        switch conditionId {
                        case 200...299, 900...902: condition = "stormy"
                        case 300...599: condition = "rainy"
                        case 600...699: condition = "snowy"
                        case 700...799: condition = "foggy"
                        case 800...899: condition = "sunny"
                        default: condition = "sunny"
                        }
                        brief["type"] = condition
                    }

                    // 4. Mise à jour Cache et Date
                    ActivityRecognitionPlugin.lastWeatherCache = brief
                    self?.lastWeatherFetchDate = Date()
                    print("🌦️ iOS Weather Cache Updated: \(brief)")
                }
            } catch {
                print("❌ iOS Weather Parsing Error")
            }
        }
        task.resume()
    }
}