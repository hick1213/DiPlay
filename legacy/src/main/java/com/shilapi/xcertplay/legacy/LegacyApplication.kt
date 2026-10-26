package com.shilapi.xcertplay.legacy

import androidx.multidex.MultiDexApplication
import com.shilapi.xcertplay.diagnostic.HeadUnitAutoUploader
import com.shilapi.xcertplay.diagnostic.HeadUnitDiagnostics

/** Dalvik does not load secondary DEX files automatically before Android 5.0. */
class LegacyApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        HeadUnitDiagnostics.install(this, "DiPlay CarPlay")
        HeadUnitDiagnostics.event("APP_START", "CarPlay process initialized")
        if (HeadUnitDiagnostics.hasPreviousCrash()) {
            HeadUnitAutoUploader.schedule(this, "previous CarPlay connection interrupted")
        }
    }
}
