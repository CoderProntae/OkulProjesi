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

data class ToolExecution(
    val id: String = System.currentTimeMillis().toString() + "_" + (0..9999).random(),
    val toolName: String, // e.g. "Dosya İnceleme", "Not Düzenleme", "Klasör Oluşturma", "İnternet Araması", "Dosya İndirme"
    val summary: String,
    val inputDetail: String,
    val outputDetail: String,
    val status: ToolStatus = ToolStatus.SUCCESS
)

data class ChatMessage(
    val id: String = System.currentTimeMillis().toString() + "_" + (0..9999).random(),
    val role: MessageRole,
    val content: String,
    val thinkingContent: String? = null,
    val toolExecutions: List<ToolExecution> = emptyList(),
    val attachedFile: SchoolItem? = null,
    val isWebSearch: Boolean = false,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
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
