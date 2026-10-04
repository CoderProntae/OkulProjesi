package com.example.ai.model

import com.example.model.SchoolItem

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class ToolStatus {
    RUNNING,
    SUCCESS,
    FAILED
}

enum class BlockType {
    TEXT,
    THINKING,
    TOOL
}

data class ToolExecution(
    val id: String = System.currentTimeMillis().toString() + "_" + (0..9999).random(),
    val toolName: String, // e.g. "👁️ 2. Model: MiniCPM-V (Görsel Alt Ajanı)", "📁 Dosya İnceleme Ajanı"
    val summary: String,
    val inputDetail: String,
    val outputDetail: String,
    val status: ToolStatus = ToolStatus.SUCCESS,
    val errorMessage: String? = null
)

data class MessageBlock(
    val id: String = System.currentTimeMillis().toString() + "_" + (0..9999).random(),
    val type: BlockType,
    val text: String = "",
    val tool: ToolExecution? = null
)

data class ChatMessage(
    val id: String = System.currentTimeMillis().toString() + "_" + (0..9999).random(),
    val role: MessageRole,
    val content: String,
    val thinkingContent: String? = null,
    val toolExecutions: List<ToolExecution> = emptyList(),
    val blocks: List<MessageBlock> = emptyList(),
    val attachedFile: SchoolItem? = null,
    val isWebSearch: Boolean = false,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val modelName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

data class AiServerStatus(
    val isConnected: Boolean = false,
    val latencyMs: Long = 0L,
    val availableModels: List<String> = emptyList(),
    val errorMessage: String? = null
)
