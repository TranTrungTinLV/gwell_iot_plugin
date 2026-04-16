import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';

/// Flutter plugin for Gwell IoT SDK (C2C mode).
///
/// Provides camera live view, playback, device management,
/// QR scan, and device binding through native Gwell SDK.
///
/// Method names match iOS GWIoTMethodChannel for consistency.
/// EventChannel matches iOS GWIoTEventChannel for real-time events.
class GwellIotPlugin {
  static const _channel = MethodChannel('com.reoqoo/gwiot');

  // ── EventChannel — matching iOS GWIoTEventChannel ─────────────────────
  static const _eventChannel = EventChannel('com.reoqoo/gwiot_events');

  /// Cached broadcast stream — all derived streams MUST use this.
  /// EventChannel only supports ONE active listener on Android,
  /// so calling receiveBroadcastStream() multiple times kills previous listeners.
  static Stream<Map<String, dynamic>>? _cachedEventStream;

  /// Broadcast stream of all events from native SDK.
  /// Each event is a Map with a 'type' key indicating the event type.
  ///
  /// Event types:
  /// - `loginStatusChanged`: {type, isLoggedIn: bool}
  /// - `deviceListUpdated`: {type, devices: List<Map>}
  /// - `tokenExpired`: {type}
  /// - `accountEvent`: {type, event: String}
  /// - `bindSuccess`: {type, device: Map}
  /// - `bindFailed`: {type, error: String}
  /// - `bindCancelled`: {type}
  /// - `pushReceived`: {type, ...payload}
  /// - `pushClicked`: {type, ...payload}
  static Stream<Map<String, dynamic>> get events {
    _cachedEventStream ??= _eventChannel
        .receiveBroadcastStream()
        .map((event) => Map<String, dynamic>.from(event as Map))
        .asBroadcastStream();
    return _cachedEventStream!;
  }

  /// Stream of login status changes.
  /// Emits `{type: 'loginStatusChanged', isLoggedIn: bool}`
  static Stream<bool> get loginStatusStream {
    return events.where((e) => e['type'] == 'loginStatusChanged').map((e) => e['isLoggedIn'] as bool? ?? false);
  }

  /// Stream of device list updates.
  /// Emits `{type: 'deviceListUpdated', devices: List<Map>}`
  static Stream<List<Map<String, dynamic>>> get deviceListStream {
    return events.where((e) => e['type'] == 'deviceListUpdated').map((e) {
      final devices = e['devices'] as List? ?? [];
      return devices.map((d) => Map<String, dynamic>.from(d as Map)).toList();
    });
  }

  /// Stream that emits when SDK token expires (login -> not logged in).
  /// Use this to trigger re-authentication.
  static Stream<void> get tokenExpiredStream {
    return events.where((e) => e['type'] == 'tokenExpired').map((_) {});
  }

  /// Stream of account events from SDK.
  /// Emits event description string.
  static Stream<String> get accountEventStream {
    return events.where((e) => e['type'] == 'accountEvent').map((e) => e['event'] as String? ?? '');
  }

  /// Stream of bind events (success, failed, cancelled).
  /// Emits the full event map with type: bindSuccess/bindFailed/bindCancelled.
  static Stream<Map<String, dynamic>> get bindEventStream {
    return events.where((e) {
      final type = e['type'] as String? ?? '';
      return type == 'bindSuccess' || type == 'bindFailed' || type == 'bindCancelled';
    });
  }

  /// Stream of device events (deleted, renamed, sharing accepted).
  /// Emits the full event map from Gwell SDK's deviceEvents LiveEvent.
  static Stream<Map<String, dynamic>> get deviceEventStream {
    return events.where((e) {
      final type = e['type'] as String? ?? '';
      return type == 'deviceDeleted' || type == 'deviceNameChanged' || type == 'sharingDeviceAccepted';
    });
  }

  /// Stream of push notification events.
  /// Emits the full event map with type: pushReceived/pushClicked.
  static Stream<Map<String, dynamic>> get pushEventStream {
    return events.where((e) {
      final type = e['type'] as String? ?? '';
      return type == 'pushReceived' || type == 'pushClicked';
    });
  }

  static Map<String, dynamic> _asMap(dynamic raw) {
    if (raw == null) return <String, dynamic>{};
    if (raw is Map) return Map<String, dynamic>.from(raw);

    return <String, dynamic>{};
  }

  // ── SDK Init ──────────────────────────────────────────────────────────

  /// Initialize the Gwell SDK. Must be called once before any other methods.
  ///
  /// [appId] and [appToken] are provided by Gwell for your app.
  static Future<Map<String, dynamic>> initSdk({
    required String appId,
    required String appToken,
    String language = 'vi',
  }) async {
    try {
      final result = await _channel.invokeMethod('initGwellSdk', {
        'appId': appId,
        'appToken': appToken,
        'language': language,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'SDK init failed'};
    } on MissingPluginException catch (_) {
      // iOS: SDK initialized in AppDelegate, method not handled in channel
      return {'success': true, 'message': 'SDK already initialized (iOS)'};
    }
  }

  // ── Phone Unique ID ───────────────────────────────────────────────────

  /// Get phone unique ID (used for C2C backend registration).
  static Future<String> getPhoneUniqueId() async {
    try {
      final result = await _channel.invokeMethod('getPhoneUniqueId');
      final map = _asMap(result);
      return map['phoneUniqueId'] as String? ?? '';
    } on PlatformException catch (_) {
      return '';
    }
  }

  /// Login SDK with C2C credentials from backend.
  /// Matches iOS `loginSDK` method.
  static Future<Map<String, dynamic>> loginSDK({
    required String accessId,
    required String accessToken,
    String terminalId = '',
    String expireTime = '0',
    String region = 'VN',
    String? expand,
  }) async {
    try {
      final result = await _channel.invokeMethod('loginSDK', {
        'accessId': accessId,
        'accessToken': accessToken,
        'terminalId': terminalId,
        'expireTime': expireTime,
        'region': region,
        if (expand != null && expand.isNotEmpty) 'expand': expand,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'loginSDK failed'};
    }
  }

  /// Legacy alias for [loginSDK].
  static Future<Map<String, dynamic>> loginWithC2CInfo({
    required String accessId,
    required String accessToken,
    required String expireTime,
    required String terminalId,
    required String expand,
  }) async {
    return loginSDK(
      accessId: accessId,
      accessToken: accessToken,
      terminalId: terminalId,
      expireTime: expireTime,
      expand: expand,
    );
  }

  /// Check login status. Returns `{"isLoggedIn": bool}`.
  static Future<Map<String, dynamic>> checkLoginStatus() async {
    try {
      final result = await _channel.invokeMethod('checkLoginStatus');
      return _asMap(result);
    } on PlatformException catch (_) {
      return {'isLoggedIn': false};
    }
  }

  /// Check if currently logged in.
  static Future<bool> isLoggedIn() async {
    final status = await checkLoginStatus();
    return status['isLoggedIn'] == true;
  }

  /// Logout from Gwell SDK.
  static Future<Map<String, dynamic>> logout() async {
    try {
      final result = await _channel.invokeMethod('logout');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Logout failed'};
    }
  }

  // ── Device Management ─────────────────────────────────────────────────

  /// Query device list from Gwell cloud.
  /// Returns `{"success": true, "devices": [...]}` with isOnline status.
  static Future<Map<String, dynamic>> getDeviceList() async {
    try {
      final result = await _channel.invokeMethod('getDeviceList');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Query failed'};
    }
  }

  /// Alias for [getDeviceList].
  static Future<Map<String, dynamic>> queryDeviceList() => getDeviceList();

  /// Get typed device list.
  static Future<List<Map<String, dynamic>>> getDeviceListParsed() async {
    final result = await getDeviceList();
    if (result['success'] == true) {
      final rawDevices = result['devices'];

      if (rawDevices is List) {
        return rawDevices.whereType<Map>().map((e) => Map<String, dynamic>.from(e)).toList();
      }
    }
    return const <Map<String, dynamic>>[];
  }

  // ── Thumbnail ──────────────────────────────────────────────────────────

  /// Get the last snapshot/thumbnail path for a device.
  /// Returns {success: true, path: '/path/to/image'} or {success: false, error: '...'}.
  static Future<String?> getLastSnapshotPath(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('getLastSnapshotPath', {'deviceId': deviceId});
      final map = _asMap(result);
      if (map['success'] == true) {
        final path = map['path'] as String?;
        return (path != null && path.isNotEmpty) ? path : null;
      }
      return null;
    } on PlatformException catch (e) {
      debugPrint('⚠️ [GwellIotPlugin] getLastSnapshotPath error: ${e.message}');
      return null;
    }
  }

  // ── Camera Features (Built-in UI) ─────────────────────────────────────

  /// Open live view for a device (SDK built-in UI).
  /// Matches iOS `openDeviceHome`.
  static Future<Map<String, dynamic>> openDeviceHome(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceHome', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Live view failed'};
    }
  }

  /// Alias for [openDeviceHome].
  static Future<Map<String, dynamic>> openLiveView(String deviceId) => openDeviceHome(deviceId);

  /// Open built-in multi-camera surveillance page (多设备同屏).
  /// Shows all devices in a grid with live streams.
  static Future<Map<String, dynamic>> openMultiLivePage() async {
    try {
      final result = await _channel.invokeMethod('openMultiLivePage');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Multi-live failed'};
    }
  }

  /// Open playback for a device (SDK built-in UI).
  static Future<Map<String, dynamic>> openPlayback(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openPlayback', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Playback failed'};
    }
  }

  /// Open playback with specific start time.
  static Future<Map<String, dynamic>> openPlaybackWithTime({required String deviceId, required int startTime}) async {
    try {
      final result = await _channel.invokeMethod('openPlaybackWithTime', {
        'deviceId': deviceId,
        'startTime': startTime,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Playback failed'};
    }
  }

  /// Check cloud playback permission for a device.
  static Future<Map<String, dynamic>> getCloudPlaybackPermission(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('getCloudPlaybackPermission', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'hasPermission': false, 'error': e.message ?? 'Failed'};
    }
  }

  // ── QR Scan & Device Binding ──────────────────────────────────────────

  /// Open QR code scanner (SDK built-in, auto-binds device).
  static Future<Map<String, dynamic>> openScanQRCode() async {
    try {
      final result = await _channel.invokeMethod('openScanQRCode');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'QR scan failed'};
    }
  }

  /// Open bindable product list.
  static Future<Map<String, dynamic>> openProductList() async {
    try {
      final result = await _channel.invokeMethod('openProductList');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Product list failed'};
    }
  }

  /// Alias for [openProductList].
  static Future<Map<String, dynamic>> openBindProductList() => openProductList();

  /// Open bind flow with QR value.
  static Future<Map<String, dynamic>> openBindByQRCode(String qrValue) async {
    try {
      final result = await _channel.invokeMethod('openBindByQRCode', {'qrValue': qrValue});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Bind failed'};
    }
  }

  // ── Device Pages ──────────────────────────────────────────────────────

  /// Open device settings page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceSettingPage(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceSettingPage', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Settings failed'};
    }
  }

  /// Alias for [openDeviceSettingPage].
  static Future<Map<String, dynamic>> openDeviceSettings(String deviceId) => openDeviceSettingPage(deviceId);

  /// Open device info page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceInfoPage(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceInfoPage', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Info failed'};
    }
  }

  /// Open events/alerts page (SDK built-in UI).
  static Future<Map<String, dynamic>> openEventsPage(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openEventsPage', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Events failed'};
    }
  }

  /// Alias for [openEventsPage].
  static Future<Map<String, dynamic>> openDeviceEvents(String deviceId) => openEventsPage(deviceId);

  /// Open message center page (SDK built-in UI).
  static Future<Map<String, dynamic>> openMessageCenterPage() async {
    try {
      final result = await _channel.invokeMethod('openMessageCenterPage');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Message center failed'};
    }
  }

  /// Open device share page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDevSharePage(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDevSharePage', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Share page failed'};
    }
  }

  // ── Device Unbind / Delete ──────────────────────────────────────────────

  /// Unbind (delete) a device from the account.
  /// Equivalent to Tuya's `deleteDevice`.
  static Future<Map<String, dynamic>> unbindDevice(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('unbindDevice', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Unbind failed'};
    }
  }

  /// Alias for [unbindDevice].
  static Future<Map<String, dynamic>> deleteDevice(String deviceId) => unbindDevice(deviceId);

  // ── Firmware Upgrade ──────────────────────────────────────────────────

  /// Check firmware upgrade info for a single device.
  /// Equivalent to Tuya's `getFirmwareInfo`.
  /// Returns `{success, hasNewVersion, info}`.
  static Future<Map<String, dynamic>> checkDevUpgradeInfo(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('checkDevUpgradeInfo', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Check upgrade failed'};
    }
  }

  /// Batch check firmware upgrade info for multiple devices.
  /// Returns `{success, count, info}`.
  static Future<Map<String, dynamic>> batchCheckDevUpgradeInfo(List<String> deviceIds) async {
    try {
      final result = await _channel.invokeMethod('batchCheckDevUpgradeInfo', {'deviceIds': deviceIds});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Batch check failed'};
    }
  }

  /// Open batch firmware upgrade page (SDK built-in UI).
  static Future<Map<String, dynamic>> openBatchUpgradePage() async {
    try {
      final result = await _channel.invokeMethod('openBatchUpgradePage');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Upgrade page failed'};
    }
  }

  // ── Cloud Service & Membership ────────────────────────────────────────

  /// Open cloud storage page for a device (SDK built-in UI).
  /// Equivalent to Tuya's cloud storage handler.
  static Future<Map<String, dynamic>> openCloudPage(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openCloudPage', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Cloud page failed'};
    }
  }

  /// Alias for [openCloudPage].
  static Future<Map<String, dynamic>> openCloudStoragePage(String deviceId) => openCloudPage(deviceId);

  /// Query membership/subscription info.
  /// Returns `{success, info}`.
  static Future<Map<String, dynamic>> queryMembershipInfo({bool forceRefresh = true}) async {
    try {
      final result = await _channel.invokeMethod('queryMembershipInfo', {'forceRefresh': forceRefresh});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Query membership failed'};
    }
  }

  /// Open membership center page (buy/upgrade cloud plans).
  static Future<Map<String, dynamic>> openMembershipCenterPage() async {
    try {
      final result = await _channel.invokeMethod('openMembershipCenterPage');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Membership center failed'};
    }
  }

  // ── Share Manager ─────────────────────────────────────────────────────

  /// Open share manager page — manage who has access to shared devices.
  static Future<Map<String, dynamic>> openShareManagerPage() async {
    try {
      final result = await _channel.invokeMethod('openShareManagerPage');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Share manager failed'};
    }
  }

  // ── Album ─────────────────────────────────────────────────────────────

  /// Open album/gallery for a device (screenshots & recordings).
  static Future<Map<String, dynamic>> openAlbum(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openAlbum', {'deviceId': deviceId});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Album failed'};
    }
  }

  // ── Language & UI ─────────────────────────────────────────────────────

  /// Set SDK language ("vi", "en", "zh").
  static Future<Map<String, dynamic>> setLanguage(String code) async {
    try {
      final result = await _channel.invokeMethod('setLanguage', {'code': code});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'setLanguage failed'};
    }
  }

  /// Set UI configuration (brand color etc.)
  static Future<Map<String, dynamic>> setUIConfiguration({
    required bool isDarkMode,
    String brandColor = '#FF4CAF50',
    String? brandHighlight,
    String? brandDisable,
    String? brand2,
    String? brand2Highlight,
    String? brand2Disable,
    String? textColor,
    String? secondaryTextColor,
    String? tertiaryTextColor,
    String? lightTextColor,
    String? linkTextColor,
    String? mainBackground,
    String? secondaryBackground,
    String? maskBackground,
    String? hudBackground,
    String? separatorLineColor,
    String? inputLineEnable,
    String? inputLineDisable,
    String? stateSafe,
    String? stateWarning,
    String? stateError,
    String? appName,
  }) async {
    try {
      final result = await _channel.invokeMethod('setUIConfiguration', {
        'isDarkMode': isDarkMode,
        'brandColor': brandColor,
        if (brandHighlight != null) 'brandHighlight': brandHighlight,
        if (brandDisable != null) 'brandDisable': brandDisable,
        if (brand2 != null) 'brand2': brand2,
        if (brand2Highlight != null) 'brand2Highlight': brand2Highlight,
        if (brand2Disable != null) 'brand2Disable': brand2Disable,
        if (textColor != null) 'textColor': textColor,
        if (secondaryTextColor != null) 'secondaryTextColor': secondaryTextColor,
        if (tertiaryTextColor != null) 'tertiaryTextColor': tertiaryTextColor,
        if (lightTextColor != null) 'lightTextColor': lightTextColor,
        if (linkTextColor != null) 'linkTextColor': linkTextColor,
        if (mainBackground != null) 'mainBackground': mainBackground,
        if (secondaryBackground != null) 'secondaryBackground': secondaryBackground,
        if (maskBackground != null) 'maskBackground': maskBackground,
        if (hudBackground != null) 'hudBackground': hudBackground,
        if (separatorLineColor != null) 'separatorLineColor': separatorLineColor,
        if (inputLineEnable != null) 'inputLineEnable': inputLineEnable,
        if (inputLineDisable != null) 'inputLineDisable': inputLineDisable,
        if (stateSafe != null) 'stateSafe': stateSafe,
        if (stateWarning != null) 'stateWarning': stateWarning,
        if (stateError != null) 'stateError': stateError,
        if (appName != null) 'appName': appName,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'setUIConfiguration failed'};
    }
  }

  // ── Push Notification — matching iOS GWIoTPushNotificationBridge ──────

  /// Forward received push notification payload to SDK.
  /// iOS: GWIoT.shared.receivePushNotification(noti:)
  /// Call this when your FCM/APNs handler receives a notification.
  static Future<Map<String, dynamic>> receivePushNotification(Map<String, dynamic> payload) async {
    try {
      final result = await _channel.invokeMethod('receivePushNotification', {'payload': payload});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'receivePushNotification failed'};
    }
  }

  /// Forward push notification click to SDK.
  /// iOS: GWIoT.shared.clickPushNotification(noti:)
  /// Call this when user taps on a notification.
  static Future<Map<String, dynamic>> clickPushNotification(Map<String, dynamic> payload) async {
    try {
      final result = await _channel.invokeMethod('clickPushNotification', {'payload': payload});
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'clickPushNotification failed'};
    }
  }
}
