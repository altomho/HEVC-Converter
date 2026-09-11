package com.example.hevcconverter

import android.app.Activity
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class SortCriterion { SIZE, DATE, FORMAT }

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val sizeMb: Double,
    val dateModified: Long,
    val duration: String,
    val resolution: String,
    val codec: String,
    val isHevc: Boolean,
    var targetUri: Uri? = null,
    var targetSizeMb: Double? = null
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var videoList by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isConverting by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Sélectionnez vos vidéos pour analyse.") }
    var isLoadingMetadata by remember { mutableStateOf(false) }

    var sortCriterion by remember { mutableStateOf(SortCriterion.DATE) }
    var sortAscending by remember { mutableStateOf(false) }
    var showOnlyToConvert by remember { mutableStateOf(false) }

    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteSourceUri by remember { mutableStateOf<Uri?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteSourceUri?.let { uri ->
                videoList = videoList.filterNot { it.uri == uri }
                Toast.makeText(context, "Fichier source supprimé", Toast.LENGTH_SHORT).show()
            }
        }
        pendingDeleteSourceUri = null
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isLoadingMetadata = true
            statusText = "Analyse des métadonnées..."
            coroutineScope.launch(Dispatchers.IO) {
                val infos = uris.map { getVideoInfo(context, it) }
                withContext(Dispatchers.Main) {
                    videoList = (videoList + infos).distinctBy { it.uri }
                    isLoadingMetadata = false
                    val toConvert = videoList.count { !it.isHevc }
                    statusText = "${videoList.size} vidéo(s) chargée(s). $toConvert à convertir."
                }
            }
        }
    }

    val processedList = remember(videoList, sortCriterion, sortAscending, showOnlyToConvert) {
        var list = if (showOnlyToConvert) videoList.filter { !it.isHevc } else videoList
        list = when (sortCriterion) {
            SortCriterion.SIZE -> if (sortAscending) list.sortedBy { it.sizeBytes } else list.sortedByDescending { it.sizeBytes }
            SortCriterion.DATE -> if (sortAscending) list.sortedBy { it.dateModified } else list.sortedByDescending { it.dateModified }
            SortCriterion.FORMAT -> if (sortAscending) list.sortedBy { it.codec } else list.sortedByDescending { it.codec }
        }
        list
    }

    val videosToProcess = videoList.filter { !it.isHevc }

    fun processNextVideo(index: Int) {
        if (index >= videosToProcess.size) {
            isConverting = false
            currentIndex = -1
            statusText = "Conversions terminées !"
            Toast.makeText(context, "Traitement par lot terminé !", Toast.LENGTH_LONG).show()
            return
        }

        currentIndex = index
        val currentVideo = videosToProcess[index]
        statusText = "Conversion (${index + 1}/${videosToProcess.size}) : ${currentVideo.name}..."

        val tempFile = File(context.cacheDir, "temp_hevc_${System.currentTimeMillis()}.mp4")
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(currentVideo.uri)).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val targetUri = saveToGallery(context, tempFile, currentVideo.name)
                    tempFile.delete()

                    val targetSizeMb = targetUri?.let { getFileSizeMb(context, it) } ?: 0.0

                    videoList = videoList.map {
                        if (it.uri == currentVideo.uri) {
                            it.copy(targetUri = targetUri, targetSizeMb = targetSizeMb)
                        } else it
                    }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convertisseur HEVC Pro", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { pickerLauncher.launch("video/*") }, enabled = !isConverting) {
                        Icon(Icons.Default.Add, contentDescription = "Ajouter")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sortCriterion == SortCriterion.DATE,
                        onClick = {
                            if (sortCriterion == SortCriterion.DATE) sortAscending = !sortAscending
                            else { sortCriterion = SortCriterion.DATE; sortAscending = false }
                        },
                        label = { Text("Date ${if (sortCriterion == SortCriterion.DATE) (if (sortAscending) "↑" else "↓") else ""}") }
                    )
                    FilterChip(
                        selected = sortCriterion == SortCriterion.SIZE,
                        onClick = {
                            if (sortCriterion == SortCriterion.SIZE) sortAscending = !sortAscending
                            else { sortCriterion = SortCriterion.SIZE; sortAscending = false }
                        },
                        label = { Text("Taille ${if (sortCriterion == SortCriterion.SIZE) (if (sortAscending) "↑" else "↓") else ""}") }
                    )
                    FilterChip(
                        selected = sortCriterion == SortCriterion.FORMAT,
                        onClick = {
                            if (sortCriterion == SortCriterion.FORMAT) sortAscending = !sortAscending
                            else { sortCriterion = SortCriterion.FORMAT; sortAscending = false }
                        },
                        label = { Text("Codec ${if (sortCriterion == SortCriterion.FORMAT) (if (sortAscending) "↑" else "↓") else ""}") }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Masquer les vidéos déjà optimisées", style = MaterialTheme.typography.bodySmall)
                Switch(checked = showOnlyToConvert, onCheckedChange = { showOnlyToConvert = it })
            }

            Button(
                onClick = {
                    if (videosToProcess.isNotEmpty()) {
                        isConverting = true
                        processNextVideo(0)
                    }
                },
                enabled = !isConverting && !isLoadingMetadata && videosToProcess.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Lancer la conversion (${videosToProcess.size})")
            }

            if (isConverting || isLoadingMetadata) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(processedList, key = { it.uri.toString() }) { video ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isConverting && videosToProcess.getOrNull(currentIndex)?.uri == video.uri)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(video.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { videoList = videoList.filterNot { it.uri == video.uri } },
                                    enabled = !isConverting
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Retirer", tint = Color.Red)
                                }
                            }

                            Text("Source : ${String.format("%.1f", video.sizeMb)} Mo | ${video.resolution} | ${video.codec}", style = MaterialTheme.typography.bodySmall)
                            Text("Durée : ${video.duration} | Date : ${SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(video.dateModified))}", style = MaterialTheme.typography.bodySmall)

                            Spacer(Modifier.height(8.dp))

                            if (video.targetUri != null) {
                                val savedMb = video.sizeMb - (video.targetSizeMb ?: 0.0)
                                val percent = if (video.sizeMb > 0) (savedMb / video.sizeMb * 100).toInt() else 0

                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text("✔ Converti : ${String.format("%.1f", video.targetSizeMb)} Mo (-$percent%)", fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { previewUri = video.uri }) { Text("Lire Source") }
                                            OutlinedButton(onClick = { previewUri = video.targetUri }) { Text("Lire Cible") }
                                            IconButton(
                                                onClick = {
                                                    pendingDeleteSourceUri = video.uri
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                        val intentSender = MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri)).intentSender
                                                        deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Supprimer source", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            } else if (video.isHevc) {
                                Text("✓ Déjà optimisé (Ignoré)", color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("⚠ En attente de conversion", color = Color(0xFFFF9800), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                    IconButton(onClick = { previewUri = video.uri }) {
                                        Icon(Icons.Default.PlayCircle, contentDescription = "Aperçu source")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewUri?.let { uri ->
        Dialog(onDismissRequest = { previewUri = null }) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(400.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Aperçu Vidéo", fontWeight = FontWeight.Bold)
                        IconButton(onClick = { previewUri = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer")
                        }
                    }
                    VideoPlayer(uri = uri)
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

fun getVideoInfo(context: Context, uri: Uri): VideoInfo {
    var name = "Fichier"
    var sizeBytes = 0L
    var dateModified = 0L
    var codec = "Inconnu"
    var isHevc = false
    var resolution = "Inconnue"
    var duration = "00:00"

    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx != -1) sizeBytes = cursor.getLong(sizeIdx)
            }
        }
    } catch (e: Exception) { e.printStackTrace() }

    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                codec = mime.replace("video/", "").uppercase()
                isHevc = (mime == MimeTypes.VIDEO_H265 || codec == "HEVC" || codec == "H265")

                val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                if (w > 0 && h > 0) resolution = "${w}x${h}"

                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durUs = format.getLong(MediaFormat.KEY_DURATION)
                    val sec = (durUs / 1000000) % 60
                    val min = (durUs / 1000000) / 60
                    duration = String.format("%02d:%02d", min, sec)
                }
                break
            }
        }
    } catch (e: Exception) { e.printStackTrace() } finally { extractor.release() }

    dateModified = System.currentTimeMillis()
    val sizeMb = sizeBytes / (1024.0 * 1024.0)

    return VideoInfo(uri, name, sizeBytes, sizeMb, dateModified, duration, resolution, codec, isHevc)
}

fun getFileSizeMb(context: Context, uri: Uri): Double {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx != -1) cursor.getLong(sizeIdx) / (1024.0 * 1024.0) else 0.0
            } else 0.0
        } ?: 0.0
    } catch (e: Exception) { 0.0 }
}

fun saveToGallery(context: Context, sourceFile: File, originalName: String): Uri? {
    val resolver = context.contentResolver
    val safeName = originalName.substringBeforeLast(".")

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
    return uri
}
