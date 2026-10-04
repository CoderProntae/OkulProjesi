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
import com.example.ai.data.ChatSession
import com.example.ai.data.ChatSessionInfo
import com.example.ai.data.ChatSessionManager

data class AiUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val attachedItem: SchoolItem? = null,
    val activeToolName: String? = null,
    val serverStatus: AiServerStatus = AiServerStatus(),
    val audioServerStatus: AiServerStatus = AiServerStatus(),
    val statusMessage: String? = null,
    val downloadProgress: Int? = null,
    val showSettingsDialog: Boolean = false,
    val showAttachmentPicker: Boolean = false,
    val showChatHistorySheet: Boolean = false,
    val currentSessionId: String = "",
    val currentSessionTitle: String = "Yeni Sohbet",
    val sessionsList: List<ChatSessionInfo> = emptyList(),
    val availableSchoolFiles: List<SchoolItem> = emptyList(),
    val serverUrl: String = "",
    val modelName: String = "",
    val visionModelName: String = "minicpm-v:latest",
    val visionServerUrl: String = "",
    val audioServerUrl: String = "http://192.168.1.100:8000",
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
    val sessionManager = ChatSessionManager(application)

    private val _uiState = MutableStateFlow(
        AiUiState(
            serverUrl = preferences.serverUrl,
            modelName = preferences.modelName,
            visionModelName = preferences.visionModelName,
            visionServerUrl = preferences.visionServerUrl,
            audioServerUrl = preferences.audioServerUrl,
            supremePrompt = preferences.supremePrompt,
            isThinkingEnabled = preferences.isThinkingEnabled,
            isWebSearchEnabled = preferences.isWebSearchEnabled,
            temperature = preferences.temperature,
            messages = emptyList()
        )
    )
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
        testServerConnection()
    }

    private fun createWelcomeMessage(): ChatMessage {
        return ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Merhaba! Ben yerel yapay zeka asistanınız ve okul çalışma ortamınızın baş danışmanıyım.\n\n" +
                "🎙️ **Whisper Ses Döküm Ajanı**: Ses kayıtlarınızı doğrudan transkript eder.\n" +
                "👁️ **2. Model (MiniCPM-V Görsel Ajanı)**: Resimleri, soruları ve ders videolarını analiz eder.\n" +
                "📁 **Canlı Disk Ajanı**: Dosya okur, not oluşturur, metin düzenler ve dosyaları siler.\n" +
                "🌐 **İnternet Arama Ajanı**: Canlı web taraması yapar.\n" +
                "📝 **Sınav & Test Simülatörü**: Derslerinizden anında deneme sınavı üretir.\n\n" +
                "Üstteki 📚 Sohbetler menüsünden geçmiş tüm oturumlarınıza dilediğiniz an erişebilirsiniz. Nasıl yardımcı olabilirim?"
        )
    }

    fun loadSessions() {
        viewModelScope.launch {
            val sessions = sessionManager.listSessions()
            if (sessions.isEmpty()) {
                val newSession = ChatSession(title = "Yeni Sohbet")
                sessionManager.saveSession(newSession)
                _uiState.value = _uiState.value.copy(
                    currentSessionId = newSession.id,
                    currentSessionTitle = newSession.title,
                    sessionsList = listOf(
                        ChatSessionInfo(
                            id = newSession.id,
                            title = newSession.title,
                            createdAt = newSession.createdAt,
                            updatedAt = newSession.updatedAt,
                            messageCount = 0,
                            previewText = ""
                        )
                    ),
                    messages = listOf(createWelcomeMessage())
                )
            } else {
                val latestInfo = sessions.first()
                val latest = sessionManager.loadSession(latestInfo.id)
                val msgs = if (latest != null && latest.messages.isNotEmpty()) latest.messages else listOf(createWelcomeMessage())
                _uiState.value = _uiState.value.copy(
                    currentSessionId = latestInfo.id,
                    currentSessionTitle = latestInfo.title,
                    sessionsList = sessions,
                    messages = msgs
                )
            }
        }
    }

    fun startNewChat() {
        viewModelScope.launch {
            val newSession = ChatSession(title = "Yeni Sohbet")
            sessionManager.saveSession(newSession)
            val updatedList = sessionManager.listSessions()
            _uiState.value = _uiState.value.copy(
                currentSessionId = newSession.id,
                currentSessionTitle = newSession.title,
                sessionsList = updatedList,
                messages = listOf(createWelcomeMessage()),
                attachedItem = null,
                showChatHistorySheet = false
            )
        }
    }

    fun selectChatSession(id: String) {
        viewModelScope.launch {
            val session = sessionManager.loadSession(id)
            if (session != null) {
                _uiState.value = _uiState.value.copy(
                    currentSessionId = session.id,
                    currentSessionTitle = session.title,
                    messages = if (session.messages.isNotEmpty()) session.messages else listOf(createWelcomeMessage()),
                    attachedItem = null,
                    showChatHistorySheet = false
                )
            }
        }
    }

    fun deleteChatSession(id: String) {
        viewModelScope.launch {
            sessionManager.deleteSession(id)
            val updatedList = sessionManager.listSessions()
            if (_uiState.value.currentSessionId == id) {
                if (updatedList.isNotEmpty()) {
                    selectChatSession(updatedList.first().id)
                } else {
                    startNewChat()
                }
            } else {
                _uiState.value = _uiState.value.copy(sessionsList = updatedList)
            }
        }
    }

    fun setShowChatHistorySheet(show: Boolean) {
        _uiState.value = _uiState.value.copy(showChatHistorySheet = show)
        if (show) {
            viewModelScope.launch {
                val list = sessionManager.listSessions()
                _uiState.value = _uiState.value.copy(sessionsList = list)
            }
        }
    }

    private fun saveCurrentSession(messages: List<ChatMessage>) {
        val sId = _uiState.value.currentSessionId
        if (sId.isBlank()) return
        var title = _uiState.value.currentSessionTitle
        if (title == "Yeni Sohbet") {
            val firstUser = messages.firstOrNull { it.role == MessageRole.USER }
            if (firstUser != null && firstUser.content.isNotBlank()) {
                title = firstUser.content.take(35).replace("\n", " ").trim()
                if (firstUser.attachedFile != null) {
                    title = "📎 ${firstUser.attachedFile.name}: $title"
                }
                _uiState.value = _uiState.value.copy(currentSessionTitle = title)
            }
        }
        viewModelScope.launch {
            sessionManager.saveSession(
                ChatSession(
                    id = sId,
                    title = title,
                    updatedAt = System.currentTimeMillis(),
                    messages = messages
                )
            )
            val list = sessionManager.listSessions()
            _uiState.value = _uiState.value.copy(sessionsList = list)
        }
    }

    fun testServerConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                serverStatus = _uiState.value.serverStatus.copy(errorMessage = null)
            )
            val status = aiClient.testConnection(preferences.serverUrl)
            var newModelName = preferences.modelName
            var statusMsg: String? = null

            var newVisionModelName = preferences.visionModelName

            if (status.isConnected && status.availableModels.isNotEmpty()) {
                val exactMatch = status.availableModels.firstOrNull { it.equals(preferences.modelName, ignoreCase = true) }
                if (exactMatch != null) {
                    newModelName = exactMatch
                    preferences.modelName = exactMatch
                } else {
                    val matchingInstalled = status.availableModels.firstOrNull { it.contains("abliterate", ignoreCase = true) || it.contains("uncensored", ignoreCase = true) }
                        ?: status.availableModels.firstOrNull { it.contains("qwen2.5:7b", ignoreCase = true) || it.equals("qwen2.5", ignoreCase = true) }
                        ?: status.availableModels.firstOrNull { it.contains("llama3.1", ignoreCase = true) || it.contains("gemma2", ignoreCase = true) }
                        ?: status.availableModels.firstOrNull { it.contains("qwen", ignoreCase = true) && !it.contains("coder", ignoreCase = true) }
                        ?: status.availableModels.first()
                    newModelName = matchingInstalled
                    preferences.modelName = matchingInstalled
                    statusMsg = "Bilgisayarınızdaki '$matchingInstalled' modeli otomatik seçildi."
                }

                val exactVision = status.availableModels.firstOrNull { it.equals(preferences.visionModelName, ignoreCase = true) }
                if (exactVision != null) {
                    newVisionModelName = exactVision
                    preferences.visionModelName = exactVision
                } else {
                    val matchingVision = status.availableModels.firstOrNull {
                        it.contains("minicpm", ignoreCase = true) || it.contains("vision", ignoreCase = true) || it.contains("llava", ignoreCase = true)
                    }
                    if (matchingVision != null) {
                        newVisionModelName = matchingVision
                        preferences.visionModelName = matchingVision
                    }
                }
            }

            val audioStatus = aiClient.testAudioConnection(preferences.audioServerUrl)

            _uiState.value = _uiState.value.copy(
                serverStatus = status,
                audioServerStatus = audioStatus,
                modelName = newModelName,
                visionModelName = newVisionModelName,
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
                        serverUrl = preferences.effectiveVisionServerUrl,
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
                        audioServerUrl = preferences.audioServerUrl,
                        prompt = "Transcribe all dialogue and educational speech from '${attached.name}' accurately."
                    )
                    val details = fileManager.extractAudioMediaDetails(audioFile)
                    if (rawAudioResult.isSuccess) {
                        val transcript = rawAudioResult.getOrThrow()
                        val whisperTool = ToolExecution(
                            toolName = "🎙️ Whisper Ses Ajanı",
                            summary = "'${attached.name}' ses dökümü tamamlandı",
                            inputDetail = "Dosya: ${attached.path} (${attached.formattedSize})",
                            outputDetail = "Transkript (${transcript.length} karakter):\n${transcript.take(1200)}...",
                            status = ToolStatus.SUCCESS
                        )
                        messageBlocks.add(MessageBlock(type = BlockType.TOOL, tool = whisperTool))
                        attachedContent = "[WHISPER TARAFINDAN ÇIKARILAN GERÇEK SES TRANSKRİPTİ - Dosya: ${attached.path}]:\n$transcript\n\n[DİKKAT]: Ses transkripti yukarıda hazır olarak verilmiştir. Kullanıcının sorusuna doğrudan bu transkripti kullanarak cevap ver. Asla 'dosya ekleyin' veya 'komut çalıştırın' deme."
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

                val existingTools = messageBlocks.mapNotNull { it.tool }
                val mergedBlocks = mutableListOf<MessageBlock>()
                if (!thinkingText.isNullOrBlank()) {
                    mergedBlocks.add(MessageBlock(type = BlockType.THINKING, text = thinkingText))
                }
                // Keep earlier tools (e.g. Whisper that ran at upload time)
                for (t in existingTools) {
                    if (parsedBlocks.none { it.tool?.summary == t.summary }) {
                        mergedBlocks.add(MessageBlock(type = BlockType.TOOL, tool = t))
                    }
                }
                mergedBlocks.addAll(parsedBlocks)
                messageBlocks.clear()
                messageBlocks.addAll(mergedBlocks)

                // If tools were executed, do a follow-up synthesis call so the model gives the complete final answer
                val executedTools = messageBlocks.mapNotNull { it.tool }
                if (executedTools.isNotEmpty()) {
                    val toolResultsSynthesis = executedTools.joinToString("\n\n") { tool ->
                        "[ARAÇ ÇIKTISI: ${tool.toolName}]:\n${tool.outputDetail}"
                    }
                    val followUpSystem = buildString {
                        append("[EN ÜST KADEME EMİR: NİHAİ DERS YANITINI VE TRANSKRİPTİNİ YAZ]\n")
                        append("Sen kullanıcının kişisel Okul Asistanısın.\n")
                        append("Kullanıcı senden ders/ses dosyasının dökümünü, transkriptini veya özetini istedi.\n")
                        append("Arka plandaki araçlar çalıştırıldı ve elde edilen gerçek transkript/içerik aşağıdadır:\n\n")
                        append(toolResultsSynthesis)
                        append("\n\n[KESİN KURALLAR - ASLA İHLAL EDİLEMEZ]:\n")
                        append("1. Yukarıdaki gerçek transkripti ve bilgileri doğrudan kullanarak kullanıcının istediği transkript metnini eksiksiz, okunaklı ve düzenli olarak yaz.\n")
                        append("2. KESİNLİKLE YASAK: ASLA 'Lütfen ses dosyanızı ekleyin', 'dosya adı belirtin', 'örneğin komut dosya incele çalıştırın' DEME! Sen bir asistansın; araçlar zaten çalıştırıldı ve sonuçlar elindedir.\n")
                        append("3. Kullanıcıya komut sözdizimi öğretme veya örnek komut verme. Doğrudan transkript dökümünü ve ders özetini Türkçe olarak ver.\n")
                    }

                    val followUpMessages = _uiState.value.messages.dropLast(1).toMutableList().apply {
                        add(ChatMessage(role = MessageRole.USER, content = "Dosyayı inceledin ve transkript elinde. Lütfen şimdi transkripti eksiksiz dök ve ders özetimi ver."))
                    }

                    val synthBlockIndex = messageBlocks.size
                    messageBlocks.add(MessageBlock(type = BlockType.TEXT, text = "\n\nCevap hazırlanıyor..."))
                    updateAssistantState(statusMsg = "Model sonuçları analiz ediyor...", activeTool = "🧠 Nihai Yanıt")

                    val synthAccum = StringBuilder()
                    val synthResult = aiClient.sendChat(
                        serverUrl = preferences.serverUrl,
                        modelName = activeModel,
                        visionModelName = activeVisionModel,
                        supremePrompt = followUpSystem,
                        messages = followUpMessages,
                        temperature = preferences.temperature,
                        isThinkingEnabled = preferences.isThinkingEnabled,
                        onChunk = { chunk ->
                            synthAccum.append(chunk)
                            val clean = cleanActionSyntax(synthAccum.toString())
                            messageBlocks[synthBlockIndex] = MessageBlock(type = BlockType.TEXT, text = clean)
                            updateAssistantState()
                        }
                    )
                    if (synthResult.isSuccess) {
                        val (finalMain, _) = synthResult.getOrThrow()
                        messageBlocks[synthBlockIndex] = MessageBlock(type = BlockType.TEXT, text = cleanActionSyntax(finalMain))
                    }
                }

                updateAssistantState(statusMsg = null, activeTool = null)

                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    statusMessage = null,
                    activeToolName = null
                )
                saveCurrentSession(_uiState.value.messages)
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
                        val isAudio = targetFile.extension.lowercase() in listOf("mp3", "wav", "m4a", "ogg", "aac", "flac")
                        val isVideo = targetFile.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi", "mov")

                        val content = if (isAudio) {
                            val audioRes = aiClient.processRawAudioInput(
                                serverUrl = preferences.serverUrl,
                                audioFile = targetFile,
                                audioServerUrl = preferences.audioServerUrl
                            )
                            if (audioRes.isSuccess) {
                                "[Whisper Transkripti]:\n" + audioRes.getOrThrow()
                            } else {
                                fileManager.extractAudioMediaDetails(targetFile)
                            }
                        } else if (isVideo) {
                            val frames = aiClient.extractMediaFrames(targetFile, maxFrames = 3)
                            val vRes = aiClient.callVisionModel(
                                serverUrl = preferences.effectiveVisionServerUrl,
                                visionModelName = preferences.visionModelName,
                                prompt = "Analyze this video in detail",
                                base64Images = frames,
                                fallbackModelName = preferences.modelName
                            )
                            if (vRes.isSuccess) "[2. Model Görsel Raporu]:\n" + vRes.getOrThrow() else fileManager.extractAudioMediaDetails(targetFile)
                        } else {
                            fileManager.readText(targetFile)
                        }

                        runningTool.copy(
                            summary = "'$fileName' dosyası başarıyla incelendi",
                            inputDetail = "Hedef: $friendlyPath (${targetFile.length() / 1024} KB)",
                            outputDetail = content.take(3000),
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

                    val ext = targetFile.extension.lowercase()
                    val isAudio = ext in listOf("mp3", "wav", "m4a", "ogg", "aac", "flac")
                    val isText = fileManager.isTextFile(targetFile)

                    if (isAudio) {
                        val rejectedTool = ToolExecution(
                            toolName = "👁️ 2. Model: MiniCPM-V (Görsel Alt Ajanı)",
                            summary = "2. Model (Görsel Ajanı) ses dosyası için çağrılamaz",
                            inputDetail = "Dosya: $friendlyPath (Ses Dosyası)",
                            outputDetail = "UYARI: '$fileName' bir ses dosyasıdır. 2. Model yalnızca resim ve video analiz eder. Ses transkripti Whisper tarafından zaten çıkarılmıştır.",
                            status = ToolStatus.FAILED,
                            errorMessage = "Ses dosyaları 2. model ile işlenemez"
                        )
                        blocks.add(MessageBlock(type = BlockType.TOOL, tool = rejectedTool))
                        onBlockUpdate()
                        lastIndex = match.range.last + 1
                        continue
                    }

                    val isVid = ext in listOf("mp4", "mkv", "webm", "avi", "mov")
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
                            serverUrl = preferences.effectiveVisionServerUrl,
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

                "SES_DÖKÜMÜ" -> {
                    val fileName = args.getOrNull(0) ?: attachedItem?.name ?: "ses.mp3"
                    val targetFile = fileManager.findFile(fileName) ?: (if (attachedItem != null) File(attachedItem.path) else File(fileManager.currentDirectory, fileName))
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val runningTool = ToolExecution(
                        toolName = "🎙️ Whisper Ses Döküm Ajanı",
                        summary = "'$fileName' ses dökümü yapılıyor...",
                        inputDetail = "Hedef: $friendlyPath\nSunucu: ${preferences.audioServerUrl}",
                        outputDetail = "Whisper modeli ses dosyasını dinliyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val toolResult = if (targetFile.exists()) {
                        val audioRes = aiClient.processRawAudioInput(
                            serverUrl = preferences.serverUrl,
                            audioFile = targetFile,
                            audioServerUrl = preferences.audioServerUrl
                        )
                        if (audioRes.isSuccess) {
                            val transcript = audioRes.getOrThrow()
                            runningTool.copy(
                                summary = "'$fileName' transkripti tamamlandı (${transcript.length} karakter)",
                                outputDetail = transcript,
                                status = ToolStatus.SUCCESS
                            )
                        } else {
                            val err = audioRes.exceptionOrNull()?.localizedMessage ?: "Ses çözülemedi"
                            runningTool.copy(
                                summary = "Ses dökümü başarısız oldu",
                                outputDetail = "Hata: $err",
                                status = ToolStatus.FAILED,
                                errorMessage = err
                            )
                        }
                    } else {
                        runningTool.copy(
                            summary = "Ses dosyası bulunamadı",
                            outputDetail = "Dosya diskte mevcut değil: $friendlyPath",
                            status = ToolStatus.FAILED,
                            errorMessage = "Dosya bulunamadı"
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "DOSYA_SİL" -> {
                    val fileName = args.getOrNull(0) ?: ""
                    val targetFile = fileManager.findFile(fileName) ?: File(fileManager.currentDirectory, fileName)
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val runningTool = ToolExecution(
                        toolName = "🗑️ Dosya Silme Ajanı",
                        summary = "'$fileName' dosyası diskten siliniyor...",
                        inputDetail = "Hedef: $friendlyPath",
                        outputDetail = "Fiziksel depolamadan kalıcı olarak siliniyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val delRes = fileManager.deleteFileByName(fileName)
                    val toolResult = if (delRes.isSuccess) {
                        _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' diskten silindi")
                        runningTool.copy(
                            summary = "'$fileName' dosyası başarıyla silindi",
                            outputDetail = "Fiziksel dosya silindi ve canlı depolama güncellendi.",
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        val err = delRes.exceptionOrNull()?.localizedMessage ?: "Silinemedi"
                        runningTool.copy(
                            summary = "'$fileName' silinemedi",
                            outputDetail = "Hata: $err",
                            status = ToolStatus.FAILED,
                            errorMessage = err
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "DOSYA_OKU" -> {
                    val fileName = args.getOrNull(0) ?: ""
                    val targetFile = fileManager.findFile(fileName) ?: File(fileManager.currentDirectory, fileName)
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val runningTool = ToolExecution(
                        toolName = "📖 Dosya Okuma Ajanı",
                        summary = "'$fileName' okunuyor...",
                        inputDetail = "Hedef: $friendlyPath",
                        outputDetail = "İçerik okunuyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val toolResult = if (targetFile.exists()) {
                        val text = fileManager.readText(targetFile)
                        runningTool.copy(
                            summary = "'$fileName' başarıyla okundu (${text.length} karakter)",
                            outputDetail = text.take(3000),
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        runningTool.copy(
                            summary = "Dosya bulunamadı: $fileName",
                            outputDetail = "Dosya diskte mevcut değil",
                            status = ToolStatus.FAILED,
                            errorMessage = "Dosya bulunamadı"
                        )
                    }
                    blocks[blockIdx] = MessageBlock(type = BlockType.TOOL, tool = toolResult)
                    onBlockUpdate()
                }

                "TEST_OLUŞTUR" -> {
                    val lessonName = args.getOrNull(0) ?: "Deneme_Sinavi"
                    val count = args.getOrNull(1) ?: "10"
                    val testContent = args.getOrNull(2) ?: ""
                    val fileName = "${lessonName.replace(" ", "_")}_Test.txt"
                    val targetFile = File(fileManager.currentDirectory, fileName)
                    val friendlyPath = fileManager.getUserFriendlyPath(targetFile)

                    val runningTool = ToolExecution(
                        toolName = "📝 Sınav & Test Simülatörü",
                        summary = "'$fileName' deneme sınavı hazırlanıyor...",
                        inputDetail = "Ders: $lessonName | Soru Sayısı: $count\nHedef: $friendlyPath",
                        outputDetail = "Sorular ve cevap anahtarı çalışma alanına kaydediliyor...",
                        status = ToolStatus.RUNNING
                    )
                    val blockIdx = blocks.size
                    blocks.add(MessageBlock(type = BlockType.TOOL, tool = runningTool))
                    onBlockUpdate()

                    val fullContent = "=== $lessonName DENEME SINAVI ($count Soru) ===\nOluşturulma: ${java.util.Date()}\n\n$testContent"
                    val createRes = fileManager.createTextFile(fileName, fullContent)
                    val toolResult = if (createRes.isSuccess) {
                        _uiState.value = _uiState.value.copy(statusMessage = "'$fileName' sınav testi oluşturuldu")
                        runningTool.copy(
                            summary = "'$fileName' sınav testi başarıyla kaydedildi",
                            outputDetail = "Test İçeriği:\n${fullContent.take(800)}...",
                            status = ToolStatus.SUCCESS
                        )
                    } else {
                        val err = createRes.exceptionOrNull()?.localizedMessage ?: "Kaydedilemedi"
                        runningTool.copy(
                            summary = "Sınav oluşturulamadı",
                            outputDetail = "Hata: $err",
                            status = ToolStatus.FAILED,
                            errorMessage = err
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
            .replace(Regex("\\[KOMUT:\\s*TEST_OLUŞTUR\\s*\\|\\s*.*?\\s*\\|\\s*.*?\\s*\\|\\s*[\\s\\S]*?\\]"), "*(Sınav testi çalışma alanına kaydedildi)*")
            .replace(Regex("\\[KOMUT:\\s*DOSYA_İNCELE\\s*\\|\\s*.*?\\]"), "*(Dosya incelendi)*")
            .replace(Regex("\\[KOMUT:\\s*SES_DÖKÜMÜ\\s*\\|\\s*.*?\\]"), "*(Ses dökümü tamamlandı)*")
            .replace(Regex("\\[KOMUT:\\s*DOSYA_SİL\\s*\\|\\s*.*?\\]"), "*(Dosya silindi)*")
            .replace(Regex("\\[KOMUT:\\s*DOSYA_OKU\\s*\\|\\s*.*?\\]"), "*(Dosya okundu)*")
            .replace(Regex("\\[KOMUT:\\s*METİN_DÜZENLE\\s*\\|\\s*.*?\\s*\\|\\s*[\\s\\S]*?\\]"), "*(Dosya içeriği başarıyla güncellendi)*")
            .replace(Regex("\\[KOMUT:\\s*KLASÖR_OLUŞTUR\\s*\\|\\s*.*?\\]"), "*(Yeni okul klasörü açıldı)*")
            .replace(Regex("\\[KOMUT:\\s*YENİDEN_ADLANDIR\\s*\\|\\s*.*?\\s*\\|\\s*.*?\\]"), "*(Dosya adı başarıyla güncellendi)*")
            .replace(Regex("\\[KOMUT:\\s*İNDİR\\s*\\|\\s*.*?\\s*\\|\\s*.*?\\]"), "*(İndirme işlemi başlatıldı)*")
            .replace(Regex("\\[KOMUT:\\s*ARA\\s*\\|\\s*.*?\\]"), "*(İnternet araması yapıldı)*")
            .replace(Regex("\\[KOMUT:\\s*GÖRSEL_MODELİ_ÇAĞIR\\s*\\|\\s*.*?\\s*\\|\\s*.*?\\]"), "*(2. Model ile görsel analizi tamamlandı)*")
            .replace(Regex("\\[KOMUT:[^\\]]+\\]"), "")
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
        visionServerUrl: String = "",
        audioServerUrl: String = "http://192.168.1.100:8000",
        supremePrompt: String,
        isThinking: Boolean,
        isWebSearch: Boolean,
        temperature: Float
    ) {
        preferences.serverUrl = serverUrl
        preferences.modelName = modelName
        preferences.visionModelName = visionModelName
        preferences.visionServerUrl = visionServerUrl
        preferences.audioServerUrl = audioServerUrl
        preferences.supremePrompt = supremePrompt
        preferences.isThinkingEnabled = isThinking
        preferences.isWebSearchEnabled = isWebSearch
        preferences.temperature = temperature

        _uiState.value = _uiState.value.copy(
            serverUrl = preferences.serverUrl,
            modelName = preferences.modelName,
            visionModelName = preferences.visionModelName,
            visionServerUrl = preferences.visionServerUrl,
            audioServerUrl = preferences.audioServerUrl,
            supremePrompt = preferences.supremePrompt,
            isThinkingEnabled = preferences.isThinkingEnabled,
            isWebSearchEnabled = preferences.isWebSearchEnabled,
            temperature = preferences.temperature,
            showSettingsDialog = false,
            statusMessage = "Yapay zeka ve sunucu ayarları kaydedildi!"
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
