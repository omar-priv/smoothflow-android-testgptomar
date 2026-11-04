package com.omarpriv.smoothflow

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal class VideoProcessor(private val context: Context) {
    data class Result(val uri: Uri, val outputFps: Int, val width: Int, val height: Int)

    fun process(
        sourceUri: Uri,
        targetFps: Int,
        progress: (Int, String) -> Unit
    ): Result {
        require(targetFps == 50 || targetFps == 60) { "اختار 50 أو 60 FPS" }

        progress(0, "تجهيز الفيديو...")
        val input = File(context.cacheDir, "smoothflow-input-${System.currentTimeMillis()}.mp4")
        copyUriToFile(sourceUri, input)

        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                videoFormat = format
                break
            }
        }
        extractor.release()
        val format = videoFormat ?: error("ما لقيت مسار فيديو بالملف")

        val sourceFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
        } else {
            0f
        }
        if (sourceFps <= 0f || kotlin.math.abs(sourceFps * 2f - targetFps) > 1.2f) {
            error(
                "هذا الإصدار يدعم حالياً 25→50 أو 30→60 FPS. " +
                    "الفيديو عندك ${"%.2f".format(sourceFps)} FPS."
            )
        }

        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        if (durationUs <= 0L) error("تعذّر قراءة مدة الفيديو")
        val frameCount = ceil(durationUs / 1_000_000.0 * sourceFps)
            .toInt().coerceAtLeast(2)

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(input.absolutePath)

        val firstRaw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: error("تعذّر قراءة أول إطار من الفيديو")
        val first = scaleForDevice(firstRaw)
        if (first !== firstRaw) firstRaw.recycle()
        val width = first.width
        val height = first.height
        if (width < 64 || height < 64) {
            first.recycle()
            retriever.release()
            error("دقة الفيديو أصغر من الحد المناسب للمعالجة")
        }

        val modelDir = File(context.filesDir, "models/rife-v2.3")
        try {
            ModelStore.ensure(modelDir) { modelProgress, message ->
                progress((modelProgress * 12 / 100), message)
            }

            progress(13, "تحميل محرك RIFE...")
            val gpuReady = runCatching {
                RifeEngine.nativeInit(modelDir.absolutePath, true)
            }.getOrDefault(false)
            if (!gpuReady) {
                val cpuReady = runCatching {
                    RifeEngine.nativeInit(modelDir.absolutePath, false)
                }.getOrDefault(false)
                if (!cpuReady) error("تعذّر تشغيل RIFE على هذا الجهاز (GPU وCPU)")
            }

            val output = File(
                context.cacheDir,
                "smoothflow-output-${System.currentTimeMillis()}.mp4"
            )
            val encoder = AvcMp4Encoder(input, output, width, height, targetFps)
            var previous = first
            var outputFrame = 0L
            try {
                encoder.writeFrame(previous)
                outputFrame++
                for (index in 1 until frameCount) {
                    val timeUs = (index * 1_000_000.0 / sourceFps).toLong()
                    val raw = retriever.getFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: error("تعذّر قراءة الإطار رقم $index")
                    val current = fitBitmap(raw, width, height)
                    if (current !== raw) raw.recycle()

                    val middle = RifeEngine.interpolate(previous, current)
                    encoder.writeFrame(middle)
                    outputFrame++
                    encoder.writeFrame(current)
                    outputFrame++

                    middle.recycle()
                    previous.recycle()
                    previous = current
                    val percent = 15 + (index * 78 / (frameCount - 1))
                    progress(percent, "استيفاء الإطارات: $index من ${frameCount - 1}")
                }
                encoder.finish()
                previous.recycle()
            } catch (error: Throwable) {
                previous.recycle()
                runCatching { encoder.finish() }
                throw error
            } finally {
                RifeEngine.nativeRelease()
                retriever.release()
            }

            progress(96, "حفظ الفيديو...")
            val saved = publishToGallery(output)
            output.delete()
            input.delete()
            progress(100, "اكتملت المعالجة")
            return Result(saved, targetFps, width, height)
        } catch (error: Throwable) {
            input.delete()
            runCatching { retriever.release() }
            runCatching { RifeEngine.nativeRelease() }
            throw error
        }
    }

    private fun scaleForDevice(bitmap: Bitmap): Bitmap {
        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
        var width = (bitmap.width * scale).roundToInt().coerceAtLeast(2)
        var height = (bitmap.height * scale).roundToInt().coerceAtLeast(2)
        width -= width % 2
        height -= height % 2
        return if (width == bitmap.width && height == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun fitBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        return if (bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذّر فتح الفيديو المختار" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        if (!target.exists() || target.length() < 1024L) {
            target.delete()
            error("ملف الفيديو فارغ أو غير صالح")
        }
    }

    private fun publishToGallery(source: File): Uri {
        val displayName = "SmoothFlow-${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/SmoothFlow"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("تعذّر إنشاء ملف في معرض الفيديو")
            try {
                context.contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "تعذّر فتح ملف الحفظ" }
                    FileInputStream(source).use { input -> input.copyTo(output) }
                }
                val completed = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, completed, null, null)
                return uri
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "SmoothFlow"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("تعذّر إنشاء مجلد Movies/SmoothFlow")
        }
        val destination = File(directory, displayName)
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        var scanned: Uri? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        MediaScannerConnection.scanFile(
            context, arrayOf(destination.absolutePath), arrayOf("video/mp4")
        ) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(destination)
    }
}
