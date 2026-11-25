package com.namchok.livesub

/**
 * คำศัพท์กำหนดเอง (ตั้งในแอป) บรรทัดละ 1 กฎ:
 *   apple=แอปเปิล        -> ฝั่งซ้ายเป็นอังกฤษ: แทนในประโยคอังกฤษก่อนแปล
 *   ทับทิม=Ruby          -> ฝั่งซ้ายเป็นไทย: แก้คำในผลแปลไทย
 * บรรทัดที่ขึ้นต้นด้วย # = หมายเหตุ
 */
object Glossary {
    private data class Rule(val from: String, val to: String, val regex: Regex?)

    private var cachedSrc: String? = null
    private var inputRules = emptyList<Rule>()
    private var outputRules = emptyList<Rule>()

    private fun isAscii(s: String) = s.all { it.code < 128 }

    @Synchronized
    private fun load() {
        val src = Prefs.glossary
        if (src == cachedSrc) return
        cachedSrc = src
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
            if (isAscii(from)) {
                val re = Regex("\\b" + Regex.escape(from) + "\\b", RegexOption.IGNORE_CASE)
                inR += Rule(from, to, re)
            } else {
                outR += Rule(from, to, null)
            }
        }
        // คำยาวก่อน กันคำสั้นไปแทนบางส่วนของคำยาว
        inputRules = inR.sortedByDescending { it.from.length }
        outputRules = outR.sortedByDescending { it.from.length }
    }

    fun applyInput(en: String): String {
        load()
        var s = en
        inputRules.forEach { r -> s = r.regex!!.replace(s, Regex.escapeReplacement(r.to)) }
        return s
    }

    fun applyOutput(th: String): String {
        load()
        var s = th
        outputRules.forEach { r -> s = s.replace(r.from, r.to) }
        return s
    }

    /** คำศัพท์ทั้งหมด สำหรับบอก Gemini */
    fun hintText(): String {
        load()
        return (inputRules + outputRules).joinToString("; ") { "${it.from} -> ${it.to}" }
    }
}
