@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.calcvault.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.calcvault.data.VaultItem
import com.calcvault.data.VaultRepository
import com.calcvault.media.PrivateDescriptor
import com.calcvault.media.PrivateMedia
import com.calcvault.media.VaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun MediaViewer(item: VaultItem, photos: List<VaultItem>, repository: VaultRepository, close: () -> Unit) {
    var fullscreen by remember(item.id) { mutableStateOf(false) }
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(fullscreen) {
        val controller = activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView) }
        if (fullscreen) {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler { if (fullscreen) fullscreen = false else close() }
    Scaffold(topBar = { if (!fullscreen) TopAppBar(title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to vault") } }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                item.category == "Photos" -> PhotoPager(item, photos.ifEmpty { listOf(item) }, repository)
                item.category == "Videos" -> VideoViewer(item, repository, fullscreen) { fullscreen = it }
                item.mime == "application/pdf" || item.name.endsWith(".pdf", true) -> PdfViewer(item, repository)
                item.mime.startsWith("text/") || item.name.substringAfterLast('.').lowercase() in listOf("txt", "md", "csv", "json", "log") -> TextViewer(item, repository)
                else -> ViewerError("No private preview for this format", "This file is stored encrypted. This version previews images, supported videos, PDFs and text. It does not hand private files to external apps or create plaintext export copies.")
            }
        }
    }
}
@Composable private fun PhotoPager(item: VaultItem, photos: List<VaultItem>, repository: VaultRepository) {
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == item.id }.coerceAtLeast(0), pageCount = { photos.size })
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { index ->
            val photo = photos[index]
            var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
            var failed by remember(photo.id) { mutableStateOf(false) }
            LaunchedEffect(photo.id) {
                try { bitmap = withContext(Dispatchers.IO) { PrivateMedia.image(repository, photo, 2560) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { failed = true }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let { ZoomImage(it, photo.name) } ?: if (failed) ViewerError("Unable to open image", "The format may be unsupported, or the encrypted file may be damaged. The original vault copy has not been changed.") else CircularProgressIndicator()
            }
        }
        Text("${pager.currentPage + 1} / ${photos.size} · Pinch to zoom · Swipe to browse", modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable private fun ZoomImage(bitmap: Bitmap, description: String) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { scale, offset, _ ->
        zoom = (zoom * scale).coerceIn(1f, 5f)
        pan = if (zoom == 1f) Offset.Zero else Offset((pan.x + offset.x).coerceIn(-3000f, 3000f), (pan.y + offset.y).coerceIn(-3000f, 3000f))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { clip = true }.transformable(transform, canPan = { zoom > 1f })
        .pointerInput(bitmap) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero }) }, contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), description, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoViewer(item: VaultItem, repository: VaultRepository, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val context = LocalContext.current
    var failed by remember(item.id) { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(1f) }
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true } })
            setMediaSource(ProgressiveMediaSource.Factory { VaultDataSource(repository, item) }.createMediaSource(MediaItem.fromUri("vault://${item.id}")))
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            prepare(); playWhenReady = false
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        // Pause synchronously on lifecycle loss, even when backgrounding prevents another
        // Compose frame. Buffered audio must not outlive the concealed/locked vault UI.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.stop(); player.release() }
    }
    if (failed) ViewerError("Video cannot be played", "This device may not support the codec, or the encrypted file may be damaged. Your file has not been changed.")
    else Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; (videoSurfaceView as? SurfaceView)?.setSecure(true) } }, modifier = Modifier.fillMaxSize(),
                onRelease = { it.player = null })
            FilledTonalIconButton(onClick = { onFullscreen(!fullscreen) }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, if (fullscreen) "Exit full screen" else "Full screen")
            }
        }
        if (!fullscreen) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VolumeUp, "Playback volume"); Spacer(Modifier.width(12.dp))
            Slider(volume, { volume = it; player.volume = it }, Modifier.weight(1f))
        }
        if (!fullscreen) Text("Rotate for a wider view · Tap video for playback controls", Modifier.padding(16.dp).align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun PdfViewer(item: VaultItem, repository: VaultRepository) {
    val context = LocalContext.current
    var count by remember(item.id) { mutableIntStateOf(0) }
    var failed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        try { count = withContext(Dispatchers.IO) { PrivateDescriptor(context, repository, item).use { descriptor -> PdfRenderer(descriptor.descriptor).use { it.pageCount } } }; if (count == 0) failed = true }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    if (failed) ViewerError("PDF cannot be opened", "It may be password-protected, unsupported or damaged. Your encrypted file has not been modified.")
    else if (count == 0) CircularProgressIndicator()
    else {
        val pager = rememberPagerState(pageCount = { count })
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { pageIndex ->
                var bitmap by remember(item.id, pageIndex) { mutableStateOf<Bitmap?>(null) }
                var pageFailed by remember(item.id, pageIndex) { mutableStateOf(false) }
                LaunchedEffect(item.id, pageIndex) {
                    try {
                        bitmap = withContext(Dispatchers.IO) {
                            PrivateDescriptor(context, repository, item).use { descriptor -> PdfRenderer(descriptor.descriptor).use { renderer -> renderer.openPage(pageIndex).use { page ->
                                require(page.width > 0 && page.height > 0)
                                val scale = 1800.0 / maxOf(page.width, page.height)
                                Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            } } }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { pageFailed = true }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bitmap?.let { ZoomImage(it, "Page ${pageIndex + 1}") } ?: if (pageFailed) Text("Unable to render this page") else CircularProgressIndicator()
                }
            }
            Text("Page ${pager.currentPage + 1} of $count · Swipe to browse", Modifier.align(Alignment.CenterHorizontally).padding(16.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
@Composable private fun TextViewer(item: VaultItem, repository: VaultRepository) {
    var text by remember(item.id) { mutableStateOf<String?>(null) }
    var failed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        try {
            text = withContext(Dispatchers.IO) {
                repository.reader(item).stream().use { input ->
                    val bytes = ByteArray(minOf(item.size, 1_048_576).toInt())
                    try {
                        var total = 0
                        while (total < bytes.size) { val n = input.read(bytes, total, bytes.size - total); if (n < 0) break; total += n }
                        String(bytes, 0, total, Charsets.UTF_8)
                    } finally { bytes.fill(0) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    if (failed) ViewerError("Text cannot be opened", "The encrypted file may be unavailable or damaged.")
    else if (text == null) CircularProgressIndicator()
    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        if (item.size > 1_048_576) Text("Preview limited to the first 1 MB. The complete file is stored encrypted.", color = MaterialTheme.colorScheme.primary)
        Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun ViewerError(title: String, detail: String) {
    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)); Text(detail, style = MaterialTheme.typography.bodyMedium) }
}
