

## Capacitor Activity Plugin (SmartPilot)

A high-performance Capacitor plugin for Android and iOS that detects transportation modes (specifically driving) and records GPS tracks automatically in the background.

## 🚀 Features

 - Automotive Detection: Automatically detects when the user starts and
   stops driving.
 - Background GPS Tracking: High-precision GPS recording while driving.
 - 3-Minute Grace Period: Logic to prevent track splitting during short
   stops (traffic lights, gas stations).
 - Offline Storage: Stores locations in a local JSON file.
 - Native Sharing: Built-in method to share the recorded JSON file via
   the native system sheet.
 - Debug Mode: Optional haptic feedback (vibrations) when activity
   transitions occur.

## 📦 Installation

    npm install activity-plugin
    npx cap sync

**Android Configuration**
In your AndroidManifest.xml, add the following permissions:

    <uses-permission android:name="android.permission.INTERNET" /> 
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /> 
    <uses-permission `android:name="android.permission.ACCESS_COARSE_LOCATION" /> 
    <uses-permission` android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /> 
    <uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" /> 
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" /> 
    <uses-permission android:name="android.permission.VIBRATE" />

**iOS Configuration**
In your Info.plist, add these keys with appropriate descriptions:

 - NSLocationAlwaysAndWhenInUseUsageDescription
 - NSLocationWhenInUseUsageDescription
 -  NSMotionUsageDescription
 - UIBackgroundModes: Include location and fetch.

## 🛠 Usage
**1. Check & Request Permissions**
Before starting, ensure the user has granted the necessary permissions.

    import { ActivityRecognition } from 'activity-plugin';
    
    const checkStatus = async () => {
      const perm = await ActivityRecognition.checkPermissions();
      if (perm.location !== 'granted') {
        await ActivityRecognition.requestPermissions();
      }
    };

**2. Start Tracking**
Start the activity detection. Pass debug: true to enable vibration feedback.

    // Double vibration = Driving started
    // Single vibration = Potential stop detected (3min timer starts)
    await ActivityRecognition.startTracking({ debug: true });

**3. Handle Events**
Listen for real-time updates in your UI.

    ActivityRecognition.addListener('onLocationUpdate', (data) => {
      console.log('New GPS point:', data.lat, data.lng);
    });
    
    ActivityRecognition.addListener('activityChange', (data) => {
      console.log('Activity type:', data.activity); // e.g., 'automotive', 'walking'
    });

**4. Manage Data**
Export or clear your recorded tracks.

    // Share the JSON file via native share sheet
    await ActivityRecognition.shareSavedLocations();
    
    // Clear the local storage
    await ActivityRecognition.clearSavedLocations();

## **📐 Background Logic Details**

The 3-Minute Timer
To ensure continuous tracks, the plugin uses a specialized background task:

 - Enter Automotive: High-precision GPS starts immediately.
 - Exit Automotive: A 3-minute timer starts.
 - If user returns to driving before 3 minutes: The timer is canceled,
   and recording continues.
 - If 3 minutes pass: GPS is switched to low-power mode to save battery
   until the next drive is detected.

## **📄 Data Format**

The stored stored_locations.json follows this structure:

    JSON
    [
      {
        "type": "location",
        "lat": 48.8566,
        "lng": 2.3522,
        "speed": 13.5,
        "date": "2026-01-29 12:00:00",
        "timestamp": 1738152000
      }
    ]

activityChange send to app

    JSON
    [
      {
        "activity": 'walking' | 'running' | 'cycling' | 'automotive' | 'stationary' | 'unknown',
        "transition": 'ENTER' | 'EXIT',
      }
    ]


onLocationUpdate send to app

    JSON
    [
      {
        
      }
    ]




## **🔍 Troubleshooting**

|Issue | Potential Cause|Solution|
|--------------------------------|--|--|
|No locations recorded| Permissions set to "While Using Application" |User must change Location to "Always" in System Settings|
|Tracking stops after 5 min|Battery Optimization (Android)|Disable "Battery Optimization" for the app in Android Settings|
|No Activity detected|Motion Sensors disabled|Ensure NSMotionUsageDescription is accepted (iOS) or Physical Activity permission is granted (Android)|
|Vibrations not working|Debug mode / Silent mode|Ensure { debug: true } is passed and the phone is not in "Do Not Disturb" mode|

## 🔋 Battery Optimization Strategy

This plugin is designed to minimize battery drain while remaining highly responsive to movement.

**1) Passive Monitoring:** 
When the user is stationary, the plugin uses the Significant Location Change (iOS) and Activity Recognition Transition API (Android). These APIs consume almost 0% battery.

**2) Adaptive Accuracy:**
 - Driving: High-precision GPS updates every 10 meters.
 - Grace Period (3 min): High-precision remains active to prevent data gaps during stops.
 - Stationary/Walking: Accuracy drops to "low precision" (3km) to allow the GPS chip to sleep.

**3) Haptic Feedback:** 
Vibrations are only triggered in debug mode to ensure the vibration motor doesn't waste energy during normal production use.

## 🔧 API Reference

    //Initializes the Activity Recognition engine.
    // debug: If true, the device will vibrate twice on driving start and once on driving stop.
    startTracking(options?: { debug?: boolean })
    
    // Returns a Promise with the array of all points stored in the internal JSON file.
    getSavedLocations()
    
    /* Opens the system share sheet. 
    On iOS, it uses UIActivityViewController. 
    On Android, it uses FileProvider to securely share the stored_locations.json.*/
    shareSavedLocations()
    

