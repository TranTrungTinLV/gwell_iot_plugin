package com.foursgen.connect

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.gw.gwiotapi.GWIoT
import com.gw.gwiotapi.entities.Device
import com.gw.gwiotapi.entities.GWResult
import com.gw.gwiotapi.entities.LivePlayerOption
import com.gw.gwiotapi.player.ILivePlayer
import com.gw.gwiotapi.player.constants.PlayerState
import com.gw.gwiotapi.player.constants.VideoDefinition
import com.gw.gwiotapi.player.constants.VideoScalingMode
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Platform View nhúng Gwell video player (ILivePlayer) vào Flutter.
 *
 * KEY INSIGHT từ API docs:
 * - PlayerView extends FrameLayout (là Android View thật)
 * - IPlayer.videoView trả về PlayerView
 * - IPlayer.scalingMode cho phép set AspectFill/Fill
 *
 * Approach FINAL:
 * 1. Tạo player → lấy player.videoView (PlayerView = FrameLayout)
 * 2. Add PlayerView vào container với MATCH_PARENT
 * 3. Set scalingMode = AspectFill để video fill container
 * 4. KHÔNG dùng attachToRoot (nó không hoạt động — children=0)
 */
class GwellNativeVideoView(
    private val context: Context,
    messenger: BinaryMessenger,
    private val viewId: Int,
    private val deviceId: String,
    private val mode: String,
    private val creationParams: Map<String, Any>?
) : PlatformView, MethodChannel.MethodCallHandler {

    companion object {
        private const val TAG = "GwellNativeVideoView"
    }

    private val containerView: FrameLayout
    private var livePlayer: ILivePlayer? = null
    private val methodChannel: MethodChannel
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isPlayerSetup = false
    private var isDisposed = false

    init {
        Log.i(TAG, "🎬 Init viewId=$viewId, deviceId=$deviceId, mode=$mode")

        // Container
        containerView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // Per-instance Method Channel
        methodChannel = MethodChannel(messenger, "native_video_view_$viewId")
        methodChannel.setMethodCallHandler(this)

        if (deviceId.isNotEmpty()) {
            setupPlayer()
        }
    }

    private fun setupPlayer() {
        if (isPlayerSetup || isDisposed) return
        isPlayerSetup = true
        Log.i(TAG, "🔧 [$deviceId] setupPlayer")

        try {
            val device = Device(
                deviceId = deviceId,
                remarkName = "",
                picDays = 0,
                cloudStatusInt = 0,
                propertiesLong = 0
            )

            val inflater = LayoutInflater.from(context)

            // ✅ FIX: Dùng attachToRoot=true — SDK tạo GLSurfaceView bên trong container.
            // Trước đây children=0 vì SurfaceProducer backend không composite GLSurfaceView.
            // Giờ Flutter side dùng Hybrid Composition → GLSurfaceView render đúng vị trí.
            val opts = LivePlayerOption(
                device = device,
                inflater = inflater,
                parent = containerView,
                attachToRoot = true
            )

            val result = GWIoT.getLivePlayer(opts = opts)

            when (result) {
                is GWResult.Success<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val player = result.data as? ILivePlayer
                    if (player != null) {
                        this.livePlayer = player
                        Log.i(TAG, "✅ [$deviceId] ILivePlayer created")
                        // ✅ KEY FIX: Set scalingMode = AspectFill
                        // "等比缩放，直到影片填满可见边界，一个维度可能被剪裁"
                        try {
                            player.scalingMode = VideoScalingMode.AspectFill
                            Log.i(TAG, "✅ [$deviceId] scalingMode = AspectFill")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ [$deviceId] Failed to set scalingMode: ${e.message}")
                        }

                        // ✅ attachToRoot=true → SDK tự inflate views vào container
                        // Log children count để verify
                        Log.i(TAG, "✅ [$deviceId] Container children=${containerView.childCount}")
                        logViewHierarchy(containerView, "  ")

                        // Observe playerState
                        player.playerState.observeForever { state: PlayerState? ->
                            if (isDisposed) return@observeForever
                            val stateStr = state?.name ?: "unknown"
                            Log.d(TAG, "📊 [$deviceId] playerState → $stateStr")
                            mainHandler.post {
                                if (isDisposed) return@post
                                methodChannel.invokeMethod("onPlayerStateChanged", mapOf(
                                    "state" to stateStr,
                                    "mode" to mode
                                ))
                            }
                        }

                        // Auto-play live mode
                        if (mode == "live") {
                            Log.i(TAG, "▶️ [$deviceId] Auto-play")
                            scope.launch {
                                try {
                                    player.play()
                                    // Log hierarchy sau play để debug
                                    mainHandler.postDelayed({
                                        Log.d(TAG, "📐 [$deviceId] After play - children=${containerView.childCount}")
                                        logViewHierarchy(containerView, "  ")
                                    }, 1000)
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ [$deviceId] play failed: ${e.message}", e)
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "❌ [$deviceId] Player data is null")
                        notifyError("Player data is null")
                    }
                }

                is GWResult.Failure<*> -> {
                    val errMsg = result.err?.toString() ?: "Unknown error"
                    Log.e(TAG, "❌ [$deviceId] Failed: $errMsg")
                    notifyError(errMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [$deviceId] setupPlayer exception: ${e.message}", e)
            notifyError(e.message ?: "Setup failed")
        }
    }

    /**
     * Debug: log view hierarchy
     */
    private fun logViewHierarchy(view: View, indent: String) {
        val name = view.javaClass.simpleName
        val size = "${view.width}x${view.height}"
        val lp = view.layoutParams
        val lpStr = when {
            lp == null -> "null"
            lp.width == ViewGroup.LayoutParams.MATCH_PARENT && lp.height == ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH"
            lp.width == ViewGroup.LayoutParams.WRAP_CONTENT && lp.height == ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
            else -> "${lp.width}x${lp.height}"
        }
        Log.d(TAG, "$indent$name [$size] lp=$lpStr")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                logViewHierarchy(view.getChildAt(i), "$indent  ")
            }
        }
    }

    private fun notifyError(errorMsg: String) {
        mainHandler.post {
            methodChannel.invokeMethod("onPlayerStateChanged", mapOf(
                "state" to "error",
                "error" to errorMsg
            ))
        }
    }

    // MARK: - MethodChannel

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "play" -> {
                scope.launch {
                    try {
                        livePlayer?.play()
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("PLAY_ERROR", e.message, null)
                    }
                }
            }
            "stop" -> {
                scope.launch {
                    try { livePlayer?.stop(); result.success(null) }
                    catch (e: Exception) { result.error("STOP_ERROR", e.message, null) }
                }
            }
            "toggleMute" -> {
                val player = livePlayer
                if (player != null) {
                    player.mute = !player.mute
                    result.success(mapOf("isMuted" to player.mute))
                } else {
                    result.success(mapOf("isMuted" to false))
                }
            }
            "setDefinition" -> {
                val args = call.arguments as? Map<*, *>
                val isHD = (args?.get("isHD") as? Boolean) ?: true
                scope.launch {
                    try {
                        livePlayer?.setDefinition(if (isHD) VideoDefinition.High else VideoDefinition.Low)
                        result.success(null)
                    } catch (e: Exception) {
                        result.error("DEFINITION_ERROR", e.message, null)
                    }
                }
            }
            "startTalk" -> {
                scope.launch {
                    try { livePlayer?.intercom?.start(); result.success(null) }
                    catch (e: Exception) { result.error("TALK_ERROR", e.message, null) }
                }
            }
            "stopTalk" -> {
                scope.launch {
                    try { livePlayer?.intercom?.stop(); result.success(null) }
                    catch (e: Exception) { result.error("TALK_ERROR", e.message, null) }
                }
            }
            else -> result.notImplemented()
        }
    }

    // MARK: - PlatformView

    override fun getView(): View = containerView

    override fun onFlutterViewAttached(flutterView: View) {
        Log.d(TAG, "📎 [$deviceId] attached, children=${containerView.childCount}")
    }

    override fun onFlutterViewDetached() {
        Log.d(TAG, "📎 [$deviceId] detached")
    }

    override fun dispose() {
        Log.i(TAG, "🧹 [$deviceId] dispose")
        isDisposed = true
        scope.launch {
            try { livePlayer?.stop() } catch (_: Exception) {}
        }
        livePlayer = null
        methodChannel.setMethodCallHandler(null)
        containerView.removeAllViews()
    }
}
