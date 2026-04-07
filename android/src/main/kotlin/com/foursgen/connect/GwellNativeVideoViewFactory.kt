package com.foursgen.connect

import android.content.Context
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Factory for creating GwellNativeVideoView platform views.
 * Mirrors iOS NativeVideoViewFactory.swift.
 * Each instance creates a GwellNativeVideoView with its own ILivePlayer.
 *
 * Registered with viewType: "native_video_view"
 */
class GwellNativeVideoViewFactory(
    private val messenger: BinaryMessenger
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    companion object {
        private const val TAG = "GwellNativeVideoViewFactory"
        const val VIEW_TYPE = "native_video_view"
    }

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        @Suppress("UNCHECKED_CAST")
        val creationParams = args as? Map<String, Any>

        val deviceId = (creationParams?.get("deviceId") as? String) ?: ""
        val mode = (creationParams?.get("mode") as? String) ?: "live"

        Log.d(TAG, "🏭 Creating GwellNativeVideoView: viewId=$viewId, deviceId=$deviceId, mode=$mode")

        return GwellNativeVideoView(context, messenger, viewId, deviceId, mode, creationParams)
    }
}
