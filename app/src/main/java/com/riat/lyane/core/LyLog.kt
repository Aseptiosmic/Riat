package com.riat.lyane.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Uygulama genelinde bellek içi günlük (halka tampon).
 * Ayarlar → Tanılama ekranında gösterilir; hiçbir yere gönderilmez.
 */
object LyLog {

    data class Entry(
        val timeMs: Long = System.currentTimeMillis(),
        val tag: String,
        val level: Int,
        val message: String
    )

    private const val MAX = 800
    private val buffer = ArrayDeque<Entry>(MAX)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    private fun push(level: Int, tag: String, msg: String, tr: Throwable? = null) {
        val m = if (tr != null) "$msg — ${tr.javaClass.simpleName}: ${tr.message}" else msg
        when (level) {
            Log.DEBUG -> Log.d(tag, m, tr)
            Log.INFO -> Log.i(tag, m, tr)
            Log.WARN -> Log.w(tag, m, tr)
            else -> Log.e(tag, m, tr)
        }
        buffer.addLast(Entry(tag = tag, level = level, message = m))
        while (buffer.size > MAX) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    fun d(tag: String, msg: String) = push(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = push(Log.INFO, tag, msg)
    fun w(tag: String, msg: String, tr: Throwable? = null) = push(Log.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = push(Log.ERROR, tag, msg, tr)

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }
}
