plugins {
    alias(libs.plugins.android.application)
}

// This is a separate, non-Compose CarPlay host for Dalvik/Android 4.2 (API 17).
// Do not lower :mobile's minSdk or package current AndroidX UI components here.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
// Simulator-only APK: no actual Bluetooth, Wi-Fi Direct, MFi, or iPhone handshake.
// Always use a separate Android applicationId; never overwrite the head-unit build.
val emulatorMockConnect = providers.gradleProperty("diplayEmulatorMockConnect")
    .map { it.equals("true", ignoreCase = true) }.orElse(false).get()
val emulatorMockClipDir = providers.gradleProperty("diplayMockClipDir")
    .orNull?.let { file(it).canonicalFile }
if (emulatorMockConnect) {
    require(localAuthenticationAssets == null) {
        "Never supply MFi identity assets to the simulator-only Mock APK"
    }
    require(emulatorMockClipDir != null &&
        emulatorMockClipDir.resolve("emulator-mock/sample.h264").isFile) {
        "Set -PdiplayMockClipDir to a directory containing emulator-mock/sample.h264"
    }
}
// Some Android 4.2 head units can install only through their vendor's app market.
// Keep the default identity unless the owner has identified a safe, replaceable package.
val legacyApplicationId = if (emulatorMockConnect) {
    "com.hick.diplay.simulator.mock"
} else {
    providers.gradleProperty("diplayLegacyApplicationId")
        .orElse("com.shihab.diplay.legacy").get()
}
require(Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    .matches(legacyApplicationId)) { "Invalid -PdiplayLegacyApplicationId: $legacyApplicationId" }

android {
    namespace = "com.shilapi.xcertplay.legacy"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = legacyApplicationId
        minSdk = 17
        targetSdk = 28
        versionCode = if (emulatorMockConnect) 90013 else 13
        versionName = if (emulatorMockConnect) "0.2.32-api17-emulator-MOCK" else
            "0.2.32-android42-auto-diagnostic-v13"
        multiDexEnabled = true
        buildConfigField("boolean", "EMULATOR_MOCK_CONNECT", emulatorMockConnect.toString())
        manifestPlaceholders["diplayAppLabel"] = if (emulatorMockConnect)
            "DiPlay 模拟器 MOCK" else "DiPlay (Android 4.2)"
    }
    buildFeatures {
        buildConfig = true
    }

    lint {
        // This is an experimental Android 4.2 head-unit test build, not a Play Store listing.
        // Keep the legacy target behavior rather than raising it solely for Play policy.
        disable += "ExpiredTargetSdkVersion"
    }

    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }
    if (emulatorMockConnect) {
        sourceSets.getByName("main").assets.srcDir(checkNotNull(emulatorMockClipDir))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        // :shared's NDK 28 native helpers target API 24; the Java-only legacy
        // H.264/audio renderer and wireless transport must not load them on Android 4.2.
        jniLibs.excludes += "**/*.so"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    testImplementation(libs.junit)
}

val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require explicitly supplied local MFi identity for a standalone Android 4.2 test APK."
    doLast {
        check(localAuthenticationAssets != null &&
            listOf("identity.pk8", "certificate.p7b").all {
                localAuthenticationAssets.resolve("offline-mfi/$it").let { f -> f.isFile && f.length() > 0 }
            }) { "Set DIPLAY_AUTH_ASSETS_DIR to an external folder with offline-mfi/identity.pk8 and certificate.p7b" }
        val expected = setOf("offline-mfi/identity.pk8", "offline-mfi/certificate.p7b")
        val actual = localAuthenticationAssets.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(localAuthenticationAssets).invariantSeparatorsPath }.toSet()
        check(actual == expected) {
            "The legacy APK may contain only $expected; remove unexpected assets from DIPLAY_AUTH_ASSETS_DIR"
        }
    }
}

// Supplying an assets directory to any debug build must never silently package other files.
if (localAuthenticationAssets != null) {
    tasks.named("preBuild").configure { dependsOn(verifyStandaloneAuthentication) }
}

tasks.register("assembleStandaloneDebug") {
    group = "build"
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
