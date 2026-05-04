import Foundation
import Capacitor
import CoreMotion
import CoreLocation
import UIKit
import AudioToolbox

@objc(ActivityRecognitionPlugin)
public class ActivityRecognitionPlugin: CAPPlugin, CLLocationManagerDelegate {

    private var saveOnlyAutomotive = true
    private var isTrackingAutomotiveForSAve: Bool = false

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
    private var hasPerformedFirstPurgeInSession = false

    private var lastSavedActivityType: String? = nil
    private var lastActivityForExit: String? = nil
    private var lastDebouncedType: String? = nil
    private var lastActivityChangeTime: Date = Date()
    private var lastLoggedActivities: [String: Date] = [:]

    private let activityLock = NSLock()
    private let syncLock = NSLock()
    private let fileAccessLock = NSLock()

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

    //
    // restore location tracking state after restart or swipe app
    //
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

    //
    // Helper Date
    //
    private func getFormattedDate() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.string(from: Date())
    }

    //
    // Centraliseed Logic for Activity detection
    //
    private func startActivityDetection() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }
        
        activityManager.startActivityUpdates(to: .main) { [weak self] (activity) in
            guard let self = self, let activity = activity else { return }
            
            // All managed by handleActivityUpdate .
            self.handleActivityUpdate(activity)
        }
    }


    //
    // Vibrate function use in debug to inform start / Stop automotive
    //
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


    //
    // Handle update of user activity
    //
    private func handleActivityUpdate(_ activity: CMMotionActivity) {
        activityLock.lock()
        defer { activityLock.unlock() }

        let currentType = getActivityName(activity)
        let now = Date()

        // if walking but not currently driving set GPS in low consumption to enable automotive detection faster
        if activity.walking && !self.isDriving {
            // maintain sensor awake for driving dtection
            self.locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
            self.locationManager.startUpdatingLocation()
        }
    
        // SECURITY ignore same activity as previous
        if currentType == self.lastSavedActivityType { return }

        // "STICKY" LOGIC while driving
        // if currently driving and receive stationary, save event but keep on Driving mode
        if self.isDriving && (currentType == "stationary" || activity.walking){
            print("⏳ Red light or small stop or walking (for small stop): stationary saved, GPS remains.")
            // save stationary ENTER but keep isDriving

            self.logToFile("Activitée reçue: stationary ENTER")

            let data = formatActivityData(type: "stationary", transition: "ENTER")
            saveLocationToJSON(data)
            self.notifyListeners("activityChange", data: data)
            
            self.lastSavedActivityType = currentType
            return
        }

        // 3. Activity detection to activate driving
        let isMovingFast = (locationManager.location?.speed ?? 0) > 5.0
        if activity.automotive || isMovingFast {
            self.lastAutomotiveDate = now
            

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
            }
        }

        // 4. TRANSITION  (EXIT OLD / ENTER NEW)
        if let old = self.lastSavedActivityType {

            self.logToFile("Activitée reçue: " + old + " EXIT")

            let exitData = formatActivityData(type: old, transition: "EXIT")
            saveLocationToJSON(exitData)
            self.notifyListeners("activityChange", data: exitData)
        }

        self.lastSavedActivityType = currentType

        self.logToFile("Activitée reçue: " + currentType + " ENTER")

        let enterData = formatActivityData(type: currentType, transition: "ENTER")
        saveLocationToJSON(enterData)
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


    //
    // Helpers for Date 
    //
    private func getFormattedDateFrom(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.string(from: date)
    }

    //
    // format activity name (to be same in iOS and Android)
    //
    private func getActivityName(_ activity: CMMotionActivity) -> String {
        if activity.automotive { return "automotive" }
        if activity.walking { return "walking" }
        if activity.running { return "running" }
        if activity.cycling { return "cycling" }
        if activity.stationary { return "stationary" }
        return "unknown"
    }


    //
    // - GPS Management, start high precision
    //
    private func startHighPrecisionGPS() {
        let status = CLLocationManager.authorizationStatus()
        if status == .authorizedAlways || status == .authorizedWhenInUse {
            DispatchQueue.main.async {
                self.locationManager.desiredAccuracy = kCLLocationAccuracyBest
                self.locationManager.distanceFilter = kCLDistanceFilterNone // no filtering
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


    //
    // - GPS Management, stop high precision
    //
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

    //
    // - Plugin Methods (JS Interfaces), StartTracking (set automatic recording of trips)
    // 
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

        // wake up app when cell change
        locationManager.startMonitoringSignificantLocationChanges()

        // wake up app when visited place change
        self.locationManager.startMonitoringVisits()
        call.resolve()
    }


    //
    // - Plugin Methods (JS Interfaces), stopTracking (stop automatic recording of trips)
    // 
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


    //
    // - Plugin Methods (JS Interfaces), getSavedLocations (get all recording event and location from saving file)
    // 
    @objc public func getSavedLocations(_ call: CAPPluginCall) {
        let points = loadStoredPoints()
        call.resolve(["locations": points])
    }


    //
    // - Plugin Methods (JS Interfaces), clearSavedLocations (delete event/position saving file)
    // 
    @objc public func clearSavedLocations(_ call: CAPPluginCall) {
        try? FileManager.default.removeItem(at: getFilePath())
        call.resolve()
    }


    //
    // - Plugin Methods (JS Interfaces), checkPermissions status to verify if all ok
    // 
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


    //
    // - Plugin Methods (JS Interfaces), requestPermissions to enable all permission for the plugin to run
    // 
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


    //
    // - locationManager strategy management
    // 
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let now = Date()

        let age = abs(location.timestamp.timeIntervalSinceNow)

        // 1. Detection BOOSTER (speed > 14 km/h)
        // Precision (<= 20m) prevent bad GPS position during fix
        if location.speed > 4 && location.horizontalAccuracy <= 20 && age < 30 { 
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

                self.logToFile("Activitée reçue: automotive ENTER")

                saveLocationToJSON(enterData)
                self.notifyListeners("activityChange", data: enterData)
                
                startHighPrecisionGPS()
            }
        }

        // 2. Refresh weather if already driving
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

        self.logToFile("Position reçue")

        saveLocationToJSON(newPoint)
        self.notifyListeners("onLocationUpdate", data: newPoint)
    }

   
    //
    // Process end of trip
    // 
    private func forceStopDriving() {
        // 0. lock to prevent // processing
        activityLock.lock()
        guard self.isDriving else { 
            activityLock.unlock()
            return 
        }
        self.isDriving = false
        activityLock.unlock()

        print("🛑 forceStopDriving : Processing end of trip...")

        // 1. Set automotive EXIT

        self.logToFile("Activité reçue: automotive EXIT")

        let exitData = formatActivityData(type: "automotive", transition: "EXIT")
        self.saveLocationToJSON(exitData)
        
        // 2. Start stationnary

        self.logToFile("Activité reçue: stationary ENTER")

        let enterData = formatActivityData(type: "stationary", transition: "ENTER")
        self.saveLocationToJSON(enterData)

        // 3. update states
        self.lastAutomotiveDate = nil
        self.lastSavedActivityType = "stationary"
        self.lastActivityChangeTime = Date()
        UserDefaults.standard.set(false, forKey: kIsDrivingState)

        // 4. free ressources
        self.stopHighPrecisionGPS()
        self.lastWeatherLocation = nil 
        self.lastWeatherFetchDate = nil
        
        // 5. Notifications
        self.notifyListeners("activityChange", data: exitData)
        if debugMode { self.triggerVibration(double: false) }

        // 6. upload with small delay
        DispatchQueue.global(qos: .background).asyncAfter(deadline: .now() + 0.5) {
            // TEST VERSION COMMENT BEGIN
            self.processAndUploadAutomotiveTripsOnly()
            // TEST VERSION COMMENT END
        }
        
        print("✅ End of driving logic completed")
    }

    //
    // Saving to JSON
    //
    private func saveLocationToJSON(_ locationData: [String: Any]) {

        if self.saveOnlyAutomotive == true
        {
            let type = locationData["type"] as? String ?? ""
            let activity = locationData["activity"] as? String ?? ""
            let transition = locationData["transition"] as? String ?? ""

            // --- 1. FILTRE DE SÉCURITÉ ---
            
            if type == "activity" {
                // only automotiv managed as activity
                if activity != "automotive" {
                    return 
                }

                if transition == "ENTER" {
                    self.isTrackingAutomotiveForSAve = true
                } else if transition == "EXIT" {
                    // EXIT automotiv is done but perpare end fater
                    defer { self.isTrackingAutomotiveForSAve = false }
                }
            } else if type == "location" {
                // poisition saved only in automotive
                if !self.isTrackingAutomotiveForSAve {
                    return
                }
            } else {
                // other type no saved
                return
            }
        }

        fileAccessLock.lock()
        defer { fileAccessLock.unlock() }

        let fileURL = getFilePath()
        
        // 1. Sérialisation en Data (Binaire)
        guard var jsonData = try? JSONSerialization.data(withJSONObject: locationData, options: []) else {
            print("❌ Erreur de sérialisation pour le point GPS")
            return
        }
        
        // 2. Ajout du saut de ligne (\n) directement en binaire (0x0A)
        jsonData.append(0x0A)

        // 3. Écriture physique sur le disque
        if FileManager.default.fileExists(atPath: fileURL.path) {
            // Le fichier existe, on ouvre un "FileHandle" pour ajouter à la fin (Append)
            if let fileHandle = try? FileHandle(forWritingTo: fileURL) {
                fileHandle.seekToEndOfFile()
                fileHandle.write(jsonData)
                fileHandle.closeFile()
            } else {
                print("❌ Impossible d'ouvrir le fichier pour ajout")
            }
        } else {
            // Premier point : on crée le fichier avec une écriture atomique pour la sécurité
            do {
                try jsonData.write(to: fileURL, options: .atomic)
            } catch {
                print("❌ Erreur lors de la création du fichier : \(error)")
            }
        }
    }

    //
    // load points from file
    //
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

    //
    // retrieve file path
    //
    private func getFilePath() -> URL {
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(fileName)
    }

    //
    // - Plugin Methods (JS Interfaces), shareSavedLocations share event / location file through nativ UI
    // 
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


    //
    // - Plugin Methods (JS Interfaces), purgeLocationsBefore purge begining of file when processed for upload
    // 
    @objc func purgeLocationsBefore(_ call: CAPPluginCall) {
        // Supporte 'timestamp' ou 'before' pour être flexible
        let timestamp = call.getDouble("timestamp") ?? call.getDouble("before")
        
        if let ts = timestamp {
            self.performPurgeBefore(timestamp: ts)
            call.resolve()
        } else {
            call.reject("Parameter 'timestamp' is mandatory")
        }
    }

    //
    // - Plugin Methods (JS Interfaces), purgeLocationsBetween purge event/location between trips
    // 
    @objc func purgeLocationsBetween(_ call: CAPPluginCall) {
        let from = call.getDouble("from")
        let to = call.getDouble("to")
        
        if let f = from, let t = to {
            self.performPurgeBetween(from: f, to: t)
            call.resolve()
        } else {
            call.reject("Parameters 'from' and 'to' are mandatory")
        }
    }


    //
    // - Process purgeLocationsBefore purge begining of file when processed for upload
    // 
    private func performPurgeBefore(timestamp: Double) {
        fileAccessLock.lock()
        defer { fileAccessLock.unlock() }
        
        let fileURL = getFilePath()
        var allEntries = self.loadLocationsFromFile(url: fileURL)
        
        let countBefore = allEntries.count
        // Utilisation d'une conversion sécurisée Double(truncating:) ou cast via NSNumber
        allEntries = allEntries.filter { entry in
            let entryTs = (entry["timestamp"] as? NSNumber)?.doubleValue ?? 0.0
            return entryTs >= timestamp
        }
        
        self.saveLocationsToFile(allEntries, url: fileURL)
        print("🧹 Purge Before: \(countBefore - allEntries.count) éléments supprimés.")
    }

    //
    // - Process performPurgeBetween purge event/location between trips
    // 
    private func performPurgeBetween(from: Double, to: Double) {
        fileAccessLock.lock()
        defer { fileAccessLock.unlock() }
        
        let fileURL = getFilePath()
        var allEntries = self.loadLocationsFromFile(url: fileURL)
        
        let countBefore = allEntries.count
        allEntries = allEntries.filter { entry in
            let entryTs = (entry["timestamp"] as? NSNumber)?.doubleValue ?? 0.0
            return entryTs < from || entryTs > to
        }
        
        self.saveLocationsToFile(allEntries, url: fileURL)
        print("🧹 Purge Between: \(countBefore - allEntries.count) éléments supprimés.")
    }


    //
    // - Process saveLocationsToFile 
    // 
    private func saveLocationsToFile(_ locations: [[String: Any]], url: URL) {
        var combinedString = ""
        for location in locations {
            if let data = try? JSONSerialization.data(withJSONObject: location, options: []),
               let jsonString = String(data: data, encoding: .utf8) {
                combinedString += jsonString + "\n" // Important : le \n pour le format JSON Lines
            }
        }
        
        do {
            try combinedString.write(to: url, atomically: true, encoding: .utf8)
        } catch {
            print("❌ iOS: Erreur sauvegarde locations : \(error.localizedDescription)")
        }
    }

    //
    // - Plugin Methods (JS Interfaces), Sync trips from js
    //
    @objc public func forceUpload(_ call: CAPPluginCall) {
        syncLock.lock()
        if syncInProgress {
            syncLock.unlock()
            print("⚠️ Sync already running. Ignoring JS call.")
            call.resolve(["status": "already_syncing"])
            return
        }

        syncInProgress = true
        syncLock.unlock()

        print("📲 Force upload triggered from JS")
        
        self.executeInternalSync {
            call.resolve(["status": "upload_completed"])
        }
    }

    //
    // sync entry called only on end of trip
    //
    func processAndUploadAutomotiveTripsOnly() {
        syncLock.lock()
        if syncInProgress {
            syncLock.unlock()
            return
        }
        syncInProgress = true
        syncLock.unlock()
        
        self.executeInternalSync(completion: nil)
    }

    //
    // Upload to server process from JS and end of trip entry
    //
    private func executeInternalSync(completion: (() -> Void)? = nil) {
        // 1. Background Task
        var bgTask: UIBackgroundTaskIdentifier = .invalid
        bgTask = UIApplication.shared.beginBackgroundTask {
            UIApplication.shared.endBackgroundTask(bgTask)
            bgTask = .invalid
        }

        let fileURL = getFilePath()

        // 2. Lecture (Phase critique)
        fileAccessLock.lock()
        let allEntries = self.loadLocationsFromFile(url: fileURL)
        fileAccessLock.unlock()

        guard !allEntries.isEmpty else {
            self.releaseSync()
            UIApplication.shared.endBackgroundTask(bgTask)
            completion?()
            return
        }

        // 3. Segmentation Look-ahead
        let FIVE_MINUTES_MS: Double = 5 * 60 * 1000
        let MIN_DURATION_MS: Double = 30 * 1000
        var finishedTrips = [[[String: Any]]]()
        var currentMeasures = [[String: Any]]()
        var isTrackingAutomotive = false

        for (index, entry) in allEntries.enumerated() {
            let type = entry["type"] as? String ?? ""
            let activity = entry["activity"] as? String ?? ""
            let transition = entry["transition"] as? String ?? ""
            let timestamp = (entry["timestamp"] as? NSNumber)?.doubleValue ?? 0

            if type == "activity" && activity == "automotive" {
                if transition == "ENTER" && !isTrackingAutomotive {
                    isTrackingAutomotive = true
                    currentMeasures = []
                }

                if transition == "EXIT" && isTrackingAutomotive {
                    var hasReEntered = false
                    if index < allEntries.count - 1 {
                        for j in (index + 1)..<allEntries.count {
                            let next = allEntries[j]
                            if ((next["timestamp"] as? Double ?? 0) - timestamp) > FIVE_MINUTES_MS { break }
                            if (next["type"] as? String) == "activity" && (next["activity"] as? String) == "automotive" && (next["transition"] as? String) == "ENTER" {
                                hasReEntered = true
                                break
                            }
                        }
                    }

                    if !hasReEntered {
                        let firstTs = (currentMeasures.first?["timestamp"] as? Double) ?? 0
                        let lastTs = (currentMeasures.last?["timestamp"] as? Double) ?? 0
                        let duration = lastTs - firstTs
                        
                        // process distance
                        let totalDistance = self.calculateTripDistance(points: currentMeasures)
                        
                        // CRITÈRES : + de 1 min ET + de 1000m
                        if duration >= 60000 && totalDistance >= 1000 {
                            if isTripSignificant(trip: currentMeasures) {
                                finishedTrips.append(currentMeasures)
                            }
                        } else {
                
                            print("⚠️ iOS: Ttrip too small (\(Int(totalDistance))m), purged immediatly.")
                            self.performPurgeBetween(from: firstTs - 1, to: lastTs + 1)
                        }
                    }
                    isTrackingAutomotive = false
                    currentMeasures = []
                    }
                }
            

            if type == "location" && isTrackingAutomotive {
                var point: [String: Any] = [
                    "lat": entry["lat"] ?? 0.0,
                    "lng": entry["lng"] ?? 0.0,
                    "speed": entry["speed"] ?? 0.0,
                    "timestamp": timestamp,
                    "type": "location"
                ]
                
                if let temp = entry["weather_temp"] { point["weather_temp"] = temp }
                if let wType = entry["weather_type"] { point["weather_type"] = wType }
                
                currentMeasures.append(point)
            }
        }

        // 4. Lancement de l'Upload séquentiel avec purge intégrée
        self.uploadTripsSequentially(tripsToUpload: finishedTrips) {
            // Callback final une fois que tous les trajets de la file sont traités
            self.releaseSync()
            UIApplication.shared.endBackgroundTask(bgTask)
            bgTask = .invalid
            completion?()
        }
    }


    private func calculateTripDistance(points: [[String: Any]]) -> Double {
        var totalDistance: Double = 0.0
        var lastLoc: CLLocation?

        for pt in points {
            if let lat = pt["lat"] as? Double, let lng = pt["lng"] as? Double {
                let currentLoc = CLLocation(latitude: lat, longitude: lng)
                if let last = lastLoc {
                    totalDistance += currentLoc.distance(from: last)
                }
                lastLoc = currentLoc
            }
        }
        return totalDistance
    }

    //
    // Load location from file as json string
    //
    private func loadLocationsFromFile(url: URL) -> [[String: Any]] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        
        do {
            let data = try String(contentsOf: url, encoding: .utf8)
            let lines = data.components(separatedBy: .newlines)
            
            return lines.compactMap { line in
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty { return nil }
                
                guard let lineData = trimmed.data(using: .utf8),
                      let json = try? JSONSerialization.jsonObject(with: lineData) as? [String: Any] else {
                    print("⚠️ corrupted JSON line ignored")
                    return nil
                }
                
                // Verify mandatory fields exixt
                if json["type"] as? String == "location" {
                    if json["lat"] == nil || json["lng"] == nil || json["timestamp"] == nil {
                        print("⚠️ bad GPS Point value --> ignored")
                        return nil
                    }
                }
                return json
            }
        } catch {
            print("❌ file read error: \(error.localizedDescription)")
            return []
        }
    }


    //
    // release sync, utils
    //
    private func releaseSync() {
        self.syncLock.lock()
        self.syncInProgress = false
        self.syncLock.unlock()
    }


    //
    // remove pedestian segments, check pedestrian start (currently not used)
    //
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


    //
    // remove pedestian segments, check pedestrian end (currently not used)
    //
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

   

    //
    // check if trip must be kept or not
    //
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


    //
    // Loops inside trips array to send them to server
    //
    private func uploadTripsSequentially(tripsToUpload: [[[String: Any]]], index: Int = 0, completion: @escaping () -> Void) {
    
        if index == 0 { self.hasPerformedFirstPurgeInSession = false }

        // all trips processed?
        guard index < tripsToUpload.count else {
            completion()
            return
        }

        let trip = tripsToUpload[index]
        
        // purge timeStamp info
        let tripStart = trip.first?["timestamp"] as? Double ?? 0
        let tripEnd = trip.last?["timestamp"] as? Double ?? 0

        // --- APPEL API NATIF ---
        self.uploadTripToServer(points: trip) { success in
            if success {
                print("✅ iOS: Upload done for trip \(index + 1)")
                
                if !self.hasPerformedFirstPurgeInSession {
                    // first trip clean all before
                    self.performPurgeBefore(timestamp: tripEnd + 1)
                    self.hasPerformedFirstPurgeInSession = true
                } else {
                    // begin file already purged purge between now.
                    self.performPurgeBetween(from: tripStart - 1, to: tripEnd + 1)
                }
            } else {
                print("❌ iOS: Échec trajet \(index + 1), conservé dans le fichier.")
            }
            
            // loop all trips
            self.uploadTripsSequentially(tripsToUpload: tripsToUpload, index: index + 1, completion: completion)
        }
    }


    //
    // upload on trip to server to server
    //
    func uploadTripToServer(points: [[String: Any]], completion: @escaping (Bool) -> Void) {
    
        // 1. Récupération sécurisée des credentials
        let token = self.groupId ?? UserDefaults.standard.string(forKey: "group_id_token")
        let urlString = self.serverUrl ?? UserDefaults.standard.string(forKey: "trip_server_url")

        guard let urlStr = urlString, let url = URL(string: urlStr), let jwt = token else {
            print("❌ Impossible d'envoyer : Token ou URL introuvable.")
            completion(false)
            return
        }

        // 2. Préparation et NETTOYAGE du payload
        let measures = points.compactMap { (dict) -> [String: Any]? in
            // On vérifie que c'est une location et que les types de base sont OK
            guard let type = dict["type"] as? String, type == "location" else { return nil }
            
            // Extraction sécurisée avec valeurs par défaut
            let lat = dict["lat"] as? Double ?? 0.0
            let lng = dict["lng"] as? Double ?? 0.0
            let speed = dict["speed"] as? Double ?? 0.0
            let timestamp = (dict["timestamp"] as? NSNumber)?.int64Value ?? 0
            
            // --- LE BLINDAGE : Validation mathématique ---
            // JSONSerialization crash sur NaN ou Infinity (souvent générés par le GPS si signal faible)
            guard lat.isFinite, lng.isFinite, speed.isFinite else {
                print("⚠️ Point ignoré : Valeur mathématique invalide (NaN/Inf)")
                return nil
            }
            
            // On reconstruit un dictionnaire propre
            var cleanPoint: [String: Any] = [
                "lat": lat,
                "lng": lng,
                "speed": max(0, speed), // Pas de vitesse négative
                "timestamp": timestamp
            ]
            
            // Ajout optionnel de la météo uniquement si les types sont corrects
            if let temp = dict["weather_temp"] as? Double, temp.isFinite { cleanPoint["weather_temp"] = temp }
            if let wType = dict["weather_type"] as? String { cleanPoint["weather_type"] = wType }
            
            return cleanPoint
        }

        if measures.isEmpty {
            print("ℹ️ Aucun point GPS valide après nettoyage. Purge du fichier.")
            completion(true)
            return
        }

        let payload: [String: Any] = ["measures": measures]

        // 3. Sérialisation avec gestion d'erreur explicite
        let jsonData: Data
        do {
            jsonData = try JSONSerialization.data(withJSONObject: payload, options: [])
        } catch {
            print("❌ CRITICAL : Échec sérialisation malgré le nettoyage : \(error.localizedDescription)")
            // Si ça échoue encore, il y a un type non supporté dans le dictionnaire.
            completion(false) 
            return
        }

        // --- Log de debug ---
        if let jsonString = String(data: jsonData, encoding: .utf8) {
            let logMsg = jsonString.count > 500 ? "\(jsonString.prefix(250))..." : jsonString
            print("📤 Envoi vers serveur (\(measures.count) points) : \(logMsg)")
        }

        // 4. Configuration de la requête
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(jwt)", forHTTPHeaderField: "Authorization")
        request.httpBody = jsonData
        request.timeoutInterval = 30

        // 5. Exécution
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            let success: Bool
            
            if let error = error {
                print("❌ Erreur réseau iOS : \(error.localizedDescription)")
                success = false
            } else if let httpResponse = response as? HTTPURLResponse {
                print("📡 Status Code : \(httpResponse.statusCode)")
                // Succès si 200, 201 ou 204
                success = (200...299).contains(httpResponse.statusCode)
            } else {
                success = false
            }

            DispatchQueue.main.async {
                completion(success)
            }
        }
        task.resume()
    }

    //
    // - Plugin Methods (JS Interfaces), test if plugin set correctly (url/token)
    //
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


    //
    // - Plugin Methods (JS Interfaces), test if syncing in progress
    //
    @objc func isSyncing(_ call: CAPPluginCall) {
        call.resolve([
            "inProgress": self.syncInProgress
        ])
    }


    //
    // fetchWeatherData retrieve weather at a lat lon position
    //
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

    //
    // logToFile only in debug
    //
    func logToFile(_ message: String) {

        // do nothing if not debug mode
        guard self.debugMode else {return}

        let timestamp = DateFormatter.localizedString(from: Date(), dateStyle: .short, timeStyle: .medium)
        let logEntry = "\(timestamp) : \(message)\n"
        
        guard let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let fileURL = documentsDirectory.appendingPathComponent("debug_log.txt")
        
        // --- Auto-vide : Si le fichier dépasse 1 Mo (1 048 576 octets) ---
        if FileManager.default.fileExists(atPath: fileURL.path) {
            if let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
               let fileSize = attributes[.size] as? UInt64,
               fileSize > 1024 * 1024 {
                
                // On supprime et on repart à zéro
                try? FileManager.default.removeItem(at: fileURL)
                let resetNote = "--- Log reset (Taille max atteinte) ---\n"
                try? resetNote.write(to: fileURL, atomically: true, encoding: .utf8)
            }
        }
        
        // --- Écriture du log (Append) ---
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            try? logEntry.write(to: fileURL, atomically: true, encoding: .utf8)
        } else {
            if let fileHandle = try? FileHandle(forWritingTo: fileURL) {
                fileHandle.seekToEndOfFile()
                if let data = logEntry.data(using: .utf8) {
                    fileHandle.write(data)
                }
                fileHandle.closeFile()
            }
        }
        
        print("DEBUG: \(message)") // Toujours visible dans Xcode
    }

    //
    // queryPastActivities
    // Query phone activity manager that could have event not managed by app during sleep
    // 

    private func queryPastActivities(from startDate: Date, to endDate: Date) {
        self.logToFile("🔍 Interrogation de l'historique : \(startDate) -> \(endDate)")
        
        activityManager.queryActivityStarting(from: startDate, to: endDate, to: .main) { (activities, error) in
            guard let activities = activities, error == nil else {
                self.logToFile("❌ Erreur queryActivity : \(error?.localizedDescription ?? "unknown")")
                return
            }

            for activity in activities {
                // On ne traite que les segments significatifs (Automotive ou High Speed)
                if activity.automotive {
                    let timestamp = activity.startDate.timeIntervalSince1970 * 1000
                    let data = [
                        "type": "activity",
                        "activity": "automotive",
                        "transition": "ENTER", // On simule un ENTER rétrospectif
                        "date": self.getFormattedDateFrom(activity.startDate),
                        "timestamp": Int64(timestamp),
                        "isBackfill": true
                    ] as [String : Any]
                    
                    self.logToFile("⏪ Point historique retrouvé : Automotive à \(data["date"]!)")
                    //self.saveLocationToJSON(data)
                }
            }
        }
    }

}