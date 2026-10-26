# Build DiPlay

Use this procedure to build the DiPlay app for an Android head unit.
Run the commands from the repository root, where `gradlew` and `settings.gradle.kts` are located.

## Select the application

This repository contains several Android applications.
Select `mobile` to build the main DiPlay app.

| Gradle module | Purpose | Debug APK |
| --- | --- | --- |
| `:mobile` | Main DiPlay app for Android head units | `mobile/build/outputs/apk/debug/mobile-debug.apk` |
| `:legacy` | Experimental wireless-only Android 4.2.x / API 17 head-unit host | `legacy/build/outputs/apk/debug/legacy-debug.apk` |
| `:maphost` | Sample application that displays a map from DiPlay | `samples/maphost/build/outputs/apk/debug/maphost-debug.apk` |
| `:home` | Optional DiPlay Home launcher | `samples/home/build/outputs/apk/debug/home-debug.apk` |
| `:automotive` | Separate Android Automotive application inherited from xcertplay | `automotive/build/outputs/apk/debug/automotive-debug.apk` |

The `:common` and `:shared` modules are libraries.
The applications use these libraries.

If the installed app is **DiPlay map host**, you selected `:maphost` or its APK.
Build `:mobile` and select `mobile-debug.apk` to install the main app.
A command such as `./gradlew assembleDebug` can build more than one application.
Use the module name in each build command to select the required application.

## Prepare the build environment

Install these tools:

- JDK 25.
- Android SDK Platform 37 (`platforms;android-37.0`).
- Android SDK Build-Tools 36.0.0.
- Android SDK Platform-Tools.
- Android NDK 28.2.13676358.

Use the Gradle wrapper included in this repository.
The first build needs an internet connection to download dependencies.

1. Open the repository root in Android Studio.
2. Install the Android SDK packages with **Tools → SDK Manager**.
3. Set **Gradle JDK** to JDK 25 in the Android Studio Gradle settings.
4. Wait for Gradle sync to finish.

For a terminal build, set `JAVA_HOME` to your JDK 25 directory.
Set `ANDROID_HOME` to your Android SDK directory.
You can also specify the SDK directory in the root `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Keep `local.properties` outside Git.
Check the Java version before the build:

```sh
java -version
```

The output must identify Java 25.
On Windows, use `gradlew.bat` in place of `./gradlew` in the commands below.

## Build the main app

For a source build without runtime authentication assets:

```sh
./gradlew :mobile:assembleDebug
```

Check that this file exists:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
```

The debug application ID is `com.shihab.diplay.hudtest`.
The release application ID is `com.shihab.diplay`.

The source APK contains no accessory identity unless you supply runtime authentication assets.
Standalone CarPlay needs these assets to connect to an iPhone.
Use the car-test procedure below for that purpose.
Tests generate synthetic identities at runtime.
Keep test private keys outside Git.

### Use Android Studio

1. Open **Run → Edit Configurations**.
2. Select an **Android App** configuration, or create one.
3. Set **Module** to `mobile`.
4. Select this configuration in the toolbar.
5. Select the Android device.
6. Click **Run** to install the main debug app.

To build an APK without installation, run `:mobile:assembleDebug` in the Android Studio terminal.
Select the APK from the `mobile` output directory.

## Android 4.2.x experimental APK

Android 4.2.x (API 17) **cannot run the standard `:mobile` APK**. The separate `:legacy`
application retains the **wireless** Bluetooth iAP2 + Wi-Fi/AirPlay transport but uses the platform Holo Views,
`MediaCodec` H.264 decoding, and old `AudioTrack` PCM/AAC playback. Connection options are
Wi-Fi Direct, manually enabled head-unit hotspot, and an existing Wi-Fi LAN.
It deliberately has no wired USB/NCM CarPlay, Compose, Android Auto, BYD cluster integration,
microphone/phone-call audio, HEVC or software Opus decoder.
The default package ID is `com.shihab.diplay.legacy`, so it can be
installed alongside the modern app on another test device. For old vendor app stores,
the application ID can be set at build time without altering the Java/Kotlin namespace:

```sh
./gradlew :legacy:assembleDebug -PdiplayLegacyApplicationId=com.example.replaceableapp
```

**No-ADB Android 4.2 head units:** The browser can download an APK from an HTTP server
on the same LAN (for example, serve the `legacy/build/outputs/apk/debug` directory from
the Mac), but a restricted vendor installer may still block installation. Changing the
package ID alone **does not** bypass installation policy or Android's signature check.
An APK signed with a different key cannot update an installed package; safely replacing
one requires removing the existing package, which deletes its app data. Do not select a
system app, launcher, or an app with irreplaceable data as the replacement target.
If the head unit permits only its original app store to initiate installation, inspect
that store's legitimate download/install flow before preparing the final APK. You need
the precise package ID and store request details; do not assume a package name is
whitelisted based on a browser download alone.

For a head unit with **browser access but no ADB or system Settings**, first run the
read-only diagnostic server on the Mac, using its actual Wi-Fi LAN IP address:

```sh
python3 scripts/car_install_probe.py --host 192.168.1.108 --port 18082
```

Open `http://<Mac LAN IP>:18082/` on the head unit. The page provides a harmless
~40 KB ZIP delivered over ~20 seconds to test whether a Download notification can
open Settings via a long-press, and a separate link to download the built APK.
Downloading alone cannot bypass the car's installer restrictions. Take a photo of
the first installer error, do **not** uninstall built-in apps, and stop the server
with Ctrl-C when diagnostics are complete. The server only serves those two fixed
downloads and a static instructions page; use it only on a trusted LAN.

Build the source-only APK (it will install and show the interface but cannot authenticate
CarPlay without an identity):

```sh
./gradlew :legacy:testDebugUnitTest :legacy:assembleDebug
```

For a wireless CarPlay test, provide your **own authorized** local MFi identity in an external
folder as explained under *Build a standalone car-test APK* below, then build:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :legacy:assembleStandaloneDebug
```

Install `legacy/build/outputs/apk/debug/legacy-debug.apk` on the **Android 4.2 car head unit**,
not on the iPhone. Turn on Bluetooth and pair the iPhone in the head unit's system settings.
In DiPlay choose **选择手机**, then select one of three Wi-Fi modes:

- **Wi-Fi Direct**: DiPlay requests a temporary Wi-Fi Direct group from Android; the firmware
  must support acting as a group owner. The iPhone is invited to the resulting AP over Bluetooth.
- **车机热点**: First enable the car's own hotspot in its system settings, then enter the
  actual SSID and password in **网络参数**. DiPlay attaches to that network; it does not alter the AP.
- **同一 Wi-Fi**: Join the same external Wi-Fi on the car and iPhone, then enter that SSID and
  password. Android 4.2 uses a dedicated Wi-Fi station/IPv4 path without API 21 NetworkCallback.

Click **连接** and approve any CarPlay prompt on the iPhone. No USB connection is involved.
API 17 supports classic Bluetooth and Wi-Fi Direct, but device firmware may disable either;
these modes have not been validated on a physical Android 4.2 head unit.
H.264 playback also depends on the head unit's hardware decoder.
Use `adb logcat -s DiPlay-Android42 IphoneCarPlayConfiguration` for first-connection diagnostics.

The `:legacy` APK is **experimental**: passing Gradle and unit tests establishes API 17
build/install eligibility, not successful iPhone pairing or real-time CarPlay performance.

## Check the source build

Run the unit tests, lint checks, and main debug build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

Check that Gradle reports `BUILD SUCCESSFUL`.
For the complete CI procedure, see [the Android workflow](../.github/workflows/android.yml).
That workflow also checks and builds the `home` and `maphost` applications.

## Build a standalone car-test APK

Prepare an external directory with these runtime authentication files:

```text
runtime-assets/
└── offline-mfi/
    ├── identity.pk8
    └── certificate.p7b
```

Use only the intended runtime files in this directory.
Keep the directory and its private key outside Git.
The build rejects unexpected credential containers in APK assets.

1. Set `DIPLAY_AUTH_ASSETS_DIR` to the absolute directory path.
2. Run the standalone debug task.

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

The task stops if either required file is absent or empty.
Its APK output is `mobile/build/outputs/apk/debug/mobile-debug.apk`.
Check both files in the APK against your selected local inputs:

- `assets/offline-mfi/identity.pk8`.
- `assets/offline-mfi/certificate.p7b`.

Use this APK for a standalone connection test.
Update the existing test app with the same signing key to keep its settings.
Different signing keys cannot update the same installed application.

## Build a release APK

Prepare the runtime assets as described in the car-test procedure.
Set these environment variables locally:

| Variable | Value |
| --- | --- |
| `DIPLAY_AUTH_ASSETS_DIR` | Absolute path to the runtime assets directory |
| `ANDROID_KEYSTORE_PATH` | Path to your Android signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the keystore and passwords outside Git.
Run the release checks and build:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

The output is `mobile/build/outputs/apk/release/mobile-release.apk`.
The APK contains the runtime identity you supplied.
Recipients can extract that identity from the APK.
The APK excludes the Android signing key.
See [the project notices](THIRD_PARTY_NOTICES.md) for the experimental identity used in public releases.

The public source archive matches the release tag.
It excludes runtime identities, signing keys, local configuration, and build output.
The retired `build-beta.py` helper is no longer part of the build procedure.

## Correct build problems

| Symptom | Action |
| --- | --- |
| Installed app is DiPlay map host | Select `mobile` in Android Studio. Build and install the APK from the `mobile` output directory. |
| Gradle cannot find the Android SDK | Set `ANDROID_HOME` or the `sdk.dir` value in `local.properties`. |
| A required SDK or NDK package is absent | Install the listed package with SDK Manager. |
| Java or Gradle JDK version is incorrect | Set the terminal JDK and Android Studio Gradle JDK to Java 25. |
| Source APK cannot start standalone CarPlay | Supply runtime authentication assets. Use `:mobile:assembleStandaloneDebug`. |
| Standalone task reports missing authentication files | Check the external directory path and both required files. |
| Android rejects an app update | Use the same application ID and signing key as the installed app. |
