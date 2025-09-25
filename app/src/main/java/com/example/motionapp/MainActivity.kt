package com.example.motionapp

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.motionapp.ui.theme.MotionAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
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
            Button(onClick = onOpenVideo, modifier = Modifier.fillMaxWidth()) { Text("Load Video") }
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

    var motionWeight by remember { mutableFloatStateOf(0.5f) } // weight for current inverted vs previous
    var frameGap by remember { mutableIntStateOf(5) } // n frames apart
    var useFront by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1.0f) }
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
    var videoUri by remember { mutableStateOf<Uri?>(null) }

    val pickVideoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> videoUri = uri }

    var motionWeight by remember { mutableFloatStateOf(0.5f) }
    var frameGap by remember { mutableIntStateOf(5) }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material.TopAppBar(
            title = { Text("Video Player") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                TextButton(onClick = { pickVideoLauncher.launch("video/*") }) { Text("Load") }
            },
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )

        if (videoUri == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Pick a video to begin")
            }
        } else {
            VideoPlayerWithMotion(
                uri = videoUri!!,
                weight = motionWeight,
                frameGap = frameGap,
                onWeightChange = { motionWeight = it },
                onFrameGapChange = { frameGap = it }
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
