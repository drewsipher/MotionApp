package com.example.motionapp

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.motionapp.ui.theme.MotionAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.LinkedHashMap
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MotionAppTheme {
                val navController = rememberNavController()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(navController)
                }
            }
        }
    }
}

sealed class Route(val route: String) {
    data object Start: Route("start")
    data object Camera: Route("camera")
    data object Video: Route("video")
}

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController, startDestination = Route.Start.route) {
        composable(Route.Start.route) { StartScreen(
            onOpenCamera = { navController.navigate(Route.Camera.route) },
            onOpenVideo = { navController.navigate(Route.Video.route) }
        ) }
        composable(Route.Camera.route) { CameraScreen(onBack = { navController.popBackStack() }) }
        composable(Route.Video.route) { VideoScreen(onBack = { navController.popBackStack() }) }
    }
}

@Composable
fun StartScreen(onOpenCamera: () -> Unit, onOpenVideo: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Motion App", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenCamera, modifier = Modifier.fillMaxWidth()) { Text("Open Camera") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenVideo, modifier = Modifier.fillMaxWidth()) { Text("Browse Videos") }
        }
    }
}

// --- Camera Screen ---
@Composable
fun CameraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCamera = (result[Manifest.permission.CAMERA] == true)
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    var motionWeight by rememberSaveable { mutableStateOf(0.5f) } // weight for current inverted vs previous
    var frameGap by rememberSaveable { mutableStateOf(5) } // n frames apart
    var useFront by rememberSaveable { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1.0f) }
    var quality by remember { mutableStateOf(CameraQuality.Medium) }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material.TopAppBar(
            title = { Text("Live Camera") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            },
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )

        if (hasCamera) {
            CameraPreviewWithControls(
                useFront = useFront,
                onToggleCamera = { useFront = !useFront },
                motionWeight = motionWeight,
                onMotionWeightChange = { motionWeight = it },
                frameGap = frameGap,
                onFrameGapChange = { frameGap = it },
                zoom = zoom,
                onZoomChange = { zoom = it },
                quality = quality,
                onQualityChange = { quality = it }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Camera permission required")
            }
        }
    }
}

@Composable
private fun CameraPreviewWithControls(
    useFront: Boolean,
    onToggleCamera: () -> Unit,
    motionWeight: Float,
    onMotionWeightChange: (Float) -> Unit,
    frameGap: Int,
    onFrameGapChange: (Int) -> Unit,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    quality: CameraQuality,
    onQualityChange: (CameraQuality) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var motionBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var camControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var camInfo by remember { mutableStateOf<androidx.camera.core.CameraInfo?>(null) }
    var analyzer by remember { mutableStateOf<MotionAnalyzer?>(null) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(10f) }
    val minZoomState = rememberUpdatedState(minZoom)
    val maxZoomState = rememberUpdatedState(maxZoom)
    val zoomState = rememberUpdatedState(zoom)

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    // Rebind camera when lens facing or quality changes
    LaunchedEffect(useFront, quality) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider.unbindAll()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(quality.width, quality.height))
            .build()

        val newAnalyzer = MotionAnalyzer(
            mirror = useFront,
            onBitmap = { bmp -> motionBitmap = bmp }
        )
        newAnalyzer.frameGap = frameGap
        newAnalyzer.weight = motionWeight
        analyzer = newAnalyzer
        imageAnalysis.setAnalyzer(analysisExecutor, newAnalyzer)

        val cameraSelector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        camControl = camera.cameraControl
        camInfo = camera.cameraInfo

        // Capture min/max zoom bounds and apply the current zoom
        camera.cameraInfo.zoomState.value?.let { state ->
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
        }
        camControl?.setZoomRatio(zoom.coerceIn(minZoom, maxZoom)) // Apply initial zoom
    }

    LaunchedEffect(motionWeight) { analyzer?.weight = motionWeight }
    LaunchedEffect(frameGap) { analyzer?.frameGap = frameGap }
    LaunchedEffect(zoom) { camControl?.setZoomRatio(zoom.coerceIn(minZoom, maxZoom)) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    while (true) {
                        var currentZoom = zoomState.value
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            val boundsMin = minZoomState.value
                            val boundsMax = maxZoomState.value
                            val newZoom = (currentZoom * gestureZoom).coerceIn(boundsMin, boundsMax)
                            if (kotlin.math.abs(newZoom - currentZoom) > 0.0001f) {
                                currentZoom = newZoom
                                onZoomChange(newZoom)
                            }
                        }
                    }
                }
        ) {
            AndroidBitmapPreview(bitmap = motionBitmap, mirror = useFront)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp)
        ) {
            MotionControls(
                motionWeight = motionWeight,
                onMotionWeightChange = onMotionWeightChange,
                frameGap = frameGap,
                onFrameGapChange = onFrameGapChange,
                quality = quality,
                onQualityChange = onQualityChange,
                onToggleCamera = onToggleCamera
            )
        }
    }
}

@Composable
fun MotionControls(
    motionWeight: Float,
    onMotionWeightChange: (Float) -> Unit,
    frameGap: Int,
    onFrameGapChange: (Int) -> Unit,
    quality: CameraQuality,
    onQualityChange: (CameraQuality) -> Unit,
    onToggleCamera: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Motion blend weight: ${"%.2f".format(motionWeight)}")
        Slider(value = motionWeight, onValueChange = onMotionWeightChange, valueRange = 0f..1f)
        Spacer(Modifier.height(8.dp))
        Text("Frame gap (n): $frameGap")
        Slider(value = frameGap.toFloat(), onValueChange = { onFrameGapChange(it.toInt().coerceIn(1, 30)) }, valueRange = 1f..30f)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            QualityDropdown(quality = quality, onQualityChange = onQualityChange)
            IconButton(onClick = onToggleCamera) {
                Icon(imageVector = Icons.Filled.Cameraswitch, contentDescription = "Swap Camera")
            }
        }
    }
}

@Composable
private fun QualityDropdown(
    quality: CameraQuality,
    onQualityChange: (CameraQuality) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Quality: ${quality.label}")
        }
        androidx.compose.material.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CameraQuality.values().forEach { q ->
                androidx.compose.material.DropdownMenuItem(onClick = {
                    expanded = false
                    if (q != quality) onQualityChange(q)
                }) {
                    Text(q.label)
                }
            }
        }
    }
}

enum class CameraQuality(val label: String, val width: Int, val height: Int) {
    Low("480p", 640, 480),
    Medium("720p", 1280, 720),
    High("1080p", 1920, 1080),
    Ultra("1440p", 2560, 1440);
}

@Composable
fun AndroidBitmapPreview(bitmap: android.graphics.Bitmap?, mirror: Boolean = false) {
    if (bitmap == null) return
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        } },
        update = {
            it.scaleX = if (mirror) -1f else 1f
            it.setImageBitmap(bitmap)
        },
        modifier = Modifier.fillMaxSize()
    )
}

class MotionAnalyzer(
    private val mirror: Boolean,
    private val onBitmap: (android.graphics.Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {
    private val buffer = ArrayDeque<android.graphics.Bitmap>()
    @Volatile var frameGap: Int = 5
    @Volatile var weight: Float = 0.5f

    override fun analyze(image: ImageProxy) {
        try {
            val raw = image.toBitmap() ?: return
            val rotated = raw.rotate(image.imageInfo.rotationDegrees)
            if (rotated !== raw) raw.recycle()
            val bmp = if (mirror) rotated.mirrorHorizontally() else rotated
            buffer.addLast(bmp)
            val gap = frameGap.coerceAtLeast(1)
            if (buffer.size > gap) {
                val prev = buffer.elementAt(buffer.size - 1 - gap)
                val out = MotionFilters.invertAndAverage(bmp, prev, weight)
                onBitmap(out)
            }
            while (buffer.size > 60) buffer.removeFirst().recycle()
        } finally {
            image.close()
        }
    }
}

// --- Video Screen ---
@Composable
fun VideoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedVideoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var motionWeight by rememberSaveable { mutableStateOf(0.5f) }
    var frameGap by rememberSaveable { mutableStateOf(5) }

    val permission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var hasMediaPermission by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMediaPermission = granted }

    LaunchedEffect(permission) {
        if (!hasMediaPermission) {
            permissionLauncher.launch(permission)
        }
    }

    val videos by produceState<List<DeviceVideo>?>(initialValue = null, hasMediaPermission, context) {
        value = if (hasMediaPermission) {
            withContext(Dispatchers.IO) { queryDeviceVideos(context) }
        } else {
            emptyList()
        }
    }

    val currentVideos = videos

    LaunchedEffect(videos) {
        val list = videos
        if (list != null && selectedVideoUri != null && list.none { it.uri == selectedVideoUri }) {
            selectedVideoUri = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material.TopAppBar(
            title = { Text("Video Player") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            },
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )

        when {
            !hasMediaPermission -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Allow video library access to browse your clips.")
                }
            }

            currentVideos == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            currentVideos.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No videos found on this device.")
                }
            }

            selectedVideoUri != null -> {
                VideoPlaybackScreen(
                    uri = selectedVideoUri!!,
                    onBack = { selectedVideoUri = null },
                    motionWeight = motionWeight,
                    frameGap = frameGap,
                    onWeightChange = { motionWeight = it },
                    onFrameGapChange = { frameGap = it }
                )
            }

            else -> {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(currentVideos, key = { it.uri }) { video ->
                            VideoListItem(
                                video = video,
                                selected = selectedVideoUri == video.uri,
                                onSelect = { selectedVideoUri = video.uri }
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class DeviceVideo(
    val uri: Uri,
    val displayName: String?,
    val durationMs: Long
)

private fun queryDeviceVideos(context: Context): List<DeviceVideo> {
    val resolver = context.contentResolver
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION
    )
    val sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC"

    val contentUris = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addAll(MediaStore.getExternalVolumeNames(context).map { volume ->
                MediaStore.Video.Media.getContentUri(volume)
            })
        }
        add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }.distinct()

    val results = LinkedHashMap<Uri, DeviceVideo>()

    for (contentUri in contentUris) {
        try {
            resolver.query(contentUri, projection, null, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(contentUri, id)
                    if (results.containsKey(uri)) continue

                    val name = cursor.getString(nameColumn)
                    val duration = cursor.getLong(durationColumn).coerceAtLeast(0L)

                    results[uri] = DeviceVideo(uri, name, duration)
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked mid-query or restricted volume; skip.
        }
    }

    return results.values.toList()
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0L)
    val hours = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun VideoListItem(
    video: DeviceVideo,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val highlight = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlight)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoThumbnail(uri = video.uri)

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = video.displayName ?: "Untitled video",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatDuration(video.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, uri, context) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        retriever.getFrameAtTime(0)
                    } finally {
                        retriever.release()
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    val currentThumbnail = thumbnail

    if (currentThumbnail != null) {
        Image(
            bitmap = currentThumbnail.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .size(72.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .size(72.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VideoPlaybackScreen(
    uri: Uri,
    onBack: () -> Unit,
    motionWeight: Float,
    frameGap: Int,
    onWeightChange: (Float) -> Unit,
    onFrameGapChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material.TopAppBar(
            title = { Text("Motion Preview") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            },
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            VideoPlayerWithMotion(
                uri = uri,
                weight = motionWeight,
                frameGap = frameGap,
                onWeightChange = onWeightChange,
                onFrameGapChange = onFrameGapChange
            )
        }
    }
}

@Composable
fun VideoPlayerWithMotion(
    uri: Uri,
    weight: Float,
    frameGap: Int,
    onWeightChange: (Float) -> Unit,
    onFrameGapChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var frameBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        durationMs = MotionVideo.decodeDuration(context, uri)
        playing = true
    }

    LaunchedEffect(playing, uri, weight, frameGap) {
        if (!playing) return@LaunchedEffect
        scope.launch(Dispatchers.Default) {
            while (playing) {
                val current = MotionVideo.frameAt(context, uri, positionMs)
                val prevTs = (positionMs - frameGap * 33L).coerceAtLeast(0L)
                val prev = MotionVideo.frameAt(context, uri, prevTs)
                if (current != null && prev != null) {
                    val out = MotionFilters.invertAndAverage(current, prev, weight)
                    frameBmp = out
                }
                positionMs = (positionMs + 33L).coerceAtMost(durationMs)
                if (positionMs >= durationMs) playing = false
                // Small delay to roughly match ~30fps, adjust as needed
                // kotlinx.coroutines.delay(33) // Consider adding delay if CPU usage is too high
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidBitmapPreview(bitmap = frameBmp)
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play") }
                Spacer(Modifier.width(8.dp))
                Text("${positionMs/1000}s / ${durationMs/1000}s")
            }
            Slider(value = positionMs.toFloat(), onValueChange = {
                positionMs = it.toLong().coerceIn(0, durationMs)
            }, valueRange = 0f..durationMs.toFloat())
            Spacer(Modifier.height(8.dp))
            Text("Motion blend weight: ${"%.2f".format(weight)}")
            Slider(value = weight, onValueChange = onWeightChange, valueRange = 0f..1f)
            Spacer(Modifier.height(8.dp))
            Text("Frame gap (n): $frameGap")
            Slider(value = frameGap.toFloat(), onValueChange = { onFrameGapChange(it.toInt().coerceIn(1, 60)) }, valueRange = 1f..60f)
        }
    }
}
