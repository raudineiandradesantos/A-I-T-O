package com.aito.screencapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class ScreenCaptureModule(reactContext: ReactApplicationContext) : 
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {
    
    companion object {
        private const val TAG = "ScreenCaptureModule"
        private const val REQUEST_MEDIA_PROJECTION = 1000
        const val NAME = "ScreenCaptureModule"
        
        // Android 14 = API 34 (UPSIDE_DOWN_CAKE)
        private const val ANDROID_14_API_LEVEL = 34
    }
    
    private var pendingPromise: Promise? = null
    private val projectionManager: MediaProjectionManager
    
    init {
        reactContext.addActivityEventListener(this)
        projectionManager = reactContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    override fun getName(): String = NAME
    
    /**
     * Request permission for screen capture with app selection on Android 14+
     * On Android 14+, user can choose to share only a specific app window
     * On older versions, falls back to full screen capture
     */
    @ReactMethod
    fun requestPermission(promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            Log.e(TAG, "Activity is null, cannot request permission")
            promise.reject("ERROR", "Activity is null")
            return
        }
        
        if (pendingPromise != null) {
            Log.w(TAG, "Permission request already in progress")
            promise.reject("ERROR", "Permission request already in progress")
            return
        }
        
        pendingPromise = promise
        
        try {
            // On Android 14+ (API 34), allow user to select specific app
            val captureIntent = if (Build.VERSION.SDK_INT >= ANDROID_14_API_LEVEL) {
                Log.d(TAG, "Android 14+ detected, using app selection mode")
                createAppSelectionIntent()
            } else {
                Log.d(TAG, "Android < 14, using full screen capture mode")
                projectionManager.createScreenCaptureIntent()
            }
            
            activity.startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
            Log.d(TAG, "MediaProjection permission dialog launched")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permission: ${e.message}", e)
            pendingPromise = null
            promise.reject("ERROR", "Failed to request permission: ${e.message}")
        }
    }
    
    /**
     * Create intent for app selection on Android 14+
     */
    @RequiresApi(ANDROID_14_API_LEVEL)
    private fun createAppSelectionIntent(): Intent {
        val config = MediaProjectionConfig.createConfigForUserChoice()
        return projectionManager.createScreenCaptureIntent(config)
    }

    /**
     * Create intent for entire screen capture on Android 14+
     */
    @RequiresApi(ANDROID_14_API_LEVEL)
    private fun createEntireScreenIntent(): Intent {
        val config = MediaProjectionConfig.createConfigForDefaultDisplay()
        return projectionManager.createScreenCaptureIntent(config)
    }
    
    /**
     * Request permission for ENTIRE SCREEN capture (no app selection)
     * On Android 14+, uses createConfigForDefaultDisplay() to FORCE entire screen option only
     * This hides the "A single app" dropdown completely
     */
    @ReactMethod
    fun requestEntireScreenPermission(promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            Log.e(TAG, "Activity is null, cannot request permission")
            promise.reject("ERROR", "Activity is null")
            return
        }
        
        if (pendingPromise != null) {
            Log.w(TAG, "Permission request already in progress")
            promise.reject("ERROR", "Permission request already in progress")
            return
        }
        
        pendingPromise = promise
        
        try {
            // On Android 14+, use createConfigForDefaultDisplay() to force entire screen only
            // This removes the "A single app" option entirely
            val captureIntent = if (Build.VERSION.SDK_INT >= ANDROID_14_API_LEVEL) {
                Log.d(TAG, "Android 14+: Using createConfigForDefaultDisplay() to force entire screen")
                createEntireScreenIntent()
            } else {
                Log.d(TAG, "Android < 14: Using standard createScreenCaptureIntent()")
                projectionManager.createScreenCaptureIntent()
            }
            
            activity.startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
            Log.d(TAG, "MediaProjection permission dialog launched (entire screen mode)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permission: ${e.message}", e)
            pendingPromise = null
            promise.reject("ERROR", "Failed to request permission: ${e.message}")
        }
    }
    
    /**
     * Get Android version info
     */
    @ReactMethod
    fun getAndroidVersion(promise: Promise) {
        val sdkVersion = Build.VERSION.SDK_INT
        val supportsAppSelection = sdkVersion >= ANDROID_14_API_LEVEL
        
        Log.d(TAG, "Android SDK: $sdkVersion, Supports app selection: $supportsAppSelection")
        
        promise.resolve(Arguments.createMap().apply {
            putInt("sdkVersion", sdkVersion)
            putBoolean("supportsAppSelection", supportsAppSelection)
            putString("androidVersion", Build.VERSION.RELEASE)
        })
    }
    
    @ReactMethod
    fun startCapture(options: ReadableMap?, promise: Promise) {
        try {
            val context = reactApplicationContext
            
            if (ScreenCaptureService.resultData == null) {
                promise.reject("ERROR", "MediaProjection permission not granted. Call requestPermission first.")
                return
            }
            
            // Set capture interval if provided
            if (options?.hasKey("intervalMs") == true) {
                ScreenCaptureService.captureIntervalMs = options.getInt("intervalMs").toLong()
            }
            
            // Set crop region if provided (for targeting specific area)
            if (options?.hasKey("cropRegion") == true) {
                val cropRegion = options.getMap("cropRegion")
                if (cropRegion != null) {
                    val x = cropRegion.getInt("x")
                    val y = cropRegion.getInt("y")
                    val width = cropRegion.getInt("width")
                    val height = cropRegion.getInt("height")
                    ScreenCaptureService.cropRegion = Rect(x, y, x + width, y + height)
                    Log.d(TAG, "Crop region set: x=$x, y=$y, w=$width, h=$height")
                }
            } else {
                ScreenCaptureService.cropRegion = null
            }
            
            // Set frame callback
            ScreenCaptureService.onFrameCaptured = { imagePath ->
                sendEvent("onFrameCaptured", Arguments.createMap().apply {
                    putString("imagePath", imagePath)
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                })
            }
            
            // Start the service
            val serviceIntent = Intent(context, ScreenCaptureService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "Screen capture started")
            promise.resolve(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }
    
    /**
     * Set crop region for capturing only part of the screen
     */
    @ReactMethod
    fun setCropRegion(x: Int, y: Int, width: Int, height: Int, promise: Promise) {
        try {
            ScreenCaptureService.cropRegion = Rect(x, y, x + width, y + height)
            Log.d(TAG, "Crop region updated: x=$x, y=$y, w=$width, h=$height")
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }
    
    /**
     * Clear crop region (capture full screen)
     */
    @ReactMethod
    fun clearCropRegion(promise: Promise) {
        ScreenCaptureService.cropRegion = null
        Log.d(TAG, "Crop region cleared")
        promise.resolve(true)
    }
    
    @ReactMethod
    fun stopCapture(promise: Promise) {
        try {
            val context = reactApplicationContext
            
            val serviceIntent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(serviceIntent)
            
            // Full Reset
            ScreenCaptureService.onFrameCaptured = null
            ScreenCaptureService.resultData = null
            ScreenCaptureService.resultCode = Activity.RESULT_CANCELED
            ScreenCaptureService.mediaProjection = null
            ScreenCaptureService.cropRegion = null
            pendingPromise = null
            
            Log.d(TAG, "Screen capture fully stopped and state reset")
            promise.resolve(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun resetState(promise: Promise) {
        try {
            ScreenCaptureService.resultData = null
            ScreenCaptureService.resultCode = Activity.RESULT_CANCELED
            ScreenCaptureService.mediaProjection = null
            pendingPromise = null
            Log.d(TAG, "Native state force-reset")
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }
    
    @ReactMethod
    fun isCapturing(promise: Promise) {
        promise.resolve(ScreenCaptureService.mediaProjection != null)
    }
    
    @ReactMethod
    fun checkOverlayPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            promise.resolve(Settings.canDrawOverlays(reactApplicationContext))
        } else {
            promise.resolve(true)
        }
    }
    
    @ReactMethod
    fun requestOverlayPermission() {
        val activity = reactApplicationContext.currentActivity ?: return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${reactApplicationContext.packageName}")
            )
            activity.startActivity(intent)
        }
    }
    
    private fun sendEvent(eventName: String, params: com.facebook.react.bridge.WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }
    
    // ActivityEventListener methods
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, hasData=${data != null}")
            
            when (resultCode) {
                Activity.RESULT_OK -> {
                    if (data != null) {
                        // Store the result for the service to use
                        ScreenCaptureService.resultCode = resultCode
                        ScreenCaptureService.resultData = data
                        
                        val isAndroid14Plus = Build.VERSION.SDK_INT >= ANDROID_14_API_LEVEL
                        Log.d(TAG, "â MediaProjection permission granted (Android 14+ app selection: $isAndroid14Plus)")
                        
                        pendingPromise?.resolve(true)
                    } else {
                        Log.e(TAG, "â Result OK but data is null")
                        pendingPromise?.reject("ERROR", "Permission granted but data is null")
                    }
                }
                Activity.RESULT_CANCELED -> {
                    Log.w(TAG, "â User canceled screen capture permission")
                    pendingPromise?.reject("PERMISSION_DENIED", "User canceled screen capture permission")
                }
                else -> {
                    Log.e(TAG, "â Unexpected result code: $resultCode")
                    pendingPromise?.reject("ERROR", "Unexpected result code: $resultCode")
                }
            }
            
            pendingPromise = null
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        // Not used
    }
    
    // Required for event emitter
    @ReactMethod
    fun addListener(eventName: String) {
        // Keep: Required for RN built-in Event Emitter
    }
    
    @ReactMethod
    fun removeListeners(count: Int) {
        // Keep: Required for RN built-in Event Emitter
    }
}

