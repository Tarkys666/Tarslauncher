package com.tarslauncher.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tarslauncher.app.data.model.NoteItem
import com.tarslauncher.app.data.notes.NoteManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageNotesDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val noteManager = remember { NoteManager(context) }
    val notes by noteManager.notesFlow.collectAsState(initial = emptyList())

    var isAddingNote by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.95f)
            ),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TARS NOT DEFTERİ",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "${notes.size} kayıtlı not",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    IconButton(
                        onClick = { isAddingNote = !isAddingNote }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Yeni Not Ekle",
                            tint = if (isAddingNote) Color.Cyan else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // New note input row if expanded
                if (isAddingNote) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF161618)
                        ),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = newTitle,
                                onValueChange = { newTitle = it },
                                label = { Text("Başlık (Opsiyonel)", color = Color.Gray, fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.Gray
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = newContent,
                                onValueChange = { newContent = it },
                                label = { Text("Not İçeriği", color = Color.Gray, fontSize = 12.sp) },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.Gray
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    isAddingNote = false
                                    newTitle = ""
                                    newContent = ""
                                }) {
                                    Text("İptal", color = Color.Gray, fontSize = 12.sp)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Button(
                                    onClick = {
                                        if (newContent.isNotBlank()) {
                                            coroutineScope.launch {
                                                noteManager.addNote(newContent, newTitle)
                                                newTitle = ""
                                                newContent = ""
                                                isAddingNote = false
                                                Toast.makeText(context, "Not kaydedildi", Toast.LENGTH_SHORT).show()
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

                // Notes List
                if (notes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Henüz kayıtlı notunuz yok.\nTARS Ajanı'na 'Bunu not al...' diyebilir veya yukarıdaki '+' butonuyla not ekleyebilirsiniz.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(notes, key = { it.id }) { note ->
                            NoteCardItem(
                                note = note,
                                formattedDate = dateFormat.format(Date(note.createdAt)),
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText(note.title, note.content))
                                    Toast.makeText(context, "Not kopyalandı", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        noteManager.deleteNote(note.id)
                                        Toast.makeText(context, "Not silindi", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (notes.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    noteManager.clearNotes()
                                    Toast.makeText(context, "Tüm notlar temizlendi", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("Tümünü Temizle", color = Color.Red.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Kapat", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCardItem(
    note: NoteItem,
    formattedDate: String,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
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
                Text(
                    text = note.title.ifBlank { "Not" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Kopyala",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Sil",
                            tint = Color.Red.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = formattedDate,
                color = Color.Gray,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Text(
                text = note.content,
                color = Color.LightGray,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
