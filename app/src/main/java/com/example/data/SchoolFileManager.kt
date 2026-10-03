package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.example.model.FolderAuditData
import com.example.model.ItemType
import com.example.model.SchoolItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.sin

class SchoolFileManager(private val context: Context) {

    val rootWorkspaceDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "OkulDizini")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    var currentDirectory: File = rootWorkspaceDir
        private set

    init {
        // Initialize workspace and sample assets if newly created
        ensureInitialSchoolContent()
    }

    fun getBreadcrumbs(): List<File> {
        val crumbs = mutableListOf<File>()
        var curr: File? = currentDirectory
        val rootPath = rootWorkspaceDir.absolutePath

        while (curr != null && curr.absolutePath.startsWith(rootPath)) {
            crumbs.add(0, curr)
            if (curr.absolutePath == rootPath) break
            curr = curr.parentFile
        }
        return crumbs
    }

    fun navigateTo(folder: File): Boolean {
        if (folder.exists() && folder.isDirectory) {
            currentDirectory = folder
            return true
        }
        return false
    }

    fun navigateUp(): Boolean {
        if (currentDirectory.absolutePath == rootWorkspaceDir.absolutePath) {
            return false
        }
        val parent = currentDirectory.parentFile
        if (parent != null && parent.absolutePath.startsWith(rootWorkspaceDir.absolutePath)) {
            currentDirectory = parent
            return true
        }
        return false
    }

    fun navigateToRoot() {
        currentDirectory = rootWorkspaceDir
    }

    suspend fun listItemsInCurrentDirectory(): List<SchoolItem> = withContext(Dispatchers.IO) {
        val files = currentDirectory.listFiles() ?: return@withContext emptyList()
        val list = files.map { file ->
            val isDir = file.isDirectory
            val ext = if (isDir) "" else file.extension
            val type = ItemType.fromExtension(ext, isDir)
            val childCount = if (isDir) (file.listFiles()?.size ?: 0) else 0
            val size = if (isDir) calculateFolderSize(file) else file.length()

            SchoolItem(
                id = file.absolutePath,
                name = file.name,
                path = file.absolutePath,
                isDirectory = isDir,
                sizeBytes = size,
                lastModified = file.lastModified(),
                extension = ext,
                itemType = type,
                childCount = childCount
            )
        }

        // Sort: Folders first, then alphabetically
        list.sortedWith(
            compareBy<SchoolItem> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    suspend fun createFolder(name: String): Result<File> = withContext(Dispatchers.IO) {
        val cleanName = sanitizeFileName(name)
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Klasör adı boş olamaz"))
        }
        val target = File(currentDirectory, cleanName)
        if (target.exists()) {
            return@withContext Result.failure(IllegalStateException("Bu isimde bir klasör veya dosya zaten mevcut"))
        }
        if (target.mkdirs()) {
            Result.success(target)
        } else {
            Result.failure(Exception("Klasör oluşturulamadı"))
        }
    }

    suspend fun createTextFile(name: String, content: String = ""): Result<File> = withContext(Dispatchers.IO) {
        var cleanName = sanitizeFileName(name)
        if (!cleanName.lowercase().endsWith(".txt")) {
            cleanName += ".txt"
        }
        val target = File(currentDirectory, cleanName)
        if (target.exists()) {
            return@withContext Result.failure(IllegalStateException("Bu isimde bir dosya zaten mevcut"))
        }
        try {
            target.writeText(content, Charsets.UTF_8)
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun rename(target: File, newName: String): Result<File> = withContext(Dispatchers.IO) {
        val cleanName = sanitizeFileName(newName)
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Yeni isim boş olamaz"))
        }
        val dest = File(target.parentFile ?: currentDirectory, cleanName)
        if (dest.exists()) {
            return@withContext Result.failure(IllegalStateException("Bu isimde bir öge zaten mevcut"))
        }
        if (target.renameTo(dest)) {
            Result.success(dest)
        } else {
            Result.failure(Exception("Yeniden adlandırma başarısız oldu"))
        }
    }

    suspend fun delete(target: File): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val success = if (target.isDirectory) {
                target.deleteRecursively()
            } else {
                target.delete()
            }
            if (success) Result.success(true) else Result.failure(Exception("Dosya silinemedi"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readText(file: File): String = withContext(Dispatchers.IO) {
        try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "Dosya içeriği okunamadı: ${e.localizedMessage}"
        }
    }

    suspend fun saveText(file: File, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            file.writeText(content, Charsets.UTF_8)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Imports an entire directory tree selected by user via Android SAF OpenDocumentTree
     * Mirrors exact subfolder & file structure in currentDirectory
     */
    suspend fun importDirectoryTree(treeUri: Uri, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val documentFolder = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(Exception("Klasör okunamadı"))

            val folderName = documentFolder.name ?: "İçe_Aktarılan_Klasör"
            var targetRoot = File(currentDirectory, sanitizeFileName(folderName))
            var counter = 1
            while (targetRoot.exists()) {
                targetRoot = File(currentDirectory, "${sanitizeFileName(folderName)}_$counter")
                counter++
            }
            targetRoot.mkdirs()

            var importedCount = 0

            suspend fun copyDocTree(sourceDoc: DocumentFile, destFolder: File) {
                val children = sourceDoc.listFiles()
                for (child in children) {
                    if (child.isDirectory) {
                        val subName = sanitizeFileName(child.name ?: "Alt_Klasör")
                        val subDest = File(destFolder, subName)
                        subDest.mkdirs()
                        copyDocTree(child, subDest)
                    } else if (child.isFile) {
                        val fileName = sanitizeFileName(child.name ?: "dosya_${System.currentTimeMillis()}")
                        val targetFile = File(destFolder, fileName)
                        onProgress("Kopyalanıyor: $fileName")
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        importedCount++
                    }
                }
            }

            copyDocTree(documentFolder, targetRoot)
            Result.success(importedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Imports multiple files picked from device via OpenMultipleDocuments
     */
    suspend fun importFiles(uris: List<Uri>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var count = 0
            for (uri in uris) {
                val displayName = getFileNameFromUri(uri)
                val target = File(currentDirectory, sanitizeFileName(displayName))
                onProgress("Aktarılıyor: $displayName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Audits and analyzes the current directory (file counts, storage per type, largest files)
     */
    suspend fun auditDirectory(targetDir: File = currentDirectory): FolderAuditData = withContext(Dispatchers.IO) {
        var totalSize = 0L
        var totalFiles = 0
        var totalFolders = 0

        var audioCount = 0
        var audioSize = 0L
        var videoCount = 0
        var videoSize = 0L
        var textCount = 0
        var textSize = 0L
        var pdfCount = 0
        var pdfSize = 0L
        var imageCount = 0
        var imageSize = 0L
        var otherCount = 0
        var otherSize = 0L

        val allFiles = mutableListOf<SchoolItem>()

        fun scan(dir: File) {
            val items = dir.listFiles() ?: return
            for (item in items) {
                if (item.isDirectory) {
                    totalFolders++
                    scan(item)
                } else {
                    totalFiles++
                    val len = item.length()
                    totalSize += len
                    val ext = item.extension
                    val type = ItemType.fromExtension(ext, false)

                    when (type) {
                        ItemType.AUDIO -> { audioCount++; audioSize += len }
                        ItemType.VIDEO -> { videoCount++; videoSize += len }
                        ItemType.TEXT -> { textCount++; textSize += len }
                        ItemType.PDF -> { pdfCount++; pdfSize += len }
                        ItemType.IMAGE -> { imageCount++; imageSize += len }
                        ItemType.OTHER, ItemType.FOLDER -> { otherCount++; otherSize += len }
                    }

                    allFiles.add(
                        SchoolItem(
                            id = item.absolutePath,
                            name = item.name,
                            path = item.absolutePath,
                            isDirectory = false,
                            sizeBytes = len,
                            lastModified = item.lastModified(),
                            extension = ext,
                            itemType = type
                        )
                    )
                }
            }
        }

        scan(targetDir)

        val largest = allFiles.sortedByDescending { it.sizeBytes }.take(5)

        FolderAuditData(
            folderName = targetDir.name,
            totalSizeBytes = totalSize,
            totalFilesCount = totalFiles,
            totalFoldersCount = totalFolders,
            audioCount = audioCount,
            audioSizeBytes = audioSize,
            videoCount = videoCount,
            videoSizeBytes = videoSize,
            textCount = textCount,
            textSizeBytes = textSize,
            pdfCount = pdfCount,
            pdfSizeBytes = pdfSize,
            imageCount = imageCount,
            imageSizeBytes = imageSize,
            otherCount = otherCount,
            otherSizeBytes = otherSize,
            largestFiles = largest
        )
    }

    private fun calculateFolderSize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) calculateFolderSize(f) else f.length()
        }
        return size
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "dosya_${System.currentTimeMillis()}"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    val n = it.getString(index)
                    if (!n.isNullOrBlank()) name = n
                }
            }
        }
        return name
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /**
     * Initializes default sample folders and realistic files for school use:
     * English Listening, Math Notes, Physics Labs, Cheat-sheets
     */
    private fun ensureInitialSchoolContent() {
        val initializedMarker = File(rootWorkspaceDir, ".initialized")
        if (initializedMarker.exists()) return

        try {
            // 1. İngilizce Dinleme Dosyaları folder
            val englishDir = File(rootWorkspaceDir, "İngilizce Dinleme (Listening)")
            if (!englishDir.exists()) englishDir.mkdirs()

            // Generate playable wav files for listening exercises
            createPlayableWavFile(
                File(englishDir, "Unit_1_Daily_Routines.wav"),
                durationSeconds = 6,
                baseFreq = 440.0
            )
            createPlayableWavFile(
                File(englishDir, "Unit_2_School_Life_Listening.wav"),
                durationSeconds = 8,
                baseFreq = 523.25
            )

            File(englishDir, "Ders_Plani_ve_Kelimeler.txt").writeText(
                """
                === İNGİLİZCE DİNLEME DERSİ (LISTENING COMPREHENSION) ===
                
                Ünite 1: Daily Routines & Study Habits
                - Vocabulary:
                  * assignment (ödev)
                  * curriculum (müfredat)
                  * lecture (üniversite dersi)
                  * comprehension (anlama)
                  * deadline (teslim tarihi)
                
                Diyalog Notları:
                Sarah: "Have you prepared the English listening audio tracks for tomorrow's seminar?"
                Mark: "Yes, I organized all MP3 files into the School Folder Manager!"
                
                Ödev:
                - Dinleme parçasını 0.75x hızında dinleyip bilinmeyen kelimeleri çıkarın.
                - Ses kaydındaki ana fikri 3 cümleyle özetleyin.
                """.trimIndent(),
                Charsets.UTF_8
            )

            // 2. Matematik & Geometri folder
            val mathDir = File(rootWorkspaceDir, "Matematik ve Geometri")
            if (!mathDir.exists()) mathDir.mkdirs()

            File(mathDir, "Formuller_ve_Teoremler.txt").writeText(
                """
                === MATEMATİK & GEOMETRİ FORMÜLLERİ ===
                
                1. Trigonometri Temel Özdeşlikler:
                   - sin²(x) + cos²(x) = 1
                   - tan(x) = sin(x) / cos(x)
                   - sin(2x) = 2 · sin(x) · cos(x)
                   - cos(2x) = cos²(x) - sin²(x)
                
                2. Türev Kuralları:
                   - d/dx [xⁿ] = n · xⁿ⁻¹
                   - d/dx [f(x) · g(x)] = f'(x)·g(x) + f(x)·g'(x)
                   - d/dx [sin(x)] = cos(x)
                
                3. Üçgende Alan & Pisagor:
                   - a² + b² = c²
                   - Alan = (Taban × Yükseklik) / 2
                """.trimIndent(),
                Charsets.UTF_8
            )

            File(mathDir, "Haftalik_Calisma_Programi.txt").writeText(
                """
                === HAFTALIK DERS ÇALIŞMA PLANI ===
                
                Pazartesi:
                - 10:00 - 11:30 : İngilizce Dinleme ve Telaffuz çalışması
                - 14:00 - 16:00 : Matematik Türev uygulamaları ve soru çözümü
                
                Çarşamba:
                - 09:00 - 11:00 : Fizik Deney Raporu hazırlığı
                - 13:00 - 15:00 : Geometri soru bankası taraması
                
                Cuma:
                - Haftalık konu tekrarları ve dosya düzenleme
                """.trimIndent(),
                Charsets.UTF_8
            )

            // 3. Fen Bilimleri ve Fizik folder
            val scienceDir = File(rootWorkspaceDir, "Fizik ve Fen Bilimleri")
            if (!scienceDir.exists()) scienceDir.mkdirs()

            File(scienceDir, "Laboratuvar_Guvenlik_Notlari.txt").writeText(
                """
                === FİZİK & KİMYA LABORATUVARI KURALLARI ===
                
                1. Laboratuvar önlüğü ve koruyucu gözlük zorunludur.
                2. Kimyasal maddelerin doğrudan koklanması ve tadılması yasaktır.
                3. Elektrik devreleri kurulurken güç kaynağı kapalı tutulmalıdır.
                4. Deney verileri anında not defterine kaydedilmelidir.
                """.trimIndent(),
                Charsets.UTF_8
            )

            initializedMarker.createNewFile()
        } catch (_: Exception) {
            // Ignore if initial seeding fails
        }
    }

    /**
     * Synthesizes a real playable 16-bit PCM WAV file so MediaPlayer can play real sound!
     */
    private fun createPlayableWavFile(file: File, durationSeconds: Int, baseFreq: Double) {
        try {
            val sampleRate = 22050
            val numSamples = durationSeconds * sampleRate
            val pcmData = ByteArray(numSamples * 2)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                // Harmonic educational chime melody
                val freq = when ((t % 2.0).toInt()) {
                    0 -> baseFreq
                    else -> baseFreq * 1.25
                }
                val sampleValue = (sin(2.0 * Math.PI * freq * t) * 0.4 * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                val idx = i * 2
                pcmData[idx] = (sampleValue.toInt() and 0xFF).toByte()
                pcmData[idx + 1] = ((sampleValue.toInt() shr 8) and 0xFF).toByte()
            }

            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * 2

            val header = ByteArray(44)
            // RIFF header
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            // fmt chunk
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // Subchunk1Size
            header[20] = 1; header[21] = 0 // AudioFormat (1 = PCM)
            header[22] = 1; header[23] = 0 // NumChannels (1 = Mono)
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = 2; header[33] = 0 // BlockAlign
            header[34] = 16; header[35] = 0 // BitsPerSample
            // data chunk
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (pcmData.size and 0xff).toByte()
            header[41] = ((pcmData.size shr 8) and 0xff).toByte()
            header[42] = ((pcmData.size shr 16) and 0xff).toByte()
            header[43] = ((pcmData.size shr 24) and 0xff).toByte()

            FileOutputStream(file).use { fos ->
                fos.write(header)
                fos.write(pcmData)
            }
        } catch (_: Exception) {}
    }
}
