package com.tarslauncher.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tarslauncher.app.data.model.WorkspaceItem
import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.notes.LocalNotesRepository
import kotlinx.coroutines.launch

private val IslandBlack = Color(0xFF000000)
private val IslandWhite = Color(0xFFFFFFFF)
private val IslandMuted = Color(0xFF888888)
private val IslandDim = Color(0xFF555555)
private val IslandBorder = Color(0xFF2A2A2A)

/**
 * Uygulama içi Dynamic Island: sistem katmanı (overlay) izni, erişilebilirlik servisi veya ağ
 * gerektirmez. Kapalıyken son aktif notu gösterir; dokununca hızlı not ekleme ve son notlar açılır.
 */
@Composable
fun NotesDynamicIsland(
    onOpenAllNotes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember { LocalNotesRepository.get(context) }
    val items by repo.workspaceItemsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    var expanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    val active = remember(items) { items.filterNot { it.isCompleted } }
    val compactLabel = active.firstOrNull()?.title?.takeIf { it.isNotBlank() }
        ?: "TARS"

    fun collapse() {
        expanded = false
        input = ""
        focusManager.clearFocus()
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        scope.launch {
            repo.addItem(WorkspaceItem(type = WorkspaceItemType.NOTE, title = text))
        }
    }

    if (expanded) {
        BackHandler { collapse() }
        // Dışarı dokununca kapat
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { collapse() }
        )
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .animateContentSize(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f))
                .widthIn(min = 96.dp, max = if (expanded) 340.dp else 220.dp)
                .background(IslandBlack, RoundedCornerShape(if (expanded) 24.dp else 20.dp))
                .border(BorderStroke(1.dp, IslandBorder), RoundedCornerShape(if (expanded) 24.dp else 20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !expanded
                ) { expanded = true }
                .padding(horizontal = 14.dp, vertical = if (expanded) 12.dp else 6.dp)
        ) {
            if (!expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .widthIn(min = 6.dp)
                            .background(if (active.isNotEmpty()) IslandWhite else IslandDim, RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        text = compactLabel,
                        color = IslandWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (active.size > 1) {
                        Text(
                            text = "  +${active.size - 1}",
                            color = IslandMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                LaunchedEffect(Unit) { focusRequester.requestFocus() }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "N O T L A R",
                        color = IslandWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "TÜMÜ",
                        color = IslandMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable { collapse(); onOpenAllNotes() }
                            .padding(4.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("+ ", color = IslandMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it.take(WorkspaceItem.MAX_TITLE_LENGTH) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(
                            color = IslandWhite,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(IslandWhite),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text("Hızlı not...", color = IslandDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            }
                            inner()
                        }
                    )
                }

                val recent = items.take(4)
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    recent.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "- ${item.title}",
                                color = if (item.isCompleted) IslandDim else IslandWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Light,
                                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        scope.launch { repo.toggleItemCompleted(item.id, !item.isCompleted) }
                                    }
                            )
                            Text(
                                text = "×",
                                color = IslandDim,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .clickable { scope.launch { repo.delete(item.id) } }
                                    .padding(start = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
