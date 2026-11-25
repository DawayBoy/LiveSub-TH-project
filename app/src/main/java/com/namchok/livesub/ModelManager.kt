package com.namchok.livesub

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** โมเดลเสียงตัวใหญ่ (แม่นกว่า) — ดาวน์โหลดในแอปเมื่อผู้ใช้เลือกใช้ */
object ModelManager {
    const val LARGE_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"
    const val LARGE_SIZE_MB = 128

    fun largeDir(ctx: Context) = File(ctx.filesDir, "vosk-large")
    private fun marker(ctx: Context) = File(largeDir(ctx), ".ready")

    fun isLargeReady(ctx: Context) = marker(ctx).exists()

    @Volatile var downloading = false
        private set

    /**
     * ดาวน์โหลด + แตกไฟล์ (รันบน thread แยก)
     * progress(0..100) และ done(error หรือ null) ถูกเรียกจาก thread นั้น
     */
    fun downloadLarge(ctx: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
        if (downloading) return
        downloading = true
        Thread({
            val app = ctx.applicationContext
            val zip = File(app.cacheDir, "vosk-large.zip")
            try {
                val conn = URL(LARGE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var sum = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sum += n
                            if (total > 0) {
                                val pct = (sum * 90 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; progress(pct) }
                            }
                        }
                    }
                }
                val dir = largeDir(app)
                dir.deleteRecursively()
                dir.mkdirs()
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        // ตัดโฟลเดอร์ชั้นแรก (vosk-model-en-us-0.22-lgraph/...)
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty() || rel.contains("..")) continue
                        val f = File(dir, rel)
                        if (e.isDirectory) f.mkdirs()
                        else {
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { zin.copyTo(it) }
                        }
                    }
                }
                File(dir, ".ready").writeText("ok")
                progress(100)
                done(null)
            } catch (e: Exception) {
                done(e.message ?: e.toString())
            } finally {
                zip.delete()
                downloading = false
            }
        }, "livesub-model-dl").start()
    }

    fun deleteLarge(ctx: Context) {
        largeDir(ctx).deleteRecursively()
    }
}
