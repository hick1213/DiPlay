package com.shilapi.xcertplay.diagnostic;

import android.app.Application;

/** Dalvik-compatible crash recorder for the standalone, Java-only LAN receiver. */
public final class HeadUnitLogApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        HeadUnitDiagnostics.install(this, "LAN Cast");
        HeadUnitDiagnostics.event("APP_START", "LAN Cast process initialized");
        if (HeadUnitDiagnostics.hasPreviousCrash()) {
            HeadUnitAutoUploader.schedule(this, "LAN Cast recovered previous failure");
        }
    }
}
