package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AudioPlayerBar
import com.example.ui.components.AudioPlayerDialog
import com.example.ui.components.BreadcrumbBar
import com.example.ui.components.CreateFolderDialog
import com.example.ui.components.CreateTextFileDialog
import com.example.ui.components.DeleteConfirmDialog
import com.example.ui.components.FolderAuditSheet
import com.example.ui.components.ImageViewerDialog
import com.example.ui.components.PdfViewerDialog
import com.example.ui.components.RenameItemDialog
import com.example.ui.components.SchoolItemCard
import com.example.ui.components.TextEditorDialog
import com.example.ui.components.VideoPlayerDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolWorkspaceScreen(
    viewModel: SchoolViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val audioState by viewModel.audioState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearchExpanded by remember { mutableStateOf(false) }

    // SAF Launchers for importing folders and files from device
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.importDirectoryTree(it) }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importFiles(uris)
        }
    }

    // Handle status message snackbars
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.School,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Okul Dosyaları",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Dersler & Dinleme Arşivi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { isSearchExpanded = !isSearchExpanded },
                            modifier = Modifier.testTag("action_toggle_search")
                        ) {
                            Icon(
                                imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Ara"
                            )
                        }

                        IconButton(
                            onClick = { viewModel.openAudit() },
                            modifier = Modifier.testTag("action_open_audit")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = "Klasör Denetimi & İstatistik",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = { viewModel.setGridMode(!uiState.isGrid) },
                            modifier = Modifier.testTag("action_toggle_view_mode")
                        ) {
                            Icon(
                                imageVector = if (uiState.isGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = "Görünüm Değiştir"
                            )
                        }

                        IconButton(
                            onClick = { viewModel.refresh() },
                            modifier = Modifier.testTag("action_refresh")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Yenile"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Search Bar Expandable
                AnimatedVisibility(visible = isSearchExpanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Ders, ses veya dosya adı ara...") },
                            singleLine = true,
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Temizle")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("search_text_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                // Interactive Breadcrumb Navigation Bar
                BreadcrumbBar(
                    breadcrumbs = uiState.breadcrumbs,
                    canNavigateUp = uiState.canNavigateUp,
                    onNavigateTo = { viewModel.navigateTo(it) },
                    onNavigateUp = { viewModel.navigateUp() }
                )

                // Quick Import & Action Chips Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("import_folder_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFolderUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cihazdan Klasör Aktar", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("import_files_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Dosya Aktar", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.setShowCreateFolder(true) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("new_folder_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Yeni Klasör", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.setShowCreateTextFile(true) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("new_text_file_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.NoteAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Yeni Not (.txt)", fontSize = 12.sp)
                    }
                }

                // Import progress indicator
                if (uiState.isLoading && uiState.importProgressMessage != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.importProgressMessage ?: "İşlem sürüyor...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { folderPickerLauncher.launch(null) },
                icon = { Icon(Icons.Default.DriveFolderUpload, contentDescription = null) },
                text = { Text("Klasör Aktar") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_import_folder")
            )
        },
        bottomBar = {
            // Sticky audio listening mini-bar
            AudioPlayerBar(
                playerState = audioState,
                onTogglePlayPause = { viewModel.audioController.togglePlayPause() },
                onExpandPlayer = { viewModel.setShowFullAudioPlayer(true) },
                onClose = { viewModel.audioController.close() }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val items = uiState.filteredItems

            if (items.isEmpty() && !uiState.isLoading) {
                // Empty state card
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(90.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (uiState.searchQuery.isNotBlank()) "Aramaya uygun dosya bulunamadı" else "Bu Klasör Boş",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (uiState.searchQuery.isNotBlank()) {
                            "'${uiState.searchQuery}' aramasıyla eşleşen bir öge bulunamadı."
                        } else {
                            "Telefonunuzdaki İndirilenler klasöründen okul derslerinizi, İngilizce dinleme kayıtlarınızı veya notlarınızı buraya aktarabilirsiniz."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    if (uiState.searchQuery.isBlank()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { folderPickerLauncher.launch(null) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.DriveFolderUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cihazdan Aktar")
                            }

                            FilledTonalButton(
                                onClick = { viewModel.setShowCreateFolder(true) }
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Yeni Klasör")
                            }
                        }
                    }
                }
            } else if (uiState.isGrid) {
                // Grid Mode
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.path }) { item ->
                        SchoolItemCard(
                            item = item,
                            isGrid = true,
                            onClick = { viewModel.openItem(item) },
                            onRename = { viewModel.setItemToRename(item) },
                            onDelete = { viewModel.setItemToDelete(item) },
                            onShare = { viewModel.shareItem(item) }
                        )
                    }
                }
            } else {
                // List Mode
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.path }) { item ->
                        SchoolItemCard(
                            item = item,
                            isGrid = false,
                            onClick = { viewModel.openItem(item) },
                            onRename = { viewModel.setItemToRename(item) },
                            onDelete = { viewModel.setItemToDelete(item) },
                            onShare = { viewModel.shareItem(item) }
                        )
                    }
                }
            }
        }
    }

    // Modal Sheet: Klasör Denetimi / Analiz
    uiState.auditData?.let { audit ->
        FolderAuditSheet(
            auditData = audit,
            onDismiss = { viewModel.dismissAudit() }
        )
    }

    // Full Audio Player Modal
    if (uiState.showFullAudioPlayer && audioState.currentItem != null) {
        AudioPlayerDialog(
            playerState = audioState,
            onTogglePlayPause = { viewModel.audioController.togglePlayPause() },
            onSeekTo = { viewModel.audioController.seekTo(it) },
            onSkipForward = { viewModel.audioController.skipForward(10) },
            onSkipBackward = { viewModel.audioController.skipBackward(10) },
            onSetSpeed = { viewModel.audioController.setSpeed(it) },
            onToggleLoop = { viewModel.audioController.toggleLoop() },
            onDismiss = { viewModel.setShowFullAudioPlayer(false) }
        )
    }

    // Video Player Modal
    uiState.selectedVideoItem?.let { videoItem ->
        VideoPlayerDialog(
            item = videoItem,
            onDismiss = { viewModel.dismissVideoPlayer() }
        )
    }

    // Text Viewer & Editor Modal
    uiState.selectedTextItem?.let { textItem ->
        TextEditorDialog(
            item = textItem,
            initialContent = uiState.textEditorContent,
            onSave = { newContent -> viewModel.saveTextFile(textItem, newContent) },
            onDismiss = { viewModel.dismissTextEditor() }
        )
    }

    // Image Viewer Modal
    uiState.selectedImageItem?.let { imgItem ->
        ImageViewerDialog(
            item = imgItem,
            onDismiss = { viewModel.dismissImageViewer() }
        )
    }

    // PDF Viewer Modal
    uiState.selectedPdfItem?.let { pdfItem ->
        PdfViewerDialog(
            item = pdfItem,
            onDismiss = { viewModel.dismissPdfViewer() }
        )
    }

    // Create Folder Dialog
    if (uiState.showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name -> viewModel.createFolder(name) },
            onDismiss = { viewModel.setShowCreateFolder(false) }
        )
    }

    // Create Text File Dialog
    if (uiState.showCreateTextFileDialog) {
        CreateTextFileDialog(
            onConfirm = { name, content -> viewModel.createTextFile(name, content) },
            onDismiss = { viewModel.setShowCreateTextFile(false) }
        )
    }

    // Rename Dialog
    uiState.itemToRename?.let { item ->
        RenameItemDialog(
            item = item,
            onConfirm = { newName -> viewModel.rename(item, newName) },
            onDismiss = { viewModel.setItemToRename(null) }
        )
    }

    // Delete Dialog
    uiState.itemToDelete?.let { item ->
        DeleteConfirmDialog(
            item = item,
            onConfirm = { viewModel.delete(item) },
            onDismiss = { viewModel.setItemToDelete(null) }
        )
    }
}
