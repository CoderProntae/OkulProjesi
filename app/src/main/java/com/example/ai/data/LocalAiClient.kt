package com.example.ai.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
        visionModelName: String = "minicpm-v",
        supremePrompt: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        isThinkingEnabled: Boolean = false,
        attachedItem: SchoolItem? = null,
        attachedFileContent: String? = null,
        webSearchSummary: String? = null,
        workspaceOverview: String? = null,
        onChunk: ((String) -> Unit)? = null
    ): Result<Pair<String, String?>> = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")

        try {
            val messagesArray = JSONArray()

            // 1. Injected Supreme Prompt (En Üst Kademe Prompt - System Message with absolute priority)
            val supremeSystemContent = buildString {
                append(supremePrompt)
                append("\n\n[SENİN KİMLİĞİN VE ÇALIŞMA ALANI ORTAMI]")
                append("\nSen kullanıcının Android cihazındaki yerel yapay zeka asistanı ve Okul Dosya Yöneticisisin.")
                append("\nModel adın: $modelName.")
                append("\n[GERÇEK DOSYA VE DİSK ERİŞİMİ - KESİN KURAL]:")
                append("\nKullanıcı sana bir dosya eklediğinde veya soru sorduğunda, o dosyanın gerçek fiziksel disk yolu, boyutu, tam içeriği / medya bilgileri sana [KULLANICININ EKLEDİĞİ GERÇEK OKUL DOSYASI] altında doğrudan verilir.")
                append("\nKESİNLİKLE YASAK: ASLA 'lütfen dosyanızı ekleyin', 'dosyaya erişemiyorum', 'ben sadece bir yapay zekayım' gibi mazeretler üretme! Dosya zaten fiziksel olarak okunmuş ve sana eksiksiz verilmiştir. Hemen kullanıcının istediği transkripti, özetini, soru çözümünü veya notunu üret.")
                if (isThinkingEnabled) {
                    append("\n\n[DÜŞÜNME MODU AKTİF]: Nihai yanıtını vermeden önce adım adım düşünme sürecini <think>...</think> etiketleri içerisine yaz. Ardından doğrudan nihai yanıtını ve çözümlerini sun.")
                }
                if (!workspaceOverview.isNullOrBlank()) {
                    append("\n\n[MEVCUT TAM OKUL ÇALIŞMA ALANI AĞACI]:\n").append(workspaceOverview)
                }
                append("\n\n[2. MODEL (GÖRSEL VE VİDEO UZMANI - $visionModelName) PROTOKOLÜ]:")
                append("\nSistemde yüksek çözünürlüklü görsel (fotoğraf/belge/ödev/el yazısı/grafik) ve video analizi yapabilen 2. bir yapay zeka modeli ($visionModelName) hazırdır.")
                append("\n2. Model, optik karakter tanıma (OCR) ve video anlama işlemlerinde en yüksek performansı İNGİLİZCE (English) verir.")
                append("\n- Kullanıcı bir görsel veya video hakkında soru sorduğunda ya da bu dosyaları incelemen gerektiğinde 2. modele sorunu İNGİLİZCE olarak şu komutla ilet:")
                append("\n  [KOMUT: GÖRSEL_MODELİ_ÇAĞIR | dosya_adi | Please analyze all visible questions, math formulas, diagrams and text in this file in detail]")
                append("\n- 2. Model sana detaylı bir İngilizce görsel analiz raporu sunacaktır.")
                append("\n- SENİN GÖREVİN: 2. Modelin İngilizce analiz raporunu alıp kullanıcıya akıcı, eksiksiz ve kusursuz bir TÜRKÇE ile ders çözümü, notu veya özeti olarak sunmaktır.")

                append("\n\n[EYLEM VE ARAÇ KULLANIM SÖZ DİZİMİ - Kullanıcı okul dosyalarını veya sistemi yönetmeni istediğinde bu komutları kullanabilirsin]:")
                append("\n- Dosya içeriğini incelemek/okumak için: [KOMUT: DOSYA_İNCELE | dosya_adi]")
                append("\n- Görsel veya video dosyalarını 2. modele inceletmek için (soruyu İngilizce yaz): [KOMUT: GÖRSEL_MODELİ_ÇAĞIR | dosya_adi | English question]")
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
                extraContext.append("\n\n[KULLANICININ EKLEDİĞİ GERÇEK OKUL DOSYASI]:\n")
                extraContext.append("- Dosya Adı: ${attachedItem.name}\n")
                extraContext.append("- Gerçek Fiziksel Disk Yolu: ${attachedItem.path}\n")
                extraContext.append("- Boyut: ${attachedItem.formattedSize} (${attachedItem.sizeBytes} bayt)\n")
                extraContext.append("- Tür: ${attachedItem.itemType.titleTr}\n")
                extraContext.append("[DOSYANIN GERÇEK İÇERİĞİ / BİLGİLERİ]:\n")
                extraContext.append(attachedFileContent.take(50000))
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
            val useStream = onChunk != null
            val payload = JSONObject().apply {
                put("model", modelName)
                put("messages", messagesArray)
                put("stream", useStream)
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
                val errBody = response.body?.string().orEmpty()
                val errorDetail = try {
                    JSONObject(errBody).optString("error", errBody)
                } catch (_: Exception) {
                    errBody
                }
                val msg = if (response.code == 404) {
                    "Model bulunamadı (HTTP 404): '$modelName'.\nOllama uyarısı: $errorDetail\nLütfen ayarlardan bilgisayarınızda yüklü olan modeli seçin."
                } else {
                    "Model yanıt vermedi: HTTP ${response.code} ($errorDetail)"
                }
                return@withContext Result.failure(Exception(msg))
            }

            val fullContent: String
            if (useStream) {
                val fullBuilder = StringBuilder()
                val source = response.body?.source() ?: return@withContext Result.failure(Exception("Boş yanıt alındı"))
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    try {
                        val chunkObj = JSONObject(line)
                        val msgObj = chunkObj.optJSONObject("message")
                        val contentPiece = msgObj?.optString("content", "").orEmpty()
                        if (contentPiece.isNotEmpty()) {
                            fullBuilder.append(contentPiece)
                            onChunk?.invoke(contentPiece)
                        }
                    } catch (_: Exception) {}
                }
                fullContent = fullBuilder.toString()
            } else {
                val respBody = response.body?.string().orEmpty()
                val respJson = JSONObject(respBody)
                val msgObj = respJson.optJSONObject("message")
                fullContent = msgObj?.optString("content", "").orEmpty()
            }

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

    suspend fun callVisionModel(
        serverUrl: String,
        visionModelName: String,
        prompt: String,
        base64Images: List<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        try {
            val messagesArray = JSONArray()
            val userMsg = JSONObject().apply {
                put("role", "user")
                put("content", prompt)
                if (base64Images.isNotEmpty()) {
                    val imgArr = JSONArray()
                    base64Images.forEach { imgArr.put(it) }
                    put("images", imgArr)
                }
            }
            messagesArray.put(userMsg)

            val payload = JSONObject().apply {
                put("model", visionModelName)
                put("messages", messagesArray)
                put("stream", false)
                put("options", JSONObject().apply {
                    put("temperature", 0.3)
                    put("num_predict", 1024)
                })
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$cleanUrl/api/chat")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                return@withContext Result.failure(Exception("2. Görsel Model ($visionModelName) yanıt vermedi: HTTP ${response.code} ($err)"))
            }
            val respBody = response.body?.string().orEmpty()
            val respJson = JSONObject(respBody)
            val msgObj = respJson.optJSONObject("message")
            val content = msgObj?.optString("content", "").orEmpty()
            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun extractMediaFrames(file: File, maxFrames: Int = 3): List<String> {
        val ext = file.extension.lowercase()
        return if (ext in listOf("mp4", "mkv", "webm", "avi", "3gp", "mov")) {
            extractVideoFrames(file, maxFrames)
        } else if (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val img = encodeImageToBase64(file)
            if (img != null) listOf(img) else emptyList()
        } else {
            emptyList()
        }
    }

    private fun extractVideoFrames(videoFile: File, maxFrames: Int = 3): List<String> {
        val frames = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 10000L
            val intervals = (1..maxFrames).map { (durationMs * it / (maxFrames + 1)) * 1000L }
            for (timeUs in intervals) {
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val maxDim = 640
                    val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                        val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    } else bitmap
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    frames.add(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return frames
    }
}
