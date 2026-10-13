package com.tarslauncher.app.agent.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBottomSheet(
    viewModel: AgentViewModel,
    onDismissRequest: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showKeyEditor by remember { mutableStateOf(uiState.apiKey.isBlank()) }
    var tempApiKey by remember(uiState.apiKey) { mutableStateOf(uiState.apiKey) }

    val mainScrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Black,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(1.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(mainScrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header: TARS Monologue Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "TARS // SYSTEM AGENT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "AUTONOMOUS SUB-ROUTINE EXECUTION",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Minimalist Key Toggle Box
                Box(
                    modifier = Modifier
                        .border(
                            BorderStroke(
                                0.8.dp,
                                if (uiState.apiKey.isNotBlank()) Color.White.copy(alpha = 0.4f) else Color.White
                            ),
                            RoundedCornerShape(3.dp)
                        )
                        .clickable { showKeyEditor = !showKeyEditor }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (uiState.apiKey.isNotBlank()) "[KEY: SET]" else "[KEY: EMPTY]",
                        color = if (uiState.apiKey.isNotBlank()) Color.White.copy(alpha = 0.8f) else Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Service Status Monochromatic Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            0.8.dp,
                            if (uiState.isServiceActive) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.6f)
                        ),
                        RoundedCornerShape(4.dp)
                    )
                    .background(Color(0xFF080808))
                    .clickable {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (uiState.isServiceActive) "ACCESSIBILITY // ACTIVE" else "ACCESSIBILITY // OFFLINE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (uiState.isServiceActive) "[LINKED]" else "[TAP TO ENGAGE]",
                        color = if (uiState.isServiceActive) Color.White.copy(alpha = 0.5f) else Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (uiState.isServiceActive) FontWeight.Normal else FontWeight.Bold
                    )
                }
            }

            // Key Editor (Expandable Monochrome Card with 1-Tap Paste)
            AnimatedVisibility(visible = showKeyEditor) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
                    border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "GEMINI FLASH API KEY",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = tempApiKey,
                                onValueChange = { tempApiKey = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        "AIzaSy...",
                                        color = Color.White.copy(alpha = 0.3f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                shape = RoundedCornerShape(3.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                    focusedContainerColor = Color.Black,
                                    unfocusedContainerColor = Color.Black
                                )
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            // 1-Tap PASTE Button for API Key
                            Box(
                                modifier = Modifier
                                    .height(52.dp)
                                    .border(BorderStroke(0.8.dp, Color.White), RoundedCornerShape(3.dp))
                                    .clickable {
                                        val clipText = clipboardManager.getText()?.text
                                        if (!clipText.isNullOrBlank()) {
                                            tempApiKey = clipText.trim()
                                            Toast.makeText(context, "API Key Panodan Yapıştırıldı", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Pano boş!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "PASTE",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (tempApiKey.isNotBlank()) {
                                Text(
                                    text = "[CLEAR]",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.clickable { tempApiKey = "" }
                                )
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .background(Color.White, RoundedCornerShape(3.dp))
                                    .clickable {
                                        viewModel.onSaveApiKey(tempApiKey)
                                        showKeyEditor = false
                                        Toast.makeText(context, "API Key Kaydedildi", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "SAVE KEY",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Directives
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TarsDirectiveChip("[SETTINGS]") { viewModel.onTaskInputChanged("Ayarlar uygulamasını aç") }
                TarsDirectiveChip("[SET ALARM]") { viewModel.onTaskInputChanged("Saat uygulamasında 07:30 alarmı kur") }
                TarsDirectiveChip("[YOUTUBE]") { viewModel.onTaskInputChanged("YouTube'u aç") }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Command Input + Execute / Abort Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.taskInput,
                    onValueChange = { viewModel.onTaskInputChanged(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "ENTER DIRECTIVE...",
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    maxLines = 2,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                        focusedContainerColor = Color.Black,
                        unfocusedContainerColor = Color.Black
                    )
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Paste button for prompt if empty
                if (uiState.taskInput.isBlank()) {
                    Box(
                        modifier = Modifier
                            .height(52.dp)
                            .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.4f)), RoundedCornerShape(4.dp))
                            .clickable {
                                val clipText = clipboardManager.getText()?.text
                                if (!clipText.isNullOrBlank()) {
                                    viewModel.onTaskInputChanged(clipText.trim())
                                }
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PASTE",
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Box(
                    modifier = Modifier
                        .height(52.dp)
                        .background(
                            if (uiState.isRunning) Color.Transparent else Color.White,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                if (uiState.isRunning) Color(0xFFFF5252) else Color.White
                            ),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (uiState.isRunning) viewModel.stopTask() else viewModel.startTask()
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.isRunning) "ABORT" else "EXEC",
                        color = if (uiState.isRunning) Color(0xFFFF5252) else Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Terminal Output Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LOG // STREAM_OUTPUT",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (uiState.isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "PROCESSING",
                            color = Color.White,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Monochrome Terminal Window
            val listState = rememberLazyListState()
            LaunchedEffect(uiState.logs.size) {
                if (uiState.logs.isNotEmpty()) {
                    listState.animateScrollToItem(uiState.logs.size - 1)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(Color.Black, RoundedCornerShape(4.dp))
                    .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(4.dp))
                    .padding(10.dp)
            ) {
                if (uiState.logs.isEmpty()) {
                    Text(
                        text = "AWAITING USER INSTRUCTION...",
                        color = Color.White.copy(alpha = 0.25f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(uiState.logs) { log ->
                            Text(
                                text = "> $log",
                                color = when {
                                    log.contains("HATA", ignoreCase = true) || log.contains("BAŞARISIZ", ignoreCase = true) -> Color(0xFFFF5252)
                                    log.contains("BAŞARILI", ignoreCase = true) || log.contains("TAMAMLANDI", ignoreCase = true) -> Color.White
                                    else -> Color.White.copy(alpha = 0.75f)
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(vertical = 1.5.dp)
                            )
                        }
                    }
                }
            }
        }

        // Monochromatic Security Clearance Dialog
        uiState.pendingConfirmation?.let { confirmation ->
            BasicAlertDialog(
                onDismissRequest = { viewModel.onConfirmDecision(false) }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "SECURITY // CLEARANCE REQUIRED",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = confirmation.prompt,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.4f)), RoundedCornerShape(3.dp))
                                    .clickable { viewModel.onConfirmDecision(false) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "DENY",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color.White, RoundedCornerShape(3.dp))
                                    .clickable { viewModel.onConfirmDecision(true) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "CONFIRM",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TarsDirectiveChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
    }
}
