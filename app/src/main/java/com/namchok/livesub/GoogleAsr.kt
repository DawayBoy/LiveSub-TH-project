package com.namchok.livesub

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * เครื่องฟังเสียงของ Google ในเครื่อง (ตัวเดียวกับคำบรรยายสดของ Android)
 * - ใส่วรรคตอน/ตัวพิมพ์ใหญ่ให้ แม่นกว่า Vosk ตัวเล็กมาก
 * - ต้อง Android 13+ และเครื่องมี "Speech Services by Google" ภาษาอังกฤษแบบออฟไลน์
 * ป้อนเสียงที่ดักได้เข้าไปทาง pipe (EXTRA_AUDIO_SOURCE) แบบ segmented session ต่อเนื่อง
 * ถ้าใช้ไม่ได้ จะเรียก onFatal ให้ service สลับกลับไปใช้ Vosk
 */
class GoogleAsr(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    companion object {
        fun isSupported(ctx: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 &&
                try { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) } catch (_: Exception) { false }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    private val queue = LinkedBlockingQueue<ByteArray>(60)   // ~6 วินาที
    private var writer: Thread? = null

    private var errorsInRow = 0
    private var everGotText = false
    private var startedAt = 0L
    private var downloadTriggered = false

    /** เรียกบน main thread */
    fun start() {
        if (Build.VERSION.SDK_INT < 33) { onFatal("ต้อง Android 13 ขึ้นไป"); return }
        val r = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (e: Exception) {
            onFatal("สร้างตัวฟังเสียง Google ไม่ได้"); return
        }
        recognizer = r
        r.setRecognitionListener(listener)
        startedAt = SystemClock.elapsedRealtime()
        writer = Thread({ writeLoop() }, "livesub-gasr-writer").also { it.start() }
        startSession()
    }

    private fun buildIntent(readFd: ParcelFileDescriptor): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            // ฟังต่อเนื่องไปเรื่อย ๆ จนกว่า pipe จะปิด แล้วส่งผลเป็นช่วง ๆ (onSegmentResults)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

    @SuppressLint("NewApi")
    private fun startSession() {
        if (stopped) return
        val r = recognizer ?: return
        closeStream()
        queue.clear()
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) {
            onFatal("สร้าง pipe ไม่ได้"); return
        }
        val intent = buildIntent(pipe[0])
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.e(CaptureService.TAG, "gasr start", e)
            pipe[0].close(); pipe[1].close()
            onFatal("เริ่มตัวฟังเสียง Google ไม่ได้"); return
        }
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        // ตัว service ได้สำเนา fd ไปแล้ว ปิดของเรา -> พอเซสชันจบ ฝั่งเขียนจะ error แล้วเริ่มเซสชันใหม่ได้
        try { pipe[0].close() } catch (_: Exception) {}
    }

    private fun closeStream() {
        val o = out
        out = null
        try { o?.close() } catch (_: Exception) {}
    }

    private fun restart(delayMs: Long) {
        if (stopped) return
        main.postDelayed({
            if (stopped) return@postDelayed
            try { recognizer?.cancel() } catch (_: Exception) {}
            startSession()
        }, delayMs)
    }

    /** เรียกจาก audio thread: ใส่คิวไว้ ถ้าคิวเต็มทิ้งของเก่า (ไม่ให้ audio thread ค้าง) */
    fun feed(buf: ShortArray, n: Int) {
        if (stopped) return
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = buf[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        if (!queue.offer(bytes)) { queue.poll(); queue.offer(bytes) }
    }

    private fun writeLoop() {
        while (!stopped) {
            val chunk = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            val o = out ?: continue
            try {
                o.write(chunk)
            } catch (e: IOException) {
                if (out === o) out = null   // เซสชันนี้ปิดไปแล้ว รอเซสชันใหม่
            }
        }
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (t.isNotEmpty()) { everGotText = true; errorsInRow = 0; onPartial(t) }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val t = first(segmentResults)
            if (t.isNotEmpty()) { everGotText = true; errorsInRow = 0; onFinal(t) }
        }

        override fun onEndOfSegmentedSession() { restart(50) }

        override fun onResults(results: Bundle?) {
            val t = first(results)
            if (t.isNotEmpty()) { everGotText = true; errorsInRow = 0; onFinal(t) }
            restart(50)
        }

        override fun onError(error: Int) {
            Log.w(CaptureService.TAG, "gasr error $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(600)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (!downloadTriggered && Build.VERSION.SDK_INT >= 33) {
                        downloadTriggered = true
                        try {
                            recognizer?.triggerModelDownload(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                            )
                        } catch (_: Exception) {}
                    }
                    onFatal("เครื่องยังไม่มีภาษาอังกฤษแบบออฟไลน์ของ Google (กำลังสั่งดาวน์โหลด)")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onFatal("ตัวฟังเสียง Google ไม่อนุญาตให้ใช้ (สิทธิ์ไม่พอ)")
                else -> {
                    errorsInRow++
                    val early = !everGotText && SystemClock.elapsedRealtime() - startedAt < 20_000
                    if (errorsInRow >= 4 || (early && errorsInRow >= 3)) {
                        onFatal("ตัวฟังเสียง Google ใช้ไม่ได้บนเครื่องนี้ (error $error)")
                    } else restart(300L * errorsInRow)
                }
            }
        }
    }

    /** เรียกบน main thread */
    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        closeStream()
        writer?.interrupt()
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
}
