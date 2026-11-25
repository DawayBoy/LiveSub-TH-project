package com.namchok.livesub

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** เก็บซับของแต่ละรอบ แล้วบันทึกเป็น .srt (ไทย+อังกฤษ) และ .txt ไว้ที่ Download/LiveSub */
class SubtitleLog {
    class Entry(val startMs: Long, val endMs: Long, val en: String, var th: String)

    private val entries = mutableListOf<Entry>()
    private val startedAt = Date()

    @Synchronized
    fun add(startMs: Long, endMs: Long, en: String, th: String): Entry =
        Entry(startMs, endMs, en, th).also { entries += it }

    private fun ts(ms: Long): String {
        val h = ms / 3_600_000; val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60; val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    /** คืนชื่อไฟล์ที่บันทึก หรือ null ถ้าไม่มีอะไรให้บันทึก */
    @Synchronized
    fun save(ctx: Context): String? {
        if (entries.isEmpty()) return null
        val name = "LiveSub_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(startedAt)

        val srt = StringBuilder()
        entries.forEachIndexed { i, e ->
            srt.append(i + 1).append('\n')
            srt.append(ts(e.startMs)).append(" --> ").append(ts(maxOf(e.endMs, e.startMs + 800))).append('\n')
            srt.append(e.th).append('\n').append(e.en).append("\n\n")
        }
        val txt = StringBuilder()
        entries.forEach { e ->
            txt.append('[').append(ts(e.startMs).substring(0, 8)).append("] ")
                .append(e.en).append('\n').append("           ").append(e.th).append("\n\n")
        }
        write(ctx, "$name.srt", "application/x-subrip", srt.toString())
        write(ctx, "$name.txt", "text/plain", txt.toString())
        return "Download/LiveSub/$name"
    }

    private fun write(ctx: Context, fileName: String, mime: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LiveSub")
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }
}
