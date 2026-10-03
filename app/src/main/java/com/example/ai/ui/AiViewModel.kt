package com.example.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.data.AiPreferences
import com.example.ai.data.LocalAiClient
import com.example.ai.data.MediaDownloader
import com.example.ai.data.WebSearchEngine
import com.example.ai.model.AiServerStatus
import com.example.ai.model.ChatMessage
import com.example.ai.model.MessageRole
import com.example.ai.model.ToolExecution
import com.example.ai.model.ToolStatus
import com.example.data.SchoolFileManager
import com.example.model.SchoolItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AiUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val attachedItem: SchoolItem? = null,
    val activeToolName: String? = null,
    val serverStatus: AiServerStatus = AiServerStatus(),
    val statusMessage: String? = null,
    val downloadProgress: Int? = null,
    val showSettingsDialog: Boolean = false,
    val showAttachmentPicker: Boolean = false,
    val availableSchoolFiles: List<SchoolItem> = emptyList(),
    val serverUrl: String = "",
    val modelName: String = "",
    val supremePrompt: String = "",
    val isThinkingEnabled: Boolean = true,
    val isWebSearchEnabled: Boolean = true,
    val temperature: Float = 0.7f
)

class AiViewModel(application: Application) : AndroidViewModel(application) {

    val preferences = AiPreferences(application)
    private val aiClient = LocalAiClient()
    private val webSearchEngine = WebSearchEngine()
    private val mediaDownloader = MediaDownloader()
    val fileManager = SchoolFileManager(application)

    private val _uiState = MutableStateFlow(
        AiUiState(
            serverUrl = preferences.serverUrl,
            modelName = preferences.modelName,
            supremePrompt = preferences.supremePrompt,
            isThinkingEnabled = preferences.isThinkingEnabled,
            isWebSearchEnabled = preferences.isWebSearchEnabled,
            temperature = preferences.temperature,
            messages = listOf(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Merhaba! Ben yerel yapay zeka asistanınızım. Bilgisayarınızdaki yerel model üzerinden tamamen özel, sansürsüz ve cihaz içi çalışıyorum.\n\nOkul dizininizdeki notları ve .txt dosyalarını düzenleyebilir, yeni klasörler açabilir, dosya oluşturabilir, internetten araştırmalar yapabilir ve video/ders materyalleri indirebilirim. Hangi araçları kullandığımı her yanıtta canlı olarak görebilir ve genişletebilirsiniz. Nasıl yardımcı olabilirim?"
                )
            )
        )
    )
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    init {
        testServerConnection()
    }

    fun testServerConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                serverStatus = _uiState.value.serverStatus.copy(errorMessage = null)
            )
            val status = aiClient.testConnection(preferences.serverUrl)
            var newModelName = preferences.modelName
            var statusMsg: String? = null

            if (status.isConnected && status.availableModels.isNotEmpty()) {
                val exactMatch = status.availableModels.firstOrNull { it.equals(preferences.modelName, ignoreCase = true) }
                if (exactMatch != null) {
                    newModelName = exactMatch
                    preferences.modelName = exactMatch
                } else {
                    val matchingInstalled = status.availableModels.firstOrNull { it.contains("qwen", ignoreCase = true) || it.contains("coder", ignoreCase = true) }
                        ?: status.availableModels.first()
                    newModelName = matchingInstalled
                    preferences.modelName = matchingInstalled
                    statusMsg = "Bilgisayarınızdaki '$matchingInstalled' modeli otomatik seçildi."
                }
            }

            _uiState.value = _uiState.value.copy(
                serverStatus = status,
                modelName = newModelName,
                statusMessage = statusMsg ?: _uiState.value.statusMessage
            )
        }
    }

    fun attachSchoolFile(item: SchoolItem) {
        _uiState.value = _uiState.value.copy(
            attachedItem = item,
            showAttachmentPicker = false,
            statusMessage = "'${item.name}' sohbete eklendi"
        )
    }

    fun removeAttachment() {
        _uiState.value = _uiState.value.copy(attachedItem = null)
    }

    fun openAttachmentPicker() {
        viewModelScope.launch {
            val items = fileManager.listItemsInCurrentDirectory()
            _uiState.value = _uiState.value.copy(
                availableSchoolFiles = items,
                showAttachmentPicker = true
            )
        }
    }

    fun closeAttachmentPicker() {
        _uiState.value = _uiState.value.copy(showAttachmentPicker = false)
    }

    fun sendMessage(userText: String, forceWebSearch: Boolean = false) {
        val trimmed = userText.trim()
        if (trimmed.isBlank() && _uiState.value.attachedItem == null) return

        val attached = _uiState.value.attachedItem

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = trimmed,
            attachedFile = attached,
            isWebSearch = forceWebSearch
        )

        val updatedList = _uiState.value.messages + userMessage
        _uiState.value = _uiState.value.copy(
            messages = updatedList,
            attachedItem = null,
            isGenerating = true,
            statusMessage = null,
            activeToolName = null
        )

        viewModelScope.launch {
            val executedTools = mutableListOf<ToolExecution>()

            // 1. Proactive File Inspection if user attaches a file or asks about a file
            var attachedContent: String? = null
            if (attached != null) {
                _uiState.value = _uiState.value.copy(activeToolName = "Dosya İnceleniyor: ${attached.name}")
                attachedContent = if (!attached.isDirectory) {
                    fileManager.readText(File(attached.path))
                } else {
                    val sub = File(attached.path).list()?.joinToString(", ") ?: "Boş"
                    "Klasör Adı: ${attached.name}, Alt Ögeler: $sub"
                }
                executedTools.add(
                    ToolExecution(
                        toolName = "Dosya İnceleme",
                        summary = "${attached.name} dosyası incelendi",
                        inputDetail = "Dosya Yolu: ${attached.path}\nBoyut: ${attached.formattedSize}\nTür: ${attached.itemType.titleTr}",
                        outputDetail = attachedContent.take(1200)
                    )
                )
            } else {
                // Check if user mentioned an existing file in current directory
                val detectedFile = findMentionedFile(trimmed)
                if (detectedFile != null) {
                    _uiState.value = _uiState.value.copy(activeToolName = "Dosya İnceleniyor: ${detectedFile.name}")
                    val readText = fileManager.readText(detectedFile)
                    attachedContent = readText
                    executedTools.add(
                        ToolExecution(
                            toolName = "Dosya İnceleme",
                            summary = "${detectedFile.name} dosyası incelendi",
                            inputDetail = "Dosya: ${detectedFile.name}",
                            outputDetail = readText.take(1200)
                        )
                    )
                }
            }

            // 2. Web search if requested or query implies research
            var webSummary: String? = null
            if (forceWebSearch || (_uiState.value.isWebSearchEnabled && shouldTriggerWebSearch(trimmed))) {
                _uiState.value = _uiState.value.copy(
                    activeToolName = "Web Araması Yapılıyor: $trimmed",
                    statusMessage = "İnternet taranıyor..."
                )
                val webResults = webSearchEngine.searchWeb(trimmed)
                if (webResults.isNotEmpty()) {
                    webSummary = webResults.joinToString("\n\n") { "Başlık: ${it.title}\nÖzet: ${it.snippet}\nKaynak: ${it.url}" }
                    executedTools.add(
                        ToolExecution(
                            toolName = "İnternet Araması",
                            summary = "'$trimmed' internette arandı (${webResults.size} sonuç)",
                            inputDetail = "Arama Sorgusu: $trimmed",
                            outputDetail = webResults.take(3).joinToString("\n") { "• ${it.title}: ${it.snippet.take(150)}" }
                        )
                    )
                }
            }

            // 3. Workspace overview
            val workspaceOverview = buildWorkspaceSummary()

            _uiState.value = _uiState.value.copy(
                activeToolName = "Model Düşünüyor...",
                statusMessage = "Yapay zeka yanıt hazırlıyor..."
            )

            val result = aiClient.sendChat(
                serverUrl = preferences.serverUrl,
                modelName = preferences.modelName,
                supremePrompt = preferences.supremePrompt,
                messages = updatedList,
                temperature = preferences.temperature,
                attachedItem = attached,
                attachedFileContent = attachedContent,
                webSearchSummary = webSummary,
                workspaceOverview = workspaceOverview
            )

            if (result.isSuccess) {
                val (mainText, thinkingText) = result.getOrThrow()

                // Execute automated actions and append to tool executions
                val actionTools = executeDetectedActions(mainText)
                executedTools.addAll(actionTools)

                val assistantMsg = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = cleanActionSyntax(mainText),
                    thinkingContent = if (preferences.isThinkingEnabled) thinkingText else null,
                    toolExecutions = executedTools
                )

                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + assistantMsg,
                    isGenerating = false,
                    statusMessage = null,
                    activeToolName = null
                )
            } else {
                val err = result.exceptionOrNull()?.localizedMessage ?: "Bilinmeyen hata"
                val errorMsg = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Model ile iletişimde hata oluştu:\n$err\n\nLütfen bilgisayarınızdaki modelin (Ollama) çalıştığından ve ayarlardaki IP adresinin doğru olduğundan emin olun.",
                    isError = true,
                    toolExecutions = executedTools
                )
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + errorMsg,
                    isGenerating = false,
                    statusMessage = null,
                    activeToolName = null
                )
            }
        }
    }

    private suspend fun findMentionedFile(query: String): File? {
        val items = fileManager.listItemsInCurrentDirectory()
        for (item in items) {
            if (query.contains(item.name, ignoreCase = true) ||
                query.contains(item.name.substringBeforeLast("."), ignoreCase = true)) {
                return File(item.path)
            }
        }
        return null
    }

    private suspend fun buildWorkspaceSummary(): String {
        return try {
            val items = fileManager.listItemsInCurrentDirectory()
            val names = items.map { "${it.name} (${it.itemType.titleTr}, ${it.formattedSize})" }
            "Mevcut Aktif Klasör: '${fileManager.currentDirectory.name}'\nİçindekiler (${items.size} öge): " + names.joinToString(", ")
        } catch (_: Exception) {
            ""
        }
    }

    private fun shouldTriggerWebSearch(query: String): Boolean {
        val q = query.lowercase()
        return q.startsWith("ara ") || q.contains("kimdir") || q.contains("nedir") ||
                q.contains("güncel") || q.contains("haber") || q.contains("hava durumu") ||
                q.contains("vikipedi") || q.contains("internette")
    }

    private suspend fun executeDetectedActions(response: String): List<ToolExecution> {
        val tools = mutableListOf<ToolExecution>()
        try {
            // Action 1: [KOMUT: METİN_DÜZENLE | dosya_adi | yeni_icerik]
            val editRegex = Regex("\\[KOMUT:\\s*METİN_DÜZENLE\\s*\\|\\s*(.*?)\\s*\\|\\s*([\\s\\S]*?)\\]")
            editRegex.findAll(response).forEach { match ->
                val fileName = match.groupValues[1].trim()
                val newContent = match.groupValues[2].trim()
                val res = fileManager.updateTextFile(fileName, newContent)
                if (res.isSuccess) {
                    tools.add(
                        ToolExecution(
                            toolName = "Metin Düzenleme",
                            summary = "'$fileName' dosyası başarıyla güncellendi",
                            inputDetail = "Hedef Dosya: $fileName",
                            outputDetail = "Yeni İçerik:\n${newContent.take(500)}..."
                        )
                    )
                    _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' dosyası güncellendi")
                }
            }

            // Action 2: [KOMUT: NOT_OLUŞTUR | dosya_adi | icerik] or [KOMUT: DOSYA_OLUŞTUR | dosya_adi | icerik]
            val createRegex = Regex("\\[KOMUT:\\s*(?:NOT_OLUŞTUR|DOSYA_OLUŞTUR)\\s*\\|\\s*(.*?)\\s*\\|\\s*([\\s\\S]*?)\\]")
            createRegex.findAll(response).forEach { match ->
                val fileName = match.groupValues[1].trim()
                val content = match.groupValues[2].trim()
                val res = fileManager.createTextFile(fileName, content)
                if (res.isSuccess) {
                    tools.add(
                        ToolExecution(
                            toolName = "Dosya Oluşturma",
                            summary = "'$fileName' dosyası oluşturuldu",
                            inputDetail = "Dosya Adı: $fileName",
                            outputDetail = "Yazılan Not İçeriği:\n${content.take(500)}..."
                        )
                    )
                    _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' notu oluşturuldu")
                }
            }

            // Action 3: [KOMUT: KLASÖR_OLUŞTUR | klasor_adi]
            val folderRegex = Regex("\\[KOMUT:\\s*KLASÖR_OLUŞTUR\\s*\\|\\s*(.*?)\\]")
            folderRegex.findAll(response).forEach { match ->
                val folderName = match.groupValues[1].trim()
                val res = fileManager.createFolder(folderName)
                if (res.isSuccess) {
                    tools.add(
                        ToolExecution(
                            toolName = "Klasör Oluşturma",
                            summary = "'$folderName' klasörü oluşturuldu",
                            inputDetail = "Klasör Adı: $folderName",
                            outputDetail = "Okul çalışma alanına yeni dizin eklendi"
                        )
                    )
                    _uiState.value = _uiState.value.copy(statusMessage = "'$folderName' klasörü açıldı")
                }
            }

            // Action 4: [KOMUT: YENİDEN_ADLANDIR | eski_ad | yeni_ad]
            val renameRegex = Regex("\\[KOMUT:\\s*YENİDEN_ADLANDIR\\s*\\|\\s*(.*?)\\s*\\|\\s*(.*?)\\]")
            renameRegex.findAll(response).forEach { match ->
                val oldName = match.groupValues[1].trim()
                val newName = match.groupValues[2].trim()
                val targetFile = fileManager.findFile(oldName) ?: File(fileManager.currentDirectory, oldName)
                if (targetFile.exists()) {
                    fileManager.rename(targetFile, newName)
                    tools.add(
                        ToolExecution(
                            toolName = "Yeniden Adlandırma",
                            summary = "'$oldName' -> '$newName' olarak değiştirildi",
                            inputDetail = "Eski İsim: $oldName",
                            outputDetail = "Yeni İsim: $newName"
                        )
                    )
                    _uiState.value = _uiState.value.copy(statusMessage = "Dosya yeniden adlandırıldı: $newName")
                }
            }

            // Action 5: [KOMUT: İNDİR | url | dosya_adi]
            val downloadRegex = Regex("\\[KOMUT:\\s*İNDİR\\s*\\|\\s*(.*?)\\s*\\|\\s*(.*?)\\]")
            downloadRegex.findAll(response).forEach { match ->
                val url = match.groupValues[1].trim()
                val name = match.groupValues[2].trim()
                downloadMedia(url, name)
                tools.add(
                    ToolExecution(
                        toolName = "Dosya İndirme",
                        summary = "'$name' indirme işlemi başlatıldı",
                        inputDetail = "Kaynak URL: $url",
                        outputDetail = "Dosya okul klasörünüze indiriliyor..."
                    )
                )
            }
        } catch (_: Exception) {}
        return tools
    }

    private fun cleanActionSyntax(raw: String): String {
        return raw
            .replace(Regex("\\[KOMUT:\\s*(?:NOT_OLUŞTUR|DOSYA_OLUŞTUR)\\s*\\|\\s*.*?\\s*\\|\\s*[\\s\\S]*?\\]"), "*(Ders notu okul klasörünüze otomatik kaydedildi)*")
            .replace(Regex("\\[KOMUT:\\s*METİN_DÜZENLE\\s*\\|\\s*.*?\\s*\\|\\s*[\\s\\S]*?\\]"), "*(Dosya içeriği başarıyla güncellendi)*")
            .replace(Regex("\\[KOMUT:\\s*KLASÖR_OLUŞTUR\\s*\\|\\s*.*?\\]"), "*(Yeni okul klasörü açıldı)*")
            .replace(Regex("\\[KOMUT:\\s*YENİDEN_ADLANDIR\\s*\\|\\s*.*?\\s*\\|\\s*.*?\\]"), "*(Dosya adı başarıyla güncellendi)*")
            .replace(Regex("\\[KOMUT:\\s*İNDİR\\s*\\|\\s*.*?\\s*\\|\\s*.*?\\]"), "*(İndirme işlemi başlatıldı)*")
    }

    fun downloadMedia(url: String, customName: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                statusMessage = "İndirme başlatılıyor...",
                downloadProgress = 0
            )
            val result = mediaDownloader.downloadFile(
                fileUrl = url,
                targetDirectory = fileManager.currentDirectory,
                customName = customName
            ) { percent ->
                _uiState.value = _uiState.value.copy(downloadProgress = percent)
            }

            if (result.isSuccess) {
                val f = result.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    downloadProgress = null,
                    statusMessage = "Dosya başarıyla indirildi: ${f.name}"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    downloadProgress = null,
                    statusMessage = "İndirme hatası: ${result.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun saveSettings(
        serverUrl: String,
        modelName: String,
        supremePrompt: String,
        isThinking: Boolean,
        isWebSearch: Boolean,
        temperature: Float
    ) {
        preferences.serverUrl = serverUrl
        preferences.modelName = modelName
        preferences.supremePrompt = supremePrompt
        preferences.isThinkingEnabled = isThinking
        preferences.isWebSearchEnabled = isWebSearch
        preferences.temperature = temperature

        _uiState.value = _uiState.value.copy(
            serverUrl = preferences.serverUrl,
            modelName = preferences.modelName,
            supremePrompt = preferences.supremePrompt,
            isThinkingEnabled = preferences.isThinkingEnabled,
            isWebSearchEnabled = preferences.isWebSearchEnabled,
            temperature = preferences.temperature,
            showSettingsDialog = false,
            statusMessage = "Yapay zeka ayarları ve En Üst Kademe Prompt kaydedildi!"
        )

        testServerConnection()
    }

    fun resetSupremePrompt() {
        preferences.resetSupremePrompt()
        _uiState.value = _uiState.value.copy(supremePrompt = preferences.supremePrompt)
    }

    fun setShowSettings(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSettingsDialog = show)
    }

    fun clearChat() {
        _uiState.value = _uiState.value.copy(
            messages = listOf(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Sohbet sıfırlandı. Okul dosyalarınız, not düzenlemeleriniz veya araştırmalarınız hakkında nasıl yardımcı olabilirim?"
                )
            )
        )
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }
}
