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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBottomSheet(
    viewModel: AgentViewModel,
    onDismissRequest: () -> Unit,
    onOpenWorkspace: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showKeyEditor by remember { mutableStateOf(uiState.groqApiKey.isBlank()) }
    var showApiKeyText by remember { mutableStateOf(false) }
    var tempApiKey by remember(uiState.groqApiKey) { mutableStateOf(uiState.groqApiKey) }
    var tempWebClientId by remember(uiState.googleWebClientId) { mutableStateOf(uiState.googleWebClientId) }

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
            // Header: TARS Workspace Agent Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "TARS // WORKSPACE AGENT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "NOTION-STYLE CLOUD WORKSPACE & LLM",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Minimalist Engine & Key Toggle Row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .border(
                                BorderStroke(
                                    0.8.dp,
                                    if (uiState.activeApiKey.isNotBlank()) Color.White.copy(alpha = 0.4f) else Color.White
                                ),
                                RoundedCornerShape(3.dp)
                            )
                            .clickable { showKeyEditor = !showKeyEditor }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (uiState.activeApiKey.isNotBlank()) "[KEY: SET]" else "[KEY: EMPTY]",
                            color = if (uiState.activeApiKey.isNotBlank()) Color.White.copy(alpha = 0.8f) else Color.White,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .border(
                                BorderStroke(
                                    0.8.dp,
                                    if (uiState.isAgentEnabled) Color.White.copy(alpha = 0.4f) else Color(0xFFFF5252)
                                ),
                                RoundedCornerShape(3.dp)
                            )
                            .clickable { viewModel.onToggleAgentEnabled(!uiState.isAgentEnabled) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (uiState.isAgentEnabled) "[AGENT: AÇIK]" else "[KİLİT: KAPALI]",
                            color = if (uiState.isAgentEnabled) Color.White.copy(alpha = 0.8f) else Color(0xFFFF5252),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            if (!uiState.isAgentEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x26FF5252), RoundedCornerShape(4.dp))
                        .border(BorderStroke(0.8.dp, Color(0xFFFF5252)), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "⚠ GÜVENLİK KİLİDİ AKTİF: TARS Ajanı ve Dynamic Island devre dışı.",
                        color = Color(0xFFFF8A80),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dynamic Island Overlay Service Status Banner
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
                        text = if (uiState.isServiceActive) "DYNAMIC ISLAND // ACTIVE" else "DYNAMIC ISLAND // OFFLINE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (uiState.isServiceActive) "[LINKED]" else "[TAP TO ENABLE]",
                        color = if (uiState.isServiceActive) Color(0xFF69F0AE) else Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (uiState.isServiceActive) FontWeight.Normal else FontWeight.Bold
                    )
                }
            }

            // Key & Cloud Engine Editor
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ENGINE // QWEN 3.8 (27B)",
                                fontSize = 10.sp,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "[GROQ LPU]",
                                fontSize = 9.sp,
                                color = Color(0xFF69F0AE),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "GROQ API KEY (gsk_...) • AES-256 KEYSTORE",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (showApiKeyText) "[GİZLE]" else "[GÖSTER]",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { showApiKeyText = !showApiKeyText }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = tempApiKey,
                                onValueChange = { tempApiKey = it.take(256) },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        "gsk_...",
                                        color = Color.White.copy(alpha = 0.3f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                },
                                singleLine = true,
                                visualTransformation = if (showApiKeyText) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
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

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "console.groq.com/keys adresinden ücretsiz alınabilir",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Firebase Firestore + Google Auth Status Badge
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(3.dp))
                                .border(BorderStroke(0.6.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(3.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "FIREBASE WORKSPACE",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (uiState.isGoogleSignedIn) {
                                    "[GOOGLE: ${uiState.userEmail?.take(20) ?: "BAĞLI"}]"
                                } else {
                                    "[ANONİM BULUT OTURUMU]"
                                },
                                fontSize = 9.sp,
                                color = if (uiState.isGoogleSignedIn) Color(0xFF69F0AE) else Color(0xFFFFD54F),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "GOOGLE WEB CLIENT ID (OPSİYONEL - GOOGLE AUTH İÇİN)",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = tempWebClientId,
                            onValueChange = { tempWebClientId = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "691832963274-xxxx.apps.googleusercontent.com",
                                    color = Color.White.copy(alpha = 0.3f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            shape = RoundedCornerShape(3.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                focusedContainerColor = Color.Black,
                                unfocusedContainerColor = Color.Black
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (tempApiKey.isNotBlank()) {
                                Text(
                                    text = "[CLEAR KEY]",
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
                                        viewModel.onSaveGoogleWebClientId(tempWebClientId)
                                        showKeyEditor = false
                                        Toast.makeText(context, "Yapılandırma Kaydedildi", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "SAVE CONFIG",
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

            // Quick Directives for Workspace
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TarsDirectiveChip("[+ GÖREV]") {
                    viewModel.onTaskInputChanged("Yarın saat 15:00'te proje toplantısı var, sunumu hazırla ve raporu gönder")
                }
                TarsDirectiveChip("[+ FİKİR]") {
                    viewModel.onTaskInputChanged("Fikir: Uygulama içine haftalık üretkenlik özeti widget'ı eklenebilir")
                }
                TarsDirectiveChip("[ÖZETLE]") {
                    viewModel.onTaskInputChanged("Bekleyen görevlerimi ve notlarımı özetle")
                }
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
                            "Görev, not veya fikir yazın...",
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

            // Mini-Plan Checklist UI
            uiState.currentPlan?.let { plan ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(4.dp))
                        .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)), RoundedCornerShape(4.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PLAN // WORKSPACE PIPELINE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${plan.steps.count { it.isCompleted }}/${plan.steps.size} COMPLETED",
                            fontSize = 9.sp,
                            color = if (plan.steps.all { it.isCompleted }) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    plan.steps.forEach { step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    step.isCompleted -> "[✓]"
                                    step.isInProgress -> "[►]"
                                    else -> "[ ]"
                                },
                                color = when {
                                    step.isCompleted -> Color(0xFF69F0AE)
                                    step.isInProgress -> Color.White
                                    else -> Color.White.copy(alpha = 0.35f)
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (step.isInProgress) FontWeight.Bold else FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${step.index}. ${step.title}",
                                color = when {
                                    step.isCompleted -> Color.White.copy(alpha = 0.5f)
                                    step.isInProgress -> Color.White
                                    else -> Color.White.copy(alpha = 0.4f)
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                textDecoration = if (step.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                            )
                        }
                    }
                }
            }

            // Terminal Output Header with 1-Tap Copy & Open Workspace Button
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (uiState.isRunning) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.4f)), RoundedCornerShape(3.dp))
                            .clickable {
                                val logText = uiState.logs.joinToString("\n")
                                if (logText.isNotBlank()) {
                                    clipboardManager.setText(AnnotatedString(logText))
                                    Toast.makeText(context, "Loglar Panoya Kopyalandı", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Kopyalanacak log yok", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "[COPY LOGS]",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (onOpenWorkspace != null) {
                        Box(
                            modifier = Modifier
                                .border(BorderStroke(0.8.dp, Color(0xFF69F0AE).copy(alpha = 0.6f)), RoundedCornerShape(3.dp))
                                .clickable { onOpenWorkspace() }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "[WORKSPACE: ${uiState.workspaceCount}]",
                                color = Color(0xFF69F0AE),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
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
                        text = "AWAITING WORKSPACE DIRECTIVE...",
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
                                    log.contains("Hata", ignoreCase = true) || log.contains("BAŞARISIZ", ignoreCase = true) -> Color(0xFFFF5252)
                                    log.contains("Tamamlandı", ignoreCase = true) || log.contains("kaydedildi", ignoreCase = true) -> Color(0xFF69F0AE)
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

        // Interactive Human-in-the-Loop Question Dialog (ask_user)
        uiState.pendingQuestion?.let { questionEvent ->
            var replyText by remember(questionEvent) { mutableStateOf("") }
            BasicAlertDialog(
                onDismissRequest = { viewModel.onAnswerQuestion(null) }
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
                            text = "AGENT // USER INPUT REQUIRED",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = questionEvent.question,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = replyText,
                            onValueChange = { replyText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "Yanıtınızı yazın...",
                                    color = Color.White.copy(alpha = 0.35f),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                                focusedContainerColor = Color.Black,
                                unfocusedContainerColor = Color.Black
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.4f)), RoundedCornerShape(3.dp))
                                    .clickable { viewModel.onAnswerQuestion(null) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "CANCEL",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color.White, RoundedCornerShape(3.dp))
                                    .clickable {
                                        if (replyText.isNotBlank()) {
                                            viewModel.onAnswerQuestion(replyText)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "REPLY",
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
