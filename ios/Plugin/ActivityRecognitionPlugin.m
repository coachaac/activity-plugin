#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(ActivityRecognitionPlugin, "ActivityRecognition",
           CAP_PLUGIN_METHOD(startTracking, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(stopTracking, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(checkPermissions, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(requestPermissions, CAPPluginReturnPromise);
)