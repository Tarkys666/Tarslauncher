package com.tarslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageNotesDialog(
    onDismiss: () -> Unit,
    onOpenAgentSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { GoogleAuthManager(context) }
    val workspaceRepo = remember { FirebaseWorkspaceRepository(context, authManager) }

    val items by workspaceRepo.workspaceItemsFlow.collectAsState(initial = emptyList())
    val currentUser by authManager.authUserFlow.collectAsState(initial = authManager.currentUser)

    var selectedFilter by remember { mutableStateOf<WorkspaceItemType?>(null) }
    var isAddingItem by remember { mutableStateOf(false) }
    var newType by remember { mutableStateOf(WorkspaceItemType.TASK) }
    var newPriority by remember { mutableStateOf(WorkspacePriority.MEDIUM) }
    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }
    var newChecklistRaw by remember { mutableStateOf("") }

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

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("tr-TR")) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.97f)
            ),
            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TARS // WORKSPACE",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "${items.size} kayıt · Firebase Bulut Senkronizasyonu",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    IconButton(
                        onClick = { isAddingItem = !isAddingItem }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Yeni Kayıt Ekle",
                            tint = if (isAddingItem) Color(0xFF69F0AE) else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Google Auth Banner
                val isGoogleLinked = currentUser != null && currentUser?.isAnonymous == false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0E0E11), RoundedCornerShape(6.dp))
                        .border(
                            BorderStroke(
                                0.8.dp,
                                if (isGoogleLinked) Color(0xFF69F0AE).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.25f)
                            ),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGoogleLinked) {
                                "GOOGLE HESABI BAĞLI"
                            } else {
                                "VERİ KORUMASI // GOOGLE AUTH"
                            },
                            color = if (isGoogleLinked) Color(0xFF69F0AE) else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (isGoogleLinked) {
                                currentUser?.email ?: "Bulut senkronizasyonu aktif"
                            } else {
                                "Verilerinizin kaybolmaması için Google ile giriş yapın"
                            },
                            color = Color.Gray,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (isGoogleLinked) {
                        Box(
                            modifier = Modifier
                                .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.3f)), RoundedCornerShape(4.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        authManager.signOut()
                                        Toast.makeText(context, "Google oturumu kapatıldı", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "ÇIKIŞ",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .clickable {
                                    val client = authManager.getGoogleSignInClient()
                                    if (client != null) {
                                        googleSignInLauncher.launch(client.signInIntent)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Google Web Client ID tanımlı değil. Agent ayarlarından veya google-services.json ile ekleyin.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        onOpenAgentSettings?.invoke()
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "GOOGLE GİRİŞ",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Filter Tabs (Tümü | Görevler | Notlar | Fikirler)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkspaceFilterTab(
                        label = "TÜMÜ (${items.size})",
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null }
                    )
                    WorkspaceFilterTab(
                        label = "GÖREV (${items.count { it.type == WorkspaceItemType.TASK }})",
                        selected = selectedFilter == WorkspaceItemType.TASK,
                        onClick = { selectedFilter = WorkspaceItemType.TASK }
                    )
                    WorkspaceFilterTab(
                        label = "NOT (${items.count { it.type == WorkspaceItemType.NOTE }})",
                        selected = selectedFilter == WorkspaceItemType.NOTE,
                        onClick = { selectedFilter = WorkspaceItemType.NOTE }
                    )
                    WorkspaceFilterTab(
                        label = "FİKİR (${items.count { it.type == WorkspaceItemType.IDEA }})",
                        selected = selectedFilter == WorkspaceItemType.IDEA,
                        onClick = { selectedFilter = WorkspaceItemType.IDEA }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Manual New Workspace Item Form
                if (isAddingItem) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF121215)),
                        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Type Selector Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                WorkspaceItemType.entries.forEach { t ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(
                                                if (newType == t) Color.White else Color.Black,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                BorderStroke(0.8.dp, Color.White.copy(alpha = 0.3f)),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { newType = t }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = t.badgeText,
                                            color = if (newType == t) Color.Black else Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = newTitle,
                                onValueChange = { newTitle = it },
                                label = { Text("Başlık", color = Color.Gray, fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.Gray
                                )
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = newContent,
                                onValueChange = { newContent = it },
                                label = { Text("Detay / İçerik", color = Color.Gray, fontSize = 11.sp) },
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.Gray
                                )
                            )

                            if (newType == WorkspaceItemType.TASK) {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = newChecklistRaw,
                                    onValueChange = { newChecklistRaw = it },
                                    label = { Text("Alt Maddeler (virgülle ayırın - opsiyonel)", color = Color.Gray, fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color.White,
                                        unfocusedBorderColor = Color.Gray
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    isAddingItem = false
                                    newTitle = ""
                                    newContent = ""
                                    newChecklistRaw = ""
                                }) {
                                    Text("İptal", color = Color.Gray, fontSize = 12.sp)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Button(
                                    onClick = {
                                        val finalTitle = newTitle.trim().ifBlank { newContent.trim().take(30) }
                                        if (finalTitle.isNotBlank()) {
                                            val checklist = if (newType == WorkspaceItemType.TASK && newChecklistRaw.isNotBlank()) {
                                                newChecklistRaw.split(",", "\n")
                                                    .map { it.trim() }
                                                    .filter { it.isNotBlank() }
                                                    .map { ChecklistItem(text = it) }
                                            } else emptyList()

                                            val item = WorkspaceItem(
                                                type = newType,
                                                title = finalTitle,
                                                content = newContent.trim(),
                                                checklist = checklist,
                                                priority = newPriority
                                            )
                                            coroutineScope.launch {
                                                workspaceRepo.addItem(item)
                                                newTitle = ""
                                                newContent = ""
                                                newChecklistRaw = ""
                                                isAddingItem = false
                                                Toast.makeText(context, "${newType.labelTr} Firebase'e kaydedildi", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("Kaydet", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Workspace Items List
                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Bu görünümde henüz kayıt yok.\nDynamic Island'dan veya TARS Agent konsolundan doğal dille görev, not ve fikir ekleyebilirsiniz.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            WorkspaceCardItem(
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
                                    clipboard.setPrimaryClip(ClipData.newPlainText(item.title, "${item.title}\n${item.content}"))
                                    Toast.makeText(context, "Panoya kopyalandı", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        workspaceRepo.deleteByQuery(item.id)
                                        Toast.makeText(context, "Kayıt silindi", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val completedTasksCount = items.count { it.type == WorkspaceItemType.TASK && it.isCompleted }
                    if (completedTasksCount > 0) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    workspaceRepo.clearCompletedTasks()
                                    Toast.makeText(context, "Tamamlanan görevler temizlendi", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text(
                                text = "Bitenleri Temizle ($completedTasksCount)",
                                color = Color(0xFFFF5252),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("KAPAT", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) Color.White else Color(0xFF101014),
                RoundedCornerShape(4.dp)
            )
            .border(
                BorderStroke(0.8.dp, if (selected) Color.White else Color.White.copy(alpha = 0.2f)),
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun WorkspaceCardItem(
    item: WorkspaceItem,
    formattedDate: String,
    onToggleComplete: (Boolean) -> Unit,
    onToggleSubItem: (String, Boolean) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val badgeColor = when (item.type) {
        WorkspaceItemType.TASK -> Color(0xFF10B981)
        WorkspaceItemType.NOTE -> Color(0xFF38BDF8)
        WorkspaceItemType.IDEA -> Color(0xFFF59E0B)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121215)),
        border = BorderStroke(0.6.dp, Color.White.copy(alpha = if (item.isCompleted) 0.08f else 0.18f)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
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
                        Icon(
                            imageVector = if (item.isCompleted) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = "Tamamla",
                            tint = if (item.isCompleted) Color(0xFF10B981) else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onToggleComplete(!item.isCompleted) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Type Badge
                    Box(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                            .border(BorderStroke(0.6.dp, badgeColor.copy(alpha = 0.6f)), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.type.badgeText,
                            color = badgeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (item.type == WorkspaceItemType.TASK && item.priority == WorkspacePriority.HIGH) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFF5252).copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ACİL",
                                color = Color(0xFFFF5252),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = item.title,
                        color = if (item.isCompleted) Color.Gray else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Kopyala",
                            tint = Color.Gray,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Sil",
                            tint = Color(0xFFFF5252).copy(alpha = 0.75f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            if (item.content.isNotBlank() && item.content != item.title) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.content,
                    color = if (item.isCompleted) Color.DarkGray else Color.LightGray,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            // Sub-checklist items
            if (item.checklist.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    item.checklist.forEach { sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleSubItem(sub.id, !sub.isDone) }
                        ) {
                            Text(
                                text = if (sub.isDone) "[✓]" else "[ ]",
                                color = if (sub.isDone) Color(0xFF10B981) else Color.Gray,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = sub.text,
                                color = if (sub.isDone) Color.Gray else Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                textDecoration = if (sub.isDone) TextDecoration.LineThrough else null
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.tags.take(3).forEach { tag ->
                        Text(
                            text = "#$tag",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Text(
                    text = formattedDate,
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
