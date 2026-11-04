package com.omarpriv.smoothflow

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

internal class AvcMp4Encoder(
    inputFile: File,
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int
) {
    private val mime = MediaFormat.MIMETYPE_VIDEO_AVC
    private val extractor = MediaExtractor()
    private val inputAudioTrack: Int
    private val audioFormat: MediaFormat?
    private val muxer: MediaMuxer
    private val codec: MediaCodec
    private val colorFormat: Int
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var frameNumber = 0L
    private var finished = false
    private val info = MediaCodec.BufferInfo()

    init {
        require(width % 2 == 0 && height % 2 == 0) { "أبعاد الفيديو لازم تكون زوجية" }
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        extractor.setDataSource(inputFile.absolutePath)
        var foundAudio = -1
        var foundAudioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (trackMime.startsWith("audio/") && foundAudio < 0) {
                foundAudio = i
                foundAudioFormat = format
                extractor.selectTrack(i)
            }
        }
        inputAudioTrack = foundAudio
        audioFormat = foundAudioFormat

        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { candidate ->
            candidate.isEncoder && candidate.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching {
                    val formats = candidate.getCapabilitiesForType(mime).colorFormats
                    formats.any {
                        it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    }
                }.getOrDefault(false)
        } ?: error("الجهاز ما فيه ترميز H.264 بصيغة YUV420 مناسبة")

        val supported = codecInfo.getCapabilitiesForType(mime).colorFormats.toSet()
        colorFormat = when {
            supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }

        val outputFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps / 5).coerceIn(1_500_000, 10_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        codec = MediaCodec.createByCodecName(codecInfo.name)
        codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.start()
    }

    fun writeFrame(bitmap: Bitmap) {
        check(!finished) { "الترميز انتهى" }
        require(bitmap.width == width && bitmap.height == height)

        val yuv = bitmapToYuv420(bitmap, colorFormat)
        var inputIndex = codec.dequeueInputBuffer(10_000)
        while (inputIndex < 0) {
            drain(endOfStream = false)
            inputIndex = codec.dequeueInputBuffer(10_000)
        }

        val input = codec.getInputBuffer(inputIndex)
            ?: error("تعذّر الوصول إلى مخزن إطارات الترميز")
        input.clear()
        if (yuv.size > input.remaining()) error("مخزن الترميز أصغر من الإطار")
        input.put(yuv)
        val ptsUs = frameNumber * 1_000_000L / fps
        codec.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
        frameNumber++
        drain(endOfStream = false)
    }

    fun finish() {
        if (finished) return
        try {
            var inputIndex = codec.dequeueInputBuffer(10_000)
            while (inputIndex < 0) {
                drain(endOfStream = false)
                inputIndex = codec.dequeueInputBuffer(10_000)
            }
            codec.queueInputBuffer(
                inputIndex, 0, 0, frameNumber * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
            drain(endOfStream = true)
            if (!muxerStarted) error("المُرمّز لم ينتج مسار فيديو")

            if (inputAudioTrack >= 0 && audioTrack >= 0) {
                copyOriginalAudio()
            }
            muxer.stop()
            muxerStarted = false
            finished = true
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
            muxer.release()
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idleLoops = 0
        var sawEos = false
        while (!sawEos) {
            val timeout = if (endOfStream) 10_000L else 0L
            when (val outputIndex = codec.dequeueOutputBuffer(info, timeout)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    idleLoops++
                    if (idleLoops > 100) error("انتهت مهلة ترميز الفيديو")
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("تغيّر تنسيق الفيديو أكثر من مرة")
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    if (audioFormat != null) audioTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val encoded = codec.getOutputBuffer(outputIndex)
                    if (encoded != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        if (!muxerStarted) error("مسار الفيديو غير جاهز")
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, encoded, info)
                    }
                    sawEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun copyOriginalAudio() {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val maxSize = (audioFormat?.getIntegerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 1_048_576)
            .coerceIn(64 * 1024, 8 * 1024 * 1024)
        val buffer = ByteBuffer.allocateDirect(maxSize)
        val sampleInfo = MediaCodec.BufferInfo()

        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            sampleInfo.set(
                0, size, extractor.sampleTime,
                extractor.sampleFlags
            )
            muxer.writeSampleData(audioTrack, buffer, sampleInfo)
            if (!extractor.advance()) break
        }
    }

    private fun bitmapToYuv420(bitmap: Bitmap, format: Int): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val ySize = w * h
        val output = ByteArray(ySize + ySize / 2)

        for (row in 0 until h) {
            for (col in 0 until w) {
                val color = pixels[row * w + col]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                output[row * w + col] = y.coerceIn(0, 255).toByte()
            }
        }

        val planar = format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val uStart = ySize
        val vStart = if (planar) ySize + ySize / 4 else ySize + 1
        var chroma = 0

        for (row in 0 until h step 2) {
            for (col in 0 until w step 2) {
                var uSum = 0
                var vSum = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val color = pixels[(row + dy) * w + col + dx]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        uSum += ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        vSum += ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    }
                }
                val u = (uSum / 4).coerceIn(0, 255).toByte()
                val v = (vSum / 4).coerceIn(0, 255).toByte()
                if (planar) {
                    output[uStart + chroma] = u
                    output[uStart + ySize / 4 + chroma] = v
                } else {
                    output[uStart + chroma * 2] = u
                    output[vStart + chroma * 2] = v
                }
                chroma++
            }
        }
        return output
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null
}
