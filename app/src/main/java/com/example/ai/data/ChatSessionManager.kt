package com.example.ai.data

import android.content.Context
import com.example.ai.model.BlockType
import com.example.ai.model.ChatMessage
import com.example.ai.model.MessageBlock
import com.example.ai.model.MessageRole
import com.example.ai.model.ToolExecution
import com.example.ai.model.ToolStatus
import com.example.model.ItemType
import com.example.model.SchoolItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatSessionInfo(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val previewText: String
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Yeni Sohbet",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
)

class ChatSessionManager(private val context: Context) {

    private val sessionsDir: File by lazy {
        val dir = File(context.filesDir, "chat_sessions")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    suspend fun listSessions(): List<ChatSessionInfo> = withContext(Dispatchers.IO) {
        val files = sessionsDir.listFiles() ?: return@withContext emptyList()
        val list = mutableListOf<ChatSessionInfo>()

        for (file in files) {
            if (file.extension == "json") {
                try {
                    val raw = file.readText(Charsets.UTF_8)
                    val json = JSONObject(raw)
                    val id = json.optString("id", file.nameWithoutExtension)
                    val title = json.optString("title", "İsimsiz Sohbet")
                    val createdAt = json.optLong("createdAt", file.lastModified())
                    val updatedAt = json.optLong("updatedAt", file.lastModified())
                    val msgsArr = json.optJSONArray("messages")
                    val msgCount = msgsArr?.length() ?: 0
                    var preview = ""
                    if (msgCount > 0) {
                        val lastMsg = msgsArr?.optJSONObject(msgCount - 1)
                        preview = lastMsg?.optString("content", "")?.take(60).orEmpty()
                    }
                    list.add(
                        ChatSessionInfo(
                            id = id,
                            title = title,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                            messageCount = msgCount,
                            previewText = preview
                        )
                    )
                } catch (_: Exception) {}
            }
        }
        list.sortedByDescending { it.updatedAt }
    }

    suspend fun loadSession(id: String): ChatSession? = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "$id.json")
        if (!file.exists()) return@withContext null
        try {
            val raw = file.readText(Charsets.UTF_8)
            val json = JSONObject(raw)
            val title = json.optString("title", "Sohbet")
            val createdAt = json.optLong("createdAt", file.lastModified())
            val updatedAt = json.optLong("updatedAt", file.lastModified())

            val msgsArr = json.optJSONArray("messages") ?: JSONArray()
            val messages = mutableListOf<ChatMessage>()

            for (i in 0 until msgsArr.length()) {
                val mObj = msgsArr.optJSONObject(i) ?: continue
                val mId = mObj.optString("id", UUID.randomUUID().toString())
                val roleStr = mObj.optString("role", "USER")
                val role = when (roleStr) {
                    "ASSISTANT" -> MessageRole.ASSISTANT
                    "SYSTEM" -> MessageRole.SYSTEM
                    else -> MessageRole.USER
                }
                val content = mObj.optString("content", "")
                val thinking = mObj.optString("thinkingContent", null)
                val modelName = mObj.optString("modelName", null)
                val timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
                val isError = mObj.optBoolean("isError", false)

                // Tool executions
                val toolExecs = mutableListOf<ToolExecution>()
                val toolsArr = mObj.optJSONArray("toolExecutions")
                if (toolsArr != null) {
                    for (t in 0 until toolsArr.length()) {
                        val tObj = toolsArr.optJSONObject(t) ?: continue
                        toolExecs.add(
                            ToolExecution(
                                id = tObj.optString("id", UUID.randomUUID().toString()),
                                toolName = tObj.optString("toolName", "Araç"),
                                summary = tObj.optString("summary", ""),
                                inputDetail = tObj.optString("inputDetail", ""),
                                outputDetail = tObj.optString("outputDetail", ""),
                                status = when (tObj.optString("status")) {
                                    "FAILED" -> ToolStatus.FAILED
                                    "RUNNING" -> ToolStatus.RUNNING
                                    else -> ToolStatus.SUCCESS
                                },
                                errorMessage = tObj.optString("errorMessage", null)
                            )
                        )
                    }
                }

                // Blocks
                val blocks = mutableListOf<MessageBlock>()
                val blocksArr = mObj.optJSONArray("blocks")
                if (blocksArr != null) {
                    for (b in 0 until blocksArr.length()) {
                        val bObj = blocksArr.optJSONObject(b) ?: continue
                        val bTypeStr = bObj.optString("type", "TEXT")
                        val bType = when (bTypeStr) {
                            "THINKING" -> BlockType.THINKING
                            "TOOL" -> BlockType.TOOL
                            else -> BlockType.TEXT
                        }
                        val bText = bObj.optString("text", "")
                        var bTool: ToolExecution? = null
                        val btObj = bObj.optJSONObject("tool")
                        if (btObj != null) {
                            bTool = ToolExecution(
                                id = btObj.optString("id", UUID.randomUUID().toString()),
                                toolName = btObj.optString("toolName", "Araç"),
                                summary = btObj.optString("summary", ""),
                                inputDetail = btObj.optString("inputDetail", ""),
                                outputDetail = btObj.optString("outputDetail", ""),
                                status = when (btObj.optString("status")) {
                                    "FAILED" -> ToolStatus.FAILED
                                    "RUNNING" -> ToolStatus.RUNNING
                                    else -> ToolStatus.SUCCESS
                                },
                                errorMessage = btObj.optString("errorMessage", null)
                            )
                        }
                        blocks.add(
                            MessageBlock(
                                id = bObj.optString("id", UUID.randomUUID().toString()),
                                type = bType,
                                text = bText,
                                tool = bTool
                            )
                        )
                    }
                }

                // Attached file
                var attachedItem: SchoolItem? = null
                val attObj = mObj.optJSONObject("attachedFile")
                if (attObj != null) {
                    attachedItem = SchoolItem(
                        id = attObj.optString("id", ""),
                        name = attObj.optString("name", "dosya"),
                        path = attObj.optString("path", ""),
                        isDirectory = attObj.optBoolean("isDirectory", false),
                        sizeBytes = attObj.optLong("sizeBytes", 0),
                        lastModified = attObj.optLong("lastModified", 0),
                        extension = attObj.optString("extension", ""),
                        itemType = ItemType.fromExtension(attObj.optString("extension", ""), attObj.optBoolean("isDirectory", false))
                    )
                }

                messages.add(
                    ChatMessage(
                        id = mId,
                        role = role,
                        content = content,
                        thinkingContent = thinking,
                        toolExecutions = toolExecs,
                        blocks = blocks,
                        attachedFile = attachedItem,
                        modelName = modelName,
                        timestamp = timestamp,
                        isError = isError
                    )
                )
            }

            ChatSession(
                id = id,
                title = title,
                createdAt = createdAt,
                updatedAt = updatedAt,
                messages = messages
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveSession(session: ChatSession) = withContext(Dispatchers.IO) {
        try {
            val file = File(sessionsDir, "${session.id}.json")
            val json = JSONObject().apply {
                put("id", session.id)
                put("title", session.title)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)

                val msgsArr = JSONArray()
                for (m in session.messages) {
                    val mObj = JSONObject().apply {
                        put("id", m.id)
                        put("role", m.role.name)
                        put("content", m.content)
                        if (m.thinkingContent != null) put("thinkingContent", m.thinkingContent)
                        if (m.modelName != null) put("modelName", m.modelName)
                        put("timestamp", m.timestamp)
                        put("isError", m.isError)

                        // Tools
                        val toolsArr = JSONArray()
                        for (t in m.toolExecutions) {
                            toolsArr.put(JSONObject().apply {
                                put("id", t.id)
                                put("toolName", t.toolName)
                                put("summary", t.summary)
                                put("inputDetail", t.inputDetail)
                                put("outputDetail", t.outputDetail)
                                put("status", t.status.name)
                                if (t.errorMessage != null) put("errorMessage", t.errorMessage)
                            })
                        }
                        put("toolExecutions", toolsArr)

                        // Blocks
                        val blocksArr = JSONArray()
                        for (b in m.blocks) {
                            blocksArr.put(JSONObject().apply {
                                put("id", b.id)
                                put("type", b.type.name)
                                put("text", b.text)
                                if (b.tool != null) {
                                    val t = b.tool
                                    put("tool", JSONObject().apply {
                                        put("id", t.id)
                                        put("toolName", t.toolName)
                                        put("summary", t.summary)
                                        put("inputDetail", t.inputDetail)
                                        put("outputDetail", t.outputDetail)
                                        put("status", t.status.name)
                                        if (t.errorMessage != null) put("errorMessage", t.errorMessage)
                                    })
                                }
                            })
                        }
                        put("blocks", blocksArr)

                        // Attached file
                        if (m.attachedFile != null) {
                            put("attachedFile", JSONObject().apply {
                                put("id", m.attachedFile.id)
                                put("name", m.attachedFile.name)
                                put("path", m.attachedFile.path)
                                put("isDirectory", m.attachedFile.isDirectory)
                                put("sizeBytes", m.attachedFile.sizeBytes)
                                put("lastModified", m.attachedFile.lastModified)
                                put("extension", m.attachedFile.extension)
                            })
                        }
                    }
                    msgsArr.put(mObj)
                }
                put("messages", msgsArr)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "$id.json")
        if (file.exists()) file.delete()
    }
}
