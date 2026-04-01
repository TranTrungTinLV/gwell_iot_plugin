import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';

/// Flutter plugin for Gwell IoT SDK (C2C mode).
///
/// Provides camera live view, playback, device management,
/// QR scan, and device binding through native Gwell SDK.
///
/// Method names match iOS GWIoTMethodChannel for consistency.
class GwellIotPlugin {
  static const _channel = MethodChannel('com.reoqoo/gwiot');

  static Map<String, dynamic> _asMap(dynamic raw) {
    if (raw == null) return <String, dynamic>{};
    if (raw is Map) return Map<String, dynamic>.from(raw);
    debugPrint("rawwwwwww::::::${raw}");
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
      debugPrint("rawDevices:::::${rawDevices}");
      if (rawDevices is List) {
        return rawDevices
            .whereType<Map>()
            .map((e) => Map<String, dynamic>.from(e))
            .toList();
      }
    }
    return const <Map<String, dynamic>>[];
  }

  // ── Camera Features (Built-in UI) ─────────────────────────────────────

  /// Open live view for a device (SDK built-in UI).
  /// Matches iOS `openDeviceHome`.
  static Future<Map<String, dynamic>> openDeviceHome(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceHome', {
        'deviceId': deviceId,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Live view failed'};
    }
  }

  /// Alias for [openDeviceHome].
  static Future<Map<String, dynamic>> openLiveView(String deviceId) =>
      openDeviceHome(deviceId);

  /// Open playback for a device (SDK built-in UI).
  static Future<Map<String, dynamic>> openPlayback(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openPlayback', {
        'deviceId': deviceId,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Playback failed'};
    }
  }

  /// Open playback with specific start time.
  static Future<Map<String, dynamic>> openPlaybackWithTime({
    required String deviceId,
    required int startTime,
  }) async {
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
  static Future<Map<String, dynamic>> getCloudPlaybackPermission(
      String deviceId) async {
    try {
      final result =
          await _channel.invokeMethod('getCloudPlaybackPermission', {
        'deviceId': deviceId,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {
        'success': false,
        'hasPermission': false,
        'error': e.message ?? 'Failed'
      };
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
  static Future<Map<String, dynamic>> openBindProductList() =>
      openProductList();

  /// Open bind flow with QR value.
  static Future<Map<String, dynamic>> openBindByQRCode(String qrValue) async {
    try {
      final result = await _channel.invokeMethod('openBindByQRCode', {
        'qrValue': qrValue,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Bind failed'};
    }
  }

  // ── Device Pages ──────────────────────────────────────────────────────

  /// Open device settings page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceSettingPage(
      String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceSettingPage', {
        'deviceId': deviceId,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Settings failed'};
    }
  }

  /// Alias for [openDeviceSettingPage].
  static Future<Map<String, dynamic>> openDeviceSettings(String deviceId) =>
      openDeviceSettingPage(deviceId);

  /// Open device info page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceInfoPage(
      String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceInfoPage', {
        'deviceId': deviceId,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Info failed'};
    }
  }

  /// Open events/alerts page (SDK built-in UI).
  static Future<Map<String, dynamic>> openEventsPage(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openEventsPage', {
        'deviceId': deviceId,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Events failed'};
    }
  }

  /// Alias for [openEventsPage].
  static Future<Map<String, dynamic>> openDeviceEvents(String deviceId) =>
      openEventsPage(deviceId);

  /// Open message center page (SDK built-in UI).
  static Future<Map<String, dynamic>> openMessageCenterPage() async {
    try {
      final result = await _channel.invokeMethod('openMessageCenterPage');
      return _asMap(result);
    } on PlatformException catch (e) {
      return {
        'success': false,
        'error': e.message ?? 'Message center failed'
      };
    }
  }

  /// Open device share page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDevSharePage(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDevSharePage', {
        'deviceId': deviceId,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'Share page failed'};
    }
  }

  // ── Language & UI ─────────────────────────────────────────────────────

  /// Set SDK language ("vi", "en", "zh").
  static Future<Map<String, dynamic>> setLanguage(String code) async {
    try {
      final result = await _channel.invokeMethod('setLanguage', {
        'code': code,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {'success': false, 'error': e.message ?? 'setLanguage failed'};
    }
  }

  /// Set UI configuration (brand color etc.)
  static Future<Map<String, dynamic>> setUIConfiguration({
    required String brandColor,
  }) async {
    try {
      final result = await _channel.invokeMethod('setUIConfiguration', {
        'brandColor': brandColor,
      });
      return _asMap(result);
    } on PlatformException catch (e) {
      return {
        'success': false,
        'error': e.message ?? 'setUIConfiguration failed'
      };
    }
  }
}
