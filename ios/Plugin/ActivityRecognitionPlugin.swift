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
            print("🔄 Réactivation suite à redémarrage système/swipe")
            startActivityDetection()
            
            // Si on était en train de rouler, on relance le GPS haute précision direct
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
            
            let previousActivity = self.lastSavedActivityType
            self.handleActivityUpdate(activity)
            
            if self.lastSavedActivityType != previousActivity {
                let data = self.formatActivityData(activity)

                self.saveLocationToJSON(point: data)

                self.notifyListeners("activityChange", data: data)
            }
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

        let currentType = getActivityName(activity)

        // Security Check: if GPS speed says moving, "stationary" is ignored
        let isPhysicallyMoving = (locationManager.location?.speed ?? 0) > 5.0 // > 18 km/h

        // do not record if same activity as prévious one
        if currentType == lastSavedActivityType {
            return 
        }

        // register activity for next round
        self.lastSavedActivityType = currentType

        if (activity.automotive || isPhysicallyMoving) {
            // On rafraîchit le timestamp dès qu'on est en voiture
            lastAutomotiveDate = Date()
            
            if !self.isDriving {
                print("🚗 Automotive START")
                self.isDriving = true
                UserDefaults.standard.set(true, forKey: kIsDrivingState)
                self.startHighPrecisionGPS()
                if let lastKnownLocation = self.locationManager.location {
                    // On passe le point actuel dans un tableau pour le traitement
                    self.processRembobinage(locations: [lastKnownLocation], startDate: activity.startDate)
                }
                if debugMode { self.triggerVibration(double: true) }
            }
        } else if activity.walking || activity.stationary || activity.cycling {
            // On ne fait rien ici ! C'est le locationManager qui gérera le timeout
            // Cela évite de lancer un Timer qui sera tué par iOS.
            if self.isDriving {
                print("🚶 Transition détectée : Le GPS surveille maintenant le timeout de 3min")
            }
        }
    }

    
    private func processRembobinage(locations: [CLLocation], startDate: Date) {
        print("⏪ Tentative de récupération de points depuis : \(startDate)")
        
        for location in locations {
            // On ne garde le point que s'il a été capturé APRÈS le début réel 
            // de l'activité physique (startDate) et s'il est assez précis.
            if location.timestamp >= startDate && location.horizontalAccuracy <= 100 {
                
                let backfillPoint: [String: Any] = [
                    "lat": location.coordinate.latitude,
                    "lng": location.coordinate.longitude,
                    "speed": location.speed,
                    "date": getFormattedDateFrom(location.timestamp), // Utilise la date du point, pas "maintenant"
                    "timestamp": location.timestamp.timeIntervalSince1970 * 1000,
                    "isBackfill": true // Flag utile pour ton JS
                ]
                
                print("📍 Point de rembobinage enregistré (\(location.timestamp))")
                saveLocationToJSON(point: backfillPoint)
            }
        }
    }

    // MARK: - Helpers Date améliorés
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
            // Arrêt complet du moteur de localisation pour économiser la batterie
            self.locationManager.stopUpdatingLocation()
            
            if #available(iOS 11.0, *) {
                self.locationManager.showsBackgroundLocationIndicator = false
            }
            print("🔋 GPS Haute précision arrêté")
        }
    }

    // MARK: - Plugin Methods (Interface JS)
    @objc public func startTracking(_ call: CAPPluginCall) {
        UserDefaults.standard.set(true, forKey: kIsTrackingActive)
        self.debugMode = call.getBool("debug") ?? false

        startActivityDetection()
        // Surveillance discrète pour le réveil auto
        locationManager.startMonitoringSignificantLocationChanges()
        call.resolve()
    }

    @objc public func stopTracking(_ call: CAPPluginCall) {
        UserDefaults.standard.set(false, forKey: kIsTrackingActive)
        UserDefaults.standard.set(false, forKey: kIsDrivingState)

        // On nettoie l'état local
        lastAutomotiveDate = nil 
        self.isDriving = false
        
        // On arrête les moteurs
        activityManager.stopActivityUpdates()
        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
        
        locationManager.allowsBackgroundLocationUpdates = false
        
        print("🛑 Tracking totalement arrêté")
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
        guard let location = locations.last else { return }

        // 1. Vérification du délai de grâce (si on ne détecte plus "automotive")
        if let lastAuto = lastAutomotiveDate {
            let timeSinceLastAuto = Date().timeIntervalSince(lastAuto)
            
            if timeSinceLastAuto > stopDelay && self.isDriving {
                print("💤 Timeout 3min atteint : Arrêt du mode conduite")
                self.isDriving = false
                UserDefaults.standard.set(false, forKey: kIsDrivingState)
                self.stopHighPrecisionGPS()
                
                if debugMode { self.triggerVibration(double: false) }
                return // On arrête d'enregistrer
            }
        }

        // 2. Enregistrement normal si on est toujours en mode conduite
        guard isDriving else { return }
        if location.horizontalAccuracy > 100 { return }

        let newPoint: [String: Any] = [
        "lat": location.coordinate.latitude,
        "lng": location.coordinate.longitude,
        "speed": location.speed,
        "date": getFormattedDate(),
        // AJOUTER * 1000 ICI pour être raccord avec Android et ta purge
        "timestamp": location.timestamp.timeIntervalSince1970 * 1000
    ]
        
        saveLocationToJSON(point: newPoint)
        self.notifyListeners("onLocationUpdate", data: newPoint)
    }

    // MARK: - Stockage & JSON
    private func saveLocationToJSON(point: [String: Any]) {
        let url = getFilePath()
        
        // On convertit le dictionnaire en Data (une seule ligne JSON)
        guard let jsonData = try? JSONSerialization.data(withJSONObject: point),
              let jsonString = String(data: jsonData, encoding: .utf8) else { return }
        
        // On ajoute un saut de ligne pour séparer les objets
        let line = jsonString + "\n"
        guard let dataToAppend = line.data(using: .utf8) else { return }

        if FileManager.default.fileExists(atPath: url.path) {
            // Si le fichier existe, on ouvre un handle pour écrire à la fin
            if let fileHandle = try? FileHandle(forWritingTo: url) {
                fileHandle.seekToEndOfFile()
                fileHandle.write(dataToAppend)
                fileHandle.closeFile()
            }
        } else {
            // Sinon, on crée le fichier avec le premier point
            try? dataToAppend.write(to: url, options: .noFileProtection)
        }
    }

    private func loadStoredPoints() -> [[String: Any]] {
        let url = getFilePath()
        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            return []
        }
        
        // On découpe par ligne, on ignore les lignes vides, et on parse chaque ligne en JSON
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
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
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


    @objc public func purgeLocationsBefore(_ call: CAPPluginCall) {
        // Récupération du timestamp limite (en ms) envoyé par le JS
        guard let timestampLimit = call.getDouble("timestamp") else {
            call.reject("Le paramètre 'timestamp' est manquant ou invalide")
            return
        }
        
        let url = getFilePath()
        
        // Si le fichier n'existe pas, il n'y a rien à purger
        guard FileManager.default.fileExists(atPath: url.path) else {
            call.resolve()
            return
        }
        
        do {
            // 1. Lecture du fichier JSONL
            let content = try String(contentsOf: url, encoding: .utf8)
            let lines = content.components(separatedBy: .newlines)
            
            // 2. Filtrage des lignes
            let filteredLines = lines.filter { line in
                // On ignore les lignes vides
                if line.trimmingCharacters(in: .whitespaces).isEmpty { return false }
                
                guard let data = line.data(using: .utf8),
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let pointTimestamp = json["timestamp"] as? Double else {
                    // Si la ligne est corrompue, on ne la garde pas par sécurité
                    return false
                }
                
                // On ne garde que les points PLUS RÉCENTS ou ÉGAUX au timestamp fourni
                return pointTimestamp >= timestampLimit
            }
            
            // 3. Reconstruction du contenu (JSONL)
            // On rejoint les lignes et on s'assure qu'il y a un saut de ligne à la fin s'il y a des données
            let newContent = filteredLines.joined(separator: "\n") + (filteredLines.isEmpty ? "" : "\n")
            
            // 4. Écriture atomique pour éviter de corrompre le fichier en cas de crash
            try newContent.write(to: url, atomically: true, encoding: .utf8)
            
            print("🧹 iOS Purge : \(lines.count - filteredLines.count) points supprimés")
            call.resolve()
            
        } catch {
            call.reject("Erreur lors de la purge : \(error.localizedDescription)")
        }
    }






}