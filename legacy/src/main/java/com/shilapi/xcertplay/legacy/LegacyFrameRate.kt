package com.shilapi.xcertplay.legacy

/** The values DiPlay can advertise as maxFPS in the CarPlay /info response. */
internal object LegacyFrameRate {
    val OPTIONS = listOf(30, 35, 40, 45, 50, 55, 60)

    fun default(api: Int): Int = if (api >= 24) 60 else 30

    fun fromSaved(fps: Int): Int = fps.takeIf { it in OPTIONS } ?: 30
}
