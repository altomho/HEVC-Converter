package com.example.hevcconverter

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Classe de données pour stocker les métadonnées de chaque vidéo
data class VideoInfo(
    val uri: Uri,
    val name: String,
    val sizeMb: Double,
    val duration: String,
    val resolution: String,
    val codec: String,
    val isHevc: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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
    val coroutineScope = rememberCoroutineScope()
    
    var videoList by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isConverting by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Sélectionnez vos vidéos pour analyse.") }
    var isLoadingMetadata by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isLoadingMetadata = true
            statusText = "Analyse des vidéos en cours..."
            
            // Extraction des métadonnées en arrière-plan pour ne pas bloquer l'interface
            coroutineScope.launch(Dispatchers.IO) {
                val infos = uris.map { getVideoInfo(context, it) }
                withContext(Dispatchers.Main) {
                    videoList = infos
                    isLoadingMetadata = false
                    val toConvert = infos.count { !it.isHevc }
                    statusText = "${infos.size} vidéo(s) ajoutée(s). $toConvert nécessitent une conversion."
                }
            }
        }
    }

    // Liste des vidéos filtrée (uniquement celles qui ne sont pas en HEVC)
    val videosToProcess = videoList.filter { !it.isHevc }

    fun processNextVideo(index: Int) {
        if (index >= videosToProcess.size) {
            isConverting = false
            currentIndex = -1
            statusText = "Toutes les conversions sont terminées ! (Vérifiez DCIM/Camera_HEVC)"
            Toast.makeText(context, "Lot terminé avec succès !", Toast.LENGTH_LONG).show()
            return
        }

        currentIndex = index
        val currentVideo = videosToProcess[index]
        statusText = "Conversion : ${currentVideo.name} (${index + 1} / ${videosToProcess.size})..."

        val tempFile = File(context.cacheDir, "temp_hevc_${index}.mp4")
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(currentVideo.uri)).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265) // Codec cible HEVC
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(context, tempFile, currentVideo.name)
                    tempFile.delete()
                    processNextVideo(index + 1)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Toast.makeText(context, "Erreur sur ${currentVideo.name}", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("HEVC Analyzer & Converter", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { launcher.launch("video/*") },
                enabled = !isConverting && !isLoadingMetadata
            ) {
                Text("Ajouter des vidéos")
            }

            Button(
                onClick = {
                    if (videosToProcess.isNotEmpty()) {
                        isConverting = true
                        processNextVideo(0)
                    }
                },
                enabled = !isConverting && !isLoadingMetadata && videosToProcess.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Convertir (${videosToProcess.size})")
            }
        }

        if (isConverting || isLoadingMetadata) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        var showOnlyToConvert by remember { mutableStateOf(false) }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Masquer les vidéos déjà optimisées", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = showOnlyToConvert,
                onCheckedChange = { showOnlyToConvert = it }
            )
        }
        // ----------------------------------------

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // On applique le filtre pour l'affichage visuel
        val displayedVideos = if (showOnlyToConvert) videosToProcess else videoList

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(displayedVideos) { _, video ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isConverting && videosToProcess.getOrNull(currentIndex)?.uri == video.uri) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = video.name, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Taille : ${String.format("%.1f", video.sizeMb)} Mo | Durée : ${video.duration}")
                        Text(text = "Résolution : ${video.resolution} | Codec original : ${video.codec}")
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        if (video.isHevc) {
                            Text("✓ Déjà optimisé (Ignoré)", color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("⚠ Nécessite une conversion H.265", color = Color(0xFFFF9800), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// Fonction utilitaire pour extraire les métadonnées réelles d'une vidéo
fun getVideoInfo(context: Context, uri: Uri): VideoInfo {
    var name = "Fichier inconnu"
    var sizeMb = 0.0
    var codec = "Inconnu"
    var isHevc = false
    var resolution = "Inconnue"
    var duration = "00:00"

    // 1. Récupération du nom et de la taille via le ContentResolver
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) sizeMb = cursor.getLong(sizeIndex) / (1024.0 * 1024.0)
            }
        }
    } catch (e: Exception) { e.printStackTrace() }

    // 2. Extraction du Codec, Résolution et Durée via MediaExtractor
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            
            if (mime.startsWith("video/")) {
                codec = mime.replace("video/", "").uppercase()
                // Vérification du format H265 / HEVC
                isHevc = (mime == MimeTypes.VIDEO_H265 || codec == "HEVC" || codec == "H265")
                
                val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                if (width > 0 && height > 0) resolution = "${width}x${height}"
                
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    val sec = (durationUs / 1000000) % 60
                    val min = (durationUs / 1000000) / 60
                    duration = String.format("%02d:%02d", min, sec)
                }
                break // On a trouvé la piste vidéo, on arrête la boucle
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        extractor.release()
    }

    return VideoInfo(uri, name, sizeMb, duration, resolution, codec, isHevc)
}

// Sauvegarde dans la galerie avec un nom sécurisé pour éviter d'écraser l'original
fun saveToGallery(context: Context, sourceFile: File, originalName: String) {
    val resolver = context.contentResolver
    val safeName = originalName.substringBeforeLast(".") // Enlève l'extension originale
    
    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "${safeName}_HEVC.mp4")
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
