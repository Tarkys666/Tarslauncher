package com.tarslauncher.app.widgets

import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** The home surface contains only the selected widget, without launcher controls. */
@Composable
fun WidgetPanel(controller: LauncherWidgetController) {
    val ids by controller.widgetIds.collectAsState()
    val id = ids.firstOrNull() ?: return
    val info = controller.info(id) ?: return
    key(id) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            NativeWidget(controller, id, info, controller.height(id, info))
        }
    }
}

@Composable
fun WidgetSettingsDialog(controller: LauncherWidgetController, onDismiss: () -> Unit) {
    val ids by controller.widgetIds.collectAsState()
    val id = ids.firstOrNull()
    val info = id?.let(controller::info)
    var choosing by rememberSaveable { mutableStateOf(id == null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (choosing || info == null) "Choose widget" else "Widget") },
        text = {
            if (choosing || info == null) {
                val providers = remember(controller) { controller.providers() }
                if (providers.isEmpty()) {
                    Text("No widgets available. Install an app that provides widgets to get started.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(providers, key = { "${it.profile}:${it.provider.flattenToString()}" }) { provider ->
                            Column(Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                controller.add(provider)
                            }.padding(vertical = 14.dp, horizontal = 4.dp)) {
                                Text(provider.loadLabel(context.packageManager))
                                val appLabel = remember(provider.provider) {
                                    runCatching {
                                        val app = context.packageManager.getApplicationInfo(provider.provider.packageName, 0)
                                        context.packageManager.getApplicationLabel(app).toString()
                                    }.getOrDefault(provider.provider.packageName)
                                }
                                Text(appLabel, color = Color.Gray)
                            }
                        }
                    }
                }
            } else if (id != null) {
                var height by remember(id) { mutableIntStateOf(controller.height(id, info)) }
                Column {
                    Text(info.loadLabel(context.packageManager))
                    if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Height: ${height}dp", Modifier.weight(1f))
                            TextButton(onClick = {
                                controller.resize(id, height - 32)
                                height = controller.height(id, info)
                            }) { Text("−") }
                            TextButton(onClick = {
                                controller.resize(id, height + 32)
                                height = controller.height(id, info)
                            }) { Text("+") }
                        }
                    }
                    TextButton(onClick = { choosing = true }) { Text("Change widget") }
                    TextButton(onClick = {
                        controller.remove(id)
                        onDismiss()
                    }) { Text("Remove widget") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE") } }
    )
}

@Suppress("DEPRECATION")
@Composable
private fun NativeWidget(controller: LauncherWidgetController, id: Int, info: AppWidgetProviderInfo, height: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth.value.toInt().coerceAtLeast(1)
        AndroidView(
            factory = { context -> controller.host.createView(context, id, info) },
            modifier = Modifier.fillMaxWidth().height(height.dp),
            update = { view ->
                // The host subtracts its default padding before notifying the provider.
                if (Build.VERSION.SDK_INT >= 31) {
                    view.updateAppWidgetSize(Bundle(), listOf(SizeF(width.toFloat(), height.toFloat())))
                } else {
                    view.updateAppWidgetSize(Bundle(), width, height, width, height)
                }
                view.contentDescription = info.loadLabel(view.context.packageManager)
            }
        )
    }
}
