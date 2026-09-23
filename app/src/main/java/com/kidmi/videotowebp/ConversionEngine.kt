package com.kidmi.videotowebp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Packages
import com.arthenica.ffmpegkit.ReturnCode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class ConversionSpeed {
    FAST,
    BALANCED,
    MAX_COMPRESSION
}

data class ConversionSettings(
    val startMs: Long,
    val endMs: Long,
    val fps: Int,
    val quality: Int,
    val maxSide: Int?,
    val lossless: Boolean,
    val loopForever: Boolean,
    val speed: ConversionSpeed = ConversionSpeed.BALANCED
)

data class ConversionResult(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long
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

        val outputUri = try {
            createPendingDestination()
        } catch (e: Exception) {
            post { onError(e.message ?: "출력 파일을 만들 수 없습니다.") }
            return
        }

        val input = try {
            FFmpegKitConfig.getSafParameterForRead(context, sourceUri)
        } catch (e: Exception) {
            deleteDestination(outputUri)
            post { onError(e.message ?: "선택한 영상을 열 수 없습니다.") }
            return
        }

        val output = try {
            FFmpegKitConfig.getSafParameterForWrite(context, outputUri)
        } catch (e: Exception) {
            deleteDestination(outputUri)
            post { onError(e.message ?: "출력 파일을 열 수 없습니다.") }
            return
        }

        val clipDurationMs = (settings.endMs - settings.startMs).coerceAtLeast(100L)
        val startSeconds = settings.startMs.coerceAtLeast(0L) / 1000.0
        val durationSeconds = clipDurationMs / 1000.0
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
            add("fps=" + fps)
            settings.maxSide?.let { side ->
                val safeSide = side.coerceIn(128, 2160)
                add(
                    "scale='if(gt(iw,ih),min(iw," + safeSide + "),-2)':'if(gt(iw,ih),-2,min(ih," + safeSide + "))':flags=" + scaleFlags
                )
            }
        }.joinToString(",")

        val speedLabel = when (settings.speed) {
            ConversionSpeed.FAST -> "빠른 모드"
            ConversionSpeed.BALANCED -> "균형 모드"
            ConversionSpeed.MAX_COMPRESSION -> "최대 압축 모드"
        }

        val startText = String.format(Locale.US, "%.3f", startSeconds)
        val durationText = String.format(Locale.US, "%.3f", durationSeconds)

        val command = buildString {
            append("-hide_banner -y ")
            append("-ss ").append(startText).append(' ')
            append("-i ").append(quote(input)).append(' ')
            append("-t ").append(durationText).append(' ')
            append("-an ")
            append("-vf ").append(quote(filters)).append(' ')
            append("-c:v libwebp ")
            append("-lossless ").append(lossless).append(' ')
            append("-quality ").append(quality).append(' ')
            append("-compression_level ").append(compressionLevel).append(' ')
            append("-preset picture ")
            append("-loop ").append(loop).append(' ')
            append("-threads 0 ")
            append(quote(output))
        }

        post { onStatus(speedLabel + " · 네이티브 FFmpeg 변환 중…") }
        post { onProgress(0.01f) }

        val session = FFmpegKit.executeAsync(
            command,
            { completed ->
                sessionId = null

                try {
                    when {
                        cancelled.get() || ReturnCode.isCancel(completed.returnCode) -> {
                            deleteDestination(outputUri)
                            post(onCancelled)
                        }

                        ReturnCode.isSuccess(completed.returnCode) -> {
                            finalizePending(outputUri)
                            val fileName = queryDisplayName(context, outputUri)
                                ?: "VideoToWebP.webp"
                            val sizeBytes = querySize(outputUri)

                            post { onProgress(1f) }
                            post {
                                onComplete(
                                    ConversionResult(
                                        uri = outputUri,
                                        fileName = fileName,
                                        sizeBytes = sizeBytes
                                    )
                                )
                            }
                        }

                        else -> {
                            deleteDestination(outputUri)
                            val detail = completed.output
                                ?.lineSequence()
                                ?.toList()
                                ?.takeLast(10)
                                ?.joinToString("\n")
                                ?.take(1400)
                                .orEmpty()

                            post {
                                onError(
                                    if (detail.isBlank()) {
                                        "WebP 변환에 실패했습니다."
                                    } else {
                                        "WebP 변환에 실패했습니다.\n" + detail
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    deleteDestination(outputUri)
                    post { onError(e.message ?: "변환 결과 처리 중 오류가 발생했습니다.") }
                }
            },
            { _ -> },
            { statistics ->
                val processedMs = statistics.time.coerceAtLeast(0.0)
                val fraction = (processedMs / clipDurationMs.toDouble())
                    .coerceIn(0.0, 1.0)
                post { onProgress((0.01 + fraction * 0.98).toFloat()) }
            }
        )

        sessionId = session.sessionId
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

    private fun createPendingDestination(): Uri {
        val resolver = context.contentResolver
        val fileName = "VideoToWebP_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            ".webp"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/VideoToWebP"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        return requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
        ) {
            "갤러리에 저장 위치를 만들 수 없습니다."
        }
    }

    private fun finalizePending(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun deleteDestination(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            context.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { descriptor -> descriptor.length.coerceAtLeast(0L) }
                ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun quote(value: String): String {
        return """ +
            value
                .replace("\\", "\\\\")
                .replace(""", "\\"") +
            """
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
