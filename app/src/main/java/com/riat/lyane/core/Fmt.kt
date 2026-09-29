package com.riat.lyane.core

import java.util.Locale

/** Biçimlendirme yardımcıları. */
object Fmt {

    fun bytes(v: Long): String {
        if (v < 0) return "?"
        if (v < 1024) return "$v B"
        val kb = v / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun mb(bytes: Long): Double = bytes / (1024.0 * 1024.0)

    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun seconds(sec: Double): String {
        if (sec < 60.0) return String.format(Locale.US, "%.1f sn", sec)
        return duration((sec * 1000).toLong())
    }

    /** RTF (Real Time Factor) için okunabilir değer: 0.35x → "0.35x (2.9x hızlı)" */
    fun rtf(rtf: Double): String {
        return if (rtf > 0 && rtf < 100) {
            String.format(Locale.US, "%.2fx — %.1f kat gerçek zaman", rtf, 1.0 / rtf)
        } else String.format(Locale.US, "%.2fx", rtf)
    }

    fun speed(bytesPerSec: Double): String =
        if (bytesPerSec > 1024 * 1024) String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1048576.0)
        else String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)

    fun relTime(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        val min = diff / 60000
        return when {
            diff < 60_000 -> "şimdi"
            min < 60 -> "$min dk önce"
            min < 60 * 24 -> "${min / 60} sa önce"
            else -> "${min / (60 * 24)} gün önce"
        }
    }
}
