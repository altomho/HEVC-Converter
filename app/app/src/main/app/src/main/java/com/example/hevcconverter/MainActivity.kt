package com.example.hevcconverter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConverterScreen()
                }
            }
        }
    }
}

@Composable
fun ConverterScreen() {
    val context = LocalContext.current
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isConverting by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Sélectionnez vos vidéos à convertir.") }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = uris
            statusText = "${uris.size} vidéo(s) sélectionnée(s)"
        }
    }

    fun processNextVideo(index: Int) {
        if (index >= selectedUris.size) {
            isConverting = false
            currentIndex = -1
            statusText = "Conversion terminée ! Fichiers sauvés dans DCIM/Camera_HEVC"
            Toast.makeText(context, "Terminé avec succès !", Toast.LENGTH_LONG).show()
            return
        }

        currentIndex = index
        val uri = selectedUris[index]
        statusText = "Traitement vidéo ${index + 1} / ${selectedUris.size} (HEVC)..."

        val tempFile = File(context.cacheDir, "temp_hevc_${index}.mp4")

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri)).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265) // Force l'encodeur matériel HEVC (Exynos)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(context, tempFile)
                    tempFile.delete()
                    processNextVideo(index + 1)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Toast.makeText(context, "Erreur vidéo ${index + 1}: ${exportException.message}", Toast.LENGTH_SHORT).show()
                    processNextVideo(index + 1)
                }
            })
            .build()

        try {
            transformer.start(editedMediaItem, tempFile.absolutePath)
        } catch (e: Exception) {
            processNextVideo(index + 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("HEVC Batch Converter", style = MaterialTheme.typography.headlineMedium)
        Text(statusText, style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { launcher.launch("video/*") },
                enabled = !isConverting
            ) {
                Text("Choisir vidéos")
            }

            Button(
                onClick = {
                    if (selectedUris.isNotEmpty()) {
                        isConverting = true
                        processNextVideo(0)
                    }
                },
                enabled = !isConverting && selectedUris.isNotEmpty()
            ) {
                Text("Convertir tout")
            }
        }

        if (isConverting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(selectedUris) { idx, uri ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (idx == currentIndex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Text(
                        text = "Vidéo #${idx + 1} : ${uri.lastPathSegment ?: "Fichier vidéo"}",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

fun saveToGallery(context: Context, sourceFile: File) {
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "HEVC_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera_HEVC")
        }
    }
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let { destUri ->
        resolver.openOutputStream(destUri)?.use { out ->
            sourceFile.inputStream().use { inStream -> inStream.copyTo(out) }
        }
    }
}
