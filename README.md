# Gwell IoT Plugin

Flutter plugin for **Gwell IoT SDK** (C2C mode) — camera live view, playback, device management, QR binding.

## Requirements

- Flutter ≥ 3.29.0
- Android minSdk ≥ 26
- **ARM64 device** (x86 emulators not supported by Gwell SDK)

---

## Integration Guide

### 1. Add dependency

In your project's `pubspec.yaml`:

```yaml
dependencies:
  gwell_iot_plugin: ^1.0.0
```

Then run:

```bash
flutter pub get
```

### 2. Add Gwell credentials

Copy the template file and fill in your credentials:

```bash
cp android/gradle.properties.example android/gradle.properties
```

Edit `android/gradle.properties` with your actual Nexus credentials:

```properties
GWIOT_NEXUS_USERNAME=your_nexus_username
GWIOT_NEXUS_PASSWORD=your_nexus_password
```

> ⚠️ `gradle.properties` is already in `.gitignore`. Never commit it to version control.

### 3. Add Gwell Maven repos

In your project's `android/settings.gradle.kts`, add Gwell repos inside `dependencyResolutionManagement > repositories`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // ... your existing repos (Google, etc.) ...

        // ── Gwell repos (required) ──
        val nexusUsername = extra.properties["GWIOT_NEXUS_USERNAME"]?.toString() ?: ""
        val nexusPassword = extra.properties["GWIOT_NEXUS_PASSWORD"]?.toString() ?: ""

        maven {
            url = uri("https://nexus-sg.gwell.cc/nexus/repository/maven-releases/")
            credentials {
                username = nexusUsername
                password = nexusPassword
            }
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("https://nexus-sg.gwell.cc/nexus/repository/maven-gwiot/")
            credentials {
                username = nexusUsername
                password = nexusPassword
            }
            isAllowInsecureProtocol = true
        }
        maven { url = uri("https://mvn.zztfly.com/android") }
        maven { url = uri("https://jitpack.io") }

        google()
        mavenCentral()
    }
}
```


### 3. Add JNI pickFirsts (if conflicts)

If you have other native SDKs (e.g. Tuya), add this in `android/app/build.gradle.kts`:

```kotlin
android {
    packaging {
        jniLibs {
            pickFirsts += listOf(
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/arm64-v8a/libc++_shared.so"
                // Add more if build complains about duplicates
            )
        }
    }
}
```

### 4. Set minSdk

Ensure `android/app/build.gradle.kts` has:

```kotlin
defaultConfig {
    minSdk = 26   // Gwell SDK requires 26+
}
```

---

## Usage

### Import

```dart
import 'package:gwell_iot_plugin/gwell_iot_plugin.dart';
```

### Initialize SDK (once, at app start)

```dart
await GwellIotPlugin.initSdk(
  appId: 'YOUR_GWELL_APP_ID',
  appToken: 'YOUR_GWELL_APP_TOKEN',
  language: 'vi',  // 'vi', 'en', 'zh'
);
```

### Login with C2C credentials

```dart
// After your backend calls thirdCustLogin and returns gwellMapping:
final result = await GwellIotPlugin.loginWithC2CInfo(
  accessId: gwellMapping['gwellAccessId'],
  accessToken: gwellMapping['gwellAccessToken'],
  expireTime: gwellMapping['tokenExpireTime'],
  terminalId: gwellMapping['gwellTerminalId'],
  expand: gwellMapping['expend'] ?? '{"area":"sg","regRegion":"SG"}',
);

if (result['success'] == true) {
  print('Login OK');
}
```

### Query devices

```dart
final result = await GwellIotPlugin.queryDeviceList();
if (result['success'] == true) {
  final devices = result['devices'] as List;
  for (final dev in devices) {
    print('Device: ${dev['deviceId']} - ${dev['deviceName']}');
  }
}
```

### Camera features

```dart
// Open live view (SDK built-in UI)
await GwellIotPlugin.openLiveView('DEVICE_ID');

// Open playback
await GwellIotPlugin.openPlayback('DEVICE_ID');

// Open device settings
await GwellIotPlugin.openDeviceSettings('DEVICE_ID');

// Open events/alerts
await GwellIotPlugin.openDeviceEvents('DEVICE_ID');
```

### QR scan & device binding

```dart
await GwellIotPlugin.openScanQRCode();
await GwellIotPlugin.openBindProductList();
```

### Logout

```dart
await GwellIotPlugin.logout();
```

### Check login status

```dart
final isLoggedIn = await GwellIotPlugin.isLoggedIn();
```

---

## API Reference

| Method | Description |
|--------|-------------|
| `initSdk(appId, appToken, language)` | Initialize Gwell SDK |
| `getPhoneUniqueId()` | Get device unique ID for C2C registration |
| `loginWithC2CInfo(...)` | Login with C2C credentials from backend |
| `queryDeviceList()` | Get list of bound devices |
| `openLiveView(deviceId)` | Open camera live view |
| `openPlayback(deviceId)` | Open camera playback |
| `openDeviceSettings(deviceId)` | Open device settings page |
| `openDeviceEvents(deviceId)` | Open device events/alerts |
| `openScanQRCode()` | Open QR scanner for device binding |
| `openBindProductList()` | Open bindable product list |
| `logout()` | Logout from Gwell SDK |
| `isLoggedIn()` | Check if currently logged in |

---

## Notes

- **expand field**: Don't hardcode! Use the `expend` field returned by Gwell's `thirdCustLogin` API on your backend. Pass it directly to the SDK.
- **area=sg**: This is assigned by Gwell during registration. Do not change it.
- **ARM64 only**: Gwell SDK only supports ARM devices, not x86 emulators.
