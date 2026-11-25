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

    /** ต้องรออีกกี่ ms ถึงจะส่งได้ */
    fun waitMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return maxOf(cooldownUntil - now, lastRequest + minIntervalMs - now, 0L)
    }

    /**
     * แปลหลายประโยคในคำขอเดียว (ประหยัดโควตา ไม่ทิ้งประโยคไหน)
     * cb ถูกเรียกบน main thread: รายการผลแปลตามลำดับ (null = ประโยคนั้นไม่สำเร็จ) หรือ null ทั้งก้อน
     */
    fun translateBatch(lines: List<String>, context: List<String>, glossaryHint: String, cb: (List<String?>?) -> Unit) {
        if (lines.isEmpty() || !canSend()) { cb(null); return }
        lastRequest = SystemClock.elapsedRealtime()
        io.execute {
            val result = try {
                val model = ensureModel()
                if (model == null) null else request(model, lines, context, glossaryHint)
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

    private fun request(model: String, lines: List<String>, context: List<String>, hint: String): List<String?>? {
        val prompt = buildString {
            append("You are a professional film/TV subtitle translator, English -> Thai.\n")
            append("The English lines come from live speech recognition: casing and punctuation may be missing, ")
            append("sentences may be split across lines, and a few words may be misheard. ")
            append("Use the context to infer the intended meaning and write natural, fluent spoken Thai ")
            append("that a Thai viewer would read in a real subtitle (not word-for-word). Keep names as names.\n")
            if (Prefs.uncensored) {
                append("Translate faithfully for an adult audience: keep profanity, insults, slang and vulgar or sexual ")
                append("language at the SAME strength in Thai (e.g. fuck -> เหี้ย/แม่ง, shit -> ฉิบหาย, asshole -> ไอ้สารเลว). ")
                append("Never soften, censor, omit, or use asterisks.\n")
            }
            if (hint.isNotBlank()) append("Required terminology: ").append(hint).append('\n')
            if (context.isNotEmpty()) append("Earlier lines (context only, do not translate):\n")
                .append(context.joinToString("\n") { "- $it" }).append('\n')
            append("Translate these ").append(lines.size).append(" line(s). ")
            append("Reply with exactly ").append(lines.size).append(" line(s), each as `<number>. <Thai>`, nothing else.\n")
            lines.forEachIndexed { i, l -> append(i + 1).append(". ").append(l).append('\n') }
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 1024 + 200 * lines.size))
        if (Prefs.uncensored) {
            // ปิดตัวกรองของ Gemini (ไม่งั้นประโยคที่มีคำหยาบอาจถูกบล็อกทั้งประโยค)
            val safety = JSONArray()
            listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT").forEach {
                safety.put(JSONObject().put("category", it).put("threshold", "BLOCK_NONE"))
            }
            body.put("safetySettings", safety)
        }
        val json = http("POST", "$base/models/$model:generateContent?key=$apiKey", body.toString()) ?: return null
        val parts = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val text = buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text") ?: "") }
        val out = arrayOfNulls<String>(lines.size)
        val re = Regex("""^\s*(\d+)\s*[.)\]:-]\s*(.+)$""")
        text.lines().forEach { l ->
            val m = re.find(l) ?: return@forEach
            val idx = m.groupValues[1].toIntOrNull()?.minus(1) ?: return@forEach
            if (idx in out.indices) out[idx] = m.groupValues[2].trim().trim('`').takeIf { it.isNotEmpty() }
        }
        // ตอบมาบรรทัดเดียวแบบไม่มีเลข (กรณีขอ 1 ประโยค)
        if (lines.size == 1 && out[0] == null) out[0] = text.lines().firstOrNull { it.isNotBlank() }?.trim()
        return out.toList()
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
