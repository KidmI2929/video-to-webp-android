package com.kidmi.videotowebp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class TrackingMode {
    FIXED,
    FACE,
    UPPER_BODY,
    FULL_BODY
}

data class FocusKeyframe(
    val timeMs: Long,
    val x: Float,
    val y: Float
)

class FaceTrackingAnalyzer(
    private val context: Context
) {
    private val runGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancel() {
        runGeneration.incrementAndGet()
    }

    fun analyze(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        initialFocusX: Float,
        initialFocusY: Float,
        mode: TrackingMode = TrackingMode.FACE,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (List<FocusKeyframe>) -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        val runId = runGeneration.incrementAndGet()

        fun isCancelled(): Boolean {
            return runGeneration.get() != runId
        }

        fun postCurrent(block: () -> Unit) {
            mainHandler.postCurrent {
                if (!isCancelled()) {
                    block()
                }
            }
        }

        if (mode == TrackingMode.FIXED) {
            postCurrent {
                onComplete(emptyList())
            }
            return
        }

        Thread {
            val faceDetector = createFaceDetector()
            val poseDetector =
                if (mode == TrackingMode.UPPER_BODY ||
                    mode == TrackingMode.FULL_BODY
                ) {
                    createPoseDetector()
                } else {
                    null
                }

            val retriever = MediaMetadataRetriever()

            try {
                postCurrent {
                    onStatus(
                        when (mode) {
                            TrackingMode.FACE -> "얼굴 추적 준비 중…"
                            TrackingMode.UPPER_BODY -> "상체 추적 준비 중…"
                            TrackingMode.FULL_BODY -> "전신 추적 준비 중…"
                            TrackingMode.FIXED -> "포커스 준비 중…"
                        }
                    )
                }
                postCurrent { onProgress(0.01f) }

                retriever.setDataSource(context, sourceUri)

                val rotation = retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                    )
                    ?.toIntOrNull()
                    ?.let { ((it % 360) + 360) % 360 }
                    ?: 0

                val rawWidth = retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )
                    ?.toIntOrNull()
                    ?.coerceAtLeast(1)
                    ?: 640

                val rawHeight = retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )
                    ?.toIntOrNull()
                    ?.coerceAtLeast(1)
                    ?: 360

                val clipStart = startMs.coerceAtLeast(0L)
                val clipEnd = endMs.coerceAtLeast(clipStart + 100L)
                val duration = clipEnd - clipStart

                val sampleInterval = when (mode) {
                    TrackingMode.FACE -> max(
                        250L,
                        ceil(duration / 72.0).toLong()
                    )
                    TrackingMode.UPPER_BODY,
                    TrackingMode.FULL_BODY -> max(
                        300L,
                        ceil(duration / 60.0).toLong()
                    )
                    TrackingMode.FIXED -> duration
                }

                val sampleTimes = buildList {
                    var t = clipStart
                    while (t < clipEnd) {
                        add(t)
                        t += sampleInterval
                    }
                    if (isEmpty() || last() < clipEnd - 40L) {
                        add(clipEnd - 1L)
                    }
                }

                val maxAnalysisSide =
                    if (mode == TrackingMode.FACE) 640.0 else 720.0
                val scale = min(
                    1.0,
                    maxAnalysisSide /
                        max(rawWidth, rawHeight).toDouble()
                )
                val decodeWidth = max(
                    2,
                    (rawWidth * scale).toInt()
                )
                val decodeHeight = max(
                    2,
                    (rawHeight * scale).toInt()
                )

                var preferredTrackingId: Int? = null
                var previousX = initialFocusX.coerceIn(0f, 1f)
                var previousY = initialFocusY.coerceIn(0f, 1f)
                var smoothedX = previousX
                var smoothedY = previousY
                var hasPoint = false
                var consecutiveMisses = 0

                val points = mutableListOf<FocusKeyframe>()

                sampleTimes.forEachIndexed { index, timeMs ->
                    if (isCancelled()) {
                        postCurrent(onCancelled)
                        return@Thread
                    }

                    postCurrent {
                        onStatus(
                            trackingLabel(mode) +
                                " 분석 중… " +
                                (index + 1) +
                                "/" +
                                sampleTimes.size
                        )
                    }

                    var decoded: Bitmap? = null
                    var oriented: Bitmap? = null

                    try {
                        decoded = try {
                            retriever.getScaledFrameAtTime(
                                timeMs * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                decodeWidth,
                                decodeHeight
                            )
                        } catch (_: Throwable) {
                            retriever.getFrameAtTime(
                                timeMs * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST
                            )
                        }

                        val source = decoded
                        if (source == null) {
                            holdPreviousPoint(
                                points,
                                hasPoint,
                                timeMs,
                                smoothedX,
                                smoothedY
                            )
                            consecutiveMisses += 1
                            return@forEachIndexed
                        }

                        oriented = rotateBitmap(source, rotation)
                        val image = InputImage.fromBitmap(oriented, 0)

                        val rawPoint = when (mode) {
                            TrackingMode.FACE -> {
                                val faces = Tasks.await(
                                    faceDetector.process(image)
                                )
                                val selected = selectFace(
                                    faces = faces,
                                    width = oriented.width,
                                    height = oriented.height,
                                    preferredTrackingId = preferredTrackingId,
                                    targetX = previousX,
                                    targetY = previousY
                                )
                                selected?.trackingId?.let {
                                    preferredTrackingId = it
                                }
                                selected?.let {
                                    faceCenter(
                                        it,
                                        oriented.width,
                                        oriented.height
                                    )
                                }
                            }

                            TrackingMode.UPPER_BODY,
                            TrackingMode.FULL_BODY -> {
                                val pose = poseDetector?.let {
                                    Tasks.await(it.process(image))
                                }

                                pose?.let {
                                    poseCenter(
                                        pose = it,
                                        mode = mode,
                                        width = oriented.width,
                                        height = oriented.height
                                    )
                                } ?: run {
                                    val faces = Tasks.await(
                                        faceDetector.process(image)
                                    )
                                    val selected = selectFace(
                                        faces = faces,
                                        width = oriented.width,
                                        height = oriented.height,
                                        preferredTrackingId = preferredTrackingId,
                                        targetX = previousX,
                                        targetY = previousY
                                    )
                                    selected?.trackingId?.let {
                                        preferredTrackingId = it
                                    }
                                    selected?.let {
                                        faceCenter(
                                            it,
                                            oriented.width,
                                            oriented.height
                                        )
                                    }
                                }
                            }

                            TrackingMode.FIXED -> null
                        }

                        if (rawPoint != null) {
                            val rawX = rawPoint.first
                            val rawY = rawPoint.second

                            if (!hasPoint) {
                                smoothedX = rawX
                                smoothedY = rawY
                                hasPoint = true
                            } else {
                                val dx = rawX - smoothedX
                                val dy = rawY - smoothedY
                                val distance = hypot(
                                    dx.toDouble(),
                                    dy.toDouble()
                                ).toFloat()

                                val deadZone = 0.006f
                                val alpha = when {
                                    distance < deadZone -> 0f
                                    distance > 0.18f -> 0.62f
                                    mode == TrackingMode.FACE -> 0.44f
                                    else -> 0.36f
                                }

                                val cappedDx =
                                    dx.coerceIn(-0.18f, 0.18f)
                                val cappedDy =
                                    dy.coerceIn(-0.18f, 0.18f)

                                smoothedX += cappedDx * alpha
                                smoothedY += cappedDy * alpha
                            }

                            smoothedX = smoothedX.coerceIn(0f, 1f)
                            smoothedY = smoothedY.coerceIn(0f, 1f)
                            previousX = smoothedX
                            previousY = smoothedY
                            consecutiveMisses = 0

                            points += FocusKeyframe(
                                timeMs = timeMs,
                                x = smoothedX,
                                y = smoothedY
                            )
                        } else {
                            consecutiveMisses += 1
                            holdPreviousPoint(
                                points = points,
                                hasPoint = hasPoint,
                                timeMs = timeMs,
                                x = smoothedX,
                                y = smoothedY
                            )
                        }
                    } finally {
                        if (
                            oriented != null &&
                            oriented !== decoded &&
                            !oriented.isRecycled
                        ) {
                            oriented.recycle()
                        }
                        if (
                            decoded != null &&
                            !decoded.isRecycled
                        ) {
                            decoded.recycle()
                        }
                    }

                    postCurrent {
                        onProgress(
                            ((index + 1f) / sampleTimes.size)
                                .coerceIn(0f, 1f)
                        )
                    }
                }

                if (isCancelled()) {
                    postCurrent(onCancelled)
                    return@Thread
                }

                val completed = ensureBoundaryPoints(
                    points = points,
                    startMs = clipStart,
                    endMs = clipEnd
                )

                postCurrent {
                    onComplete(completed)
                }
            } catch (e: Throwable) {
                if (isCancelled()) {
                    postCurrent(onCancelled)
                } else {
                    postCurrent {
                        onError(
                            trackingLabel(mode) +
                                " 분석에 실패했습니다. " +
                                (e.message ?: e.javaClass.simpleName)
                        )
                    }
                }
            } finally {
                runCatching { retriever.release() }
                runCatching { faceDetector.close() }
                runCatching { poseDetector?.close() }
            }
        }.start()
    }

    private fun createFaceDetector(): FaceDetector {
        return FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(
                    FaceDetectorOptions.PERFORMANCE_MODE_FAST
                )
                .setLandmarkMode(
                    FaceDetectorOptions.LANDMARK_MODE_NONE
                )
                .setContourMode(
                    FaceDetectorOptions.CONTOUR_MODE_NONE
                )
                .setClassificationMode(
                    FaceDetectorOptions.CLASSIFICATION_MODE_NONE
                )
                .setMinFaceSize(0.07f)
                .enableTracking()
                .build()
        )
    }

    private fun createPoseDetector(): PoseDetector {
        return PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(
                    PoseDetectorOptions.STREAM_MODE
                )
                .build()
        )
    }

    private fun trackingLabel(mode: TrackingMode): String {
        return when (mode) {
            TrackingMode.FIXED -> "고정 포커스"
            TrackingMode.FACE -> "얼굴 추적"
            TrackingMode.UPPER_BODY -> "상체 추적"
            TrackingMode.FULL_BODY -> "전신 추적"
        }
    }

    private fun faceCenter(
        face: Face,
        width: Int,
        height: Int
    ): Pair<Float, Float> {
        val box = face.boundingBox
        return Pair(
            (
                box.exactCenterX() /
                    width.coerceAtLeast(1).toFloat()
                ).coerceIn(0f, 1f),
            (
                box.exactCenterY() /
                    height.coerceAtLeast(1).toFloat()
                ).coerceIn(0f, 1f)
        )
    }

    private fun poseCenter(
        pose: Pose,
        mode: TrackingMode,
        width: Int,
        height: Int
    ): Pair<Float, Float>? {
        val landmarkTypes = when (mode) {
            TrackingMode.UPPER_BODY -> listOf(
                PoseLandmark.NOSE,
                PoseLandmark.LEFT_EAR,
                PoseLandmark.RIGHT_EAR,
                PoseLandmark.LEFT_SHOULDER,
                PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW,
                PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_HIP
            )
            TrackingMode.FULL_BODY -> listOf(
                PoseLandmark.NOSE,
                PoseLandmark.LEFT_EAR,
                PoseLandmark.RIGHT_EAR,
                PoseLandmark.LEFT_SHOULDER,
                PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW,
                PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_KNEE,
                PoseLandmark.RIGHT_KNEE,
                PoseLandmark.LEFT_ANKLE,
                PoseLandmark.RIGHT_ANKLE,
                PoseLandmark.LEFT_HEEL,
                PoseLandmark.RIGHT_HEEL,
                PoseLandmark.LEFT_FOOT_INDEX,
                PoseLandmark.RIGHT_FOOT_INDEX
            )
            else -> emptyList()
        }

        val landmarks = landmarkTypes
            .mapNotNull { pose.getPoseLandmark(it) }
            .filter {
                it.inFrameLikelihood >= 0.35f
            }

        if (landmarks.size < 3) {
            return null
        }

        val frameWidth = width.coerceAtLeast(1).toFloat()
        val frameHeight = height.coerceAtLeast(1).toFloat()

        val xs = landmarks.map {
            (it.position.x / frameWidth).coerceIn(0f, 1f)
        }
        val ys = landmarks.map {
            (it.position.y / frameHeight).coerceIn(0f, 1f)
        }

        val minX = xs.minOrNull() ?: return null
        val maxX = xs.maxOrNull() ?: return null
        val minY = ys.minOrNull() ?: return null
        val maxY = ys.maxOrNull() ?: return null

        val centerX = ((minX + maxX) * 0.5f)
            .coerceIn(0f, 1f)

        val centerY = when (mode) {
            TrackingMode.UPPER_BODY -> {
                (
                    minY +
                        (maxY - minY) * 0.56f
                    ).coerceIn(0f, 1f)
            }
            TrackingMode.FULL_BODY -> {
                (
                    minY +
                        (maxY - minY) * 0.50f
                    ).coerceIn(0f, 1f)
            }
            else -> ((minY + maxY) * 0.5f)
                .coerceIn(0f, 1f)
        }

        return Pair(centerX, centerY)
    }

    private fun holdPreviousPoint(
        points: MutableList<FocusKeyframe>,
        hasPoint: Boolean,
        timeMs: Long,
        x: Float,
        y: Float
    ) {
        if (!hasPoint) return
        points += FocusKeyframe(
            timeMs = timeMs,
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f)
        )
    }

    private fun selectFace(
        faces: List<Face>,
        width: Int,
        height: Int,
        preferredTrackingId: Int?,
        targetX: Float,
        targetY: Float
    ): Face? {
        if (faces.isEmpty()) return null

        preferredTrackingId?.let { id ->
            faces.firstOrNull {
                it.trackingId == id
            }?.let {
                return it
            }
        }

        return faces.minByOrNull { face ->
            val box = face.boundingBox
            val cx =
                box.exactCenterX() /
                    width.coerceAtLeast(1).toFloat()
            val cy =
                box.exactCenterY() /
                    height.coerceAtLeast(1).toFloat()

            val dx = cx - targetX
            val dy = cy - targetY
            val distance =
                dx * dx +
                    dy * dy

            val area =
                (
                    box.width().coerceAtLeast(1) *
                        box.height().coerceAtLeast(1)
                    ).toFloat() /
                    (
                        width.coerceAtLeast(1) *
                            height.coerceAtLeast(1)
                        ).toFloat()

            distance - area * 0.18f
        }
    }

    private fun ensureBoundaryPoints(
        points: List<FocusKeyframe>,
        startMs: Long,
        endMs: Long
    ): List<FocusKeyframe> {
        if (points.isEmpty()) {
            return emptyList()
        }

        val sorted = points
            .sortedBy { it.timeMs }
            .distinctBy { it.timeMs }
            .toMutableList()

        if (sorted.first().timeMs > startMs) {
            sorted.add(
                0,
                FocusKeyframe(
                    startMs,
                    sorted.first().x,
                    sorted.first().y
                )
            )
        }

        if (sorted.last().timeMs < endMs) {
            sorted += FocusKeyframe(
                endMs,
                sorted.last().x,
                sorted.last().y
            )
        }

        return sorted
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun rotateBitmap(
        source: Bitmap,
        rotation: Int
    ): Bitmap {
        if (rotation % 360 == 0) {
            return source
        }

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }
}
