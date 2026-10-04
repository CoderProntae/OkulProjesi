package com.example.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.data.AiPreferences
import com.example.ai.model.AiServerStatus

@Composable
fun AiSettingsDialog(
    currentServerUrl: String,
    currentModelName: String,
    currentVisionModelName: String = "minicpm-v",
    currentVisionServerUrl: String = "",
    currentAudioServerUrl: String = "http://192.168.1.100:8000",
    currentSupremePrompt: String,
    isThinking: Boolean,
    isWebSearch: Boolean,
    temperature: Float,
    serverStatus: AiServerStatus,
    audioServerStatus: AiServerStatus = AiServerStatus(),
    onTestConnection: () -> Unit,
    onSave: (serverUrl: String, modelName: String, visionModelName: String, visionServerUrl: String, audioServerUrl: String, supremePrompt: String, isThinking: Boolean, isWebSearch: Boolean, temperature: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(currentServerUrl) }
    var modelName by remember { mutableStateOf(currentModelName) }
    var visionModelName by remember { mutableStateOf(currentVisionModelName) }
    var visionServerUrl by remember { mutableStateOf(currentVisionServerUrl) }
    var audioServerUrl by remember { mutableStateOf(currentAudioServerUrl) }
    var supremePrompt by remember { mutableStateOf(currentSupremePrompt) }
    var thinkingMode by remember { mutableStateOf(isThinking) }
    var webSearchEnabled by remember { mutableStateOf(isWebSearch) }
    var temp by remember { mutableFloatStateOf(temperature) }

    val recommendedModels = listOf(
        "qwen2.5-coder:7b",
        "dolphin-mistral",
        "dolphin-llama3",
        "deepseek-r1:7b",
        "minicpm-v",
        "llama3.2:3b",
        "llava"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .padding(vertical = 12.dp)
                .testTag("ai_settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Yapay Zeka & Sunucu Ayarları",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Yerel Model (GTX 1660 Super) & Üst Kademe Yönerge",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_ai_settings_btn")) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Kapat")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connection Status Pill Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (serverStatus.isConnected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (serverStatus.isConnected) Icons.Default.CheckCircle else Icons.Default.Sensors,
                                contentDescription = null,
                                tint = if (serverStatus.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (serverStatus.isConnected) "Bilgisayara Bağlandı (${serverStatus.latencyMs} ms)" else "Bağlantı Kurulamadı",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (serverStatus.isConnected) {
                                        "Tespit edilen modeller: ${serverStatus.availableModels.take(3).joinToString(", ")}"
                                    } else {
                                        serverStatus.errorMessage ?: "Ollama sunucusu kontrol ediliyor..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Button(
                            onClick = onTestConnection,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("test_connection_btn")
                        ) {
                            Text("Test Et", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 1: Server URL
                Text(
                    text = "Bilgisayar Adresi (Ollama / Local IP)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    singleLine = true,
                    placeholder = { Text("http://192.168.1.X:11435") },
                    modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: Model Name
                Text(
                    text = "Model Adı",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("model_name_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (serverStatus.availableModels.isNotEmpty()) {
                    Text(
                        text = "Bilgisayarınızda Yüklü Modeller (Seçmek için dokunun):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        serverStatus.availableModels.forEach { m ->
                            FilterChip(
                                selected = modelName == m,
                                onClick = { modelName = m },
                                label = { Text(m, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "GTX 1660 Super (6GB VRAM) için önerilen modeller:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recommendedModels.take(3).forEach { m ->
                        FilterChip(
                            selected = modelName == m,
                            onClick = { modelName = m },
                            label = { Text(m, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recommendedModels.drop(3).forEach { m ->
                        FilterChip(
                            selected = modelName == m,
                            onClick = { modelName = m },
                            label = { Text(m, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2.5: 2. Model (Görsel ve Video Uzmanı)
                Text(
                    text = "2. Model: Görsel ve Video Uzmanı (Vision)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "1. Modeliniz görsel, ödev fotoğrafı veya video incelemesi gerektiğinde doğrudan bu 2. modeli arka planda çağırır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = visionModelName,
                    onValueChange = { visionModelName = it },
                    singleLine = true,
                    placeholder = { Text("minicpm-v") },
                    modifier = Modifier.fillMaxWidth().testTag("vision_model_name_input"),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("minicpm-v", "llava:7b", "moondream").forEach { vm ->
                        FilterChip(
                            selected = visionModelName == vm,
                            onClick = { visionModelName = vm },
                            label = { Text(vm, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Görsel/Video Sunucusu (Opsiyonel Ayrı Port):",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = visionServerUrl,
                    onValueChange = { visionServerUrl = it },
                    singleLine = true,
                    placeholder = { Text("Boş bırakılırsa ana sunucu ($serverUrl) kullanılır") },
                    modifier = Modifier.fillMaxWidth().testTag("vision_server_url_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Section 2.6: Ses & Transkript Sunucusu (Whisper Portu)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎙️ Ses ve Transkript Motoru (Whisper)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (audioServerStatus.isConnected) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "Aktif (${audioServerStatus.latencyMs}ms)",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Ses dosyalarını (.mp3, .wav) doğrudan dinleyen Whisper sunucu portu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = audioServerUrl,
                    onValueChange = { audioServerUrl = it },
                    singleLine = true,
                    placeholder = { Text("http://192.168.1.X:8000") },
                    modifier = Modifier.fillMaxWidth().testTag("audio_server_url_input"),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val baseIp = serverUrl.substringBeforeLast(":")
                    listOf("$baseIp:8000", "$baseIp:11435").forEach { quickUrl ->
                        FilterChip(
                            selected = audioServerUrl == quickUrl,
                            onClick = { audioServerUrl = quickUrl },
                            label = { Text(quickUrl.substringAfterLast(":"), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Section 3: En Üst Kademe Prompt (Master Directive)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "En Üst Kademe Prompt (Master Directive)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    OutlinedButton(
                        onClick = { supremePrompt = AiPreferences.DEFAULT_SUPREME_PROMPT },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("reset_supreme_prompt_btn")
                    ) {
                        Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sıfırla", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bu prompt, modelin sistem yönergesinden ve kullanıcı girdilerinden daima üstündür. Modelin her zaman ilk ve son kontrol edeceği en yüksek yetkili emirdir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = supremePrompt,
                    onValueChange = { supremePrompt = it },
                    minLines = 6,
                    maxLines = 10,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("supreme_prompt_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(14.dp))

                // Section 4: Features Switches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Düşünme / Akıl Yürütme Modu (<think>)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "DeepSeek-R1 ve düşünme modellerinin akıl yürütme adımlarını ayrı kutuda gösterir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = thinkingMode,
                        onCheckedChange = { thinkingMode = it },
                        modifier = Modifier.testTag("thinking_mode_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Canlı İnternet Araması",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Araştırma ve güncel bilgiler için telefondan doğrudan arama yapıp modele aktarır.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = webSearchEnabled,
                        onCheckedChange = { webSearchEnabled = it },
                        modifier = Modifier.testTag("web_search_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(14.dp))

                // Section 5: PC Kurulum Rehberi (Step-by-step for GTX 1660 Super)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GTX 1660 Super ile PC Kurulumu (3 Adım):",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "1. https://ollama.com adresinden Ollama'yı indirip kurun.\n" +
                                    "2. Bilgisayarınızda PowerShell'i açıp şu komutla sansürsüz modeli başlatın:\n" +
                                    "   \$env:OLLAMA_HOST=\"0.0.0.0\"; ollama run dolphin-mistral\n" +
                                    "   (6GB VRAM için tam uygundur, sansürsüz ve hızlıdır)\n" +
                                    "3. Bilgisayarınızın yerel IP'sini (ipconfig yazarak) yukarıdaki adrese yazıp 'Test Et'e basın.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Save button
                Button(
                    onClick = {
                        onSave(
                            serverUrl.trim(),
                            modelName.trim(),
                            visionModelName.trim(),
                            visionServerUrl.trim(),
                            audioServerUrl.trim(),
                            supremePrompt,
                            thinkingMode,
                            webSearchEnabled,
                            temp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_ai_settings_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ayarları Kaydet ve Uygula")
                }
            }
        }
    }
}
