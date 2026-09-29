package com.riat.lyane.core

/**
 * Uzun metinleri TTS için cümlelere böler.
 * Her cümle ayrı sentezlenir; ilk cümle çalınırken sonrakiler üretilir
 * (akış hissi) ve RAM kullanımı sabit kalır.
 */
object TextSplitter {

    private val ABBREV = listOf(
        "Dr", "Prof", "Mr", "Mrs", "Ms", "vs", "vb", "ör", "sn", "min", "sa",
        "No", "Cad", "Sok", "Mah", "Ave", "St", "Inc", "Ltd", "Jr", "Sr", "Fig", "Tab"
    )

    fun sentences(text: String, maxLen: Int = 260): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            sb.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '…' || c == ';' || c == '\n') {
                val before = wordBefore(normalized, i)
                val isAbbrev = ABBREV.any { it.equals(before, ignoreCase = true) }
                val next = normalized.getOrNull(i + 1)
                if (!isAbbrev && (next == null || next.isWhitespace() || next == '"' || next == '’' || next == '\'')) {
                    push(sb, out)
                }
            }
            i++
        }
        push(sb, out)

        // çok kısa parçaları birleştir
        val merged = ArrayList<String>()
        for (piece in out) {
            val last = merged.lastOrNull()
            if (last != null && (last.length + piece.length) < maxLen / 2 && (last.length < 25 || piece.length < 25)) {
                merged[merged.size - 1] = (last.trimEnd() + " " + piece).trim()
            } else {
                merged.add(piece)
            }
        }

        // çok uzun parçaları virgülden böl
        val result = ArrayList<String>()
        for (piece in merged) {
            if (piece.length <= maxLen) {
                result.add(piece)
            } else {
                result.addAll(splitLong(piece, maxLen))
            }
        }
        return result.filter { it.isNotBlank() }
    }

    private fun push(sb: StringBuilder, out: MutableList<String>) {
        val s = sb.toString().trim()
        if (s.isNotEmpty()) out.add(s)
        sb.clear()
    }

    private fun wordBefore(s: String, idx: Int): String {
        var j = idx - 1
        while (j >= 0 && !s[j].isLetter()) j--
        val end = j
        while (j >= 0 && s[j].isLetter()) j--
        return if (end > j) s.substring(j + 1, end + 1) else ""
    }

    private fun splitLong(piece: String, maxLen: Int): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var lastSplit = -1
        var i = 0
        while (i < piece.length) {
            val c = piece[i]
            if (c == ',' || c == ':' || c == '—' || c == '–' || (c == ' ' && i - start > maxLen / 2)) lastSplit = i
            if (i - start + 1 >= maxLen) {
                val cut = if (lastSplit > start) lastSplit + 1 else i + 1
                parts.add(piece.substring(start, cut).trim())
                start = cut
                lastSplit = -1
            }
            i++
        }
        if (start < piece.length) parts.add(piece.substring(start).trim())
        return parts.filter { it.isNotEmpty() }
    }
}
