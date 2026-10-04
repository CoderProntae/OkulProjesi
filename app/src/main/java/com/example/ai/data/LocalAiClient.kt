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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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
                append("\n[GERÇEK DOSYA VE DİSK ERİŞİMİ - KESİN KURALLAR]:")
                append("\n1. Kullanıcı sana bir dosya eklediğinde (veya soru sorduğunda), o dosyanın gerçek fiziksel disk yolu, boyutu, tüm içeriği / medya bilgileri / transkripti sana [KULLANICININ EKLEDİĞİ GERÇEK OKUL DOSYASI] altında doğrudan verilir.")
                append("\n2. KESİNLİKLE YASAK: ASLA 'lütfen dosyanızı ekleyin', 'dosyayı göremiyorum', 'ben bir yapay zekayım' DEME! Dosya sana zaten eksiksiz olarak verilmiştir. Kullanıcının istediği transkripti, özetini, soru çözümünü veya notunu anında üret.")
                append("\n3. SES DOSYALARI (MP3/WAV/M4A/OGG) - KESİN KURAL:")
                append("\n- Ses dosyaları ses motoru (Whisper) ile işlenir. 2. Model ($visionModelName) bir GÖRSEL modeldir ve ses dosyalarını KESİNLİKLE İŞLEYEMEZ.")
                append("\n- Bu nedenle ses dosyaları (.mp3, .wav vb.) için ASLA `GÖRSEL_MODELİ_ÇAĞIR` komutunu KULLANMA!")
                append("\n- Kullanıcı ses dosyası eklediğinde, dosyanın transkripti veya bilgisi sana doğrudan sağlanır. Gerekirse `[KOMUT: DOSYA_İNCELE | dosya_adi]` çağırabilirsin. Kullanıcının sorusunu yanıtla ve notu `[KOMUT: NOT_OLUŞTUR | dosya_adi_not.txt | içerik]` ile kaydet.")
                if (isThinkingEnabled) {
                    append("\n\n[DÜŞÜNME MODU AKTİF]: Nihai yanıtını vermeden önce adım adım düşünme sürecini <think>...</think> etiketleri içerisine yaz. Ardından doğrudan nihai yanıtını ve çözümlerini sun.")
                }
                if (!workspaceOverview.isNullOrBlank()) {
                    append("\n\n[MEVCUT TAM OKUL ÇALIŞMA ALANI AĞACI]:\n").append(workspaceOverview)
                }
                append("\n\n[2. MODEL (SADECE GÖRSEL VE VİDEO UZMANI - $visionModelName) PROTOKOLÜ]:")
                append("\nSistemde yüksek çözünürlüklü görsel (fotoğraf/belge/ödev/el yazısı/grafik) ve video analizi yapabilen 2. bir yapay zeka modeli ($visionModelName) hazırdır.")
                append("\n- SADECE resim (.jpg, .png) veya video (.mp4, .mkv) dosyaları inceleneceği zaman 2. modele sorunu İNGİLİZCE olarak şu komutla ilet:")
                append("\n  [KOMUT: GÖRSEL_MODELİ_ÇAĞIR | dosya_adi | Please analyze all visible questions, math formulas, diagrams and text in this file in detail]")
                append("\n- 2. Model sana detaylı bir İngilizce görsel analiz raporu sunacaktır.")
                append("\n- SENİN GÖREVİN: 2. Modelin analiz raporunu alıp kullanıcıya akıcı ve kusursuz bir TÜRKÇE ile ders çözümü veya notu olarak sunmaktır.")

                append("\n\n[EYLEM VE ARAÇ KULLANIM KURALLARI - ALT AJANLAR]:")
                append("\n- Önce ne yapacağını kullanıcıya kısaca açıkla, ardından komutunu çağır:")
                append("\n- Dosyayı incelemek için: [KOMUT: DOSYA_İNCELE | dosya_adi]")
                append("\n- Ses dosyasını transkript etmek için: [KOMUT: SES_DÖKÜMÜ | ses_dosyasi_adi]")
                append("\n- Dosya silmek için: [KOMUT: DOSYA_SİL | dosya_adi]")
                append("\n- Metin dosyasını okumak için: [KOMUT: DOSYA_OKU | dosya_adi]")
                append("\n- Test/sınav hazırlamak için: [KOMUT: TEST_OLUŞTUR | ders_veya_konu | soru_sayisi | sorular_ve_cevaplar]")
                append("\n- SADECE resim (.jpg, .png) veya video (.mp4) analizi için 2. modeli çağırmak: [KOMUT: GÖRSEL_MODELİ_ÇAĞIR | dosya_adi | English question]")
                append("\n  (DİKKAT: Ses dosyaları için GÖRSEL_MODELİ_ÇAĞIR ASLA çağrılmaz; sesler için Whisper / SES_DÖKÜMÜ kullanılır.)")
                append("\n- Metin düzenlemek için: [KOMUT: METİN_DÜZENLE | dosya_adi | yeni_icerik]")
                append("\n- Ders notu/transkript kaydetmek için: [KOMUT: NOT_OLUŞTUR | dosya_adi.txt | içerik]")
                append("\n- Yeni klasör açmak için: [KOMUT: KLASÖR_OLUŞTUR | klasor_adi]")
                append("\n- Yeniden adlandırmak için: [KOMUT: YENİDEN_ADLANDIR | eski_ad | yeni_ad]")
                append("\n- Dosya indirmek için: [KOMUT: İNDİR | dosya_url | dosya_adi]")
                append("\n- İnternet araması için: [KOMUT: ARA | arama_sorgusu]")
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

            // If image or video attached, prepare base64 image frames
            val mediaImages = mutableListOf<String>()
            if (attachedItem != null) {
                val f = File(attachedItem.path)
                val frames = extractMediaFrames(f, maxFrames = 3)
                mediaImages.addAll(frames)
            }

            if (messagesArray.length() > 1) {
                val lastObj = messagesArray.getJSONObject(messagesArray.length() - 1)
                val currentText = lastObj.optString("content", "")
                if (extraContext.isNotEmpty()) {
                    lastObj.put("content", (currentText + extraContext.toString()).trim())
                }

                if (mediaImages.isNotEmpty()) {
                    val imagesArr = JSONArray()
                    mediaImages.forEach { imagesArr.put(it) }
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
        base64Images: List<String>,
        fallbackModelName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        
        suspend fun executeCall(modelToUse: String): Result<String> {
            return try {
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
                    put("model", modelToUse)
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
                    return Result.failure(Exception("Model ($modelToUse) yanıt vermedi: HTTP ${response.code} ($err)"))
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

        val primaryResult = executeCall(visionModelName)
        if (primaryResult.isSuccess) {
            return@withContext primaryResult
        }

        // If primary vision model failed and fallback model is provided, try fallback model
        if (!fallbackModelName.isNullOrBlank() && fallbackModelName != visionModelName) {
            val fallbackResult = executeCall(fallbackModelName)
            if (fallbackResult.isSuccess) {
                return@withContext fallbackResult
            }
        }

        primaryResult
    }

    suspend fun testAudioConnection(audioServerUrl: String): AiServerStatus = withContext(Dispatchers.IO) {
        val cleanUrl = audioServerUrl.trim().removeSuffix("/")
        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("$cleanUrl/openapi.json")
                .build()
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            if (response.isSuccessful || response.code in listOf(200, 404, 405)) {
                AiServerStatus(
                    isConnected = true,
                    latencyMs = latency,
                    availableModels = listOf("Faster-Whisper (Aktif ve Hazır)")
                )
            } else {
                AiServerStatus(isConnected = false, errorMessage = "Whisper HTTP ${response.code}")
            }
        } catch (e: Exception) {
            AiServerStatus(isConnected = false, errorMessage = "Whisper bağlantı hatası: ${e.localizedMessage}")
        }
    }

    suspend fun processRawAudioInput(
        serverUrl: String,
        audioFile: File,
        audioServerUrl: String? = null,
        prompt: String = "Please transcribe all spoken dialogue and speech in this audio accurately."
    ): Result<String> = withContext(Dispatchers.IO) {
        val urlsToTry = mutableListOf<String>()
        val primaryAudio = audioServerUrl?.trim()?.removeSuffix("/")
        if (!primaryAudio.isNullOrBlank()) {
            urlsToTry.add(primaryAudio)
        } else {
            val hostPart = serverUrl.trim().removeSuffix("/").substringBeforeLast(":")
            urlsToTry.add("$hostPart:8000")
        }
        val cleanMain = serverUrl.trim().removeSuffix("/")
        if (!urlsToTry.contains(cleanMain)) {
            urlsToTry.add(cleanMain)
        }

        val mediaType = when (audioFile.extension.lowercase()) {
            "wav" -> "audio/wav".toMediaType()
            "ogg" -> "audio/ogg".toMediaType()
            "flac" -> "audio/flac".toMediaType()
            else -> "audio/mpeg".toMediaType()
        }

        // Try standard Whisper endpoint on all candidates
        for (targetUrl in urlsToTry) {
            // Attempt 1: response_format="json" with model
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
                    .addFormDataPart("model", "Systran/faster-whisper-small")
                    .addFormDataPart("response_format", "json")
                    .build()

                val request = Request.Builder()
                    .url("$targetUrl/v1/audio/transcriptions")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val rawBody = response.body?.string().orEmpty().trim()
                    val cleanText = parseTranscriptText(rawBody)
                    if (cleanText.isNotBlank()) {
                        return@withContext Result.success(cleanText)
                    }
                }
            } catch (_: Exception) {}

            // Attempt 2: ONLY file parameter (FastAPI uses its own default loaded model and json response)
            try {
                val simpleBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
                    .build()

                val simpleReq = Request.Builder()
                    .url("$targetUrl/v1/audio/transcriptions")
                    .post(simpleBody)
                    .build()

                val simpleResp = client.newCall(simpleReq).execute()
                if (simpleResp.isSuccessful) {
                    val rawBody = simpleResp.body?.string().orEmpty().trim()
                    val cleanText = parseTranscriptText(rawBody)
                    if (cleanText.isNotBlank()) {
                        return@withContext Result.success(cleanText)
                    }
                }
            } catch (_: Exception) {}

            // Attempt 3: model="whisper" or model="whisper-1" (OpenAI standard)
            try {
                val oaiBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
                    .addFormDataPart("model", "whisper-1")
                    .build()

                val oaiReq = Request.Builder()
                    .url("$targetUrl/v1/audio/transcriptions")
                    .post(oaiBody)
                    .build()

                val oaiResp = client.newCall(oaiReq).execute()
                if (oaiResp.isSuccessful) {
                    val rawBody = oaiResp.body?.string().orEmpty().trim()
                    val cleanText = parseTranscriptText(rawBody)
                    if (cleanText.isNotBlank()) {
                        return@withContext Result.success(cleanText)
                    }
                }
            } catch (_: Exception) {}
        }

        // Try raw base64 audio payload to multimodal chat endpoint on Ollama
        try {
            val ollamaUrl = serverUrl.trim().removeSuffix("/")
            val audioBytes = audioFile.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            val audioPayload = JSONObject().apply {
                put("model", "whisper")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                        put("audio", base64Audio)
                    })
                })
                put("stream", false)
            }
            val reqBody = audioPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$ollamaUrl/api/chat")
                .post(reqBody)
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val b = resp.body?.string().orEmpty()
                val j = JSONObject(b)
                val c = j.optJSONObject("message")?.optString("content", "").orEmpty()
                if (c.isNotBlank()) {
                    return@withContext Result.success(c)
                }
            }
        } catch (_: Exception) {}

        Result.failure(Exception("Yerel Whisper veya Ses Modeli (port 8000/11435) yanıt vermedi."))
    }

    private fun parseTranscriptText(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                if (json.has("text")) {
                    return json.optString("text", "")
                }
            } catch (_: Exception) {}
        }
        return trimmed
    }

    fun extractMediaFrames(file: File, maxFrames: Int = 6): List<String> {
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

    private fun extractVideoFrames(videoFile: File, maxFrames: Int = 6): List<String> {
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
