package com.riat.lyane.model

import java.io.File
import java.util.regex.Pattern

/**
 * Kurulu bir model dizininde, katalog tanımındaki yol adaylarını çözümler.
 *
 * Aday listeleri esnektir: önce birebir ad/dizin eşleşmesi aranır, sonra
 * basit glob desenleri (`*`, `?`) denenir. Böylece farklı model sürümlerindeki
 * dosya adı farkları (ör. `model.onnx` ↔ `tr_TR-fahrettin-medium.int8.onnx`)
 * uygulamayı kırmadan çözümlenir.
 */
object PathResolver {

    data class Resolved(
        val dir: File,
        val files: Map<String, File>,
        val dataDirs: Map<String, File>,
        val ruleFsts: List<File> = emptyList()
    )

    private data class Indexed(val root: File, val files: List<File>, val dirs: List<File>)

    private fun index(root: File): Indexed {
        val files = mutableListOf<File>()
        val dirs = mutableListOf<File>()
        root.walkTopDown().onEach { f ->
            when {
                f.isDirectory -> if (f != root) dirs += f
                f.isFile -> files += f
            }
        }.count()
        return Indexed(root, files, dirs)
    }

    private fun rel(root: File, f: File): String =
        f.relativeTo(root).invariantSeparatorsPath

    fun globToRegex(pattern: String): Pattern {
        val sb = StringBuilder()
        for (c in pattern) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '.' -> sb.append("\\.")
            else -> sb.append(Pattern.quote(c.toString()))
        }
        return Pattern.compile(sb.toString())
    }

    /**
     * Bir rol için adayları çözer.
     * @return dosya ya da null
     */
    private fun resolveFile(idx: Indexed, candidates: List<String>, preferInt8: Boolean): File? {
        if (candidates.isEmpty()) return null
        // 1) birebir eşleşme (göreli yol → dosya adı)
        for (cand in candidates) {
            if ('*' !in cand && '?' !in cand) {
                idx.files.firstOrNull { rel(idx.root, it) == cand }?.let { return it }
                idx.files.firstOrNull { it.name == cand }?.let { return it }
            }
        }
        // 2) glob
        for (cand in candidates) {
            if ('*' !in cand && '?' !in cand) continue
            val p = globToRegex(cand)
            val matches = idx.files.filter { p.matcher(rel(idx.root, it)).matches() || p.matcher(it.name).matches() }
            if (matches.isNotEmpty()) {
                return when {
                    matches.size == 1 -> matches[0]
                    preferInt8 -> matches.firstOrNull { it.name.contains(".int8.") || it.name.contains("-int8.") }
                        ?: matches.maxByOrNull { it.length() }!!
                    else -> matches.maxByOrNull { it.length() }!!
                }
            }
        }
        return null
    }

    private fun resolveDir(idx: Indexed, candidates: List<String>): File? {
        for (cand in candidates) {
            if ('*' !in cand && '?' !in cand) {
                idx.dirs.firstOrNull { it.name == cand }?.let { return it }
            } else {
                val p = globToRegex(cand)
                idx.dirs.firstOrNull { p.matcher(it.name).matches() }?.let { return it }
            }
        }
        return null
    }

    fun resolve(dir: File, spec: ModelSpec): Resolved {
        val idx = index(dir)
        val preferInt8 = spec.options["quantized"] == "true"
        val files = LinkedHashMap<String, File>()
        val dirs = LinkedHashMap<String, File>()

        fun put(role: String, candidates: List<String>) {
            resolveFile(idx, candidates, preferInt8)?.let { files[role] = it }
        }

        val p = spec.paths
        put("model", p.model)
        put("encoder", p.encoder)
        put("decoder", p.decoder)
        put("joiner", p.joiner)
        put("tokens", p.tokens)
        put("voices", p.voices)
        put("lexicon", p.lexicon)
        put("vocoder", p.vocoder)
        put("acousticModel", p.acousticModel)
        put("preprocessor", p.preprocessor)
        put("uncachedDecoder", p.uncachedDecoder)
        put("cachedDecoder", p.cachedDecoder)
        put("mergedDecoder", p.mergedDecoder)
        put("durationPredictor", p.durationPredictor)
        put("textEncoder", p.textEncoder)
        put("vectorEstimator", p.vectorEstimator)
        put("ttsJson", p.ttsJson)
        put("unicodeIndexer", p.unicodeIndexer)
        put("voiceStyle", p.voiceStyle)
        // ruleFsts çoklu olabilir; tüm eşleşmeler birleşir
        val fst = mutableListOf<File>()
        for (cand in p.ruleFsts) {
            resolveFile(idx, listOf(cand), preferInt8)?.let { fst += it }
        }
        if (fst.isNotEmpty()) files["ruleFsts"] = File(fst.joinToString(",")) // yol listesi ayrı tutulur
        for (cand in p.dataDir) resolveDir(idx, listOf(cand))?.let { dirs[cand.substringAfterLast('/')] = it }

        return Resolved(dir, files, dirs)
    }

    /** Eksik kritik dosyaları bulur (kurulum doğrulama ve tanılama için). */
    fun missingCritical(spec: ModelSpec, resolved: Resolved): List<String> {
        val need = when (spec.engine) {
            "vits" -> listOf("model", "tokens")
            "matcha" -> listOf("acousticModel", "vocoder", "tokens")
            "kokoro" -> listOf("model", "voices", "tokens")
            "kitten" -> listOf("model", "voices", "tokens")
            "supertonic" -> listOf("durationPredictor", "textEncoder", "vectorEstimator", "vocoder", "ttsJson", "unicodeIndexer")
            "whisper" -> listOf("encoder", "decoder", "tokens")
            "moonshine" -> {
                if (resolved.files.containsKey("mergedDecoder")) listOf("encoder", "mergedDecoder", "tokens")
                else listOf("preprocessor", "encoder", "uncachedDecoder", "cachedDecoder", "tokens")
            }
            "sensevoice" -> listOf("model", "tokens")
            "transducer" -> listOf("encoder", "decoder", "joiner", "tokens")
            "paraformer" -> listOf("model", "tokens")
            "silero" -> listOf("model")
            else -> emptyList()
        }
        return need.filter { it !in resolved.files }
    }
}
