package com.namchok.livesub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

/** TextView ที่มีขอบตัวอักษร (ตัวขาวขอบดำแบบซับหนัง) อ่านได้บนพื้นหลังใส */
class OutlineTextView(ctx: Context) : TextView(ctx) {

    /** ความหนาขอบ (px) 0 = ไม่มีขอบ */
    var outlineWidth = 0f
        set(v) { field = v; super.invalidate() }
    var outlineColor = Color.BLACK

    private var drawingOutline = false

    // setTextColor() เรียก invalidate() ทุกครั้ง — กันวนลูปตอนวาดขอบ
    override fun invalidate() {
        if (!drawingOutline) super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f) {
            val fill = textColors
            drawingOutline = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
            paint.strokeJoin = Paint.Join.ROUND
            setTextColor(outlineColor)
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            setTextColor(fill)
            drawingOutline = false
        }
        super.onDraw(canvas)
    }
}
