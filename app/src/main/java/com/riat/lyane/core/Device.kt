package com.riat.lyane.core

import android.os.Build
import android.os.Build.VERSION
import android.system.Os
import android.system.OsConstants

/**
 * Cihaz donanım profili: motor iplik sayısı ve performans sınıfı için
 * bellek/çekirdek bilgisi toplar. Varsayılan ayarlar buna göre belirlenir.
 */
object Device {

    val cores: Int by lazy { Runtime.getRuntime().availableProcessors().coerceIn(1, 16) }

    val totalRamMb: Long by lazy {
        val total = Os.sysconf(OsConstants._SC_PHYS_PAGES) * Os.sysconf(OsConstants._SC_PAGE_SIZE)
        total / (1024L * 1024L)
    }

    /** 1 = giriş seviyesi, 2 = orta, 3 = üst seviye */
    val tier: Int by lazy {
        var score = 0
        if (cores >= 6) score++ else if (cores >= 4) score += 0
        if (totalRamMb >= 6 * 1024) score++ else if (totalRamMb >= 4 * 1024) score += 0
        if (VERSION.SDK_INT >= 31) score++
        score.coerceIn(1, 3)
    }

    val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"

    /** Kokoro gibi ağır modeller için önerilen iplik sayısı. */
    fun ttsThreads(): Int = when {
        tier >= 3 -> 4
        tier == 2 -> 3
        else -> 2
    }.coerceAtMost(cores)

    /** ASR için önerilen iplik sayısı. */
    fun asrThreads(): Int = (cores / 2).coerceIn(1, 4)

    fun summary(): String =
        "Android ${VERSION.RELEASE} (API ${VERSION.SDK_INT}) · $cores çekirdek · " +
            "${totalRamMb} MB RAM · $abi · seviye $tier"
}
