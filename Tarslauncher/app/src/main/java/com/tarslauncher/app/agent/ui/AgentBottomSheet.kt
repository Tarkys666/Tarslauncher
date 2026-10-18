package com.tarslauncher.app.agent.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ═══════════════════════════════════════════════════════════════════════════════
// TARS LAUNCHER ULTRA-MINIMAL MONOCHROME PALETTE (0.dp Corners, Pure #000000)
// ═══════════════════════════════════════════════════════════════════════════════
private val TarsBlack = Color(0xFF000000)
private val TarsWhite = Color(0xFFFFFFFF)
private val TarsMuted = Color(0xFF888888)
private val TarsDim = Color(0xFF555555)
private val TarsLine = Color(0xFF1A1A1A)

/**
 * Ultra-minimal, full-monochrome, zero-corner (`RectangleShape`), pitch-black (`#000000`)
 * TARS Agent Console matching TARS Launcher's design language (`HomeScreen`, `SettingsScreen`, `NotesDialog`).
 */
@Composable
fun AgentBottomSheet(
    viewModel: AgentViewModel,
    onDismissRequest: () -> Unit,
    onOpenWorkspace: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    var showKeyEditor by remember { mutableStateOf(uiState.groqApiKey.isBlank()) }
    var tempApiKey by remember(uiState.groqApiKey) { mutableStateOf(uiState.groqApiKey) }

    val logListState = rememberLazyListState()
    LaunchedEffect(uiState.logs.size) {
        if (uiState.logs.isNotEmpty()) {
            logListState.animateScrollToItem(uiState.logs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = TarsBlack,
            shape = RectangleShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TarsBlack)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // ─── 1. HEADER ───────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "T A R S   A G E N T",
                        color = TarsWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = 3.sp
                    )

                    Text(
                        text = "×",
                        color = TarsMuted,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .clickable { onDismissRequest() }
                            .padding(horizontal = 6.dp)
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = TarsLine)

                // ─── 2. MINIMAL SETTINGS ROWS (ZERO NESTED BOXES) ────────────
                // Row A: Dynamic Island Accessibility Service
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "- Dynamic Island",
                        color = TarsWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Light
                    )

                    Text(
                        text = if (uiState.isServiceActive) "AKTİF" else "AÇ",
                        color = if (uiState.isServiceActive) TarsMuted else TarsWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = TarsLine)

                // Row B: Agent Master Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onToggleAgentEnabled(!uiState.isAgentEnabled) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "- Ajan Durumu",
                        color = TarsWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Light
                    )

                    Text(
                        text = if (uiState.isAgentEnabled) "AÇIK" else "KAPALI",
                        color = if (uiState.isAgentEnabled) TarsWhite else TarsDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = TarsLine)

                // Row C: Groq API Key
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showKeyEditor = !showKeyEditor },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "- API Anahtarı",
                            color = TarsWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Light
                        )

                        Text(
                            text = if (uiState.groqApiKey.isNotBlank() && !showKeyEditor) {
                                "KAYITLI"
                            } else if (showKeyEditor) {
                                "GİZLE"
                            } else {
                                "GİR"
                            },
                            color = if (uiState.groqApiKey.isNotBlank()) TarsMuted else TarsWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp
                        )
                    }

                    AnimatedVisibility(visible = showKeyEditor) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "- ",
                                    color = TarsDim,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Light
                                )

                                Box(modifier = Modifier.weight(1f)) {
                                    if (tempApiKey.isEmpty()) {
                                        Text(
                                            text = "gsk_...",
                                            color = TarsDim,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Light
                                        )
                                    }
                                    BasicTextField(
                                        value = tempApiKey,
                                        onValueChange = { tempApiKey = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = TarsWhite,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Light
                                        ),
                                        cursorBrush = SolidColor(TarsWhite),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Text(
                                    text = "YAPIŞTIR",
                                    color = TarsMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Light,
                                    letterSpacing = 1.5.sp,
                                    modifier = Modifier
                                        .clickable {
                                            val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                            if (clip.isNotBlank()) {
                                                tempApiKey = clip
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Text(
                                    text = "KAYDET",
                                    color = TarsWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    letterSpacing = 1.5.sp,
                                    modifier = Modifier
                                        .clickable {
                                            viewModel.onSaveApiKey(tempApiKey)
                                            showKeyEditor = false
                                            focusManager.clearFocus()
                                            Toast.makeText(context, "Kaydedildi", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = TarsLine)

                // Row D: Second Brain (Notes Vault)
                if (onOpenWorkspace != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenWorkspace() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "- Notlarım (${uiState.workspaceCount})",
                            color = TarsWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Light
                        )

                        Text(
                            text = "AÇ",
                            color = TarsMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = TarsLine)
                }

                // ─── 3. MINIMAL CONVERSATION / OUTPUT STREAM ─────────────────
                if (uiState.logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "- HAZIR -",
                            color = TarsDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraLight,
                            letterSpacing = 3.sp
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (!uiState.isRunning) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "TEMİZLE",
                                    color = TarsDim,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Light,
                                    letterSpacing = 2.sp,
                                    modifier = Modifier
                                        .clickable { viewModel.clearLogs() }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }

                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            itemsIndexed(uiState.logs) { index, line ->
                                val isLast = index == uiState.logs.lastIndex
                                Text(
                                    text = "- $line",
                                    color = if (isLast) TarsWhite else TarsMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Light,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // ─── 4. INLINE CONFIRMATION ACTIONS (WHEN QUESTION PENDING) ──
                val pendingQuestion = uiState.pendingQuestion
                if (pendingQuestion != null) {
                    HorizontalDivider(thickness = 1.dp, color = TarsLine)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ONAYLA",
                            color = TarsWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 2.sp,
                            modifier = Modifier
                                .clickable { viewModel.onAnswerQuestion("Evet, onayla") }
                                .padding(vertical = 4.dp)
                        )

                        Text(
                            text = "İPTAL",
                            color = TarsMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp,
                            modifier = Modifier
                                .clickable { viewModel.onAnswerQuestion(null) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = TarsLine)

                // ─── 5. BOTTOM INPUT BAR ─────────────────────────────────────
                val canSubmit = uiState.taskInput.trim().isNotBlank() && (!uiState.isRunning || pendingQuestion != null)

                val sendAction = {
                    val text = uiState.taskInput.trim()
                    if (text.isNotBlank()) {
                        if (pendingQuestion != null) {
                            viewModel.onTaskInputChanged("")
                            viewModel.onAnswerQuestion(text)
                        } else if (!uiState.isRunning) {
                            viewModel.startTask(text)
                        }
                        focusManager.clearFocus()
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "- ",
                        color = TarsMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        if (uiState.taskInput.isEmpty()) {
                            Text(
                                text = if (pendingQuestion != null) "Yanıt yaz..." else "Not veya soru yaz...",
                                color = TarsDim,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Light
                            )
                        }
                        BasicTextField(
                            value = uiState.taskInput,
                            onValueChange = viewModel::onTaskInputChanged,
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TarsWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Light
                            ),
                            cursorBrush = SolidColor(TarsWhite),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { if (canSubmit) sendAction() }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    if (uiState.isRunning && pendingQuestion == null) {
                        Text(
                            text = "DURDUR",
                            color = TarsMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier
                                .clickable { viewModel.stopTask() }
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    } else {
                        Text(
                            text = "↑",
                            color = if (canSubmit) TarsWhite else TarsDim,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .clickable(enabled = canSubmit) { sendAction() }
                                .padding(horizontal = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
