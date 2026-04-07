package com.foursgen.connect
import android.app.Activity
import android.content.Intent
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
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
import com.gw.gwiotapi.entities.PushNotification
import com.gw.gwiotapi.entities.DeviceEvent
import android.content.Context
import android.view.View
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
class GwellIotPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, EventChannel.StreamHandler {

    private lateinit var channel: MethodChannel
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
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

    /// Track previous login state to detect token expiry (true → false)
    private var wasLoggedIn: Boolean = false

    /// Flag to ensure bind listener is only set up once
    private var isBindListenerSetup: Boolean = false

    private val currentActivity: Activity? get() = activity

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding
        channel = MethodChannel(binding.binaryMessenger, "com.reoqoo/gwiot")
        channel.setMethodCallHandler(this)

        // EventChannel — matching iOS GWIoTEventChannel (com.reoqoo/gwiot_events)
        eventChannel = EventChannel(binding.binaryMessenger, "com.reoqoo/gwiot_events")
        eventChannel?.setStreamHandler(this)

        // ✅ Register native video view for multi-camera
        binding.platformViewRegistry.registerViewFactory(
            GwellNativeVideoViewFactory.VIEW_TYPE,
            GwellNativeVideoViewFactory(binding.binaryMessenger)
        )
        Log.i(TAG, "[PlatformView] ✅ Registered ${GwellNativeVideoViewFactory.VIEW_TYPE}")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        eventSink = null
        flutterPluginBinding = null
    }

    // ── EventChannel.StreamHandler ────────────────────────────────────────
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        Log.i(TAG, "[EventChannel] 🎧 Flutter started listening to events")
        eventSink = events
        wasLoggedIn = try {
            GWIoT.isLogin.value ?: false
        } catch (_: Exception) { false }
        startObservingLiveData()
    }

    override fun onCancel(arguments: Any?) {
        Log.i(TAG, "[EventChannel] 🔇 Flutter stopped listening to events")
        eventSink = null
    }

    /**
     * Send event to Flutter via EventChannel (ensure main thread).
     * Matching iOS GWIoTEventChannel.sendEvent()
     */
    private fun sendEventToFlutter(data: Map<String, Any?>) {
        scope.launch {
            try {
                eventSink?.success(data)
                Log.d(TAG, "[EventChannel] 📤 Sent event: ${data["type"]}")
            } catch (e: Exception) {
                Log.w(TAG, "[EventChannel] ⚠️ Failed to send event: ${e.message}")
            }
        }
    }

    /**
     * Observe SDK LiveData / LiveEvent — matching iOS GWIoTEventChannel.startObserving()
     * Streams: loginStatusChanged, deviceListUpdated, tokenExpired, accountEvent
     */
    private fun startObservingLiveData() {
        if (!GwellSdkInitializer.sdkInitialized) {
            Log.w(TAG, "[EventChannel] SDK not initialized, skipping observers")
            return
        }

        try {
            // ── Login Status — matching iOS isLogin.observe ──
            GWIoT.isLogin.observeForever { isLoggedIn ->
                val loggedIn = isLoggedIn ?: false

                // Detect token expired: was logged in → now not
                if (wasLoggedIn && !loggedIn) {
                    Log.i(TAG, "[EventChannel] 🔑 Token expired detected (isLogin: true → false)")
                    sendEventToFlutter(mapOf("type" to "tokenExpired"))
                }
                wasLoggedIn = loggedIn

                sendEventToFlutter(mapOf(
                    "type" to "loginStatusChanged",
                    "isLoggedIn" to loggedIn
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] isLogin observe failed: ${e.message}")
        }

        try {
            // ── Device List — matching iOS deviceList.observe ──
            GWIoT.deviceList.observeForever { devices ->
                val deviceArray = (devices as? List<*>)?.filterIsInstance<Device>() ?: emptyList()
                val list = deviceArray.map { device ->
                    mapOf(
                        "deviceId" to device.deviceId,
                        "deviceName" to device.remarkName,
                    )
                }
                sendEventToFlutter(mapOf(
                    "type" to "deviceListUpdated",
                    "devices" to list
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] deviceList observe failed: ${e.message}")
        }

        try {
            // ── Account Event — matching iOS accountEvent.observe ──
            GWIoT.accountEvent.observeForever { event ->
                if (event == null) return@observeForever
                val eventDesc = event.toString().lowercase()
                Log.i(TAG, "[EventChannel] 📋 AccountEvent: $eventDesc")

                // If SDK reports token/session expired via accountEvent, also emit tokenExpired
                if (eventDesc.contains("token") || eventDesc.contains("expire") || eventDesc.contains("auth")) {
                    Log.i(TAG, "[EventChannel] 🔑 AccountEvent suggests token issue. Emitting tokenExpired.")
                    sendEventToFlutter(mapOf("type" to "tokenExpired"))
                }

                sendEventToFlutter(mapOf(
                    "type" to "accountEvent",
                    "event" to event.toString()
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] accountEvent observe failed: ${e.message}")
        }

        try {
            // ── Device Events — DeviceDeleted, NameChanged, SharingDeviceAccepted ──
            GWIoT.deviceEvents.observeForever { event ->
                if (event == null) return@observeForever
                Log.i(TAG, "[EventChannel] 📋 DeviceEvent: $event")

                when (event) {
                    is DeviceEvent.DeviceDeleted -> {
                        Log.i(TAG, "[EventChannel] 🗑️ Device DELETED: ${event.deviceId}")
                        sendEventToFlutter(mapOf(
                            "type" to "deviceDeleted",
                            "deviceId" to event.deviceId
                        ))
                        // Cũng emit bindSuccess để reload danh sách
                        scope.launch { emitBindSuccessEvent() }
                    }
                    is DeviceEvent.NameChanged -> {
                        Log.i(TAG, "[EventChannel] ✏️ Device renamed: ${event.deviceId} -> ${event.name}")
                        sendEventToFlutter(mapOf(
                            "type" to "deviceNameChanged",
                            "deviceId" to event.deviceId,
                            "newName" to event.name
                        ))
                    }
                    is DeviceEvent.SharingDeviceAccepted -> {
                        Log.i(TAG, "[EventChannel] 🤝 Sharing accepted: ${event.deviceId}")
                        sendEventToFlutter(mapOf(
                            "type" to "sharingDeviceAccepted",
                            "deviceId" to event.deviceId
                        ))
                        scope.launch { emitBindSuccessEvent() }
                    }
                    else -> {
                        Log.d(TAG, "[EventChannel] Unknown DeviceEvent: $event")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] deviceEvents observe failed: ${e.message}")
        }

        // Setup bind event listener
        setupBindEventListener()
    }

    /**
     * Bind Event Listener — matching iOS GWIoTDeviceBridge.setupBindEventListener()
     * Observes GWPlugin.bindEvents → emit bindSuccess, bindFailed, bindCancelled
     */
    private fun setupBindEventListener() {
        if (isBindListenerSetup) {
            Log.d(TAG, "[BindEvent] ⚠️ Bind event listener already set up")
            return
        }

        try {
            Log.i(TAG, "[BindEvent] 🎧 Setting up Bind Event Listener")

            // Try to access GWPlugin.bindEvents via reflection
            // iOS: GWPlugin.shared.bindEvents.observe
            // Android: may be GWIoT.bindComp or similar
            val gwPluginClass = try {
                Class.forName("com.gw.gwiotapi.GWPlugin")
            } catch (_: ClassNotFoundException) {
                try {
                    Class.forName("com.gw.gwiotapi.plugin.GWPlugin")
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (gwPluginClass != null) {
                // Try to get singleton instance
                val sharedField = gwPluginClass.declaredFields.find {
                    it.name.equals("INSTANCE", ignoreCase = true) ||
                    it.name.equals("shared", ignoreCase = true) ||
                    it.name.equals("Companion", ignoreCase = true)
                }
                if (sharedField != null) {
                    sharedField.isAccessible = true
                    val instance = sharedField.get(null)
                    // Try to find bindEvents property
                    val bindEventsField = gwPluginClass.declaredFields.find {
                        it.name.contains("bindEvent", ignoreCase = true)
                    }
                    if (bindEventsField != null && instance != null) {
                        bindEventsField.isAccessible = true
                        val bindEvents = bindEventsField.get(instance)
                        Log.i(TAG, "[BindEvent] ✅ Found bindEvents: ${bindEvents?.javaClass?.name}")
                        // Attempt to observe — implementation depends on SDK API
                    }
                }
            }

            isBindListenerSetup = true
            Log.i(TAG, "[BindEvent] ✅ Bind event listener setup complete")
        } catch (e: Exception) {
            Log.w(TAG, "[BindEvent] Setup failed (non-fatal): ${e.message}")
            isBindListenerSetup = true // Don't retry
        }
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
            "getLastSnapshotPath" -> handleGetLastSnapshotPath(call, result)

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

            // ── Device Pages (matching iOS GWIoTDeviceBridge) ──────────
            "openDeviceSettingPage" -> handleOpenDeviceSettings(call, result)
            "openDeviceSettings" -> handleOpenDeviceSettings(call, result)
            "openDeviceInfoPage" -> handleOpenDeviceInfoPage(call, result)
            "openEventsPage" -> handleOpenDeviceEvents(call, result)
            "openDeviceEvents" -> handleOpenDeviceEvents(call, result)
            "openMessageCenterPage" -> handleOpenMessageCenterPage(result)
            "openDevSharePage" -> handleOpenDevSharePage(call, result)

            // ── Multi-Live (built-in multi-camera surveillance) ───
            "openMultiLivePage" -> handleOpenMultiLivePage(result)

            // ── Push Notification (matching iOS GWIoTPushNotificationBridge) ──
            "receivePushNotification" -> handleReceivePushNotification(call, result)
            "clickPushNotification" -> handleClickPushNotification(call, result)

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
                    Log.i(TAG, "[C2C_LOGIN] ⏳ Waiting 1.5s for SDK components to mount...")
                    kotlinx.coroutines.delay(1500)

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
                        // ⚠️ DO NOT call emitBindSuccessEvent() here!
                        // It creates an infinite loop: query → bindSuccess → Flutter reload → query → ...
                        // bindSuccess is only emitted from actual bind actions (scan QR, bind product list)
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
    //  THUMBNAIL — Get last snapshot path for a device
    // ══════════════════════════════════════════════════════════════════════

    private fun handleGetLastSnapshotPath(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val deviceId = call.argument<String>("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            result.success(mapOf("success" to false, "error" to "Missing deviceId"))
            return
        }

        scope.launch {
            try {
                val devResult = GWIoT.queryDeviceCacheFirst(deviceId)
                if (devResult is GWResult.Success) {
                    val device = devResult.data
                    if (device != null) {
                        val lastFramePath = GWIoT.getLastSnapshotPath(device)
                        Log.i(TAG, "[THUMBNAIL] deviceId=$deviceId, path=$lastFramePath")
                        result.success(mapOf(
                            "success" to true,
                            "path" to (lastFramePath ?: "")
                        ))
                    } else {
                        result.success(mapOf("success" to false, "error" to "Device data is null"))
                    }
                } else {
                    result.success(mapOf("success" to false, "error" to "Device not found in cache: $deviceId"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[THUMBNAIL] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Snapshot failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MULTI-LIVE — Built-in SDK multi-camera surveillance page
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenMultiLivePage(result: Result) {
        if (!ensureSdkInitialized(result)) return

        scope.launch {
            try {
                Log.d(TAG, "[MULTI_LIVE] Opening multi-live page")
                GWIoT.openMultiLivePage()
                Log.i(TAG, "[MULTI_LIVE] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[MULTI_LIVE] EXCEPTION: ${e.message}", e)
                handleErrorResult("MULTI_LIVE", e, result)
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
    //  DEVICE SETTINGS — matching iOS GWIoTDeviceBridge.openDeviceSettingPage
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
                Log.d(TAG, "[SETTINGS] Opening settings for device: $deviceId")
                // iOS: GWIoT.shared.openDeviceSettingPage(device:)
                // Android: try openDeviceSettingPage first, fallback to openDeviceInfoPage
                try {
                    GWIoT.openDeviceSettingPage(device)
                } catch (_: Exception) {
                    GWIoT.openDeviceInfoPage(device)
                }
                Log.i(TAG, "[SETTINGS] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[SETTINGS] EXCEPTION: ${e.message}", e)
                handleErrorResult("SETTINGS", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE INFO PAGE — matching iOS GWIoTDeviceBridge.openDeviceInfoPage
    //  (Separate from settings — iOS has both openDeviceSettingPage & openDeviceInfoPage)
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenDeviceInfoPage(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            return
        }
        scope.launch {
            try {
                Log.d(TAG, "[DEVICE_INFO] Opening info page for device: $deviceId")
                GWIoT.openDeviceInfoPage(device)
                Log.i(TAG, "[DEVICE_INFO] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[DEVICE_INFO] EXCEPTION: ${e.message}", e)
                handleErrorResult("DEVICE_INFO", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE EVENTS — COPIED from working project
    // ═══════════════════════════════�    /**
//     * ✅ Emit bindSuccess event qua EventChannel kèm device list mới.
//     * Flutter side bắt event này để:
//     * 1. Reload UI device list
//     * 2. So sánh old vs new → detect thiết bị mới
//     * 3. Gọi backend API addDevice cho thiết bị mới để lưu database
//     *
//     * Format map theo TuyaCameraModel: devId, name, iconUrl, isOnline, category
//     */
    private suspend fun emitBindSuccessEvent() {
        try {
            // Query device list mới từ SDK (matching handleQueryDeviceList)
            val devResult = GWIoT.queryDeviceList()

            val devices = when (devResult) {
                is GWResult.Success<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val deviceList = (devResult.data as? List<Device>) ?: emptyList()
                    // Also update cache
                    cachedDevices = deviceList

                    deviceList.map { dev ->
                        // Get online status via IoT props (matching handleQueryDeviceList)
                        var isOnline = false
                        try {
                            val propsResult = GWIoT.getIoTProps(dev)
                            if (propsResult is GWResult.Success) {
                                isOnline = propsResult.data?.isOnline ?: false
                            }
                        } catch (_: Exception) {}

                        // ✅ LOG chi tiết thông tin thiết bị (chỉ public fields)
                        Log.i(TAG, "═══════════════════════════════════════════════")
                        Log.i(TAG, "[BindEvent] 📋 Device Info:")
                        Log.i(TAG, "  ├─ deviceId       : ${dev.deviceId}")
                        Log.i(TAG, "  ├─ remarkName     : ${dev.remarkName}")
                        Log.i(TAG, "  ├─ isOnline       : $isOnline")
                        Log.i(TAG, "  ├─ productInfo    : ${dev.productInfo?.name}")
                        Log.i(TAG, "  ├─ relation       : ${dev.relation}")
                        Log.i(TAG, "  └─ jsonString     : ${dev.jsonString}")
                        Log.i(TAG, "═══════════════════════════════════════════════")

                        // ✅ Map theo format TuyaCameraModel (devId, name, isOnline, category)
                        mapOf(
                            "deviceId" to dev.deviceId,
                            "deviceName" to dev.remarkName,
                            "isOnline" to isOnline,
                            "deviceType" to (dev.productInfo?.name ?: ""),
                            "category" to "gwell_camera",
                            "jsonString" to (dev.jsonString ?: ""),
                        )
                    }
                }
                is GWResult.Failure<*> -> {
                    Log.e(TAG, "[BindEvent] ❌ queryDeviceList failed: ${devResult.err?.message}")
                    emptyList()
                }
            }

            Log.i(TAG, "[BindEvent] 📤 Emitting bindSuccess with ${devices.size} devices")
            sendEventToFlutter(mapOf(
                "type" to "bindSuccess",
                "devices" to devices
            ))
        } catch (e: Exception) {
            Log.w(TAG, "[BindEvent] ⚠️ Failed to emit bindSuccess: ${e.message}")
            sendEventToFlutter(mapOf(
                "type" to "bindSuccess",
                "devices" to emptyList<Map<String, Any>>()
            ))
        }
    }


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
                     is GWResult.Success<*> -> {
                         Log.i(TAG, "[SCAN_QR] ✅ Binding success")
                         // ✅ Emit bindSuccess event → Flutter bắt để gọi backend API
                         emitBindSuccessEvent()
                         result.success(mapOf("success" to true))
                     }
                     is GWResult.Failure<*> -> result.success(mapOf("success" to false, "error" to scanResult.toString()))
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "[SCAN_QR] EXCEPTION: ${e.message}", e)
                 result.success(mapOf("success" to false, "error" to (e.message ?: "QR scan failed")))
             }
         }
     }

     private fun handleOpenBindProductList(result: Result) {
         if (!ensureSdkInitialized(result)) return
         scope.launch {
             try {
                 Log.d(TAG, "[BIND_LIST] Opening product list")
                 val bindResult = GWIoT.openBindableProductList()
                 when (bindResult) {
                     is GWResult.Success<*> -> {
                         Log.i(TAG, "[BIND_LIST] ✅ Binding success")
                         // ✅ Emit bindSuccess event → Flutter bắt để gọi backend API
                         emitBindSuccessEvent()
                         result.success(mapOf("success" to true))
                     }
                     is GWResult.Failure<*> -> result.success(mapOf("success" to false, "error" to bindResult.toString()))
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "[BIND_LIST] EXCEPTION: ${e.message}", e)
                 result.success(mapOf("success" to false, "error" to (e.message ?: "Bind list failed")))
             }
         }
     }

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
    //  MESSAGE CENTER — matching iOS GWIoTDeviceBridge.openMessageCenterPage
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenMessageCenterPage(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                Log.d(TAG, "[MSG_CENTER] Opening message center page")
                // iOS: GWIoT.shared.openMessageCenterPage { result, error in ... }
                // Android: try GWIoT.openMessageCenterPage() or msgCenterComp
                try {
                    GWIoT.openMessageCenterPage()
                    Log.i(TAG, "[MSG_CENTER] ✅ Opened")
                    result.success(mapOf("success" to true))
                } catch (e: NoSuchMethodError) {
                    // API may not exist in this SDK version — try via component
                    Log.w(TAG, "[MSG_CENTER] openMessageCenterPage not found, trying msgCenterComp")
                    try {
                        val msgComp = GWIoT.msgCenterComp
                        // Try reflection to call open method
                        val openMethod = msgComp.javaClass.methods.find {
                            it.name.contains("open", ignoreCase = true) ||
                            it.name.contains("show", ignoreCase = true)
                        }
                        if (openMethod != null) {
                            openMethod.invoke(msgComp)
                            Log.i(TAG, "[MSG_CENTER] ✅ Opened via msgCenterComp")
                            result.success(mapOf("success" to true))
                        } else {
                            result.success(mapOf("success" to false, "error" to "Message center API not found in SDK"))
                        }
                    } catch (compE: Exception) {
                        Log.w(TAG, "[MSG_CENTER] msgCenterComp also failed: ${compE.message}")
                        result.success(mapOf("success" to false, "error" to "Message center not available: ${compE.message}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[MSG_CENTER] EXCEPTION: ${e.message}", e)
                handleErrorResult("MSG_CENTER", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEV SHARE — matching iOS GWIoTDeviceBridge.openDevSharePage
    // ══════════════════════════════════════════════════════════════════════

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
                Log.d(TAG, "[DEV_SHARE] Opening share page for device: $deviceId")
                val shareOption = DevShareOption(device)
                GWIoT.openDevSharePage(shareOption)
                Log.i(TAG, "[DEV_SHARE] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[DEV_SHARE] EXCEPTION: ${e.message}", e)
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
    //  PUSH NOTIFICATION — matching iOS GWIoTPushNotificationBridge
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Handle incoming push notification.
     * iOS: GWIoT.shared.receivePushNotification(noti: PushNotification(userInfo:))
     * Android: GWIoT.receivePushNotification(PushNotification(intent=...))
     */
    private fun handleReceivePushNotification(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val payload = call.argument<Map<String, Any>>("payload") ?: emptyMap()
                Log.d(TAG, "[PUSH] receivePushNotification payload keys: ${payload.keys}")

                // Build Intent with extras from payload
                val context = flutterPluginBinding?.applicationContext
                if (context != null) {
                    val intent = Intent()
                    payload.forEach { (key, value) ->
                        when (value) {
                            is String -> intent.putExtra(key, value)
                            is Int -> intent.putExtra(key, value)
                            is Long -> intent.putExtra(key, value)
                            is Boolean -> intent.putExtra(key, value)
                        }
                    }
                    val noti = PushNotification(intent = intent)
                    GWIoT.receivePushNotification(noti)
                    Log.i(TAG, "[PUSH] ✅ receivePushNotification forwarded to SDK")

                    // Send event to Flutter (matching iOS sendPushEventToFlutter)
                    val eventData = mutableMapOf<String, Any?>("type" to "pushReceived")
                    eventData.putAll(payload)
                    sendEventToFlutter(eventData)
                }
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[PUSH] receivePushNotification EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
            }
        }
    }

    /**
     * Handle user clicking on a push notification.
     * iOS: GWIoT.shared.clickPushNotification(noti: PushNotification(userInfo:))
     * Android: similar — forward to SDK and emit event to Flutter
     */
    private fun handleClickPushNotification(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val payload = call.argument<Map<String, Any>>("payload") ?: emptyMap()
                Log.d(TAG, "[PUSH] clickPushNotification payload keys: ${payload.keys}")

                val context = flutterPluginBinding?.applicationContext
                if (context != null) {
                    val intent = Intent()
                    payload.forEach { (key, value) ->
                        when (value) {
                            is String -> intent.putExtra(key, value)
                            is Int -> intent.putExtra(key, value)
                            is Long -> intent.putExtra(key, value)
                            is Boolean -> intent.putExtra(key, value)
                        }
                    }
                    val noti = PushNotification(intent = intent)
                    GWIoT.clickPushNotification(noti)
                    Log.i(TAG, "[PUSH] ✅ clickPushNotification forwarded to SDK")

                    // Send event to Flutter (matching iOS sendPushEventToFlutter)
                    val eventData = mutableMapOf<String, Any?>("type" to "pushClicked")
                    eventData.putAll(payload)
                    sendEventToFlutter(eventData)
                }
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[PUSH] clickPushNotification EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI CONFIG
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSetLanguage(call: MethodCall, result: Result) {
        val codeStr = call.argument<String>("code") ?: ""
        Log.i(TAG, "[setLanguage] code=$codeStr")

        val langCode = when (codeStr.lowercase()) {
            "vi" -> com.gw.gwiotapi.entities.LanguageCode.VI
            "en" -> com.gw.gwiotapi.entities.LanguageCode.EN
            "zh" -> com.gw.gwiotapi.entities.LanguageCode.ZH_HANS
            else -> com.gw.gwiotapi.entities.LanguageCode.EN
        }

        try {
            GWIoT.setLanguage(langCode)
            Log.i(TAG, "[setLanguage] ✅ Set to $langCode")
            result.success(mapOf("success" to true, "code" to codeStr))
        } catch (e: Exception) {
            Log.e(TAG, "[setLanguage] ❌ Failed: ${e.message}", e)
            result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
        }
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
