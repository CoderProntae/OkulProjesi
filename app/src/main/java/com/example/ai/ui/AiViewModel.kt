package com.example.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.data.AiPreferences
import com.example.ai.data.LocalAiClient
import com.example.ai.data.MediaDownloader
import com.example.ai.data.WebSearchEngine
import com.example.ai.model.AiServerStatus
import com.example.ai.model.BlockType
import com.example.ai.model.ChatMessage
import com.example.ai.model.MessageBlock
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
            blocks = emptyList()
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
            val messageBlocks = mutableListOf<MessageBlock>()

            fun updateAssistantState(
                statusMsg: String? = null,
                activeTool: String? = null
            ) {
                val currentList = _uiState.value.messages.toMutableList()
                if (currentList.isNotEmpty() && currentList.last().role == MessageRole.ASSISTANT) {
                    val fullText = messageBlocks.filter { it.type == BlockType.TEXT }.joinToString("\n\n") { it.text }
                    val thinkText = messageBlocks.firstOrNull { it.type == BlockType.THINKING }?.text
                    val allTools = messageBlocks.mapNotNull { it.tool }

                    currentList[currentList.lastIndex] = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = fullText,
                        thinkingContent = thinkText,
                        blocks = messageBlocks.toList(),
                        toolExecutions = allTools,
                        modelName = activeModel
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = currentList,
                        statusMessage = statusMsg ?: _uiState.value.statusMessage,
                        activeToolName = activeTool ?: _uiState.value.activeToolName
                    )
                }
            }

            // 1. Prepare attached file content in background without polluting message blocks prematurely
            var attachedContent: String? = null
            if (attached != null) {
                val ext = attached.extension.lowercase()
                val isImageOrVideo = ext in listOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "webm", "avi", "mov")
                val isAudio = ext in listOf("mp3", "wav", "m4a", "ogg", "aac", "flac")
                val isText = fileManager.isTextFile(File(attached.path))

                if (isImageOrVideo) {
                    val frames = aiClient.extractMediaFrames(File(attached.path), maxFrames = 3)
                    val isVideo = ext in listOf("mp4", "mkv", "webm", "avi", "mov")
                    val visionPrompt = if (isVideo) {
                        "You are an expert visual educator assistant. Analyze these ${frames.size} video keyframes extracted from '${attached.path}' in detail:\n" +
                        "1. Describe visual content, actions, lecture slides, formulas.\n" +
                        "2. Transcribe visible text and equations.\n" +
                        "3. Structure your response clearly."
                    } else {
                        "You are an expert visual educator assistant. Analyze this image/document '${attached.path}' in detail:\n" +
                        "1. Transcribe all visible text, questions, diagrams and formulas accurately in English.\n" +
                        "2. Explain what is depicted clearly."
                    }
                    val visionResult = aiClient.callVisionModel(
                        serverUrl = preferences.serverUrl,
                        visionModelName = activeVisionModel,
                        prompt = visionPrompt,
                        base64Images = frames,
                        fallbackModelName = activeModel
                    )
                    if (visionResult.isSuccess) {
                        val analysis = visionResult.getOrThrow()
                        attachedContent = "[2. MODEL ($activeVisionModel) GÖRSEL/VİDEO ANALİZ RAPORU - Dosya: ${attached.path}]:\n$analysis"
                    } else {
                        attachedContent = "[GÖRSEL/VİDEO DOSYASI - ${attached.name} (${attached.formattedSize})]:\nDosya Yolu: ${attached.path}\nKilit Kare Sayısı: ${frames.size}\nLütfen bu dosya hakkındaki soruyu ve ders konusunu yanıtla."
                    }
                } else if (isAudio) {
                    val audioFile = File(attached.path)
                    val rawAudioResult = aiClient.processRawAudioInput(
                        serverUrl = preferences.serverUrl,
                        audioFile = audioFile,
                        audioServerUrl = preferences.audioServerUrl.ifBlank { null },
                        prompt = "Transcribe all dialogue and educational speech from '${attached.name}' accurately."
                    )
                    val details = fileManager.extractAudioMediaDetails(audioFile)
                    if (rawAudioResult.isSuccess) {
                        val transcript = rawAudioResult.getOrThrow()
                        attachedContent = "[GERÇEK SES MODELİNDEN HAM SES TRANSKRİPTİ - Dosya: ${attached.path}]:\n$transcript\n\n[MEDYA BİLGİSİ]:\n$details"
                    } else {
                        attachedContent = "[GERÇEK SES DOSYASI GİRDİSİ - ${attached.name} (${attached.formattedSize})]:\n$details\n(Not: Dosyanın ham ikili ses verisi yerel sunucuya iletildi. Lütfen içeriği ve ders detaylarını analiz et.)"
                    }
                } else if (isText) {
                    attachedContent = fileManager.readText(File(attached.path))
                } else {
                    attachedContent = "Dosya: ${attached.name} (${attached.formattedSize})"
                }
            }

            // 2. Web search if explicitly forced
            var webSummary: String? = null
            if (forceWebSearch || (_uiState.value.isWebSearchEnabled && shouldTriggerWebSearch(trimmed))) {
                val webResults = webSearchEngine.searchWeb(trimmed)
                if (webResults.isNotEmpty()) {
                    webSummary = webResults.joinToString("\n\n") { "Başlık: ${it.title}\nÖzet: ${it.snippet}\nKaynak: ${it.url}" }
                }
            }

            // 3. Workspace overview (deep recursive tree)
            val workspaceOverview = buildWorkspaceSummary()

            updateAssistantState(statusMsg = "1. Model ($activeModel) yanıt yazıyor...")

            // Create initial placeholder text block for streaming
            val streamBlock = MessageBlock(type = BlockType.TEXT, text = "")
            messageBlocks.add(streamBlock)

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
                        rawSoFar.contains("GÖRSEL_MODELİ_ÇAĞIR") -> "👁️ 2. Model Devrede..."
                        rawSoFar.contains("METİN_DÜZENLE") -> "⚙️ Dosya Düzenleniyor..."
                        rawSoFar.contains("NOT_OLUŞTUR") || rawSoFar.contains("DOSYA_OLUŞTUR") -> "📝 Not Oluşturuluyor..."
                        rawSoFar.contains("KLASÖR_OLUŞTUR") -> "📁 Klasör Açılıyor..."
                        rawSoFar.contains("ARA") -> "🔍 İnternet Taranıyor..."
                        rawSoFar.contains("<think>") && !rawSoFar.contains("</think>") -> "🧠 Model Düşünüyor..."
                        else -> null
                    }

                    val (streamMain, streamThink) = extractThinkingFromRaw(rawSoFar)

                    // Update thinking block if present
                    if (!streamThink.isNullOrBlank()) {
                        val thinkIdx = messageBlocks.indexOfFirst { it.type == BlockType.THINKING }
                        if (thinkIdx != -1) {
                            messageBlocks[thinkIdx] = MessageBlock(type = BlockType.THINKING, text = streamThink)
                        } else {
                            messageBlocks.add(0, MessageBlock(type = BlockType.THINKING, text = streamThink))
                        }
                    }

                    val lastIdx = messageBlocks.indexOfLast { it.type == BlockType.TEXT }
                    if (lastIdx != -1) {
                        messageBlocks[lastIdx] = MessageBlock(type = BlockType.TEXT, text = cleanActionSyntax(streamMain))
                    }
                    updateAssistantState(activeTool = activeTool)
                }
            )

            if (result.isSuccess) {
                val (mainText, thinkingText) = result.getOrThrow()

                // Parse and execute actions in their exact textual order
                val parsedBlocks = parseAndExecuteInterleavedBlocks(
                    rawText = mainText,
                    attachedItem = attached,
                    onBlockUpdate = {
                        val currentList = _uiState.value.messages.toMutableList()
                        if (currentList.isNotEmpty() && currentList.last().role == MessageRole.ASSISTANT) {
                            val fullText = messageBlocks.filter { it.type == BlockType.TEXT }.joinToString("\n\n") { it.text }
                            currentList[currentList.lastIndex] = ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = fullText,
                                thinkingContent = thinkingText,
                                blocks = messageBlocks.toList(),
                                toolExecutions = messageBlocks.mapNotNull { it.tool },
                                modelName = activeModel
                            )
                            _uiState.value = _uiState.value.copy(messages = currentList)
                        }
                    }
                )

                messageBlocks.clear()
                if (!thinkingText.isNullOrBlank()) {
                    messageBlocks.add(MessageBlock(type = BlockType.THINKING, text = thinkingText))
                }
                messageBlocks.addAll(parsedBlocks)

                updateAssistantState(statusMsg = null, activeTool = null)

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
                    blocks = messageBlocks.toList()
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

    private suspend fun parseAndExecuteInterleavedBlocks(
        rawText: String,
        attachedItem: SchoolItem?,
        onBlockUpdate: () -> Unit
    ): List<MessageBlock> {
        val blocks = mutableListOf<MessageBlock>()
        val commandRegex = Regex("\\[KOMUT:\\s*([A-ZÇĞİÖŞÜ_]+)(?:\\s*\\|\\s*([\\s\\S]*?))?\\]")
        val matches = commandRegex.findAll(rawText).toList()

        var lastIndex = 0

        for (match in matches) {
            val textBefore = rawText.substring(lastIndex, match.range.first).trim()
            if (textBefore.isNotBlank()) {
                val cleanPreText = cleanActionSyntax(textBefore)
                if (cleanPreText.isNotBlank()) {
                    blocks.add(MessageBlock(type = BlockType.TEXT, text = cleanPreText))
                }
            }

            val cmdType = match.groupValues[1].trim()
            val rawArgs = match.groupValues.getOrNull(2)?.trim().orEmpty()
            val args = rawArgs.split("|").map { it.trim() }

            when (cmdType) {
                "DOSYA_İNCELE" -> {
                    val fileName = args.getOrNull(0) ?: attachedItem?.name ?: "dosya"
                    val targetFile = fileManager.findFile(fileName) ?: File(fileManager.currentDirectory, fileName)
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val runningTool = ToolExecution(
                        toolName = "📁 Dosya İnceleme Ajanı",
                        summary = "'$fileName' dosyası inceleniyor...",
                        inputDetail = "Hedef: $friendlyPath",
                        outputDetail = "Dosya içeriği ve medya etiketleri okunuyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val toolResult = if (targetFile.exists()) {
                        val content = fileManager.readText(targetFile)
                        runningTool.copy(
                            summary = "'$fileName' dosyası başarıyla incelendi",
                            inputDetail = "Hedef: $friendlyPath (${targetFile.length() / 1024} KB)",
                            outputDetail = content.take(2000),
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        runningTool.copy(
                            summary = "'$fileName' dosyası bulunamadı",
                            inputDetail = "Hedef: $friendlyPath",
                            outputDetail = "Dosya okul çalışma alanında bulunamadı",
                            status = ToolStatus.FAILED,
                            errorMessage = "Dosya mevcut değil"
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "GÖRSEL_MODELİ_ÇAĞIR" -> {
                    val fileName = args.getOrNull(0) ?: attachedItem?.name ?: "görsel"
                    val question = args.getOrNull(1) ?: "Please analyze this image and extract all visible text and formulas in detail"
                    val targetFile = fileManager.findFile(fileName) ?: (if (attachedItem != null) File(attachedItem.path) else File(fileManager.currentDirectory, fileName))
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val isVid = targetFile.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi", "mov")
                    val runningTool = ToolExecution(
                        toolName = "👁️ 2. Model: MiniCPM-V (Görsel Alt Ajanı)",
                        summary = "'$fileName' 2. modelce analiz ediliyor...",
                        inputDetail = "1. Model Talimatı: $question\nHedef: $friendlyPath\nModel: ${preferences.visionModelName}",
                        outputDetail = "Kilit kareler çıkarılıyor ve 2. model (MiniCPM-V) çağrılıyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val frames = if (targetFile.exists()) aiClient.extractMediaFrames(targetFile, maxFrames = 3) else emptyList()
                    val toolResult = if (frames.isNotEmpty()) {
                        val vRes = aiClient.callVisionModel(
                            serverUrl = preferences.serverUrl,
                            visionModelName = preferences.visionModelName,
                            prompt = question,
                            base64Images = frames,
                            fallbackModelName = preferences.modelName
                        )
                        if (vRes.isSuccess) {
                            runningTool.copy(
                                summary = "$fileName (${if (isVid) "Video ${frames.size} kare" else "Görsel"}) 2. modelce analiz edildi",
                                outputDetail = vRes.getOrThrow(),
                                status = ToolStatus.SUCCESS
                            )
                        } else {
                            val err = vRes.exceptionOrNull()?.localizedMessage ?: "Görsel analizi başarısız oldu"
                            runningTool.copy(
                                summary = "2. Model analizi başarısız oldu",
                                outputDetail = "Hata: $err",
                                status = ToolStatus.FAILED,
                                errorMessage = err
                            )
                        }
                    } else {
                        runningTool.copy(
                            summary = "Görsel/Video kareleri okunamadı",
                            outputDetail = "Dosya mevcut değil veya kilit kare çıkarılamadı",
                            status = ToolStatus.FAILED,
                            errorMessage = "Kare çıkarılamadı"
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "NOT_OLUŞTUR", "DOSYA_OLUŞTUR" -> {
                    val fileName = args.getOrNull(0) ?: "ders_notu.txt"
                    val content = args.getOrNull(1) ?: ""
                    val targetFile = File(fileManager.currentDirectory, fileName)
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val runningTool = ToolExecution(
                        toolName = "✍️ Not Oluşturma Ajanı",
                        summary = "'$fileName' ders notu yazılıyor...",
                        inputDetail = "Dosya: $friendlyPath",
                        outputDetail = "Dosya diske kaydediliyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val res = fileManager.createTextFile(fileName, content)
                    val toolResult = if (res.isSuccess) {
                        _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' notu oluşturuldu")
                        runningTool.copy(
                            summary = "'$fileName' ders notu oluşturuldu",
                            outputDetail = "Diske Yazılan İçerik (${content.length} karakter):\n${content.take(600)}...",
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        val err = res.exceptionOrNull()?.localizedMessage ?: "Oluşturulamadı"
                        runningTool.copy(
                            summary = "'$fileName' oluşturulamadı",
                            outputDetail = "Hata: $err",
                            status = ToolStatus.FAILED,
                            errorMessage = err
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "METİN_DÜZENLE" -> {
                    val fileName = args.getOrNull(0) ?: "dosya.txt"
                    val content = args.getOrNull(1) ?: ""
                    val targetFile = fileManager.findFile(fileName) ?: File(fileManager.currentDirectory, fileName)
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val runningTool = ToolExecution(
                        toolName = "✍️ Metin Düzenleme Ajanı",
                        summary = "'$fileName' düzenleniyor...",
                        inputDetail = "Hedef: $friendlyPath",
                        outputDetail = "Yeni içerik diske yazılıyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val res = fileManager.updateTextFile(fileName, content)
                    val toolResult = if (res.isSuccess) {
                        _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' dosyası güncellendi")
                        runningTool.copy(
                            summary = "'$fileName' dosyası başarıyla güncellendi",
                            outputDetail = "Yeni İçerik (${content.length} karakter):\n${content.take(600)}...",
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        val err = res.exceptionOrNull()?.localizedMessage ?: "Güncellenemedi"
                        runningTool.copy(
                            summary = "'$fileName' güncellenemedi",
                            outputDetail = "Hata: $err",
                            status = ToolStatus.FAILED,
                            errorMessage = err
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "KLASÖR_OLUŞTUR" -> {
                    val folderName = args.getOrNull(0) ?: "Yeni_Klasor"
                    val targetDir = File(fileManager.currentDirectory, folderName)
                    val friendlyPath = fileManager.getUserFriendlyPath(targetDir)

                    val runningTool = ToolExecution(
                        toolName = "📁 Klasör Oluşturma Ajanı",
                        summary = "'$folderName' klasörü oluşturuluyor...",
                        inputDetail = "Konum: $friendlyPath",
                        outputDetail = "Klasör fiziksel cihaz hafızasına ekleniyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val res = fileManager.createFolder(folderName)
                    val toolResult = if (res.isSuccess) {
                        _uiState.value = _uiState.value.copy(statusMessage = "'$folderName' klasörü açıldı")
                        runningTool.copy(
                            summary = "'$folderName' klasörü başarıyla açıldı",
                            outputDetail = "Fiziksel Yol: ${res.getOrThrow().absolutePath}",
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        val err = res.exceptionOrNull()?.localizedMessage ?: "Oluşturulamadı"
                        runningTool.copy(
                            summary = "'$folderName' oluşturulamadı",
                            outputDetail = "Hata: $err",
                            status = ToolStatus.FAILED,
                            errorMessage = err
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "YENİDEN_ADLANDIR" -> {
                    val oldName = args.getOrNull(0) ?: ""
                    val newName = args.getOrNull(1) ?: ""
                    val targetFile = fileManager.findFile(oldName) ?: File(fileManager.currentDirectory, oldName)

                    val runningTool = ToolExecution(
                        toolName = "🏷️ Yeniden Adlandırma Ajanı",
                        summary = "'$oldName' -> '$newName' olarak değiştiriliyor...",
                        inputDetail = "Eski: $oldName -> Yeni: $newName",
                        outputDetail = "Fiziksel depolamada ad güncelleniyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val toolResult = if (targetFile.exists()) {
                        val res = fileManager.rename(targetFile, newName)
                        if (res.isSuccess) {
                            _uiState.value = _uiState.value.copy(statusMessage = "Yeniden adlandırıldı: $newName")
                            runningTool.copy(
                                summary = "'$oldName' -> '$newName' olarak değiştirildi",
                                outputDetail = "Fiziksel Yol: ${res.getOrThrow().absolutePath}",
                                status = ToolStatus.SUCCESS
                            )
                        } else {
                            val err = res.exceptionOrNull()?.localizedMessage ?: "Yeniden adlandırılamadı"
                            runningTool.copy(
                                summary = "Yeniden adlandırma başarısız oldu",
                                outputDetail = "Hata: $err",
                                status = ToolStatus.FAILED,
                                errorMessage = err
                            )
                        }
                    } else {
                        runningTool.copy(
                            summary = "Dosya bulunamadı: $oldName",
                            outputDetail = "Hedef dosya mevcut değil",
                            status = ToolStatus.FAILED,
                            errorMessage = "Dosya bulunamadı"
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "ARA" -> {
                    val query = args.getOrNull(0) ?: ""
                    val runningTool = ToolExecution(
                        toolName = "🌐 İnternet Arama Ajanı",
                        summary = "'$query' internette taranıyor...",
                        inputDetail = "Arama Sorgusu: $query",
                        outputDetail = "Arama motoru taranıyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val results = webSearchEngine.searchWeb(query)
                    val toolResult = if (results.isNotEmpty()) {
                        runningTool.copy(
                            summary = "'$query' internette arandı (${results.size} sonuç)",
                            outputDetail = results.take(3).joinToString("\n") { "• ${it.title}: ${it.snippet.take(150)}" },
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        runningTool.copy(
                            summary = "Arama sonucu bulunamadı",
                            outputDetail = "İnternet aramasında sonuç dönmedi",
                            status = ToolStatus.FAILED,
                            errorMessage = "Sonuç bulunamadı"
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "İNDİR" -> {
                    val url = args.getOrNull(0) ?: ""
                    val name = args.getOrNull(1) ?: "indirilen_dosya"
                    downloadMedia(url, name)
                    val toolResult = ToolExecution(
                        toolName = "⬇️ Dosya İndirme Ajanı",
                        summary = "'$name' indirme işlemi başlatıldı",
                        inputDetail = "Kaynak URL: $url\nHedef: 📁 Okul Dizini/$name",
                        outputDetail = "Dosya arka planda okul klasörünüze indiriliyor...",
                        status = ToolStatus.RUNNING
                    )
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = toolResult))
                    onBlockUpdate()
                }
            }

            lastIndex = match.range.last + 1
        }

        if (lastIndex < rawText.length) {
            val textAfter = rawText.substring(lastIndex).trim()
            if (textAfter.isNotBlank()) {
                val cleanPostText = cleanActionSyntax(textAfter)
                if (cleanPostText.isNotBlank()) {
                    blocks.add(MessageBlock(type = BlockType.TEXT, text = cleanPostText))
                }
            }
        }

        // If user attached an item and no explicit tool command was emitted, insert the tool card naturally
        val hasTool = blocks.any { it.type == BlockType.TOOL }
        if (!hasTool && attachedItem != null) {
            val ext = attachedItem.extension.lowercase()
            val isVidOrImg = ext in listOf("jpg", "jpeg", "png", "webp", "mp4", "mkv", "webm", "avi", "mov")
            val isAudio = ext in listOf("mp3", "wav", "m4a", "ogg", "aac", "flac")

            if (isVidOrImg) {
                val frames = aiClient.extractMediaFrames(File(attachedItem.path), maxFrames = 3)
                val analysisText = if (frames.isNotEmpty()) {
                    val isVid = ext in listOf("mp4", "mkv", "webm", "avi", "mov")
                    val vRes = aiClient.callVisionModel(
                        serverUrl = preferences.serverUrl,
                        visionModelName = preferences.visionModelName,
                        prompt = "Extract and describe all educational content, text, questions, and formulas accurately in English",
                        base64Images = frames
                    )
                    vRes.getOrDefault("Görsel/Video içeriği analiz edildi.")
                } else "Kareler çıkarılamadı"

                val visionTool = ToolExecution(
                    toolName = "👁️ 2. Model: MiniCPM-V (Görsel Alt Ajanı)",
                    summary = "${attachedItem.name} 2. modelce doğrudan analiz edildi",
                    inputDetail = "Dosya Girdisi: ${attachedItem.path} (${attachedItem.formattedSize})\nModel: ${preferences.visionModelName}",
                    outputDetail = analysisText,
                    status = ToolStatus.SUCCESS
                )
                if (blocks.isNotEmpty() && blocks[0].type == BlockType.TEXT) {
                    blocks.add(1, MessageBlock(type = BlockType.TOOL, tool = visionTool))
                } else {
                    blocks.add(0, MessageBlock(type = BlockType.TOOL, tool = visionTool))
                }
            } else if (isAudio) {
                val details = fileManager.extractAudioMediaDetails(File(attachedItem.path))
                val audioTool = ToolExecution(
                    toolName = "👁️ 2. Model: MiniCPM-V (Ses & Medya Alt Ajanı)",
                    summary = "${attachedItem.name} 2. modelce transkript ve analize alındı",
                    inputDetail = "Dosya Girdisi: ${attachedItem.path} (${attachedItem.formattedSize})\nModel: ${preferences.visionModelName}",
                    outputDetail = details,
                    status = ToolStatus.SUCCESS
                )
                if (blocks.isNotEmpty() && blocks[0].type == BlockType.TEXT) {
                    blocks.add(1, MessageBlock(type = BlockType.TOOL, tool = audioTool))
                } else {
                    blocks.add(0, MessageBlock(type = BlockType.TOOL, tool = audioTool))
                }
            }
        }

        // Fallback: If no blocks exist
        if (blocks.isEmpty()) {
            val cleanAll = cleanActionSyntax(rawText)
            blocks.add(MessageBlock(type = BlockType.TEXT, text = if (cleanAll.isNotBlank()) cleanAll else "İşlem tamamlandı."))
        }

        return blocks
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
