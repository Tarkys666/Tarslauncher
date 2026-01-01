package com.tarslauncher.app.utils

import androidx.compose.runtime.staticCompositionLocalOf

interface MotionDurationScale { fun scale(durationMillis: Int): Int }

class StaticMotionDurationScale(private val factor: Float) : MotionDurationScale {
    override fun scale(durationMillis: Int) = (durationMillis * factor).toInt()
}

val LocalMotionDurationScale = staticCompositionLocalOf<MotionDurationScale> { StaticMotionDurationScale(1f) }
