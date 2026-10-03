package com.example.ai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MediaDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    suspend fun downloadFile(
        fileUrl: String,
        targetDirectory: File,
        customName: String? = null,
        onProgress: (percent: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(fileUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("İndirme başarısız oldu: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Boş veri yanıtı"))
            val contentLength = body.contentLength()

            val resolvedName = if (!customName.isNullOrBlank()) {
                customName
            } else {
                extractFileName(fileUrl, response.header("Content-Disposition"))
            }

            val sanitizedName = resolvedName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val destFile = File(targetDirectory, sanitizedName)

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                            onProgress(percent)
                        }
                    }
                }
            }

            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractFileName(url: String, contentDisposition: String?): String {
        if (!contentDisposition.isNullOrBlank() && contentDisposition.contains("filename=")) {
            val parts = contentDisposition.split("filename=")
            if (parts.size > 1) {
                var name = parts[1].trim()
                if (name.startsWith("\"") && name.endsWith("\"") && name.length >= 2) {
                    name = name.substring(1, name.length - 1)
                }
                if (name.isNotBlank()) return name
            }
        }
        val cleanUrl = url.substringBefore("?").substringBefore("#")
        val lastSegment = cleanUrl.substringAfterLast("/")
        return if (lastSegment.isNotBlank() && lastSegment.contains(".")) {
            lastSegment
        } else {
            "indirilen_dosya_${System.currentTimeMillis()}.mp4"
        }
    }
}
