package com.tarslauncher.app.agent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showKeyEditor by remember { mutableStateOf(uiState.apiKey.isBlank()) }
    var tempApiKey by remember(uiState.apiKey) { mutableStateOf(uiState.apiKey) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color(0xFF141419),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = "TARS Agent",
                        tint = Color(0xFF7C4DFF),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "TARS Asistan",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(onClick = { showKeyEditor = !showKeyEditor }) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "API Anahtarı",
                        tint = if (uiState.apiKey.isNotBlank()) Color(0xFF81C784) else Color(0xFFFFB74D)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Service status banner
            ServiceStatusChip(
                isActive = uiState.isServiceActive,
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )

            // API key editor section
            AnimatedVisibility(visible = showKeyEditor) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Google AI Studio API Anahtarı (Gemini 2.0 Flash - Ücretsiz)",
                            fontSize = 12.sp,
                            color = Color(0xFFB0B0C0)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = tempApiKey,
                            onValueChange = { tempApiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("AIzaSy...", color = Color.Gray, fontSize = 13.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF7C4DFF),
                                unfocusedBorderColor = Color(0xFF333344),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    viewModel.onSaveApiKey(tempApiKey)
                                    showKeyEditor = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Kaydet", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick suggestion chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickChip("Ayarları Aç") { viewModel.onTaskInputChanged("Ayarlar uygulamasını aç") }
                QuickChip("Alarm Kur") { viewModel.onTaskInputChanged("Saat uygulamasında 07:30 alarmı kur") }
                QuickChip("YouTube Aç") { viewModel.onTaskInputChanged("YouTube'u aç") }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Task input and action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.taskInput,
                    onValueChange = { viewModel.onTaskInputChanged(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Telefonda ne yapmamı istersiniz?", color = Color.Gray, fontSize = 13.sp) },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7C4DFF),
                        unfocusedBorderColor = Color(0xFF333344),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (uiState.isRunning) {
                    IconButton(
                        onClick = { viewModel.stopTask() },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0xFFE53935), RoundedCornerShape(12.dp))
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = "Durdur", tint = Color.White)
                    }
                } else {
                    IconButton(
                        onClick = { viewModel.startTask() },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0xFF7C4DFF), RoundedCornerShape(12.dp))
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Başlat", tint = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live execution terminal log
            Text(
                text = "İşlem Günlüğü",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9E9EAF)
            )
            Spacer(modifier = Modifier.height(4.dp))

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
                    .background(Color(0xFF0C0C10), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                if (uiState.logs.isEmpty()) {
                    Text(
                        text = "Bir komut verin ve Başlat'a dokunun...",
                        color = Color(0xFF555566),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(uiState.logs) { log ->
                            Text(
                                text = "› $log",
                                color = if (log.contains("Hata", ignoreCase = true) || log.contains("BAŞARISIZ")) {
                                    Color(0xFFFF8A80)
                                } else if (log.contains("Başarılı", ignoreCase = true) || log.contains("TAMAMLANDI")) {
                                    Color(0xFFB9F6CA)
                                } else {
                                    Color(0xFFD1D1DF)
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                if (uiState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.TopEnd),
                        strokeWidth = 2.dp,
                        color = Color(0xFF7C4DFF)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Sensitive action confirmation dialog
        uiState.pendingConfirmation?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { viewModel.onConfirmDecision(false) },
                title = { Text("Kullanıcı Onayı Gerekli", color = Color.White) },
                text = { Text(confirmation.prompt, color = Color(0xFFD0D0E0)) },
                confirmButton = {
                    Button(
                        onClick = { viewModel.onConfirmDecision(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) {
                        Text("Onayla")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onConfirmDecision(false) }) {
                        Text("Reddet", color = Color.LightGray)
                    }
                },
                containerColor = Color(0xFF1E1E28)
            )
        }
    }
}

@Composable
private fun ServiceStatusChip(
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) Color(0xFF1B2E1E) else Color(0xFF332015))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isActive) Color(0xFF81C784) else Color(0xFFFFB74D),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isActive) "Erişilebilirlik Servisi Aktif" else "Erişilebilirlik Kapalı — Açmak için dokunun",
            color = if (isActive) Color(0xFFC8E6C9) else Color(0xFFFFE0B2),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun QuickChip(
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB0B0C5)),
        modifier = Modifier.height(30.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(text = label, fontSize = 11.sp)
    }
}
