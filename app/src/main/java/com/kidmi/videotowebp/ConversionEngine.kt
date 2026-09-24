package com.kidmi.videotowebp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Packages
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

enum class ConversionSpeed {
    TURBO,
    FAST,
    BALANCED,
    MAX_COMPRESSION
}

enum class SplitMode {
    NONE,
    COUNT,
    SIZE
}

enum class CropAspect(val ratio: Double?) {
    ORIGINAL(null),
    SQUARE(1.0),
    PORTRAIT_4_5(4.0 / 5.0),
    PORTRAIT_9_16(9.0 / 16.0),
    PORTRAIT_3_4(3.0 / 4.0),
    LANDSCAPE_16_9(16.0 / 9.0)
}

data class ConversionSettings(
    val startMs: Long,
    val endMs: Long,
    val fps: Int,
    val quality: Int,
    val maxSide: Int?,
    val lossless: Boolean,
    val loopForever: Boolean,
    val speed: ConversionSpeed = ConversionSpeed.FAST,
    val outputTreeUri: Uri? = null,
    val splitMode: SplitMode = SplitMode.NONE,
    val splitCount: Int = 2,
    val targetPartSizeMb: Int = 8,
    val targetTotalSizeMb: Int? = null,
    val cropAspect: CropAspect = CropAspect.ORIGINAL,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val cropZoom: Float = 1f,
    val focusTrack: List<FocusKeyframe> = emptyList()
)

data class ConversionPartResult(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val index: Int
)

data class ConversionResult(
    val parts: List<ConversionPartResult>,
    val totalSizeBytes: Long,
    val elapsedMs: Long
)

private data class OutputTarget(
    val uri: Uri,
    val pendingMediaStore: Boolean
)

private data class FixedSegment(
    val startMs: Long,
    val durationMs: Long,
    val partNumber: Int
)

class ConversionEngine(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val runGeneration = AtomicInteger(0)

    @Volatile
    private var sessionId: Long? = null

    fun convert(
        sourceUri: Uri,
        settings: ConversionSettings,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (ConversionResult) -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        cancel()
        runGeneration.incrementAndGet()
        cancelled.set(false)

        val libraries = try {
            Packages.getExternalLibraries()
        } catch (_: Throwable) {
            emptyList()
        }

        if (!libraries.contains("libwebp")) {
            post {
                onError("이 APK의 FFmpeg 빌드에 libwebp가 포함되어 있지 않습니다.")
            }
            return
        }

        var cachedInput: File? = null
        val reusableInput =
            settings.splitMode != SplitMode.NONE ||
                settings.targetTotalSizeMb != null

        val input = try {
            if (!reusableInput) {
                FFmpegKitConfig.getSafParameterForRead(context, sourceUri)
            } else {
                post {
                    onStatus("분할 변환을 위해 영상을 준비 중…")
                    onProgress(0.005f)
                }
                copySourceToCache(sourceUri).also {
                    cachedInput = it
                }.absolutePath
            }
        } catch (e: Throwable) {
            cachedInput?.delete()
            post {
                onError(
                    "입력 영상을 준비하지 못했습니다. " +
                        (e.message ?: e.javaClass.simpleName)
                )
            }
            return
        }

        val startMs = settings.startMs.coerceAtLeast(0L)
        val endMs = settings.endMs.coerceAtLeast(startMs + 100L)
        val startedAt = SystemClock.elapsedRealtime()
        val stamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            Locale.US
        ).format(Date())

        if (
            settings.splitMode == SplitMode.NONE &&
            settings.targetTotalSizeMb != null &&
            !settings.lossless
        ) {
            convertSingleToTargetSize(
                input = input,
                settings = settings,
                startMs = startMs,
                endMs = endMs,
                stamp = stamp,
                startedAt = startedAt,
                onStatus = onStatus,
                onProgress = onProgress,
                onComplete = onComplete,
                onError = onError,
                onCancelled = onCancelled,
                cachedInput = cachedInput
            )
            return
        }

        when (settings.splitMode) {
            SplitMode.SIZE -> convertByTargetSize(
                input = input,
                settings = settings,
                startMs = startMs,
                endMs = endMs,
                stamp = stamp,
                startedAt = startedAt,
                onStatus = onStatus,
                onProgress = onProgress,
                onComplete = onComplete,
                onError = onError,
                onCancelled = onCancelled,
                cachedInput = cachedInput
            )

            SplitMode.NONE,
            SplitMode.COUNT -> convertFixedSegments(
                input = input,
                settings = settings,
                startMs = startMs,
                endMs = endMs,
                stamp = stamp,
                startedAt = startedAt,
                onStatus = onStatus,
                onProgress = onProgress,
                onComplete = onComplete,
                onError = onError,
                onCancelled = onCancelled,
                cachedInput = cachedInput
            )
        }
    }

    private fun convertSingleToTargetSize(
        input: String,
        settings: ConversionSettings,
        startMs: Long,
        endMs: Long,
        stamp: String,
        startedAt: Long,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (ConversionResult) -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit,
        cachedInput: File?
    ) {
        val targetBytes =
            settings.targetTotalSizeMb
                ?.coerceIn(1, 100)
                ?.times(1024L * 1024L)
                ?: run {
                    cachedInput?.delete()
                    post { onError("목표 전체 용량이 올바르지 않습니다.") }
                    return
                }

        val durationMs = endMs - startMs
        var attempt = 0
        var currentQuality =
            settings.quality.coerceIn(10, 100)
        var currentMaxSide = settings.maxSide
        var reportedProgress = 0.01f

        fun report(value: Float) {
            if (value > reportedProgress) {
                reportedProgress = value.coerceAtMost(0.97f)
                post { onProgress(reportedProgress) }
            }
        }

        fun finishWithTemp(
            temp: File,
            actualQuality: Int,
            actualMaxSide: Int?
        ) {
            if (cancelled.get()) {
                temp.delete()
                cachedInput?.delete()
                post(onCancelled)
                return
            }

            val fileName =
                buildFileName(
                    stamp = stamp,
                    partNumber = 1,
                    includePartNumber = false
                )

            val target = try {
                createOutputTarget(
                    fileName,
                    settings.outputTreeUri
                )
            } catch (e: Throwable) {
                temp.delete()
                cachedInput?.delete()
                post {
                    onError(
                        e.message
                            ?: "저장 파일을 만들 수 없습니다."
                    )
                }
                return
            }

            try {
                copyFileToUri(temp, target.uri)
                finalizeOutputTarget(target)

                val size = querySize(target.uri)
                temp.delete()
                cachedInput?.delete()

                val part = ConversionPartResult(
                    uri = target.uri,
                    fileName =
                        queryDisplayName(
                            context,
                            target.uri
                        ) ?: fileName,
                    sizeBytes = size,
                    durationMs = durationMs,
                    index = 1
                )

                val note =
                    if (size > targetBytes * 1.08) {
                        " · 목표보다 큼"
                    } else {
                        ""
                    }

                post {
                    onStatus(
                        "목표 용량 맞춤 완료 · Q$actualQuality" +
                            (
                                actualMaxSide?.let { side ->
                                    " · " + side + "p"
                                } ?: ""
                                ) +
                            note
                    )
                }

                complete(
                    listOf(part),
                    startedAt,
                    onProgress,
                    onComplete
                )
            } catch (e: Throwable) {
                temp.delete()
                cachedInput?.delete()
                deleteDestination(target.uri)
                post {
                    onError(
                        "목표 용량 결과 저장 실패. " +
                            (
                                e.message
                                    ?: e.javaClass.simpleName
                                )
                    )
                }
            }
        }

        lateinit var encodeAttempt: () -> Unit

        encodeAttempt = {
            if (cancelled.get()) {
                cachedInput?.delete()
                post(onCancelled)
            } else {
                attempt += 1

                val temp = File(
                    context.cacheDir,
                    "target_total_" +
                        System.nanoTime() +
                        ".webp"
                )
                temp.delete()

                val adjusted =
                    settings.copy(
                        quality = currentQuality,
                        maxSide = currentMaxSide,
                        targetTotalSizeMb = null
                    )

                post {
                    onStatus(
                        "목표 " +
                            settings.targetTotalSizeMb +
                            "MB 맞추는 중 · " +
                            attempt +
                            "/5 · Q" +
                            currentQuality
                    )
                }

                runEncode(
                    input = input,
                    output = temp.absolutePath,
                    segmentStartMs = startMs,
                    segmentDurationMs = durationMs,
                    settings = adjusted,
                    onStatistics = { processedMs ->
                        val local =
                            (
                                processedMs.toDouble() /
                                    durationMs.coerceAtLeast(1L)
                                )
                                .coerceIn(0.0, 0.99)

                        val attemptBase =
                            (attempt - 1) * 0.16

                        report(
                            (
                                0.02 +
                                    attemptBase +
                                    local * 0.15
                                )
                                .coerceAtMost(0.94)
                                .toFloat()
                        )
                    },
                    onSuccess = {
                        val size = temp.length()

                        if (size <= 0L) {
                            temp.delete()
                            cachedInput?.delete()
                            post {
                                onError(
                                    "목표 용량용 WebP 생성에 실패했습니다."
                                )
                            }
                            return@runEncode
                        }

                        val oversized =
                            size > targetBytes * 1.05

                        if (
                            oversized &&
                            attempt < 5
                        ) {
                            val ratio =
                                targetBytes.toDouble() /
                                    size.toDouble()

                            val nextQuality =
                                (
                                    currentQuality *
                                        kotlin.math
                                            .sqrt(ratio) *
                                        0.94
                                    )
                                    .toInt()
                                    .coerceIn(10, 100)

                            if (
                                nextQuality <
                                currentQuality - 1
                            ) {
                                currentQuality =
                                    nextQuality
                            } else {
                                val baseSide =
                                    currentMaxSide
                                        ?: 1080
                                currentMaxSide =
                                    (
                                        baseSide * 0.84
                                        )
                                        .toInt()
                                        .coerceAtLeast(240)
                            }

                            if (
                                currentQuality <= 12 &&
                                size > targetBytes * 1.25
                            ) {
                                val baseSide =
                                    currentMaxSide
                                        ?: 1080
                                currentMaxSide =
                                    (
                                        baseSide * 0.80
                                        )
                                        .toInt()
                                        .coerceAtLeast(240)
                            }

                            temp.delete()
                            encodeAttempt()
                        } else {
                            finishWithTemp(
                                temp,
                                currentQuality,
                                currentMaxSide
                            )
                        }
                    },
                    onFailure = { detail ->
                        temp.delete()
                        cachedInput?.delete()
                        post { onError(detail) }
                    },
                    onCancelled = {
                        temp.delete()
                        cachedInput?.delete()
                        post(onCancelled)
                    }
                )
            }
        }

        post { onProgress(0.01f) }
        encodeAttempt()
    }

    private fun convertFixedSegments(
        input: String,
        settings: ConversionSettings,
        startMs: Long,
        endMs: Long,
        stamp: String,
        startedAt: Long,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (ConversionResult) -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit,
        cachedInput: File?
    ) {
        val totalDurationMs = endMs - startMs
        val segments = if (settings.splitMode == SplitMode.COUNT) {
            val requestedCount = settings.splitCount.coerceIn(2, 20)
            val maxCount = max(1, (totalDurationMs / 100L).toInt())
            val count = min(requestedCount, maxCount)

            List(count) { index ->
                val segmentStart =
                    startMs + totalDurationMs * index / count
                val segmentEnd =
                    startMs + totalDurationMs * (index + 1) / count
                FixedSegment(
                    startMs = segmentStart,
                    durationMs = (segmentEnd - segmentStart).coerceAtLeast(100L),
                    partNumber = index + 1
                )
            }
        } else {
            listOf(
                FixedSegment(
                    startMs = startMs,
                    durationMs = totalDurationMs,
                    partNumber = 1
                )
            )
        }

        val parts = mutableListOf<ConversionPartResult>()

        var inputCleaned = false

        fun cleanupInput() {
            if (!inputCleaned) {
                inputCleaned = true
                cachedInput?.delete()
            }
        }

        fun cleanup() {
            parts.forEach { deleteDestination(it.uri) }
            parts.clear()
        }

        fun fail(message: String, current: Uri? = null) {
            cleanupInput()
            current?.let { deleteDestination(it) }
            cleanup()
            post { onError(message) }
        }

        fun encode(index: Int) {
            if (cancelled.get()) {
                cleanupInput()
                cleanup()
                post(onCancelled)
                return
            }

            if (index >= segments.size) {
                cleanupInput()
                complete(parts, startedAt, onProgress, onComplete)
                return
            }

            val segment = segments[index]
            val split = segments.size > 1
            val fileName = buildFileName(
                stamp = stamp,
                partNumber = segment.partNumber,
                includePartNumber = split
            )
            val target = try {
                createOutputTarget(fileName, settings.outputTreeUri)
            } catch (e: Throwable) {
                fail(e.message ?: "저장 파일을 만들 수 없습니다.")
                return
            }

            val output = try {
                FFmpegKitConfig.getSafParameterForWrite(
                    context,
                    target.uri
                )
            } catch (e: Throwable) {
                fail(
                    "출력 파일 초기화에 실패했습니다. " +
                        (e.message ?: e.javaClass.simpleName),
                    target.uri
                )
                return
            }

            val status = if (split) {
                "파트 ${segment.partNumber}/${segments.size} 변환 중…"
            } else {
                speedLabel(settings.speed) + " · Animated WebP 변환 중…"
            }
            post { onStatus(status) }

            runEncode(
                input = input,
                output = output,
                segmentStartMs = segment.startMs,
                segmentDurationMs = segment.durationMs,
                settings = settings,
                onStatistics = { processedMs ->
                    val completedBefore =
                        segment.startMs - startMs
                    val overall =
                        (completedBefore + processedMs)
                            .coerceIn(0L, totalDurationMs)
                    post {
                        onProgress(
                            (overall.toDouble() / totalDurationMs)
                                .coerceIn(0.0, 0.995)
                                .toFloat()
                        )
                    }
                },
                onSuccess = {
                    try {
                        finalizeOutputTarget(target)
                        parts += ConversionPartResult(
                            uri = target.uri,
                            fileName = queryDisplayName(context, target.uri)
                                ?: fileName,
                            sizeBytes = querySize(target.uri),
                            durationMs = segment.durationMs,
                            index = segment.partNumber
                        )
                        encode(index + 1)
                    } catch (e: Throwable) {
                        fail(
                            e.message ?: "변환 결과 저장에 실패했습니다.",
                            target.uri
                        )
                    }
                },
                onFailure = { detail ->
                    fail(detail, target.uri)
                },
                onCancelled = {
                    deleteDestination(target.uri)
                    cleanupInput()
                    cleanup()
                    post(onCancelled)
                }
            )
        }

        post { onProgress(0.01f) }
        encode(0)
    }

    private fun convertByTargetSize(
        input: String,
        settings: ConversionSettings,
        startMs: Long,
        endMs: Long,
        stamp: String,
        startedAt: Long,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (ConversionResult) -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit,
        cachedInput: File?
    ) {
        val totalDurationMs = endMs - startMs
        val targetBytes =
            settings.targetPartSizeMb.coerceIn(1, 100) * 1024L * 1024L
        val parts = mutableListOf<ConversionPartResult>()
        val minChunkMs = max(
            100L,
            (1000.0 / settings.fps.coerceIn(1, 30))
                .toLong()
                .coerceAtLeast(1L)
        )

        var nextStartMs = startMs
        var partNumber = 1
        var bytesPerMsEstimate: Double? = null
        var reportedProgress = 0.01f
        var inputCleaned = false
        lateinit var encodeNextPart: () -> Unit

        fun cleanupInput() {
            if (!inputCleaned) {
                inputCleaned = true
                cachedInput?.delete()
            }
        }

        fun reportProgress(value: Float) {
            if (value > reportedProgress) {
                reportedProgress = value.coerceAtMost(0.995f)
                post { onProgress(reportedProgress) }
            }
        }

        fun cleanup() {
            parts.forEach { deleteDestination(it.uri) }
            parts.clear()
        }

        fun fail(message: String) {
            cleanupInput()
            cleanup()
            post { onError(message) }
        }

        fun acceptTemp(
            temp: File,
            durationMs: Long,
            sizeBytes: Long
        ) {
            if (cancelled.get()) {
                temp.delete()
                cleanupInput()
                cleanup()
                post(onCancelled)
                return
            }

            val fileName = buildFileName(
                stamp = stamp,
                partNumber = partNumber,
                includePartNumber = true
            )

            val target = try {
                createOutputTarget(fileName, settings.outputTreeUri)
            } catch (e: Throwable) {
                temp.delete()
                fail(e.message ?: "저장 파일을 만들 수 없습니다.")
                return
            }

            try {
                copyFileToUri(temp, target.uri)
                finalizeOutputTarget(target)

                parts += ConversionPartResult(
                    uri = target.uri,
                    fileName = queryDisplayName(context, target.uri)
                        ?: fileName,
                    sizeBytes = sizeBytes,
                    durationMs = durationMs,
                    index = partNumber
                )

                bytesPerMsEstimate =
                    sizeBytes.toDouble() / durationMs.coerceAtLeast(1L)
                nextStartMs =
                    (nextStartMs + durationMs).coerceAtMost(endMs)
                partNumber += 1
                temp.delete()

                reportProgress(
                    ((nextStartMs - startMs).toDouble() / totalDurationMs)
                        .coerceIn(0.0, 0.995)
                        .toFloat()
                )

                encodeNextPart()
            } catch (e: Throwable) {
                temp.delete()
                deleteDestination(target.uri)
                fail(
                    "분할 파일 저장에 실패했습니다. " +
                        (e.message ?: e.javaClass.simpleName)
                )
            }
        }

        fun tryCandidate(
            durationMs: Long,
            attempt: Int
        ) {
            if (cancelled.get()) {
                cleanupInput()
                cleanup()
                post(onCancelled)
                return
            }

            val remaining = endMs - nextStartMs
            val safeDuration =
                durationMs.coerceIn(minChunkMs.coerceAtMost(remaining), remaining)
            val temp = File(
                context.cacheDir,
                "webp_part_${System.nanoTime()}.webp"
            )
            temp.delete()

            post {
                onStatus(
                    "파트 $partNumber · 목표 ${settings.targetPartSizeMb}MB 맞추는 중…"
                )
            }

            runEncode(
                input = input,
                output = temp.absolutePath,
                segmentStartMs = nextStartMs,
                segmentDurationMs = safeDuration,
                settings = settings,
                onStatistics = { processedMs ->
                    val overall =
                        (nextStartMs - startMs + processedMs)
                            .coerceIn(0L, totalDurationMs)
                    reportProgress(
                        (overall.toDouble() / totalDurationMs)
                            .coerceIn(0.0, 0.995)
                            .toFloat()
                    )
                },
                onSuccess = {
                    val size = temp.length()
                    if (size <= 0L) {
                        temp.delete()
                        fail("용량 분할용 WebP 생성에 실패했습니다.")
                        return@runEncode
                    }

                    val highLimit = (targetBytes * 1.05).toLong()
                    val lowLimit = (targetBytes * 0.72).toLong()
                    val remainingAfter =
                        (endMs - nextStartMs).coerceAtLeast(0L)
                    val canGrow =
                        safeDuration < remainingAfter &&
                            size < lowLimit
                    val mustShrink =
                        size > highLimit &&
                            safeDuration > minChunkMs

                    if (attempt < 4 && (mustShrink || canGrow)) {
                        val ratio =
                            targetBytes.toDouble() / size.toDouble()
                        val safety =
                            if (mustShrink) 0.90 else 0.92
                        var nextDuration =
                            (safeDuration * ratio * safety).toLong()

                        if (mustShrink) {
                            nextDuration = min(
                                nextDuration,
                                (safeDuration * 0.92).toLong()
                            )
                        } else {
                            nextDuration = max(
                                nextDuration,
                                (safeDuration * 1.08).toLong()
                            )
                        }

                        nextDuration = nextDuration.coerceIn(
                            minChunkMs.coerceAtMost(remainingAfter),
                            remainingAfter
                        )

                        if (
                            kotlin.math.abs(nextDuration - safeDuration) >=
                            max(50L, safeDuration / 20L)
                        ) {
                            temp.delete()
                            tryCandidate(
                                durationMs = nextDuration,
                                attempt = attempt + 1
                            )
                            return@runEncode
                        }
                    }

                    acceptTemp(
                        temp = temp,
                        durationMs = safeDuration,
                        sizeBytes = size
                    )
                },
                onFailure = { detail ->
                    temp.delete()
                    fail(detail)
                },
                onCancelled = {
                    temp.delete()
                    cleanupInput()
                    cleanup()
                    post(onCancelled)
                }
            )
        }

        encodeNextPart = {
            if (cancelled.get()) {
                cleanup()
                post(onCancelled)
            } else {
                val remaining = endMs - nextStartMs

                when {
                    remaining <= 50L -> {
                        cleanupInput()
                        complete(
                            parts,
                            startedAt,
                            onProgress,
                            onComplete
                        )
                    }

                    partNumber > 100 -> {
                        fail(
                            "분할 파일이 100개를 초과해 작업을 중단했습니다."
                        )
                    }

                    else -> {
                        val estimatedDuration =
                            bytesPerMsEstimate?.let { bytesPerMs ->
                                (
                                    targetBytes.toDouble() * 0.90 /
                                        bytesPerMs.coerceAtLeast(1.0)
                                    ).toLong()
                            } ?: min(
                                remaining,
                                max(
                                    750L,
                                    settings.targetPartSizeMb
                                        .coerceIn(1, 100)
                                        .toLong() * 650L
                                )
                            )

                        tryCandidate(
                            durationMs = estimatedDuration.coerceIn(
                                minChunkMs.coerceAtMost(remaining),
                                remaining
                            ),
                            attempt = 0
                        )
                    }
                }
            }
        }

        post { onProgress(0.01f) }
        encodeNextPart()
    }

    private fun runEncode(
        input: String,
        output: String,
        segmentStartMs: Long,
        segmentDurationMs: Long,
        settings: ConversionSettings,
        onStatistics: (Long) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        val encodeGeneration = runGeneration.get()

        fun isStale(): Boolean {
            return encodeGeneration != runGeneration.get()
        }

        val arguments = buildArguments(
            input = input,
            output = output,
            segmentStartMs = segmentStartMs,
            segmentDurationMs = segmentDurationMs,
            settings = settings
        )

        val session = try {
            FFmpegKit.executeWithArgumentsAsync(
                arguments.toTypedArray(),
                { completed ->
                    if (isStale()) {
                        runCatching {
                            File(output)
                                .takeIf { it.isFile }
                                ?.delete()
                        }
                        return@executeWithArgumentsAsync
                    }

                    sessionId = null

                    when {
                        cancelled.get() ||
                            ReturnCode.isCancel(completed.returnCode) -> {
                            onCancelled()
                        }

                        ReturnCode.isSuccess(completed.returnCode) -> {
                            onSuccess()
                        }

                        else -> {
                            val detail = completed.output
                                ?.lineSequence()
                                ?.toList()
                                ?.takeLast(12)
                                ?.joinToString("\n")
                                ?.take(1600)
                                .orEmpty()
                            onFailure(
                                if (detail.isBlank()) {
                                    "WebP 변환에 실패했습니다."
                                } else {
                                    "WebP 변환에 실패했습니다.\n$detail"
                                }
                            )
                        }
                    }
                },
                { _ -> },
                { statistics ->
                    if (!isStale()) {
                        onStatistics(
                            statistics.time
                                .coerceAtLeast(0.0)
                                .toLong()
                        )
                    }
                }
            )
        } catch (e: Throwable) {
            onFailure(
                "FFmpeg 실행에 실패했습니다. " +
                    (e.message ?: e.javaClass.simpleName)
            )
            return
        }

        if (isStale()) {
            runCatching {
                FFmpegKit.cancel(session.sessionId)
            }
        } else {
            sessionId = session.sessionId
        }
    }

    private fun buildArguments(
        input: String,
        output: String,
        segmentStartMs: Long,
        segmentDurationMs: Long,
        settings: ConversionSettings
    ): List<String> {
        val fps = settings.fps.coerceIn(1, 30)
        val quality = settings.quality.coerceIn(1, 100)
        val lossless = if (settings.lossless) 1 else 0
        val loop = if (settings.loopForever) 0 else 1

        val compression = when (settings.speed) {
            ConversionSpeed.TURBO -> 0
            ConversionSpeed.FAST -> 1
            ConversionSpeed.BALANCED -> 4
            ConversionSpeed.MAX_COMPRESSION -> 6
        }

        val scaleFlags = when (settings.speed) {
            ConversionSpeed.TURBO -> "fast_bilinear"
            ConversionSpeed.FAST -> "bilinear"
            ConversionSpeed.BALANCED -> "bicubic"
            ConversionSpeed.MAX_COMPRESSION -> "lanczos"
        }

        val filters = buildList {
            // Normalize filter timestamps so dynamic face tracking uses t=0
            // at the beginning of every encoded segment.
            add("setpts=PTS-STARTPTS")

            val cropZoom =
                settings.cropZoom.coerceIn(1f, 4f)
            val shouldCrop =
                settings.cropAspect != CropAspect.ORIGINAL ||
                    cropZoom > 1.001f

            if (shouldCrop) {
                val zoomText = String.format(
                    Locale.US,
                    "%.6f",
                    cropZoom
                )

                val focusXExpr = buildFocusExpression(
                    points = settings.focusTrack,
                    segmentStartMs = segmentStartMs,
                    segmentDurationMs = segmentDurationMs,
                    fallback = settings.focusX,
                    axis = { it.x }
                )

                val focusYExpr = buildFocusExpression(
                    points = settings.focusTrack,
                    segmentStartMs = segmentStartMs,
                    segmentDurationMs = segmentDurationMs,
                    fallback = settings.focusY,
                    axis = { it.y }
                )

                val baseWidth: String
                val baseHeight: String

                val targetRatio =
                    settings.cropAspect.ratio

                if (targetRatio == null) {
                    baseWidth = "iw"
                    baseHeight = "ih"
                } else {
                    val ratioText = String.format(
                        Locale.US,
                        "%.8f",
                        targetRatio
                    )
                    baseWidth =
                        "if(gt(iw/ih,$ratioText),trunc(ih*$ratioText/2)*2,iw)"
                    baseHeight =
                        "if(gt(iw/ih,$ratioText),ih,trunc(iw/$ratioText/2)*2)"
                }

                val cropWidth =
                    "max(2,trunc(($baseWidth)/$zoomText/2)*2)"
                val cropHeight =
                    "max(2,trunc(($baseHeight)/$zoomText/2)*2)"
                val cropX =
                    "max(0,min(iw-ow,iw*($focusXExpr)-ow/2))"
                val cropY =
                    "max(0,min(ih-oh,ih*($focusYExpr)-oh/2))"

                add(
                    "crop=w='$cropWidth':h='$cropHeight':x='$cropX':y='$cropY'"
                )
            }

            add("fps=$fps")

            settings.maxSide?.let { side ->
                val safeSide = side.coerceIn(128, 2160)
                add(
                    "scale='if(gt(iw,ih),min(iw,$safeSide),-2)':'if(gt(iw,ih),-2,min(ih,$safeSide))':flags=$scaleFlags"
                )
            }
        }.joinToString(",")

        val base = mutableListOf(
            "-hide_banner",
            "-loglevel", "error",
            "-nostdin",
            "-y",
            "-ss", String.format(
                Locale.US,
                "%.3f",
                segmentStartMs / 1000.0
            ),
            "-i", input,
            "-t", String.format(
                Locale.US,
                "%.3f",
                segmentDurationMs / 1000.0
            ),
            "-an",
            "-vf", filters,
            "-c:v", "libwebp",
            "-lossless", lossless.toString(),
            "-quality", quality.toString(),
            "-compression_level", compression.toString(),
            "-preset", "picture",
            "-loop", loop.toString(),
            "-threads", "0"
        )

        if (!settings.lossless) {
            base += listOf("-pix_fmt", "yuv420p")
        }
        base += output
        return base
    }

    private fun buildFocusExpression(
        points: List<FocusKeyframe>,
        segmentStartMs: Long,
        segmentDurationMs: Long,
        fallback: Float,
        axis: (FocusKeyframe) -> Float
    ): String {
        val fallbackText = String.format(
            Locale.US,
            "%.6f",
            fallback.coerceIn(0f, 1f)
        )

        if (points.size < 2) {
            return fallbackText
        }

        val segmentEndMs = segmentStartMs + segmentDurationMs
        val sorted = points.sortedBy { it.timeMs }

        val selected = buildList {
            sorted.lastOrNull {
                it.timeMs <= segmentStartMs
            }?.let { add(it) }

            sorted.filterTo(this) {
                it.timeMs > segmentStartMs &&
                    it.timeMs < segmentEndMs
            }

            sorted.firstOrNull {
                it.timeMs >= segmentEndMs
            }?.let { add(it) }
        }
            .distinctBy { it.timeMs }
            .ifEmpty { sorted.take(1) }

        if (selected.size == 1) {
            return String.format(
                Locale.US,
                "%.6f",
                axis(selected.first()).coerceIn(0f, 1f)
            )
        }

        var expression = String.format(
            Locale.US,
            "%.6f",
            axis(selected.last()).coerceIn(0f, 1f)
        )

        for (index in selected.size - 2 downTo 0) {
            val a = selected[index]
            val b = selected[index + 1]

            val t0 = (a.timeMs - segmentStartMs) / 1000.0
            val t1 = (b.timeMs - segmentStartMs) / 1000.0

            if (t1 <= t0) continue

            val v0 = axis(a).coerceIn(0f, 1f)
            val v1 = axis(b).coerceIn(0f, 1f)

            val t0Text = String.format(
                Locale.US,
                "%.6f",
                t0
            )
            val t1Text = String.format(
                Locale.US,
                "%.6f",
                t1
            )
            val v0Text = String.format(
                Locale.US,
                "%.6f",
                v0
            )
            val deltaText = String.format(
                Locale.US,
                "%.6f",
                v1 - v0
            )
            val durationText = String.format(
                Locale.US,
                "%.6f",
                t1 - t0
            )

            val interpolated =
                "$v0Text+($deltaText)*(t-($t0Text))/($durationText)"

            expression =
                "if(lt(t,$t1Text),$interpolated,$expression)"
        }

        return expression
    }

    private fun complete(
        parts: List<ConversionPartResult>,
        startedAt: Long,
        onProgress: (Float) -> Unit,
        onComplete: (ConversionResult) -> Unit
    ) {
        if (parts.isEmpty()) {
            return
        }

        val result = ConversionResult(
            parts = parts.toList(),
            totalSizeBytes = parts.sumOf { it.sizeBytes },
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
        )

        post { onProgress(1f) }
        post { onComplete(result) }
    }

    fun cancel() {
        runGeneration.incrementAndGet()
        cancelled.set(true)
        sessionId?.let { id ->
            try {
                FFmpegKit.cancel(id)
            } catch (_: Throwable) {
            }
        }
        sessionId = null
    }

    private fun speedLabel(speed: ConversionSpeed): String {
        return when (speed) {
            ConversionSpeed.TURBO -> "터보 모드"
            ConversionSpeed.FAST -> "빠른 모드"
            ConversionSpeed.BALANCED -> "균형 모드"
            ConversionSpeed.MAX_COMPRESSION -> "최대 압축 모드"
        }
    }

    private fun buildFileName(
        stamp: String,
        partNumber: Int,
        includePartNumber: Boolean
    ): String {
        return if (includePartNumber) {
            "VideoToWebP_${stamp}_%03d.webp".format(
                Locale.US,
                partNumber
            )
        } else {
            "VideoToWebP_${stamp}.webp"
        }
    }

    private fun createOutputTarget(
        fileName: String,
        outputTreeUri: Uri?
    ): OutputTarget {
        if (outputTreeUri != null) {
            val root = requireNotNull(
                DocumentFile.fromTreeUri(context, outputTreeUri)
            ) {
                "선택한 저장 폴더를 열 수 없습니다."
            }

            require(root.canWrite()) {
                "선택한 저장 폴더에 쓰기 권한이 없습니다."
            }

            root.findFile(fileName)?.delete()

            val file = requireNotNull(
                root.createFile("image/webp", fileName)
            ) {
                "선택한 폴더에 WebP 파일을 만들 수 없습니다."
            }

            return OutputTarget(
                uri = file.uri,
                pendingMediaStore = false
            )
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/VideoToWebP"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
        ) {
            "갤러리에 저장 위치를 만들 수 없습니다."
        }

        return OutputTarget(
            uri = uri,
            pendingMediaStore = true
        )
    }

    private fun copySourceToCache(
        sourceUri: Uri
    ): File {
        val displayName = queryDisplayName(context, sourceUri).orEmpty()
        val extension = displayName
            .substringAfterLast('.', "mp4")
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "mp4" }

        val target = File(
            context.cacheDir,
            "split_source_${System.nanoTime()}.$extension"
        )

        context.contentResolver
            .openInputStream(sourceUri)
            .use { input ->
                requireNotNull(input) {
                    "선택한 영상을 열 수 없습니다."
                }

                target.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }

        require(target.length() > 0L) {
            "선택한 영상이 비어 있습니다."
        }

        return target
    }

    private fun copyFileToUri(
        source: File,
        destination: Uri
    ) {
        context.contentResolver.openOutputStream(
            destination,
            "w"
        ).use { output ->
            requireNotNull(output) {
                "저장 파일을 열 수 없습니다."
            }

            source.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun finalizeOutputTarget(
        target: OutputTarget
    ) {
        if (!target.pendingMediaStore) return

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }

        context.contentResolver.update(
            target.uri,
            values,
            null,
            null
        )
    }

    private fun deleteDestination(uri: Uri) {
        try {
            val deleted =
                context.contentResolver.delete(uri, null, null)

            if (deleted <= 0) {
                DocumentFile
                    .fromSingleUri(context, uri)
                    ?.delete()
            }
        } catch (_: Throwable) {
            try {
                DocumentFile
                    .fromSingleUri(context, uri)
                    ?.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        val descriptorLength = try {
            context.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { it.length }
                ?: -1L
        } catch (_: Throwable) {
            -1L
        }

        if (descriptorLength >= 0L) {
            return descriptorLength
        }

        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use 0L
                }

                val index = cursor.getColumnIndex(
                    android.provider.OpenableColumns.SIZE
                )

                if (index >= 0 && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    0L
                }
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
