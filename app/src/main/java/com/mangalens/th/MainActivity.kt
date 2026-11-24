package com.mangalens.th

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val requestCapture = 4101
    private lateinit var language: Spinner
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 24)
        }
        val title = TextView(this).apply {
            text = "MangaLens TH"
            textSize = 28f
            setTextColor(0xFF7651D6.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "AI Manga Translator • รุ่นทดลอง 0.1"
            textSize = 14f
            setPadding(0, 4, 0, 24)
        }
        language = Spinner(this)
        language.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("ตรวจจับอัตโนมัติ (อังกฤษ/เกาหลี/จีน)", "อังกฤษ", "เกาหลี", "จีนตัวย่อ")
        )
        status = TextView(this).apply {
            text = "เลือกภาษา แล้วกดเริ่มจับหน้าจอ"
            textSize = 14f
            setPadding(0, 20, 0, 20)
        }
        val start = Button(this).apply {
            text = "เริ่มแปลหน้าจอ"
            setOnClickListener { beginCapture() }
        }
        val stop = Button(this).apply {
            text = "หยุดแปล"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "หยุดการแปลแล้ว"
            }
        }
        val overlay = Button(this).apply {
            text = "อนุญาตให้แสดงคำแปลทับแอปอื่น"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                    startActivity(i)
                } else Toast.makeText(this@MainActivity, "อนุญาตแล้ว", Toast.LENGTH_SHORT).show()
            }
        }
        val note = TextView(this).apply {
            text = "วิธีใช้: อนุญาตหน้าต่างลอย → เริ่มแปลหน้าจอ → อนุญาตจับภาพหน้าจอ → สลับไปเว็บมังฮวา\\n\\nรุ่นนี้เป็นต้นแบบทดสอบ OCR และ overlay การแปลออฟไลน์/AI จะพัฒนาเพิ่มในรุ่นถัดไป"
            textSize = 13f
            setPadding(0, 22, 0, 0)
        }
        layout.addView(title); layout.addView(subtitle); layout.addView(language)
        layout.addView(overlay); layout.addView(start); layout.addView(stop)
        layout.addView(status); layout.addView(note)
        setContentView(layout)
    }

    private fun beginCapture() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "กรุณาอนุญาตหน้าต่างลอยก่อน"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
    }

    @Deprecated("Deprecated by Android, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, data)
                putExtra(CaptureService.EXTRA_LANGUAGE, language.selectedItemPosition)
            }
            ContextCompat.startForegroundService(this, i)
            status.text = "กำลังจับหน้าจอ — สลับไปยังเว็บมังฮวาได้"
        } else {
            status.text = "ยังไม่ได้อนุญาตจับภาพหน้าจอ"
        }
    }
}
