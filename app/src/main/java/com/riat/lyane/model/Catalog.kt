package com.riat.lyane.model

import kotlinx.serialization.Serializable

/**
 * Lyane model kataloğu şeması.
 *
 * Katalog, `assets/catalog/models.json` içinde gömülü olarak gelir; ayrıca
 * kullanıcı kendi katalog JSON dosyalarını (Dosya ya da Lyane Drop ile) içe
 * aktarabilir. Böylece mağaza listesi herkese açık ve genişletilebilir kalır.
 */
@Serializable
data class EnginePaths(
    /** Ana model dosyası (VITS/Kokoro/Kitten/SenseVoice). */
    val model: List<String> = emptyList(),
    val encoder: List<String> = emptyList(),
    val decoder: List<String> = emptyList(),
    val joiner: List<String> = emptyList(),
    val tokens: List<String> = emptyList(),
    /** Kokoro/Kitten: voices.bin */
    val voices: List<String> = emptyList(),
    val lexicon: List<String> = emptyList(),
    /** espeak-ng-data veya dict gibi veri dizinleri. */
    val dataDir: List<String> = emptyList(),
    /** Matcha: vocos/hifigan vokoder. */
    val vocoder: List<String> = emptyList(),
    /** Matcha: akustik model. */
    val acousticModel: List<String> = emptyList(),
    /** Moonshine v1. */
    val preprocessor: List<String> = emptyList(),
    val uncachedDecoder: List<String> = emptyList(),
    val cachedDecoder: List<String> = emptyList(),
    /** Moonshine v2. */
    val mergedDecoder: List<String> = emptyList(),
    /** Sayı/tarih normalizasyonu için kural dosyaları (*.fst). */
    val ruleFsts: List<String> = emptyList(),
    /** Supertonic. */
    val durationPredictor: List<String> = emptyList(),
    val textEncoder: List<String> = emptyList(),
    val vectorEstimator: List<String> = emptyList(),
    val ttsJson: List<String> = emptyList(),
    val unicodeIndexer: List<String> = emptyList(),
    val voiceStyle: List<String> = emptyList()
)

@Serializable
data class ModelSpec(
    val id: String,
    val name: String,
    /** tts | asr | asr_live | vad */
    val task: String,
    /** vits | matcha | kokoro | kitten | supertonic | whisper | moonshine |
     *  sensevoice | transducer | paraformer | silero */
    val engine: String,
    val languages: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    val license: String = "",
    val author: String = "",
    val description: String = "",
    val homeUrl: String = "",
    val url: String = "",
    /** tar.bz2 | tar.gz | tar | zip | file */
    val format: String = "tar.bz2",
    /** 1..5 (dolandırıcılık değil, kalite puanı) */
    val quality: Int = 3,
    /** 1..5 hız puanı */
    val speed: Int = 3,
    val recommended: Boolean = false,
    /** Konuşmacı adları (Kokoro/Kitten). Boşsa tek ses. */
    val speakers: List<String> = emptyList(),
    val paths: EnginePaths = EnginePaths(),
    /** Motor varsayılanları: language, task, quantized, useItn, maxSpeechDur… */
    val options: Map<String, String> = emptyMap(),
    /** Çalışması için önerilen en az RAM (MB). */
    val minRamMb: Int = 0,
    val vocabulary: String = ""
)

/** Katalog dosyasının kökü. */
@Serializable
data class CatalogFile(
    val version: Int = 1,
    val updatedAt: String = "",
    val models: List<ModelSpec> = emptyList()
)

/** Kurulu modelin diske yazılan tanımı (model dizinindeki lyane.json). */
@Serializable
data class InstalledModel(
    val spec: ModelSpec,
    val installedAtMs: Long,
    val sizeBytes: Long,
    val dirName: String,
    val source: String = "download"
) {
    val id: String get() = spec.id
    val task: String get() = spec.task
    val engine: String get() = spec.engine
    val name: String get() = spec.name
}

object TaskType {
    const val TTS = "tts"
    const val ASR = "asr"          // offline (dosya / VAD'lı parça)
    const val ASR_LIVE = "asr_live" // akış (canlı)
    const val VAD = "vad"
}
