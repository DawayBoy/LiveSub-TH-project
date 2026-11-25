package com.namchok.livesub

import android.content.Context
import android.content.SharedPreferences

/** การตั้งค่าที่จำไว้ข้ามการเปิดแอป */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("livesub", Context.MODE_PRIVATE)
        }
    }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** true = Realtime (ขึ้นทันทีระหว่างพูด), false = แม่นยำ (รอจบประโยคค่อยแปล) */
    var realtime: Boolean get() = b("realtime", true); set(v) = setB("realtime", v)
    var showEnglish: Boolean get() = b("showEnglish", true); set(v) = setB("showEnglish", v)

    /** ขนาดตัวอักษรภาษาไทย (sp) */
    var fontSize: Int
        get() = sp.getInt("fontSize", 20)
        set(v) = sp.edit().putInt("fontSize", v).apply()

    /** พื้นหลังซับ: 0 = ใส (ตัวขาวขอบดำ แบบซับหนัง), 1 = กึ่งใส, 2 = ทึบ */
    var bgStyle: Int
        get() = sp.getInt("bgStyle", 0)
        set(v) = sp.edit().putInt("bgStyle", v).apply()

    fun bgStyleName(s: Int = bgStyle) = when (s) { 0 -> "ใส"; 1 -> "กึ่งใส"; else -> "ทึบ" }

    /** ตำแหน่งซับเป็นสัดส่วนความสูงจอ (0..1) แยกแนวตั้ง/แนวนอน, -1 = ค่าเริ่มต้น */
    fun posFrac(landscape: Boolean): Float =
        sp.getFloat(if (landscape) "posL" else "posP", if (landscape) 0.72f else 0.62f)
    fun setPosFrac(landscape: Boolean, v: Float) =
        sp.edit().putFloat(if (landscape) "posL" else "posP", v).apply()

    /** บันทึกซับเป็นไฟล์ .srt/.txt ใน Download/LiveSub */
    var saveSubs: Boolean get() = b("saveSubs", true); set(v) = setB("saveSubs", v)

    /** อ่านซับไทยออกเสียง */
    var tts: Boolean get() = b("tts", false); set(v) = setB("tts", v)

    /** ใช้โมเดลเสียงตัวใหญ่ (แม่นกว่า กินเครื่องกว่า) */
    var largeModel: Boolean get() = b("largeModel", false); set(v) = setB("largeModel", v)

    /** คำศัพท์กำหนดเอง บรรทัดละ 1 กฎ: english=ไทย หรือ คำไทยที่แปลผิด=คำที่ถูก */
    var glossary: String
        get() = sp.getString("glossary", "") ?: ""
        set(v) = sp.edit().putString("glossary", v).apply()

    /** ตัวฟังเสียง: 0 = อัตโนมัติ (Google ในเครื่องถ้ามี ไม่งั้น Vosk), 1 = Vosk เท่านั้น */
    var engine: Int
        get() = sp.getInt("engine", 0)
        set(v) = sp.edit().putInt("engine", v).apply()

    /** แปลคำหยาบตรงตัว ไม่เซ็นเซอร์ */
    var uncensored: Boolean get() = b("uncensored", true); set(v) = setB("uncensored", v)

    /** โหมดคุณภาพสูงผ่านเน็ต (Gemini free tier) */
    var gemini: Boolean get() = b("gemini", false); set(v) = setB("gemini", v)
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()
    /** ชื่อโมเดล Gemini ที่เลือกอัตโนมัติไว้ (cache) */
    var geminiModel: String
        get() = sp.getString("geminiModel", "") ?: ""
        set(v) = sp.edit().putString("geminiModel", v).apply()
}
