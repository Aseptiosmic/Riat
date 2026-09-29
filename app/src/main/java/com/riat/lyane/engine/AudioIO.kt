package com.riat.lyane.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Düşük gecikmeli PCM çalar (AudioTrack, float örnekler) — TTS çıktısını
 * üretim sürerken çalmaya başlar; böylece perceived gecikme en aza iner.
 */
class PcmPlayer(val sampleRate: Int) {

    private val track: AudioTrack
    @Volatile var running = false
        private set

    init {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        track = AudioTrack(attr, fmt, minBuf.coerceAtLeast(sampleRate / 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
    }

    fun start() {
        if (!running) {
            track.play()
            running = true
        }
    }

    fun write(samples: FloatArray): Int {
        if (!running) start()
        return track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    fun pause() {
        runCatching { track.pause() }
        running = false
    }

    fun resume() {
        runCatching { track.play() }
        running = true
    }

    fun stopAndFlush() {
        runCatching { track.pause(); track.flush() }
        running = false
    }

    fun headPositionMs(): Long =
        if (running) track.playbackHeadPosition * 1000L / sampleRate.coerceAtLeast(1) else 0L

    fun release() {
        running = false
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}

/** WAV okuma/yazma: 16-bit PCM ve float PCM destekler. */
object WavIo {

    class WavException(message: String) : Exception(message)

    data class WavData(val samples: FloatArray, val sampleRate: Int)

    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val dataLen = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.writeIntLe(36 + dataLen)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLe(16)
            raf.writeShortLe(1) // PCM
            raf.writeShortLe(1) // mono
            raf.writeIntLe(sampleRate)
            raf.writeIntLe(sampleRate * 2)
            raf.writeShortLe(2)
            raf.writeShortLe(16)
            raf.writeBytes("data")
            raf.writeIntLe(dataLen)
            val buf = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val v = s.coerceIn(-1f, 1f)
                buf.putShort((v * 32767f).toInt().toShort())
            }
            raf.write(buf.array())
        }
    }

    fun read(file: File): WavData {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { din ->
            val header = ByteArray(12)
            din.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE")
                throw WavException("WAV dosyası değil: ${file.name}")
            var fmt = -1
            var channels = 1
            var sampleRate = 16000
            var bits = 16
            var dataLen = -1
            while (true) {
                val id = ByteArray(4)
                if (din.read(id) < 4) break
                val size = readIntLe(din)
                when (String(id)) {
                    "fmt " -> {
                        fmt = readShortLe(din).toInt()
                        channels = readShortLe(din).toInt()
                        sampleRate = readIntLe(din)
                        din.skipBytes(4) // byte rate
                        din.skipBytes(2) // block align
                        bits = readShortLe(din).toInt()
                        if (size > 16) din.skipBytes((size - 16).toInt())
                    }
                    "data" -> {
                        dataLen = size
                        // okumaya devam; veriyi aşağıda tüketiyoruz
                        val bytes = ByteArray(size)
                        din.readFully(bytes)
                        return when {
                            fmt == 1 && bits == 16 -> {
                                val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val n = bytes.size / 2 / channels
                                val out = FloatArray(n)
                                for (i in 0 until n) {
                                    var acc = 0f
                                    for (c in 0 until channels) acc += bb.short.toInt() / 32768f
                                    out[i] = acc / channels
                                }
                                WavData(out, sampleRate)
                            }
                            fmt == 3 && bits == 32 -> {
                                val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val n = bytes.size / 4 / channels
                                val out = FloatArray(n)
                                for (i in 0 until n) {
                                    var acc = 0f
                                    for (c in 0 until channels) acc += bb.float
                                    out[i] = acc / channels
                                }
                                WavData(out, sampleRate)
                            }
                            else -> throw WavException("Desteklenmeyen WAV biçimi: fmt=$fmt bits=$bits")
                        }
                    }
                    else -> din.skipBytes(size)
                }
            }
            throw WavException("data bloğu bulunamadı")
        }
    }

    private fun readIntLe(din: DataInputStream): Int {
        val b = ByteArray(4)
        din.readFully(b)
        return (b[0].toInt() and 0xFF) or (b[1].toInt() and 0xFF shl 8) or
            (b[2].toInt() and 0xFF shl 16) or (b[3].toInt() and 0xFF shl 24)
    }

    private fun readShortLe(din: DataInputStream): Short {
        val b = ByteArray(2)
        din.readFully(b)
        return ((b[0].toInt() and 0xFF) or (b[1].toInt() and 0xFF shl 8)).toShort()
    }

    private fun RandomAccessFile.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }
}

/**
 * Mikrofon kaynağı: 16 kHz mono, engellemesiz okuma.
 */
class MicSource private constructor(private val record: AudioRecord) {

    val sampleRate = 16000
    @Volatile private var stopped = false

    /** Bir tur okur; konuşma yoksa boş dizi dönebilir. */
    fun readChunk(): Pair<FloatArray, Float> {
        val buf = ShortArray(CHUNK)
        val n = record.read(buf, 0, CHUNK, AudioRecord.READ_BLOCKING)
        if (n <= 0) return FloatArray(0) to 0f
        val out = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i] / 32768f
            out[i] = v
            sum += (v * v).toDouble()
        }
        val rms = sqrt(sum / n).toFloat()
        return out to rms
    }

    fun stop() {
        stopped = true
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    companion object {
        const val CHUNK = 1600 // 100 ms @ 16 kHz

        @SuppressLint("MissingPermission")
        fun create(): MicSource? {
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                (minBuf * 4).coerceAtLeast(16000 * 2)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            record.startRecording()
            return MicSource(record)
        }
    }
}

/** Basit RMS ölçer (UI seviye göstergesi için). */
fun FloatArray.rms(): Float {
    if (isEmpty()) return 0f
    var sum = 0.0
    for (v in this) sum += (v * v).toDouble()
    return sqrt(sum / size).toFloat()
}

fun FloatArray.peak(): Float {
    var m = 0f
    for (v in this) if (abs(v) > m) m = abs(v)
    return m
}
