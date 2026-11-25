package com.namchok.livesub

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * กล่องซับลอยทับทุกแอป
 * - พื้นหลัง 3 แบบ: ใส (ตัวขาวขอบดำแบบซับหนัง) / กึ่งใส / ทึบ
 * - แตะที่ซับ = แสดง/ซ่อนปุ่มควบคุม (ซ่อนเองใน 4 วินาที), ลาก = ย้ายตำแหน่ง
 * บรรทัด 1: ประโยคก่อนหน้า (จาง)  บรรทัด 2: ภาษาไทยปัจจุบัน  บรรทัด 3: อังกฤษ (เหลือง)
 */
class OverlayController(
    private val ctx: Context,
    private val onToggleMode: () -> Unit,
    private val onToggleEnglish: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())

    private val root = LinearLayout(ctx)
    private val bar = LinearLayout(ctx)
    private val tvDim = OutlineTextView(ctx)
    private val tvMain = OutlineTextView(ctx)
    private val tvEn = OutlineTextView(ctx)
    private val btnMode = TextView(ctx)
    private val btnEn = TextView(ctx)
    private val btnBg = TextView(ctx)
    private val btnClose = TextView(ctx)
    private var shown = false
    private val hideBar = Runnable { bar.visibility = View.GONE }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(6), dp(12), dp(8))

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.END
        listOf(btnMode, btnEn, btnBg, btnClose).forEach { b ->
            b.setTextColor(Color.WHITE)
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            b.setPadding(dp(10), dp(5), dp(10), dp(5))
            b.background = GradientDrawable().apply {
                setColor(Color.argb(150, 40, 40, 40))
                cornerRadius = dp(12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            bar.addView(b, lp)
        }
        btnClose.text = "✕"
        btnMode.setOnClickListener { onToggleMode(); pokeBar() }
        btnEn.setOnClickListener { onToggleEnglish(); pokeBar() }
        btnBg.setOnClickListener {
            Prefs.bgStyle = (Prefs.bgStyle + 1) % 3
            applyPrefs(); pokeBar()
        }
        btnClose.setOnClickListener { onClose() }

        tvMain.setTextColor(Color.WHITE)
        tvDim.setTextColor(Color.argb(200, 255, 255, 255))
        tvEn.setTextColor(Color.rgb(255, 213, 79))
        listOf(tvDim, tvMain, tvEn).forEach { it.gravity = Gravity.CENTER_HORIZONTAL }

        root.addView(bar)
        root.addView(tvDim)
        root.addView(tvMain)
        root.addView(tvEn)
        enableTouch()
        applyPrefs()
    }

    /** แตะ = โชว์/ซ่อนปุ่ม, ลาก = ย้ายกล่อง */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableTouch() {
        var downRawY = 0f
        var startY = 0
        var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (abs(dy) > dp(8)) moved = true
                    if (moved) {
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        if (shown) wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) savePosition()
                    else if (bar.visibility == View.VISIBLE) { main.removeCallbacks(hideBar); bar.visibility = View.GONE }
                    else pokeBar()
                    true
                }
                else -> false
            }
        }
    }

    /** แสดงแถบปุ่ม แล้วซ่อนเองใน 4 วินาที */
    private fun pokeBar() {
        bar.visibility = View.VISIBLE
        main.removeCallbacks(hideBar)
        main.postDelayed(hideBar, 4000)
    }

    fun applyPrefs() {
        val size = Prefs.fontSize.toFloat()
        tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tvDim.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.8f)
        tvEn.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.72f)

        val style = Prefs.bgStyle
        val bgAlpha = when (style) { 0 -> 0; 1 -> 110; else -> 200 }
        root.background = GradientDrawable().apply {
            setColor(Color.argb(bgAlpha, 0, 0, 0))
            cornerRadius = dp(12).toFloat()
        }
        // พื้นใส = ขอบดำหนาขึ้นให้อ่านออกทุกฉาก
        val outline = when (style) { 0 -> size * density * 0.16f; 1 -> size * density * 0.08f; else -> 0f }
        listOf(tvDim, tvMain, tvEn).forEach {
            it.outlineWidth = outline
            it.setShadowLayer(if (style == 2) 0f else 3f, 1f, 1f, Color.BLACK)
        }

        btnMode.text = if (Prefs.realtime) "⚡ Realtime" else "🎯 แม่นยำ"
        btnEn.text = if (Prefs.showEnglish) "EN ✓" else "EN ✗"
        btnBg.text = "◐ " + Prefs.bgStyleName(style)
        tvEn.visibility = if (Prefs.showEnglish && tvEn.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun isLandscape() =
        ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun screenH() = ctx.resources.displayMetrics.heightPixels

    /** จำตำแหน่งแยกแนวตั้ง/แนวนอน เป็นสัดส่วนของความสูงจอ */
    private fun savePosition() {
        Prefs.setPosFrac(isLandscape(), (params.y.toFloat() / screenH()).coerceIn(0f, 0.95f))
    }

    private fun restorePosition() {
        params.y = (Prefs.posFrac(isLandscape()) * screenH()).toInt()
    }

    /** เรียกเมื่อหมุนจอ */
    fun onOrientationChanged() {
        restorePosition()
        if (shown) try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    fun show() {
        if (shown) return
        restorePosition()
        wm.addView(root, params)
        shown = true
        pokeBar()   // ครั้งแรกให้เห็นปุ่มก่อน
    }

    /** dim = บรรทัดจาง, main = ภาษาไทยหลัก, en = อังกฤษ */
    fun setTexts(dim: String, main: String, en: String) {
        tvDim.text = dim
        tvDim.visibility = if (dim.isBlank()) View.GONE else View.VISIBLE
        // ไม่มีเสียงพูด: พื้นใสโชว์แค่ไอคอนเล็ก ๆ ไว้ให้แตะเรียกปุ่มได้
        tvMain.text = main.ifBlank { if (Prefs.bgStyle == 0) "🎧" else "🎧 กำลังฟัง…" }
        tvMain.alpha = if (main.isBlank()) 0.4f else 1f
        tvEn.text = en
        tvEn.visibility = if (Prefs.showEnglish && en.isNotBlank()) View.VISIBLE else View.GONE
    }

    fun remove() {
        main.removeCallbacks(hideBar)
        if (shown) {
            try { wm.removeView(root) } catch (_: Exception) {}
            shown = false
        }
    }
}
