package com.mangalens.th

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_LANGUAGE = "language"
        private const val CHANNEL = "mangalens_capture"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: Handler? = null
    private var thread: HandlerThread? = null
    private var windowManager: WindowManager? = null
    private val overlays = mutableListOf<TextView>()
    private val processing = AtomicBoolean(false)
    private var lastRun = 0L
    private var selectedLanguage = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1

    // Translation state (used on the main thread only)
    private val translators = HashMap<String, Translator>()
    private val readySources = HashSet<String>()
    private var generation = 0
    private var noticeShown = false
    private var lastToast = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, notification())
        }
        if (projection == null && intent != null) {
            selectedLanguage = intent.getIntExtra(EXTRA_LANGUAGE, 0)
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_DATA) as? Intent)
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            startProjection(code, data)
        }
        return START_NOT_STICKY
    }

    private fun startProjection(code: Int, data: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.densityDpi

        thread = HandlerThread("MangaLensOCR").also { it.start() }
        worker = Handler(thread!!.looper)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastRun < 1200 || !processing.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastRun = now
            val image = source.acquireLatestImage()
            if (image == null) { processing.set(false); return@setOnImageAvailableListener }
            try {
                val bitmap = imageToBitmap(image)
                image.close()
                runOcr(bitmap)
            } catch (_: Exception) {
                image.close()
                processing.set(false)
            }
        }, worker)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, worker)

        virtualDisplay = projection?.createVirtualDisplay(
            "MangaLensTH", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, worker
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val padded = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
        padded.recycle()
        return cropped
    }

    private fun runOcr(bitmap: Bitmap) {
        val recognizer = when (selectedLanguage) {
            1 -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            2 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            3 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> showOverlay(result) }
            .addOnCompleteListener {
                recognizer.close()
                bitmap.recycle()
                processing.set(false)
            }
    }

    // Pick the source language of one OCR line from its script.
    private fun detectSource(text: String): String {
        for (ch in text) {
            val c = ch.code
            if (c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F) {
                return TranslateLanguage.KOREAN
            }
        }
        for (ch in text) {
            val c = ch.code
            if (c in 0x4E00..0x9FFF) return TranslateLanguage.CHINESE
        }
        return TranslateLanguage.ENGLISH
    }

    private fun translatorFor(source: String): Translator {
        return translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.THAI)
                    .build()
            )
        }
    }

    private fun toast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToast < 8000) return
        lastToast = now
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showOverlay(result: Text) {
        clearOverlays()
        generation++
        val current = generation
        if (!noticeShown && result.textBlocks.isNotEmpty()) {
            noticeShown = true
            toast("กำลังเตรียมโมเดลแปลไทย (ครั้งแรกต้องต่อเน็ต)")
        }
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val original = line.text.trim()
                if (original.isEmpty()) continue
                val source = detectSource(original)
                val translator = translatorFor(source)
                if (source in readySources) {
                    translateLine(translator, original, box, current)
                } else {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            readySources.add(source)
                            translateLine(translator, original, box, current)
                        }
                        .addOnFailureListener {
                            toast("โหลดโมเดลแปลไม่สำเร็จ ลองต่อเน็ตแล้วเริ่มใหม่")
                        }
                }
            }
        }
    }

    private fun translateLine(translator: Translator, original: String, box: Rect, gen: Int) {
        translator.translate(original).addOnSuccessListener { thai ->
            if (gen == generation) addLabel(box, thai)
        }
    }

    private fun addLabel(box: Rect, translated: String) {
        val label = TextView(this).apply {
            text = translated
            textSize = 13f
            setTextColor(0xFF171717.toInt())
            setBackgroundColor(0xEFFFFFFF.toInt())
            setPadding(5, 2, 5, 2)
            elevation = 8f
        }
        val params = WindowManager.LayoutParams(
            box.width().coerceAtLeast(60),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = box.left
            y = box.top
        }
        try {
            windowManager?.addView(label, params)
            overlays.add(label)
        } catch (_: Exception) { }
    }

    private fun clearOverlays() {
        overlays.forEach { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlays.clear()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "MangaLens screen translation",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("MangaLens TH กำลังทำงาน")
            .setContentText("กำลังตรวจจับข้อความบนหน้าจอ")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        generation++
        clearOverlays()
        translators.values.forEach { it.close() }
        translators.clear()
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }
}
