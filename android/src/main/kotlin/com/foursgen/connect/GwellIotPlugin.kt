package com.foursgen.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.gw.gwiotapi.GWIoT
import com.gw.gwiotapi.entities.AppTexts
import com.gw.gwiotapi.entities.DevShareOption
import com.gw.gwiotapi.entities.Device
import com.gw.gwiotapi.entities.DeviceEvent
import com.gw.gwiotapi.entities.GWResult
import com.gw.gwiotapi.entities.OpenPluginOption
import com.gw.gwiotapi.entities.PlaybackOption
import com.gw.gwiotapi.entities.PushNotification
import com.gw.gwiotapi.entities.ScanQRCodeOptions
import com.gw.gwiotapi.entities.Theme
import com.gw.gwiotapi.entities.UIConfiguration
import com.gw.gwiotapi.entities.UserC2CInfo
import com.gw.gwiotapi.ext.productInfo
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * GwellIotPlugin — Android MethodChannel plugin for GWIoT SDK.
 *
 * Based on official demo: https://github.com/reoqoo/gwiotapi/tree/master/android/demo
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

    private var cachedDevices: List<Device> = emptyList()
    private var lastRegRegion: String = "SG"
    private var lastAreaCode: String = "sg"
    private var wasLoggedIn: Boolean = false
    private var lastEmittedDeviceIds: Set<String> = emptySet() // Throttle deviceList observer

    // ── FlutterPlugin ─────────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding
        channel = MethodChannel(binding.binaryMessenger, "com.reoqoo/gwiot")
        channel.setMethodCallHandler(this)

        eventChannel = EventChannel(binding.binaryMessenger, "com.reoqoo/gwiot_events")
        eventChannel?.setStreamHandler(this)

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
        Log.i(TAG, "[EventChannel] 🎧 Flutter started listening")
        eventSink = events
        wasLoggedIn = try { GWIoT.isLogin.value ?: false } catch (_: Exception) { false }
        startObservingLiveData()
    }

    override fun onCancel(arguments: Any?) {
        Log.i(TAG, "[EventChannel] 🔇 Flutter stopped listening")
        eventSink = null
    }

    private fun sendEventToFlutter(data: Map<String, Any?>) {
        scope.launch {
            try {
                eventSink?.success(data)
                Log.d(TAG, "[EventChannel] 📤 Sent: ${data["type"]}")
            } catch (e: Exception) {
                Log.w(TAG, "[EventChannel] ⚠️ Send failed: ${e.message}")
            }
        }
    }

    private fun startObservingLiveData() {
        if (!GwellSdkInitializer.sdkInitialized) {
            Log.w(TAG, "[EventChannel] SDK not initialized, skipping observers")
            return
        }

        // Login status
        try {
            GWIoT.isLogin.observeForever { isLoggedIn ->
                val loggedIn = isLoggedIn ?: false
                if (wasLoggedIn && !loggedIn) {
                    Log.i(TAG, "[EventChannel] 🔑 Token expired (isLogin: true → false)")
                    sendEventToFlutter(mapOf("type" to "tokenExpired"))
                }
                wasLoggedIn = loggedIn
                sendEventToFlutter(mapOf("type" to "loginStatusChanged", "isLoggedIn" to loggedIn))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] isLogin observe failed: ${e.message}")
        }

        // Device list — throttled: only emit when device IDs actually change
        try {
            GWIoT.deviceList.observeForever { devices ->
                val deviceInstances = (devices as? List<*>)?.filterIsInstance<Device>() ?: emptyList()
                val currentIds = deviceInstances.map { it.deviceId }.toSet()

                if (currentIds != lastEmittedDeviceIds) {
                    val previousIds = lastEmittedDeviceIds // snapshot before update
                    lastEmittedDeviceIds = currentIds
                    scope.launch {
                        val list = deviceInstances.map { dev ->
                            var isOnline = false
                            try {
                                val propsResult = GWIoT.getIoTProps(dev)
                                if (propsResult is GWResult.Success) isOnline = propsResult.data?.isOnline ?: false
                            } catch (_: Exception) {}
                            mapOf(
                                "deviceId" to dev.deviceId,
                                "deviceName" to dev.remarkName,
                                "isOnline" to isOnline,
                                "deviceType" to (dev.productInfo?.name ?: ""),
                                "category" to "gwell_camera",
                                "relation" to (dev.relation.name),
                                "solution" to (dev.solution.name),
                                "jsonString" to (dev.jsonString ?: ""),
                            ).plus(extractSaasFields(dev.jsonString))
                        }
                        Log.i(TAG, "[EventChannel] 📋 deviceList changed: ${list.size} devices")
                        sendEventToFlutter(mapOf("type" to "deviceListUpdated", "devices" to list))

                        // ✅ Auto-emit bindSuccess khi phát hiện device mới được bind
                        if (currentIds.size > previousIds.size) {
                            val newIds = currentIds - previousIds
                            Log.i(TAG, "[EventChannel] 🎉 New device(s) detected: $newIds → emitting bindSuccess")
                            kotlinx.coroutines.delay(500) // Đợi deviceListUpdated được xử lý trước
                            emitBindSuccessEvent()
                        }
                    }
                } else {
                    Log.d(TAG, "[EventChannel] ⏭️ deviceList unchanged (${currentIds.size} devices), skipping")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] deviceList observe failed: ${e.message}")
        }

        // Account events
        try {
            GWIoT.accountEvent.observeForever { event ->
                if (event == null) return@observeForever
                val eventDesc = event.toString().lowercase()
                Log.i(TAG, "[EventChannel] 📋 AccountEvent: $eventDesc")
                if (eventDesc.contains("token") || eventDesc.contains("expire") || eventDesc.contains("auth")) {
                    sendEventToFlutter(mapOf("type" to "tokenExpired"))
                }
                sendEventToFlutter(mapOf("type" to "accountEvent", "event" to event.toString()))
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] accountEvent observe failed: ${e.message}")
        }

        // Device events
        try {
            GWIoT.deviceEvents.observeForever { event ->

                if (event == null) return@observeForever
                Log.i(TAG, "[EventChannel] 📋 DeviceEvent: $event")
                when (event) {
                    is DeviceEvent.DeviceDeleted -> {
                        sendEventToFlutter(mapOf("type" to "deviceDeleted", "deviceId" to event.deviceId))
                    }
                    is DeviceEvent.NameChanged -> {
                        sendEventToFlutter(mapOf(
                            "type" to "deviceNameChanged",
                            "deviceId" to event.deviceId,
                            "newName" to event.name
                        ))
                    }
                    is DeviceEvent.SharingDeviceAccepted -> {
                        sendEventToFlutter(mapOf("type" to "sharingDeviceAccepted", "deviceId" to event.deviceId))
                        scope.launch { emitBindSuccessEvent() }
                    }
                    else -> Log.d(TAG, "[EventChannel] Unknown DeviceEvent: $event")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[EventChannel] deviceEvents observe failed: ${e.message}")
        }
    }

    // ── ActivityAware ─────────────────────────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivityForConfigChanges() { activity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivity() { activity = null }

    // ── Method Dispatch ───────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            // SDK Init
            "initGwellSdk" -> handleInitGwellSdk(call, result)
            "getPhoneUniqueId" -> handleGetPhoneUniqueId(result)

            // Auth
            "loginSDK", "loginWithC2CInfo" -> handleLoginWithC2CInfo(call, result)
            "checkLoginStatus", "isLoggedIn" -> handleCheckLoginStatus(result)
            "logout" -> handleLogout(result)

            // Backend Credentials
            "saveBackendCredentials" -> handleSaveBackendCredentials(call, result)
            "getBackendCredentials" -> handleGetBackendCredentials(result)
            "clearBackendCredentials" -> handleClearBackendCredentials(result)

            // Push Token
            "uploadPushToken" -> handleUploadPushToken(call, result)

            // Device Management
            "queryDeviceList", "getDeviceList" -> handleQueryDeviceList(result)
            "getLastSnapshotPath" -> handleGetLastSnapshotPath(call, result)
            "unbindDevice", "deleteDevice" -> handleUnbindDevice(call, result)

            // Player
            "openLiveView", "openDeviceHome" -> handleOpenLiveView(call, result)
            "openPlayback" -> handleOpenPlayback(call, result)
            "openPlaybackWithTime" -> handleOpenPlaybackWithTime(call, result)
            "getCloudPlaybackPermission" -> handleGetCloudPlaybackPermission(call, result)

            // QR & Binding
            "openScanQRCode" -> handleOpenScanQRCode(result)
            "openBindProductList", "openProductList" -> handleOpenBindProductList(result)
            "openBindByQRCode" -> handleOpenBindByQRCode(call, result)

            // Device Pages
            "openDeviceSettings", "openDeviceSettingPage" -> handleOpenDeviceSettings(call, result)
            "openDeviceInfoPage" -> handleOpenDeviceInfoPage(call, result)
            "openDeviceEvents", "openEventsPage" -> handleOpenDeviceEvents(call, result)
            "openMessageCenterPage" -> handleOpenMessageCenterPage(result)
            "openDevSharePage" -> handleOpenDevSharePage(call, result)
            "openShareManagerPage" -> handleOpenShareManagerPage(result)

            // Multi-Live
            "openMultiLivePage" -> handleOpenMultiLivePage(result)

            // Firmware Upgrade
            "checkDevUpgradeInfo" -> handleCheckDevUpgradeInfo(call, result)
            "batchCheckDevUpgradeInfo" -> handleBatchCheckDevUpgradeInfo(call, result)
            "openBatchUpgradePage" -> handleOpenBatchUpgradePage(result)

            // Cloud Service & Membership
            "openCloudPage", "openCloudStoragePage" -> handleOpenCloudPage(call, result)
            "queryMembershipInfo" -> handleQueryMembershipInfo(call, result)
            "openMembershipCenterPage" -> handleOpenMembershipCenterPage(result)

            // Album
            "openAlbum" -> handleOpenAlbum(call, result)

            // Push Notification
            "receivePushNotification" -> handleReceivePushNotification(call, result)
            "clickPushNotification" -> handleClickPushNotification(call, result)

            // UI Config
            "setLanguage" -> handleSetLanguage(call, result)
            "setUIConfiguration" -> handleSetUIConfiguration(call, result)

            else -> result.notImplemented()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SDK INIT
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
            // Resolve host app's main Activity class for SDK navigation
            val mainActivityClass: Class<android.app.Activity>? = try {
                activity?.javaClass?.let {
                    @Suppress("UNCHECKED_CAST")
                    it as Class<android.app.Activity>
                }
            } catch (_: Exception) { null }

            GwellSdkInitializer.init(app, appId, appToken, language, mainActivityClass)
            Log.i(TAG, "[INIT_SDK] ✅ GWIoT SDK initialized")
            result.success(mapOf("success" to true, "message" to "SDK initialized"))
        } catch (e: Exception) {
            Log.e(TAG, "[INIT_SDK] ❌ ${e.message}", e)
            result.success(mapOf("success" to false, "message" to (e.message ?: "SDK init failed")))
        }
    }

    private fun handleGetPhoneUniqueId(result: Result) {
        try {
            val ret = GWIoT.phoneUniqueId()
            if (ret is GWResult.Success) {
                result.success(mapOf("success" to true, "phoneUniqueId" to "${ret.data ?: ""}"))
            } else {
                result.success(mapOf("success" to false, "phoneUniqueId" to ""))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[PHONE_ID] ❌ ${e.message}", e)
            result.success(mapOf("success" to false, "phoneUniqueId" to ""))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AUTH — C2C Login (matching demo: GWIoT.login(info))
    // ══════════════════════════════════════════════════════════════════════

    private fun handleLoginWithC2CInfo(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val accessId = call.argument<String>("accessId") ?: ""
        val accessToken = call.argument<String>("accessToken") ?: ""
        val expireTime = call.argument<String>("expireTime") ?: ""
        val terminalId = call.argument<String>("terminalId") ?: ""
        val expandFromFlutter = call.argument<String>("expand")
        val region = call.argument<String>("region") ?: "VN"

        if (accessId.isBlank() || accessToken.isBlank()) {
            result.error("INVALID_ARGS", "accessId and accessToken are required", null)
            return
        }

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
                Log.d(TAG, "[C2C_LOGIN] accessId=$accessId, area=${c2cInfo.area}, regRegion=${c2cInfo.regRegion}")

                lastRegRegion = c2cInfo.regRegion ?: region.uppercase()
                lastAreaCode = c2cInfo.area ?: RegionMapper.getAreaCode(region)

                val loginResult = GWIoT.login2(c2cInfo)
                Log.d(TAG, "[C2C_LOGIN] login2 result: $loginResult, isLogin=${GWIoT.isLogin.value}")

                if (loginResult is GWResult.Success) {
                    // Wait for SDK components to mount after login
                    kotlinx.coroutines.delay(1500)
                    Log.i(TAG, "[C2C_LOGIN] ✅ Success")
                    result.success(mapOf("success" to true, "message" to "C2C login2 successful"))
                } else if (loginResult is GWResult.Failure) {
                    Log.e(TAG, "[C2C_LOGIN] ❌ ${loginResult.err}")
                    result.success(mapOf("success" to false, "message" to "login2 failed: ${loginResult.err}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[C2C_LOGIN] ❌ ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "C2C login failed")))
            }
        }
    }

    private fun handleCheckLoginStatus(result: Result) {
        if (!GwellSdkInitializer.sdkInitialized) {
            result.success(mapOf("isLoggedIn" to false))
            return
        }
        try {
            val isLoggedIn = GWIoT.isLogin.value ?: false
            result.success(mapOf("isLoggedIn" to isLoggedIn))
        } catch (e: Exception) {
            result.success(mapOf("isLoggedIn" to false))
        }
    }

    private fun handleLogout(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                GWIoT.logout()
                cachedDevices = emptyList()
                Log.i(TAG, "[logout] ✅ Success")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[logout] ❌ ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Logout failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE LIST (matching demo: GWIoT.queryDeviceList())
    // ══════════════════════════════════════════════════════════════════════

    private fun handleQueryDeviceList(result: Result) {
        if (!ensureSdkInitialized(result)) return

        scope.launch {
            try {
                val devResult = GWIoT.queryDeviceList()
                Log.d("devResult","devResult::::$devResult")
                when (devResult) {
                    is GWResult.Success<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val devices = (devResult.data as? List<Device>) ?: emptyList()
                        cachedDevices = devices

                        val list = devices.map { dev ->
                            var isOnline = false
                            try {
                                val propsResult = GWIoT.getIoTProps(dev)
                                if (propsResult is GWResult.Success) isOnline = propsResult.data?.isOnline ?: false
                            } catch (_: Exception) {}

                            mapOf(
                                "deviceId" to dev.deviceId,
                                "deviceName" to dev.remarkName,
                                "isOnline" to isOnline,
                                "deviceType" to (dev.productInfo?.name ?: ""),
                                "category" to "gwell_camera",
                                "relation" to (dev.relation.name),
                                "solution" to (dev.solution.name),
                                "jsonString" to (dev.jsonString ?: ""),
                            ).plus(extractSaasFields(dev.jsonString))
                        }
                        Log.i(TAG, "[DEVICE_LIST] ✅ Found ${list.size} devices")
                        result.success(mapOf("success" to true, "devices" to list))
                    }
                    is GWResult.Failure<*> -> {
                        val errMsg = devResult.err?.message ?: "Unknown error"
                        val reason = devResult.err?.reason?.toString() ?: "Unknown reason"
                        Log.e(TAG, "[DEVICE_LIST] ❌ $errMsg ($reason)")
                        val responseMap = mutableMapOf<String, Any>("success" to false, "error" to "[$reason] $errMsg")
                        if (isSignatureError(errMsg, reason)) {
                            responseMap["errorCode"] = "10007"
                            responseMap["isSignatureError"] = true
                        }
                        result.success(responseMap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[DEVICE_LIST] ❌ ${e.message}", e)
                result.success(mapOf("success" to false, "error" to (e.message ?: "Query failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PLAYER (matching demo: GWIoT.openHome / openPlayback)
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenLiveView(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId) ?: run {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId. Try refreshing the device list."))
            return
        }
        scope.launch {
            try {
                GWIoT.openHome(OpenPluginOption(device))
                Log.i(TAG, "[LIVE_VIEW] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("LIVE_VIEW", e, result)
            }
        }
    }

    private fun handleOpenPlayback(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId) ?: run {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            return
        }
        scope.launch {
            try {
                GWIoT.openPlayback(PlaybackOption(device))
                Log.i(TAG, "[PLAYBACK] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("PLAYBACK", e, result)
            }
        }
    }

    private fun handleOpenPlaybackWithTime(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val startTime = call.argument<Long>("startTime") ?: call.argument<Int>("startTime")?.toLong()
        val device = findDevice(deviceId) ?: run {
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

    private fun handleGetCloudPlaybackPermission(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId) ?: run {
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
            } catch (_: Exception) {
                result.success(mapOf("success" to false, "hasPermission" to false))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  THUMBNAIL
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
                if (devResult is GWResult.Success && devResult.data != null) {
                    val path = GWIoT.getLastSnapshotPath(devResult.data!!)
                    result.success(mapOf("success" to true, "path" to (path ?: "")))
                } else {
                    result.success(mapOf("success" to false, "error" to "Device not found in cache: $deviceId"))
                }
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "Snapshot failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  QR & BINDING (matching demo: GWIoT.openBind / openScanQRCodePage)
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenScanQRCode(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val opts = ScanQRCodeOptions(
                    enableBuiltInHandling = true,
                    title = "Quét mã QR",
                    descTitle = "Quét mã QR trên thiết bị hoặc mã chia sẻ"
                )
                val scanResult = GWIoT.openScanQRCodePage(opts)
                Log.d("[SCAN_QR]","[SCAN_QR]::::$scanResult")
                // enableBuiltInHandling=true → SDK tự bind internally
                // openScanQRCodePage returns BEFORE binding completes
                // → Emit bindSuccess sau delay để đợi SDK finish bind
                when (scanResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[SCAN_QR] ✅ QR recognized — waiting for SDK bind to complete...")
                        // Emit bindSuccess sau 4s để đảm bảo device đã vào deviceList
                        scope.launch {
                            kotlinx.coroutines.delay(4000)
                            Log.i(TAG, "[SCAN_QR] ⏰ Emitting bindSuccess after delay")
                            emitBindSuccessEvent()
                        }
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> {
                        Log.i(TAG, "[SCAN_QR] ⚠️ SDK returned Failure (expected with enableBuiltInHandling=true): $scanResult")
                        // NOTE: SDK ALWAYS returns Failure với enableBuiltInHandling=true,
                        // nhưng vẫn mở ScanQRCodeActivity và tự bind async.
                        // bindSuccess sẽ được emit bởi deviceList observer khi device mới xuất hiện.
                        result.success(mapOf("success" to true, "sdkResult" to scanResult.toString()))
                    }
                }
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "QR scan failed")))
            }
        }
    }

    private fun handleOpenBindProductList(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val bindResult = GWIoT.openBindableProductList()
                when (bindResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[BIND_LIST] ✅ Binding success")
                        emitBindSuccessEvent()
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> result.success(mapOf("success" to false, "error" to bindResult.toString()))
                }
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "Bind list failed")))
            }
        }
    }

    private fun handleOpenBindByQRCode(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val qrValue = call.argument<String>("qrValue") ?: ""
        if (qrValue.isBlank()) {
            result.error("INVALID_ARGS", "Missing qrValue", null)
            return
        }
        scope.launch {
            try {
                // Use recognizeQRCode to process QR value directly
                // enableBuiltInHandling=true → SDK auto-handles bind/share flow
                val recognizeResult = GWIoT.recognizeQRCode(qrValue, true)
                Log.d(TAG, "[BIND_QR] recognizeQRCode result: $recognizeResult")

                when (recognizeResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[BIND_QR] ✅ QR recognized → waiting 4s then emitting bindSuccess")
                        scope.launch {
                            kotlinx.coroutines.delay(4000)
                            emitBindSuccessEvent()
                        }
                        result.success(mapOf(
                            "success" to true,
                            "qrType" to recognizeResult.data.toString()
                        ))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf(
                            "success" to false,
                            "error" to recognizeResult.toString()
                        ))
                    }
                }
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "recognizeQRCode failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE PAGES
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenDeviceSettings(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val device = findDeviceOrError(call, result) ?: return
        scope.launch {
            try {
                try { GWIoT.openDeviceSettingPage(device) } catch (_: Exception) { GWIoT.openDeviceInfoPage(device) }
                Log.i(TAG, "[SETTINGS] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("SETTINGS", e, result)
            }
        }
    }

    private fun handleOpenDeviceInfoPage(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val device = findDeviceOrError(call, result) ?: return
        scope.launch {
            try {
                GWIoT.openDeviceInfoPage(device)
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("DEVICE_INFO", e, result)
            }
        }
    }

    private fun handleOpenDeviceEvents(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val device = findDeviceOrError(call, result) ?: return
        scope.launch {
            try {
                GWIoT.openEventsPage(device)
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("EVENTS", e, result)
            }
        }
    }

    private fun handleOpenMessageCenterPage(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                GWIoT.openMessageCenterPage()
                Log.i(TAG, "[MSG_CENTER] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                Log.e(TAG, "[MSG_CENTER] ❌ ${e.message}", e)
                handleErrorResult("MSG_CENTER", e, result)
            }
        }
    }

    private fun handleOpenDevSharePage(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val device = findDeviceOrError(call, result) ?: return
        scope.launch {
            try {
                GWIoT.openDevSharePage(DevShareOption(device))
                Log.i(TAG, "[DEV_SHARE] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("DEV_SHARE", e, result)
            }
        }
    }

    private fun handleOpenMultiLivePage(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                GWIoT.openMultiLivePage()
                
                Log.i(TAG, "[MULTI_LIVE] ✅ Opened")
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                handleErrorResult("MULTI_LIVE", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BACKEND CREDENTIALS
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSaveBackendCredentials(call: MethodCall, result: Result) {
        val context = activity?.applicationContext ?: run {
            result.success(mapOf("success" to false, "error" to "No context"))
            return
        }
        try {
            context.getSharedPreferences("gwell_credentials", Context.MODE_PRIVATE).edit()
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
        val context = activity?.applicationContext ?: run {
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
        } catch (_: Exception) {
            result.success(emptyMap<String, Any?>())
        }
    }

    private fun handleClearBackendCredentials(result: Result) {
        try {
            activity?.applicationContext
                ?.getSharedPreferences("gwell_credentials", Context.MODE_PRIVATE)
                ?.edit()?.clear()?.apply()
        } catch (_: Exception) {}
        result.success(mapOf("success" to true))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUSH
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

    /**
     * Handle incoming push notification.
     * Matching demo: GWIoT.receivePushNotification(PushNotification(intent=...))
     */
    private fun handleReceivePushNotification(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val payload = call.argument<Map<String, Any>>("payload") ?: emptyMap()
                val intent = buildIntentFromPayload(payload)
                if (intent != null) {
                    GWIoT.receivePushNotification(PushNotification(intent = intent))
                    Log.i(TAG, "[PUSH] ✅ receivePushNotification forwarded")
                    sendEventToFlutter(mutableMapOf<String, Any?>("type" to "pushReceived").apply { putAll(payload) })
                }
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
            }
        }
    }

    private fun handleClickPushNotification(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val payload = call.argument<Map<String, Any>>("payload") ?: emptyMap()
                val intent = buildIntentFromPayload(payload)
                if (intent != null) {
                    GWIoT.clickPushNotification(PushNotification(intent = intent))
                    Log.i(TAG, "[PUSH] ✅ clickPushNotification forwarded")
                    sendEventToFlutter(mutableMapOf<String, Any?>("type" to "pushClicked").apply { putAll(payload) })
                }
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI CONFIG
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSetLanguage(call: MethodCall, result: Result) {
        val codeStr = call.argument<String>("code") ?: ""
        val langCode = when (codeStr.lowercase()) {
            "vi" -> com.gw.gwiotapi.entities.LanguageCode.VI
            "en" -> com.gw.gwiotapi.entities.LanguageCode.EN
            "zh" -> com.gw.gwiotapi.entities.LanguageCode.ZH_HANS
            else -> com.gw.gwiotapi.entities.LanguageCode.EN
        }
        try {
            GWIoT.setLanguage(langCode)
            result.success(mapOf("success" to true, "code" to codeStr))
        } catch (e: Exception) {
            result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
        }
    }

    private fun handleSetUIConfiguration(call: MethodCall, result: Result) {
        try {
            val isDarkMode = call.argument<Boolean>("isDarkMode") ?: false
            val brandColor = call.argument<String>("brandColor") ?: "#FF4CAF50"
            val appName = call.argument<String>("appName") ?: "Gwell IoT"

            // Default color palettes
            val defaultLight = mapOf(
                "brandColor" to brandColor,
                "brandHighlight" to "#FF66BB6A",
                "brandDisable" to "#FF81C784",
                "brand2" to "#FF2196F3",
                "brand2Highlight" to "#FF42A5F5",
                "brand2Disable" to "#FF90CAF9",
                "textColor" to "#FF212121",
                "secondaryTextColor" to "#FF757575",
                "tertiaryTextColor" to "#FF9E9E9E",
                "lightTextColor" to "#FFFFFFFF",
                "linkTextColor" to "#FF1976D2",
                "mainBackground" to "#FFFFFFFF",
                "secondaryBackground" to "#FFF5F5F5",
                "maskBackground" to "#80000000",
                "hudBackground" to "#CC000000",
                "separatorLineColor" to "#FFE0E0E0",
                "inputLineEnable" to brandColor,
                "inputLineDisable" to "#FFBDBDBD",
                "stateSafe" to "#FF4CAF50",
                "stateWarning" to "#FFFFC107",
                "stateError" to "#FFF44336",
            )

            val defaultDark = mapOf(
                "brandColor" to brandColor,
                "brandHighlight" to "#FF66BB6A",
                "brandDisable" to "#FF388E3C",
                "brand2" to "#FF2196F3",
                "brand2Highlight" to "#FF42A5F5",
                "brand2Disable" to "#FF1565C0",
                "textColor" to "#FFFFFFFF",
                "secondaryTextColor" to "#B3FFFFFF",
                "tertiaryTextColor" to "#80FFFFFF",
                "lightTextColor" to "#FFFFFFFF",
                "linkTextColor" to "#FF64B5F6",
                "mainBackground" to "#FF121212",
                "secondaryBackground" to "#FF1E1E1E",
                "maskBackground" to "#80000000",
                "hudBackground" to "#CC333333",
                "separatorLineColor" to "#FF333333",
                "inputLineEnable" to brandColor,
                "inputLineDisable" to "#FF555555",
                "stateSafe" to "#FF4CAF50",
                "stateWarning" to "#FFFFC107",
                "stateError" to "#FFF44336",
            )

            val defaults = if (isDarkMode) defaultDark else defaultLight

            // Flutter args override defaults
            fun color(key: String): String =
                call.argument<String>(key) ?: defaults[key] ?: "#FF000000"

            val uiConfig = UIConfiguration(
                theme = Theme(
                    colors = Theme.Colors(
                        brand = color("brandColor"),
                        brandHighlight = color("brandHighlight"),
                        brandDisable = color("brandDisable"),
                        brand2 = color("brand2"),
                        brand2Highlight = color("brand2Highlight"),
                        brand2Disable = color("brand2Disable"),
                        text = color("textColor"),
                        secondaryText = color("secondaryTextColor"),
                        tertiaryText = color("tertiaryTextColor"),
                        lightText = color("lightTextColor"),
                        linkText = color("linkTextColor"),
                        mainBackground = color("mainBackground"),
                        secondaryBackground = color("secondaryBackground"),
                        maskBackground = color("maskBackground"),
                        hudBackground = color("hudBackground"),
                        separatorLine = color("separatorLineColor"),
                        inputLineEnable = color("inputLineEnable"),
                        inputLineDisable = color("inputLineDisable"),
                        stateSafe = color("stateSafe"),
                        stateWarning = color("stateWarning"),
                        stateError = color("stateError"),
                    )
                ),
                texts = AppTexts(appNamePlaceHolder = appName)
            )
            // 1. Tell Android's AppCompat system to switch DayNight mode
            //    This is CRITICAL — without it, DayNight theme resources won't switch
            //    for any AppCompatActivity (including all Gwell SDK pages).
            val nightMode = if (isDarkMode) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)

            // 2. Apply color palette to Gwell SDK
            GWIoT.setUIConfiguration(uiConfig)
            Log.i(TAG, "[UI_CONFIG] ✅ Theme set: isDarkMode=$isDarkMode, nightMode=$nightMode")
            result.success(mapOf("success" to true))
        } catch (e: Exception) {
            Log.e(TAG, "[UI_CONFIG] ❌ FAILED: ${e.message}", e)
            result.success(mapOf("success" to false, "error" to (e.message ?: "Failed")))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE: UNBIND / DELETE
    // ══════════════════════════════════════════════════════════════════════

    private fun handleUnbindDevice(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            result.success(mapOf("success" to false, "error" to "deviceId is required"))
            return
        }
        scope.launch {
            try {
                val unbindResult = GWIoT.unbindDevice(deviceId)
                when (unbindResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[UNBIND] ✅ Device $deviceId unbound")
                        // Refresh cached device list
                        val devResult = GWIoT.queryDeviceList()
                        if (devResult is GWResult.Success<*>) {
                            @Suppress("UNCHECKED_CAST")
                            cachedDevices = (devResult.data as? List<Device>) ?: emptyList()
                        }
                        sendEventToFlutter(mapOf("type" to "deviceUnbound", "deviceId" to deviceId))
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> {
                        Log.e(TAG, "[UNBIND] ❌ ${unbindResult.err?.message}")
                        result.success(mapOf("success" to false, "error" to (unbindResult.err?.message ?: "Unbind failed")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("UNBIND", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FIRMWARE UPGRADE
    // ══════════════════════════════════════════════════════════════════════

    private fun handleCheckDevUpgradeInfo(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceId = call.argument<String>("deviceId") ?: ""
        if (deviceId.isEmpty()) {
            result.success(mapOf("success" to false, "error" to "deviceId is required"))
            return
        }
        scope.launch {
            try {
                val upgradeResult = GWIoT.checkDevUpgradeInfo(deviceId)
                when (upgradeResult) {
                    is GWResult.Success<*> -> {
                        val info = upgradeResult.data
                        Log.i(TAG, "[OTA] ✅ Upgrade info for $deviceId: $info")
                        result.success(mapOf(
                            "success" to true,
                            "hasNewVersion" to (info != null),
                            "info" to (info?.toString() ?: "")
                        ))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (upgradeResult.err?.message ?: "Check failed")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("OTA_CHECK", e, result)
            }
        }
    }

    private fun handleBatchCheckDevUpgradeInfo(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val deviceIds = call.argument<List<String>>("deviceIds") ?: emptyList()
        if (deviceIds.isEmpty()) {
            result.success(mapOf("success" to false, "error" to "deviceIds list is required"))
            return
        }
        scope.launch {
            try {
                val upgradeResult = GWIoT.batchCheckDevUpgradeInfo(deviceIds)
                when (upgradeResult) {
                    is GWResult.Success<*> -> {
                        val infoList = upgradeResult.data as? List<*>
                        Log.i(TAG, "[OTA] ✅ Batch upgrade info: ${infoList?.size ?: 0} devices")
                        result.success(mapOf(
                            "success" to true,
                            "count" to (infoList?.size ?: 0),
                            "info" to (infoList?.toString() ?: "[]")
                        ))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (upgradeResult.err?.message ?: "Batch check failed")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("OTA_BATCH_CHECK", e, result)
            }
        }
    }

    private fun handleOpenBatchUpgradePage(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val openResult = GWIoT.openBatchUpgradePage()
                when (openResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[OTA] ✅ Batch upgrade page opened")
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (openResult.err?.message ?: "Failed to open")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("OTA_PAGE", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CLOUD SERVICE & MEMBERSHIP
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenCloudPage(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val device = findDeviceOrError(call, result) ?: return
        scope.launch {
            try {
                val openResult = GWIoT.openCloudPage(device)
                when (openResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[CLOUD] ✅ Cloud page opened for ${device.deviceId}")
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (openResult.err?.message ?: "Failed to open")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("CLOUD_PAGE", e, result)
            }
        }
    }

    private fun handleQueryMembershipInfo(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val forceRefresh = call.argument<Boolean>("forceRefresh") ?: true
        scope.launch {
            try {
                val memberResult = GWIoT.queryMembershipInfo(forceRefresh)
                when (memberResult) {
                    is GWResult.Success<*> -> {
                        val info = memberResult.data
                        Log.i(TAG, "[MEMBERSHIP] ✅ Info: $info")
                        result.success(mapOf(
                            "success" to true,
                            "info" to (info?.toString() ?: "")
                        ))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (memberResult.err?.message ?: "Query failed")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("MEMBERSHIP_QUERY", e, result)
            }
        }
    }

    private fun handleOpenMembershipCenterPage(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val openResult = GWIoT.openMembershipCenterPage()
                when (openResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[MEMBERSHIP] ✅ Center page opened")
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (openResult.err?.message ?: "Failed to open")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("MEMBERSHIP_PAGE", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SHARE MANAGER
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenShareManagerPage(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                val openResult = GWIoT.devShareComp.openShareManagerPage()
                when (openResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[SHARE] ✅ Share manager page opened")
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (openResult.err?.message ?: "Failed to open")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("SHARE_MANAGER", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ALBUM
    // ══════════════════════════════════════════════════════════════════════

    private fun handleOpenAlbum(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return
        val device = findDeviceOrError(call, result) ?: return
        scope.launch {
            try {
                val openResult = GWIoT.openAlbum(device, null)
                when (openResult) {
                    is GWResult.Success<*> -> {
                        Log.i(TAG, "[ALBUM] ✅ Album opened for ${device.deviceId}")
                        result.success(mapOf("success" to true))
                    }
                    is GWResult.Failure<*> -> {
                        result.success(mapOf("success" to false, "error" to (openResult.err?.message ?: "Failed to open")))
                    }
                }
            } catch (e: Exception) {
                handleErrorResult("ALBUM", e, result)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Emit bindSuccess event with refreshed device list.
     * Flutter uses this to detect new devices and sync with backend.
     */
    private suspend fun emitBindSuccessEvent() {
        try {
            val devResult = GWIoT.queryDeviceList()
            Log.d("Scan_QR","Scan_QR:::::::$devResult")
            val devices = when (devResult) {
                is GWResult.Success<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val deviceList = (devResult.data as? List<Device>) ?: emptyList()
                    cachedDevices = deviceList

                    // ✅ LOG RAW SDK RESPONSE — dump TẤT CẢ fields cho backend dev
                    Log.i(TAG, "╔══════════════════════════════════════════════════════")
                    Log.i(TAG, "║ 📱 [RAW SDK RESPONSE] bindSuccess — ${deviceList.size} devices")
                    Log.i(TAG,  "╠══════════════════════════════════════════════════════")
                    deviceList.forEachIndexed { index, dev ->
                        Log.i(TAG, "║")
                        Log.i(TAG, "║ ══ Device ${index + 1}/${deviceList.size} ══")

                        // Dump ALL fields bằng reflection
                        try {
                            val fields = dev.javaClass.declaredFields
                            for (field in fields) {
                                field.isAccessible = true
                                val value = try { field.get(dev) } catch (_: Exception) { "⚠️ inaccessible" }
                                val valStr = value?.toString() ?: "null"
                                Log.i(TAG, "║ ${field.name}: $valStr")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "║ ⚠️ Reflection failed: ${e.message}")
                        }

                        // Dump known properties thủ công (backup)
                        Log.i(TAG, "║ ── Known Properties ──")
                        Log.i(TAG, "║ deviceId        : ${dev.deviceId}")
                        Log.i(TAG, "║ remarkName      : ${dev.remarkName}")
                        Log.i(TAG, "║ productInfo.name: ${dev.productInfo?.name ?: "null"}")
                        Log.i(TAG, "║ jsonString      : ${dev.jsonString ?: "null"}")

                        // Dump IoTProps
                        try {
                            val propsResult = GWIoT.getIoTProps(dev)
                            if (propsResult is GWResult.Success && propsResult.data != null) {
                                val props = propsResult.data!!
                                Log.i(TAG, "║ ── IoTProps ──")
                                // Reflection cho props
                                try {
                                    val propFields = props.javaClass.declaredFields
                                    for (field in propFields) {
                                        field.isAccessible = true
                                        val value = try { field.get(props) } catch (_: Exception) { "⚠️ inaccessible" }
                                        val valStr = value?.toString() ?: "null"
                                        Log.i(TAG, "║ props.${field.name}: $valStr")
                                    }
                                } catch (_: Exception) {
                                    Log.i(TAG, "║ props.isOnline  : ${props.isOnline}")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    Log.i(TAG, "╚══════════════════════════════════════════════════════")

                    deviceList.map { dev ->
                        var isOnline = false
                        try {
                            val propsResult = GWIoT.getIoTProps(dev)
                            if (propsResult is GWResult.Success) isOnline = propsResult.data?.isOnline ?: false
                        } catch (_: Exception) {}
                        mapOf(
                            "deviceId" to dev.deviceId,
                            "deviceName" to dev.remarkName,
                            "isOnline" to isOnline,
                            "deviceType" to (dev.productInfo?.name ?: ""),
                            "category" to "gwell_camera",
                            "relation" to (dev.relation.name),
                            "solution" to (dev.solution.name),
                            "jsonString" to (dev.jsonString ?: ""),
                        ).plus(extractSaasFields(dev.jsonString))
                    }
                }
                is GWResult.Failure<*> -> {
                    Log.e(TAG, "[BindEvent] ❌ queryDeviceList failed: ${devResult.err?.message}")
                    emptyList()
                }
            }
            sendEventToFlutter(mapOf("type" to "bindSuccess", "devices" to devices))
        } catch (e: Exception) {
            Log.w(TAG, "[BindEvent] ⚠️ ${e.message}")
            sendEventToFlutter(mapOf("type" to "bindSuccess", "devices" to emptyList<Map<String, Any>>()))
        }
    }

    private fun findDevice(deviceId: String): Device? = cachedDevices.find { it.deviceId == deviceId }

    /** Find device from MethodCall or send error result. Returns null if not found. */
    private fun findDeviceOrError(call: MethodCall, result: Result): Device? {
        val deviceId = call.argument<String>("deviceId") ?: ""
        return findDevice(deviceId) ?: run {
            result.success(mapOf("success" to false, "error" to "Device not found: $deviceId"))
            null
        }
    }

    private fun ensureSdkInitialized(result: Result): Boolean {
        if (GwellSdkInitializer.sdkInitialized) return true
        result.error("SDK_NOT_INITIALIZED", "GWIoT SDK not initialized. Use ARM64 device.", null)
        return false
    }

    private fun isSignatureError(errMsg: String, reason: String = ""): Boolean {
        val combined = "$errMsg $reason".lowercase()
        return combined.contains("signature") || combined.contains("10007")
                || combined.contains("-7") || combined.contains("auth failed")
    }

    private fun handleErrorResult(tag: String, e: Exception, result: Result) {
        val errMsg = e.message ?: "Unknown error"
        val responseMap = mutableMapOf<String, Any>("success" to false, "error" to errMsg)
        if (isSignatureError(errMsg)) {
            Log.e(TAG, "[$tag] 🔴 SIGNATURE ERROR: $errMsg")
            responseMap["errorCode"] = "10007"
            responseMap["isSignatureError"] = true
        }
        result.success(responseMap)
    }

    /** Build Intent from Flutter payload map for push notifications. */
    private fun buildIntentFromPayload(payload: Map<String, Any>): Intent? {
        val context = flutterPluginBinding?.applicationContext ?: return null
        return Intent().apply {
            payload.forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                }
            }
        }
    }

    /**
     * Extract saas fields from Device.jsonString since Device.saas is internal.
     * Returns map with: sn, productModel, productId, lastBindTime
     */
    private fun extractSaasFields(jsonString: String?): Map<String, Any> {
        if (jsonString.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(jsonString)
            val saas = json.optJSONObject("saas") ?: return emptyMap()
            mapOf(
                "sn" to (saas.optString("sn", "")),
                "productModel" to (saas.optString("productModel", "")),
                "productId" to (saas.optString("productId", "")),
                "lastBindTime" to (saas.optLong("lastBindTime", 0)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "[extractSaasFields] ⚠️ JSON parse error: ${e.message}")
            emptyMap()
        }
    }
}
