package com.tarslauncher.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.data.GroqRemoteDataSource
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.prompt.AgentPromptProvider
import com.tarslauncher.app.data.model.WorkspaceItem
import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.sync.GoogleAuthManager
import com.tarslauncher.app.data.workspace.FirebaseWorkspaceRepository
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════════
// ULTRA-MINIMAL PITCH-BLACK MONOCHROME PALETTE (0.dp Corners, Pure #000000)
// ═══════════════════════════════════════════════════════════════════════════════
private val MonoBlack = Color(0xFF000000)
private val MonoWhite = Color(0xFFFFFFFF)
private val MonoMuted = Color(0xFF888888)
private val MonoDim = Color(0xFF555555)
private val MonoLine = Color(0xFF222222)
private val MonoBorder = Color(0xFF333333)
private val MonoDisabledBg = Color(0xFF141414)

/**
 * Ultra-minimal, full-monochrome, zero-corner (`RectangleShape`), pitch-black (`#000000`) Notes screen.
 * - Lists ONLY notes separated by `-` lines.
 * - Bottom input bar with `↑` arrow button summarizes the note via LLM and saves it to the Firebase server.
 */
@Composable
fun ManageNotesDialog(
    onDismiss: () -> Unit,
    onOpenAgentSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val authManager = remember { GoogleAuthManager(context) }
    val workspaceRepo = remember { FirebaseWorkspaceRepository(context, authManager) }
    val agentPrefs = remember { AgentPreferences(context) }
    val workspaceItems by workspaceRepo.workspaceItemsFlow.collectAsState(initial = emptyList())

    var noteInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        authManager.ensureSessionUid()
    }

    fun submitAndSummarizeNote() {
        val rawText = noteInput.trim()
        if (rawText.isBlank() || isSending) return

        noteInput = ""
        focusManager.clearFocus()
        isSending = true
        statusText = "ÖZETLENİYOR VE SUNUCUYA GÖNDERİLİYOR..."

        coroutineScope.launch {
            val apiKey = agentPrefs.getGroqApiKey().trim()
            val summarizedText = if (apiKey.isNotBlank()) {
                val remote = GroqRemoteDataSource(
                    apiKeyProvider = { apiKey },
                    preferredModelProvider = { GroqRemoteDataSource.DEFAULT_GROQ_MODEL }
                )
                val llmRes = remote.generateContent(
                    systemInstruction = AgentPromptProvider.NOTE_SUMMARIZER_SYSTEM_PROMPT,
                    messages = listOf(ChatMessage(ChatMessage.Role.USER, rawText))
                )
                llmRes.getOrNull()
                    ?.replace(Regex("""<think>.*?</think>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.takeIf { it.isNotBlank() }
                    ?: rawText
            } else {
                rawText
            }

            val newItem = WorkspaceItem(
                type = WorkspaceItemType.NOTE,
                title = summarizedText.take(WorkspaceItem.MAX_TITLE_LENGTH),
                content = ""
            )

            val saveRes = workspaceRepo.addItem(newItem)
            statusText = if (saveRes.isSuccess) {
                null
            } else {
                "SUNUCU HATASI: ${saveRes.exceptionOrNull()?.localizedMessage ?: "Kaydedilemedi"}"
            }
            isSending = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MonoBlack,
            shape = RectangleShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MonoBlack)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // ─── 1. ULTRA-MINIMAL HEADER ─────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "N O T L A R",
                        color = MonoWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 3.sp
                    )

                    Box(
                        modifier = Modifier
                            .border(BorderStroke(1.dp, MonoBorder), RectangleShape)
                            .clickable { onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = MonoWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = MonoLine)

                // ─── 2. NOTES LIST (SEPARATED BY '-' LINES) ──────────────────
                if (workspaceItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "- HENÜZ NOT YOK -",
                            color = MonoDim,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(
                            items = workspaceItems,
                            key = { _, item -> item.id }
                        ) { index, item ->
                            MonochromeNoteRow(
                                item = item,
                                onToggleComplete = {
                                    coroutineScope.launch {
                                        workspaceRepo.toggleItemCompleted(item.id, !item.isCompleted)
                                    }
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        workspaceRepo.deleteByQuery(item.id)
                                    }
                                }
                            )

                            if (index < workspaceItems.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    thickness = 1.dp,
                                    color = MonoLine
                                )
                            }
                        }
                    }
                }

                // ─── 3. STATUS LINE (IF SUMMARIZING OR ERROR) ────────────────
                if (!statusText.isNullOrBlank()) {
                    HorizontalDivider(thickness = 1.dp, color = MonoLine)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MonoBlack)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = statusText.orEmpty(),
                            color = MonoMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // ─── 4. BOTTOM INPUT BAR WITH ARROW ('↑') SEND BUTTON ────────
                HorizontalDivider(thickness = 1.dp, color = MonoBorder)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MonoBlack)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        textStyle = TextStyle(
                            color = MonoWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(MonoWhite),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { submitAndSummarizeNote() }
                        ),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (noteInput.isEmpty()) {
                                    Text(
                                        text = "Not yaz (özetlenip sunucuya gönderilir)...",
                                        color = MonoDim,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Light
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    val canSend = noteInput.isNotBlank() && !isSending
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(if (canSend) MonoWhite else MonoDisabledBg, RectangleShape)
                            .border(BorderStroke(1.dp, if (canSend) MonoWhite else MonoBorder), RectangleShape)
                            .clickable(enabled = canSend) { submitAndSummarizeNote() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSending) "…" else "↑",
                            color = if (canSend) MonoBlack else MonoDim,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonochromeNoteRow(
    item: WorkspaceItem,
    onToggleComplete: () -> Unit,
    onDelete: () -> Unit
) {
    val displayBody = remember(item.title, item.content) {
        val t = item.title.trim()
        val c = item.content.trim()
        if (c.isNotBlank() && !c.equals(t, ignoreCase = true)) {
            "$t — $c"
        } else {
            t
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleComplete() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "- ",
            color = if (item.isCompleted) MonoDim else MonoMuted,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp
        )

        Text(
            text = displayBody,
            color = if (item.isCompleted) MonoDim else MonoWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 20.sp,
            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "×",
            color = MonoDim,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .clickable { onDelete() }
                .padding(horizontal = 6.dp)
        )
    }
}

/**
 * Dedicated full-monochrome, zero-corner (`RectangleShape`), pitch-black (`#000000`)
 * Google Auth & Cloud Account screen accessible from Settings -> ACCOUNT & AUTH (GOOGLE).
 */
@Composable
fun ManageAuthDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { GoogleAuthManager(context) }
    val currentUser by authManager.authUserFlow.collectAsState(initial = authManager.currentUser)

    val isGoogleLinked = currentUser != null && currentUser?.isAnonymous == false
    val userDisplayLabel = currentUser?.email ?: currentUser?.displayName ?: "Google Hesabı"
    var authStatusMessage by remember { mutableStateOf<String?>(null) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        coroutineScope.launch {
            val authRes = authManager.handleGoogleSignInResult(result.data)
            if (authRes.isSuccess) {
                val user = authRes.getOrNull()
                authStatusMessage = "GOOGLE HESABI BAĞLANDI: ${user?.email ?: user?.displayName ?: "Aktif"}"
            } else {
                val err = authRes.exceptionOrNull()?.localizedMessage ?: "Bilinmeyen hata"
                authStatusMessage = if (result.resultCode == Activity.RESULT_CANCELED) {
                    "GİRİŞ İPTAL EDİLDİ."
                } else {
                    "GİRİŞ HATASI: $err"
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MonoBlack,
            shape = RectangleShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MonoBlack)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "G O O G L E   A U T H",
                        color = MonoWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 3.sp
                    )

                    Box(
                        modifier = Modifier
                            .border(BorderStroke(1.dp, MonoBorder), RectangleShape)
                            .clickable { onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            color = MonoWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(thickness = 1.dp, color = MonoLine)
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "DURUM",
                    color = MonoMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isGoogleLinked) "- BAĞLI: $userDisplayLabel" else "- ANONİM BULUT OTURUMU",
                    color = MonoWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(thickness = 1.dp, color = MonoLine)
                Spacer(modifier = Modifier.height(24.dp))

                if (isGoogleLinked) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, MonoWhite), RectangleShape)
                            .clickable {
                                coroutineScope.launch {
                                    authManager.signOut()
                                    authManager.ensureSessionUid()
                                    authStatusMessage = "OTURUM KAPATILDI."
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "GOOGLE OTURUMUNU KAPAT",
                            color = MonoWhite,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MonoWhite, RectangleShape)
                            .clickable {
                                authStatusMessage = null
                                val client = authManager.getGoogleSignInClient()
                                if (client != null) {
                                    googleSignInLauncher.launch(client.signInIntent)
                                } else {
                                    authStatusMessage = "WEB CLIENT ID YAPILANDIRILMAMIŞ."
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "GOOGLE İLE GİRİŞ YAP",
                            color = MonoBlack,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }
                }

                if (!authStatusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "- ${authStatusMessage.orEmpty()}",
                        color = MonoMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
