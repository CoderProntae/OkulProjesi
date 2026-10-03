package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.SchoolItem

@Composable
fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni Okul Klasörü Oluştur") },
        text = {
            Column {
                Text(
                    text = "Dersleriniz veya dinleme dosyalarınız için bir klasör adı belirleyin (Örn: İngilizce Dinlemeler, Matematik Notları):",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = {
                        folderName = it
                        errorText = null
                    },
                    label = { Text("Klasör Adı") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_folder_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderName.isBlank()) {
                        errorText = "Klasör adı boş olamaz"
                    } else {
                        onConfirm(folderName.trim())
                    }
                },
                modifier = Modifier.testTag("confirm_create_folder_btn")
            ) {
                Text("Oluştur")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("İptal")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun CreateTextFileDialog(
    onConfirm: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    var fileName by remember { mutableStateOf("") }
    var initialContent by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni Ders Notu (.txt) Oluştur") },
        text = {
            Column {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        errorText = null
                    },
                    label = { Text("Dosya Adı (Örn: Ders_Notlari)") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_file_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = initialContent,
                    onValueChange = { initialContent = it },
                    label = { Text("İlk Not / İçerik (İsteğe bağlı)") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (fileName.isBlank()) {
                        errorText = "Dosya adı boş olamaz"
                    } else {
                        onConfirm(fileName.trim(), initialContent)
                    }
                },
                modifier = Modifier.testTag("confirm_create_file_btn")
            ) {
                Text("Oluştur ve Kaydet")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("İptal")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun RenameItemDialog(
    item: SchoolItem,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(item.name) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeniden Adlandır") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        errorText = null
                    },
                    label = { Text("Yeni İsim") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_input_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isBlank()) {
                        errorText = "İsim boş bırakılamaz"
                    } else {
                        onConfirm(newName.trim())
                    }
                },
                modifier = Modifier.testTag("confirm_rename_btn")
            ) {
                Text("Değiştir")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("İptal")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun DeleteConfirmDialog(
    item: SchoolItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.isDirectory) "Klasörü Sil" else "Dosyayı Sil") },
        text = {
            Text(
                text = "'${item.name}' ${if (item.isDirectory) "klasörü ve içindeki tüm dosyalar kalıcı olarak silinecek." else "dosyası silinecek."} Emin misiniz?"
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.testTag("confirm_delete_btn")
            ) {
                Text("Sil")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Vazgeç")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
