## 1.1.0

* **iOS-safe platform guard**: All methods return safe defaults on iOS (no crash)
* Added `isSupported` getter to check platform compatibility
* Added `_invoke()` helper — centralized MethodChannel error handling
* EventChannel returns `Stream.empty()` on iOS
* Removed redundant try/catch boilerplate across 30+ methods
* Cleaned up unused `cupertino.dart` import

## 1.0.0

* Initial release
* C2C login with `login2()` API
* Device management: query, live view, playback, settings, events
* QR scan & device binding
* Standalone SDK initializer (no Application subclass needed)
