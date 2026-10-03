package com.example.ai.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.ai.model.AiServerStatus
import com.example.ai.model.ChatMessage
import com.example.ai.model.MessageRole
import com.example.model.SchoolItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class LocalAiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    suspend fun testConnection(serverUrl: String): AiServerStatus = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("$cleanUrl/api/tags")
                .build()

            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val models = mutableListOf<String>()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        models.add(m.optString("name", ""))
                    }
                }
                AiServerStatus(
                    isConnected = true,
                    latencyMs = latency,
                    availableModels = models.filter { it.isNotBlank() }
                )
            } else {
                AiServerStatus(
                    isConnected = false,
                    errorMessage = "Sunucu yanıt verdi fakat hata döndü: HTTP ${response.code}"
                )
            }
        } catch (e: Exception) {
            val msg = when {
                e.message?.contains("Failed to connect") == true || e.message?.contains("ECONNREFUSED") == true ->
                    "Bilgisayara bağlanılamadı. Ollama'nın çalıştığından ve OLLAMA_HOST=0.0.0.0 ayarlandığından emin olun."
                e.message?.contains("timeout") == true ->
                    "Bağlantı zaman aşımına uğradı. Telefon ve bilgisayarın aynı Wi-Fi ağına bağlı olduğunu kontrol edin."
                else -> "Bağlantı hatası: ${e.localizedMessage}"
            }
            AiServerStatus(isConnected = false, errorMessage = msg)
        }
    }

    suspend fun sendChat(
        serverUrl: String,
        modelName: String,
        supremePrompt: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        attachedItem: SchoolItem? = null,
        attachedFileContent: String? = null,
        webSearchSummary: String? = null,
        workspaceOverview: String? = null
    ): Result<Pair<String, String?>> = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")

        try {
            val messagesArray = JSONArray()

            // 1. Injected Supreme Prompt (En Üst Kademe Prompt - System Message with absolute priority)
            val supremeSystemContent = buildString {
                append(supremePrompt)
                append("\n\n[SİSTEM BİLGİSİ VE ÇALIŞMA ALANI ORTAMI]")
                append("\nKullanıcının Android telefonunda 'Okul Dosyaları' uygulaması çalışıyor.")
                append("\nKullanıcının okul dizinindeki tüm ders notlarını inceleyebilir, dosyaları yeniden adlandırabilir veya yeni notlar oluşturabilirsin.")
                if (!workspaceOverview.isNullOrBlank()) {
                    append("\n\n[MEVCUT OKUL DİZİNİ VE DOSYALAR]:\n").append(workspaceOverview)
                }
                append("\n\n[EYLEM VE ARAÇ KULLANIM SÖZ DİZİMİ - Kullanıcı okul dosyalarını veya sistemi yönetmeni istediğinde bu komutları kullanabilirsin]:")
                append("\n- Dosya içeriğini incelemek/okumak için: [KOMUT: DOSYA_İNCELE | dosya_adi]")
                append("\n- Mevcut metin/.txt dosyasını düzenlemek/güncellemek için: [KOMUT: METİN_DÜZENLE | dosya_adi | yeni_icerik]")
                append("\n- Yeni ders notu veya dosya oluşturmak için: [KOMUT: NOT_OLUŞTUR | dosya_adi.txt | içerik]")
                append("\n- Yeni okul klasörü açmak için: [KOMUT: KLASÖR_OLUŞTUR | klasor_adi]")
                append("\n- Dosya veya klasör adı değiştirmek için: [KOMUT: YENİDEN_ADLANDIR | eski_ad | yeni_ad]")
                append("\n- İnternetten dosya/video indirmek için: [KOMUT: İNDİR | dosya_url | dosya_adi]")
                append("\n- İnternette yeni arama yapmak için: [KOMUT: ARA | arama_sorgusu]")
            }

            messagesArray.put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", supremeSystemContent)
                }
            )

            // 2. Chat history
            for (msg in messages) {
                if (msg.role == MessageRole.SYSTEM) continue
                val roleStr = if (msg.role == MessageRole.USER) "user" else "assistant"
                messagesArray.put(
                    JSONObject().apply {
                        put("role", roleStr)
                        put("content", msg.content)
                    }
                )
            }

            // 3. Current active user prompt enrichment (attachments, web search results)
            val lastUserMsg = messages.lastOrNull { it.role == MessageRole.USER }
            val extraContext = StringBuilder()

            if (!attachedFileContent.isNullOrBlank() && attachedItem != null) {
                extraContext.append("\n\n[KULLANICININ EKLEDİĞİ OKUL DOSYASI]: '${attachedItem.name}' (Tür: ${attachedItem.itemType.titleTr})\nİçerik:\n")
                extraContext.append(attachedFileContent.take(8000))
            }

            if (!webSearchSummary.isNullOrBlank()) {
                extraContext.append("\n\n[CANLI İNTERNET ARAMA SONUÇLARI]:\n").append(webSearchSummary)
            }

            // If vision model and image attached, prepare base64 image
            var base64Image: String? = null
            if (attachedItem != null && (attachedItem.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp"))) {
                base64Image = encodeImageToBase64(File(attachedItem.path))
            }

            if (extraContext.isNotEmpty() && messagesArray.length() > 1) {
                val lastObj = messagesArray.getJSONObject(messagesArray.length() - 1)
                val currentText = lastObj.optString("content", "")
                lastObj.put("content", currentText + extraContext.toString())

                if (base64Image != null) {
                    val imagesArr = JSONArray().apply { put(base64Image) }
                    lastObj.put("images", imagesArr)
                }
            }

            // Prepare Ollama request payload
            val payload = JSONObject().apply {
                put("model", modelName)
                put("messages", messagesArray)
                put("stream", false)
                put("options", JSONObject().apply {
                    put("temperature", temperature.toDouble())
                    put("num_predict", 2048)
                })
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$cleanUrl/api/chat")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Model yanıt vermedi: HTTP ${response.code}"))
            }

            val respBody = response.body?.string().orEmpty()
            val respJson = JSONObject(respBody)
            val msgObj = respJson.optJSONObject("message")
            val fullContent = msgObj?.optString("content", "").orEmpty()

            // Separate <think>...</think> if thinking model (e.g. DeepSeek-R1)
            val (mainContent, thinkingContent) = extractThinking(fullContent)

            Result.success(mainContent to thinkingContent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractThinking(raw: String): Pair<String, String?> {
        val thinkRegex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
        val match = thinkRegex.find(raw)
        return if (match != null) {
            val thinking = match.groupValues[1].trim()
            val cleanMain = raw.replace(match.value, "").trim()
            cleanMain to thinking
        } else {
            raw to null
        }
    }

    private fun encodeImageToBase64(file: File): String? {
        return try {
            val bm = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val maxDim = 1024
            val scaled = if (bm.width > maxDim || bm.height > maxDim) {
                val ratio = bm.width.toFloat() / bm.height.toFloat()
                val targetW = if (ratio > 1f) maxDim else (maxDim * ratio).toInt()
                val targetH = if (ratio > 1f) (maxDim / ratio).toInt() else maxDim
                Bitmap.createScaledBitmap(bm, targetW, targetH, true)
            } else bm

            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
}
