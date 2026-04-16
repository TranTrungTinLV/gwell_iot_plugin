import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/services.dart';

/// Flutter plugin for Gwell IoT SDK (C2C mode).
///
/// Provides camera live view, playback, device management,
/// QR scan, and device binding through native Gwell SDK.
///
/// **Android-only** — all methods return safe defaults on iOS.
class GwellIotPlugin {
  static const _channel = MethodChannel('com.reoqoo/gwiot');

  // ── EventChannel — matching iOS GWIoTEventChannel ─────────────────────
  static const _eventChannel = EventChannel('com.reoqoo/gwiot_events');

  /// Cached broadcast stream — all derived streams MUST use this.
  /// EventChannel only supports ONE active listener on Android,
  /// so calling receiveBroadcastStream() multiple times kills previous listeners.
  static Stream<Map<String, dynamic>>? _cachedEventStream;

  /// Whether the current platform supports this plugin.
  static bool get isSupported => Platform.isAndroid;

  /// Broadcast stream of all events from native SDK.
  /// Returns an empty stream on iOS (no native implementation).
  static Stream<Map<String, dynamic>> get events {
    if (!isSupported) return const Stream.empty();
    _cachedEventStream ??=
        _eventChannel
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

  /// Safe MethodChannel call — returns default on iOS.
  static Future<Map<String, dynamic>> _invoke(String method, [dynamic args]) async {
    if (!isSupported) return <String, dynamic>{'success': false, 'reason': 'unsupported_platform'};
    try {
      final result = await _channel.invokeMethod(method, args);
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? '$method failed'};
    } on MissingPluginException catch (_) {
      return {'success': false, 'reason': 'missing_plugin'};
    }
  }

  // ── SDK Init ──────────────────────────────────────────────────────────

  /// Initialize the Gwell SDK. Must be called once before any other methods.
  static Future<Map<String, dynamic>> initSdk({
    required String appId,
    required String appToken,
    String language = 'vi',
  }) async {
    if (!isSupported) return {'success': true, 'message': 'Not supported on this platform'};
    return _invoke('initGwellSdk', {'appId': appId, 'appToken': appToken, 'language': language});
  }

  // ── Phone Unique ID ───────────────────────────────────────────────────

  /// Get phone unique ID (used for C2C backend registration).
  static Future<String> getPhoneUniqueId() async {
    if (!isSupported) return '';
    final map = await _invoke('getPhoneUniqueId');
    return map['phoneUniqueId'] as String? ?? '';
  }

  /// Login SDK with C2C credentials from backend.
  static Future<Map<String, dynamic>> loginSDK({
    required String accessId,
    required String accessToken,
    String terminalId = '',
    String expireTime = '0',
    String region = 'VN',
    String? expand,
  }) {
    return _invoke('loginSDK', {
      'accessId': accessId,
      'accessToken': accessToken,
      'terminalId': terminalId,
      'expireTime': expireTime,
      'region': region,
      if (expand != null && expand.isNotEmpty) 'expand': expand,
    });
  }

  /// Legacy alias for [loginSDK].
  static Future<Map<String, dynamic>> loginWithC2CInfo({
    required String accessId,
    required String accessToken,
    required String expireTime,
    required String terminalId,
    required String expand,
  }) {
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
    if (!isSupported) return {'isLoggedIn': false};
    return _invoke('checkLoginStatus');
  }

  /// Check if currently logged in.
  static Future<bool> isLoggedIn() async {
    final status = await checkLoginStatus();
    return status['isLoggedIn'] == true;
  }

  /// Logout from Gwell SDK.
  static Future<Map<String, dynamic>> logout() => _invoke('logout');

  // ── Device Management ─────────────────────────────────────────────────

  /// Query device list from Gwell cloud.
  static Future<Map<String, dynamic>> getDeviceList() => _invoke('getDeviceList');

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
  static Future<String?> getLastSnapshotPath(String deviceId) async {
    if (!isSupported) return null;
    final map = await _invoke('getLastSnapshotPath', {'deviceId': deviceId});
    if (map['success'] == true) {
      final path = map['path'] as String?;
      return (path != null && path.isNotEmpty) ? path : null;
    }
    return null;
  }

  // ── Camera Features (Built-in UI) ─────────────────────────────────────

  /// Open live view for a device (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceHome(String deviceId) =>
      _invoke('openDeviceHome', {'deviceId': deviceId});

  /// Alias for [openDeviceHome].
  static Future<Map<String, dynamic>> openLiveView(String deviceId) => openDeviceHome(deviceId);

  /// Open built-in multi-camera surveillance page.
  static Future<Map<String, dynamic>> openMultiLivePage() => _invoke('openMultiLivePage');

  /// Open playback for a device (SDK built-in UI).
  static Future<Map<String, dynamic>> openPlayback(String deviceId) => _invoke('openPlayback', {'deviceId': deviceId});

  /// Open playback with specific start time.
  static Future<Map<String, dynamic>> openPlaybackWithTime({required String deviceId, required int startTime}) =>
      _invoke('openPlaybackWithTime', {'deviceId': deviceId, 'startTime': startTime});

  /// Check cloud playback permission for a device.
  static Future<Map<String, dynamic>> getCloudPlaybackPermission(String deviceId) =>
      _invoke('getCloudPlaybackPermission', {'deviceId': deviceId});

  // ── QR Scan & Device Binding ──────────────────────────────────────────

  /// Open QR code scanner (SDK built-in, auto-binds device).
  static Future<Map<String, dynamic>> openScanQRCode() => _invoke('openScanQRCode');

  /// Open bindable product list.
  static Future<Map<String, dynamic>> openProductList() => _invoke('openProductList');

  /// Alias for [openProductList].
  static Future<Map<String, dynamic>> openBindProductList() => openProductList();

  /// Open bind flow with QR value.
  static Future<Map<String, dynamic>> openBindByQRCode(String qrValue) =>
      _invoke('openBindByQRCode', {'qrValue': qrValue});

  // ── Device Pages ──────────────────────────────────────────────────────

  /// Open device settings page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceSettingPage(String deviceId) =>
      _invoke('openDeviceSettingPage', {'deviceId': deviceId});

  /// Alias for [openDeviceSettingPage].
  static Future<Map<String, dynamic>> openDeviceSettings(String deviceId) => openDeviceSettingPage(deviceId);

  /// Open device info page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceInfoPage(String deviceId) =>
      _invoke('openDeviceInfoPage', {'deviceId': deviceId});

  /// Open events/alerts page (SDK built-in UI).
  static Future<Map<String, dynamic>> openEventsPage(String deviceId) =>
      _invoke('openEventsPage', {'deviceId': deviceId});

  /// Alias for [openEventsPage].
  static Future<Map<String, dynamic>> openDeviceEvents(String deviceId) => openEventsPage(deviceId);

  /// Open message center page (SDK built-in UI).
  static Future<Map<String, dynamic>> openMessageCenterPage() => _invoke('openMessageCenterPage');

  /// Open device share page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDevSharePage(String deviceId) =>
      _invoke('openDevSharePage', {'deviceId': deviceId});

  // ── Device Unbind / Delete ──────────────────────────────────────────────

  /// Unbind (delete) a device from the account.
  static Future<Map<String, dynamic>> unbindDevice(String deviceId) => _invoke('unbindDevice', {'deviceId': deviceId});

  /// Alias for [unbindDevice].
  static Future<Map<String, dynamic>> deleteDevice(String deviceId) => unbindDevice(deviceId);

  // ── Firmware Upgrade ──────────────────────────────────────────────────

  /// Check firmware upgrade info for a single device.
  static Future<Map<String, dynamic>> checkDevUpgradeInfo(String deviceId) =>
      _invoke('checkDevUpgradeInfo', {'deviceId': deviceId});

  /// Batch check firmware upgrade info for multiple devices.
  static Future<Map<String, dynamic>> batchCheckDevUpgradeInfo(List<String> deviceIds) =>
      _invoke('batchCheckDevUpgradeInfo', {'deviceIds': deviceIds});

  /// Open batch firmware upgrade page (SDK built-in UI).
  static Future<Map<String, dynamic>> openBatchUpgradePage() => _invoke('openBatchUpgradePage');

  // ── Cloud Service & Membership ────────────────────────────────────────

  /// Open cloud storage page for a device (SDK built-in UI).
  static Future<Map<String, dynamic>> openCloudPage(String deviceId) =>
      _invoke('openCloudPage', {'deviceId': deviceId});

  /// Alias for [openCloudPage].
  static Future<Map<String, dynamic>> openCloudStoragePage(String deviceId) => openCloudPage(deviceId);

  /// Query membership/subscription info.
  static Future<Map<String, dynamic>> queryMembershipInfo({bool forceRefresh = true}) =>
      _invoke('queryMembershipInfo', {'forceRefresh': forceRefresh});

  /// Open membership center page (buy/upgrade cloud plans).
  static Future<Map<String, dynamic>> openMembershipCenterPage() => _invoke('openMembershipCenterPage');

  // ── Share Manager ─────────────────────────────────────────────────────

  /// Open share manager page.
  static Future<Map<String, dynamic>> openShareManagerPage() => _invoke('openShareManagerPage');

  // ── Album ─────────────────────────────────────────────────────────────

  /// Open album/gallery for a device (screenshots & recordings).
  static Future<Map<String, dynamic>> openAlbum(String deviceId) => _invoke('openAlbum', {'deviceId': deviceId});

  // ── Language & UI ─────────────────────────────────────────────────────

  /// Set SDK language ("vi", "en", "zh").
  static Future<Map<String, dynamic>> setLanguage(String code) => _invoke('setLanguage', {'code': code});

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
  }) {
    return _invoke('setUIConfiguration', {
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
  }

  // ── Push Notification — matching iOS GWIoTPushNotificationBridge ──────

  /// Forward received push notification payload to SDK.
  static Future<Map<String, dynamic>> receivePushNotification(Map<String, dynamic> payload) =>
      _invoke('receivePushNotification', {'payload': payload});

  /// Forward push notification click to SDK.
  static Future<Map<String, dynamic>> clickPushNotification(Map<String, dynamic> payload) =>
      _invoke('clickPushNotification', {'payload': payload});
}
