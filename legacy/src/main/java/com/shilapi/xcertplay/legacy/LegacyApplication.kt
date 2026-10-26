package com.shilapi.xcertplay.legacy

import androidx.multidex.MultiDexApplication

/** Dalvik does not load secondary DEX files automatically before Android 5.0. */
class LegacyApplication : MultiDexApplication()
