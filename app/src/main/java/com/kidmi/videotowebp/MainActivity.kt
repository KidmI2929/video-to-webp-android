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
import androidx.compose.foundation.clickable
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
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
            ) {
                VideoToWebPApp()
            }
        }
    }
}

private data class ResolutionOption(
    val label: String,
    val maxSide: Int?
)

@Composable
fun VideoToWebPApp() {
    val context = LocalContext.current
    val engine = remember { ConversionEngine(context.applicationContext) }
    val prefs = remember {
        context.getSharedPreferences("video_to_webp", ComponentActivity.MODE_PRIVATE)
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

    var fps by remember { mutableIntStateOf(15) }
    var quality by remember { mutableIntStateOf(80) }
    var maxSide by remember { mutableStateOf<Int?>(720) }
    var lossless by remember { mutableStateOf(false) }
    var loopForever by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(ConversionSpeed.FAST) }

    var splitMode by remember { mutableStateOf(SplitMode.NONE) }
    var splitCount by remember { mutableIntStateOf(2) }
    var targetPartSizeMb by remember { mutableIntStateOf(8) }

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
                targetPartSizeMb = targetPartSizeMb
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
            Header()

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
                    onTrimChange = { newStart, newEnd, seekSec ->
                        startSec = newStart
                        endSec = newEnd
                        exoPlayer.pause()
                        seekPlayer((seekSec * 1000f).toLong())
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
                    onSpeedChange = { speed = it }
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
                    onShare = {
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
private fun Header() {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
            "영상 구간을 확인하면서 빠르게 Animated WebP로 변환",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    converting: Boolean,
    onPickAnother: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onTrimChange: (Float, Float, Float) -> Unit
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
                        info.width.toString() + "×" + info.height +
                            " · " + formatDuration(info.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LinearProgressIndicator(
                    progress = { selectedProgress },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatDuration(currentPositionMs),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { onSeekBy(-1000L) },
                            enabled = !converting
                        ) {
                            Text("−1s")
                        }
                        Button(
                            onClick = onTogglePlay,
                            enabled = !converting
                        ) {
                            Text(if (isPlaying) "일시정지" else "재생")
                        }
                        FilledTonalButton(
                            onClick = { onSeekBy(1000L) },
                            enabled = !converting
                        ) {
                            Text("+1s")
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
            label + "  " + formatDuration(timeMs),
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
    onSpeedChange: (ConversionSpeed) -> Unit
) {
    SectionCard(
        title = "출력 설정",
        subtitle = "화질과 속도를 원하는 용도에 맞게 조절"
    ) {
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
    onShare: () -> Unit
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

        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (result.parts.size > 1) {
                    "전체 파일 공유"
                } else {
                    "WebP 공유"
                }
            )
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

private fun shareResult(
    context: android.content.Context,
    result: ConversionResult
) {
    if (result.parts.size <= 1) {
        val uri = result.parts.firstOrNull()?.uri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "WebP 공유")
        )
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
