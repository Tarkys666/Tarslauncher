package com.tarslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.data.GroqAgentBrain
import com.tarslauncher.app.agent.data.GroqRemoteDataSource
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.runner.AgentLoopRunner
import com.tarslauncher.app.data.model.ChecklistItem
import com.tarslauncher.app.data.model.WorkspaceItem
import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.model.WorkspacePriority
import com.tarslauncher.app.data.sync.GoogleAuthManager
import com.tarslauncher.app.data.workspace.FirebaseWorkspaceRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// 21st.dev Charcoal-Ink & OLED Minimalist Palette
private val SurfaceBg = Color(0xFF09090B)
private val CardBg = Color(0xFF111115)
private val ElevatedBg = Color(0xFF18181E)
private val SubtleBorder = Color(0xFF24242D)
private val ActiveBorder = Color(0xFF3F3F4E)
private val TextPrimary = Color(0xFFFAFAFA)
private val TextSecondary = Color(0xFFA1A1AA)
private val TextMuted = Color(0xFF63636E)
private val AccentEmerald = Color(0xFF10B981)
private val AccentSky = Color(0xFF38BDF8)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentRose = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
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

    val items by workspaceRepo.workspaceItemsFlow.collectAsState(initial = emptyList())
    val currentUser by authManager.authUserFlow.collectAsState(initial = authManager.currentUser)

    var selectedFilter by remember { mutableStateOf<WorkspaceItemType?>(null) }

    // 21st.dev Unified Composer state (Metal Prompt Bar + Manual Composer)
    var useAiComposer by remember { mutableStateOf(agentPrefs.getGroqApiKey().isNotBlank()) }
    var composerInput by remember { mutableStateOf("") }
    var composerDetails by remember { mutableStateOf("") }
    var showDetailFields by remember { mutableStateOf(false) }
    var manualType by remember { mutableStateOf(WorkspaceItemType.TASK) }
    var manualPriority by remember { mutableStateOf(WorkspacePriority.MEDIUM) }
    var isInputFocused by remember { mutableStateOf(false) }

    var isAiProcessing by remember { mutableStateOf(false) }
    var aiStatusBanner by remember { mutableStateOf<String?>(null) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        coroutineScope.launch {
            val authRes = authManager.handleGoogleSignInResult(result.data)
            if (authRes.isSuccess) {
                val email = authRes.getOrNull()?.email ?: "Google Hesabı"
                Toast.makeText(context, "Giriş başarılı: $email", Toast.LENGTH_SHORT).show()
            } else {
                val err = authRes.exceptionOrNull()?.message ?: "Google girişi başarısız"
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    val filteredItems = remember(items, selectedFilter) {
        if (selectedFilter == null) items else items.filter { it.type == selectedFilter }
    }

    val totalTasks = remember(items) { items.count { it.type == WorkspaceItemType.TASK } }
    val completedTasks = remember(items) { items.count { it.type == WorkspaceItemType.TASK && it.isCompleted } }
    val taskProgress = remember(totalTasks, completedTasks) {
        if (totalTasks > 0) completedTasks.toFloat() / totalTasks.toFloat() else 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = taskProgress,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "taskProgress"
    )

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("tr-TR")) }

    fun submitComposer() {
        val text = composerInput.trim()
        if (text.isBlank() || isAiProcessing) return
        focusManager.clearFocus()

        if (useAiComposer) {
            val apiKey = agentPrefs.getGroqApiKey().trim()
            if (apiKey.isBlank()) {
                Toast.makeText(
                    context,
                    "AI modu için Groq API anahtarı gerekli. Manuel modla kaydediliyor veya Agent ayarlarından anahtar ekleyin.",
                    Toast.LENGTH_LONG
                ).show()
                onOpenAgentSettings?.invoke()
                return
            }

            isAiProcessing = true
            aiStatusBanner = "TARS AI analiz ediyor…"
            val promptToRun = if (composerDetails.isNotBlank()) {
                "$text\nDetaylar: ${composerDetails.trim()}"
            } else {
                text
            }

            coroutineScope.launch {
                val remote = GroqRemoteDataSource(
                    apiKeyProvider = { apiKey },
                    preferredModelProvider = { GroqRemoteDataSource.DEFAULT_GROQ_MODEL }
                )
                val brain = GroqAgentBrain(remote, GroqRemoteDataSource.DEFAULT_GROQ_MODEL)
                val runner = AgentLoopRunner(context.applicationContext, brain, workspaceRepo)

                runner.runAgent(promptToRun).collect { event ->
                    when (event) {
                        is AgentEvent.StatusLog -> {
                            aiStatusBanner = event.message.take(72)
                        }
                        is AgentEvent.Success -> {
                            aiStatusBanner = event.summary
                            composerInput = ""
                            composerDetails = ""
                            showDetailFields = false
                            isAiProcessing = false
                        }
                        is AgentEvent.Failure -> {
                            aiStatusBanner = "⚠ ${event.errorReason}"
                            isAiProcessing = false
                        }
                        is AgentEvent.AwaitingConfirmation -> {
                            event.userDecision.complete(false)
                            aiStatusBanner = "⚠ Toplu silme işlemi yalnızca TARS Agent panelinden onaylanabilir."
                            isAiProcessing = false
                        }
                        is AgentEvent.QuestionToUser -> {
                            event.userAnswer.complete(null)
                            aiStatusBanner = "ℹ ${event.question}"
                            isAiProcessing = false
                        }
                        else -> Unit
                    }
                }
                isAiProcessing = false
            }
        } else {
            // Direct Manual Creation
            val rawDetails = composerDetails.trim()
            val checklist = if (manualType == WorkspaceItemType.TASK && rawDetails.isNotBlank()) {
                rawDetails.split(",", "\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { ChecklistItem(text = it) }
            } else {
                emptyList()
            }

            val contentText = if (manualType == WorkspaceItemType.TASK && checklist.isNotEmpty()) {
                ""
            } else {
                rawDetails
            }

            val newItem = WorkspaceItem(
                type = manualType,
                title = text,
                content = contentText,
                checklist = checklist,
                priority = if (manualType == WorkspaceItemType.TASK) manualPriority else WorkspacePriority.MEDIUM
            )

            coroutineScope.launch {
                val res = workspaceRepo.addItem(newItem)
                if (res.isSuccess) {
                    composerInput = ""
                    composerDetails = ""
                    showDetailFields = false
                    aiStatusBanner = "✓ ${manualType.labelTr} kaydedildi"
                } else {
                    Toast.makeText(context, "Kayıt hatası", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SurfaceBg,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, SubtleBorder),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // 1. 21st.dev Minimalist Header + Cloud Sync Pill + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // 21st.dev Checklist Icon Badge
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(ElevatedBg, RoundedCornerShape(10.dp))
                                .border(BorderStroke(0.8.dp, ActiveBorder), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✦",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column {
                            Text(
                                text = "Notlarım & Görevler",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.2).sp
                            )
                            Text(
                                text = "${items.size} kayıt · Bulut Senkronizasyonu",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isGoogleLinked = currentUser != null && currentUser?.isAnonymous == false
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (isGoogleLinked) AccentEmerald.copy(alpha = 0.12f) else ElevatedBg
                                )
                                .border(
                                    BorderStroke(
                                        0.8.dp,
                                        if (isGoogleLinked) AccentEmerald.copy(alpha = 0.45f) else ActiveBorder
                                    ),
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable {
                                    if (isGoogleLinked) {
                                        coroutineScope.launch {
                                            authManager.signOut()
                                            Toast.makeText(context, "Google oturumu kapatıldı", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val client = authManager.getGoogleSignInClient()
                                        if (client != null) {
                                            googleSignInLauncher.launch(client.signInIntent)
                                        } else {
                                            onOpenAgentSettings?.invoke()
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (isGoogleLinked) AccentEmerald else AccentAmber,
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = if (isGoogleLinked) {
                                        currentUser?.email?.substringBefore("@")?.take(10) ?: "Bağlı"
                                    } else {
                                        "Google Bağla"
                                    },
                                    color = if (isGoogleLinked) AccentEmerald else TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Circular Close Button
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(ElevatedBg)
                                .border(BorderStroke(0.8.dp, SubtleBorder), CircleShape)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Kapat",
                                tint = TextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                // 2. 21st.dev Checkbox Todo List Progress Bar (shown when tasks exist)
                if (totalTasks > 0) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardBg, RoundedCornerShape(12.dp))
                            .border(BorderStroke(0.8.dp, SubtleBorder), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Görev İlerlemesi",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Box(
                                modifier = Modifier
                                    .background(ElevatedBg, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$completedTasks/$totalTasks Tamamlandı · %${(taskProgress * 100).toInt()}",
                                    color = if (completedTasks == totalTasks) AccentEmerald else TextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(7.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(ElevatedBg)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (completedTasks == totalTasks) AccentEmerald else TextPrimary
                                    )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. 21st.dev "Metal Prompt Bar" Unified AI & Manual Composer
                val composerBorderColor by animateColorAsState(
                    targetValue = when {
                        isAiProcessing -> AccentEmerald
                        isInputFocused && useAiComposer -> AccentEmerald.copy(alpha = 0.65f)
                        isInputFocused -> ActiveBorder
                        else -> SubtleBorder
                    },
                    animationSpec = tween(220),
                    label = "composerBorder"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, composerBorderColor), RoundedCornerShape(16.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp)
                ) {
                    BasicTextField(
                        value = composerInput,
                        onValueChange = { composerInput = it.take(WorkspaceItem.MAX_CONTENT_LENGTH) },
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        cursorBrush = SolidColor(if (useAiComposer) AccentEmerald else TextPrimary),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitComposer() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isInputFocused = it.isFocused },
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (composerInput.isEmpty()) {
                                    Text(
                                        text = if (useAiComposer) {
                                            "AI ile görev, alt liste, not veya fikir yazın..."
                                        } else {
                                            "Yeni ${manualType.labelTr.lowercase()} başlığı..."
                                        },
                                        color = TextMuted,
                                        fontSize = 13.5.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // Optional expandable secondary details / sub-checklist input
                    AnimatedVisibility(
                        visible = showDetailFields,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = SubtleBorder, thickness = 0.6.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            BasicTextField(
                                value = composerDetails,
                                onValueChange = { composerDetails = it.take(WorkspaceItem.MAX_CONTENT_LENGTH) },
                                textStyle = TextStyle(
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                ),
                                cursorBrush = SolidColor(TextPrimary),
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        if (composerDetails.isEmpty()) {
                                            Text(
                                                text = if (!useAiComposer && manualType == WorkspaceItemType.TASK) {
                                                    "Alt maddeleri virgülle ayırarak yazın (örn. Sunumu hazırla, Bütçeyi kontrol et)..."
                                                } else {
                                                    "Ek açıklama veya detay metni..."
                                                },
                                                color = TextMuted,
                                                fontSize = 12.sp
                                            )
                                        }
                                        inner()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bottom Chip Toolbar inside Composer (21st.dev Metal Prompt Bar pattern)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            // AI Mode Toggle Pill
                            ComposerChip(
                                label = "✦ AI",
                                active = useAiComposer,
                                activeColor = AccentEmerald,
                                onClick = { useAiComposer = !useAiComposer }
                            )

                            if (!useAiComposer) {
                                WorkspaceItemType.entries.forEach { t ->
                                    ComposerChip(
                                        label = t.labelTr,
                                        active = manualType == t,
                                        activeColor = when (t) {
                                            WorkspaceItemType.TASK -> AccentEmerald
                                            WorkspaceItemType.NOTE -> AccentSky
                                            WorkspaceItemType.IDEA -> AccentAmber
                                        },
                                        onClick = { manualType = t }
                                    )
                                }

                                if (manualType == WorkspaceItemType.TASK) {
                                    ComposerChip(
                                        label = if (manualPriority == WorkspacePriority.HIGH) "🔥 Acil" else "Normal",
                                        active = manualPriority == WorkspacePriority.HIGH,
                                        activeColor = AccentRose,
                                        onClick = {
                                            manualPriority = if (manualPriority == WorkspacePriority.HIGH) {
                                                WorkspacePriority.MEDIUM
                                            } else {
                                                WorkspacePriority.HIGH
                                            }
                                        }
                                    )
                                }
                            }

                            ComposerChip(
                                label = if (showDetailFields) "− Detay" else "+ Detay",
                                active = showDetailFields,
                                activeColor = TextSecondary,
                                onClick = { showDetailFields = !showDetailFields }
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Circular Send Action Button (21st.dev style)
                        val canSubmit = composerInput.isNotBlank() && !isAiProcessing
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isAiProcessing -> ElevatedBg
                                        canSubmit -> TextPrimary
                                        else -> ElevatedBg
                                    }
                                )
                                .border(
                                    BorderStroke(
                                        0.8.dp,
                                        if (canSubmit) TextPrimary else SubtleBorder
                                    ),
                                    CircleShape
                                )
                                .clickable(enabled = canSubmit) { submitComposer() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isAiProcessing) {
                                CircularProgressIndicator(
                                    color = AccentEmerald,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Text(
                                    text = "↑",
                                    color = if (canSubmit) Color.Black else TextMuted,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Live AI Status / Feedback Pill
                AnimatedVisibility(visible = !aiStatusBanner.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .background(ElevatedBg, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = aiStatusBanner.orEmpty(),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "×",
                            color = TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable { aiStatusBanner = null }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 4. 21st.dev Segmented Filter Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBg, RoundedCornerShape(11.dp))
                        .border(BorderStroke(0.8.dp, SubtleBorder), RoundedCornerShape(11.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    SegmentedFilterPill(
                        label = "Tümü",
                        count = items.size,
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentedFilterPill(
                        label = "Görev",
                        count = totalTasks,
                        selected = selectedFilter == WorkspaceItemType.TASK,
                        onClick = { selectedFilter = WorkspaceItemType.TASK },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentedFilterPill(
                        label = "Not",
                        count = items.count { it.type == WorkspaceItemType.NOTE },
                        selected = selectedFilter == WorkspaceItemType.NOTE,
                        onClick = { selectedFilter = WorkspaceItemType.NOTE },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentedFilterPill(
                        label = "Fikir",
                        count = items.count { it.type == WorkspaceItemType.IDEA },
                        selected = selectedFilter == WorkspaceItemType.IDEA,
                        onClick = { selectedFilter = WorkspaceItemType.IDEA },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 5. Workspace Items List (21st.dev Agent Todo List + Bento Cards)
                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(CardBg, CircleShape)
                                    .border(BorderStroke(0.8.dp, SubtleBorder), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "✦", color = TextMuted, fontSize = 16.sp)
                            }
                            Text(
                                text = "Çalışma alanı boş",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Yukarıdaki komut alanından veya Dynamic Island üzerinden doğal dille görev, not ve fikir ekleyebilirsiniz.",
                                color = TextMuted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 17.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            Workspace21stCard(
                                item = item,
                                formattedDate = dateFormat.format(Date(item.createdAt)),
                                onToggleComplete = { completed ->
                                    coroutineScope.launch {
                                        workspaceRepo.toggleItemCompleted(item.id, completed)
                                    }
                                },
                                onToggleSubItem = { subId, done ->
                                    coroutineScope.launch {
                                        workspaceRepo.toggleChecklistSubItem(item.id, subId, done)
                                    }
                                },
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clipBody = buildString {
                                        append(item.title)
                                        if (item.content.isNotBlank()) append("\n").append(item.content)
                                        if (item.checklist.isNotEmpty()) {
                                            append("\n")
                                            item.checklist.forEach { sub ->
                                                append(if (sub.isDone) "\n[x] " else "\n[ ] ").append(sub.text)
                                            }
                                        }
                                    }
                                    clipboard.setPrimaryClip(ClipData.newPlainText(item.title, clipBody))
                                    Toast.makeText(context, "Panoya kopyalandı", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        workspaceRepo.deleteByQuery(item.id)
                                    }
                                }
                            )
                        }
                    }
                }

                // 6. 21st.dev Minimalist Footer ("Clear completed" + Settings link)
                HorizontalDivider(color = SubtleBorder, thickness = 0.6.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (completedTasks > 0) {
                        Text(
                            text = "Tamamlananları temizle ($completedTasks)",
                            color = TextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        workspaceRepo.clearCompletedTasks()
                                        Toast.makeText(context, "Tamamlanan görevler temizlendi", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    } else {
                        Text(
                            text = if (onOpenAgentSettings != null) "Ajan Ayarları ↗" else "",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = if (onOpenAgentSettings != null) {
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenAgentSettings.invoke() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            } else Modifier
                        )
                    }

                    Text(
                        text = "Kapat",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ElevatedBg)
                            .border(BorderStroke(0.8.dp, SubtleBorder), RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val bgColor = if (active) activeColor.copy(alpha = 0.14f) else ElevatedBg
    val borderColor = if (active) activeColor.copy(alpha = 0.55f) else SubtleBorder
    val textColor = if (active) activeColor else TextSecondary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .border(BorderStroke(0.8.dp, borderColor), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SegmentedFilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ElevatedBg else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(BorderStroke(0.8.dp, ActiveBorder), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = count.toString(),
                color = if (selected) TextSecondary else TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * 21st.dev-inspired Workspace Card combining `Agent Todo List` (#28423) collapsible sub-checklists,
 * `Todo Tool` (#12368) circular status marks, and clean micro-contrast dark framing.
 */
@Composable
private fun Workspace21stCard(
    item: WorkspaceItem,
    formattedDate: String,
    onToggleComplete: (Boolean) -> Unit,
    onToggleSubItem: (String, Boolean) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val accentColor = when (item.type) {
        WorkspaceItemType.TASK -> AccentEmerald
        WorkspaceItemType.NOTE -> AccentSky
        WorkspaceItemType.IDEA -> AccentAmber
    }

    var checklistExpanded by remember { mutableStateOf(true) }
    val cardAlpha by animateFloatAsState(
        targetValue = if (item.isCompleted) 0.62f else 1f,
        animationSpec = tween(220),
        label = "cardAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(
                BorderStroke(
                    0.8.dp,
                    if (item.isCompleted) SubtleBorder.copy(alpha = 0.6f) else SubtleBorder
                ),
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        // Top Row: Circular Status Checkbox + Title + Badges + Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (item.type == WorkspaceItemType.TASK) {
                    CircularStatusCheckbox(
                        checked = item.isCompleted,
                        accentColor = AccentEmerald,
                        size = 20.dp,
                        onClick = { onToggleComplete(!item.isCompleted) }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                } else {
                    // Subtle colored indicator dot for Note / Idea
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(accentColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Text(
                    text = item.title,
                    color = if (item.isCompleted) TextSecondary else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right side: checklist counter (like 21st.dev `1/4 ^`) + copy + delete
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (item.checklist.isNotEmpty()) {
                    val doneSubCount = item.checklist.count { it.isDone }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ElevatedBg)
                            .clickable { checklistExpanded = !checklistExpanded }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$doneSubCount/${item.checklist.size}",
                            color = if (doneSubCount == item.checklist.size) AccentEmerald else TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (checklistExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Alt liste",
                            tint = TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onCopy),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Kopyala",
                        tint = TextMuted,
                        modifier = Modifier.size(13.5.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Sil",
                        tint = AccentRose.copy(alpha = 0.75f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Body content (if distinct from title)
        if (item.content.isNotBlank() && item.content != item.title) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.content,
                color = if (item.isCompleted) TextMuted else TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = if (item.type == WorkspaceItemType.TASK) 30.dp else 18.dp)
            )
        }

        // 21st.dev Collapsible Sub-Checklist (`starc007/todo-list` & `serafimcloud/todo-tool`)
        AnimatedVisibility(
            visible = item.checklist.isNotEmpty() && checklistExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(SurfaceBg, RoundedCornerShape(10.dp))
                    .border(BorderStroke(0.6.dp, SubtleBorder), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                item.checklist.forEach { sub ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onToggleSubItem(sub.id, !sub.isDone)
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        CircularStatusCheckbox(
                            checked = sub.isDone,
                            accentColor = AccentEmerald,
                            size = 16.dp,
                            onClick = { onToggleSubItem(sub.id, !sub.isDone) }
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = sub.text,
                            color = if (sub.isDone) TextMuted else TextPrimary.copy(alpha = 0.92f),
                            fontSize = 12.5.sp,
                            textDecoration = if (sub.isDone) TextDecoration.LineThrough else null,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(9.dp))

        // Footer Row: Type Badge + Priority Badge + Tags + Date
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (item.type == WorkspaceItemType.TASK) 30.dp else 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Semantic Type Pill
                Box(
                    modifier = Modifier
                        .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.type.labelTr,
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (item.type == WorkspaceItemType.TASK && item.priority == WorkspacePriority.HIGH) {
                    Box(
                        modifier = Modifier
                            .background(AccentRose.copy(alpha = 0.14f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Yüksek Öncelik",
                            color = AccentRose,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                item.tags.take(2).forEach { tag ->
                    Text(
                        text = "#$tag",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Text(
                text = formattedDate,
                color = TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Custom 21st.dev circular status checkbox (`Todo Tool` #12368 / `Agent Todo List` #28423).
 */
@Composable
private fun CircularStatusCheckbox(
    checked: Boolean,
    accentColor: Color,
    size: Dp,
    onClick: () -> Unit
) {
    Canvas(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        val radius = this.size.minDimension / 2f
        val strokeWidth = 1.5.dp.toPx()

        if (checked) {
            drawCircle(
                color = accentColor,
                radius = radius
            )
            // Crisp checkmark path
            val w = this.size.width
            val h = this.size.height
            drawLine(
                color = Color.Black,
                start = Offset(w * 0.28f, h * 0.52f),
                end = Offset(w * 0.44f, h * 0.68f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.Black,
                start = Offset(w * 0.44f, h * 0.68f),
                end = Offset(w * 0.74f, h * 0.34f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
        } else {
            drawCircle(
                color = Color(0xFF52525E),
                radius = radius - strokeWidth / 2f,
                style = Stroke(width = strokeWidth)
            )
        }
    }
}
