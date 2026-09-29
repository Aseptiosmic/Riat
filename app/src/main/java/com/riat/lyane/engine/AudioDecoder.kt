package com.riat.lyane.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.riat.lyane.core.LyLog

/**
 * Herhangi bir ses/video dosyasını (mp3, m4a, ogg, wav, mp4, 3gp, aac…)
 * float PCM mono'ya çözer. Kod çözme cihazda, tamamen yerel yapılır.
 */
object AudioDecoder {

    class DecodeException(message: String) : Exception(message)

    data class Decoded(val samples: FloatArray, val sampleRate: Int) {
        val durationSec: Double get() = samples.size.toDouble() / sampleRate.coerceAtLeast(1)
    }

    fun decode(context: Context, uri: Uri): Decoded {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw DecodeException("Dosya açılamadı")
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            // fallback: yol ile dene
            runCatching { extractor.setDataSource(context, uri, null) }
                .onFailure { throw DecodeException("Dosya okunamadı: ${e.message}") }
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) throw DecodeException("Dosyada ses izi yok")
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw DecodeException("Bu ses biçimi bu cihazda çözülemiyor ($mime)")
        }

        try {
            return decodeLoop(extractor, codec)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec): Decoded {
        codec.configure(null, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val out = ArrayList<Float>(1 shl 18)
        var outputSampleRate = -1
        var outputChannels = 1
        var sawInputEOS = false
        var sawOutputEOS = false
        var guard = 0

        while (!sawOutputEOS && guard++ < 5_000_000) {
            if (!sawInputEOS) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(ib, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    outputSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIdx >= 0 -> {
                    if (outputSampleRate < 0) {
                        // bazı kodlayıcılar format değişimi göndermez
                        val f = codec.outputFormat
                        runCatching { outputSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                        runCatching { outputChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                        if (outputSampleRate < 0) outputSampleRate = 16000
                    }
                    val ob = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val bb = ob.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.position(info.offset)
                        bb.limit(info.offset + info.size)
                        when {
                            bb.remaining() % 4 == 0 && isFloatFormat(codec) -> {
                                val frames = bb.remaining() / 4 / outputChannels
                                for (fr in 0 until frames) {
                                    var acc = 0f
                                    for (c in 0 until outputChannels) acc += bb.float
                                    out += acc / outputChannels
                                }
                            }
                            else -> { // 16-bit PCM (yaygın durum)
                                val frames = bb.remaining() / 2 / outputChannels
                                for (fr in 0 until frames) {
                                    var acc = 0f
                                    for (c in 0 until outputChannels) acc += bb.short.toInt() / 32768f
                                    out += acc / outputChannels
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                }
            }
        }
        if (outputSampleRate < 0) outputSampleRate = 16000
        LyLog.d(TAG, "Kod çözme bitti: ${out.size} örnek @ $outputSampleRate Hz")
        return Decoded(out.toFloatArray(), outputSampleRate)
    }

    private fun isFloatFormat(codec: MediaCodec): Boolean {
        val name = codec.name.lowercase()
        return name.contains("float") && !name.contains("pcm16")
    }

    private const val TAG = "AudioDecoder"
}
