package com.kidmi.videotowebp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ConversionSettings(
    val startMs: Long,
    val endMs: Long,
    val fps: Int,
    val quality: Int,
    val maxSide: Int?,
    val lossless: Boolean,
    val loopForever: Boolean
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

        Thread {
            var cleanupInput: File? = null
            var cleanupOutput: File? = null

            try {
                post { onStatus("영상 준비 중…") }
                post { onProgress(0.02f) }

                val inputFile = copySourceToCache(sourceUri)
                cleanupInput = inputFile

                if (cancelled.get()) {
                    inputFile.delete()
                    post(onCancelled)
                    return@Thread
                }

                val outputFile = File(
                    context.cacheDir,
                    "webp_${System.currentTimeMillis()}.webp"
                )
                cleanupOutput = outputFile

                val clipDurationMs = (settings.endMs - settings.startMs).coerceAtLeast(100L)
                val clipDurationSeconds = clipDurationMs / 1000.0
                val startSeconds = settings.startMs / 1000.0

                val filters = buildList {
                    add("fps=${settings.fps.coerceIn(1, 60)}")
                    settings.maxSide?.let { side ->
                        val safe = side.coerceIn(128, 2160)
                        add(
                            "scale='if(gt(iw,ih),min(iw,$safe),-2)':'if(gt(iw,ih),-2,min(ih,$safe))':flags=lanczos"
                        )
                    }
                }.joinToString(",")

                val loop = if (settings.loopForever) 0 else 1
                val pixelFormat = if (settings.lossless) "bgra" else "yuv420p"
                val lossless = if (settings.lossless) 1 else 0

                val command = buildString {
                    append("-y ")
                    append("-ss ${"%.3f".format(Locale.US, startSeconds)} ")
                    append("-t ${"%.3f".format(Locale.US, clipDurationSeconds)} ")
                    append("-i ${quote(inputFile.absolutePath)} ")
                    append("-vf ${quote(filters)} ")
                    append("-an ")
                    append("-c:v libwebp ")
                    append("-lossless $lossless ")
                    append("-quality ${settings.quality.coerceIn(1, 100)} ")
                    append("-compression_level 6 ")
                    append("-preset picture ")
                    append("-pix_fmt $pixelFormat ")
                    append("-loop $loop ")
                    append(quote(outputFile.absolutePath))
                }

                post { onStatus("Animated WebP 변환 중…") }
                post { onProgress(0.05f) }

                val session = FFmpegKit.executeAsync(
                    command,
                    { completed ->
                        sessionId = null

                        try {
                            when {
                                cancelled.get() || ReturnCode.isCancel(completed.returnCode) -> {
                                    post(onCancelled)
                                }

                                ReturnCode.isSuccess(completed.returnCode) -> {
                                    if (!outputFile.exists() || outputFile.length() == 0L) {
                                        post { onError("변환 파일이 생성되지 않았습니다.") }
                                    } else {
                                        post { onStatus("갤러리에 저장 중…") }
                                        val result = saveToGallery(outputFile)
                                        post { onProgress(1f) }
                                        post { onComplete(result) }
                                    }
                                }

                                else -> {
                                    val detail = completed.output
                                        ?.lineSequence()
                                        ?.toList()
                                        ?.takeLast(6)
                                        ?.joinToString("\n")
                                        ?.take(900)
                                        .orEmpty()

                                    post {
                                        onError(
                                            if (detail.isBlank()) {
                                                "WebP 변환에 실패했습니다."
                                            } else {
                                                "WebP 변환에 실패했습니다.\n$detail"
                                            }
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            post { onError(e.message ?: "결과 저장에 실패했습니다.") }
                        } finally {
                            inputFile.delete()
                            outputFile.delete()
                        }
                    },
                    { _ -> },
                    { statistics ->
                        val processedMs = statistics.time.coerceAtLeast(0.0)
                        val fraction = (processedMs / clipDurationMs.toDouble())
                            .coerceIn(0.0, 1.0)
                        post {
                            onProgress((0.05 + fraction * 0.90).toFloat())
                        }
                    }
                )

                sessionId = session.sessionId
            } catch (e: Exception) {
                cleanupInput?.delete()
                cleanupOutput?.delete()

                if (cancelled.get()) {
                    post(onCancelled)
                } else {
                    post { onError(e.message ?: "변환 준비 중 오류가 발생했습니다.") }
                }
            }
        }.start()
    }

    fun cancel() {
        cancelled.set(true)
        sessionId?.let { FFmpegKit.cancel(it) }
        sessionId = null
    }

    private fun copySourceToCache(uri: Uri): File {
        val displayName = queryDisplayName(context, uri).orEmpty()
        val extension = displayName.substringAfterLast('.', "mp4")
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "mp4" }

        val file = File(
            context.cacheDir,
            "source_${System.currentTimeMillis()}.$extension"
        )

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "선택한 영상을 열 수 없습니다." }
            FileOutputStream(file).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }

        return file
    }

    private fun saveToGallery(file: File): ConversionResult {
        val resolver = context.contentResolver
        val fileName = "VideoToWebP_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            ".webp"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/webp")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/VideoToWebP"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ) { "갤러리에 저장 위치를 만들 수 없습니다." }

        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "갤러리 파일을 열 수 없습니다." }
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)

            return ConversionResult(
                uri = uri,
                fileName = fileName,
                sizeBytes = file.length()
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun quote(value: String): String {
        return "\"" +
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") +
            "\""
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
