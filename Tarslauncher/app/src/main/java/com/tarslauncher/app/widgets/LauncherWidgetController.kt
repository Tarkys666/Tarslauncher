package com.tarslauncher.app.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/** Widget bindings belong to this device, never to the cloud layout. */
class LauncherWidgetController(private val activity: Activity, restoringActivity: Boolean) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val preferences = activity.getSharedPreferences("home_widgets", Context.MODE_PRIVATE)
    val host = object : AppWidgetHost(activity.applicationContext, HOST_ID) {
        override fun onProvidersChanged() {
            super.onProvidersChanged()
            refresh()
        }
    }
    private val mutableIds = MutableStateFlow<List<Int>>(emptyList())
    val widgetIds = mutableIds.asStateFlow()

    private var pendingId: Int
        get() = preferences.getInt("pending_id", AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) { preferences.edit().putInt("pending_id", value).apply() }

    init {
        // A fresh launch must not expose a half-configured widget left by a killed flow.
        if (!restoringActivity) cancelPending()
        refresh()
    }

    fun startListening() {
        host.startListening()
        refresh()
    }

    fun stopListening() = host.stopListening()

    fun refresh() {
        val boundIds = host.appWidgetIds.filter { it != pendingId && info(it) != null }.sorted()
        // Preserve the first visible widget when upgrading from the multi-widget layout.
        boundIds.drop(1).forEach(::deleteBinding)
        mutableIds.value = boundIds.take(1)
    }

    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    fun providers(): List<AppWidgetProviderInfo> = manager.installedProviders
        .filter { info ->
            info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 &&
                (Build.VERSION.SDK_INT < 28 || info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0)
        }
        .sortedBy { it.loadLabel(activity.packageManager).lowercase() }

    @Suppress("DEPRECATION")
    fun add(provider: AppWidgetProviderInfo) {
        cancelPending()
        try {
            val id = host.allocateAppWidgetId()
            pendingId = id
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            }
            if (manager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, options)) {
                configureOrFinish()
            } else {
                activity.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
                }, REQUEST_BIND)
            }
        } catch (_: RuntimeException) {
            fail()
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return
        val id = pendingId
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val returnedId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id) ?: id
        if (resultCode != Activity.RESULT_OK || returnedId != id || info(id) == null) {
            cancelPending()
        } else if (requestCode == REQUEST_BIND) {
            configureOrFinish()
        } else {
            finishAdding()
        }
    }

    private fun configureOrFinish() {
        val provider = info(pendingId) ?: return fail()
        if (provider.configure == null) {
            finishAdding()
            return
        }
        try {
            host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, REQUEST_CONFIGURE, null)
        } catch (_: RuntimeException) {
            fail()
        }
    }

    private fun finishAdding() {
        val id = pendingId
        // Keep the previous widget until the replacement has been configured successfully.
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        host.appWidgetIds.filter { it != id }.forEach(::deleteBinding)
        refresh()
    }

    private fun cancelPending() {
        val id = pendingId
        // Never delete an ID supplied by another app's result intent.
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID && id in host.appWidgetIds) {
            deleteBinding(id)
        }
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun fail() {
        cancelPending()
        Toast.makeText(activity, "Unable to add this widget. Please try again.", Toast.LENGTH_LONG).show()
    }

    fun remove(id: Int) {
        if (id !in widgetIds.value) return
        deleteBinding(id)
        refresh()
    }

    private fun deleteBinding(id: Int) {
        host.deleteAppWidgetId(id)
        preferences.edit().remove("height_$id").apply()
    }

    fun minimumHeight(info: AppWidgetProviderInfo): Int {
        val pixels = if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) {
            info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight
        } else info.minHeight
        return ceil(pixels / activity.resources.displayMetrics.density).toInt().coerceAtLeast(48)
    }

    fun height(id: Int, info: AppWidgetProviderInfo): Int = preferences.getInt(
        "height_$id",
        ceil(info.minHeight / activity.resources.displayMetrics.density).toInt().coerceAtLeast(96)
    ).coerceAtLeast(minimumHeight(info))

    fun resize(id: Int, height: Int) {
        val provider = info(id) ?: return
        if (id !in widgetIds.value) return
        val min = minimumHeight(provider)
        val max = if (Build.VERSION.SDK_INT >= 31 && provider.maxResizeHeight > 0) {
            (provider.maxResizeHeight / activity.resources.displayMetrics.density).toInt().coerceAtLeast(min)
        } else maxOf(600, min)
        preferences.edit().putInt("height_$id", height.coerceIn(min, max)).apply()
    }

    companion object {
        private const val HOST_ID = 1024
        private const val REQUEST_BIND = 4010
        private const val REQUEST_CONFIGURE = 4011
    }
}
