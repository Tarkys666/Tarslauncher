package com.tarslauncher.app.onboarding

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the state of the user onboarding process.
 * Uses SharedPreferences to persist whether the user has completed the onboarding.
 */
class OnboardingManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Checks if the onboarding process has been completed.
     * @return `true` if completed, `false` otherwise.
     */
    fun isOnboardingComplete(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    /**
     * Marks the onboarding process as complete.
     */
    fun setOnboardingComplete() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "TarsLauncherOnboardingPrefs"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
