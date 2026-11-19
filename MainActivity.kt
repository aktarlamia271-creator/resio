package com.example.portraitmaker

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.ui.PlayerView
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

/** Reads display width/height of the video (rotation-aware). */
private fun videoSize(ctx: Context, uri: Uri): Pair<Int, Int> {
    val r = MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
    r.release()
    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
    return w to h
}

/** Fraction of the source width that a 9:16 window covers (max 1). */
private fun cropFraction(w: Int, h: Int): Float =
    ((h * 9f / 16f) / w).coerceAtMost(1f)

private fun saveToGallery(ctx: Context, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "portrait_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PortraitMaker")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
    ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var size by remember { mutableStateOf(1920 to 1080) }
    var pos by remember { mutableFloatStateOf(0.5f) }   // 0 = left edge, 1 = right edge
    var exporting by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked ->
        if (picked != null) { uri = picked; size = videoSize(ctx, picked); pos = 0.5f }
    }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(uri) {
        uri?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare() }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }) { Text("ভিডিও সিলেক্ট করো") }

        if (uri != null) {
            val (w, h) = size
            val f = cropFraction(w, h)

            Box(Modifier.fillMaxWidth().aspectRatio(w.toFloat() / h)) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize()
                )
                // Dim everything outside the 9:16 window
                Canvas(Modifier.fillMaxSize()) {
                    val cropW = size.width * f
                    val left = (size.width - cropW) * pos
                    val dim = Color.Black.copy(alpha = 0.6f)
                    drawRect(dim, Offset.Zero, Size(left, size.height))
                    drawRect(dim, Offset(left + cropW, 0f), Size(size.width - left - cropW, size.height))
                }
            }

            Text("ফ্রেমের পজিশন (বাম ↔ ডান)")
            Slider(value = pos, onValueChange = { pos = it })

            Button(
                enabled = !exporting,
                onClick = {
                    exporting = true
                    export(ctx, uri!!, f, pos) { ok ->
                        exporting = false
                        Toast.makeText(
                            ctx,
                            if (ok) "সেভ হয়েছে: Movies/PortraitMaker" else "এক্সপোর্ট ফেইল হয়েছে",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            ) { Text(if (exporting) "এক্সপোর্ট হচ্ছে…" else "9:16 এক্সপোর্ট করো") }

            if (exporting) CircularProgressIndicator()
        }
    }
}

private fun export(ctx: Context, uri: Uri, f: Float, pos: Float, done: (Boolean) -> Unit) {
    // Crop() uses normalized device coordinates: -1..1 on both axes
    val left = -1f + 2f * (1f - f) * pos
    val right = left + 2f * f
    val crop = Crop(left, right, -1f, 1f)
    val scale = Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT)

    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(Effects(emptyList(), listOf(crop, scale)))
        .build()

    val out = File(ctx.cacheDir, "out_${System.currentTimeMillis()}.mp4")
    val transformer = Transformer.Builder(ctx)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                saveToGallery(ctx, out); out.delete(); done(true)
            }
            override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                e.printStackTrace(); done(false)
            }
        })
        .build()
    transformer.start(edited, out.absolutePath)
}
