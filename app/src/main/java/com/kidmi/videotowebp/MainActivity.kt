package com.kidmi.videotowebp

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EB7FF),
    onPrimary = Color(0xFF0E2454),
    primaryContainer = Color(0xFF233D78),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF7ED7C4),
    secondaryContainer = Color(0xFF174B42),
    background = Color(0xFF0B0D12),
    surface = Color(0xFF11151D),
    surfaceVariant = Color(0xFF1B202A),
    outline = Color(0xFF87909F)
)

private enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF0B1B3F),
    secondary = Color(0xFF006B5D),
    secondaryContainer = Color(0xFF9EF2DE),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9ECF3),
    outline = Color(0xFF737986)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotionWebPRoot()
        }
    }
}

@Composable
private fun MotionWebPRoot() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(
            "video_to_webp",
            android.content.Context.MODE_PRIVATE
        )
    }

    var themeMode by remember {
        mutableStateOf(
            runCatching {
                ThemeMode.valueOf(
                    prefs.getString(
                        "theme_mode",
                        ThemeMode.SYSTEM.name
                    ) ?: ThemeMode.SYSTEM.name
                )
            }.getOrDefault(ThemeMode.SYSTEM)
        )
    }

    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    LaunchedEffect(themeMode) {
        prefs.edit()
            .putString("theme_mode", themeMode.name)
            .apply()
    }

    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors
    ) {
        VideoToWebPApp(
            themeMode = themeMode,
            onThemeModeChange = { themeMode = it }
        )
    }
}

private data class ResolutionOption(
    val label: String,
    val maxSide: Int?
)

private data class QuickPreset(
    val label: String,
    val maxSide: Int?,
    val fps: Int,
    val quality: Int,
    val speed: ConversionSpeed
)

@Composable
private fun VideoToWebPApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val engine = remember { ConversionEngine(context.applicationContext) }
    val prefs = remember {
        context.getSharedPreferences("video_to_webp", android.content.Context.MODE_PRIVATE)
    }
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var metadataLoading by remember { mutableStateOf(false) }

    var startSec by remember { mutableFloatStateOf(0f) }
    var endSec by remember { mutableFloatStateOf(0f) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    var fps by remember {
        mutableIntStateOf(prefs.getInt("fps", 15).coerceIn(5, 30))
    }
    var quality by remember {
        mutableIntStateOf(prefs.getInt("quality", 80).coerceIn(10, 100))
    }
    var maxSide by remember {
        mutableStateOf<Int?>(
            prefs.getInt("max_side", 720).let { if (it < 0) null else it }
        )
    }
    var lossless by remember {
        mutableStateOf(prefs.getBoolean("lossless", false))
    }
    var loopForever by remember {
        mutableStateOf(prefs.getBoolean("loop_forever", true))
    }
    var speed by remember {
        mutableStateOf(
            runCatching {
                ConversionSpeed.valueOf(
                    prefs.getString("speed", ConversionSpeed.FAST.name)
                        ?: ConversionSpeed.FAST.name
                )
            }.getOrDefault(ConversionSpeed.FAST)
        )
    }

    var splitMode by remember {
        mutableStateOf(
            runCatching {
                SplitMode.valueOf(
                    prefs.getString("split_mode", SplitMode.NONE.name)
                        ?: SplitMode.NONE.name
                )
            }.getOrDefault(SplitMode.NONE)
        )
    }
    var splitCount by remember {
        mutableIntStateOf(prefs.getInt("split_count", 2).coerceIn(2, 20))
    }
    var targetPartSizeMb by remember {
        mutableIntStateOf(
            prefs.getInt("target_part_mb", 8).coerceIn(1, 100)
        )
    }

    var cropAspect by remember {
        mutableStateOf(
            runCatching {
                CropAspect.valueOf(
                    prefs.getString(
                        "crop_aspect",
                        CropAspect.ORIGINAL.name
                    ) ?: CropAspect.ORIGINAL.name
                )
            }.getOrDefault(CropAspect.ORIGINAL)
        )
    }

    var focusX by remember {
        mutableFloatStateOf(
            prefs.getFloat("focus_x", 0.5f).coerceIn(0f, 1f)
        )
    }

    var focusY by remember {
        mutableFloatStateOf(
            prefs.getFloat("focus_y", 0.5f).coerceIn(0f, 1f)
        )
    }

    var outputTreeUri by remember {
        mutableStateOf(
            prefs.getString("output_tree_uri", null)?.let(Uri::parse)
        )
    }

    var converting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("영상을 선택해 시작하세요.") }
    var result by remember { mutableStateOf<ConversionResult?>(null) }
    var selectedResultPart by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }

            engine.cancel()
            exoPlayer.pause()
            converting = false
            progress = 0f
            result = null
            selectedResultPart = 0
            videoInfo = null
            videoUri = uri
            currentPositionMs = 0L
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {
            }

            outputTreeUri = uri
            prefs.edit()
                .putString("output_tree_uri", uri.toString())
                .apply()
        }
    }

    val folderName = remember(outputTreeUri) {
        outputTreeUri?.let {
            try {
                DocumentFile.fromTreeUri(context, it)?.name
            } catch (_: Throwable) {
                null
            }
        } ?: "Pictures / VideoToWebP"
    }

    LaunchedEffect(
        fps,
        quality,
        maxSide,
        lossless,
        loopForever,
        speed,
        splitMode,
        splitCount,
        targetPartSizeMb,
        cropAspect,
        focusX,
        focusY
    ) {
        prefs.edit()
            .putInt("fps", fps)
            .putInt("quality", quality)
            .putInt("max_side", maxSide ?: -1)
            .putBoolean("lossless", lossless)
            .putBoolean("loop_forever", loopForever)
            .putString("speed", speed.name)
            .putString("split_mode", splitMode.name)
            .putInt("split_count", splitCount)
            .putInt("target_part_mb", targetPartSizeMb)
            .putString("crop_aspect", cropAspect.name)
            .putFloat("focus_x", focusX)
            .putFloat("focus_y", focusY)
            .apply()
    }

    LaunchedEffect(videoUri) {
        val uri = videoUri
        if (uri == null) {
            exoPlayer.clearMediaItems()
            return@LaunchedEffect
        }

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()

        metadataLoading = true
        status = "영상 정보를 읽는 중…"

        try {
            val info = withContext(Dispatchers.IO) {
                readVideoInfo(context, uri)
            }
            videoInfo = info
            startSec = 0f
            endSec = (info.durationMs / 1000f).coerceAtLeast(0.1f)
            currentPositionMs = 0L
            exoPlayer.seekTo(0L)
            status = "트림 구간과 출력 설정을 확인하세요."
        } catch (e: Throwable) {
            error = e.message ?: "영상 정보를 읽을 수 없습니다."
            status = "다른 영상을 선택하세요."
        } finally {
            metadataLoading = false
        }
    }

    LaunchedEffect(exoPlayer, videoUri, startSec, endSec) {
        while (true) {
            val startMs = (startSec * 1000f).toLong()
            val endMs = (endSec * 1000f).toLong()
            var position = exoPlayer.currentPosition.coerceAtLeast(0L)

            if (
                videoUri != null &&
                endMs > startMs &&
                exoPlayer.isPlaying &&
                position >= endMs
            ) {
                exoPlayer.seekTo(startMs)
                position = startMs
            }

            currentPositionMs = position
            isPlaying = exoPlayer.isPlaying
            delay(60)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            engine.cancel()
            exoPlayer.release()
        }
    }

    DisposableEffect(converting) {
        val activity = context as? android.app.Activity
        if (converting) {
            activity?.window?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        } else {
            activity?.window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        onDispose {
            if (converting) {
                activity?.window?.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
        }
    }

    fun seekPlayer(targetMs: Long) {
        val startMs = (startSec * 1000f).toLong()
        val endMs = (endSec * 1000f).toLong()
        val safe = if (endMs > startMs) {
            targetMs.coerceIn(startMs, endMs)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        exoPlayer.seekTo(safe)
        currentPositionMs = safe
    }

    fun startConversion() {
        val uri = videoUri ?: return
        val info = videoInfo ?: return
        val safeEnd = endSec.coerceAtMost(info.durationMs / 1000f)
        val safeStart = startSec.coerceIn(
            0f,
            (safeEnd - 0.1f).coerceAtLeast(0f)
        )

        exoPlayer.pause()
        converting = true
        result = null
        selectedResultPart = 0
        error = null
        progress = 0f

        engine.convert(
            sourceUri = uri,
            settings = ConversionSettings(
                startMs = (safeStart * 1000).roundToInt().toLong(),
                endMs = (safeEnd * 1000).roundToInt().toLong(),
                fps = fps,
                quality = quality,
                maxSide = maxSide,
                lossless = lossless,
                loopForever = loopForever,
                speed = speed,
                outputTreeUri = outputTreeUri,
                splitMode = splitMode,
                splitCount = splitCount,
                targetPartSizeMb = targetPartSizeMb,
                cropAspect = cropAspect,
                focusX = focusX,
                focusY = focusY
            ),
            onStatus = { status = it },
            onProgress = { progress = it.coerceIn(0f, 1f) },
            onComplete = {
                converting = false
                progress = 1f
                result = it
                selectedResultPart = 0
                status = if (it.parts.size == 1) {
                    "변환 완료 · " + folderName
                } else {
                    it.parts.size.toString() + "개 파일 생성 완료 · " + folderName
                }
            },
            onError = {
                converting = false
                progress = 0f
                error = it
                status = "변환 실패"
            },
            onCancelled = {
                converting = false
                progress = 0f
                status = "변환이 취소되었습니다."
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange
            )

            if (videoUri == null) {
                EmptyVideoCard(
                    onPick = { videoPicker.launch(arrayOf("video/*")) }
                )
            } else {
                PlayerSection(
                    exoPlayer = exoPlayer,
                    info = videoInfo,
                    currentPositionMs = currentPositionMs,
                    isPlaying = isPlaying,
                    startSec = startSec,
                    endSec = endSec,
                    outputFps = fps,
                    cropAspect = cropAspect,
                    focusX = focusX,
                    focusY = focusY,
                    converting = converting,
                    onPickAnother = {
                        videoPicker.launch(arrayOf("video/*"))
                    },
                    onTogglePlay = {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                        } else {
                            val startMs = (startSec * 1000).toLong()
                            val endMs = (endSec * 1000).toLong()
                            if (
                                exoPlayer.currentPosition < startMs ||
                                exoPlayer.currentPosition >= endMs
                            ) {
                                exoPlayer.seekTo(startMs)
                            }
                            exoPlayer.play()
                        }
                    },
                    onSeekBy = { delta ->
                        exoPlayer.pause()
                        seekPlayer(currentPositionMs + delta)
                    },
                    onSetIn = {
                        videoInfo?.let {
                            val maxIn = (endSec - 0.1f).coerceAtLeast(0f)
                            startSec = (currentPositionMs / 1000f)
                                .coerceIn(0f, maxIn)
                            exoPlayer.pause()
                        }
                    },
                    onSetOut = {
                        videoInfo?.let { currentInfo ->
                            val maxOut = currentInfo.durationMs / 1000f
                            val minOut = (startSec + 0.1f)
                                .coerceAtMost(maxOut)
                            endSec = (currentPositionMs / 1000f)
                                .coerceIn(minOut, maxOut)
                            exoPlayer.pause()
                        }
                    },
                    onTrimChange = { newStart, newEnd, seekSec ->
                        startSec = newStart
                        endSec = newEnd
                        exoPlayer.pause()
                        seekPlayer((seekSec * 1000f).toLong())
                    },
                    onFocusChange = { x, y ->
                        focusX = x.coerceIn(0f, 1f)
                        focusY = y.coerceIn(0f, 1f)
                    }
                )
            }

            if (metadataLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            videoInfo?.let {
                OutputSettingsCard(
                    fps = fps,
                    quality = quality,
                    maxSide = maxSide,
                    lossless = lossless,
                    loopForever = loopForever,
                    speed = speed,
                    enabled = !converting,
                    onFpsChange = { fps = it },
                    onQualityChange = { quality = it },
                    onMaxSideChange = { maxSide = it },
                    onLosslessChange = { lossless = it },
                    onLoopChange = { loopForever = it },
                    onSpeedChange = { speed = it },
                    onApplyPreset = { preset ->
                        maxSide = preset.maxSide
                        fps = preset.fps
                        quality = preset.quality
                        lossless = false
                        speed = preset.speed
                    }
                )

                FramingCard(
                    cropAspect = cropAspect,
                    focusX = focusX,
                    focusY = focusY,
                    enabled = !converting,
                    onCropAspectChange = { cropAspect = it },
                    onFocusChange = { x, y ->
                        focusX = x.coerceIn(0f, 1f)
                        focusY = y.coerceIn(0f, 1f)
                    }
                )

                StorageCard(
                    folderName = folderName,
                    customFolder = outputTreeUri != null,
                    enabled = !converting,
                    onChooseFolder = { folderPicker.launch(outputTreeUri) },
                    onResetFolder = {
                        outputTreeUri = null
                        prefs.edit().remove("output_tree_uri").apply()
                    }
                )

                SplitCard(
                    mode = splitMode,
                    count = splitCount,
                    targetMb = targetPartSizeMb,
                    enabled = !converting,
                    onModeChange = { splitMode = it },
                    onCountChange = { splitCount = it },
                    onTargetMbChange = { targetPartSizeMb = it }
                )

                ConversionActionCard(
                    converting = converting,
                    progress = progress,
                    status = status,
                    onConvert = { startConversion() },
                    onCancel = {
                        engine.cancel()
                        status = "취소 요청 중…"
                    }
                )
            }

            result?.let { converted ->
                ResultCard(
                    result = converted,
                    selectedIndex = selectedResultPart,
                    onSelectPart = { selectedResultPart = it },
                    onOpenSelected = {
                        converted.parts
                            .getOrNull(selectedResultPart)
                            ?.let { openResult(context, it.uri) }
                    },
                    onShareSelected = {
                        converted.parts
                            .getOrNull(selectedResultPart)
                            ?.let { shareSingleResult(context, it.uri) }
                    },
                    onShareAll = {
                        shareResult(context, converted)
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("변환 오류") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) {
                    Text("확인")
                }
            }
        )
    }
}

@Composable
private fun Header(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Motion WebP",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    "LOCAL · FAST",
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 5.dp
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            "영상 구간을 프레임 단위로 확인하고 원하는 화면비로 변환",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                listOf(
                    ThemeMode.SYSTEM to "시스템",
                    ThemeMode.LIGHT to "라이트",
                    ThemeMode.DARK to "다크"
                )
            ) { option ->
                FilterChip(
                    selected = themeMode == option.first,
                    onClick = { onThemeModeChange(option.first) },
                    label = { Text(option.second) }
                )
            }
        }
    }
}

@Composable
private fun EmptyVideoCard(
    onPick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "먼저 영상을 선택하세요",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "MP4 · MOV · WebM 등 기기와 FFmpeg가 읽을 수 있는 영상을 지원합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onPick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("영상 선택")
            }
        }
    }
}

@Composable
private fun PlayerSection(
    exoPlayer: ExoPlayer,
    info: VideoInfo?,
    currentPositionMs: Long,
    isPlaying: Boolean,
    startSec: Float,
    endSec: Float,
    outputFps: Int,
    cropAspect: CropAspect,
    focusX: Float,
    focusY: Float,
    converting: Boolean,
    onPickAnother: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSetIn: () -> Unit,
    onSetOut: () -> Unit,
    onTrimChange: (Float, Float, Float) -> Unit,
    onFocusChange: (Float, Float) -> Unit
) {
    val durationSec = ((info?.durationMs ?: 100L) / 1000f).coerceAtLeast(0.1f)
    val startMs = (startSec * 1000f).toLong()
    val endMs = (endSec * 1000f).toLong()
    val selectedDuration = (endMs - startMs).coerceAtLeast(1L)
    val withinSelection = (currentPositionMs - startMs)
        .coerceIn(0L, selectedDuration)
    val selectedProgress = (
        withinSelection.toDouble() / selectedDuration.toDouble()
    ).toFloat()

    val sourceFps = info?.sourceFps?.takeIf { it > 0.1f }
        ?: outputFps.toFloat().coerceAtLeast(1f)
    val currentFrame = frameIndexAt(
        currentPositionMs,
        sourceFps
    )
    val frameStepMs = (1000f / sourceFps)
        .roundToInt()
        .coerceAtLeast(1)
        .toLong()
    val focusMarkerColor =
        MaterialTheme.colorScheme.secondary

    val aspect = if (
        info != null &&
        info.width > 0 &&
        info.height > 0
    ) {
        (info.width.toFloat() / info.height.toFloat()).coerceIn(0.56f, 1.9f)
    } else {
        16f / 9f
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    update = { view ->
                        view.player = exoPlayer
                    },
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(
                            cropAspect,
                            converting
                        ) {
                            if (!converting &&
                                cropAspect != CropAspect.ORIGINAL
                            ) {
                                detectTapGestures { offset ->
                                    val width = size.width
                                        .toFloat()
                                        .coerceAtLeast(1f)
                                    val height = size.height
                                        .toFloat()
                                        .coerceAtLeast(1f)

                                    onFocusChange(
                                        (offset.x / width)
                                            .coerceIn(0f, 1f),
                                        (offset.y / height)
                                            .coerceIn(0f, 1f)
                                    )
                                }
                            }
                        }
                ) {
                    cropAspect.ratio?.let { targetRatio ->
                        val ratio = targetRatio.toFloat()
                        val canvasRatio =
                            size.width / size.height

                        val cropWidth: Float
                        val cropHeight: Float

                        if (canvasRatio > ratio) {
                            cropHeight = size.height
                            cropWidth = cropHeight * ratio
                        } else {
                            cropWidth = size.width
                            cropHeight = cropWidth / ratio
                        }

                        val centerX =
                            focusX.coerceIn(0f, 1f) *
                                size.width
                        val centerY =
                            focusY.coerceIn(0f, 1f) *
                                size.height

                        val left = (
                            centerX - cropWidth / 2f
                            ).coerceIn(
                            0f,
                            (size.width - cropWidth)
                                .coerceAtLeast(0f)
                        )
                        val top = (
                            centerY - cropHeight / 2f
                            ).coerceIn(
                            0f,
                            (size.height - cropHeight)
                                .coerceAtLeast(0f)
                        )

                        drawRect(
                            color = Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(
                                left,
                                top
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                cropWidth,
                                cropHeight
                            ),
                            style = Stroke(width = 3.dp.toPx())
                        )

                        drawCircle(
                            color = focusMarkerColor,
                            radius = 7.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(
                                centerX,
                                centerY
                            )
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                if (info != null) {
                    Text(
                        info.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        buildString {
                            append(info.width)
                            append("×")
                            append(info.height)
                            append(" · ")
                            append(formatDuration(info.durationMs))
                            info.sourceFps?.let {
                                append(" · ")
                                append(String.format(
                                    java.util.Locale.US,
                                    "%.2f fps",
                                    it
                                ))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LinearProgressIndicator(
                    progress = { selectedProgress },
                    modifier = Modifier.fillMaxWidth()
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        buildString {
                            append(formatDetailedTime(currentPositionMs))
                            currentFrame?.let {
                                append(" · Frame ")
                                append(it)
                            }
                            append(" · ")
                            append(
                                String.format(
                                    java.util.Locale.US,
                                    "%.2f fps",
                                    sourceFps
                                )
                            )
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { onSeekBy(-frameStepMs) },
                            enabled = !converting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("−1F")
                        }
                        Button(
                            onClick = onTogglePlay,
                            enabled = !converting,
                            modifier = Modifier.weight(1.35f)
                        ) {
                            Text(
                                if (isPlaying) {
                                    "일시정지"
                                } else {
                                    "재생"
                                }
                            )
                        }
                        FilledTonalButton(
                            onClick = { onSeekBy(frameStepMs) },
                            enabled = !converting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+1F")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSeekBy(-1000L) },
                            enabled = !converting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("−1초")
                        }
                        OutlinedButton(
                            onClick = { onSeekBy(1000L) },
                            enabled = !converting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+1초")
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    "트림 구간",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "핸들을 움직이면 플레이어가 해당 프레임으로 바로 이동합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                RangeSlider(
                    value = startSec..endSec.coerceAtMost(durationSec),
                    onValueChange = { range ->
                        val minGap = minOf(0.1f, durationSec)
                        val newStart = range.start.coerceIn(
                            0f,
                            (durationSec - minGap).coerceAtLeast(0f)
                        )
                        val newEnd = range.endInclusive.coerceIn(
                            (newStart + minGap).coerceAtMost(durationSec),
                            durationSec
                        )
                        val seekSec = if (
                            abs(newStart - startSec) >= abs(newEnd - endSec)
                        ) {
                            newStart
                        } else {
                            newEnd
                        }
                        onTrimChange(newStart, newEnd, seekSec)
                    },
                    valueRange = 0f..durationSec,
                    enabled = !converting
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TimePill("IN", (startSec * 1000).toLong())
                    TimePill("OUT", (endSec * 1000).toLong())
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSetIn,
                        enabled = !converting && info != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("현재 위치 → IN")
                    }
                    FilledTonalButton(
                        onClick = onSetOut,
                        enabled = !converting && info != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("현재 위치 → OUT")
                    }
                }

                OutlinedButton(
                    onClick = onPickAnother,
                    enabled = !converting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("다른 영상 선택")
                }
            }
        }
    }
}

@Composable
private fun TimePill(
    label: String,
    timeMs: Long
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            label + "  " + formatDetailedTime(timeMs),
            modifier = Modifier.padding(
                horizontal = 11.dp,
                vertical = 7.dp
            ),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun OutputSettingsCard(
    fps: Int,
    quality: Int,
    maxSide: Int?,
    lossless: Boolean,
    loopForever: Boolean,
    speed: ConversionSpeed,
    enabled: Boolean,
    onFpsChange: (Int) -> Unit,
    onQualityChange: (Int) -> Unit,
    onMaxSideChange: (Int?) -> Unit,
    onLosslessChange: (Boolean) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onSpeedChange: (ConversionSpeed) -> Unit,
    onApplyPreset: (QuickPreset) -> Unit
) {
    SectionCard(
        title = "출력 설정",
        subtitle = "화질·속도 설정은 변경 즉시 현재 프리셋에 자동 저장"
    ) {
        Text("빠른 프리셋", fontWeight = FontWeight.SemiBold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                listOf(
                    QuickPreset(
                        "초고속",
                        480,
                        12,
                        72,
                        ConversionSpeed.TURBO
                    ),
                    QuickPreset(
                        "추천",
                        720,
                        15,
                        80,
                        ConversionSpeed.FAST
                    ),
                    QuickPreset(
                        "고화질",
                        1080,
                        20,
                        88,
                        ConversionSpeed.BALANCED
                    )
                )
            ) { preset ->
                FilterChip(
                    selected =
                        maxSide == preset.maxSide &&
                            fps == preset.fps &&
                            quality == preset.quality &&
                            speed == preset.speed &&
                            !lossless,
                    onClick = { onApplyPreset(preset) },
                    enabled = enabled,
                    label = { Text(preset.label) }
                )
            }
        }

        Text("해상도", fontWeight = FontWeight.SemiBold)
        val resolutions = listOf(
            ResolutionOption("원본", null),
            ResolutionOption("1080", 1080),
            ResolutionOption("720", 720),
            ResolutionOption("480", 480),
            ResolutionOption("320", 320)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(resolutions) { option ->
                FilterChip(
                    selected = maxSide == option.maxSide,
                    onClick = { onMaxSideChange(option.maxSide) },
                    enabled = enabled,
                    label = { Text(option.label) }
                )
            }
        }

        SliderSetting(
            title = "FPS",
            valueText = fps.toString(),
            value = fps.toFloat(),
            range = 5f..30f,
            steps = 24,
            enabled = enabled,
            onValue = { onFpsChange(it.roundToInt()) }
        )

        SliderSetting(
            title = if (lossless) "압축 강도" else "화질",
            valueText = quality.toString(),
            value = quality.toFloat(),
            range = 10f..100f,
            steps = 89,
            enabled = enabled,
            onValue = { onQualityChange(it.roundToInt()) }
        )

        Text("변환 프리셋", fontWeight = FontWeight.SemiBold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                listOf(
                    ConversionSpeed.TURBO to "터보",
                    ConversionSpeed.FAST to "빠름",
                    ConversionSpeed.BALANCED to "균형",
                    ConversionSpeed.MAX_COMPRESSION to "최대 압축"
                )
            ) { option ->
                FilterChip(
                    selected = speed == option.first,
                    onClick = { onSpeedChange(option.first) },
                    enabled = enabled,
                    label = { Text(option.second) }
                )
            }
        }

        SettingSwitch(
            title = "무손실 WebP",
            description = "최대 화질. 파일 크기는 크게 증가할 수 있습니다.",
            checked = lossless,
            enabled = enabled,
            onCheckedChange = onLosslessChange
        )

        SettingSwitch(
            title = "무한 반복",
            description = "Animated WebP를 계속 반복 재생합니다.",
            checked = loopForever,
            enabled = enabled,
            onCheckedChange = onLoopChange
        )
    }
}

@Composable
private fun FramingCard(
    cropAspect: CropAspect,
    focusX: Float,
    focusY: Float,
    enabled: Boolean,
    onCropAspectChange: (CropAspect) -> Unit,
    onFocusChange: (Float, Float) -> Unit
) {
    SectionCard(
        title = "화면비 · 크롭 · 포커스",
        subtitle = "원하는 화면비로 자르고 선택한 지점을 화면 중심에 맞춤"
    ) {
        Text(
            "출력 화면비",
            fontWeight = FontWeight.SemiBold
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                listOf(
                    CropAspect.ORIGINAL to "원본",
                    CropAspect.SQUARE to "1:1",
                    CropAspect.PORTRAIT_4_5 to "4:5",
                    CropAspect.PORTRAIT_9_16 to "9:16",
                    CropAspect.PORTRAIT_3_4 to "3:4",
                    CropAspect.LANDSCAPE_16_9 to "16:9"
                )
            ) { option ->
                FilterChip(
                    selected = cropAspect == option.first,
                    onClick = {
                        onCropAspectChange(option.first)
                    },
                    enabled = enabled,
                    label = { Text(option.second) }
                )
            }
        }

        if (cropAspect == CropAspect.ORIGINAL) {
            Text(
                "원본 화면비에서는 크롭하지 않습니다. 화면비를 선택하면 포커스 기능이 활성화됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "포커스 X " +
                            (focusX * 100).roundToInt() +
                            "% · Y " +
                            (focusY * 100).roundToInt() +
                            "%",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "영상 화면을 직접 탭하면 그 지점이 크롭 중심이 됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "빠른 포커스",
                fontWeight = FontWeight.SemiBold
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    listOf(
                        Triple("중앙", 0.5f, 0.5f),
                        Triple("얼굴", 0.5f, 0.28f),
                        Triple("상체", 0.5f, 0.40f),
                        Triple("전신", 0.5f, 0.55f)
                    )
                ) { preset ->
                    FilterChip(
                        selected =
                            kotlin.math.abs(focusX - preset.second) < 0.02f &&
                                kotlin.math.abs(focusY - preset.third) < 0.02f,
                        onClick = {
                            onFocusChange(
                                preset.second,
                                preset.third
                            )
                        },
                        enabled = enabled,
                        label = { Text(preset.first) }
                    )
                }
            }
        }

        Text(
            "화면비와 포커스도 현재 프리셋에 자동 저장됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun StorageCard(
    folderName: String,
    customFolder: Boolean,
    enabled: Boolean,
    onChooseFolder: () -> Unit,
    onResetFolder: () -> Unit
) {
    SectionCard(
        title = "저장 위치",
        subtitle = "출력 WebP를 저장할 폴더"
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (customFolder) "선택한 폴더" else "기본 폴더",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    folderName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onChooseFolder,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text("폴더 선택")
            }

            if (customFolder) {
                OutlinedButton(
                    onClick = onResetFolder,
                    enabled = enabled
                ) {
                    Text("기본값")
                }
            }
        }
    }
}

@Composable
private fun SplitCard(
    mode: SplitMode,
    count: Int,
    targetMb: Int,
    enabled: Boolean,
    onModeChange: (SplitMode) -> Unit,
    onCountChange: (Int) -> Unit,
    onTargetMbChange: (Int) -> Unit
) {
    SectionCard(
        title = "분할 생성",
        subtitle = "긴 영상을 여러 Animated WebP로 나눠 저장"
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                listOf(
                    SplitMode.NONE to "분할 없음",
                    SplitMode.COUNT to "개수 기준",
                    SplitMode.SIZE to "용량 기준"
                )
            ) { option ->
                FilterChip(
                    selected = mode == option.first,
                    onClick = { onModeChange(option.first) },
                    enabled = enabled,
                    label = { Text(option.second) }
                )
            }
        }

        when (mode) {
            SplitMode.NONE -> {
                Text(
                    "선택한 트림 구간을 하나의 WebP로 생성합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SplitMode.COUNT -> {
                SliderSetting(
                    title = "생성 파일 수",
                    valueText = count.toString() + "개",
                    value = count.toFloat(),
                    range = 2f..20f,
                    steps = 17,
                    enabled = enabled,
                    onValue = { onCountChange(it.roundToInt()) }
                )
                Text(
                    "선택 구간을 시간 기준으로 동일하게 나눕니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SplitMode.SIZE -> {
                SliderSetting(
                    title = "파일당 목표 용량",
                    valueText = targetMb.toString() + " MB",
                    value = targetMb.toFloat(),
                    range = 1f..100f,
                    steps = 98,
                    enabled = enabled,
                    onValue = { onTargetMbChange(it.roundToInt()) }
                )
                Text(
                    "FFmpeg가 목표 용량 근처에서 각 파트를 종료합니다. 코덱 특성상 실제 크기는 약간 넘을 수 있습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ConversionActionCard(
    converting: Boolean,
    progress: Float,
    status: String,
    onConvert: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                status,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )

            if (converting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    (progress * 100).roundToInt().toString() + "%",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("변환 취소")
                }
            } else {
                Button(
                    onClick = onConvert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        "Animated WebP 만들기",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: ConversionResult,
    selectedIndex: Int,
    onSelectPart: (Int) -> Unit,
    onOpenSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onShareAll: () -> Unit
) {
    val safeIndex = selectedIndex.coerceIn(
        0,
        (result.parts.size - 1).coerceAtLeast(0)
    )
    val preview = result.parts.getOrNull(safeIndex)

    SectionCard(
        title = "완료",
        subtitle = result.parts.size.toString() + "개 파일 · " +
            formatBytes(result.totalSizeBytes) + " · " +
            "%.2f초".format(result.elapsedMs / 1000.0)
    ) {
        preview?.let {
            AnimatedWebPPreview(it.uri)

            if (result.parts.size > 1) {
                Text(
                    "생성 파일",
                    fontWeight = FontWeight.SemiBold
                )

                result.parts.forEachIndexed { index, part ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPart(index) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (index == safeIndex) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "Part " + part.index.toString().padStart(2, '0'),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    part.fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                formatBytes(part.sizeBytes),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            } else {
                Text(
                    preview.fileName + " · " + formatBytes(preview.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onOpenSelected,
                modifier = Modifier.weight(1f)
            ) {
                Text("선택 파일 열기")
            }
            Button(
                onClick = onShareSelected,
                modifier = Modifier.weight(1f)
            ) {
                Text("선택 공유")
            }
        }

        if (result.parts.size > 1) {
            FilledTonalButton(
                onClick = onShareAll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("전체 파일 공유")
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            content()
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValue: (Float) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                valueText,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun AnimatedWebPPreview(uri: Uri) {
    val context = LocalContext.current

    key(uri) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                            val source = ImageDecoder.createSource(
                                context.contentResolver,
                                uri
                            )
                            val drawable = ImageDecoder.decodeDrawable(source)
                            setImageDrawable(drawable)
                            (drawable as? Animatable)?.start()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun openResult(
    context: android.content.Context,
    uri: Uri
) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/webp")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(view, "WebP 열기")
        )
    }
}

private fun shareSingleResult(
    context: android.content.Context,
    uri: Uri
) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/webp"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "WebP 공유")
    )
}

private fun shareResult(
    context: android.content.Context,
    result: ConversionResult
) {
    if (result.parts.size <= 1) {
        val uri = result.parts.firstOrNull()?.uri ?: return
        shareSingleResult(context, uri)
        return
    }

    val uris = ArrayList<Uri>()
    result.parts.forEach { uris.add(it.uri) }

    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/webp"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "WebP 파일 공유")
    )
}
