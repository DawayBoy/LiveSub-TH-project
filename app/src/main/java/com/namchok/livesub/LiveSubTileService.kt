package com.namchok.livesub

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** ปุ่มในแถบเลื่อนลง (Quick Settings): แตะเดียว เริ่ม/หยุดแปล */
class LiveSubTileService : TileService() {

    companion object {
        /** ให้ service เรียกเมื่อเริ่ม/หยุด เพื่ออัปเดตสถานะปุ่ม */
        fun refresh(ctx: Context) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, LiveSubTileService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val t = qsTile ?: return
        t.state = if (CaptureService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "LiveSub"
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (CaptureService.running) "กำลังแปล" else "แปลซับ EN→TH"
        }
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        // การเริ่มดักเสียงต้องให้ผู้ใช้กดอนุญาตทุกครั้ง (กฎของ Android) จึงเปิดหน้าแอปสั้น ๆ
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
