package com.example.data

import android.content.Context
import android.net.Uri
import android.os.Environment
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sin

class SchoolFileManager(private val context: Context) {

    val rootWorkspaceDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "OkulDizini")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    // Public Documents or Download directory which is NEVER deleted on app update/uninstall
    val persistentBackupDir: File? by lazy {
        try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val d = File(docs, "OkulDizini")
            if (!d.exists()) d.mkdirs()
            if (d.exists()) d else {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dlDir = File(dl, "OkulDizini")
                if (!dlDir.exists()) dlDir.mkdirs()
                if (dlDir.exists()) dlDir else null
            }
        } catch (_: Exception) {
            null
        }
    }

    var currentDirectory: File = rootWorkspaceDir
        private set

    init {
        // 1. Remove any legacy template sample folders that were automatically seeded
        cleanupLegacySampleContent()
        // 2. Search and migrate real user files from any previous versions or public storage
        migrateFromPreviousVersions()
        // 3. Keep persistent public mirror updated with user files
        mirrorToPersistentStorage()
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

    suspend fun listItemsInCurrentDirectory(): List<SchoolItem> = listItemsInDirectory(currentDirectory)

    suspend fun listItemsInDirectory(dir: File): List<SchoolItem> = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext emptyList()
        val list = files.filter { !it.name.startsWith(".") }.map { file ->
            val isDir = file.isDirectory
            val ext = if (isDir) "" else file.extension
            val type = ItemType.fromExtension(ext, isDir)
            val childCount = if (isDir) (file.listFiles()?.count { !it.name.startsWith(".") } ?: 0) else 0
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
            mirrorToPersistentStorage()
            Result.success(target)
        } else {
            Result.failure(Exception("Klasör oluşturulamadı"))
        }
    }

    suspend fun buildFullWorkspaceHierarchy(maxDepth: Int = 3): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        fun traverse(dir: File, depth: Int, indent: String) {
            if (depth > maxDepth) return
            val files = dir.listFiles() ?: return
            val sorted = files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            for (f in sorted) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    val count = f.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                    sb.append("$indent📁 ${f.name}/ ($count öge)\n")
                    traverse(f, depth + 1, "$indent  ")
                } else {
                    val ext = f.extension.lowercase()
                    val icon = when (ext) {
                        "txt", "md" -> "📄"
                        "mp3", "wav", "m4a", "ogg" -> "🎵"
                        "mp4", "mkv" -> "🎬"
                        "pdf" -> "📕"
                        "jpg", "png", "webp" -> "🖼️"
                        else -> "📎"
                    }
                    val preview = if (ext in listOf("txt", "md") && f.length() < 200_000) {
                        try {
                            val head = f.readText().lines().filter { it.isNotBlank() }.take(2).joinToString(" | ")
                            if (head.isNotBlank()) " [Özet: ${head.take(80)}...]" else ""
                        } catch (_: Exception) { "" }
                    } else ""
                    sb.append("$indent$icon ${f.name}$preview\n")
                }
            }
        }
        sb.append("📁 OkulDizini/ (Kök Çalışma Alanı)\n")
        traverse(rootWorkspaceDir, 1, "  ")
        sb.toString()
    }

    fun findAssociatedTranscript(audioFileName: String): String? {
        val baseName = audioFileName.substringBeforeLast(".")
        val searchDirs = listOf(currentDirectory, rootWorkspaceDir)
        for (dir in searchDirs) {
            val directMatch = File(dir, "$baseName.txt")
            if (directMatch.exists() && directMatch.isFile) return directMatch.readText()
            val transcriptMatch = File(dir, "${baseName}_transcript.txt")
            if (transcriptMatch.exists() && transcriptMatch.isFile) return transcriptMatch.readText()
            val srtMatch = File(dir, "$baseName.srt")
            if (srtMatch.exists() && srtMatch.isFile) return srtMatch.readText()
            val lrcMatch = File(dir, "$baseName.lrc")
            if (lrcMatch.exists() && lrcMatch.isFile) return lrcMatch.readText()
            val related = dir.listFiles { _, name ->
                name.endsWith(".txt", ignoreCase = true) &&
                (name.contains("ders", ignoreCase = true) || name.contains("kelime", ignoreCase = true) || name.contains("plan", ignoreCase = true))
            }
            if (!related.isNullOrEmpty()) {
                return related.first().readText()
            }
        }
        return null
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

    suspend fun findFile(name: String): File? = withContext(Dispatchers.IO) {
        val clean = name.trim().lowercase()
        // Check current directory first
        val direct = File(currentDirectory, name)
        if (direct.exists()) return@withContext direct

        // Deep search in workspace
        fun searchDir(dir: File): File? {
            val list = dir.listFiles() ?: return null
            for (f in list) {
                if (f.name.equals(name, ignoreCase = true) || f.name.lowercase().contains(clean)) {
                    return f
                }
                if (f.isDirectory) {
                    val found = searchDir(f)
                    if (found != null) return found
                }
            }
            return null
        }
        searchDir(rootWorkspaceDir)
    }

    suspend fun updateTextFile(nameOrPath: String, newContent: String): Result<File> = withContext(Dispatchers.IO) {
        val target = findFile(nameOrPath) ?: File(currentDirectory, sanitizeFileName(nameOrPath))
        try {
            target.writeText(newContent, Charsets.UTF_8)
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

    fun isTextFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in listOf("txt", "md", "json", "csv", "srt", "lrc", "vtt", "xml", "html", "htm", "py", "kt", "c", "cpp", "js", "java", "ts", "css", "log", "ini", "conf", "sh", "bat", "ps1")
    }

    suspend fun delete(target: File): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Also delete from persistentBackupDir so it's not restored again
            val relPath = target.relativeToOrNull(rootWorkspaceDir)?.path
            if (relPath != null && persistentBackupDir != null) {
                val mirrorTarget = File(persistentBackupDir, relPath)
                if (mirrorTarget.exists()) {
                    if (mirrorTarget.isDirectory) mirrorTarget.deleteRecursively() else mirrorTarget.delete()
                }
            }

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
        if (!file.exists()) return@withContext "Dosya bulunamadı: ${file.name}"
        if (!isTextFile(file)) {
            val ext = file.extension.lowercase()
            return@withContext when {
                ext in listOf("mp3", "wav", "m4a", "ogg", "aac", "flac") -> {
                    val associated = findAssociatedTranscript(file.name)
                    if (associated != null) {
                        "Ses Kaydı: ${file.name}\nİlişkili Transkript/Ders Notu:\n$associated"
                    } else {
                        "Ses Dosyası: ${file.name} (${file.length() / 1024} KB, İkili Ses Kaydı)"
                    }
                }
                ext in listOf("mp4", "mkv", "webm", "avi", "mov", "3gp") -> "Video Dosyası: ${file.name} (${file.length() / 1024} KB, Video Kaydı)"
                ext in listOf("jpg", "jpeg", "png", "webp") -> "Görsel Dosyası: ${file.name} (${file.length() / 1024} KB, Fotoğraf)"
                ext == "pdf" -> "PDF Dokümanı: ${file.name} (${file.length() / 1024} KB)"
                else -> "İkili Dosya: ${file.name} (${file.extension.uppercase()}, ${file.length() / 1024} KB)"
            }
        }
        try {
            file.readText(Charsets.UTF_8).take(30000)
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
     * Removes any legacy template sample folders (İngilizce Dinleme, Matematik, Fizik)
     * so that the workspace only ever contains real user files and folders.
     */
    private fun cleanupLegacySampleContent() {
        try {
            val dummyNames = listOf(
                "İngilizce Dinleme (Listening)",
                "Matematik ve Geometri",
                "Fizik ve Fen Bilimleri"
            )
            val searchDirs = listOfNotNull(rootWorkspaceDir, persistentBackupDir)
            for (parent in searchDirs) {
                for (name in dummyNames) {
                    val dir = File(parent, name)
                    if (dir.exists() && dir.isDirectory) {
                        val files = dir.listFiles()?.map { it.name } ?: emptyList()
                        val dummyFiles = listOf(
                            "Unit_1_Daily_Routines.wav",
                            "Unit_2_School_Life_Listening.wav",
                            "Ders_Plani_ve_Kelimeler.txt",
                            "Formuller_ve_Teoremler.txt",
                            "Haftalik_Calisma_Programi.txt",
                            "Laboratuvar_Guvenlik_Notlari.txt"
                        )
                        // Only delete if it exclusively contains template files
                        if (files.isEmpty() || files.all { it in dummyFiles }) {
                            dir.deleteRecursively()
                        }
                    }
                }
                val marker = File(parent, ".initialized")
                if (marker.exists()) marker.delete()
            }
        } catch (_: Exception) {}
    }

    /**
     * Searches for real user files from persistent storage / previous package installs
     * and migrates them into current workspace.
     */
    fun migrateFromPreviousVersions(): Int {
        var restoredCount = 0
        try {
            val candidateSourceDirs = mutableListOf<File>()

            // 1. Check persistent public Documents & Download directory
            persistentBackupDir?.let { if (it.exists() && it.absolutePath != rootWorkspaceDir.absolutePath) candidateSourceDirs.add(it) }
            val docs = File("/storage/emulated/0/Documents/OkulDizini")
            if (docs.exists() && docs.absolutePath != rootWorkspaceDir.absolutePath) candidateSourceDirs.add(docs)
            val downloads = File("/storage/emulated/0/Download/OkulDizini")
            if (downloads.exists() && downloads.absolutePath != rootWorkspaceDir.absolutePath) candidateSourceDirs.add(downloads)

            // 2. Check previous internal files directory
            val internalFiles = File(context.filesDir, "OkulDizini")
            if (internalFiles.exists() && internalFiles.absolutePath != rootWorkspaceDir.absolutePath) candidateSourceDirs.add(internalFiles)

            // 3. Check Android/data of known past package names
            val extAndroidData = File("/storage/emulated/0/Android/data")
            if (extAndroidData.exists() && extAndroidData.isDirectory) {
                val pastPackages = listOf(
                    "com.example",
                    "com.example.myapplication",
                    "com.aistudio.okuldosyalari.skljwm"
                )
                for (pkg in pastPackages) {
                    val pkgDir = File(extAndroidData, "$pkg/files/OkulDizini")
                    if (pkgDir.exists() && pkgDir.absolutePath != rootWorkspaceDir.absolutePath) {
                        candidateSourceDirs.add(pkgDir)
                    }
                }
            }

            val dummyNames = listOf(
                "İngilizce Dinleme (Listening)",
                "Matematik ve Geometri",
                "Fizik ve Fen Bilimleri"
            )

            for (srcDir in candidateSourceDirs.distinctBy { it.absolutePath }) {
                val files = srcDir.listFiles() ?: continue
                for (f in files) {
                    if (f.name in dummyNames) continue
                    restoredCount += copyRecursivelyWithoutOverwriting(f, File(rootWorkspaceDir, f.name))
                }
            }
        } catch (_: Exception) {}
        return restoredCount
    }

    private fun copyRecursivelyWithoutOverwriting(source: File, target: File): Int {
        var count = 0
        if (!source.exists()) return 0
        if (source.isDirectory) {
            if (!target.exists()) target.mkdirs()
            val files = source.listFiles() ?: return 0
            for (f in files) {
                count += copyRecursivelyWithoutOverwriting(f, File(target, f.name))
            }
        } else {
            if (!target.exists() || target.length() == 0L) {
                try {
                    source.copyTo(target, overwrite = true)
                    count++
                } catch (_: Exception) {}
            }
        }
        return count
    }

    /**
     * Mirrors all files in rootWorkspaceDir to persistent public storage
     * so that if the user deletes the app or updates, the files remain safe.
     */
    fun mirrorToPersistentStorage() {
        val dest = persistentBackupDir ?: return
        try {
            copyRecursivelyWithoutOverwriting(rootWorkspaceDir, dest)
        } catch (_: Exception) {}
    }

    /**
     * Creates a full ZIP archive backup of the entire school workspace
     * in the phone's Download folder.
     */
    fun backupWorkspaceToZip(): Result<File> {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val zipFile = File(downloadDir, "OkulDizini_Yedek_$timestamp.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                fun addDirToZip(dir: File, baseDir: File) {
                    val files = dir.listFiles() ?: return
                    for (file in files) {
                        if (file.isDirectory) {
                            addDirToZip(file, baseDir)
                        } else {
                            val relativePath = file.relativeTo(baseDir).path
                            val entry = ZipEntry(relativePath)
                            zos.putNextEntry(entry)
                            FileInputStream(file).use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
                addDirToZip(rootWorkspaceDir, rootWorkspaceDir)
            }

            mirrorToPersistentStorage()
            Result.success(zipFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
