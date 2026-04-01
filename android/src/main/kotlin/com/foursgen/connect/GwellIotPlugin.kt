package com.foursgen.connect
import android.app.Activity
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import com.gw.gwiotapi.GWIoT
import com.gw.gwiotapi.entities.UserC2CInfo
import com.gw.gwiotapi.entities.GWResult
import com.gw.gwiotapi.entities.ScanQRCodeOptions
import com.gw.gwiotapi.entities.OpenPluginOption
import com.gw.gwiotapi.entities.PlaybackOption
import com.gw.gwiotapi.entities.Device
import android.content.Context
import com.gw.gwiotapi.entities.AppTexts
import com.gw.gwiotapi.entities.DevShareOption
import com.gw.gwiotapi.entities.Theme
import com.gw.gwiotapi.entities.UIConfiguration
import com.gw.gwiotapi.ext.productInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

 /**
 * GwellIotPlugin — Android MethodChannel plugin for GWIoT SDK.
 *
 * Core logic COPIED from working project: intergrate_gwell/GwellAuthPlugin.kt
 * Method names match iOS GWIoTMethodChannel.swift for cross-platform parity.
 */
class GwellIotPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private val scope = CoroutineScope(Dispatchers.Main)
    private val TAG = "GwellIotPlugin"
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activity: Activity? = null

    // Cache devices from last queryDeviceList (same as working project)
    private var cachedDevices: List<Device> = emptyList()

    // Persist region across login → queryDeviceList calls
    // HttpServiceAdapter reads regRegion from MMKV on EVERY request,
    // so we must patch MMKV immediately before each queryDeviceList call.
    private var lastRegRegion: String = "SG"
    private var lastAreaCode: String = "sg"

    private val currentActivity: Activity? get() = activity

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding
        channel = MethodChannel(binding.binaryMessenger, "com.reoqoo/gwiot")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        flutterPluginBinding = null
    }

    // ── ActivityAware ────────────────────────────────────────────────────
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }
    override fun onDetachedFromActivityForConfigChanges() { activity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }
    override fun onDetachedFromActivity() { activity = null }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            // ── SDK Init (matching working project) ──────────────
            "initGwellSdk" -> handleInitGwellSdk(call, result)
            "getPhoneUniqueId" -> handleGetPhoneUniqueId(result)
            // ── Auth - C2C Login (matching working project + iOS) ─
            "loginSDK" -> handleLoginWithC2CInfo(call, result)
            "loginWithC2CInfo" -> handleLoginWithC2CInfo(call, result)
            "checkLoginStatus" -> handleCheckLoginStatus(result)
            "isLoggedIn" -> handleCheckLoginStatus(result)
            "logout" -> handleLogout(result)
            // ── Auth - Register/Login (iOS-only, stub for Android) ─
            "sendRegisterCodeEmail" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            "verifyCodeEmail" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            "registerWithEmail" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            "sendRegisterCodePhone" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            "verifyCodePhone" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            "registerWithPhone" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            "loginWithEmail" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            "loginWithPhone" -> result.success(mapOf("success" to false, "error" to "Not implemented on Android"))
            // ── Backend Credentials (matching iOS) ────────────────
            "saveBackendCredentials" -> handleSaveBackendCredentials(call, result)
            "getBackendCredentials" -> handleGetBackendCredentials(result)
            "clearBackendCredentials" -> handleClearBackendCredentials(result)

            // ── Push Token ────────────────────────────────────────
            "uploadPushToken" -> handleUploadPushToken(call, result)

            // ── Device Management (matching working project) ──────
            "getDeviceList" -> handleQueryDeviceList(result)
            "queryDeviceList" -> handleQueryDeviceList(result)

            // ── Player - Built-in UI (matching working project) ───
            "openDeviceHome" -> handleOpenLiveView(call, result)
            "openLiveView" -> handleOpenLiveView(call, result)
            "openPlayback" -> handleOpenPlayback(call, result)
            "openPlaybackWithTime" -> handleOpenPlaybackWithTime(call, result)
            "getCloudPlaybackPermission" -> handleGetCloudPlaybackPermission(call, result)

            // ── QR & Binding (matching working project) ──────────
            "openScanQRCode" -> handleOpenScanQRCode(result)
            "openProductList" -> handleOpenBindProductList(result)
            "openBindProductList" -> handleOpenBindProductList(result)
            "openBindByQRCode" -> handleOpenBindByQRCode(call, result)

            // ── Device Pages (matching working project) ──────────
            "openDeviceSettingPage" -> handleOpenDeviceSettings(call, result)
            "openDeviceSettings" -> handleOpenDeviceSettings(call, result)
            "openDeviceInfoPage" -> handleOpenDeviceSettings(call, result) // same as settings
            "openEventsPage" -> handleOpenDeviceEvents(call, result)
            "openDeviceEvents" -> handleOpenDeviceEvents(call, result)
            "openMessageCenterPage" -> handleOpenMessageCenterPage(result)
            "openDevSharePage" -> handleOpenDevSharePage(call, result)

            // ── UI Config ────────────────────────────────────────
            "setLanguage" -> handleSetLanguage(call, result)
            "setUIConfiguration" -> handleSetUIConfiguration(call, result)

            else -> result.notImplemented()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SDK INIT — COPIED from working project GwellApplication.initGwiot()
    // ══════════════════════════════════════════════════════════════════════

    private fun handleInitGwellSdk(call: MethodCall, result: Result) {
        try {
            val appId = call.argument<String>("appId") ?: ""
            val appToken = call.argument<String>("appToken") ?: ""
            val language = call.argument<String>("language") ?: "vi"

            val app = flutterPluginBinding?.applicationContext as? android.app.Application
            if (app == null) {
                result.success(mapOf("success" to false, "message" to "No application context"))
                return
            }
            GwellSdkInitializer.init(app, appId, appToken, language)
            Log.i(TAG, "[INIT_SDK] GWIoT SDK initialized")
            result.success(mapOf("success" to true, "message" to "SDK initialized"))
        } catch (e: Exception) {
            Log.e(TAG, "[INIT_SDK] EXCEPTION: ${e.message}", e)
            result.success(mapOf("success" to false, "message" to (e.message ?: "SDK init failed")))
        }
    }

    // COPIED from working project
    private fun handleGetPhoneUniqueId(result: Result) {
        try {
            val ret = GWIoT.phoneUniqueId()
            if (ret is GWResult.Success) {
                val id = ret.data ?: ""
                Log.d(TAG, "[PHONE_ID] $id")
                result.success(mapOf("success" to true, "phoneUniqueId" to "$id"))
            } else if (ret is GWResult.Failure) {
                Log.e(TAG, "[PHONE_ID] Failed: ${ret.err}")
                result.success(mapOf("success" to false, "phoneUniqueId" to ""))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[PHONE_ID] EXCEPTION: ${e.message}", e)
            result.success(mapOf("success" to false, "phoneUniqueId" to ""))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  C2C LOGIN — COPIED from working project GwellAuthPlugin.handleLoginWithC2CInfo()
    // ══════════════════════════════════════════════════════════════════════

    private fun handleLoginWithC2CInfo(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val accessId = call.argument<String>("accessId") ?: ""
        val accessToken = call.argument<String>("accessToken") ?: ""
        val expireTime = call.argument<String>("expireTime") ?: ""
        val terminalId = call.argument<String>("terminalId") ?: ""

        // Accept expand from Flutter (matching working project)
        // Fallback: build from region
        val expandFromFlutter = call.argument<String>("expand")
        val region = call.argument<String>("region") ?: "VN"

        Log.d(TAG, "[C2C_LOGIN] accessId=$accessId, tokenLen=${accessToken.length}")

        if (accessId.isBlank() || accessToken.isBlank()) {
            result.error("INVALID_ARGS", "accessId and accessToken are required", null)
            return
        }

        // Build expand (matching working project format)
        val expand = if (!expandFromFlutter.isNullOrBlank()) {
            expandFromFlutter
        } else {
            val areaCode = RegionMapper.getAreaCode(region)
            val regRegion = region.uppercase()
            "{\"area\":\"$areaCode\",\"regRegion\":\"$regRegion\"}"
        }

        scope.launch {
            try {
                val c2cInfo = UserC2CInfo(accessId, accessToken, expireTime, terminalId, expand)
                Log.d(TAG, "[C2C_LOGIN] UserC2CInfo created:")
                Log.d(TAG, "[C2C_LOGIN]   .accessId=${c2cInfo.accessId}")
                Log.d(TAG, "[C2C_LOGIN]   .area=${c2cInfo.area}")
                Log.d(TAG, "[C2C_LOGIN]   .regRegion=${c2cInfo.regRegion}")
                Log.d(TAG, "[C2C_LOGIN]   .expireTime=${c2cInfo.expireTime}")
                Log.d(TAG, "[C2C_LOGIN]   .terminalId=${c2cInfo.terminalId}")
                Log.d(TAG, "[C2C_LOGIN]   .userId=${c2cInfo.userId}")
                Log.d(TAG, "[C2C_LOGIN]   .expend=${c2cInfo.expend}")
                Log.d(TAG, "[C2C_LOGIN] isLogin BEFORE: ${GWIoT.isLogin.value}")

                // ┌──────────────────────────────────────────────────────────────┐
                // │ SDK BUG: LoginSucceededUtils writes regRegion='null'         │
                // │ to 'gwell' MMKV synchronously during login2() callback.     │
                // │ It IMMEDIATELY triggers internal device sync (readDeviceV2) │
                // │ which reads regRegion=null → defaults to US → 10007.        │
                // │                                                              │
                // │ We CANNOT prevent internal sync from failing.                │
                // │ FIX: Patch MMKV before EVERY explicit queryDeviceList()     │
                // │ call. HttpServiceAdapter re-reads MMKV on each request,     │
                // │ so patching right before our query ensures correct region.   │
                // └──────────────────────────────────────────────────────────────┘

                // Save region for use in queryDeviceList
                lastRegRegion = c2cInfo.regRegion ?: region.uppercase()
                lastAreaCode = c2cInfo.area ?: RegionMapper.getAreaCode(region)
                Log.i(TAG, "[C2C_LOGIN] Saved region: regRegion=$lastRegRegion, area=$lastAreaCode")

                // Pre-seed MMKV before login (helps if SDK reads during init)
                patchAllMmkvStores(lastRegRegion, lastAreaCode)

                // Single login2 - DO NOT re-login (2nd login triggers LoginSucceededUtils again)
                val loginResult = GWIoT.login2(c2cInfo)
                Log.d(TAG, "[C2C_LOGIN] login2 result: $loginResult")
                Log.d(TAG, "[C2C_LOGIN] isLogin AFTER: ${GWIoT.isLogin.value}")

                if (loginResult is GWResult.Success) {
                    Log.i(TAG, "[C2C_LOGIN] ✅ login2 SUCCESS")

                    // Wait for LoginSucceededUtils to finish + components to mount
                    // Internal device sync WILL fail with 10007 - that's expected.
                    // Our explicit queryDeviceList() will succeed because we patch MMKV before it.
                    Log.i(TAG, "[C2C_LOGIN] ⏳ Waiting 3s for SDK components to mount...")
                    kotlinx.coroutines.delay(3000)

                    // Patch MMKV after LoginSucceededUtils has written regRegion=null
                    patchAllMmkvStores(lastRegRegion, lastAreaCode)
                    Log.i(TAG, "[C2C_LOGIN] ✅ Post-login MMKV patch applied")
                    dumpMmkvKeys()

                    result.success(mapOf("success" to true, "message" to "C2C login2 successful"))
                } else if (loginResult is GWResult.Failure) {
                    Log.e(TAG, "[C2C_LOGIN] ❌ FAILED - ${loginResult.err}")
                    result.success(mapOf("success" to false, "message" to "login2 failed: ${loginResult.err}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[C2C_LOGIN] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "C2C login failed")))
            }
        }
    }

    /**
     * Patch regRegion in MMKV stores at ALL possible root directories.
     *
     * KEY INSIGHT: Tuya SDK initializes MMKV FIRST with root=thingmmkv.
     * MMKV.initialize() can only be called once per process.
     * So when Gwell SDK's HttpServiceAdapter reads MMKV without specifying rootDir,
     * it reads from Tuya's thingmmkv root — NOT Gwell's gw_key_value root.
     *
     * In the reference project (intergrate_gwell), there's NO Tuya SDK,
     * so MMKV default root = Gwell's own directory and everything works.
     *
     * FIX: Patch regRegion in ALL 3 possible MMKV locations:
     * 1. gw_key_value/ (Gwell's explicit rootDir via MMKVLoader)
     * 2. files/thingmmkv/ (Tuya's MMKV default root — what HttpServiceAdapter may use)
     * 3. MMKV default (without rootDir — fallback)
     */
    private fun patchAllMmkvStores(regRegion: String, area: String): Boolean {
        val context = flutterPluginBinding?.applicationContext ?: return false

        // Root 1: Gwell's explicit MMKV root (used by MMKVLoader)
        val gwKvDir = java.io.File(
            context.filesDir.parent ?: "/data/data/${context.packageName}",
            "gw_key_value"
        ).absolutePath

        // Root 2: Tuya's MMKV default root (set by Tuya SDK's MMKV.initialize())
        val thingMmkvDir = java.io.File(context.filesDir, "thingmmkv").absolutePath

        var success = true
        val storeIds = listOf("gwell", "account_shared")

        // Patch in Gwell's root
        storeIds.forEach { storeId ->
            if (!patchSingleMmkvStore(storeId, gwKvDir, regRegion, area)) success = false
        }

        // Patch in Tuya's MMKV default root (critical for HttpServiceAdapter)
        storeIds.forEach { storeId ->
            patchSingleMmkvStore(storeId, thingMmkvDir, regRegion, area)
        }

        // Patch via MMKV default (no rootDir) — covers any other resolution path
        patchMmkvDefaultRoot(regRegion, area)

        return success
    }

    /**
     * Patch MMKV stores using default root (no explicit rootDir).
     * This covers the case where SDK code accesses MMKV.mmkvWithID("gwell")
     * without specifying rootDir — it will use whatever root was set by
     * the first MMKV.initialize() call (which is Tuya's thingmmkv in main project).
     */
    private fun patchMmkvDefaultRoot(regRegion: String, area: String) {
        try {
            val mmkvClass = Class.forName("com.tencent.mmkv.MMKV")

            // Try mmkvWithID(storeId, mode) — 2-param version without rootDir
            val mmkvWithIdMethod = try {
                mmkvClass.getMethod(
                    "mmkvWithID",
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
            } catch (_: NoSuchMethodException) { null }

            if (mmkvWithIdMethod != null) {
                listOf("gwell", "account_shared").forEach { storeId ->
                    try {
                        val mmkv = mmkvWithIdMethod.invoke(null, storeId, 2) // MULTI_PROCESS_MODE
                        if (mmkv != null) {
                            val encodeMethod = mmkv.javaClass.getMethod("encode", String::class.java, String::class.java)
                            encodeMethod.invoke(mmkv, "regRegion", regRegion)
                            encodeMethod.invoke(mmkv, "keyUserRegion", area)
                            Log.d(TAG, "[MMKV_PATCH] ✅ [DEFAULT/$storeId] regRegion=$regRegion")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "[MMKV_PATCH] [DEFAULT/$storeId] skip: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "[MMKV_PATCH] Default root patch skip: ${e.message}")
        }
    }

    private fun patchSingleMmkvStore(storeId: String, rootDir: String, regRegion: String, area: String): Boolean {
        return try {
            val mmkvClass = Class.forName("com.tencent.mmkv.MMKV")
            val mmkvWithIdMethod = mmkvClass.getMethod(
                "mmkvWithID",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            val mmkv = mmkvWithIdMethod.invoke(null, storeId, 2, null, rootDir)

            if (mmkv != null) {
                val encodeMethod = mmkv.javaClass.getMethod("encode", String::class.java, String::class.java)
                encodeMethod.invoke(mmkv, "regRegion", regRegion)
                encodeMethod.invoke(mmkv, "keyUserRegion", area)

                val decodeMethod = mmkv.javaClass.getMethod("decodeString", String::class.java)
                val verifyRegion = decodeMethod.invoke(mmkv, "regRegion") as? String
                val verifyArea = decodeMethod.invoke(mmkv, "keyUserRegion") as? String
                Log.i(TAG, "[MMKV_PATCH] ✅ [$storeId] regRegion=$verifyRegion, keyUserRegion=$verifyArea")
                true
            } else {
                Log.w(TAG, "[MMKV_PATCH] ⚠️ [$storeId] mmkvWithID returned null")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "[MMKV_PATCH] ⚠️ [$storeId] Reflection failed: ${e.message}")
            false
        }
    }

    /**
     * Dump critical MMKV keys from BOTH roots for diagnostics.
     */
    private fun dumpMmkvKeys() {
        try {
            val context = flutterPluginBinding?.applicationContext ?: return
            val gwKvDir = java.io.File(
                context.filesDir.parent ?: "/data/data/${context.packageName}",
                "gw_key_value"
            ).absolutePath
            val thingMmkvDir = java.io.File(context.filesDir, "thingmmkv").absolutePath

            val mmkvClass = Class.forName("com.tencent.mmkv.MMKV")
            val mmkvWithIdMethod = mmkvClass.getMethod(
                "mmkvWithID",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )

            // Dump from BOTH roots
            mapOf("GW" to gwKvDir, "TUYA" to thingMmkvDir).forEach { (rootLabel, rootDir) ->
                listOf("gwell", "account_shared").forEach { storeId ->
                    try {
                        val mmkv = mmkvWithIdMethod.invoke(null, storeId, 2, null, rootDir) ?: return@forEach
                        val decodeMethod = mmkv.javaClass.getMethod("decodeString", String::class.java)
                        val regRegion = decodeMethod.invoke(mmkv, "regRegion") as? String
                        val keyUserRegion = decodeMethod.invoke(mmkv, "keyUserRegion") as? String
                        Log.i(TAG, "[MMKV_DUMP] [$rootLabel/$storeId] regRegion=$regRegion, keyUserRegion=$keyUserRegion")
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[MMKV_DUMP] Failed: ${e.message}")
        }
    }

    /**
     * Deep reflection: find HttpServiceAdapter and any region-related fields/methods.
     * The SDK uses HttpServiceAdapter internally to manage API regions.
     * We search for it and attempt to set the region to the correct value.
     */
    private fun patchHttpServiceAdapterRegion(regRegion: String, area: String) {
        // Try to find HttpServiceAdapter class via multiple possible package names
        val possibleClassNames = listOf(
            "com.jwkj.base_utils.http.HttpServiceAdapter",
            "com.gw.component.http.HttpServiceAdapter",
            "com.gw.http.HttpServiceAdapter",
            "com.jwkj.base_http.HttpServiceAdapter",
            "com.gw.base_http.HttpServiceAdapter",
            "com.gw.reoqoo.HttpServiceAdapter",
            "com.jwkj.compo_host.http.HttpServiceAdapter",
            "com.gw.compo_host.http.HttpServiceAdapter",
            "com.gw.gwiotapi.internal.HttpServiceAdapter",
        )

        for (className in possibleClassNames) {
            try {
                val cls = Class.forName(className)
                Log.i(TAG, "[HTTP_ADAPTER] ✅ Found class: $className")

                // Log ALL methods on this class
                val methods = cls.declaredMethods
                Log.i(TAG, "[HTTP_ADAPTER] Methods (${methods.size}):")
                methods.forEach { m ->
                    Log.i(TAG, "[HTTP_ADAPTER]   ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) -> ${m.returnType.simpleName}")
                }

                // Log ALL fields on this class
                val fields = cls.declaredFields
                Log.i(TAG, "[HTTP_ADAPTER] Fields (${fields.size}):")
                fields.forEach { f ->
                    f.isAccessible = true
                    Log.i(TAG, "[HTTP_ADAPTER]   ${f.name}: ${f.type.simpleName}")
                }

                // Try to find a singleton instance
                val instanceField = fields.find { it.name.contains("INSTANCE", true) || it.name.contains("instance", true) || it.name.contains("singleton", true) }
                val companionField = fields.find { it.name == "Companion" }

                if (instanceField != null) {
                    instanceField.isAccessible = true
                    val instance = instanceField.get(null)
                    Log.i(TAG, "[HTTP_ADAPTER] Found instance via field: ${instanceField.name}")
                    trySetRegionOnInstance(instance, regRegion, area)
                } else if (companionField != null) {
                    companionField.isAccessible = true
                    val companion = companionField.get(null)
                    Log.i(TAG, "[HTTP_ADAPTER] Found Companion object")
                    // Try getInstance() on companion
                    try {
                        val getInstanceMethod = companion.javaClass.getMethod("getInstance")
                        val instance = getInstanceMethod.invoke(companion)
                        trySetRegionOnInstance(instance, regRegion, area)
                    } catch (_: Exception) {
                        Log.w(TAG, "[HTTP_ADAPTER] No getInstance() on Companion")
                    }
                }

                // Also try static methods directly
                trySetRegionOnClass(cls, regRegion, area)
                return // Found the class, done
            } catch (_: ClassNotFoundException) {
                // Not this class name, try next
            } catch (e: Exception) {
                Log.w(TAG, "[HTTP_ADAPTER] Error with $className: ${e.message}")
            }
        }

        // If we didn't find HttpServiceAdapter, try brute-force search via GwCompoMediator
        Log.w(TAG, "[HTTP_ADAPTER] ⚠️ Could not find HttpServiceAdapter class")
        tryFindViaGwCompoMediator(regRegion, area)
    }

    private fun trySetRegionOnInstance(instance: Any?, regRegion: String, area: String) {
        if (instance == null) return
        Log.i(TAG, "[HTTP_ADAPTER] Instance class: ${instance.javaClass.name}")

        // Log all fields and their current values
        val allFields = mutableListOf<java.lang.reflect.Field>()
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            allFields.addAll(currentClass.declaredFields)
            currentClass = currentClass.superclass
        }

        allFields.forEach { f ->
            try {
                f.isAccessible = true
                val value = f.get(instance)
                if (f.name.contains("region", true) || f.name.contains("area", true) ||
                    f.name.contains("host", true) || f.name.contains("country", true) ||
                    (value is String && (value.equals("US", true) || value.equals("SG", true)))) {
                    Log.i(TAG, "[HTTP_ADAPTER] 🎯 REGION-RELATED field: ${f.name} = $value (type=${f.type.simpleName})")

                    // If it's a String field containing "US", try to set it to correct region
                    if (value is String && value.equals("US", true)) {
                        f.set(instance, regRegion)
                        Log.i(TAG, "[HTTP_ADAPTER] ✅ Set ${f.name} from US to $regRegion")
                    }
                    if (value is String && value.equals("null", true)) {
                        f.set(instance, regRegion)
                        Log.i(TAG, "[HTTP_ADAPTER] ✅ Set ${f.name} from null to $regRegion")
                    }
                } else {
                    Log.d(TAG, "[HTTP_ADAPTER] field: ${f.name} = $value")
                }
            } catch (e: Exception) {
                Log.d(TAG, "[HTTP_ADAPTER] Can't read field ${f.name}: ${e.message}")
            }
        }

        // Try calling setRegion, setServerRegion, updateRegion methods
        val regionSetters = listOf("setRegion", "setServerRegion", "updateRegion", "setRegionCode", "changeRegion")
        for (setter in regionSetters) {
            try {
                val method = instance.javaClass.getMethod(setter, String::class.java)
                method.invoke(instance, regRegion)
                Log.i(TAG, "[HTTP_ADAPTER] ✅ Called $setter($regRegion)")
            } catch (_: NoSuchMethodException) {
                // Not available
            } catch (e: Exception) {
                Log.w(TAG, "[HTTP_ADAPTER] $setter failed: ${e.message}")
            }
        }
    }

    private fun trySetRegionOnClass(cls: Class<*>, regRegion: String, area: String) {
        // Try static setRegion methods
        listOf("setRegion", "setServerRegion", "updateRegion").forEach { methodName ->
            try {
                val method = cls.getMethod(methodName, String::class.java)
                method.invoke(null, regRegion)
                Log.i(TAG, "[HTTP_ADAPTER] ✅ Called static $methodName($regRegion)")
            } catch (_: NoSuchMethodException) {
                // Not available
            } catch (e: Exception) {
                Log.w(TAG, "[HTTP_ADAPTER] static $methodName failed: ${e.message}")
            }
        }
    }

    /**
     * Try to find SDK internal components via GwCompoMediator to locate HttpServiceAdapter
     */
    private fun tryFindViaGwCompoMediator(regRegion: String, area: String) {
        val mediatorClasses = listOf(
            "com.gw.component.GwCompoMediator",
            "com.jwkj.base_utils.GwCompoMediator",
            "com.gw.component.mediator.GwCompoMediator",
        )

        for (className in mediatorClasses) {
            try {
                val cls = Class.forName(className)
                Log.i(TAG, "[COMPO_SEARCH] ✅ Found GwCompoMediator: $className")

                // Log all methods
                cls.declaredMethods.forEach { m ->
                    Log.i(TAG, "[COMPO_SEARCH]   method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                }
                cls.declaredFields.forEach { f ->
                    Log.i(TAG, "[COMPO_SEARCH]   field: ${f.name}: ${f.type.simpleName}")
                }
                return
            } catch (_: ClassNotFoundException) {
                // Try next
            }
        }
        Log.w(TAG, "[COMPO_SEARCH] Could not find GwCompoMediator either")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  checkLoginStatus / logout — COPIED from working project
    // ══════════════════════════════════════════════════════════════════════

    private fun handleCheckLoginStatus(result: Result) {
        if (!GwellSdkInitializer.sdkInitialized) {
            result.success(mapOf("isLoggedIn" to false))
            return
        }
        try {
            val isLoggedIn = GWIoT.isLogin.value ?: false
            Log.d(TAG, "[checkLoginStatus] isLoggedIn=$isLoggedIn")
            result.success(mapOf("isLoggedIn" to isLoggedIn))
        } catch (e: Exception) {
            Log.e(TAG, "[checkLoginStatus] EXCEPTION: ${e.message}", e)
            result.success(mapOf("isLoggedIn" to false))
        }
    }

    // COPIED from working project
    private fun handleLogout(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                GWIoT.logout()
                cachedDevices = emptyList()
                Log.i(TAG, "[logout] ✅ Success")
                // iOS returns: {"success": true}
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[logout] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Logout failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE LIST — COPIED from working project GwellAuthPlugin.handleQueryDeviceList()
    // ══════════════════════════════════════════════════════════════════════

    private fun handleQueryDeviceList(result: Result) {
        if (!ensureSdkInitialized(result)) return

        val isLoggedIn = GWIoT.isLogin.value
        Log.d(TAG, "[DEVICE_LIST] isLogin=$isLoggedIn (null is normal for C2C mode)")

        scope.launch {
            try {
                Log.d(TAG, "[DEVICE_LIST] Querying...")
                // EXACT COPY: use queryDeviceList() (NOT queryDeviceListCacheFirst)
                val devResult = GWIoT.queryDeviceList()
                Log.d(TAG, "[DEVICE_LIST] Result: ${devResult::class.simpleName}")

                when (devResult) {
                    is GWResult.Success<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val devices = (devResult.data as? List<Device>) ?: emptyList()
                        cachedDevices = devices

                        // iOS also gets IoT props for online status
                        val list = devices.map { dev ->
                            var isOnline = false
                            try {
                                val propsResult = GWIoT.getIoTProps(dev)
                                if (propsResult is GWResult.Success) {
                                    isOnline = propsResult.data?.isOnline ?: false
                                }
                            } catch (_: Exception) {}

                            mapOf(
                                "deviceId" to dev.deviceId,
                                "deviceName" to dev.remarkName,
                                "isOnline" to isOnline,
                                "deviceType" to (dev.productInfo?.name ?: ""),
                                "jsonString" to (dev.jsonString ?: ""),
                            )
                        }
                        Log.i(TAG, "[DEVICE_LIST] ✅ Found ${list.size} devices")
                        // iOS returns: {"success": true, "devices": [...]}
                        result.success(mapOf("success" to true, "devices" to list))
                    }
                    is GWResult.Failure<*> -> {
                        val gwError = devResult.err
                        val errMsg = gwError?.message ?: "Unknown error"
                        val reason = gwError?.reason?.toString() ?: "Unknown reason"
                        Log.e(TAG, "[DEVICE_LIST] ❌ FAILURE: message=$errMsg, reason=$reason")

                        val responseMap = mutableMapOf<String, Any>(
                            "success" to false,
                            "error" to "[$reason] $errMsg",
                        )
                        if (isSignatureError(errMsg, reason)) {
                            responseMap["errorCode"] = "10007"
                            responseMap["isSignatureError"] = true
                        }
                        result.success(responseMap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[DEVICE_LIST] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Query failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LIVE VIEW — COPIED from working project GwellAuthPlugin.handleOpenLiveView()
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenLiveView(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId. Try refreshing the device list."))
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "[LIVE_VIEW] Opening for device: $deviceId")
                val option = OpenPluginOption(device)
                GWIoT.openHome(option)
                Log.i(TAG, "[LIVE_VIEW] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[LIVE_VIEW] EXCEPTION: ${e.message}", e)
                handleErrorResult("LIVE_VIEW", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PLAYBACK — COPIED from working project GwellAuthPlugin.handleOpenPlayback()
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenPlayback(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "[PLAYBACK] Opening for device: $deviceId")
                val option = PlaybackOption(device)
                GWIoT.openPlayback(option)
                Log.i(TAG, "[PLAYBACK] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[PLAYBACK] EXCEPTION: ${e.message}", e)
                handleErrorResult("PLAYBACK", e, result)
            }
        }
    }

    // Matching iOS GWIoTPlayerBridge.openPlaybackWithTime
    private fun handleOpenPlaybackWithTime(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val startTime = call.argument<Long>("startTime") ?: call.argument<Int>("startTime")?.toLong()
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            return
        }
        scope.launch {
            try {
                val option = PlaybackOption(device)
                if (startTime != null) option.startTime = startTime
                GWIoT.openPlayback(option)
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("PLAYBACK_TIME", e, result)
            }
        }
    }

    // Matching iOS GWIoTPlayerBridge.getCloudPlaybackPermission
    private fun handleGetCloudPlaybackPermission(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "hasPermission" to false))
            return
        }
        scope.launch {
            try {
                val cloudResult = GWIoT.getCloudPlaybackPermission(device)
                if (cloudResult is GWResult.Success) {
                    result.success(mapOf("success" to true, "hasPermission" to (cloudResult.data ?: false)))
                } else {
                    result.success(mapOf("success" to false, "hasPermission" to false))
                }
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "hasPermission" to false))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE SETTINGS — COPIED from working project
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenDeviceSettings(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            return
        }
        scope.launch {
            try {
                Log.d(TAG, "[SETTINGS] Opening for device: $deviceId")
                GWIoT.openDeviceInfoPage(device)
                Log.i(TAG, "[SETTINGS] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[SETTINGS] EXCEPTION: ${e.message}", e)
                handleErrorResult("SETTINGS", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE EVENTS — COPIED from working project
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenDeviceEvents(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            return
        }
        scope.launch {
            try {
                Log.d(TAG, "[EVENTS] Opening for device: $deviceId")
                GWIoT.openEventsPage(device)
                Log.i(TAG, "[EVENTS] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[EVENTS] EXCEPTION: ${e.message}", e)
                handleErrorResult("EVENTS", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  QR & BINDING — COPIED from working project
    // ══════════════════════════════════════════════════════════════════════

    // COPIED from working project
    private fun handleOpenScanQRCode(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                Log.d(TAG, "[SCAN_QR] Opening scan page")
                val opts = ScanQRCodeOptions(
                    enableBuiltInHandling = true,
                    title = "Quét mã QR",
                    descTitle = "Quét mã QR trên thiết bị hoặc mã chia sẻ"
                )
                val scanResult = GWIoT.openScanQRCodePage(opts)
                when (scanResult) {
                    is GWResult.Success<*> -> result.success(mapOf("success" to true))
                    is GWResult.Failure<*> -> result.success(mapOf("success" to false, "error" to scanResult.toString()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[SCAN_QR] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "QR scan failed")))
            }
        }
    }

    // COPIED from working project
    private fun handleOpenBindProductList(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                Log.d(TAG, "[BIND_LIST] Opening product list")
                val bindResult = GWIoT.openBindableProductList()
                when (bindResult) {
                    is GWResult.Success<*> -> result.success(mapOf("success" to true))
                    is GWResult.Failure<*> -> result.success(mapOf("success" to false, "error" to bindResult.toString()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[BIND_LIST] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Bind list failed")))
            }
        }
    }

    // Matching iOS GWIoTDeviceBridge.openBindByQRCode — uses GWIoT.openBind(qrCodeValue:)
    // Android SDK equivalent: try openScanQRCodePage with the value
    private fun handleOpenBindByQRCode(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val qrValue = call.argument<String>("qrValue") ?: ""
        if (qrValue.isBlank()) {
            result.error("INVALID_ARGS", "Missing qrValue", null)
            return
        }
        scope.launch {
            try {
                // Android SDK may not have openBind(qrValue) — fallback to scan page
                val opts = ScanQRCodeOptions(
                    enableBuiltInHandling = true,
                    title = "Quét mã QR",
                    descTitle = "Quét mã QR trên thiết bị hoặc mã chia sẻ"
                )
                val scanResult = GWIoT.openScanQRCodePage(opts)
                when (scanResult) {
                    is GWResult.Success<*> -> result.success(mapOf("success" to true))
                    is GWResult.Failure<*> -> result.success(mapOf("success" to false, "error" to scanResult.toString()))
                }
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "Bind failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MESSAGE CENTER & DEV SHARE — matching iOS (not in working project)
    // ══════════════════════════════════════════════════════════════════════

    // Matching iOS GWIoTDeviceBridge.openMessageCenterPage
    private fun handleOpenMessageCenterPage(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                // Android SDK: GWIoT.openMessageCenter() may not exist
                // Return not-implemented gracefully
                result.success(mapOf("success" to false, "error" to "Not available on Android"))
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
            }
        }
    }

    // Matching iOS GWIoTDeviceBridge.openDevSharePage
    private fun handleOpenDevSharePage(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            return
        }
        scope.launch {
            try {
                // iOS: DevShareOption(device: device) → openDevSharePage(opt:)
                val shareOption = DevShareOption(device)
                GWIoT.openDevSharePage(shareOption)
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("DEV_SHARE", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BACKEND CREDENTIALS (matching iOS UserDefaults pattern)
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSaveBackendCredentials(call: MethodCall, result: Result) {
        val context = currentActivity?.applicationContext ?: run {
            result.success(mapOf("success" to false, "error" to "No context"))
            return
        }
        try {
            val prefs = context.getSharedPreferences("gwell_credentials", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("accessId", call.argument<String>("accessId") ?: "")
                .putString("accessToken", call.argument<String>("accessToken") ?: "")
                .putString("terminalId", call.argument<String>("terminalId") ?: "")
                .putString("area", call.argument<String>("area") ?: "")
                .putString("regRegion", call.argument<String>("regRegion") ?: "")
                .apply()
            result.success(mapOf("success" to true))
        } catch (e: Exception) {
            result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
        }
    }

    private fun handleGetBackendCredentials(result: Result) {
        val context = currentActivity?.applicationContext ?: run {
            result.success(emptyMap<String, Any?>())
            return
        }
        try {
            val prefs = context.getSharedPreferences("gwell_credentials", Context.MODE_PRIVATE)
            result.success(mapOf(
                "accessId" to prefs.getString("accessId", null),
                "accessToken" to prefs.getString("accessToken", null),
                "terminalId" to prefs.getString("terminalId", null),
                "area" to prefs.getString("area", null),
                "regRegion" to prefs.getString("regRegion", null),
            ))
        } catch (e: Exception) {
            result.success(emptyMap<String, Any?>())
        }
    }

    private fun handleClearBackendCredentials(result: Result) {
        val context = currentActivity?.applicationContext ?: run {
            result.success(mapOf("success" to true))
            return
        }
        try {
            context.getSharedPreferences("gwell_credentials", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
        result.success(mapOf("success" to true))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUSH TOKEN
    // ══════════════════════════════════════════════════════════════════════

    private fun handleUploadPushToken(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val token = call.argument<String>("token") ?: ""
        if (token.isBlank()) {
            result.success(mapOf("success" to false, "error" to "Token is empty"))
            return
        }
        scope.launch {
            try {
                val ret = GWIoT.uploadPushToken(token)
                when (ret) {
                    is GWResult.Success<*> -> result.success(mapOf("success" to true))
                    is GWResult.Failure<*> -> result.success(mapOf("success" to false, "error" to ret.err.toString()))
                }
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI CONFIG
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSetLanguage(call: MethodCall, result: Result) {
        // Language is set during SDK init via GwellSdkInitializer
        result.success(mapOf("success" to true))
    }

    private fun handleSetUIConfiguration(call: MethodCall, result: Result) {
        try {
            val uiConfig = UIConfiguration(
                theme = Theme(),
                texts = AppTexts(appNamePlaceHolder = "4SGen Connect")
            )
            GWIoT.setUIConfiguration(uiConfig)
            result.success(mapOf("success" to true))
        } catch (e: Exception) {
            result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPERS — COPIED from working project
    // ══════════════════════════════════════════════════════════════════════

    // COPIED from working project
    private fun findDevice(deviceId: String): Device? {
        return cachedDevices.find { it.deviceId == deviceId }
    }

    // COPIED from working project
    private fun ensureSdkInitialized(result: Result): Boolean {
        if (GwellSdkInitializer.sdkInitialized) return true
        result.error("SDK_NOT_INITIALIZED", "GWIoT SDK not initialized. Use ARM64 device.", null)
        return false
    }

    // Matching iOS isSignatureError pattern
    private fun isSignatureError(errMsg: String, reason: String = ""): Boolean {
        val combined = "$errMsg $reason".lowercase()
        return combined.contains("signature") || combined.contains("10007")
                || combined.contains("-7") || combined.contains("auth failed")
    }

    // Matching iOS error response pattern with signature detection
    private fun handleErrorResult(tag: String, e: Exception, result: Result) {
        val errMsg = e.message ?: "Unknown error"
        val responseMap = mutableMapOf<String, Any>(
            "success" to false,
            "error" to errMsg,
        )
        if (isSignatureError(errMsg)) {
            Log.e(TAG, "[$tag] 🔴 SIGNATURE ERROR: $errMsg")
            responseMap["errorCode"] = "10007"
            responseMap["isSignatureError"] = true
        }
        result.success(responseMap)
    }
}
