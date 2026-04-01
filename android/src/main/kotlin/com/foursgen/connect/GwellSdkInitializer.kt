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

    // Cache init params for forceReinit()
    private var cachedApp: Application? = null
    private var cachedAppId: String = ""
    private var cachedAppToken: String = ""
    private var cachedLanguage: String = "vi"

    fun init(app: Application, appId: String, appToken: String, language: String = "vi") {
        if (sdkInitialized) {
            Log.i(TAG, "GWIoT SDK already initialized, skipping")
            return
        }

        // Cache params for forceReinit
        cachedApp = app
        cachedAppId = appId
        cachedAppToken = appToken
        cachedLanguage = language

        if (isUnsupportedEmulatorAbi()) {
            Log.w(TAG, "Skipping GWIoT initialization on x86/x86_64 emulator ABI")
            return
        }

        // NOTE: With disableAccountService=true, SDK won't auto-login from stale MMKV.
        // Do NOT clear gw_key_value or account_shared — it destroys SDK component registry.

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
        // NOTE: Do NOT set disableAccountService=true — it prevents SDK from mounting
        // the device query component (ISyncDevTaskApi), causing NotFoundComponent error.
        // The reference project uses true but works because it has NO Tuya SDK conflict.
        // In main project with Tuya, we need full component registry to be available.
        option.hostConfig = HostConfig(env = HostConfig.Env.Prod)

        // Set language
        option.language = when (language.lowercase()) {
            "vi" -> LanguageCode.VI
            "en" -> LanguageCode.EN
            "zh" -> LanguageCode.ZH_HANS
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

        // Pre-seed MMKV with correct region BEFORE SDK init
        // Tuya SDK initializes MMKV first → default root = thingmmkv
        // We must seed ALL possible MMKV roots so Gwell SDK finds SG region
        preSeedMmkvRegion(app, "SG", "sg")

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

    /**
     * Force re-initialize SDK after logout.
     * Used when camera-app has stale auto-login from Tuya's MMKV.
     * After logout(), components are destroyed. Need to reinit to get clean state.
     */
    fun forceReinit() {
        val app = cachedApp ?: throw IllegalStateException("SDK was never initialized")
        Log.i(TAG, "Force reinitializing GWIoT SDK...")
        sdkInitialized = false
        init(app, cachedAppId, cachedAppToken, cachedLanguage)
    }

    private fun isUnsupportedEmulatorAbi(): Boolean {
        return Build.SUPPORTED_ABIS.any { abi ->
            abi.equals("x86", ignoreCase = true) || abi.equals("x86_64", ignoreCase = true)
        }
    }

    /**
     * Pre-seed MMKV stores with correct region at ALL possible root directories.
     * Must be called BEFORE GWIoT.initialize() so the SDK reads correct region on startup.
     *
     * MMKV root conflict: Tuya SDK calls MMKV.initialize() first with root=thingmmkv.
     * Gwell SDK uses gw_key_value/ via explicit rootDir. If any SDK code reads MMKV
     * without specifying rootDir, it reads from Tuya's thingmmkv root.
     */
    private fun preSeedMmkvRegion(app: Application, regRegion: String, area: String) {
        try {
            val mmkvClass = Class.forName("com.tencent.mmkv.MMKV")

            // Root 1: Gwell's explicit root
            val gwKvDir = java.io.File(
                app.filesDir.parent ?: "/data/data/${app.packageName}",
                "gw_key_value"
            ).also { it.mkdirs() }.absolutePath

            // Root 2: Tuya's MMKV default root
            val thingMmkvDir = java.io.File(app.filesDir, "thingmmkv")
                .also { it.mkdirs() }.absolutePath

            val mmkvWithIdMethod4 = mmkvClass.getMethod(
                "mmkvWithID",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )

            // Patch both roots with both store IDs
            for (rootDir in listOf(gwKvDir, thingMmkvDir)) {
                for (storeId in listOf("gwell", "account_shared")) {
                    try {
                        val mmkv = mmkvWithIdMethod4.invoke(null, storeId, 2, null, rootDir) ?: continue
                        val encode = mmkv.javaClass.getMethod("encode", String::class.java, String::class.java)
                        encode.invoke(mmkv, "regRegion", regRegion)
                        encode.invoke(mmkv, "keyUserRegion", area)
                        Log.d(TAG, "[PRE_SEED] ✅ $storeId@${rootDir.substringAfterLast("/")} → regRegion=$regRegion")
                    } catch (e: Exception) {
                        Log.d(TAG, "[PRE_SEED] ⚠️ $storeId@${rootDir.substringAfterLast("/")}: ${e.message}")
                    }
                }
            }

            // Also try MMKV default (no rootDir) — 2-param version
            try {
                val mmkvWithIdMethod2 = mmkvClass.getMethod(
                    "mmkvWithID",
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                for (storeId in listOf("gwell", "account_shared")) {
                    val mmkv = mmkvWithIdMethod2.invoke(null, storeId, 2) ?: continue
                    val encode = mmkv.javaClass.getMethod("encode", String::class.java, String::class.java)
                    encode.invoke(mmkv, "regRegion", regRegion)
                    encode.invoke(mmkv, "keyUserRegion", area)
                    Log.d(TAG, "[PRE_SEED] ✅ $storeId@DEFAULT → regRegion=$regRegion")
                }
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.w(TAG, "[PRE_SEED] MMKV pre-seed failed (non-fatal): ${e.message}")
        }
    }
}
