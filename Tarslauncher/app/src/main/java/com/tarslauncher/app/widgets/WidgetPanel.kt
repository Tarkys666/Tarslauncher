package com.tarslauncher.app.widgets

import android.appwidget.AppWidgetProviderInfo
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
fun WidgetPanel(controller: LauncherWidgetController) {
    val ids by controller.widgetIds.collectAsState()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (ids.isNotEmpty()) {
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "DONE" else "EDIT", color = Color.White)
                }
            }
            TextButton(onClick = { showPicker = true }) {
                Text("+ ADD WIDGET", color = Color.White)
            }
        }
        if (ids.isEmpty()) {
            Box(Modifier.fillMaxSize().clickable { showPicker = true }, contentAlignment = Alignment.Center) {
                Text("Add your first widget", color = Color.White.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ids, key = { it }) { id ->
                    val info = controller.info(id)
                    if (info != null) {
                        var height by remember(id) { mutableIntStateOf(controller.height(id, info)) }
                        Column {
                            if (editing) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(info.loadLabel(context.packageManager), modifier = Modifier.weight(1f), color = Color.White)
                                    if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) {
                                        TextButton(onClick = {
                                            controller.resize(id, height - 32)
                                            height = controller.height(id, info)
                                        }) { Text("−", color = Color.White) }
                                        TextButton(onClick = {
                                            controller.resize(id, height + 32)
                                            height = controller.height(id, info)
                                        }) { Text("+", color = Color.White) }
                                    }
                                    TextButton(onClick = { controller.remove(id) }) { Text("REMOVE", color = Color.White) }
                                }
                            }
                            NativeWidget(controller, id, info, height)
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        val providers = remember(controller) { controller.providers() }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add widget") },
            text = {
                if (providers.isEmpty()) {
                    Text("No widgets available. Install an app that provides widgets to get started.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(providers, key = { "${it.profile}:${it.provider.flattenToString()}" }) { provider ->
                            Column(Modifier.fillMaxWidth().clickable {
                                showPicker = false
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
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("CANCEL") } }
        )
    }
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
