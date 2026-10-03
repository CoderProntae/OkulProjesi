package com.example.model

enum class ItemType(
    val titleTr: String,
    val iconName: String
) {
    FOLDER("Klasör", "folder"),
    AUDIO("Ses & Dinleme", "music_note"),
    VIDEO("Video & Ders Kaydı", "movie"),
    TEXT("Metin & Not", "description"),
    PDF("PDF Belgesi", "picture_as_pdf"),
    IMAGE("Görsel / Fotoğraf", "image"),
    OTHER("Diğer Dosya", "insert_drive_file");

    companion object {
        fun fromExtension(ext: String, isDir: Boolean): ItemType {
            if (isDir) return FOLDER
            return when (ext.lowercase()) {
                "mp3", "wav", "m4a", "aac", "ogg", "flac", "wma", "opus" -> AUDIO
                "mp4", "mkv", "webm", "avi", "mov", "3gp", "flv" -> VIDEO
                "txt", "md", "csv", "json", "xml", "log", "rtf", "doc", "docx" -> TEXT
                "pdf" -> PDF
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg" -> IMAGE
                else -> OTHER
            }
        }
    }
}

data class SchoolItem(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String,
    val itemType: ItemType,
    val childCount: Int = 0
) {
    val formattedSize: String
        get() {
            if (isDirectory) {
                return if (childCount == 1) "1 öge" else "$childCount öge"
            }
            if (sizeBytes < 1024) return "$sizeBytes B"
            val kb = sizeBytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024.0
            return "%.2f GB".format(gb)
        }
}

data class FolderAuditData(
    val folderName: String,
    val totalSizeBytes: Long,
    val totalFilesCount: Int,
    val totalFoldersCount: Int,
    val audioCount: Int,
    val audioSizeBytes: Long,
    val videoCount: Int,
    val videoSizeBytes: Long,
    val textCount: Int,
    val textSizeBytes: Long,
    val pdfCount: Int,
    val pdfSizeBytes: Long,
    val imageCount: Int,
    val imageSizeBytes: Long,
    val otherCount: Int,
    val otherSizeBytes: Long,
    val largestFiles: List<SchoolItem> = emptyList()
) {
    val formattedTotalSize: String
        get() {
            if (totalSizeBytes < 1024) return "$totalSizeBytes B"
            val kb = totalSizeBytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024.0
            return "%.2f GB".format(gb)
        }
}
