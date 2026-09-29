package com.riat.lyane.model

import java.io.File

/**
 * Harici model arşivlerinden motor türünü otomatik algılar.
 * Kullanıcı herhangi bir sherpa-onnx uyumlu modeli (katalogda olmasa bile)
 * içe aktarabilsin diye var.
 */
object ModelAutoDetect {

    private fun filesOf(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.toList()

    private fun File.base() = name.substringAfterLast('/')

    fun detect(dir: File, displayName: String): ModelSpec? {
        val files = filesOf(dir)
        val names = files.map { it.base() }
        val has = { pattern: Regex -> names.any { pattern.matches(it) } }

        fun find(pattern: Regex) = files.firstOrNull { pattern.matches(it.base()) }
        fun tokens() = find(Regex("tokens\\.txt")) ?: find(Regex(".*tokens.*\\.txt"))
        fun sizeBytes() = files.sumOf { it.length() }
        fun dirNamed(n: String) = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == n }

        val espeak = dirNamed("espeak-ng-data")
        val tk = tokens()

        return when {
            // Kokoro: voices.bin + model.onnx (+ tokens)
            has(Regex("voices\\.bin")) -> {
                val model = find(Regex("model(\\.int8)?\\.onnx")) ?: find(Regex(".*\\.onnx"))
                ModelSpec(
                    id = "import-kokoro-${System.currentTimeMillis()}",
                    name = displayName,
                    task = TaskType.TTS, engine = "kokoro",
                    sizeBytes = sizeBytes(),
                    license = "Apache-2.0", author = "Kokoro-82M",
                    description = "Otomatik algılanmış Kokoro modeli",
                    paths = EnginePaths(
                        model = listOfNotNull(model?.base(), "*.onnx"),
                        voices = listOf("voices.bin", "*.bin"),
                        tokens = listOfNotNull(tk?.base(), "tokens.txt", "tokens.bin"),
                        dataDir = if (espeak != null) listOf("espeak-ng-data") else emptyList()
                    ),
                    options = if ((model?.base() ?: "").contains("int8")) mapOf("quantized" to "true") else emptyMap()
                )
            }
            // Supertonic
            has(Regex("duration_predictor.*\\.onnx")) -> ModelSpec(
                id = "import-supertonic-${System.currentTimeMillis()}",
                name = displayName, task = TaskType.TTS, engine = "supertonic",
                sizeBytes = sizeBytes(), license = "?", author = "SupertonicTTS",
                description = "Otomatik algılanmış Supertonic modeli",
                paths = EnginePaths(
                    durationPredictor = listOf("duration_predictor*.onnx"),
                    textEncoder = listOf("text_encoder*.onnx"),
                    vectorEstimator = listOf("vector_estimator*.onnx"),
                    vocoder = listOf("vocoder*.onnx"),
                    ttsJson = listOf("tts.json"),
                    unicodeIndexer = listOf("unicode_indexer.bin"),
                    voiceStyle = listOf("voice_style*")
                )
            )
            // Matcha: akustik model + vokoder
            has(Regex("model-steps.*\\.onnx")) || has(Regex("vocos.*\\.onnx")) || has(Regex("hifigan.*\\.onnx")) -> {
                val acoustic = find(Regex("model-steps.*\\.onnx"))
                val voc = find(Regex("vocos.*\\.onnx")) ?: find(Regex("hifigan.*\\.onnx"))
                ModelSpec(
                    id = "import-matcha-${System.currentTimeMillis()}",
                    name = displayName, task = TaskType.TTS, engine = "matcha",
                    sizeBytes = sizeBytes(), license = "Apache-2.0", author = "icefall/matcha",
                    description = "Otomatik algılanmış Matcha modeli",
                    paths = EnginePaths(
                        acousticModel = listOfNotNull(acoustic?.base(), "model-steps*.onnx"),
                        vocoder = listOfNotNull(voc?.base(), "vocos*.onnx", "hifigan*.onnx"),
                        tokens = listOfNotNull(tk?.base(), "tokens.txt"),
                        dataDir = if (espeak != null) listOf("espeak-ng-data") else emptyList()
                    )
                )
            }
            // Moonshine v2
            has(Regex("encoder_model.*\\.(onnx|ort)")) -> ModelSpec(
                id = "import-moonshine-${System.currentTimeMillis()}",
                name = displayName, task = TaskType.ASR, engine = "moonshine",
                sizeBytes = sizeBytes(), license = "MIT", author = "Useful Sensors",
                description = "Otomatik algılanmış Moonshine modeli",
                paths = EnginePaths(
                    encoder = listOf("encoder_model*.onnx", "encoder_model*.ort"),
                    mergedDecoder = listOf("decoder_model_merged*.onnx", "decoder_model_merged*.ort"),
                    tokens = listOfNotNull(tk?.base(), "tokens.txt")
                )
            )
            // Moonshine v1
            has(Regex("preprocess\\.(onnx|ort|int8\\.onnx)")) -> ModelSpec(
                id = "import-moonshine-${System.currentTimeMillis()}",
                name = displayName, task = TaskType.ASR, engine = "moonshine",
                sizeBytes = sizeBytes(), license = "MIT", author = "Useful Sensors",
                description = "Otomatik algılanmış Moonshine v1 modeli",
                paths = EnginePaths(
                    preprocessor = listOf("preprocess*.onnx", "preprocess*.ort"),
                    encoder = listOf("encode*.onnx", "encode*.ort", "encoder*.onnx"),
                    uncachedDecoder = listOf("uncached_decode*.onnx", "uncached_decode*.ort"),
                    cachedDecoder = listOf("cached_decode*.onnx", "cached_decode*.ort"),
                    tokens = listOfNotNull(tk?.base(), "tokens.txt")
                )
            )
            // Whisper: encoder/decoder çifti
            has(Regex(".*-encoder(\\.int8)?\\.onnx")) && has(Regex(".*-decoder(\\.int8)?\\.onnx")) -> {
                val enc = find(Regex(".*-encoder(\\.int8)?\\.onnx"))
                val dec = find(Regex(".*-decoder(\\.int8)?\\.onnx"))
                ModelSpec(
                    id = "import-whisper-${System.currentTimeMillis()}",
                    name = displayName, task = TaskType.ASR, engine = "whisper",
                    sizeBytes = sizeBytes(), license = "MIT", author = "OpenAI",
                    description = "Otomatik algılanmış Whisper modeli (çok dilli)",
                    languages = listOf("auto"),
                    paths = EnginePaths(
                        encoder = listOfNotNull(enc?.base(), "*-encoder*.onnx"),
                        decoder = listOfNotNull(dec?.base(), "*-decoder*.onnx"),
                        tokens = listOfNotNull(tk?.base(), "*-tokens.txt", "tokens.txt")
                    ),
                    options = mapOf("language" to "auto", "task" to "transcribe")
                )
            }
            // SenseVoice
            has(Regex("model\\.int8\\.onnx")) && tk != null -> ModelSpec(
                id = "import-sensevoice-${System.currentTimeMillis()}",
                name = displayName, task = TaskType.ASR, engine = "sensevoice",
                sizeBytes = sizeBytes(), license = "Apache-2.0", author = "FunAudioLLM",
                description = "Otomatik algılanmış SenseVoice modeli",
                languages = listOf("zh", "en", "ja", "ko", "yue"),
                paths = EnginePaths(
                    model = listOf("model.int8.onnx", "model.onnx"),
                    tokens = listOf(tk.base(), "tokens.txt")
                ),
                options = mapOf("language" to "auto", "useItn" to "true", "quantized" to "true")
            )
            // Transducer (çevrimdışı veya akış): encoder/decoder/joiner
            has(Regex("encoder.*\\.int8\\.onnx")) && has(Regex("decoder.*\\.onnx")) && has(Regex("joiner.*\\.onnx")) -> {
                val enc = find(Regex("encoder.*\\.onnx"))
                ModelSpec(
                    id = "import-zipformer-${System.currentTimeMillis()}",
                    name = displayName,
                    // -online- adı varsa akış modeli say
                    task = if (enc?.base()?.contains("online") == true || dir.name.contains("streaming"))
                        TaskType.ASR_LIVE else TaskType.ASR,
                    engine = "transducer",
                    sizeBytes = sizeBytes(), license = "Apache-2.0", author = "icefall",
                    description = "Otomatik algılanmış Zipformer transducer modeli",
                    paths = EnginePaths(
                        encoder = listOf("encoder*.int8.onnx", "encoder*.onnx"),
                        decoder = listOf("decoder*.int8.onnx", "decoder*.onnx"),
                        joiner = listOf("joiner*.int8.onnx", "joiner*.onnx"),
                        tokens = listOfNotNull(tk?.base(), "tokens.txt")
                    ),
                    options = mapOf("quantized" to "true")
                )
            }
            // VITS/Piper: tek onnx + tokens (+ espeak-ng-data)
            tk != null && espeak != null -> {
                val model = files.filter { it.extension == "onnx" }.maxByOrNull { it.length() }
                ModelSpec(
                    id = "import-vits-${System.currentTimeMillis()}",
                    name = displayName, task = TaskType.TTS, engine = "vits",
                    sizeBytes = sizeBytes(), license = "MIT", author = "Piper/VITS",
                    description = "Otomatik algılanmış VITS (Piper) modeli",
                    paths = EnginePaths(
                        model = listOfNotNull(model?.base(), "*.onnx"),
                        tokens = listOf(tk.base()),
                        dataDir = listOf("espeak-ng-data")
                    )
                )
            }
            else -> null
        }
    }
}
