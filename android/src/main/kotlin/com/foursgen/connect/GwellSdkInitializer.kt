package com.foursgen.connect

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import com.gw.gwiotapi.GWIoT
import com.gw.gwiotapi.entities.AlbumConfig
import com.gw.gwiotapi.entities.AppConfig
import com.gw.gwiotapi.entities.AppTexts
import com.gw.gwiotapi.entities.DeviceShareOption
import com.gw.gwiotapi.entities.InitOptions
import com.gw.gwiotapi.entities.Theme
import com.gw.gwiotapi.entities.UIConfiguration
import com.gw.gwiotapi.entities.HostConfig
import com.gw.gwiotapi.entities.LanguageCode
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.File

/**
 * Standalone Gwell SDK initializer — no Application subclass needed.
 * Call GwellSdkInitializer.init() from your plugin or host app.
 */
object GwellSdkInitializer {
    private const val TAG = "GwellSdkInitializer"

    @Volatile
    var sdkInitialized: Boolean = false
        private set

    fun init(app: Application, appId: String, appToken: String, language: String = "vi") {
        if (sdkInitialized) {
            Log.i(TAG, "GWIoT SDK already initialized, skipping")
            return
        }

        if (isUnsupportedEmulatorAbi()) {
            Log.w(TAG, "Skipping GWIoT initialization on x86/x86_64 emulator ABI")
            return
        }

        // Init Firebase if needed (required by ML Kit & push module)
        try {
            if (FirebaseApp.getApps(app).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId("foursgen-connect")
                    .setApplicationId("1:000000000000:android:0000000000000000")
                    .setApiKey("AIzaSyDummyKeyForGwellSdkInit")
                    .build()
                FirebaseApp.initializeApp(app, options)
                Log.i(TAG, "Firebase initialized with dummy config")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed (non-fatal): ${e.message}")
        }

        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val versionName = packageInfo.versionName ?: "1.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }

        // Resolve main activity class
        val mainActivityClass: Class<Activity> = try {
            val launchIntent = app.packageManager.getLaunchIntentForPackage(app.packageName)
            val className = launchIntent?.component?.className
            if (className != null) {
                @Suppress("UNCHECKED_CAST")
                Class.forName(className) as Class<Activity>
            } else {
                Activity::class.java
            }
        } catch (e: Exception) {
            Activity::class.java
        }

        val option = InitOptions(
            app = app,
            versionName = versionName,
            versionCode = versionCode,
            appConfig = AppConfig(
                appId = appId,
                appToken = appToken,
            ),
            mainActvityKlass = mainActivityClass,
        )
        option.disableAccountService = true
        option.hostConfig = HostConfig(env = HostConfig.Env.Prod)

        // Set language
        option.language = when (language.lowercase()) {
            "vi" -> LanguageCode.VI
            "en" -> LanguageCode.EN
            "zh" -> LanguageCode.ZH
            else -> LanguageCode.EN
        }

        val snapshotDir = "${app.getExternalFilesDir(null)}${File.separator}iotplugin${File.separator}ScreenShots"
        val recordDir = "${app.getExternalFilesDir(null)}${File.separator}iotplugin${File.separator}RecordVideo"
        option.albumConfig = AlbumConfig(
            snapshotDir = snapshotDir,
            recordDir = recordDir,
            watermarkConfig = null
        )

        option.deviceShareOptions = listOf(
            DeviceShareOption.QRCode
        )

        GWIoT.initialize(option)

        GWIoT.setUIConfiguration(
            UIConfiguration(
                theme = Theme(),
                texts = AppTexts(
                    appNamePlaceHolder = "4SGen Connect"
                )
            )
        )

        sdkInitialized = true
        Log.i(TAG, "GWIoT SDK initialized successfully")
    }

    private fun isUnsupportedEmulatorAbi(): Boolean {
        return Build.SUPPORTED_ABIS.any { abi ->
            abi.equals("x86", ignoreCase = true) || abi.equals("x86_64", ignoreCase = true)
        }
    }
}
