package com.example.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.SchoolFileManager
import com.example.model.FolderAuditData
import com.example.model.ItemType
import com.example.model.SchoolItem
import com.example.player.AudioPlayerController
import com.example.player.AudioPlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class SchoolUiState(
    val items: List<SchoolItem> = emptyList(),
    val breadcrumbs: List<File> = emptyList(),
    val canNavigateUp: Boolean = false,
    val isGrid: Boolean = false,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val importProgressMessage: String? = null,
    val auditData: FolderAuditData? = null,
    val selectedTextItem: SchoolItem? = null,
    val textEditorContent: String = "",
    val selectedVideoItem: SchoolItem? = null,
    val selectedImageItem: SchoolItem? = null,
    val selectedPdfItem: SchoolItem? = null,
    val showCreateFolderDialog: Boolean = false,
    val showCreateTextFileDialog: Boolean = false,
    val itemToRename: SchoolItem? = null,
    val itemToDelete: SchoolItem? = null,
    val showFullAudioPlayer: Boolean = false
) {
    val filteredItems: List<SchoolItem>
        get() {
            if (searchQuery.isBlank()) return items
            val q = searchQuery.trim().lowercase()
            return items.filter { it.name.lowercase().contains(q) || it.extension.lowercase().contains(q) }
        }
}

class SchoolViewModel(application: Application) : AndroidViewModel(application) {

    private val fileManager = SchoolFileManager(application)
    val audioController = AudioPlayerController(application)

    private val _uiState = MutableStateFlow(SchoolUiState())
    val uiState: StateFlow<SchoolUiState> = _uiState.asStateFlow()

    val audioState: StateFlow<AudioPlayerState> = audioController.state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val items = fileManager.listItemsInCurrentDirectory()
            val crumbs = fileManager.getBreadcrumbs()
            val canUp = fileManager.currentDirectory.absolutePath != fileManager.rootWorkspaceDir.absolutePath

            _uiState.value = _uiState.value.copy(
                items = items,
                breadcrumbs = crumbs,
                canNavigateUp = canUp,
                isLoading = false
            )
        }
    }

    fun navigateTo(folder: File) {
        if (fileManager.navigateTo(folder)) {
            refresh()
        }
    }

    fun navigateUp() {
        if (fileManager.navigateUp()) {
            refresh()
        }
    }

    fun navigateToRoot() {
        fileManager.navigateToRoot()
        refresh()
    }

    fun setGridMode(isGrid: Boolean) {
        _uiState.value = _uiState.value.copy(isGrid = isGrid)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val result = fileManager.createFolder(name)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    showCreateFolderDialog = false,
                    statusMessage = "'$name' klasörü oluşturuldu"
                )
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Hata: ${result.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun createTextFile(name: String, content: String) {
        viewModelScope.launch {
            val result = fileManager.createTextFile(name, content)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    showCreateTextFileDialog = false,
                    statusMessage = "'$name' notu oluşturuldu"
                )
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Hata: ${result.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun rename(item: SchoolItem, newName: String) {
        viewModelScope.launch {
            val target = File(item.path)
            val result = fileManager.rename(target, newName)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    itemToRename = null,
                    statusMessage = "Yeniden adlandırıldı: $newName"
                )
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Hata: ${result.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun delete(item: SchoolItem) {
        viewModelScope.launch {
            val target = File(item.path)
            // If playing this item, stop audio
            if (audioController.state.value.currentItem?.path == item.path) {
                audioController.stop()
            }
            val result = fileManager.delete(target)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    itemToDelete = null,
                    statusMessage = "'${item.name}' silindi"
                )
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Silinemedi: ${result.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun importDirectoryTree(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                importProgressMessage = "Klasör ve alt dizin yapısı taranıyor..."
            )
            val res = fileManager.importDirectoryTree(treeUri) { progress ->
                _uiState.value = _uiState.value.copy(importProgressMessage = progress)
            }
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgressMessage = null,
                    statusMessage = "Klasör ve $count dosya başarıyla içe aktarıldı!"
                )
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgressMessage = null,
                    statusMessage = "İçe aktarma hatası: ${res.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                importProgressMessage = "${uris.size} dosya aktarılıyor..."
            )
            val res = fileManager.importFiles(uris) { progress ->
                _uiState.value = _uiState.value.copy(importProgressMessage = progress)
            }
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgressMessage = null,
                    statusMessage = "$count dosya klasöre kopyalandı!"
                )
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgressMessage = null,
                    statusMessage = "Aktarma hatası: ${res.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun openAudit() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val audit = fileManager.auditDirectory()
            _uiState.value = _uiState.value.copy(
                auditData = audit,
                isLoading = false
            )
        }
    }

    fun dismissAudit() {
        _uiState.value = _uiState.value.copy(auditData = null)
    }

    fun openItem(item: SchoolItem) {
        if (item.isDirectory) {
            navigateTo(File(item.path))
            return
        }

        when (item.itemType) {
            ItemType.AUDIO -> {
                audioController.play(item)
                _uiState.value = _uiState.value.copy(showFullAudioPlayer = true)
            }
            ItemType.VIDEO -> {
                _uiState.value = _uiState.value.copy(selectedVideoItem = item)
            }
            ItemType.TEXT -> {
                viewModelScope.launch {
                    val content = fileManager.readText(File(item.path))
                    _uiState.value = _uiState.value.copy(
                        selectedTextItem = item,
                        textEditorContent = content
                    )
                }
            }
            ItemType.IMAGE -> {
                _uiState.value = _uiState.value.copy(selectedImageItem = item)
            }
            ItemType.PDF -> {
                _uiState.value = _uiState.value.copy(selectedPdfItem = item)
            }
            ItemType.OTHER, ItemType.FOLDER -> {
                // Open with external system viewer via Intent
                openExternalFile(item)
            }
        }
    }

    fun saveTextFile(item: SchoolItem, content: String) {
        viewModelScope.launch {
            val result = fileManager.saveText(File(item.path), content)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "'${item.name}' kaydedildi"
                )
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Kaydedilemedi: ${result.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun shareItem(item: SchoolItem) {
        try {
            val context = getApplication<Application>()
            val file = File(item.path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mimeType = when (item.itemType) {
                ItemType.AUDIO -> "audio/*"
                ItemType.VIDEO -> "video/*"
                ItemType.PDF -> "application/pdf"
                ItemType.IMAGE -> "image/*"
                ItemType.TEXT -> "text/plain"
                else -> "*/*"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, item.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Paylaş: ${item.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Paylaşılamadı: ${e.localizedMessage}"
            )
        }
    }

    private fun openExternalFile(item: SchoolItem) {
        try {
            val context = getApplication<Application>()
            val file = File(item.path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Dosyayı Aç").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
    }

    fun dismissTextEditor() {
        _uiState.value = _uiState.value.copy(selectedTextItem = null)
    }

    fun dismissVideoPlayer() {
        _uiState.value = _uiState.value.copy(selectedVideoItem = null)
    }

    fun dismissImageViewer() {
        _uiState.value = _uiState.value.copy(selectedImageItem = null)
    }

    fun dismissPdfViewer() {
        _uiState.value = _uiState.value.copy(selectedPdfItem = null)
    }

    fun setShowCreateFolder(show: Boolean) {
        _uiState.value = _uiState.value.copy(showCreateFolderDialog = show)
    }

    fun setShowCreateTextFile(show: Boolean) {
        _uiState.value = _uiState.value.copy(showCreateTextFileDialog = show)
    }

    fun setItemToRename(item: SchoolItem?) {
        _uiState.value = _uiState.value.copy(itemToRename = item)
    }

    fun setItemToDelete(item: SchoolItem?) {
        _uiState.value = _uiState.value.copy(itemToDelete = item)
    }

    fun setShowFullAudioPlayer(show: Boolean) {
        _uiState.value = _uiState.value.copy(showFullAudioPlayer = show)
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun backupWorkspace() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val res = fileManager.backupWorkspaceToZip()
            if (res.isSuccess) {
                val f = res.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = "Tüm dosyalar İndirilenler klasörüne yedeklendi: ${f.name}"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = "Yedekleme hatası: ${res.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun recoverPreviousFiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val count = fileManager.migrateFromPreviousVersions()
            refresh()
            _uiState.value = _uiState.value.copy(
                statusMessage = if (count > 0) {
                    "Önceki sürümlerden $count dosya ve alt klasör başarıyla geri yüklendi!"
                } else {
                    "Tüm dosyalarınız zaten güncel ve mevcut."
                }
            )
        }
    }

    fun restoreFromZip(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, importProgressMessage = "ZIP yedek arşivi açılıyor...")
            val res = fileManager.restoreWorkspaceFromZip(uri)
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                refresh()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgressMessage = null,
                    statusMessage = "ZIP yedeğindeki $count dosya ve alt dizin yapısı eksiksiz geri yüklendi!"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    importProgressMessage = null,
                    statusMessage = "Geri yükleme hatası: ${res.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioController.close()
    }
}
