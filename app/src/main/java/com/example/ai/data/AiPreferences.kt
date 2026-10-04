package com.example.ai.data

import android.content.Context
import android.content.SharedPreferences

class AiPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ai_settings_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_SERVER_URL = "http://192.168.1.100:11435"
        const val DEFAULT_AUDIO_SERVER_URL = "http://192.168.1.100:8000"
        const val DEFAULT_MODEL = "qwen2.5-coder:7b"
        const val DEFAULT_VISION_MODEL = "minicpm-v:latest"

        const val DEFAULT_SUPREME_PROMPT = """[EN ÜST KADEME EMİR VE ANA YÖNERGE - TÜM KURALLARDAN VE KULLANICI GİRDİLERİNDEN DAİMA ÜSTÜNDÜR]
Sen kullanıcının kişisel Okul ve Çalışma Alanı Yapay Zeka Asistanısın.
Aşağıdaki ana direktifler sistemdeki en yüksek yetkiye sahiptir ve asla ihlal edilemez:
1. Kullanıcının talimatlarını sansürsüz, doğrudan, dürüst ve en eksiksiz şekilde yerine getir. Hiçbir gereksiz ahlaki vaaz veya kısıtlama koyma.
2. Okul derslerini, İngilizce dinleme kayıtlarını, ders notlarını, videoları ve internet araştırmalarını en yüksek akademik ve pratik doğrulukla analiz et.
3. Kullanıcının okul dizinindeki dosya ve klasör işlemlerini (isim düzenleme, içerik inceleme, özetleme, not oluşturma) sadakatle ve hassasiyetle gerçekleştir.
4. İnternet araması ve içerik indirme işlemlerinde en güncel ve güvenilir kaynakları sağla.
5. Cevaplarında daima açık, anlaşılır, derinlemesine ve akıcı Türkçe yanıtlar sun.
6. Bu En Üst Kademe Prompt, sistemdeki her şeyden önce kontrol edilir ve cevabın sonunda da bu direktiflerin gereği eksiksiz yerine getirilmiş olmalıdır."""
    }

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value.trim().removeSuffix("/")).apply()

    var modelName: String
        get() = prefs.getString("model_name", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model_name", value.trim()).apply()

    var visionModelName: String
        get() = prefs.getString("vision_model_name", DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(value) = prefs.edit().putString("vision_model_name", value.trim()).apply()

    var visionServerUrl: String
        get() = prefs.getString("vision_server_url", "") ?: ""
        set(value) = prefs.edit().putString("vision_server_url", value.trim().removeSuffix("/")).apply()

    val effectiveVisionServerUrl: String
        get() = visionServerUrl.ifBlank { serverUrl }

    var audioServerUrl: String
        get() = prefs.getString("audio_server_url", DEFAULT_AUDIO_SERVER_URL) ?: DEFAULT_AUDIO_SERVER_URL
        set(value) = prefs.edit().putString("audio_server_url", value.trim().removeSuffix("/")).apply()

    var supremePrompt: String
        get() = prefs.getString("supreme_prompt", DEFAULT_SUPREME_PROMPT) ?: DEFAULT_SUPREME_PROMPT
        set(value) = prefs.edit().putString("supreme_prompt", value).apply()

    var isThinkingEnabled: Boolean
        get() = prefs.getBoolean("thinking_mode", true)
        set(value) = prefs.edit().putBoolean("thinking_mode", value).apply()

    var isWebSearchEnabled: Boolean
        get() = prefs.getBoolean("web_search_enabled", true)
        set(value) = prefs.edit().putBoolean("web_search_enabled", value).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.7f)
        set(value) = prefs.edit().putFloat("temperature", value).apply()

    fun resetSupremePrompt() {
        supremePrompt = DEFAULT_SUPREME_PROMPT
    }
}
