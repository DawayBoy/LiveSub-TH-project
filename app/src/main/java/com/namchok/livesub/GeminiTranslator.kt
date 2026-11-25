package com.namchok.livesub

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * แปลคุณภาพสูงผ่าน Gemini API (free tier, ใช้ API key ของผู้ใช้เอง)
 * - เลือกโมเดล Flash-Lite รุ่นล่าสุดให้อัตโนมัติ (ชื่อโมเดลเปลี่ยนบ่อย)
 * - จำกัดความถี่ และพักเองเมื่อโดน rate limit -> ระหว่างนั้นใช้ ML Kit แทน
 */
class GeminiTranslator(private val apiKey: String) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    private var lastRequest = 0L
    private var cooldownUntil = 0L

    /** ส่งได้ไม่ถี่เกินนี้ (free tier มี RPM จำกัด) */
    private val minIntervalMs = 4000L

    @Volatile var lastError: String? = null
        private set

    fun canSend(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return apiKey.isNotBlank() && now >= cooldownUntil && now - lastRequest >= minIntervalMs
    }

    /** แปล 1 ประโยค; cb ถูกเรียกบน main thread (null = ไม่สำเร็จ) */
    fun translate(en: String, context: List<String>, glossaryHint: String, cb: (String?) -> Unit) {
        if (!canSend()) { cb(null); return }
        lastRequest = SystemClock.elapsedRealtime()
        io.execute {
            val result = try {
                val model = ensureModel()
                if (model == null) null else request(model, en, context, glossaryHint)
            } catch (e: Exception) {
                Log.w(CaptureService.TAG, "gemini", e)
                lastError = e.message
                null
            }
            main.post { cb(result) }
        }
    }

    private fun ensureModel(): String? {
        Prefs.geminiModel.takeIf { it.isNotBlank() }?.let { return it }
        val json = http("GET", "$base/models?pageSize=200&key=$apiKey", null) ?: return null
        val models = JSONObject(json).optJSONArray("models") ?: return null
        val names = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            val name = m.optString("name").removePrefix("models/")
            if (!methods.contains("generateContent")) continue
            if (!name.startsWith("gemini")) continue
            if (listOf("image", "tts", "live", "audio", "embedding", "exp", "robotics", "computer")
                    .any { name.contains(it) }) continue
            names += name
        }
        // ชอบ flash-lite ตัวเสถียรเวอร์ชันสูงสุด > flash-lite preview > flash
        val pick = names.filter { it.contains("flash-lite") && !it.contains("preview") }.maxOrNull()
            ?: names.filter { it.contains("flash-lite") }.maxOrNull()
            ?: names.filter { it.contains("flash") && !it.contains("preview") }.maxOrNull()
            ?: names.filter { it.contains("flash") }.maxOrNull()
        if (pick != null) Prefs.geminiModel = pick
        return pick
    }

    private fun request(model: String, en: String, context: List<String>, hint: String): String? {
        val prompt = buildString {
            append("You translate English video speech into natural, concise Thai subtitles. ")
            append("The English comes from automatic speech recognition: it is lowercase, has no punctuation, and may contain small mistakes — infer the intended meaning. ")
            append("Reply with ONLY the Thai subtitle text, one line, nothing else.\n")
            if (hint.isNotBlank()) append("Required terminology: ").append(hint).append('\n')
            if (context.isNotEmpty()) append("Previous lines (for context, do not translate): ")
                .append(context.joinToString(" / ")).append('\n')
            append("Line to translate: ").append(en)
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 256))
        val json = http("POST", "$base/models/$model:generateContent?key=$apiKey", body.toString()) ?: return null
        val text = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
        return text?.trim()?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun http(method: String, url: String, body: String?): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 12000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        if (code == 429) {
            cooldownUntil = SystemClock.elapsedRealtime() + 60_000
            lastError = "ใช้โควตาฟรีเต็ม พัก 1 นาที (ใช้ ML Kit แทน)"
            return null
        }
        if (code == 404 && method == "POST") {
            Prefs.geminiModel = ""   // โมเดลถูกปลด เลือกใหม่รอบหน้า
        }
        if (code !in 200..299) {
            lastError = "Gemini error $code"
            c.errorStream?.close()
            return null
        }
        lastError = null
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** ทดสอบ key จากหน้าตั้งค่า: คืนชื่อโมเดลที่ใช้ได้ หรือ null */
    fun test(cb: (String?) -> Unit) {
        io.execute {
            val r = try { Prefs.geminiModel = ""; ensureModel() } catch (e: Exception) { lastError = e.message; null }
            main.post { cb(r) }
        }
    }

    fun close() { io.shutdownNow() }
}
