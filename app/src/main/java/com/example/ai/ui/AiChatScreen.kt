package com.example.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import com.example.ai.model.ToolStatus
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.model.BlockType
import com.example.ai.model.ChatMessage
import com.example.ai.model.MessageBlock
import com.example.ai.model.MessageRole
import com.example.ai.model.ToolExecution
import com.example.ui.components.getTypeColorAndIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var inputText by remember { mutableStateOf("") }
    var forceWebSearch by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Okul Yapay Zeka Asistanı",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Server Connection Status
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (uiState.serverStatus.isConnected) Color(0xFF10B981) else Color(0xFFEF4444),
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.serverStatus.isConnected) {
                                    "${uiState.modelName} • Bağlı (${uiState.serverStatus.latencyMs}ms)"
                                } else {
                                    "Yerel Model Bağlantısı Bekleniyor"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.testServerConnection() },
                        modifier = Modifier.testTag("refresh_ai_connection_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Bağlantıyı Yenile")
                    }

                    IconButton(
                        onClick = { viewModel.clearChat() },
                        modifier = Modifier.testTag("clear_chat_btn")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Sohbeti Temizle")
                    }

                    IconButton(
                        onClick = { viewModel.setShowSettings(true) },
                        modifier = Modifier.testTag("open_ai_settings_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Ayarlar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Quick Prompts Chips Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        inputText = "Okul dizinimdeki ders notlarını ve klasörleri incele, bana detaylı bir özet çıkar."
                    },
                    label = { Text("📂 Dosyaları İncele & Özetle", fontSize = 12.sp) }
                )

                AssistChip(
                    onClick = {
                        inputText = "Formuller_ve_Teoremler.txt dosyasını düzenle ve içine yeni integral kuralları ekle."
                    },
                    label = { Text("📝 Notu Düzenle (.txt)", fontSize = 12.sp) }
                )

                AssistChip(
                    onClick = {
                        inputText = "Bana 'Biyoloji Dersi' adında yeni bir klasör aç ve içine hücre bölünmesi notu oluştur."
                    },
                    label = { Text("📁 Klasör & Not Aç", fontSize = 12.sp) }
                )

                AssistChip(
                    onClick = {
                        forceWebSearch = true
                        inputText = "İnternette araştır: "
                    },
                    label = { Text("🌐 Web'de Araştır", fontSize = 12.sp) }
                )

                AssistChip(
                    onClick = { showDownloadDialog = true },
                    label = { Text("📥 Video/Dosya İndir", fontSize = 12.sp) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

            // Chat Messages List
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatMessageBubble(
                        message = message,
                        onCopy = { text ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Yapay Zeka Yanıtı", text))
                        }
                    )
                }

                if (uiState.isGenerating) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = uiState.activeToolName ?: "Model düşünüyor ve araçları çalıştırıyor...",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Active Attached File Badge
            AnimatedVisibility(visible = uiState.attachedItem != null) {
                uiState.attachedItem?.let { item ->
                    val (color, icon) = getTypeColorAndIcon(item.itemType)
                    Surface(
                        color = color.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Eklendi: ${item.name} (${item.formattedSize})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            IconButton(
                                onClick = { viewModel.removeAttachment() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Kaldır", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment button
                    IconButton(
                        onClick = { viewModel.openAttachmentPicker() },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("attach_school_file_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Okul Dosyası Ekle",
                            tint = if (uiState.attachedItem != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Web search toggle
                    IconButton(
                        onClick = { forceWebSearch = !forceWebSearch },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Canlı İnternet Araması",
                            tint = if (forceWebSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                if (forceWebSearch) "İnternette ara ve sor..." else "Derslerin veya dosyaların hakkında sor...",
                                fontSize = 13.sp
                            )
                        },
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .testTag("ai_chat_input_field"),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank() || uiState.attachedItem != null) {
                                val text = inputText
                                inputText = ""
                                viewModel.sendMessage(text, forceWebSearch)
                                forceWebSearch = false
                            }
                        },
                        enabled = !uiState.isGenerating && (inputText.isNotBlank() || uiState.attachedItem != null),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("send_ai_message_btn"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Gönder",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // Settings Modal
    if (uiState.showSettingsDialog) {
        AiSettingsDialog(
            currentServerUrl = uiState.serverUrl,
            currentModelName = uiState.modelName,
            currentVisionModelName = uiState.visionModelName,
            currentSupremePrompt = uiState.supremePrompt,
            isThinking = uiState.isThinkingEnabled,
            isWebSearch = uiState.isWebSearchEnabled,
            temperature = uiState.temperature,
            serverStatus = uiState.serverStatus,
            onTestConnection = { viewModel.testServerConnection() },
            onSave = { sUrl, mName, vName, sPrompt, isTh, isWs, temp ->
                viewModel.saveSettings(sUrl, mName, vName, sPrompt, isTh, isWs, temp)
            },
            onDismiss = { viewModel.setShowSettings(false) }
        )
    }

    // Attachment Picker Modal
    if (uiState.showAttachmentPicker) {
        SchoolAttachmentPickerDialog(
            fileManager = viewModel.fileManager,
            onSelect = { item -> viewModel.attachSchoolFile(item) },
            onDismiss = { viewModel.closeAttachmentPicker() }
        )
    }

    // Download Media Dialog
    if (showDownloadDialog) {
        DownloadMediaDialog(
            onConfirm = { url, name ->
                showDownloadDialog = false
                viewModel.downloadMedia(url, name)
            },
            onDismiss = { showDownloadDialog = false }
        )
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onCopy: (String) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val timeStr = remember(message.timestamp) {
        val sdf = SimpleDateFormat("HH:mm", Locale("tr"))
        sdf.format(Date(message.timestamp))
    }

    var isThinkingExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else if (message.isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 0.94f)
                .animateContentSize()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Attached File Pill inside User Message
                if (message.attachedFile != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.2f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "📎 ${message.attachedFile.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (message.blocks.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        message.blocks.forEach { block ->
                            when (block.type) {
                                BlockType.THINKING -> {
                                    if (block.text.isNotBlank()) {
                                        ThinkingCard(thinkingContent = block.text)
                                    }
                                }
                                BlockType.TOOL -> {
                                    if (block.tool != null) {
                                        ToolExecutionCard(tool = block.tool)
                                    }
                                }
                                BlockType.TEXT -> {
                                    if (block.text.isNotBlank()) {
                                        Text(
                                            text = block.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 22.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Fallback for legacy messages
                    if (message.toolExecutions.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            message.toolExecutions.forEach { tool ->
                                ToolExecutionCard(tool = tool)
                            }
                        }
                    }

                    if (!message.thinkingContent.isNullOrBlank()) {
                        ThinkingCard(thinkingContent = message.thinkingContent)
                    }

                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Bottom row: timestamp, model badge, and copy button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                        if (!isUser && !message.modelName.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "🤖 ${message.modelName}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (!isUser) {
                        IconButton(
                            onClick = { onCopy(message.content) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Kopyala",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingCard(thinkingContent: String) {
    var isThinkingExpanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isThinkingExpanded = !isThinkingExpanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Modelin Düşünme Süreci (<think>)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = if (isThinkingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (isThinkingExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = thinkingContent,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ToolExecutionCard(tool: ToolExecution) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = when (tool.status) {
        ToolStatus.RUNNING -> Color(0xFFF59E0B) // Amber/Yellow
        ToolStatus.SUCCESS -> Color(0xFF10B981) // Emerald Green
        ToolStatus.FAILED -> Color(0xFFEF4444) // Red
    }
    val badgeBg = statusColor.copy(alpha = 0.15f)
    val badgeTextColor = when (tool.status) {
        ToolStatus.RUNNING -> Color(0xFFD97706)
        ToolStatus.SUCCESS -> Color(0xFF059669)
        ToolStatus.FAILED -> Color(0xFFDC2626)
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (tool.status == ToolStatus.RUNNING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = statusColor
                        )
                    } else if (tool.status == ToolStatus.SUCCESS) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg
                    ) {
                        Text(
                            text = tool.toolName,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTextColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = tool.summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "Kapat" else "Genişlet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))

                // Live status banner
                val statusBannerText = when (tool.status) {
                    ToolStatus.RUNNING -> "⏳ Ajan işlemi yürütülüyor (Canlı)..."
                    ToolStatus.SUCCESS -> "✅ Ajan işlemi başarıyla tamamlandı."
                    ToolStatus.FAILED -> "❌ Ajan işlemi başarısız oldu."
                }
                Text(
                    text = statusBannerText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeTextColor
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (!tool.errorMessage.isNullOrBlank()) {
                    Text(
                        text = "Hata / Başarısızlık Nedeni:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444)
                    )
                    Text(
                        text = tool.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = Color(0xFFDC2626)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (tool.inputDetail.isNotBlank()) {
                    Text(
                        text = "Girdi / Talimat:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = tool.inputDetail,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (tool.outputDetail.isNotBlank()) {
                    Text(
                        text = "Ajan Çıktısı / Gözlem Sonucu:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = tool.outputDetail,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
