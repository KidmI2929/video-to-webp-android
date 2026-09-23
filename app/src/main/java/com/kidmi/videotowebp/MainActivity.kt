package com.kidmi.videotowebp

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VideoToWebPApp()
            }
        }
    }
}

private data class ResolutionOption(val label: String, val maxSide: Int?)

@Composable
fun VideoToWebPApp() {
    val context = LocalContext.current
    val engine = remember { ConversionEngine(context.applicationContext) }

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var metadataLoading by remember { mutableStateOf(false) }

    var startSec by remember { mutableFloatStateOf(0f) }
    var endSec by remember { mutableFloatStateOf(0f) }
    var fps by remember { mutableIntStateOf(15) }
    var quality by remember { mutableIntStateOf(80) }
    var maxSide by remember { mutableStateOf<Int?>(720) }
    var lossless by remember { mutableStateOf(false) }
    var loopForever by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(ConversionSpeed.BALANCED) }

    var converting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("영상 파일을 선택하세요.") }
    var result by remember { mutableStateOf<ConversionResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            engine.cancel()
            converting = false
            progress = 0f
            result = null
            videoInfo = null
            videoUri = uri
        }
    }

    LaunchedEffect(videoUri) {
        val uri = videoUri ?: return@LaunchedEffect
        metadataLoading = true
        status = "영상 정보 읽는 중…"
        try {
            val info = withContext(Dispatchers.IO) {
                readVideoInfo(context, uri)
            }
            videoInfo = info
            startSec = 0f
            endSec = (info.durationMs / 1000f).coerceAtLeast(0.1f)
            status = "설정을 조절한 뒤 변환하세요."
        } catch (e: Exception) {
            error = e.message ?: "영상 정보를 읽을 수 없습니다."
            status = "다른 영상을 선택하세요."
        } finally {
            metadataLoading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine.cancel() }
    }

    fun startConversion() {
        val uri = videoUri ?: return
        val info = videoInfo ?: return
        val safeEnd = endSec.coerceAtMost(info.durationMs / 1000f)
        val safeStart = startSec.coerceIn(0f, (safeEnd - 0.1f).coerceAtLeast(0f))

        converting = true
        result = null
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
                speed = speed
            ),
            onStatus = { status = it },
            onProgress = { progress = it.coerceIn(0f, 1f) },
            onComplete = {
                converting = false
                progress = 1f
                result = it
                status = "완료 · Pictures/VideoToWebP에 저장됨"
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

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Video → WebP", style = MaterialTheme.typography.headlineLarge)
            Text(
                "영상의 원하는 구간을 Animated WebP로 바로 변환합니다.",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = !converting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (videoUri == null) "영상 선택" else "다른 영상 선택")
            }

            if (metadataLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            videoUri?.let { uri ->
                key(uri) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(uri)
                                    setMediaController(
                                        MediaController(ctx).also { controller ->
                                            controller.setMediaPlayer(this)
                                        }
                                    )
                                    setOnPreparedListener { player ->
                                        player.isLooping = true
                                        seekTo(1)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                    }
                }
            }

            videoInfo?.let { info ->
                Text(
                    "${info.displayName} · ${info.width}×${info.height} · ${formatDuration(info.durationMs)}",
                    style = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                Text("구간 자르기", style = MaterialTheme.typography.titleMedium)
                val durationSec = (info.durationMs / 1000f).coerceAtLeast(0.1f)
                RangeSlider(
                    value = startSec..endSec.coerceAtMost(durationSec),
                    onValueChange = { range ->
                        val minGap = 0.1f
                        startSec = range.start.coerceIn(0f, (durationSec - minGap).coerceAtLeast(0f))
                        endSec = range.endInclusive
                            .coerceIn((startSec + minGap).coerceAtMost(durationSec), durationSec)
                    },
                    valueRange = 0f..durationSec,
                    enabled = !converting
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("시작 ${formatDuration((startSec * 1000).toLong())}")
                    Text("끝 ${formatDuration((endSec * 1000).toLong())}")
                }

                Text("해상도", style = MaterialTheme.typography.titleMedium)
                val resolutions = listOf(
                    ResolutionOption("원본", null),
                    ResolutionOption("1080", 1080),
                    ResolutionOption("720", 720),
                    ResolutionOption("480", 480),
                    ResolutionOption("320", 320)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(resolutions) { option ->
                        FilterChip(
                            selected = maxSide == option.maxSide,
                            onClick = { maxSide = option.maxSide },
                            enabled = !converting,
                            label = { Text(option.label) }
                        )
                    }
                }

                Text("FPS · $fps", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { fps = it.roundToInt() },
                    valueRange = 5f..30f,
                    steps = 24,
                    enabled = !converting
                )

                Text(
                    if (lossless) "압축 강도 · $quality" else "화질 · $quality",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.roundToInt() },
                    valueRange = 10f..100f,
                    steps = 89,
                    enabled = !converting
                )

                SettingSwitch(
                    title = "무손실 WebP",
                    description = "파일이 훨씬 커질 수 있습니다.",
                    checked = lossless,
                    enabled = !converting,
                    onCheckedChange = { lossless = it }
                )
                SettingSwitch(
                    title = "무한 반복",
                    description = "끄면 애니메이션을 한 번만 재생합니다.",
                    checked = loopForever,
                    enabled = !converting,
                    onCheckedChange = { loopForever = it }
                )

                Text("변환 속도", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            ConversionSpeed.FAST to "빠름",
                            ConversionSpeed.BALANCED to "균형",
                            ConversionSpeed.MAX_COMPRESSION to "최대 압축"
                        )
                    ) { option ->
                        FilterChip(
                            selected = speed == option.first,
                            onClick = { speed = option.first },
                            enabled = !converting,
                            label = { Text(option.second) }
                        )
                    }
                }
                Text(
                    when (speed) {
                        ConversionSpeed.FAST -> "속도를 우선합니다. 파일은 조금 커질 수 있습니다."
                        ConversionSpeed.BALANCED -> "속도와 파일 크기의 균형을 맞춥니다."
                        ConversionSpeed.MAX_COMPRESSION -> "파일 크기를 줄이는 대신 변환이 느려집니다."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                if (endSec - startSec > 30f) {
                    Text(
                        "30초가 넘는 Animated WebP는 파일 크기와 변환 시간이 크게 늘어날 수 있습니다.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (converting) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${(progress * 100).roundToInt()}%")
                    OutlinedButton(
                        onClick = {
                            engine.cancel()
                            status = "취소 요청 중…"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("변환 취소")
                    }
                } else {
                    Button(
                        onClick = { startConversion() },
                        enabled = endSec > startSec && !metadataLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Animated WebP로 변환")
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }

            result?.let { converted ->
                HorizontalDivider()
                Text("변환 결과", style = MaterialTheme.typography.titleLarge)

                AnimatedWebPPreview(converted.uri)

                Text(
                    "${converted.fileName} · ${formatBytes(converted.sizeBytes)} · " +
                        "%.2f초".format(converted.elapsedMs / 1000.0),
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/webp"
                            putExtra(Intent.EXTRA_STREAM, converted.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "WebP 공유"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("공유")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("오류") },
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
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
