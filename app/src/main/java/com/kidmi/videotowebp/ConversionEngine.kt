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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

enum class ConversionSpeed {
    FAST,
    BALANCED,
    MAX_COMPRESSION
}

enum class SplitMode {
    NONE,
    COUNT,
    SIZE
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
    val targetPartSizeMb: Int = 8
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

class ConversionEngine(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

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
        cancelled.set(false)

        val libraries = try {
            Packages.getExternalLibraries()
        } catch (_: Throwable) {
            emptyList()
        }

        if (!libraries.contains("libwebp")) {
            post { onError("이 APK의 FFmpeg 빌드에 libwebp가 포함되어 있지 않습니다.") }
            return
        }

        val input = try {
            FFmpegKitConfig.getSafParameterForRead(context, sourceUri)
        } catch (e: Throwable) {
            post {
                onError(
                    "FFmpeg 초기화에 실패했습니다. " +
                        (e.message ?: e.javaClass.simpleName)
                )
            }
            return
        }

        val startMs = settings.startMs.coerceAtLeast(0L)
        val endMs = settings.endMs.coerceAtLeast(startMs + 100L)
        val totalDurationMs = endMs - startMs
        val startedAt = SystemClock.elapsedRealtime()
        val createdParts = mutableListOf<ConversionPartResult>()
        val jobStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val requestedCount = settings.splitCount.coerceIn(2, 20)
        val maxCountByDuration = max(1, (totalDurationMs / 100L).toInt())
        val effectiveCount = min(requestedCount, maxCountByDuration)
        val targetBytes = settings.targetPartSizeMb.coerceIn(1, 100) * 1024L * 1024L

        var nextStartMs = startMs
        var partIndex = 0

        fun cleanupAll() {
            createdParts.forEach { deleteDestination(it.uri) }
            createdParts.clear()
        }

        fun finishSuccess() {
            val result = ConversionResult(
                parts = createdParts.toList(),
                totalSizeBytes = createdParts.sumOf { it.sizeBytes },
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
            )
            post { onProgress(1f) }
            post { onComplete(result) }
        }

        fun fail(message: String) {
            cleanupAll()
            post { onError(message) }
        }

        fun cancelJob(currentUri: Uri? = null) {
            currentUri?.let { deleteDestination(it) }
            cleanupAll()
            post(onCancelled)
        }

        fun nextSegment(): Pair<Long, Long>? {
            return when (settings.splitMode) {
                SplitMode.NONE -> {
                    if (partIndex > 0) null else startMs to totalDurationMs
                }

                SplitMode.COUNT -> {
                    if (partIndex >= effectiveCount) {
                        null
                    } else {
                        val partStart = startMs + (totalDurationMs * partIndex / effectiveCount)
                        val partEnd = startMs + (totalDurationMs * (partIndex + 1) / effectiveCount)
                        partStart to (partEnd - partStart).coerceAtLeast(100L)
                    }
                }

                SplitMode.SIZE -> {
                    if (nextStartMs >= endMs - 50L || partIndex >= 100) {
                        null
                    } else {
                        nextStartMs to (endMs - nextStartMs)
                    }
                }
            }
        }

        fun encodeNext() {
            if (cancelled.get()) {
                cancelJob()
                return
            }

            val segment = nextSegment()
            if (segment == null) {
                if (createdParts.isEmpty()) {
                    fail("변환된 파일이 없습니다.")
                } else {
                    finishSuccess()
                }
                return
            }

            val segmentStartMs = segment.first
            val segmentDurationMs = segment.second
            val displayPartNumber = partIndex + 1
            val splitEnabled = settings.splitMode != SplitMode.NONE
            val fileName = buildFileName(
                stamp = jobStamp,
                partNumber = displayPartNumber,
                includePartNumber = splitEnabled
            )

            val target = try {
                createOutputTarget(fileName, settings.outputTreeUri)
            } catch (e: Throwable) {
                fail(e.message ?: "저장 파일을 만들 수 없습니다.")
                return
            }

            val output = try {
                FFmpegKitConfig.getSafParameterForWrite(context, target.uri)
            } catch (e: Throwable) {
                deleteDestination(target.uri)
                fail(
                    "출력 파일 초기화에 실패했습니다. " +
                        (e.message ?: e.javaClass.simpleName)
                )
                return
            }

            val fps = settings.fps.coerceIn(1, 30)
            val quality = settings.quality.coerceIn(1, 100)
            val loop = if (settings.loopForever) 0 else 1
            val lossless = if (settings.lossless) 1 else 0
            val compressionLevel = when (settings.speed) {
                ConversionSpeed.FAST -> 1
                ConversionSpeed.BALANCED -> 4
                ConversionSpeed.MAX_COMPRESSION -> 6
            }
            val scaleFlags = when (settings.speed) {
                ConversionSpeed.FAST -> "bilinear"
                ConversionSpeed.BALANCED -> "bicubic"
                ConversionSpeed.MAX_COMPRESSION -> "lanczos"
            }

            val filters = buildList {
                add("fps=$fps")
                settings.maxSide?.let { side ->
                    val safeSide = side.coerceIn(128, 2160)
                    add(
                        "scale='if(gt(iw,ih),min(iw,$safeSide),-2)':'if(gt(iw,ih),-2,min(ih,$safeSide))':flags=$scaleFlags"
                    )
                }
            }.joinToString(",")

            val startText = String.format(Locale.US, "%.3f", segmentStartMs / 1000.0)
            val durationText = String.format(Locale.US, "%.3f", segmentDurationMs / 1000.0)

            val arguments = mutableListOf(
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-y",
                "-ss", startText,
                "-i", input,
                "-t", durationText,
                "-an",
                "-vf", filters,
                "-c:v", "libwebp",
                "-lossless", lossless.toString(),
                "-quality", quality.toString(),
                "-compression_level", compressionLevel.toString(),
                "-preset", "picture",
                "-loop", loop.toString(),
                "-threads", "0"
            )

            if (settings.splitMode == SplitMode.SIZE) {
                arguments += listOf("-fs", targetBytes.toString())
            }
            arguments += output

            val statusText = when (settings.splitMode) {
                SplitMode.NONE -> "Animated WebP 변환 중…"
                SplitMode.COUNT -> "파트 $displayPartNumber/$effectiveCount 변환 중…"
                SplitMode.SIZE -> "파트 $displayPartNumber · 목표 ${settings.targetPartSizeMb}MB 변환 중…"
            }
            post { onStatus(statusText) }

            val lastStatsMs = AtomicLong(0L)

            val session = try {
                FFmpegKit.executeWithArgumentsAsync(
                    arguments.toTypedArray(),
                    { completed ->
                        sessionId = null

                        try {
                            when {
                                cancelled.get() || ReturnCode.isCancel(completed.returnCode) -> {
                                    cancelJob(target.uri)
                                }

                                ReturnCode.isSuccess(completed.returnCode) -> {
                                    finalizeOutputTarget(target)
                                    val sizeBytes = querySize(target.uri)

                                    val measuredMs = lastStatsMs.get()
                                    val actualDurationMs = when (settings.splitMode) {
                                        SplitMode.SIZE -> {
                                            if (measuredMs <= 0L) {
                                                if (sizeBytes < targetBytes) segmentDurationMs else 0L
                                            } else {
                                                measuredMs.coerceIn(1L, segmentDurationMs)
                                            }
                                        }

                                        else -> segmentDurationMs
                                    }

                                    if (actualDurationMs < 50L) {
                                        deleteDestination(target.uri)
                                        fail("용량 분할 중 다음 구간 길이를 계산하지 못했습니다.")
                                        return@executeWithArgumentsAsync
                                    }

                                    createdParts += ConversionPartResult(
                                        uri = target.uri,
                                        fileName = queryDisplayName(context, target.uri) ?: fileName,
                                        sizeBytes = sizeBytes,
                                        durationMs = actualDurationMs,
                                        index = displayPartNumber
                                    )

                                    partIndex += 1

                                    if (settings.splitMode == SplitMode.SIZE) {
                                        nextStartMs = (segmentStartMs + actualDurationMs).coerceAtMost(endMs)

                                        val reachedEnd = actualDurationMs >= segmentDurationMs - 50L
                                        if (reachedEnd) {
                                            finishSuccess()
                                        } else {
                                            encodeNext()
                                        }
                                    } else {
                                        val completedMs = when (settings.splitMode) {
                                            SplitMode.NONE -> totalDurationMs
                                            SplitMode.COUNT -> totalDurationMs * partIndex / effectiveCount
                                            SplitMode.SIZE -> 0L
                                        }
                                        post {
                                            onProgress(
                                                (completedMs.toDouble() / totalDurationMs)
                                                    .coerceIn(0.0, 1.0)
                                                    .toFloat()
                                            )
                                        }
                                        encodeNext()
                                    }
                                }

                                else -> {
                                    deleteDestination(target.uri)
                                    val detail = completed.output
                                        ?.lineSequence()
                                        ?.toList()
                                        ?.takeLast(10)
                                        ?.joinToString("\n")
                                        ?.take(1400)
                                        .orEmpty()
                                    fail(
                                        if (detail.isBlank()) {
                                            "WebP 변환에 실패했습니다."
                                        } else {
                                            "WebP 변환에 실패했습니다.\n$detail"
                                        }
                                    )
                                }
                            }
                        } catch (e: Throwable) {
                            deleteDestination(target.uri)
                            fail(
                                "변환 결과 처리 중 오류가 발생했습니다. " +
                                    (e.message ?: e.javaClass.simpleName)
                            )
                        }
                    },
                    { _ -> },
                    { statistics ->
                        val processedMs = statistics.time.coerceAtLeast(0.0).toLong()
                        lastStatsMs.set(processedMs)

                        val overallMs = (segmentStartMs - startMs + processedMs)
                            .coerceIn(0L, totalDurationMs)
                        post {
                            onProgress(
                                (overallMs.toDouble() / totalDurationMs)
                                    .coerceIn(0.0, 0.995)
                                    .toFloat()
                            )
                        }
                    }
                )
            } catch (e: Throwable) {
                deleteDestination(target.uri)
                fail(
                    "FFmpeg 실행에 실패했습니다. " +
                        (e.message ?: e.javaClass.simpleName)
                )
                return
            }

            sessionId = session.sessionId
        }

        post { onProgress(0.01f) }
        encodeNext()
    }

    fun cancel() {
        cancelled.set(true)
        sessionId?.let { id ->
            try {
                FFmpegKit.cancel(id)
            } catch (_: Throwable) {
            }
        }
        sessionId = null
    }

    private fun buildFileName(
        stamp: String,
        partNumber: Int,
        includePartNumber: Boolean
    ): String {
        return if (includePartNumber) {
            "VideoToWebP_${stamp}_%03d.webp".format(Locale.US, partNumber)
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
            return OutputTarget(file.uri, pendingMediaStore = false)
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

        return OutputTarget(uri, pendingMediaStore = true)
    }

    private fun finalizeOutputTarget(target: OutputTarget) {
        if (!target.pendingMediaStore) return

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(target.uri, values, null, null)
    }

    private fun deleteDestination(uri: Uri) {
        try {
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted <= 0) {
                DocumentFile.fromSingleUri(context, uri)?.delete()
            }
        } catch (_: Throwable) {
            try {
                DocumentFile.fromSingleUri(context, uri)?.delete()
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

        if (descriptorLength >= 0L) return descriptorLength

        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
