import 'package:flutter/services.dart';

/// Flutter plugin for Gwell IoT SDK (C2C mode).
///
/// Provides camera live view, playback, device management,
/// QR scan, and device binding through native Gwell SDK.
class GwellIotPlugin {
  static const _channel = MethodChannel('com.foursgen.connect/gwell_iot');

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
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'SDK init failed'};
    }
  }

  // ── Phone Unique ID ───────────────────────────────────────────────────

  /// Get phone unique ID (used for C2C backend registration).
  static Future<String> getPhoneUniqueId() async {
    try {
      final result = await _channel.invokeMethod('getPhoneUniqueId');
      final map = Map<String, dynamic>.from(result);
      return map['phoneUniqueId'] as String? ?? '';
    } on PlatformException catch (_) {
      return '';
    }
  }

  // ── C2C Login ─────────────────────────────────────────────────────────

  /// Login with C2C credentials from your backend.
  ///
  /// Uses `login2()` internally for proper result handling.
  /// [expand] should be the raw `expend` field from Gwell's thirdCustLogin response.
  static Future<Map<String, dynamic>> loginWithC2CInfo({
    required String accessId,
    required String accessToken,
    required String expireTime,
    required String terminalId,
    required String expand,
  }) async {
    try {
      final result = await _channel.invokeMethod('loginWithC2CInfo', {
        'accessId': accessId,
        'accessToken': accessToken,
        'expireTime': expireTime,
        'terminalId': terminalId,
        'expand': expand,
      });
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'C2C login failed'};
    }
  }

  // ── Device Management ─────────────────────────────────────────────────

  /// Query device list from Gwell cloud.
  static Future<Map<String, dynamic>> queryDeviceList() async {
    try {
      final result = await _channel.invokeMethod('queryDeviceList');
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'Query failed'};
    }
  }

  // ── Camera Features ───────────────────────────────────────────────────

  /// Open live view for a device (SDK built-in UI).
  static Future<Map<String, dynamic>> openLiveView(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openLiveView', {
        'deviceId': deviceId,
      });
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'Live view failed'};
    }
  }

  /// Open playback for a device (SDK built-in UI).
  static Future<Map<String, dynamic>> openPlayback(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openPlayback', {
        'deviceId': deviceId,
      });
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'Playback failed'};
    }
  }

  /// Open device settings page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceSettings(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceSettings', {
        'deviceId': deviceId,
      });
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'Settings failed'};
    }
  }

  /// Open device events/alerts page (SDK built-in UI).
  static Future<Map<String, dynamic>> openDeviceEvents(String deviceId) async {
    try {
      final result = await _channel.invokeMethod('openDeviceEvents', {
        'deviceId': deviceId,
      });
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'Events failed'};
    }
  }

  // ── QR Scan & Device Binding ──────────────────────────────────────────

  /// Open QR code scanner (SDK built-in, auto-binds device).
  static Future<Map<String, dynamic>> openScanQRCode() async {
    try {
      final result = await _channel.invokeMethod('openScanQRCode');
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'QR scan failed'};
    }
  }

  /// Open bindable product list.
  static Future<Map<String, dynamic>> openBindProductList() async {
    try {
      final result = await _channel.invokeMethod('openBindProductList');
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'Bind list failed'};
    }
  }

  // ── Logout ────────────────────────────────────────────────────────────

  /// Logout from Gwell SDK.
  static Future<Map<String, dynamic>> logout() async {
    try {
      final result = await _channel.invokeMethod('logout');
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? 'Logout failed'};
    }
  }

  /// Check if currently logged in.
  static Future<bool> isLoggedIn() async {
    try {
      final result = await _channel.invokeMethod('isLoggedIn');
      return (result as Map?)?['isLoggedIn'] == true;
    } catch (_) {
      return false;
    }
  }
}
