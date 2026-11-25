package com.namchok.livesub

/**
 * คำศัพท์กำหนดเอง (ตั้งในแอป) บรรทัดละ 1 กฎ:
 *   apple=แอปเปิล        -> ฝั่งซ้ายเป็นอังกฤษ: แทนในประโยคอังกฤษก่อนแปล
 *   ทับทิม=Ruby          -> ฝั่งซ้ายเป็นไทย: แก้คำในผลแปลไทย
 * บรรทัดที่ขึ้นต้นด้วย # = หมายเหตุ
 * + คำหยาบในตัว (Profanity) เมื่อเปิด "แปลคำหยาบตรงตัว" — กฎของผู้ใช้มาก่อนเสมอ
 */
object Glossary {
    private class Rule(val from: String, val to: String, val regex: Regex?)

    private var cachedKey: String? = null
    private var userInput = emptyList<Rule>()
    private var builtinInput = emptyList<Rule>()
    private var outputRules = emptyList<Rule>()

    private fun isAscii(s: String) = s.all { it.code < 128 }

    private fun wordRule(from: String, to: String) =
        Rule(from, to, Regex("\\b" + Regex.escape(from) + "\\b", RegexOption.IGNORE_CASE))

    @Synchronized
    private fun load() {
        val src = Prefs.glossary
        val key = src + "|" + Prefs.uncensored
        if (key == cachedKey) return
        cachedKey = key
        val inR = mutableListOf<Rule>()
        val outR = mutableListOf<Rule>()
        src.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val i = line.indexOf('=')
            if (i <= 0 || i == line.length - 1) return@forEach
            val from = line.substring(0, i).trim()
            val to = line.substring(i + 1).trim()
            if (from.isEmpty()) return@forEach
            if (isAscii(from)) inR += wordRule(from, to) else outR += Rule(from, to, null)
        }
        // คำยาวก่อน กันคำสั้นไปแทนบางส่วนของคำยาว
        userInput = inR.sortedByDescending { it.from.length }
        outputRules = outR.sortedByDescending { it.from.length }
        val userKeys = inR.map { it.from.lowercase() }.toSet()
        builtinInput = if (Prefs.uncensored) {
            Profanity.rules.filter { it.first !in userKeys }
                .map { wordRule(it.first, it.second) }
                .sortedByDescending { it.from.length }
        } else emptyList()
    }

    /** @param builtin ใส่คำหยาบในตัวด้วยไหม (ML Kit = ใส่, Gemini = ไม่ต้อง เพราะสั่งในพรอมต์แล้ว) */
    fun applyInput(en: String, builtin: Boolean = true): String {
        load()
        var s = en
        userInput.forEach { r -> s = r.regex!!.replace(s, Regex.escapeReplacement(r.to)) }
        if (builtin) builtinInput.forEach { r -> s = r.regex!!.replace(s, Regex.escapeReplacement(r.to)) }
        return s
    }

    fun applyOutput(th: String): String {
        load()
        var s = th
        outputRules.forEach { r -> s = s.replace(r.from, r.to) }
        return s
    }

    /** คำศัพท์ของผู้ใช้ สำหรับบอก Gemini */
    fun hintText(): String {
        load()
        return (userInput + outputRules).joinToString("; ") { "${it.from} -> ${it.to}" }
    }
}
