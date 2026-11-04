package com.omarpriv.smoothflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var selectedVideo: Uri? = null
    private var lastOutput: Uri? = null
    private lateinit var selectedLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fpsSpinner: Spinner
    private lateinit var processButton: Button
    private lateinit var pickButton: Button
    private lateinit var openButton: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedVideo = uri
            selectedLabel.text = displayName(uri)
            statusLabel.text = "الفيديو جاهز. اختار معدل الإطارات وابدأ."
            progressBar.progress = 0
            openButton.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 17, 31)
        window.navigationBarColor = Color.rgb(7, 17, 31)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 17, 31), Color.rgb(12, 32, 48))
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        root.addView(text("SMOOTHFLOW  /  FRAME INTERPOLATION", 11, 0xFF6DE7D5.toInt(), true))
        root.addView(space(14))
        root.addView(text("خلّي الحركة أنعم", 30, Color.WHITE, true))
        root.addView(space(8))
        root.addView(text(
            "ضاعف الإطارات باستيفاء حقيقي باستخدام RIFE، مع معالجة الفيديو على جهازك.",
            15, 0xFFB9C9D8.toInt(), false
        ))
        root.addView(space(26))

        val videoCard = card()
        root.addView(videoCard, matchWrap())
        videoCard.addView(text("الفيديو", 18, Color.WHITE, true))
        videoCard.addView(space(10))
        selectedLabel = text("ما اخترت فيديو لسه", 14, 0xFFB9C9D8.toInt(), false)
        videoCard.addView(selectedLabel)
        videoCard.addView(space(16))
        pickButton = button("اختيار فيديو", 0xFF1E5262.toInt(), Color.WHITE)
        videoCard.addView(pickButton, matchWrap())
        pickButton.setOnClickListener {
            picker.launch(arrayOf("video/mp4", "video/*"))
        }

        root.addView(space(18))
        val optionsCard = card()
        root.addView(optionsCard, matchWrap())
        optionsCard.addView(text("معدل الإطارات الناتج", 18, Color.WHITE, true))
        optionsCard.addView(space(8))
        fpsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("50 FPS  ·  لفيديو 25 FPS", "60 FPS  ·  لفيديو 30 FPS")
            )
            setSelection(1)
        }
        optionsCard.addView(fpsSpinner, matchWrap())
        optionsCard.addView(space(8))
        optionsCard.addView(text(
            "حالياً التحويل الدقيق المدعوم هو 25→50 أو 30→60.",
            12, 0xFF90A8BB.toInt(), false
        ))

        root.addView(space(18))
        processButton = button("ابدأ الاستيفاء الحقيقي", 0xFF38D6C2.toInt(), 0xFF06232B.toInt())
        root.addView(processButton, matchWrap())
        processButton.setOnClickListener { requestPermissionThenProcess() }

        root.addView(space(18))
        val progressCard = card()
        root.addView(progressCard, matchWrap())
        statusLabel = text("اختار فيديو عشان نبدأ.", 14, Color.WHITE, false)
        progressCard.addView(statusLabel)
        progressCard.addView(space(12))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        progressCard.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
        ))
        progressCard.addView(space(8))
        progressCard.addView(text(
            "لأداء أفضل على الهواتف المتوسطة، ابدأ بمقطع قصير 5–10 ثواني.",
            12, 0xFF90A8BB.toInt(), false
        ))

        root.addView(space(14))
        openButton = button("فتح الفيديو الناتج", 0xFF25475A.toInt(), Color.WHITE).apply {
            visibility = View.GONE
        }
        root.addView(openButton, matchWrap())
        openButton.setOnClickListener { openOutput() }

        root.addView(space(24))
        root.addView(text(
            "المعالجة محلية. ملفات RIFE تُنزّل أول مرة فقط (حوالي 54 MB).",
            12, 0xFF7892A7.toInt(), false
        ))
    }

    private fun requestPermissionThenProcess() {
        if (selectedVideo == null) {
            statusLabel.text = "اختار فيديو بالأول."
            return
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 900)
            return
        }
        startProcessing()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 900) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startProcessing()
            } else {
                statusLabel.text = "لازم تسمح بحفظ الفيديو داخل Movies/SmoothFlow."
            }
        }
    }

    private fun startProcessing() {
        val uri = selectedVideo ?: return
        val targetFps = if (fpsSpinner.selectedItemPosition == 0) 50 else 60
        setBusy(true)
        progressBar.progress = 0
        statusLabel.text = "جاري تجهيز المعالجة..."

        executor.execute {
            try {
                val result = VideoProcessor(this).process(uri, targetFps) { progress, message ->
                    runOnUiThread {
                        progressBar.progress = progress
                        statusLabel.text = message
                    }
                }
                lastOutput = result.uri
                runOnUiThread {
                    setBusy(false)
                    progressBar.progress = 100
                    statusLabel.text =
                        "اكتمل الفيديو: ${result.outputFps} FPS · ${result.width}×${result.height}"
                    openButton.visibility = View.VISIBLE
                }
            } catch (error: Throwable) {
                val message = error.message ?: "صار خطأ غير معروف"
                runOnUiThread {
                    setBusy(false)
                    statusLabel.text = "ما اكتملت المعالجة: $message"
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        processButton.isEnabled = !busy
        pickButton.isEnabled = !busy
        fpsSpinner.isEnabled = !busy
        processButton.text = if (busy) "جاري المعالجة..." else "ابدأ الاستيفاء الحقيقي"
    }

    private fun displayName(uri: Uri): String {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else uri.lastPathSegment
        } ?: (uri.lastPathSegment ?: "video.mp4")
    }

    private fun openOutput() {
        val uri = lastOutput ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { statusLabel.text = "الفيديو محفوظ في مجلد Movies/SmoothFlow." }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(0xFF13283A.toInt(), dp(18))
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD)
                else Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
        }

    private fun button(value: String, backgroundColor: Int, textColor: Int): Button =
        Button(this).apply {
            text = value
            isAllCaps = false
            textSize = 15f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(backgroundColor, dp(14))
            minHeight = dp(52)
            stateListAnimator = null
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
