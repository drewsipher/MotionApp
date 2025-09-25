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
import androidx.compose.foundation.layout.*
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button // M3 Button
import androidx.compose.material3.Icon // M3 Icon
import androidx.compose.material3.IconButton // M3 IconButton
import androidx.compose.material3.MaterialTheme // M3 MaterialTheme
import androidx.compose.material3.Slider // M3 Slider
import androidx.compose.material3.Surface // M3 Surface
import androidx.compose.material3.Text // M3 Text
import androidx.compose.material3.TextButton // M3 TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
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
                onZoomChange = { zoom = it }
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
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var motionBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(useFront) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider.unbindAll()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val analyzer = MotionAnalyzer(frameGapProvider = { frameGap }, weightProvider = { motionWeight }) { bmp ->
            motionBitmap = bmp
        }
    imageAnalysis.setAnalyzer(analysisExecutor, analyzer)

        val cameraSelector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        val camCtrl = camera.cameraControl
        val camInfo = camera.cameraInfo
        val zoomState = camInfo.zoomState
        zoomState.observe(lifecycleOwner) { st ->
            val newZoom = st?.zoomRatio ?: 1f
            if (kotlin.math.abs(newZoom - zoom) > 0.01f) {
                onZoomChange(newZoom)
            }
        }
        camCtrl.setZoomRatio(zoom)
        analysis = imageAnalysis
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidBitmapPreview(bitmap = motionBitmap)
        }
        MotionControls(
            motionWeight = motionWeight,
            onMotionWeightChange = onMotionWeightChange,
            frameGap = frameGap,
            onFrameGapChange = onFrameGapChange,
            onToggleCamera = onToggleCamera,
            zoom = zoom,
            onZoomChange = { z ->
                onZoomChange(z)
            }
        )
    }
}

@Composable
fun MotionControls(
    motionWeight: Float,
    onMotionWeightChange: (Float) -> Unit,
    frameGap: Int,
    onFrameGapChange: (Int) -> Unit,
    onToggleCamera: () -> Unit,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("Motion blend weight: ${"%.2f".format(motionWeight)}")
        Slider(value = motionWeight, onValueChange = onMotionWeightChange, valueRange = 0f..1f)
        Spacer(Modifier.height(8.dp))
        Text("Frame gap (n): $frameGap")
        Slider(value = frameGap.toFloat(), onValueChange = { onFrameGapChange(it.toInt().coerceIn(1, 30)) }, valueRange = 1f..30f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onToggleCamera) { Text("Swap Camera") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Zoom: ${"%.2f".format(zoom)}x")
        Slider(value = zoom, onValueChange = onZoomChange, valueRange = 1f..8f)
    }
}

// Simple composable to draw a Bitmap
@Composable
fun AndroidBitmapPreview(bitmap: android.graphics.Bitmap?) {
    if (bitmap == null) return
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageBitmap(bitmap) },
        modifier = Modifier.fillMaxSize()
    )
}

class MotionAnalyzer(
    private val frameGapProvider: () -> Int,
    private val weightProvider: () -> Float,
    private val onBitmap: (android.graphics.Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {
    private val buffer = ArrayDeque<android.graphics.Bitmap>()

    override fun analyze(image: ImageProxy) {
        try {
            val bmp = image.toBitmap() ?: return
            buffer.addLast(bmp)
            val gap = frameGapProvider().coerceAtLeast(1)
            if (buffer.size > gap) {
                val prev = buffer.elementAt(buffer.size - 1 - gap)
                val out = MotionFilters.invertAndAverage(bmp, prev, weightProvider())
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
        TopAppBar(
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
    // For simplicity, decode frames to Bitmaps using MediaMetadataRetriever on a background thread
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
