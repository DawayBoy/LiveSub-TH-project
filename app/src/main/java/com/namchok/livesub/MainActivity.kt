package com.namchok.livesub

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    private val REQ_CAPTURE = 100
    private val REQ_PERMS = 101

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnLarge: Button
    private lateinit var swLarge: Switch
    private lateinit var tvGemini: TextView
    private var translator: Translator? = null
    private var thaiModelReady = false
    private var thaiModelMsg = "กำลังตรวจโมเดลแปลไทย…"
    /** เปิดมาจากปุ่ม Quick Settings: ขอสิทธิ์แล้วเริ่มเลย จากนั้นหลบไปข้างหลัง */
    private var fromTile = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        buildUi()
        prepareThaiModel()
        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    private fun handleAutostart(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            i.removeExtra(EXTRA_AUTOSTART)
            fromTile = true
            if (!CaptureService.running) btnStart.post { onStartStop() }
        }
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        fun text(s: String, size: Float = 15f, color: Int = Color.WHITE) = TextView(this).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun header(s: String) = text(s, 17f, Color.rgb(129, 212, 250)).apply {
            setPadding(0, dp(20), 0, dp(4))
        }
        fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
            text = label; setTextColor(Color.WHITE); isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        fun button(label: String, onClick: (Button) -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick(this) }
        }

        col.addView(text("LiveSub TH", 26f))
        col.addView(text("แปลเสียงวิดีโอ อังกฤษ → ไทย เป็นซับลอยบนจอ (ฟรี ทำงานในเครื่อง)", 14f, Color.LTGRAY))

        tvStatus = text("", 14f, Color.rgb(255, 213, 79))
        col.addView(tvStatus)

        btnStart = Button(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setOnClickListener { onStartStop() }
        }
        col.addView(btnStart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
        ).apply { topMargin = dp(8) })
        col.addView(text("💡 ทางลัด: ปัดแถบด้านบนลง → แก้ไขปุ่ม → ลากปุ่ม \"LiveSub\" มาไว้ แตะเดียวเริ่ม/หยุดได้เลย",
            13f, Color.LTGRAY))

        // ----- การแสดงผล -----
        col.addView(header("การแสดงผล"))
        col.addView(switch("⚡ โหมด Realtime (ขึ้นซับทันทีระหว่างพูด)\nปิด = โหมดแม่นยำ รอจบประโยคค่อยแปล", Prefs.realtime) {
            Prefs.realtime = it; refreshService()
        })
        col.addView(switch("แสดงภาษาอังกฤษใต้ภาษาไทย", Prefs.showEnglish) {
            Prefs.showEnglish = it; refreshService()
        })
        col.addView(button("พื้นหลังซับ: ${Prefs.bgStyleName()}  (แตะเพื่อเปลี่ยน)") { b ->
            Prefs.bgStyle = (Prefs.bgStyle + 1) % 3
            b.text = "พื้นหลังซับ: ${Prefs.bgStyleName()}  (แตะเพื่อเปลี่ยน)"
            refreshService()
        })
        val tvSize = text("ขนาดตัวอักษร: ${Prefs.fontSize}")
        col.addView(tvSize)
        col.addView(SeekBar(this).apply {
            max = 22   // 14..36
            progress = Prefs.fontSize - 14
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    Prefs.fontSize = p + 14
                    tvSize.text = "ขนาดตัวอักษร: ${Prefs.fontSize}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { refreshService() }
            })
        })
        col.addView(text("ตำแหน่งซับ: ลากที่ซับขึ้นลงได้ แอปจำแยกแนวตั้ง/แนวนอนให้เอง", 13f, Color.LTGRAY))

        // ----- ฟีเจอร์เสริม -----
        col.addView(header("ฟีเจอร์เสริม"))
        col.addView(switch("💾 บันทึกซับเป็นไฟล์ (.srt + .txt) ไว้ที่ Download/LiveSub", Prefs.saveSubs) {
            Prefs.saveSubs = it
        })
        col.addView(switch("🔊 อ่านซับไทยออกเสียง (ใช้เสียงอ่านในเครื่อง ฟรี)", Prefs.tts) {
            Prefs.tts = it; refreshService()
        })
        col.addView(button("📖 คำศัพท์กำหนดเอง (${glossaryCount()} คำ)") { b -> editGlossary(b) })

        // ----- ความแม่นยำ -----
        col.addView(header("ความแม่นยำ"))
        swLarge = switch("🎯 ใช้โมเดลฟังเสียงตัวใหญ่ (แม่นขึ้น กินแรม/แบตมากขึ้น)", Prefs.largeModel) { on ->
            if (on && !ModelManager.isLargeReady(this)) {
                swLarge.isChecked = false
                Toast.makeText(this, "ดาวน์โหลดโมเดลตัวใหญ่ก่อน (ปุ่มด้านล่าง)", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.largeModel = on
                if (CaptureService.running) Toast.makeText(this, "มีผลเมื่อเริ่มแปลรอบถัดไป", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(swLarge)
        btnLarge = button("") { onLargeModelButton() }
        col.addView(btnLarge)

        col.addView(switch("☁️ โหมดคุณภาพสูงผ่านเน็ต (Gemini ฟรี)\nแปลเป็นธรรมชาติกว่า ใช้เน็ต มีโควตาต่อวัน", Prefs.gemini) {
            Prefs.gemini = it; refreshService(); updateGeminiText()
        })
        tvGemini = text("", 13f, Color.LTGRAY)
        col.addView(tvGemini)
        col.addView(button("ใส่ / ทดสอบ Gemini API key") { editGeminiKey() })
        col.addView(button("รับ API key ฟรี (Google AI Studio)") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        })

        // ----- สิทธิ์ -----
        col.addView(header("สิทธิ์และการตั้งค่าเครื่อง"))
        col.addView(button("เปิดสิทธิ์ 'แสดงทับแอปอื่น'") { openOverlaySettings() })
        col.addView(button("ตั้งค่าแอป (Xiaomi: เปิด Autostart / ไม่จำกัดแบตเตอรี่)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(text(
            "\nวิธีใช้:\n" +
            "1. กด เริ่มแปล → อนุญาตทุกอย่าง (ถ้ามีให้เลือก ให้เลือก 'ทั้งหน้าจอ')\n" +
            "2. เปิดวิดีโอในแอปไหนก็ได้ ซับจะขึ้นเอง\n" +
            "3. ลากซับขึ้นลงได้ / แตะที่ซับเพื่อเรียกปุ่ม: ⚡ สลับโหมด, ◐ เปลี่ยนพื้นหลัง, ✕ หยุด\n\n" +
            "หมายเหตุ: Netflix, Disney+ และแอปที่มีลิขสิทธิ์บางแอปล็อกเสียงไว้ จะดักเสียงไม่ได้",
            13f, Color.LTGRAY))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 18))
            addView(col)
        })
        updateLargeButton()
        updateGeminiText()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        btnStart.text = if (CaptureService.running) "■ หยุดแปล" else "▶ เริ่มแปล"
        val lines = mutableListOf<String>()
        lines += if (overlayOk) "✓ สิทธิ์แสดงทับแอปอื่น" else "✗ ยังไม่ได้เปิดสิทธิ์แสดงทับแอปอื่น"
        lines += (if (thaiModelReady) "✓ " else "… ") + thaiModelMsg
        lines += if (CaptureService.running) "● กำลังทำงาน" else "○ หยุดอยู่"
        tvStatus.text = lines.joinToString("\n")
    }

    // ---------------- Glossary ----------------

    private fun glossaryCount() =
        Prefs.glossary.lines().count { it.contains('=') && !it.trim().startsWith("#") }

    private fun editGlossary(b: Button) {
        val et = EditText(this).apply {
            setText(Prefs.glossary)
            hint = "บรรทัดละ 1 คำ เช่น\nbreaker=เบรกเกอร์\nvin diesel=วิน ดีเซล\nทับทิม=Ruby"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("คำศัพท์กำหนดเอง")
            .setMessage("• อังกฤษ=ไทย → แปลคำนี้ตามที่กำหนด\n• คำไทยที่แปลผิด=คำที่ถูก → แก้ในผลแปล")
            .setView(et)
            .setPositiveButton("บันทึก") { _, _ ->
                Prefs.glossary = et.text.toString()
                b.text = "📖 คำศัพท์กำหนดเอง (${glossaryCount()} คำ)"
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    // ---------------- Large model ----------------

    private fun updateLargeButton() {
        btnLarge.isEnabled = !ModelManager.downloading
        btnLarge.text = when {
            ModelManager.downloading -> "กำลังดาวน์โหลด…"
            ModelManager.isLargeReady(this) -> "ลบโมเดลตัวใหญ่ (คืนพื้นที่ ~300MB)"
            else -> "ดาวน์โหลดโมเดลตัวใหญ่ (${ModelManager.LARGE_SIZE_MB}MB — แนะนำใช้ Wi-Fi)"
        }
    }

    private fun onLargeModelButton() {
        if (ModelManager.isLargeReady(this)) {
            AlertDialog.Builder(this)
                .setMessage("ลบโมเดลตัวใหญ่? (ดาวน์โหลดใหม่ได้ภายหลัง)")
                .setPositiveButton("ลบ") { _, _ ->
                    ModelManager.deleteLarge(this)
                    Prefs.largeModel = false
                    swLarge.isChecked = false
                    updateLargeButton()
                }
                .setNegativeButton("ยกเลิก", null).show()
            return
        }
        ModelManager.downloadLarge(this,
            progress = { pct ->
                runOnUiThread {
                    btnLarge.text = if (pct < 90) "กำลังดาวน์โหลด… $pct%" else "กำลังแตกไฟล์…"
                }
            },
            done = { err ->
                runOnUiThread {
                    if (err == null) {
                        Prefs.largeModel = true
                        swLarge.isChecked = true
                        Toast.makeText(this, "โมเดลตัวใหญ่พร้อมใช้", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "ดาวน์โหลดไม่สำเร็จ: $err", Toast.LENGTH_LONG).show()
                    }
                    updateLargeButton()
                }
            })
        updateLargeButton()
    }

    // ---------------- Gemini ----------------

    private fun updateGeminiText() {
        tvGemini.text = when {
            Prefs.geminiKey.isBlank() -> "ยังไม่ได้ใส่ API key (ฟรี ไม่ต้องผูกบัตร) — ถ้าไม่มี key จะใช้แปลในเครื่องตามปกติ"
            Prefs.geminiModel.isNotBlank() -> "✓ ใส่ key แล้ว ใช้โมเดล ${Prefs.geminiModel}"
            else -> "ใส่ key แล้ว (ยังไม่ได้ทดสอบ)"
        } + "\nหมายเหตุ: ข้อความที่ส่งไป Gemini แบบฟรี Google อาจนำไปใช้ปรับปรุงบริการ"
    }

    private fun editGeminiKey() {
        val et = EditText(this).apply {
            setText(Prefs.geminiKey)
            hint = "วาง API key ที่นี่ (ขึ้นต้นด้วย AIza…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setView(et)
            .setPositiveButton("บันทึกและทดสอบ") { _, _ ->
                Prefs.geminiKey = et.text.toString()
                Prefs.geminiModel = ""
                updateGeminiText()
                if (Prefs.geminiKey.isBlank()) return@setPositiveButton
                tvGemini.text = "กำลังทดสอบ key…"
                val g = GeminiTranslator(Prefs.geminiKey)
                g.test { model ->
                    if (model != null) {
                        Toast.makeText(this, "ใช้ได้! โมเดล: $model", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "key ใช้ไม่ได้: ${g.lastError ?: "ไม่พบโมเดล"}", Toast.LENGTH_LONG).show()
                    }
                    g.close()
                    updateGeminiText()
                    refreshService()
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    // ---------------- Thai model / service ----------------

    private fun prepareThaiModel() {
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build()
        )
        translator = t
        thaiModelMsg = "กำลังดาวน์โหลดโมเดลแปลไทย (~30MB ครั้งแรกครั้งเดียว)…"
        updateStatus()
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                thaiModelReady = true
                thaiModelMsg = "โมเดลแปลไทยพร้อม (ใช้ออฟไลน์ได้)"
                updateStatus()
            }
            .addOnFailureListener {
                thaiModelMsg = "โหลดโมเดลแปลไทยไม่สำเร็จ — ต่อเน็ตแล้วเปิดแอปใหม่"
                updateStatus()
            }
    }

    private fun refreshService() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH))
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun onStartStop() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            btnStart.postDelayed({ updateStatus() }, 300)
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMS)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "เปิดสิทธิ์ 'แสดงทับแอปอื่น' ให้ LiveSub TH แล้วกลับมากดเริ่มอีกครั้ง",
                Toast.LENGTH_LONG).show()
            fromTile = false
            openOverlaySettings()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onStartStop()
            } else {
                Toast.makeText(this, "ต้องอนุญาต 'ไมโครโฟน/บันทึกเสียง' เพื่อดักเสียงวิดีโอ", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "เริ่มแล้ว — เปิดวิดีโอได้เลย", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "ยกเลิก", Toast.LENGTH_SHORT).show()
            if (fromTile) moveTaskToBack(true)
        }
        fromTile = false
        updateStatus()
    }

    override fun onDestroy() {
        translator?.close()
        super.onDestroy()
    }
}
