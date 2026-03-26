package com.foursgen.connect

import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GwellIotPlugin : FlutterPlugin, MethodCallHandler {

    private lateinit var channel: MethodChannel
    private val scope = CoroutineScope(Dispatchers.Main)
    private val TAG = "GwellIotPlugin"
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null

    // Cache devices from last queryDeviceList
    private var cachedDevices: List<Device> = emptyList()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = binding
        channel = MethodChannel(binding.binaryMessenger, "com.foursgen.connect/gwell_iot")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        flutterPluginBinding = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initGwellSdk" -> handleInitGwellSdk(call, result)
            "getPhoneUniqueId" -> handleGetPhoneUniqueId(result)
            "loginWithC2CInfo" -> handleLoginWithC2CInfo(call, result)
            "queryDeviceList" -> handleQueryDeviceList(result)
            "openLiveView" -> handleOpenLiveView(call, result)
            "openPlayback" -> handleOpenPlayback(call, result)
            "openDeviceSettings" -> handleOpenDeviceSettings(call, result)
            "openDeviceEvents" -> handleOpenDeviceEvents(call, result)
            "openScanQRCode" -> handleOpenScanQRCode(result)
            "openBindProductList" -> handleOpenBindProductList(result)
            "logout" -> handleLogout(result)
            "isLoggedIn" -> handleIsLoggedIn(result)
            else -> result.notImplemented()
        }
    }

    // ── SDK Init ─────────────────────────────────────────────────────────

    private fun handleInitGwellSdk(call: MethodCall, result: Result) {
        try {
            val appId = call.argument<String>("appId") ?: ""
            val appToken = call.argument<String>("appToken") ?: ""
            val language = call.argument<String>("language") ?: "vi"

            if (appId.isBlank() || appToken.isBlank()) {
                result.error("INVALID_ARGS", "appId and appToken are required", null)
                return
            }

            val app = flutterPluginBinding?.applicationContext as? android.app.Application
            if (app == null) {
                result.error("NO_APP", "Cannot get Application context", null)
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

    // ── C2C Login ────────────────────────────────────────────────────────

    private fun handleLoginWithC2CInfo(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val accessId = call.argument<String>("accessId") ?: ""
        val accessToken = call.argument<String>("accessToken") ?: ""
        val expireTime = call.argument<String>("expireTime") ?: ""
        val terminalId = call.argument<String>("terminalId") ?: ""
        val expand = call.argument<String>("expand") ?: ""

        Log.d(TAG, "[C2C_LOGIN] accessId=$accessId, tokenLen=${accessToken.length}")

        if (accessId.isBlank() || accessToken.isBlank()) {
            result.error("INVALID_ARGS", "accessId and accessToken are required", null)
            return
        }

        scope.launch {
            try {
                val c2cInfo = UserC2CInfo(accessId, accessToken, expireTime, terminalId, expand)
                Log.d(TAG, "[C2C_LOGIN] UserC2CInfo created:")
                Log.d(TAG, "[C2C_LOGIN]   .accessId=${c2cInfo.accessId}")
                Log.d(TAG, "[C2C_LOGIN]   .area=${c2cInfo.area}")
                Log.d(TAG, "[C2C_LOGIN]   .regRegion=${c2cInfo.regRegion}")
                Log.d(TAG, "[C2C_LOGIN]   .expend=${c2cInfo.expend}")

                Log.d(TAG, "[C2C_LOGIN] isLogin BEFORE: ${GWIoT.isLogin.value}")

                // Use login2(c2c) — suspend fun returning GWResult<User>
                val loginResult = GWIoT.login2(c2cInfo)
                Log.d(TAG, "[C2C_LOGIN] login2 result: $loginResult")
                Log.d(TAG, "[C2C_LOGIN] isLogin AFTER: ${GWIoT.isLogin.value}")

                if (loginResult is GWResult.Success) {
                    Log.i(TAG, "[C2C_LOGIN] SUCCESS - user=${loginResult.data}")
                    result.success(mapOf("success" to true, "message" to "C2C login2 successful"))
                } else if (loginResult is GWResult.Failure) {
                    Log.e(TAG, "[C2C_LOGIN] FAILED - ${loginResult.err}")
                    result.success(mapOf("success" to false, "message" to "login2 failed: ${loginResult.err}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[C2C_LOGIN] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "C2C login failed")))
            }
        }
    }

    // ── Device Management ────────────────────────────────────────────────

    private fun handleQueryDeviceList(result: Result) {
        if (!ensureSdkInitialized(result)) return

        val isLoggedIn = GWIoT.isLogin.value
        Log.d(TAG, "[DEVICE_LIST] isLogin=$isLoggedIn")

        scope.launch {
            try {
                Log.d(TAG, "[DEVICE_LIST] Querying...")
                val devResult = GWIoT.queryDeviceList()
                Log.d(TAG, "[DEVICE_LIST] Result: ${devResult::class.simpleName}")

                when (devResult) {
                    is GWResult.Success<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val devices = (devResult.data as? List<Device>) ?: emptyList()
                        cachedDevices = devices
                        val list = devices.map { dev ->
                            mapOf(
                                "deviceId" to dev.deviceId,
                                "deviceName" to dev.remarkName,
                                "jsonString" to (dev.jsonString ?: ""),
                            )
                        }
                        Log.i(TAG, "[DEVICE_LIST] Found ${list.size} devices")
                        result.success(mapOf("success" to true, "devices" to list))
                    }
                    is GWResult.Failure<*> -> {
                        val gwError = devResult.err
                        val errMsg = gwError?.message ?: "Unknown error"
                        val reason = gwError?.reason?.toString() ?: "Unknown reason"
                        Log.e(TAG, "[DEVICE_LIST] FAILURE: message=$errMsg, reason=$reason")
                        result.success(mapOf("success" to false, "message" to "[$reason] $errMsg"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[DEVICE_LIST] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "Query failed")))
            }
        }
    }

    // ── Live View (openHome) ─────────────────────────────────────────────

    private fun handleOpenLiveView(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "message" to "Device not found: $deviceId. Try refreshing the device list."))
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "[LIVE_VIEW] Opening for device: $deviceId")
                val option = OpenPluginOption(device)
                GWIoT.openHome(option)
                Log.i(TAG, "[LIVE_VIEW] Opened successfully")
                result.success(mapOf("success" to true, "message" to "Live view opened"))
            } catch (e: Exception) {
                Log.e(TAG, "[LIVE_VIEW] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "Failed to open live view")))
            }
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────

    private fun handleOpenPlayback(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "message" to "Device not found: $deviceId"))
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "[PLAYBACK] Opening for device: $deviceId")
                val option = PlaybackOption(device)
                GWIoT.openPlayback(option)
                Log.i(TAG, "[PLAYBACK] Opened successfully")
                result.success(mapOf("success" to true, "message" to "Playback opened"))
            } catch (e: Exception) {
                Log.e(TAG, "[PLAYBACK] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "Failed to open playback")))
            }
        }
    }

    // ── Device Settings ──────────────────────────────────────────────────

    private fun handleOpenDeviceSettings(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "message" to "Device not found: $deviceId"))
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "[SETTINGS] Opening for device: $deviceId")
                GWIoT.openDeviceInfoPage(device)
                Log.i(TAG, "[SETTINGS] Opened successfully")
                result.success(mapOf("success" to true, "message" to "Settings opened"))
            } catch (e: Exception) {
                Log.e(TAG, "[SETTINGS] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "Failed to open settings")))
            }
        }
    }

    // ── Device Events ────────────────────────────────────────────────────

    private fun handleOpenDeviceEvents(call: MethodCall, result: Result) {
        if (!ensureSdkInitialized(result)) return

        val deviceId = call.argument<String>("deviceId") ?: ""
        val device = findDevice(deviceId)
        if (device == null) {
            result.success(mapOf("success" to false, "message" to "Device not found: $deviceId"))
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "[EVENTS] Opening for device: $deviceId")
                GWIoT.openEventsPage(device)
                Log.i(TAG, "[EVENTS] Opened successfully")
                result.success(mapOf("success" to true, "message" to "Events opened"))
            } catch (e: Exception) {
                Log.e(TAG, "[EVENTS] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "Failed to open events")))
            }
        }
    }

    // ── QR & Binding ─────────────────────────────────────────────────────

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
                    is GWResult.Success<*> -> result.success(mapOf("success" to true, "message" to "QR scanned"))
                    is GWResult.Failure<*> -> result.success(mapOf("success" to false, "message" to scanResult.toString()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[SCAN_QR] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "QR scan failed")))
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
                    is GWResult.Success<*> -> result.success(mapOf("success" to true, "message" to "Bind list opened"))
                    is GWResult.Failure<*> -> result.success(mapOf("success" to false, "message" to bindResult.toString()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "[BIND_LIST] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "Bind list failed")))
            }
        }
    }

    // ── Logout / isLoggedIn ──────────────────────────────────────────────

    private fun handleLogout(result: Result) {
        if (!ensureSdkInitialized(result)) return
        scope.launch {
            try {
                GWIoT.logout()
                cachedDevices = emptyList()
                Log.i(TAG, "[LOGOUT] Success")
                result.success(mapOf("success" to true, "message" to "Logged out"))
            } catch (e: Exception) {
                Log.e(TAG, "[LOGOUT] EXCEPTION: ${e.message}", e)
                result.success(mapOf("success" to false, "message" to (e.message ?: "Logout failed")))
            }
        }
    }

    private fun handleIsLoggedIn(result: Result) {
        if (!GwellSdkInitializer.sdkInitialized) {
            result.success(mapOf("isLoggedIn" to false))
            return
        }
        try {
            val isLoggedIn = GWIoT.isLogin.value ?: false
            Log.d(TAG, "[IS_LOGGED_IN] $isLoggedIn")
            result.success(mapOf("isLoggedIn" to isLoggedIn))
        } catch (e: Exception) {
            Log.e(TAG, "[IS_LOGGED_IN] EXCEPTION: ${e.message}", e)
            result.success(mapOf("isLoggedIn" to false))
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun findDevice(deviceId: String): Device? {
        return cachedDevices.find { it.deviceId == deviceId }
    }

    private fun ensureSdkInitialized(result: Result): Boolean {
        if (GwellSdkInitializer.sdkInitialized) return true
        result.error("SDK_NOT_INITIALIZED", "GWIoT SDK not initialized. Call initSdk() first.", null)
        return false
    }
}
