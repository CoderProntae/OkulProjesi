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
    val visionModelName: String = "minicpm-v",
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
            visionModelName = preferences.visionModelName,
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

        val activeModel = preferences.modelName
        val activeVisionModel = preferences.visionModelName

        val placeholderAssistantMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            thinkingContent = null,
            modelName = activeModel,
            toolExecutions = emptyList()
        )

        val updatedList = _uiState.value.messages + userMessage + placeholderAssistantMsg
        _uiState.value = _uiState.value.copy(
            messages = updatedList,
            attachedItem = null,
            isGenerating = true,
            statusMessage = "1. Model ($activeModel) başlatılıyor...",
            activeToolName = null
        )

        viewModelScope.launch {
            val executedTools = mutableListOf<ToolExecution>()

            fun updateAssistantMessage(
                content: String? = null,
                thinking: String? = null,
                tools: List<ToolExecution>? = null,
                statusMsg: String? = null,
                activeTool: String? = null
            ) {
                val currentList = _uiState.value.messages.toMutableList()
                if (currentList.isNotEmpty() && currentList.last().role == MessageRole.ASSISTANT) {
                    val last = currentList.last()
                    currentList[currentList.lastIndex] = last.copy(
                        content = content ?: last.content,
                        thinkingContent = thinking ?: last.thinkingContent,
                        toolExecutions = tools ?: last.toolExecutions,
                        modelName = activeModel
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = currentList,
                        statusMessage = statusMsg ?: _uiState.value.statusMessage,
                        activeToolName = activeTool ?: _uiState.value.activeToolName
                    )
                }
            }

            // 1. Proactive File Inspection if user attaches a file or asks about a file
            var attachedContent: String? = null
            if (attached != null) {
                val ext = attached.extension.lowercase()
                val isImageOrVideo = ext in listOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "webm", "avi", "mov")
                val isAudio = ext in listOf("mp3", "wav", "m4a", "ogg", "aac", "flac")
                val isText = fileManager.isTextFile(File(attached.path))
                val friendlyPath = fileManager.getUserFriendlyPath(File(attached.path))

                if (isImageOrVideo) {
                    val isVideo = ext in listOf("mp4", "mkv", "webm", "avi", "mov")
                    val runningTool = ToolExecution(
                        toolName = "👁️ 2. Model: MiniCPM-V (Görsel/Video Alt Ajanı)",
                        summary = "${attached.name} (${if (isVideo) "Video" else "Görsel"}) 2. modelce analiz ediliyor...",
                        inputDetail = "Dosya: $friendlyPath (${attached.formattedSize})\nModel: $activeVisionModel\nTalimat: English Visual Inspection",
                        outputDetail = "Kilit kareler çıkarılıyor ve $activeVisionModel modeline iletiliyor...",
                        status = ToolStatus.RUNNING
                    )
                    executedTools.add(runningTool)
                    updateAssistantMessage(tools = executedTools.toList(), statusMsg = "2. Model ($activeVisionModel) görsel analizi yapıyor...")

                    val frames = aiClient.extractMediaFrames(File(attached.path), maxFrames = 3)
                    if (frames.isNotEmpty()) {
                        val visionPrompt = if (isVideo) {
                            "You are an expert visual educator assistant. Analyze these ${frames.size} video keyframes extracted from the file '${attached.name}' in precise detail:\n" +
                            "1. Describe the key visual content and actions (e.g. science experiment, lecture whiteboard, presentation slides, diagrams).\n" +
                            "2. Transcribe and extract all visible text, questions, mathematical formulas, labels, and numbers accurately in English.\n" +
                            "3. Structure your analysis clearly."
                        } else {
                            "You are an expert visual educator assistant. Analyze this educational image/document '${attached.name}' in precise detail:\n" +
                            "1. Transcribe all visible text, handwritten notes, mathematical equations, questions, and diagrams accurately in English.\n" +
                            "2. Explain what is depicted in the image clearly."
                        }
                        val visionResult = aiClient.callVisionModel(
                            serverUrl = preferences.serverUrl,
                            visionModelName = activeVisionModel,
                            prompt = visionPrompt,
                            base64Images = frames
                        )
                        if (visionResult.isSuccess) {
                            val analysis = visionResult.getOrThrow()
                            executedTools[executedTools.lastIndex] = runningTool.copy(
                                summary = "${attached.name} (${if (isVideo) "Video: ${frames.size} kare" else "Fotoğraf"}) başarıyla incelendi",
                                outputDetail = analysis,
                                status = ToolStatus.SUCCESS
                            )
                            attachedContent = "[2. GÖRSEL VE VİDEO MODELİNİN ($activeVisionModel) İNGİLİZCE ANALİZ RAPORU - Dosya: ${attached.name}]:\n$analysis\n\n(YÖNERGE: Yukarıdaki İngilizce görsel analiz raporunu kullanarak kullanıcıya akıcı ve kusursuz Türkçe ile detaylı ders açıklaması, soru çözümü veya not oluştur.)"
                        } else {
                            val err = visionResult.exceptionOrNull()?.localizedMessage ?: "Görsel analizi başarısız oldu"
                            executedTools[executedTools.lastIndex] = runningTool.copy(
                                summary = "2. Model görsel analizi başarısız oldu",
                                outputDetail = "Hata: $err",
                                status = ToolStatus.FAILED,
                                errorMessage = err
                            )
                        }
                    } else {
                        executedTools[executedTools.lastIndex] = runningTool.copy(
                            summary = "Video/Görsel kareleri çıkarılamadı",
                            outputDetail = "Dosyadan kilit kareler okunamadı",
                            status = ToolStatus.FAILED,
                            errorMessage = "Kare çıkarılamadı"
                        )
                    }
                    updateAssistantMessage(tools = executedTools.toList())
                } else if (isAudio) {
                    val runningTool = ToolExecution(
                        toolName = "📁 Dosya İnceleme Ajanı",
                        summary = "${attached.name} ses dosyası bağlamı taranıyor...",
                        inputDetail = "Dosya: $friendlyPath (${attached.formattedSize})\nTür: Ses Dosyası",
                        outputDetail = "İlişkili ders transkripti ve notları aranıyor...",
                        status = ToolStatus.RUNNING
                    )
                    executedTools.add(runningTool)
                    updateAssistantMessage(tools = executedTools.toList(), statusMsg = "Ders ses kaydı inceleniyor...")

                    val associated = fileManager.findAssociatedTranscript(attached.name)
                    if (associated != null) {
                        executedTools[executedTools.lastIndex] = runningTool.copy(
                            summary = "${attached.name} transkripti bulundu ve yüklendi",
                            outputDetail = "Transkript Notu:\n$associated",
                            status = ToolStatus.SUCCESS
                        )
                        attachedContent = "[DERS SES KAYDI: ${attached.name} (${attached.formattedSize})]\nBu ses dosyasıyla ilişkili ders transkripti / notu bulundu:\n$associated"
                    } else {
                        executedTools[executedTools.lastIndex] = runningTool.copy(
                            summary = "${attached.name} ses dosyası bağlamı hazırlandı",
                            outputDetail = "Bu dosya bir ikili ses kaydıdır (${attached.formattedSize}). Model dinleme rehberliği yapacak.",
                            status = ToolStatus.SUCCESS
                        )
                        attachedContent = "[DERS SES KAYDI: ${attached.name} (${attached.formattedSize})]\n(Bu dosya bir ikili ses kaydıdır. Kullanıcı dinleme dersiyle ilgili sorular sorduğunda dinleme stratejileri ve içerik konusunda yardımcı ol.)"
                    }
                    updateAssistantMessage(tools = executedTools.toList())
                } else if (isText) {
                    val runningTool = ToolExecution(
                        toolName = "📁 Dosya İnceleme Ajanı",
                        summary = "${attached.name} metin içeriği okunuyor...",
                        inputDetail = "Dosya: $friendlyPath (${attached.formattedSize})",
                        outputDetail = "Metin taranıyor...",
                        status = ToolStatus.RUNNING
                    )
                    executedTools.add(runningTool)
                    updateAssistantMessage(tools = executedTools.toList(), statusMsg = "Metin dosyası okunuyor...")

                    val readText = fileManager.readText(File(attached.path))
                    executedTools[executedTools.lastIndex] = runningTool.copy(
                        summary = "${attached.name} başarıyla okundu",
                        outputDetail = readText.take(1500),
                        status = ToolStatus.SUCCESS
                    )
                    attachedContent = readText
                    updateAssistantMessage(tools = executedTools.toList())
                } else {
                    val folderTool = ToolExecution(
                        toolName = "📁 Klasör İnceleme Ajanı",
                        summary = "${attached.name} klasör yapısı incelendi",
                        inputDetail = "Konum: $friendlyPath",
                        outputDetail = if (attached.isDirectory) {
                            val sub = File(attached.path).list()?.filter { !it.startsWith(".") }?.joinToString(", ") ?: "Boş"
                            "Klasör İçeriği: $sub"
                        } else "Dosya: ${attached.name} (${attached.formattedSize})",
                        status = ToolStatus.SUCCESS
                    )
                    executedTools.add(folderTool)
                    attachedContent = if (attached.isDirectory) {
                        val sub = File(attached.path).list()?.filter { !it.startsWith(".") }?.joinToString(", ") ?: "Boş"
                        "Klasör: ${attached.name}, İçindeki Dosyalar: $sub"
                    } else "Dosya: ${attached.name} (${attached.formattedSize})"
                    updateAssistantMessage(tools = executedTools.toList())
                }
            } else {
                // Check if user mentioned an existing file in current directory
                val detectedFile = findMentionedFile(trimmed)
                if (detectedFile != null) {
                    val friendlyPath = fileManager.getUserFriendlyPath(detectedFile)
                    val runningTool = ToolExecution(
                        toolName = "📁 Dosya İnceleme Ajanı",
                        summary = "${detectedFile.name} inceleniyor...",
                        inputDetail = "Dosya: $friendlyPath",
                        outputDetail = "İçerik okunuyor...",
                        status = ToolStatus.RUNNING
                    )
                    executedTools.add(runningTool)
                    updateAssistantMessage(tools = executedTools.toList(), statusMsg = "${detectedFile.name} inceleniyor...")

                    val readText = fileManager.readText(detectedFile)
                    executedTools[executedTools.lastIndex] = runningTool.copy(
                        summary = "${detectedFile.name} dosyası incelendi",
                        outputDetail = readText.take(1500),
                        status = ToolStatus.SUCCESS
                    )
                    attachedContent = readText
                    updateAssistantMessage(tools = executedTools.toList())
                }
            }

            // 2. Web search if requested or query implies research
            var webSummary: String? = null
            if (forceWebSearch || (_uiState.value.isWebSearchEnabled && shouldTriggerWebSearch(trimmed))) {
                val runningTool = ToolExecution(
                    toolName = "🌐 İnternet Arama Ajanı",
                    summary = "'$trimmed' internette taranıyor...",
                    inputDetail = "Arama Sorgusu: $trimmed",
                    outputDetail = "Arama motoru taranıyor...",
                    status = ToolStatus.RUNNING
                )
                executedTools.add(runningTool)
                updateAssistantMessage(tools = executedTools.toList(), statusMsg = "İnternet taranıyor...")

                val webResults = webSearchEngine.searchWeb(trimmed)
                if (webResults.isNotEmpty()) {
                    webSummary = webResults.joinToString("\n\n") { "Başlık: ${it.title}\nÖzet: ${it.snippet}\nKaynak: ${it.url}" }
                    executedTools[executedTools.lastIndex] = runningTool.copy(
                        summary = "'$trimmed' internette arandı (${webResults.size} sonuç)",
                        outputDetail = webResults.take(3).joinToString("\n") { "• ${it.title}: ${it.snippet.take(150)}" },
                        status = ToolStatus.SUCCESS
                    )
                } else {
                    executedTools[executedTools.lastIndex] = runningTool.copy(
                        summary = "Arama sonucu bulunamadı",
                        outputDetail = "İnternet aramasında sonuç dönmedi",
                        status = ToolStatus.FAILED,
                        errorMessage = "Sonuç bulunamadı"
                    )
                }
                updateAssistantMessage(tools = executedTools.toList())
            }

            // 3. Workspace overview (recursive tree of entire school workspace)
            val workspaceOverview = buildWorkspaceSummary()

            updateAssistantMessage(
                statusMsg = "1. Model ($activeModel) yanıt yazıyor...",
                tools = executedTools.toList()
            )

            val accumulatedText = StringBuilder()
            val result = aiClient.sendChat(
                serverUrl = preferences.serverUrl,
                modelName = activeModel,
                visionModelName = activeVisionModel,
                supremePrompt = preferences.supremePrompt,
                messages = _uiState.value.messages.dropLast(1),
                temperature = preferences.temperature,
                isThinkingEnabled = preferences.isThinkingEnabled,
                attachedItem = attached,
                attachedFileContent = attachedContent,
                webSearchSummary = webSummary,
                workspaceOverview = workspaceOverview,
                onChunk = { chunk ->
                    accumulatedText.append(chunk)
                    val rawSoFar = accumulatedText.toString()

                    val activeTool = when {
                        rawSoFar.contains("GÖRSEL_MODELİ_ÇAĞIR") -> "👁️ 2. Model Çağrılıyor..."
                        rawSoFar.contains("METİN_DÜZENLE") -> "⚙️ Dosya Düzenleniyor..."
                        rawSoFar.contains("NOT_OLUŞTUR") || rawSoFar.contains("DOSYA_OLUŞTUR") -> "📝 Not Oluşturuluyor..."
                        rawSoFar.contains("KLASÖR_OLUŞTUR") -> "📁 Klasör Açılıyor..."
                        rawSoFar.contains("ARA") -> "🔍 İnternet Taranıyor..."
                        rawSoFar.contains("<think>") && !rawSoFar.contains("</think>") -> "🧠 Model Düşünüyor..."
                        else -> null
                    }

                    val (streamMain, streamThink) = extractThinkingFromRaw(rawSoFar)
                    updateAssistantMessage(
                        content = cleanActionSyntax(streamMain),
                        thinking = if (preferences.isThinkingEnabled) streamThink else null,
                        activeTool = activeTool
                    )
                }
            )

            if (result.isSuccess) {
                val (mainText, thinkingText) = result.getOrThrow()

                // Execute automated actions in real time and append to tool executions
                val actionTools = executeDetectedActions(mainText)
                val allTools = executedTools + actionTools

                val finalCleanText = cleanActionSyntax(mainText)
                updateAssistantMessage(
                    content = if (finalCleanText.isNotBlank()) finalCleanText else "İşlem başarıyla tamamlandı.",
                    thinking = if (preferences.isThinkingEnabled) thinkingText else null,
                    tools = allTools
                )

                _uiState.value = _uiState.value.copy(
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
                    modelName = activeModel,
                    toolExecutions = executedTools
                )
                val currentList = _uiState.value.messages.toMutableList()
                if (currentList.isNotEmpty() && currentList.last().role == MessageRole.ASSISTANT) {
                    currentList[currentList.lastIndex] = errorMsg
                } else {
                    currentList.add(errorMsg)
                }
                _uiState.value = _uiState.value.copy(
                    messages = currentList,
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
            fileManager.buildFullWorkspaceHierarchy(maxDepth = 3)
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractThinkingFromRaw(raw: String): Pair<String, String?> {
        val thinkRegex = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.IGNORE_CASE)
        val match = thinkRegex.find(raw)
        return if (match != null) {
            val thinking = match.groupValues[1].trim()
            val cleanMain = raw.replace(match.value, "").trim()
            cleanMain to thinking
        } else {
            raw to null
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
            // Action 0: [KOMUT: DOSYA_İNCELE | dosya_adi]
            val inspectRegex = Regex("\\[KOMUT:\\s*DOSYA_İNCELE\\s*\\|\\s*(.*?)\\]")
            inspectRegex.findAll(response).forEach { match ->
                val fileName = match.groupValues[1].trim()
                val targetFile = fileManager.findFile(fileName) ?: File(fileManager.currentDirectory, fileName)
                val friendlyPath = fileManager.getUserFriendlyPath(targetFile)
                if (targetFile.exists()) {
                    val content = fileManager.readText(targetFile)
                    tools.add(
                        ToolExecution(
                            toolName = "📁 Dosya İnceleme Ajanı",
                            summary = "'$fileName' dosyası başarıyla incelendi",
                            inputDetail = "Hedef: $friendlyPath (${targetFile.length() / 1024} KB)",
                            outputDetail = content.take(1500),
                            status = ToolStatus.SUCCESS
                        )
                    )
                } else {
                    tools.add(
                        ToolExecution(
                            toolName = "📁 Dosya İnceleme Ajanı",
                            summary = "'$fileName' dosyası bulunamadı",
                            inputDetail = "Hedef: $friendlyPath",
                            outputDetail = "Dosya okul çalışma alanında bulunamadı",
                            status = ToolStatus.FAILED,
                            errorMessage = "Dosya mevcut değil"
                        )
                    )
                }
            }

            // Action 1: [KOMUT: METİN_DÜZENLE | dosya_adi | yeni_icerik]
            val editRegex = Regex("\\[KOMUT:\\s*METİN_DÜZENLE\\s*\\|\\s*(.*?)\\s*\\|\\s*([\\s\\S]*?)\\]")
            editRegex.findAll(response).forEach { match ->
                val fileName = match.groupValues[1].trim()
                val newContent = match.groupValues[2].trim()
                val targetFile = fileManager.findFile(fileName) ?: File(fileManager.currentDirectory, fileName)
                val friendlyPath = fileManager.getUserFriendlyPath(targetFile)
                val res = fileManager.updateTextFile(fileName, newContent)
                if (res.isSuccess) {
                    tools.add(
                        ToolExecution(
                            toolName = "✍️ Metin Düzenleme Ajanı",
                            summary = "'$fileName' dosyası başarıyla güncellendi",
                            inputDetail = "Hedef: $friendlyPath",
                            outputDetail = "Yeni İçerik:\n${newContent.take(500)}...",
                            status = ToolStatus.SUCCESS
                        )
                    )
                    _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' dosyası güncellendi")
                } else {
                    tools.add(
                        ToolExecution(
                            toolName = "✍️ Metin Düzenleme Ajanı",
                            summary = "'$fileName' güncellenemedi",
                            inputDetail = "Hedef: $friendlyPath",
                            outputDetail = "Hata: ${res.exceptionOrNull()?.localizedMessage}",
                            status = ToolStatus.FAILED,
                            errorMessage = res.exceptionOrNull()?.localizedMessage
                        )
                    )
                }
            }

            // Action 2: [KOMUT: NOT_OLUŞTUR | dosya_adi | icerik] or [KOMUT: DOSYA_OLUŞTUR | dosya_adi | icerik]
            val createRegex = Regex("\\[KOMUT:\\s*(?:NOT_OLUŞTUR|DOSYA_OLUŞTUR)\\s*\\|\\s*(.*?)\\s*\\|\\s*([\\s\\S]*?)\\]")
            createRegex.findAll(response).forEach { match ->
                val fileName = match.groupValues[1].trim()
                val content = match.groupValues[2].trim()
                val targetFile = File(fileManager.currentDirectory, fileName)
                val friendlyPath = fileManager.getUserFriendlyPath(targetFile)
                val res = fileManager.createTextFile(fileName, content)
                if (res.isSuccess) {
                    tools.add(
                        ToolExecution(
                            toolName = "✍️ Not Oluşturma Ajanı",
                            summary = "'$fileName' ders notu oluşturuldu",
                            inputDetail = "Dosya: $friendlyPath",
                            outputDetail = "Yazılan Not İçeriği:\n${content.take(500)}...",
                            status = ToolStatus.SUCCESS
                        )
                    )
                    _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' notu oluşturuldu")
                } else {
                    tools.add(
                        ToolExecution(
                            toolName = "✍️ Not Oluşturma Ajanı",
                            summary = "'$fileName' oluşturulamadı",
                            inputDetail = "Dosya: $friendlyPath",
                            outputDetail = "Hata: ${res.exceptionOrNull()?.localizedMessage}",
                            status = ToolStatus.FAILED,
                            errorMessage = res.exceptionOrNull()?.localizedMessage
                        )
                    )
                }
            }

            // Action 3: [KOMUT: KLASÖR_OLUŞTUR | klasor_adi]
            val folderRegex = Regex("\\[KOMUT:\\s*KLASÖR_OLUŞTUR\\s*\\|\\s*(.*?)\\]")
            folderRegex.findAll(response).forEach { match ->
                val folderName = match.groupValues[1].trim()
                val targetDir = File(fileManager.currentDirectory, folderName)
                val friendlyPath = fileManager.getUserFriendlyPath(targetDir)
                val res = fileManager.createFolder(folderName)
                if (res.isSuccess) {
                    tools.add(
                        ToolExecution(
                            toolName = "📁 Klasör Oluşturma Ajanı",
                            summary = "'$folderName' klasörü açıldı",
                            inputDetail = "Konum: $friendlyPath",
                            outputDetail = "Klasör fiziksel cihaz depolamasına eklendi",
                            status = ToolStatus.SUCCESS
                        )
                    )
                    _uiState.value = _uiState.value.copy(statusMessage = "'$folderName' klasörü açıldı")
                } else {
                    tools.add(
                        ToolExecution(
                            toolName = "📁 Klasör Oluşturma Ajanı",
                            summary = "'$folderName' oluşturulamadı",
                            inputDetail = "Konum: $friendlyPath",
                            outputDetail = "Hata: ${res.exceptionOrNull()?.localizedMessage}",
                            status = ToolStatus.FAILED,
                            errorMessage = res.exceptionOrNull()?.localizedMessage
                        )
                    )
                }
            }

            // Action 4: [KOMUT: YENİDEN_ADLANDIR | eski_ad | yeni_ad]
            val renameRegex = Regex("\\[KOMUT:\\s*YENİDEN_ADLANDIR\\s*\\|\\s*(.*?)\\s*\\|\\s*(.*?)\\]")
            renameRegex.findAll(response).forEach { match ->
                val oldName = match.groupValues[1].trim()
                val newName = match.groupValues[2].trim()
                val targetFile = fileManager.findFile(oldName) ?: File(fileManager.currentDirectory, oldName)
                if (targetFile.exists()) {
                    val res = fileManager.rename(targetFile, newName)
                    if (res.isSuccess) {
                        tools.add(
                            ToolExecution(
                                toolName = "🏷️ Yeniden Adlandırma Ajanı",
                                summary = "'$oldName' -> '$newName' olarak değiştirildi",
                                inputDetail = "Eski: $oldName -> Yeni: $newName",
                                outputDetail = "Fiziksel depolamada dosya adı güncellendi",
                                status = ToolStatus.SUCCESS
                            )
                        )
                        _uiState.value = _uiState.value.copy(statusMessage = "Dosya yeniden adlandırıldı: $newName")
                    } else {
                        tools.add(
                            ToolExecution(
                                toolName = "🏷️ Yeniden Adlandırma Ajanı",
                                summary = "Yeniden adlandırma başarısız oldu",
                                inputDetail = "Eski: $oldName -> Yeni: $newName",
                                outputDetail = "Hata: ${res.exceptionOrNull()?.localizedMessage}",
                                status = ToolStatus.FAILED,
                                errorMessage = res.exceptionOrNull()?.localizedMessage
                            )
                        )
                    }
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
                        toolName = "⬇️ Dosya İndirme Ajanı",
                        summary = "'$name' indirme işlemi başlatıldı",
                        inputDetail = "Kaynak URL: $url\nHedef: 📁 Okul Dizini/$name",
                        outputDetail = "Dosya okul klasörünüze indiriliyor...",
                        status = ToolStatus.RUNNING
                    )
                )
            }

            // Action 6: [KOMUT: GÖRSEL_MODELİ_ÇAĞIR | dosya_adi | soru]
            val visionRegex = Regex("\\[KOMUT:\\s*GÖRSEL_MODELİ_ÇAĞIR\\s*\\|\\s*(.*?)\\s*\\|\\s*(.*?)\\]")
            visionRegex.findAll(response).forEach { match ->
                val fileName = match.groupValues[1].trim()
                val question = match.groupValues[2].trim()
                val targetFile = fileManager.findFile(fileName) ?: File(fileManager.currentDirectory, fileName)
                val friendlyPath = fileManager.getUserFriendlyPath(targetFile)
                if (targetFile.exists()) {
                    val frames = aiClient.extractMediaFrames(targetFile, maxFrames = 3)
                    if (frames.isNotEmpty()) {
                        val isVid = targetFile.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi", "mov")
                        val vRes = aiClient.callVisionModel(
                            serverUrl = preferences.serverUrl,
                            visionModelName = preferences.visionModelName,
                            prompt = question,
                            base64Images = frames
                        )
                        if (vRes.isSuccess) {
                            tools.add(
                                ToolExecution(
                                    toolName = "👁️ 2. Model: MiniCPM-V (Görsel/Video Alt Ajanı)",
                                    summary = "$fileName (${if (isVid) "Video ${frames.size} kare" else "Görsel"}) 2. modelce analiz edildi",
                                    inputDetail = "1. Modelin Talimatı: $question\nHedef: $friendlyPath\nModel: ${preferences.visionModelName}",
                                    outputDetail = vRes.getOrThrow(),
                                    status = ToolStatus.SUCCESS
                                )
                            )
                        } else {
                            tools.add(
                                ToolExecution(
                                    toolName = "👁️ 2. Model: MiniCPM-V (Görsel/Video Alt Ajanı)",
                                    summary = "2. Model analizi başarısız oldu",
                                    inputDetail = "Hedef: $friendlyPath\nModel: ${preferences.visionModelName}",
                                    outputDetail = "Hata: ${vRes.exceptionOrNull()?.localizedMessage}",
                                    status = ToolStatus.FAILED,
                                    errorMessage = vRes.exceptionOrNull()?.localizedMessage
                                )
                            )
                        }
                    }
                } else {
                    tools.add(
                        ToolExecution(
                            toolName = "👁️ 2. Model: MiniCPM-V (Görsel/Video Alt Ajanı)",
                            summary = "'$fileName' dosyası bulunamadı",
                            inputDetail = "Aranan Dosya: $fileName",
                            outputDetail = "Dosya okul çalışma alanında bulunamadı",
                            status = ToolStatus.FAILED,
                            errorMessage = "Dosya bulunamadı"
                        )
                    )
                }
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
            .replace(Regex("\\[KOMUT:\\s*GÖRSEL_MODELİ_ÇAĞIR\\s*\\|\\s*.*?\\s*\\|\\s*.*?\\]"), "*(2. Model ile görsel/video analizi tamamlandı)*")
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
        visionModelName: String,
        supremePrompt: String,
        isThinking: Boolean,
        isWebSearch: Boolean,
        temperature: Float
    ) {
        preferences.serverUrl = serverUrl
        preferences.modelName = modelName
        preferences.visionModelName = visionModelName
        preferences.supremePrompt = supremePrompt
        preferences.isThinkingEnabled = isThinking
        preferences.isWebSearchEnabled = isWebSearch
        preferences.temperature = temperature

        _uiState.value = _uiState.value.copy(
            serverUrl = preferences.serverUrl,
            modelName = preferences.modelName,
            visionModelName = preferences.visionModelName,
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
