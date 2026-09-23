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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.launch
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
    val subjectTracker = remember {
        FaceTrackingAnalyzer(context.applicationContext)
    }
    val prefs = remember {
        context.getSharedPreferences("video_to_webp", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
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

    var cropZoom by remember {
        mutableFloatStateOf(
            prefs.getFloat("crop_zoom", 1f)
                .coerceIn(1f, 4f)
        )
    }

    var targetTotalSizeEnabled by remember {
        mutableStateOf(
            prefs.getBoolean(
                "target_total_enabled",
                false
            )
        )
    }

    var targetTotalSizeMb by remember {
        mutableIntStateOf(
            prefs.getInt(
                "target_total_mb",
                8
            ).coerceIn(1, 100)
        )
    }

    var manualKeyframes by remember {
        mutableStateOf<List<FocusKeyframe>>(
            emptyList()
        )
    }

    var previewTrack by remember {
        mutableStateOf<List<FocusKeyframe>>(
            emptyList()
        )
    }

    var trackingPreviewBusy by remember {
        mutableStateOf(false)
    }

    var trackingPreviewProgress by remember {
        mutableFloatStateOf(0f)
    }

    var trackingMode by remember {
        mutableStateOf(
            runCatching {
                TrackingMode.valueOf(
                    prefs.getString(
                        "tracking_mode",
                        null
                    ) ?: if (
                        prefs.getBoolean(
                            "auto_face_track",
                            false
                        )
                    ) {
                        TrackingMode.FACE.name
                    } else {
                        TrackingMode.FIXED.name
                    }
                )
            }.getOrDefault(TrackingMode.FIXED)
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
    var batchUris by remember {
        mutableStateOf<List<Uri>>(emptyList())
    }
    var batchRunToken by remember {
        mutableIntStateOf(0)
    }
    var selectedTab by rememberSaveable {
        mutableIntStateOf(0)
    }

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

            subjectTracker.cancel()
            engine.cancel()
            exoPlayer.pause()
            converting = false
            progress = 0f
            result = null
            selectedResultPart = 0
            selectedTab = 0
            videoInfo = null
            manualKeyframes = emptyList()
            previewTrack = emptyList()
            trackingPreviewBusy = false
            trackingPreviewProgress = 0f
            videoUri = uri
            currentPositionMs = 0L
        }
    }

    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val unique = uris.distinct()

            unique.forEach { uri ->
                try {
                    context.contentResolver
                        .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                } catch (_: Throwable) {
                }
            }

            batchUris = unique
            status =
                "배치 큐에 " +
                    unique.size +
                    "개 영상 추가"
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
        focusY,
        cropZoom,
        targetTotalSizeEnabled,
        targetTotalSizeMb,
        trackingMode
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
            .putFloat("crop_zoom", cropZoom)
            .putBoolean(
                "target_total_enabled",
                targetTotalSizeEnabled
            )
            .putInt(
                "target_total_mb",
                targetTotalSizeMb
            )
            .putString("tracking_mode", trackingMode.name)
            .remove("auto_face_track")
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

    LaunchedEffect(
        videoUri,
        trackingMode,
        startSec,
        endSec
    ) {
        previewTrack = emptyList()
        trackingPreviewProgress = 0f
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
            subjectTracker.cancel()
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
        val startMs =
            (safeStart * 1000).roundToInt().toLong()
        val endMs =
            (safeEnd * 1000).roundToInt().toLong()
        val shouldTrack =
            trackingMode != TrackingMode.FIXED &&
                (
                    cropAspect != CropAspect.ORIGINAL ||
                        cropZoom > 1.001f
                    )

        exoPlayer.pause()
        subjectTracker.cancel()
        engine.cancel()
        converting = true
        result = null
        selectedResultPart = 0
        error = null
        progress = 0f

        fun runConversion(
            tracking: List<FocusKeyframe>
        ) {
            val mergedTracking =
                mergeTrackingKeyframes(
                    automatic = tracking,
                    manual = manualKeyframes,
                    startMs = startMs,
                    endMs = endMs
                )
            val analysisWeight = when {
                !shouldTrack -> 0f
                trackingMode == TrackingMode.FACE -> 0.22f
                else -> 0.30f
            }

            engine.convert(
                sourceUri = uri,
                settings = ConversionSettings(
                    startMs = startMs,
                    endMs = endMs,
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
                    targetTotalSizeMb =
                        if (
                            targetTotalSizeEnabled &&
                            splitMode == SplitMode.NONE &&
                            !lossless
                        ) {
                            targetTotalSizeMb
                        } else {
                            null
                        },
                    cropAspect = cropAspect,
                    focusX = focusX,
                    focusY = focusY,
                    cropZoom = cropZoom,
                    focusTrack = mergedTracking
                ),
                onStatus = { status = it },
                onProgress = {
                    progress = (
                        analysisWeight +
                            it.coerceIn(0f, 1f) *
                            (1f - analysisWeight)
                        ).coerceIn(0f, 1f)
                },
                onComplete = {
                    converting = false
                    progress = 1f
                    result = it
                    selectedResultPart = 0
                    selectedTab = 3
                    status = if (it.parts.size == 1) {
                        "변환 완료 · " + folderName
                    } else {
                        it.parts.size.toString() +
                            "개 파일 생성 완료 · " +
                            folderName
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

        if (!shouldTrack) {
            runConversion(emptyList())
            return
        }

        if (previewTrack.isNotEmpty()) {
            status = "미리 분석한 추적 경로를 사용합니다."
            runConversion(previewTrack)
            return
        }

        subjectTracker.analyze(
            sourceUri = uri,
            startMs = startMs,
            endMs = endMs,
            initialFocusX = focusX,
            initialFocusY = focusY,
            mode = trackingMode,
            onStatus = { status = it },
            onProgress = {
                progress =
                    (it.coerceIn(0f, 1f) * 0.22f)
                        .coerceIn(0f, 0.22f)
            },
            onComplete = { points ->
                previewTrack = points
                if (points.isEmpty()) {
                    status =
                        "추적 대상을 찾지 못해 고정 포커스로 변환합니다."
                    runConversion(emptyList())
                } else {
                    val label = when (trackingMode) {
                        TrackingMode.FACE -> "얼굴"
                        TrackingMode.UPPER_BODY -> "상체"
                        TrackingMode.FULL_BODY -> "전신"
                        TrackingMode.FIXED -> "고정"
                    }
                    status =
                        label +
                            " 추적 " +
                            points.size +
                            "개 지점 분석 완료"
                    runConversion(points)
                }
            },
            onError = {
                status =
                    "추적 분석 실패 · 고정 포커스로 계속 변환합니다."
                runConversion(emptyList())
            },
            onCancelled = {
                converting = false
                progress = 0f
                status = "변환이 취소되었습니다."
            }
        )
    }

    fun startBatchConversion() {
        val queue = batchUris
        if (queue.isEmpty()) {
            startConversion()
            return
        }

        subjectTracker.cancel()
        engine.cancel()
        exoPlayer.pause()

        batchRunToken += 1
        val runToken = batchRunToken

        converting = true
        progress = 0f
        result = null
        selectedResultPart = 0
        error = null

        val allParts =
            mutableListOf<ConversionPartResult>()
        val batchStartedAt =
            android.os.SystemClock
                .elapsedRealtime()

        var queueIndex = 0

        fun finishBatch() {
            converting = false
            progress = 1f

            result = ConversionResult(
                parts = allParts.toList(),
                totalSizeBytes =
                    allParts.sumOf {
                        it.sizeBytes
                    },
                elapsedMs =
                    android.os.SystemClock
                        .elapsedRealtime() -
                        batchStartedAt
            )

            selectedResultPart = 0
            selectedTab = 3
            status =
                "배치 변환 완료 · " +
                    queue.size +
                    "개 영상"
        }

        lateinit var convertNext: () -> Unit

        convertNext = next@{
            if (
                batchRunToken != runToken ||
                !converting
            ) {
                return@next
            }

            if (queueIndex >= queue.size) {
                finishBatch()
            } else {
                val currentUri =
                    queue[queueIndex]

                status =
                    "배치 " +
                        (queueIndex + 1) +
                        "/" +
                        queue.size +
                        " · 영상 정보 읽는 중…"

                scope.launch {
                    val currentInfo =
                        try {
                            withContext(
                                Dispatchers.IO
                            ) {
                                readVideoInfo(
                                    context,
                                    currentUri
                                )
                            }
                        } catch (e: Throwable) {
                            converting = false
                            progress = 0f
                            error =
                                "배치 " +
                                    (queueIndex + 1) +
                                    " 영상 정보를 읽지 못했습니다. " +
                                    (
                                        e.message
                                            ?: e.javaClass.simpleName
                                        )
                            status = "배치 변환 실패"
                            return@launch
                        }

                    if (
                        batchRunToken != runToken ||
                        !converting
                    ) {
                        return@launch
                    }

                    val itemStartMs = 0L
                    val itemEndMs =
                        currentInfo.durationMs
                            .coerceAtLeast(100L)

                    fun runBatchEngine(
                        track:
                            List<FocusKeyframe>
                    ) {
                        val base =
                            queueIndex.toFloat() /
                                queue.size
                        val span =
                            1f /
                                queue.size

                        engine.convert(
                            sourceUri =
                                currentUri,
                            settings =
                                ConversionSettings(
                                    startMs =
                                        itemStartMs,
                                    endMs =
                                        itemEndMs,
                                    fps = fps,
                                    quality =
                                        quality,
                                    maxSide =
                                        maxSide,
                                    lossless =
                                        lossless,
                                    loopForever =
                                        loopForever,
                                    speed =
                                        speed,
                                    outputTreeUri =
                                        outputTreeUri,
                                    splitMode =
                                        splitMode,
                                    splitCount =
                                        splitCount,
                                    targetPartSizeMb =
                                        targetPartSizeMb,
                                    targetTotalSizeMb =
                                        if (
                                            targetTotalSizeEnabled &&
                                            splitMode ==
                                                SplitMode.NONE &&
                                            !lossless
                                        ) {
                                            targetTotalSizeMb
                                        } else {
                                            null
                                        },
                                    cropAspect =
                                        cropAspect,
                                    focusX =
                                        focusX,
                                    focusY =
                                        focusY,
                                    cropZoom =
                                        cropZoom,
                                    focusTrack =
                                        track
                                ),
                            onStatus = {
                                status =
                                    "배치 " +
                                        (queueIndex + 1) +
                                        "/" +
                                        queue.size +
                                        " · " +
                                        it
                            },
                            onProgress = {
                                progress =
                                    (
                                        base +
                                            it.coerceIn(
                                                0f,
                                                1f
                                            ) *
                                            span
                                        )
                                        .coerceIn(
                                            0f,
                                            1f
                                        )
                            },
                            onComplete = {
                                if (
                                    batchRunToken ==
                                        runToken &&
                                    converting
                                ) {
                                    allParts +=
                                        it.parts
                                    queueIndex += 1
                                    convertNext()
                                }
                            },
                            onError = {
                                converting = false
                                progress = 0f
                                error =
                                    "배치 " +
                                        (queueIndex + 1) +
                                        " 실패\n" +
                                        it
                                status =
                                    "배치 변환 실패"
                            },
                            onCancelled = {
                                converting = false
                                progress = 0f
                                status =
                                    "배치 변환이 취소되었습니다."
                            }
                        )
                    }

                    val hasCrop =
                        cropAspect !=
                            CropAspect.ORIGINAL ||
                            cropZoom > 1.001f

                    if (
                        trackingMode ==
                            TrackingMode.FIXED ||
                        !hasCrop
                    ) {
                        runBatchEngine(
                            emptyList()
                        )
                    } else {
                        subjectTracker.analyze(
                            sourceUri =
                                currentUri,
                            startMs =
                                itemStartMs,
                            endMs =
                                itemEndMs,
                            initialFocusX =
                                focusX,
                            initialFocusY =
                                focusY,
                            mode =
                                trackingMode,
                            onStatus = {
                                status =
                                    "배치 " +
                                        (queueIndex + 1) +
                                        "/" +
                                        queue.size +
                                        " · " +
                                        it
                            },
                            onProgress = {
                                val base =
                                    queueIndex
                                        .toFloat() /
                                        queue.size
                                val span =
                                    1f /
                                        queue.size

                                progress =
                                    (
                                        base +
                                            it.coerceIn(
                                                0f,
                                                1f
                                            ) *
                                            span *
                                            0.20f
                                        )
                                        .coerceIn(
                                            0f,
                                            1f
                                        )
                            },
                            onComplete = {
                                if (
                                    batchRunToken ==
                                        runToken &&
                                    converting
                                ) {
                                    runBatchEngine(it)
                                }
                            },
                            onError = {
                                if (
                                    batchRunToken ==
                                        runToken &&
                                    converting
                                ) {
                                    runBatchEngine(
                                        emptyList()
                                    )
                                }
                            },
                            onCancelled = {
                                converting = false
                                progress = 0f
                                status =
                                    "배치 변환이 취소되었습니다."
                            }
                        )
                    }
                }
            }
        }

        convertNext()
    }

    fun analyzeTrackingPreview() {
        val uri = videoUri ?: return
        val info = videoInfo ?: return

        if (trackingMode == TrackingMode.FIXED) {
            previewTrack = emptyList()
            trackingPreviewProgress = 0f
            status = "고정 포커스 모드입니다."
            return
        }

        if (
            cropAspect == CropAspect.ORIGINAL &&
            cropZoom <= 1.001f
        ) {
            status =
                "화면비를 바꾸거나 크롭 확대를 적용한 뒤 추적을 분석하세요."
            return
        }

        val startMs =
            (startSec * 1000f)
                .toLong()
                .coerceAtLeast(0L)
        val endMs =
            (endSec * 1000f)
                .toLong()
                .coerceAtMost(info.durationMs)

        subjectTracker.cancel()
        trackingPreviewBusy = true
        trackingPreviewProgress = 0f
        previewTrack = emptyList()

        subjectTracker.analyze(
            sourceUri = uri,
            startMs = startMs,
            endMs = endMs,
            initialFocusX = focusX,
            initialFocusY = focusY,
            mode = trackingMode,
            onStatus = {
                status = "미리보기 · " + it
            },
            onProgress = {
                trackingPreviewProgress =
                    it.coerceIn(0f, 1f)
            },
            onComplete = { points ->
                trackingPreviewBusy = false
                trackingPreviewProgress = 1f
                previewTrack = points
                status = if (points.isEmpty()) {
                    "추적 대상을 찾지 못했습니다."
                } else {
                    "추적 경로 " +
                        points.size +
                        "개 지점 분석 완료"
                }
            },
            onError = {
                trackingPreviewBusy = false
                trackingPreviewProgress = 0f
                status = it
            },
            onCancelled = {
                trackingPreviewBusy = false
                trackingPreviewProgress = 0f
                status = "추적 미리보기가 취소되었습니다."
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (
                    videoInfo != null ||
                    batchUris.isNotEmpty()
                ) {
                    CompactConversionBar(
                        converting = converting,
                        progress = progress,
                        status = status,
                        enabled =
                            videoInfo != null ||
                                batchUris.isNotEmpty(),
                        actionLabel =
                            if (batchUris.isNotEmpty()) {
                                "배치 " +
                                    batchUris.size +
                                    "개 Animated WebP 만들기"
                            } else {
                                "Animated WebP 만들기"
                            },
                        onConvert = {
                            if (batchUris.isNotEmpty()) {
                                startBatchConversion()
                            } else {
                                startConversion()
                            }
                        },
                        onCancel = {
                            batchRunToken += 1
                            converting = false
                            subjectTracker.cancel()
                            engine.cancel()
                            progress = 0f
                            status = "변환이 취소되었습니다."
                        }
                    )
                }

                MobileNavigationBar(
                    selectedTab = selectedTab,
                    hasResult = result != null,
                    onSelect = { selectedTab = it }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactHeader(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange
            )

            if (videoInfo != null) {
                SettingsSummaryBar(
                    maxSide = maxSide,
                    fps = fps,
                    quality = quality,
                    cropAspect = cropAspect,
                    trackingMode = trackingMode,
                    splitMode = splitMode
                )
            }

            if (metadataLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when (selectedTab) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (videoUri == null) {
                            EmptyVideoCard(
                                onPick = {
                                    videoPicker.launch(arrayOf("video/*"))
                                }
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
                                cropZoom = cropZoom,
                                converting = converting,
                                onPickAnother = {
                                    videoPicker.launch(arrayOf("video/*"))
                                },
                                onTogglePlay = {
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        val startMs =
                                            (startSec * 1000).toLong()
                                        val endMs =
                                            (endSec * 1000).toLong()
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
                                    seekPlayer(
                                        currentPositionMs + delta
                                    )
                                },
                                onSetIn = {
                                    videoInfo?.let {
                                        val maxIn =
                                            (endSec - 0.1f)
                                                .coerceAtLeast(0f)
                                        startSec =
                                            (currentPositionMs / 1000f)
                                                .coerceIn(0f, maxIn)
                                        exoPlayer.pause()
                                    }
                                },
                                onSetOut = {
                                    videoInfo?.let { currentInfo ->
                                        val maxOut =
                                            currentInfo.durationMs / 1000f
                                        val minOut =
                                            (startSec + 0.1f)
                                                .coerceAtMost(maxOut)
                                        endSec =
                                            (currentPositionMs / 1000f)
                                                .coerceIn(
                                                    minOut,
                                                    maxOut
                                                )
                                        exoPlayer.pause()
                                    }
                                },
                                onTrimChange = {
                                        newStart,
                                        newEnd,
                                        seekSec ->
                                    startSec = newStart
                                    endSec = newEnd
                                    exoPlayer.pause()
                                    seekPlayer(
                                        (seekSec * 1000f).toLong()
                                    )
                                },
                                onFocusChange = { x, y ->
                                    focusX = x.coerceIn(0f, 1f)
                                    focusY = y.coerceIn(0f, 1f)
                                }
                            )

                            videoUri?.let { currentUri ->
                                TimelineThumbnailStrip(
                                    context = context,
                                    uri = currentUri,
                                    startMs = 0L,
                                    endMs =
                                        videoInfo?.durationMs
                                            ?: (
                                                endSec *
                                                    1000f
                                                )
                                                .toLong(),
                                    currentMs =
                                        currentPositionMs,
                                    onSeek = { target ->
                                        exoPlayer.pause()
                                        seekPlayer(target)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                1 -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val currentInfo = videoInfo
                        if (currentInfo == null) {
                            EmptyTabHint(
                                title = "추적할 영상이 없습니다",
                                message = "편집 탭에서 먼저 영상을 선택하세요.",
                                action = "편집으로 이동",
                                onAction = { selectedTab = 0 }
                            )
                        } else {
                            CompactFocusPreview(
                                exoPlayer = exoPlayer,
                                info = currentInfo,
                                cropAspect = cropAspect,
                                focusX = focusX,
                                focusY = focusY,
                                cropZoom = cropZoom,
                                trackingPath =
                                    mergeTrackingKeyframes(
                                        previewTrack,
                                        manualKeyframes,
                                        (startSec * 1000f)
                                            .toLong(),
                                        (endSec * 1000f)
                                            .toLong()
                                    ),
                                manualKeyframes =
                                    manualKeyframes,
                                converting = converting,
                                onFocusChange = { x, y ->
                                    focusX = x.coerceIn(0f, 1f)
                                    focusY = y.coerceIn(0f, 1f)
                                }
                            )

                            FramingCard(
                                cropAspect = cropAspect,
                                focusX = focusX,
                                focusY = focusY,
                                cropZoom = cropZoom,
                                trackingMode = trackingMode,
                                enabled =
                                    !converting &&
                                        !trackingPreviewBusy,
                                onCropAspectChange = {
                                    cropAspect = it
                                },
                                onFocusChange = { x, y ->
                                    focusX = x.coerceIn(0f, 1f)
                                    focusY = y.coerceIn(0f, 1f)
                                },
                                onCropZoomChange = {
                                    cropZoom =
                                        it.coerceIn(1f, 4f)
                                },
                                onResetCrop = {
                                    cropZoom = 1f
                                    focusX = 0.5f
                                    focusY = 0.5f
                                },
                                onTrackingModeChange = {
                                    trackingMode = it
                                }
                            )

                            TrackingEditorCard(
                                currentPositionMs =
                                    currentPositionMs,
                                focusX = focusX,
                                focusY = focusY,
                                automaticPoints =
                                    previewTrack,
                                manualPoints =
                                    manualKeyframes,
                                analyzing =
                                    trackingPreviewBusy,
                                analysisProgress =
                                    trackingPreviewProgress,
                                enabled =
                                    !converting,
                                onAnalyze = {
                                    analyzeTrackingPreview()
                                },
                                onAddManual = {
                                    val point =
                                        FocusKeyframe(
                                            timeMs =
                                                currentPositionMs,
                                            x = focusX,
                                            y = focusY
                                        )

                                    manualKeyframes =
                                        (
                                            manualKeyframes
                                                .filter {
                                                    kotlin.math.abs(
                                                        it.timeMs -
                                                            point.timeMs
                                                    ) > 120L
                                                } +
                                                point
                                            )
                                            .sortedBy {
                                                it.timeMs
                                            }
                                },
                                onRemoveManual = { point ->
                                    manualKeyframes =
                                        manualKeyframes
                                            .filterNot {
                                                it == point
                                            }
                                },
                                onClear = {
                                    manualKeyframes =
                                        emptyList()
                                    previewTrack =
                                        emptyList()
                                    trackingPreviewProgress =
                                        0f
                                },
                                onSeek = { timeMs ->
                                    exoPlayer.pause()
                                    seekPlayer(timeMs)
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                2 -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (videoInfo == null) {
                            EmptyTabHint(
                                title = "출력할 영상이 없습니다",
                                message = "편집 탭에서 영상을 선택하면 출력 설정을 사용할 수 있습니다.",
                                action = "영상 선택",
                                onAction = { selectedTab = 0 }
                            )
                        } else {
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

                            videoInfo?.let { currentInfo ->
                                EstimateTargetCard(
                                    info = currentInfo,
                                    durationMs =
                                        (
                                            (endSec - startSec)
                                                .coerceAtLeast(0.1f) *
                                                1000f
                                            )
                                            .toLong(),
                                    fps = fps,
                                    quality = quality,
                                    maxSide = maxSide,
                                    lossless = lossless,
                                    cropZoom = cropZoom,
                                    speed = speed,
                                    splitMode = splitMode,
                                    enabled = !converting,
                                    targetEnabled =
                                        targetTotalSizeEnabled,
                                    targetMb =
                                        targetTotalSizeMb,
                                    onTargetEnabledChange = {
                                        targetTotalSizeEnabled =
                                            it
                                        if (it) {
                                            splitMode =
                                                SplitMode.NONE
                                            lossless = false
                                        }
                                    },
                                    onTargetMbChange = {
                                        targetTotalSizeMb =
                                            it.coerceIn(1, 100)
                                    },
                                    onApplyRecommendedQuality = {
                                        quality =
                                            it.coerceIn(10, 100)
                                    }
                                )
                            }

                            StorageCard(
                                folderName = folderName,
                                customFolder = outputTreeUri != null,
                                enabled = !converting,
                                onChooseFolder = {
                                    folderPicker.launch(outputTreeUri)
                                },
                                onResetFolder = {
                                    outputTreeUri = null
                                    prefs.edit()
                                        .remove("output_tree_uri")
                                        .apply()
                                }
                            )

                            BatchQueueCard(
                                queue = batchUris,
                                enabled = !converting,
                                onChoose = {
                                    batchPicker.launch(
                                        arrayOf("video/*")
                                    )
                                },
                                onClear = {
                                    batchUris =
                                        emptyList()
                                }
                            )

                            SplitCard(
                                mode = splitMode,
                                count = splitCount,
                                targetMb = targetPartSizeMb,
                                enabled = !converting,
                                onModeChange = { splitMode = it },
                                onCountChange = {
                                    splitCount = it
                                },
                                onTargetMbChange = {
                                    targetPartSizeMb = it
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val converted = result
                        if (converted == null) {
                            EmptyTabHint(
                                title = "아직 결과가 없습니다",
                                message = "다른 탭에서 설정을 마친 뒤 아래 고정 버튼으로 변환하세요.",
                                action = "출력 설정 보기",
                                onAction = { selectedTab = 2 }
                            )
                        } else {
                            ResultCard(
                                result = converted,
                                selectedIndex = selectedResultPart,
                                onSelectPart = {
                                    selectedResultPart = it
                                },
                                onOpenSelected = {
                                    converted.parts
                                        .getOrNull(
                                            selectedResultPart
                                        )
                                        ?.let {
                                            openResult(
                                                context,
                                                it.uri
                                            )
                                        }
                                },
                                onShareSelected = {
                                    converted.parts
                                        .getOrNull(
                                            selectedResultPart
                                        )
                                        ?.let {
                                            shareSingleResult(
                                                context,
                                                it.uri
                                            )
                                        }
                                },
                                onShareAll = {
                                    shareResult(
                                        context,
                                        converted
                                    )
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
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
private fun CompactHeader(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val nextTheme = when (themeMode) {
        ThemeMode.SYSTEM -> ThemeMode.LIGHT
        ThemeMode.LIGHT -> ThemeMode.DARK
        ThemeMode.DARK -> ThemeMode.SYSTEM
    }
    val themeLabel = when (themeMode) {
        ThemeMode.SYSTEM -> "시스템"
        ThemeMode.LIGHT -> "라이트"
        ThemeMode.DARK -> "다크"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                "Motion WebP",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "모바일 편집기",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FilledTonalButton(
            onClick = {
                onThemeModeChange(nextTheme)
            }
        ) {
            Text(themeLabel)
        }
    }
}

@Composable
private fun SettingsSummaryBar(
    maxSide: Int?,
    fps: Int,
    quality: Int,
    cropAspect: CropAspect,
    trackingMode: TrackingMode,
    splitMode: SplitMode
) {
    val aspectLabel = when (cropAspect) {
        CropAspect.ORIGINAL -> "원본비"
        CropAspect.SQUARE -> "1:1"
        CropAspect.PORTRAIT_4_5 -> "4:5"
        CropAspect.PORTRAIT_9_16 -> "9:16"
        CropAspect.PORTRAIT_3_4 -> "3:4"
        CropAspect.LANDSCAPE_16_9 -> "16:9"
    }
    val trackingLabel = when (trackingMode) {
        TrackingMode.FIXED -> "고정"
        TrackingMode.FACE -> "얼굴"
        TrackingMode.UPPER_BODY -> "상체"
        TrackingMode.FULL_BODY -> "전신"
    }
    val splitLabel = when (splitMode) {
        SplitMode.NONE -> "1파일"
        SplitMode.COUNT -> "개수분할"
        SplitMode.SIZE -> "용량분할"
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(
            listOf(
                maxSide?.let { it.toString() + "p" } ?: "원본",
                fps.toString() + "fps",
                "Q" + quality,
                aspectLabel,
                trackingLabel,
                splitLabel
            )
        ) { label ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 6.dp
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun MobileNavigationBar(
    selectedTab: Int,
    hasResult: Boolean,
    onSelect: (Int) -> Unit
) {
    NavigationBar {
        val tabs = listOf(
            Triple("편집", "✂", false),
            Triple("추적", "◎", false),
            Triple("출력", "⚙", false),
            Triple(
                if (hasResult) "결과 •" else "결과",
                "✓",
                hasResult
            )
        )

        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onSelect(index) },
                icon = {
                    Text(
                        tab.second,
                        fontWeight = if (selectedTab == index) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        }
                    )
                },
                label = {
                    Text(tab.first)
                }
            )
        }
    }
}

@Composable
private fun CompactConversionBar(
    converting: Boolean,
    progress: Float,
    status: String,
    enabled: Boolean,
    actionLabel: String,
    onConvert: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 14.dp,
                    vertical = 8.dp
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (converting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            status,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedButton(
                        onClick = onCancel
                    ) {
                        Text("취소")
                    }
                }
            } else {
                Button(
                    onClick = onConvert,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        actionLabel,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTabHint(
    title: String,
    message: String,
    action: String,
    onAction: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(action)
            }
        }
    }
}

@Composable
private fun CompactFocusPreview(
    exoPlayer: ExoPlayer,
    info: VideoInfo,
    cropAspect: CropAspect,
    focusX: Float,
    focusY: Float,
    cropZoom: Float,
    trackingPath: List<FocusKeyframe>,
    manualKeyframes: List<FocusKeyframe>,
    converting: Boolean,
    onFocusChange: (Float, Float) -> Unit
) {
    val aspect = if (
        info.width > 0 &&
        info.height > 0
    ) {
        (info.width.toFloat() / info.height.toFloat())
            .coerceIn(0.56f, 1.9f)
    } else {
        16f / 9f
    }
    val markerColor = MaterialTheme.colorScheme.secondary

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
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
                            resizeMode =
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShowBuffering(
                                PlayerView.SHOW_BUFFERING_WHEN_PLAYING
                            )
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
                            if (
                                !converting &&
                                (
                                    cropAspect != CropAspect.ORIGINAL ||
                                        cropZoom > 1.001f
                                    )
                            ) {
                                detectTapGestures { offset ->
                                    val width =
                                        size.width.toFloat()
                                            .coerceAtLeast(1f)
                                    val height =
                                        size.height.toFloat()
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
                    val hasCrop =
                        cropAspect != CropAspect.ORIGINAL ||
                            cropZoom > 1.001f

                    if (hasCrop) {
                        val zoom =
                            cropZoom.coerceIn(1f, 4f)
                        val targetRatio =
                            cropAspect.ratio
                                ?.toFloat()

                        val baseWidth: Float
                        val baseHeight: Float

                        if (targetRatio == null) {
                            baseWidth = size.width
                            baseHeight = size.height
                        } else {
                            val canvasRatio =
                                size.width / size.height

                            if (canvasRatio > targetRatio) {
                                baseHeight = size.height
                                baseWidth =
                                    baseHeight * targetRatio
                            } else {
                                baseWidth = size.width
                                baseHeight =
                                    baseWidth / targetRatio
                            }
                        }

                        val cropWidth =
                            baseWidth / zoom
                        val cropHeight =
                            baseHeight / zoom

                        val centerX =
                            focusX.coerceIn(0f, 1f) *
                                size.width
                        val centerY =
                            focusY.coerceIn(0f, 1f) *
                                size.height

                        val left =
                            (centerX - cropWidth / 2f)
                                .coerceIn(
                                    0f,
                                    (size.width - cropWidth)
                                        .coerceAtLeast(0f)
                                )
                        val top =
                            (centerY - cropHeight / 2f)
                                .coerceIn(
                                    0f,
                                    (size.height - cropHeight)
                                        .coerceAtLeast(0f)
                                )

                        drawRect(
                            color = Color.White,
                            topLeft =
                                androidx.compose.ui.geometry.Offset(
                                    left,
                                    top
                                ),
                            size =
                                androidx.compose.ui.geometry.Size(
                                    cropWidth,
                                    cropHeight
                                ),
                            style = Stroke(
                                width = 3.dp.toPx()
                            )
                        )
                    }

                    if (trackingPath.size >= 2) {
                        trackingPath.zipWithNext()
                            .forEach { pair ->
                                val a = pair.first
                                val b = pair.second

                                drawLine(
                                    color =
                                        markerColor.copy(
                                            alpha = 0.72f
                                        ),
                                    start =
                                        androidx.compose.ui.geometry.Offset(
                                            a.x.coerceIn(0f, 1f) *
                                                size.width,
                                            a.y.coerceIn(0f, 1f) *
                                                size.height
                                        ),
                                    end =
                                        androidx.compose.ui.geometry.Offset(
                                            b.x.coerceIn(0f, 1f) *
                                                size.width,
                                            b.y.coerceIn(0f, 1f) *
                                                size.height
                                        ),
                                    strokeWidth = 3.dp.toPx()
                                )
                            }
                    }

                    manualKeyframes.forEach { point ->
                        drawCircle(
                            color = Color(0xFFFFC857),
                            radius = 6.dp.toPx(),
                            center =
                                androidx.compose.ui.geometry.Offset(
                                    point.x.coerceIn(0f, 1f) *
                                        size.width,
                                    point.y.coerceIn(0f, 1f) *
                                        size.height
                                )
                        )
                    }

                    drawCircle(
                        color = markerColor,
                        radius = 7.dp.toPx(),
                        center =
                            androidx.compose.ui.geometry.Offset(
                                focusX.coerceIn(0f, 1f) *
                                    size.width,
                                focusY.coerceIn(0f, 1f) *
                                    size.height
                            )
                    )
                }
            }

            Text(
                if (
                    cropAspect == CropAspect.ORIGINAL &&
                    cropZoom <= 1.001f
                ) {
                    "화면비를 바꾸거나 확대하면 크롭 영역이 표시됩니다."
                } else if (trackingPath.isNotEmpty()) {
                    "추적 경로를 선으로 표시 중 · 노란 점은 수동 키프레임"
                } else {
                    "추적할 기준 위치를 영상에서 직접 탭하세요."
                },
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    cropZoom: Float,
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
                            if (
                                !converting &&
                                (
                                    cropAspect != CropAspect.ORIGINAL ||
                                        cropZoom > 1.001f
                                    )
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
                    val hasCrop =
                        cropAspect != CropAspect.ORIGINAL ||
                            cropZoom > 1.001f

                    if (hasCrop) {
                        val zoom =
                            cropZoom.coerceIn(1f, 4f)
                        val targetRatio =
                            cropAspect.ratio
                                ?.toFloat()

                        val baseWidth: Float
                        val baseHeight: Float

                        if (targetRatio == null) {
                            baseWidth = size.width
                            baseHeight = size.height
                        } else {
                            val canvasRatio =
                                size.width / size.height

                            if (canvasRatio > targetRatio) {
                                baseHeight = size.height
                                baseWidth =
                                    baseHeight * targetRatio
                            } else {
                                baseWidth = size.width
                                baseHeight =
                                    baseWidth / targetRatio
                            }
                        }

                        val cropWidth =
                            baseWidth / zoom
                        val cropHeight =
                            baseHeight / zoom

                        val centerX =
                            focusX.coerceIn(0f, 1f) *
                                size.width
                        val centerY =
                            focusY.coerceIn(0f, 1f) *
                                size.height

                        val left =
                            (centerX - cropWidth / 2f)
                                .coerceIn(
                                    0f,
                                    (size.width - cropWidth)
                                        .coerceAtLeast(0f)
                                )
                        val top =
                            (centerY - cropHeight / 2f)
                                .coerceIn(
                                    0f,
                                    (size.height - cropHeight)
                                        .coerceAtLeast(0f)
                                )

                        drawRect(
                            color = Color.White,
                            topLeft =
                                androidx.compose.ui.geometry.Offset(
                                    left,
                                    top
                                ),
                            size =
                                androidx.compose.ui.geometry.Size(
                                    cropWidth,
                                    cropHeight
                                ),
                            style = Stroke(
                                width = 3.dp.toPx()
                            )
                        )

                        drawCircle(
                            color = focusMarkerColor,
                            radius = 7.dp.toPx(),
                            center =
                                androidx.compose.ui.geometry.Offset(
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

        var advanced by rememberSaveable {
            mutableStateOf(false)
        }

        FilledTonalButton(
            onClick = {
                advanced = !advanced
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (advanced) {
                    "고급 설정 접기"
                } else {
                    "고급 설정 펼치기"
                }
            )
        }

        if (advanced) {
            Text(
                "변환 프리셋",
                fontWeight = FontWeight.SemiBold
            )
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
                        selected =
                            speed == option.first,
                        onClick = {
                            onSpeedChange(
                                option.first
                            )
                        },
                        enabled = enabled,
                        label = {
                            Text(option.second)
                        }
                    )
                }
            }

            SettingSwitch(
                title = "무손실 WebP",
                description =
                    "최대 화질. 파일 크기는 크게 증가할 수 있습니다.",
                checked = lossless,
                enabled = enabled,
                onCheckedChange =
                    onLosslessChange
            )

            SettingSwitch(
                title = "무한 반복",
                description =
                    "Animated WebP를 계속 반복 재생합니다.",
                checked = loopForever,
                enabled = enabled,
                onCheckedChange =
                    onLoopChange
            )
        }
    }
}

@Composable
private fun FramingCard(
    cropAspect: CropAspect,
    focusX: Float,
    focusY: Float,
    cropZoom: Float,
    trackingMode: TrackingMode,
    enabled: Boolean,
    onCropAspectChange: (CropAspect) -> Unit,
    onFocusChange: (Float, Float) -> Unit,
    onCropZoomChange: (Float) -> Unit,
    onResetCrop: () -> Unit,
    onTrackingModeChange: (TrackingMode) -> Unit
) {
    SectionCard(
        title = "화면비 · 크롭 · 인물 추적",
        subtitle = "고정 포커스부터 얼굴·상체·전신 자동 추적까지"
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

        SliderSetting(
            title = "크롭 확대",
            valueText =
                String.format(
                    java.util.Locale.US,
                    "%.2fx",
                    cropZoom
                ),
            value = cropZoom,
            range = 1f..4f,
            steps = 29,
            enabled = enabled,
            onValue = onCropZoomChange
        )

        SliderSetting(
            title = "가로 위치",
            valueText =
                (focusX * 100)
                    .roundToInt()
                    .toString() +
                    "%",
            value = focusX,
            range = 0f..1f,
            steps = 99,
            enabled = enabled,
            onValue = {
                onFocusChange(
                    it,
                    focusY
                )
            }
        )

        SliderSetting(
            title = "세로 위치",
            valueText =
                (focusY * 100)
                    .roundToInt()
                    .toString() +
                    "%",
            value = focusY,
            range = 0f..1f,
            steps = 99,
            enabled = enabled,
            onValue = {
                onFocusChange(
                    focusX,
                    it
                )
            }
        )

        OutlinedButton(
            onClick = onResetCrop,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("크롭 위치·확대 초기화")
        }

        Text(
            "포커스 추적",
            fontWeight = FontWeight.SemiBold
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                listOf(
                    TrackingMode.FIXED to "고정",
                    TrackingMode.FACE to "얼굴",
                    TrackingMode.UPPER_BODY to "상체",
                    TrackingMode.FULL_BODY to "전신"
                )
            ) { option ->
                FilterChip(
                    selected = trackingMode == option.first,
                    onClick = {
                        onTrackingModeChange(option.first)
                    },
                    enabled =
                        enabled &&
                            (
                                option.first == TrackingMode.FIXED ||
                                    cropAspect != CropAspect.ORIGINAL ||
                                    cropZoom > 1.001f
                                ),
                    label = { Text(option.second) }
                )
            }
        }

        when {
            cropAspect == CropAspect.ORIGINAL &&
                cropZoom <= 1.001f -> {
                Text(
                    "현재는 원본 화면 그대로입니다. 화면비를 바꾸거나 크롭 확대를 1.0x보다 높이면 " +
                        "세밀 위치 조절과 자동 추적이 활성화됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            trackingMode == TrackingMode.FACE -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "얼굴 모드: 여러 얼굴이 있으면 플레이어에서 원하는 얼굴 근처를 먼저 탭하세요. " +
                            "첫 얼굴을 선택한 뒤 tracking ID와 위치를 이용해 같은 얼굴을 이어서 추적합니다.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            trackingMode == TrackingMode.UPPER_BODY -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "상체 모드: 머리·어깨·팔·골반 포즈를 분석해 인물 상체의 중심을 따라갑니다. " +
                            "포즈가 잠깐 사라지면 직전 위치를 유지하고 얼굴 위치로 보조합니다.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            trackingMode == TrackingMode.FULL_BODY -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "전신 모드: 머리부터 발목·발까지 포즈 랜드마크를 사용해 전신 중심을 추적합니다. " +
                            "여러 사람이 나오면 포즈 모델이 가장 두드러진 인물을 우선 추적합니다.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            else -> {
                Text(
                    "고정 모드에서는 아래 위치를 영상 전체에 그대로 사용합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (
            cropAspect != CropAspect.ORIGINAL ||
            cropZoom > 1.001f
        ) {
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
                        "기준 포커스 X " +
                            (focusX * 100).roundToInt() +
                            "% · Y " +
                            (focusY * 100).roundToInt() +
                            "%",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (trackingMode == TrackingMode.FIXED) {
                            "영상 화면을 탭하면 실제 크롭 중심이 바뀝니다."
                        } else {
                            "영상 화면 탭 위치는 추적 시작점과 추적 실패 시 fallback 기준으로 사용합니다."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "빠른 기준 위치",
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
                            kotlin.math.abs(
                                focusX - preset.second
                            ) < 0.02f &&
                                kotlin.math.abs(
                                    focusY - preset.third
                                ) < 0.02f,
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
            "추적 모드·화면비·기준 포커스까지 자동 저장됩니다. " +
                "자동 추적은 변환 직전에 기기 내부에서 분석되며 인터넷 업로드를 사용하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun TrackingEditorCard(
    currentPositionMs: Long,
    focusX: Float,
    focusY: Float,
    automaticPoints: List<FocusKeyframe>,
    manualPoints: List<FocusKeyframe>,
    analyzing: Boolean,
    analysisProgress: Float,
    enabled: Boolean,
    onAnalyze: () -> Unit,
    onAddManual: () -> Unit,
    onRemoveManual: (FocusKeyframe) -> Unit,
    onClear: () -> Unit,
    onSeek: (Long) -> Unit
) {
    SectionCard(
        title = "추적 경로 편집",
        subtitle = "자동 경로를 먼저 확인하고 필요한 시점만 수동으로 보정"
    ) {
        if (analyzing) {
            LinearProgressIndicator(
                progress = {
                    analysisProgress
                        .coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "추적 경로 분석 중 · " +
                    (analysisProgress * 100)
                        .roundToInt() +
                    "%",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Button(
                onClick = onAnalyze,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (automaticPoints.isEmpty()) {
                        "추적 경로 미리 분석"
                    } else {
                        "추적 경로 다시 분석"
                    }
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "자동 " +
                    automaticPoints.size +
                    "점 · 수동 " +
                    manualPoints.size +
                    "점",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }

        FilledTonalButton(
            onClick = onAddManual,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "현재 " +
                    formatDetailedTime(
                        currentPositionMs
                    ) +
                    " 위치를 수동 키프레임으로 추가"
            )
        }

        Text(
            "현재 포커스 X " +
                (focusX * 100)
                    .roundToInt() +
                "% · Y " +
                (focusY * 100)
                    .roundToInt() +
                "%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (manualPoints.isNotEmpty()) {
            LazyRow(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                items(
                    manualPoints,
                    key = {
                        it.timeMs
                    }
                ) { point ->
                    Surface(
                        shape =
                            RoundedCornerShape(
                                14.dp
                            ),
                        color =
                            MaterialTheme
                                .colorScheme
                                .secondaryContainer
                    ) {
                        Column(
                            modifier =
                                Modifier.padding(
                                    10.dp
                                ),
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    4.dp
                                )
                        ) {
                            TextButton(
                                onClick = {
                                    onSeek(
                                        point.timeMs
                                    )
                                }
                            ) {
                                Text(
                                    formatDetailedTime(
                                        point.timeMs
                                    )
                                )
                            }
                            TextButton(
                                onClick = {
                                    onRemoveManual(
                                        point
                                    )
                                }
                            ) {
                                Text("삭제")
                            }
                        }
                    }
                }
            }
        }

        if (
            automaticPoints.isNotEmpty() ||
            manualPoints.isNotEmpty()
        ) {
            OutlinedButton(
                onClick = onClear,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("추적 경로/수동 보정 초기화")
            }
        }

        Text(
            "수동 키프레임 주변 약 0.45초는 자동 추적보다 수동 위치를 우선합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun EstimateTargetCard(
    info: VideoInfo,
    durationMs: Long,
    fps: Int,
    quality: Int,
    maxSide: Int?,
    lossless: Boolean,
    cropZoom: Float,
    speed: ConversionSpeed,
    splitMode: SplitMode,
    enabled: Boolean,
    targetEnabled: Boolean,
    targetMb: Int,
    onTargetEnabledChange: (Boolean) -> Unit,
    onTargetMbChange: (Int) -> Unit,
    onApplyRecommendedQuality: (Int) -> Unit
) {
    val estimate = estimateOutput(
        info = info,
        durationMs = durationMs,
        fps = fps,
        quality = quality,
        maxSide = maxSide,
        lossless = lossless,
        cropZoom = cropZoom,
        speed = speed
    )

    val targetBytes =
        targetMb.coerceIn(1, 100) *
            1024L *
            1024L

    val recommendation =
        recommendedQualityForTarget(
            estimateAtCurrentQuality =
                estimate.sizeBytes,
            currentQuality = quality,
            targetBytes = targetBytes
        )

    SectionCard(
        title = "예상치 · 목표 전체 용량",
        subtitle = "변환 전 대략적인 크기/시간을 보고 전체 파일 크기도 자동 조정"
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "예상 크기",
                        style =
                            MaterialTheme
                                .typography
                                .labelMedium
                    )
                    Text(
                        formatBytes(
                            estimate.sizeBytes
                        ),
                        fontWeight =
                            FontWeight.Bold
                    )
                }
                Column(
                    horizontalAlignment =
                        Alignment.End
                ) {
                    Text(
                        "예상 변환 시간",
                        style =
                            MaterialTheme
                                .typography
                                .labelMedium
                    )
                    Text(
                        "~" +
                            String.format(
                                java.util.Locale.US,
                                "%.1f초",
                                estimate.seconds
                            ),
                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }

        Text(
            "예상치는 영상 내용과 기기 성능에 따라 실제 결과와 차이가 날 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingSwitch(
            title = "목표 전체 용량 자동 맞춤",
            description =
                "완성 WebP를 측정해 최대 5회 재인코딩하면서 품질과 필요 시 해상도를 낮춥니다.",
            checked = targetEnabled,
            enabled =
                enabled &&
                    splitMode == SplitMode.NONE &&
                    !lossless,
            onCheckedChange =
                onTargetEnabledChange
        )

        if (targetEnabled) {
            SliderSetting(
                title = "목표 전체 용량",
                valueText =
                    targetMb.toString() +
                        " MB",
                value = targetMb.toFloat(),
                range = 1f..100f,
                steps = 98,
                enabled =
                    enabled &&
                        splitMode ==
                            SplitMode.NONE &&
                        !lossless,
                onValue = {
                    onTargetMbChange(
                        it.roundToInt()
                    )
                }
            )

            Text(
                "현재 설정 기준 추천 품질 Q" +
                    recommendation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedButton(
                onClick = {
                    onApplyRecommendedQuality(
                        recommendation
                    )
                },
                enabled =
                    enabled &&
                        !lossless,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("추천 품질 먼저 적용")
            }
        }

        if (
            splitMode != SplitMode.NONE
        ) {
            Text(
                "전체 목표 용량은 분할 없음에서만 사용합니다. 분할 모드에서는 파일당 목표 용량을 사용하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
private fun BatchQueueCard(
    queue: List<Uri>,
    enabled: Boolean,
    onChoose: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current

    val names = remember(queue) {
        queue.mapIndexed { index, uri ->
            queryDisplayName(
                context,
                uri
            ) ?: "영상 " + (index + 1)
        }
    }

    SectionCard(
        title = "배치 변환 큐",
        subtitle = "여러 영상을 같은 출력/크롭/추적 프리셋으로 순차 처리"
    ) {
        if (queue.isEmpty()) {
            Text(
                "배치 큐가 비어 있습니다. 선택하면 각 영상의 전체 구간을 순서대로 변환합니다.",
                style = MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color =
                    MaterialTheme
                        .colorScheme
                        .primaryContainer
            ) {
                Text(
                    queue.size.toString() +
                        "개 영상 대기 중",
                    modifier =
                        Modifier.padding(12.dp),
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer
                )
            }

            LazyRow(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                items(
                    names
                ) { name ->
                    Surface(
                        shape =
                            RoundedCornerShape(
                                12.dp
                            ),
                        color =
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                    ) {
                        Text(
                            name,
                            modifier =
                                Modifier.padding(
                                    horizontal =
                                        10.dp,
                                    vertical =
                                        7.dp
                                ),
                            maxLines = 1,
                            overflow =
                                TextOverflow
                                    .Ellipsis,
                            style =
                                MaterialTheme
                                    .typography
                                    .labelMedium
                        )
                    }
                }
            }
        }

        Button(
            onClick = onChoose,
            enabled = enabled,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                if (queue.isEmpty()) {
                    "여러 영상 선택"
                } else {
                    "배치 영상 다시 선택"
                }
            )
        }

        if (queue.isNotEmpty()) {
            OutlinedButton(
                onClick = onClear,
                enabled = enabled,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("배치 큐 비우기")
            }
        }

        Text(
            "배치 변환은 각 영상 전체 구간을 사용합니다. 출력/크롭/추적/목표 용량 설정은 모든 영상에 동일하게 적용됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
