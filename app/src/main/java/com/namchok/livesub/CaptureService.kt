package com.namchok.livesub

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.Locale

/**
 * ดักเสียงจากแอปวิดีโอ (YouTube, TikTok, Facebook, ไฟล์ในเครื่อง ฯลฯ)
 * -> Vosk แปลงเสียงเป็นข้อความอังกฤษ (ในเครื่อง)
 * -> ML Kit แปลเป็นไทย (ในเครื่อง) [+ Gemini ถ้าเปิดโหมดคุณภาพสูง]
 * -> แสดงซับลอยทับหน้าจอ / อ่านออกเสียง / บันทึกเป็นไฟล์
 */
class CaptureService : Service() {

    companion object {
        const val TAG = "LiveSub"
        const val ACTION_START = "com.namchok.livesub.START"
        const val ACTION_STOP = "com.namchok.livesub.STOP"
        const val ACTION_TOGGLE_MODE = "com.namchok.livesub.TOGGLE_MODE"
        const val ACTION_REFRESH = "com.namchok.livesub.REFRESH"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "livesub"
        private const val NOTIF_ID = 7
        private const val SAMPLE_RATE = 16000
        /** ถ้าพูดยาวเกินนี้ (คำ) โดยไม่หยุด จะตัดประโยคบังคับ กันซับค้างยาว */
        private const val FORCE_SPLIT_WORDS = 22
        /** โหมด Realtime: แปลประโยคที่ยังพูดไม่จบได้ถี่สุดทุกกี่ ms */
        private const val PARTIAL_TRANSLATE_INTERVAL_MS = 350L
        /** เงียบนานเท่านี้ ล้างซับ */
        private const val CLEAR_AFTER_MS = 8000L

        @Volatile var running = false
            private set
    }

    /** ประโยคที่จบแล้ว (th อาจถูกอัปเกรดเป็นผลจาก Gemini ทีหลัง) */
    private class Line(val en: String, var th: String, val entry: SubtitleLog.Entry?)

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var audioThread: Thread? = null
    private var model: Model? = null
    private var translator: Translator? = null
    @Volatile private var translatorReady = false
    private var gemini: GeminiTranslator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var overlay: OverlayController? = null
    private var log: SubtitleLog? = null
    private var sessionStart = 0L

    // สถานะข้อความ (ใช้บน main thread เท่านั้น)
    private val finals = ArrayDeque<Line>()      // เก็บ 2 ประโยคล่าสุด
    private var curEn = ""
    private var curTh = ""
    private var utterStart = -1L
    private var generation = 0
    private var lastPartialReq = 0L
    private var pendingPartial: String? = null

    private val clearRunnable = Runnable {
        finals.clear(); curEn = ""; curTh = ""
        render()
    }
    private val partialRunnable = Runnable { pendingPartial?.let { translatePartial(it) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onOrientationChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_MODE -> {
                Prefs.realtime = !Prefs.realtime
                overlay?.applyPrefs(); render(); updateNotification()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                overlay?.applyPrefs(); render(); updateNotification()
                applyOptionalFeatures()
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY

        // ต้องขึ้น foreground ก่อนขอ MediaProjection (Android 14+)
        startForegroundCompat()

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            toast("ไม่ได้รับสิทธิ์ดักเสียง")
            stopSelf(); return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (proj == null) {
            toast("เริ่มดักเสียงไม่ได้ ลองกดเริ่มใหม่")
            stopSelf(); return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        running = true
        LiveSubTileService.refresh(this)
        log = if (Prefs.saveSubs) SubtitleLog() else null

        overlay = OverlayController(
            this,
            onToggleMode = {
                Prefs.realtime = !Prefs.realtime
                overlay?.applyPrefs(); render(); updateNotification()
            },
            onToggleEnglish = {
                Prefs.showEnglish = !Prefs.showEnglish
                overlay?.applyPrefs(); render()
            },
            onClose = { stopSelf() }
        ).also {
            try { it.show() } catch (e: Exception) {
                toast("แสดงซับทับแอปไม่ได้ — เปิดสิทธิ์ 'แสดงทับแอปอื่น' ก่อน")
            }
        }
        overlay?.setTexts("", "กำลังเตรียมโมเดล…", "")

        setupTranslator()
        applyOptionalFeatures()
        loadSpeechModel()
        return START_NOT_STICKY
    }

    // ---------------- Speech model ----------------

    private fun loadSpeechModel() {
        if (Prefs.largeModel && ModelManager.isLargeReady(this)) {
            overlay?.setTexts("", "กำลังโหลดโมเดลแม่นยำสูง… (ครั้งละ ~10 วินาที)", "")
            val path = ModelManager.largeDir(this).absolutePath
            Thread({
                try {
                    val m = Model(path)
                    main.post { model = m; if (running) startAudio() else m.close() }
                } catch (e: Exception) {
                    Log.e(TAG, "large model", e)
                    main.post {
                        toast("โหลดโมเดลแม่นยำสูงไม่ได้ (แรมไม่พอ?) — ใช้โมเดลปกติแทน")
                        loadSmallModel()
                    }
                }
            }, "livesub-model").start()
        } else {
            loadSmallModel()
        }
    }

    private fun loadSmallModel() {
        StorageService.unpack(this, "model-en-us", "model",
            { m ->
                model = m
                if (running) startAudio() else m.close()
            },
            { e ->
                Log.e(TAG, "model unpack failed", e)
                overlay?.setTexts("", "โหลดโมเดลเสียงไม่สำเร็จ", e.message ?: "")
            })
    }

    // ---------------- Optional: Gemini / TTS ----------------

    private fun applyOptionalFeatures() {
        // Gemini
        if (Prefs.gemini && Prefs.geminiKey.isNotBlank()) {
            if (gemini == null) gemini = GeminiTranslator(Prefs.geminiKey)
        } else {
            gemini?.close(); gemini = null
        }
        // TTS
        if (Prefs.tts) {
            if (tts == null) {
                tts = TextToSpeech(this) { status ->
                    val t = tts
                    if (status == TextToSpeech.SUCCESS && t != null) {
                        val r = t.setLanguage(Locale("th", "TH"))
                        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                        // ใช้ช่องเสียง Accessibility -> ไม่ถูกดักกลับเข้ามาฟังซ้ำ
                        t.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        t.setSpeechRate(1.15f)
                        if (!ttsReady) toast("เครื่องยังไม่มีเสียงอ่านภาษาไทย: ตั้งค่า → ภาษา → เสียงอ่าน (Google) → ติดตั้งภาษาไทย")
                    }
                }
            }
        } else {
            tts?.stop(); tts?.shutdown(); tts = null; ttsReady = false
        }
    }

    private fun speak(th: String) {
        val t = tts ?: return
        if (!ttsReady || !Prefs.tts || th.isBlank()) return
        // ถ้าอ่านไม่ทัน ตัดของเก่าทิ้ง อ่านประโยคล่าสุดแทน (ไม่ให้เสียงพากย์ล้าหลังภาพ)
        val mode = if (t.isSpeaking) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        t.speak(th, mode, null, "ls${SystemClock.uptimeMillis()}")
    }

    // ---------------- Translation ----------------

    private fun setupTranslator() {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
        val t = Translation.getClient(opts)
        translator = t
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translatorReady = true }
            .addOnFailureListener { e ->
                Log.e(TAG, "translator model", e)
                toast("โหลดโมเดลแปลไทยไม่สำเร็จ — ต่อเน็ตแล้วเริ่มใหม่ (ระหว่างนี้แสดงอังกฤษอย่างเดียว)")
            }
    }

    /** แปลด้วย ML Kit (ในเครื่อง) + คำศัพท์กำหนดเอง */
    private fun translate(enRaw: String, cb: (String?) -> Unit) {
        val t = translator
        if (!translatorReady || t == null || enRaw.isBlank()) { cb(null); return }
        val en = Glossary.applyInput(enRaw)
        t.translate(en)
            .addOnSuccessListener { cb(Glossary.applyOutput(it)) }
            .addOnFailureListener { cb(null) }
    }

    // ---------------- Audio ----------------

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val proj = projection ?: return
        val m = model ?: return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 4, SAMPLE_RATE))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            rec.startRecording()
            record = rec
        } catch (e: Exception) {
            Log.e(TAG, "audio start", e)
            overlay?.setTexts("", "ดักเสียงไม่ได้", e.message ?: "")
            return
        }
        sessionStart = SystemClock.elapsedRealtime()
        overlay?.setTexts("", "", "")
        audioThread = Thread({ audioLoop(m) }, "livesub-audio").also { it.start() }
    }

    private fun audioLoop(m: Model) {
        val recognizer = Recognizer(m, SAMPLE_RATE.toFloat())
        val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
        var lastPartial = ""
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val rec = record ?: break
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (recognizer.acceptWaveForm(buf, n)) {
                    val text = field(recognizer.result, "text")
                    lastPartial = ""
                    if (text.isNotBlank()) main.post { onFinal(text) }
                } else {
                    val p = field(recognizer.partialResult, "partial")
                    if (p != lastPartial) {
                        lastPartial = p
                        if (p.split(' ').size >= FORCE_SPLIT_WORDS) {
                            val text = field(recognizer.finalResult, "text")
                            lastPartial = ""
                            if (text.isNotBlank()) main.post { onFinal(text) }
                        } else if (p.isNotBlank()) {
                            main.post { onPartial(p) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop", e)
        } finally {
            recognizer.close()
        }
    }

    private fun field(json: String, key: String): String =
        try { JSONObject(json).optString(key, "").trim() } catch (_: Exception) { "" }

    private fun now() = SystemClock.elapsedRealtime() - sessionStart

    // ---------------- Subtitle logic (main thread) ----------------

    private fun onPartial(p: String) {
        if (utterStart < 0) utterStart = maxOf(0L, now() - 600)
        curEn = p
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, CLEAR_AFTER_MS)
        if (Prefs.realtime) {
            val t = SystemClock.uptimeMillis()
            val wait = PARTIAL_TRANSLATE_INTERVAL_MS - (t - lastPartialReq)
            pendingPartial = p
            main.removeCallbacks(partialRunnable)
            if (wait <= 0) translatePartial(p) else main.postDelayed(partialRunnable, wait)
        }
        render()
    }

    private fun translatePartial(p: String) {
        lastPartialReq = SystemClock.uptimeMillis()
        pendingPartial = null
        val gen = generation
        translate(p) { th ->
            if (th != null && gen == generation && curEn.isNotBlank()) {
                curTh = th
                render()
            }
        }
    }

    private fun onFinal(text: String) {
        generation++
        val gen = generation
        val start = if (utterStart >= 0) utterStart else maxOf(0L, now() - 2000)
        val end = now()
        utterStart = -1
        main.removeCallbacks(partialRunnable)
        pendingPartial = null
        curEn = ""
        curTh = ""
        main.removeCallbacks(clearRunnable)
        main.postDelayed(clearRunnable, CLEAR_AFTER_MS)

        translate(text) { th ->
            // ถ้ามีประโยคใหม่จบแซงไปหลายประโยคแล้ว ไม่ต้องย้อนกลับมาแสดง
            if (gen < generation - 1) return@translate
            val thai = th ?: text
            val context = finals.map { it.en }
            val line = Line(text, thai, log?.add(start, end, text, thai))
            finals.addLast(line)
            while (finals.size > 2) finals.removeFirst()
            render()
            speak(if (th != null) thai else "")
            upgradeWithGemini(line, context)
        }
        render()
    }

    /** ส่งไป Gemini (ถ้าเปิด) แล้วแทนที่คำแปล ML Kit เมื่อได้ผลที่ดีกว่า */
    private fun upgradeWithGemini(line: Line, context: List<String>) {
        val g = gemini ?: return
        if (!g.canSend()) return
        g.translate(Glossary.applyInput(line.en), context, Glossary.hintText()) { better ->
            if (better.isNullOrBlank()) return@translate
            val fixed = Glossary.applyOutput(better)
            line.th = fixed
            line.entry?.th = fixed
            if (finals.contains(line)) render()
        }
    }

    private fun render() {
        val ov = overlay ?: return
        val last = finals.lastOrNull()
        val prev = if (finals.size >= 2) finals.first() else null
        if (Prefs.realtime) {
            if (curEn.isBlank()) {
                ov.setTexts(prev?.th ?: "", last?.th ?: "", last?.en ?: "")
            } else {
                val line = if (curTh.isNotBlank()) curTh else if (translatorReady) "…" else curEn
                ov.setTexts(last?.th ?: "", line, curEn)
            }
        } else {
            val en = if (curEn.isNotBlank()) "$curEn …" else last?.en ?: ""
            ov.setTexts(prev?.th ?: "", last?.th ?: "", en)
        }
    }

    // ---------------- Notification ----------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "LiveSub", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags
        )
        val modePi = PendingIntent.getService(
            this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_TOGGLE_MODE), flags
        )
        val openPi = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), flags
        )
        val mode = (if (Prefs.realtime) "โหมด Realtime" else "โหมดแม่นยำ") +
            (if (Prefs.gemini && Prefs.geminiKey.isNotBlank()) " + Gemini" else "")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LiveSub กำลังแปลซับ EN → TH")
            .setContentText(mode)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_rotate), "สลับโหมด", modePi
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "หยุด", stopPi
                ).build()
            )
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        audioThread?.interrupt()
        try { record?.stop() } catch (_: Exception) {}
        try { audioThread?.join(800) } catch (_: Exception) {}
        record?.release(); record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        translator?.close(); translator = null
        gemini?.close(); gemini = null
        tts?.stop(); tts?.shutdown(); tts = null
        model?.close(); model = null
        overlay?.remove(); overlay = null
        // บันทึกซับของรอบนี้
        try {
            log?.save(this)?.let { Toast.makeText(this, "บันทึกซับแล้ว: $it", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            Log.e(TAG, "save subs", e)
        }
        log = null
        LiveSubTileService.refresh(this)
        super.onDestroy()
    }
}
