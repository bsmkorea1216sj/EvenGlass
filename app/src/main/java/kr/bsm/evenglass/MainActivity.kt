package kr.bsm.evenglass

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var still: ImageView
    private lateinit var live: TextView
    private lateinit var status: TextView
    private lateinit var ko: TextView
    private lateinit var orig: TextView
    private lateinit var panel: View
    private lateinit var pauseBtn: Button
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val prefs by lazy { getSharedPreferences("gemini_settings", MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val translator by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.KOREAN).build())
    }
    private val offlineCache = LruCache<String, String>(300)
    private val modelMutex = Mutex()
    private var modelReady = false
    @Volatile private var paused = false
    @Volatile private var busy = false
    @Volatile private var generation = 0L
    private var photoBusy = false
    private var lastRun = 0L
    private var lastRequest = -1500L
    private var lastOrig = ""
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var g2 = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else setStatus("카메라 권한이 필요합니다. [사진]으로 번역할 수 있습니다.")
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) recognizePhoto(uri) else if (!photoBusy) setPaused(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        still = findViewById(R.id.still)
        live = findViewById(R.id.live)
        status = findViewById(R.id.status)
        ko = findViewById(R.id.ko)
        orig = findViewById(R.id.orig)
        panel = findViewById(R.id.panel)
        pauseBtn = findViewById(R.id.pause)
        cameraExecutor = Executors.newSingleThreadExecutor()

        pauseBtn.setOnClickListener { if (!photoBusy) setPaused(!paused) }
        findViewById<Button>(R.id.flip).setOnClickListener {
            if (!photoBusy) {
                generation++
                lastOrig = ""
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                startCamera()
            }
        }
        findViewById<Button>(R.id.pick).setOnClickListener {
            if (!photoBusy) { setPaused(true); pickImage.launch("image/*") }
        }
        findViewById<Button>(R.id.precision).setOnClickListener { capturePhoto() }
        findViewById<Button>(R.id.settings).setOnClickListener { if (!photoBusy) showSettings() }
        findViewById<Button>(R.id.g2).setOnClickListener { toggleG2() }
        findViewById<Button>(R.id.prompter).setOnClickListener {
            startActivity(Intent(this, PrompterActivity::class.java))
        }
        lifecycleScope.launch {
            try { prepareModel() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
        translator.close()
    }

    private fun settings() = GeminiSettings(
        prefs.getString("api_key", "") ?: "",
        prefs.getString("model", GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
    )

    private fun showSettings() {
        val current = settings()
        val padding = (20 * resources.displayMetrics.density).roundToInt()
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val key = EditText(this).apply {
            hint = "Gemini API 키"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(current.key)
        }
        val model = EditText(this).apply {
            hint = "모델명"
            setSingleLine(true)
            setText(current.model)
        }
        val testResult = TextView(this).apply { text = "실시간 OCR 텍스트와 정밀 촬영 사진은 Gemini에 전송됩니다." }
        fields.addView(key)
        fields.addView(model)
        fields.addView(testResult)
        val dialog = AlertDialog.Builder(this).setTitle("Gemini 설정")
            .setView(fields).setNegativeButton("취소", null)
            .setNeutralButton("연결 테스트", null).setPositiveButton("저장", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        fun entered() = GeminiSettings(key.text.toString().trim(), model.text.toString().trim().ifEmpty { GeminiClient.DEFAULT_MODEL })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = entered()
            prefs.edit().putString("api_key", value.key).putString("model", value.model).apply()
            generation++
            lastOrig = ""
            setStatus(if (value.key.isBlank()) "오프라인 번역 · API 키 없음" else "Gemini 설정 저장 완료")
            dialog.dismiss()
        }
        val test = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        test.setOnClickListener {
            val value = entered()
            test.isEnabled = false
            testResult.text = "연결 테스트 중…"
            lifecycleScope.launch {
                try {
                    GeminiClient.test(value)
                    testResult.text = "연결 성공"
                } catch (e: CancellationException) { throw e }
                catch (e: GeminiException) { testResult.text = "연결 실패: " + e.message }
                catch (_: Exception) { testResult.text = "연결 실패: 설정을 확인하세요." }
                finally { test.isEnabled = true }
            }
        }
    }

    private suspend fun prepareModel() = modelMutex.withLock {
        if (!modelReady) {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            modelReady = true
        }
    }

    private suspend fun offline(text: String): TranslationResult {
        if (text.isBlank()) return TranslationResult("", "")
        prepareModel()
        val out = text.split("\n\n").map { paragraph ->
            offlineCache.get(paragraph) ?: translator.translate(paragraph).await().also { offlineCache.put(paragraph, it) }
        }
        return TranslationResult(text, out.joinToString("\n\n"))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .build()
                analysis.setAnalyzer(cameraExecutor) { analyze(it) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lensFacing).build(), pv, analysis, capture)
                imageCapture = capture
                setLive(if (paused) "일시정지" else "실시간", !paused)
            } catch (_: Exception) {
                imageCapture = null
                setLive("카메라 오류", false)
                setStatus("카메라를 열 수 없습니다. [사진]으로 번역할 수 있습니다.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (paused || busy || media == null || now - lastRun < 600L) { proxy.close(); return }
        busy = true
        lastRun = now
        val token = generation
        val rotation = proxy.imageInfo.rotationDegrees
        val w = if (rotation % 180 == 0) proxy.width else proxy.height
        val h = if (rotation % 180 == 0) proxy.height else proxy.width
        try {
            recognizer.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener { result ->
                    if (paused || token != generation) busy = false
                    else handleLive(paragraphs(result, w, h), token)
                }
                .addOnFailureListener {
                    if (token == generation && !paused) setStatus("일본어 인식 오류")
                    busy = false
                }
                .addOnCompleteListener { proxy.close() }
        } catch (_: Exception) { proxy.close(); busy = false }
    }

    // Crop by block center in the upright OCR coordinate system: central 80% x 70%.
    // Join wrapped lines inside each block; preserve paragraph boundaries across blocks.
    private fun paragraphs(text: Text, width: Int = 0, height: Int = 0): String {
        val blocks = text.textBlocks.filter { block ->
            val r = block.boundingBox
            (width == 0 || height == 0 || (r != null &&
                r.exactCenterX() >= width * 0.10f && r.exactCenterX() <= width * 0.90f &&
                r.exactCenterY() >= height * 0.15f && r.exactCenterY() <= height * 0.85f))
        }
        val vertical = blocks.count { b ->
            val r = b.boundingBox
            r != null && r.height() > r.width() * 1.5f
        } > blocks.size / 2
        val ordered = if (vertical) blocks.sortedWith(compareByDescending<Text.TextBlock> { it.boundingBox?.right ?: 0 }.thenBy { it.boundingBox?.top ?: 0 })
            else blocks.sortedWith(compareBy<Text.TextBlock> { it.boundingBox?.top ?: 0 }.thenBy { it.boundingBox?.left ?: 0 })
        return ordered.map { b ->
            b.lines.joinToString("") { it.text.trim().replace(Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])[ \\t\\u3000]+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])"), "") }
        }.filter { JP.containsMatchIn(it) }.joinToString("\n\n")
    }

    private fun handleLive(text: String, token: Long) {
        if (text.isBlank()) {
            setLive("일본어 찾는 중", true)
            busy = false
            return
        }
        val conf = settings()
        val now = SystemClock.elapsedRealtime()
        if (text == lastOrig || (conf.key.isNotBlank() && now - lastRequest < 1500L)) {
            busy = false
            return
        }
        lastOrig = text
        if (conf.key.isNotBlank()) lastRequest = now
        setLive(if (conf.key.isBlank()) "오프라인 번역" else "Gemini 번역 중", true)
        lifecycleScope.launch {
            var reason: String? = if (conf.key.isBlank()) "API 키 없음" else null
            try {
                val result = if (reason == null) {
                    try {
                        GeminiClient.translate(conf, text).also {
                            if (it.original.isBlank()) throw GeminiException("번역 응답이 비어 있습니다.")
                        }
                    } catch (e: GeminiException) {
                        reason = e.message ?: "API 오류"
                        if (token == generation && !paused) setStatus("오프라인 번역 · " + reason)
                        offline(text)
                    }
                } else {
                    if (token == generation && !paused) setStatus("오프라인 번역 · " + reason)
                    offline(text)
                }
                if (token == generation && !paused) showResult(result, reason, false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation && !paused) {
                    orig.text = text
                    ko.text = ""
                    setStatus("오프라인 번역 · " + (reason ?: "번역 오류") + " · ML Kit 모델을 준비하지 못했습니다.")
                    setLive("번역 오류", false)
                }
            } finally { busy = false }
        }
    }

    private fun capturePhoto() {
        if (photoBusy) return
        val capture = imageCapture ?: run { setStatus("카메라 준비 후 다시 촬영하세요."); return }
        setPaused(true)
        photoBusy = true
        setLive("정밀 촬영 중", true)
        val file = try { File.createTempFile("precision-", ".jpg", cacheDir) } catch (_: Exception) {
            photoBusy = false; setStatus("촬영 파일을 만들 수 없습니다."); return
        }
        capture.targetRotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    recognizePhoto(Uri.fromFile(file), file)
                }
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    photoBusy = false
                    setLive("촬영 오류", false)
                    setStatus("정밀 촬영 실패. 카메라를 확인하세요.")
                }
            })
    }

    private fun recognizePhoto(uri: Uri, temp: File? = null) {
        if (photoBusy && temp == null) return
        setPaused(true)
        photoBusy = true
        setLive("사진 정밀 분석 중", true)
        val conf = settings()
        lifecycleScope.launch {
            var reason: String? = if (conf.key.isBlank()) "API 키 없음" else null
            try {
                val bitmap = withContext(Dispatchers.IO) { decodePhoto(uri) }
                still.setImageBitmap(bitmap)
                still.visibility = View.VISIBLE
                val result = if (reason == null) {
                    val jpeg = withContext(Dispatchers.IO) {
                        ByteArrayOutputStream().use { out ->
                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw IllegalStateException("JPEG")
                            out.toByteArray()
                        }
                    }
                    try { GeminiClient.vision(conf, jpeg) }
                    catch (e: GeminiException) {
                        reason = e.message ?: "API 오류"
                        setStatus("오프라인 번역 · " + reason)
                        offline(paragraphs(recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()))
                    }
                } else {
                    setStatus("오프라인 번역 · " + reason)
                    offline(paragraphs(recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()))
                }
                showResult(result, reason, true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                setLive("사진 분석 오류", false)
                setStatus(if (reason == null) "사진을 열거나 분석하지 못했습니다." else "오프라인 번역 · " + reason + " · 사진 인식 또는 모델 준비 실패")
            } finally {
                photoBusy = false
                temp?.delete()
            }
        }
    }

    // Decode at bounded size, honor EXIF rotation/mirroring, then resize to max 2048px.
    private fun decodePhoto(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("photo")
        val exif = contentResolver.openInputStream(uri)?.use {
            try { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        val scale = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
        matrix.postScale(scale, scale)
        val resized = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (resized !== bitmap) bitmap.recycle()
        return resized
    }

    private fun showResult(result: TranslationResult, reason: String?, photo: Boolean) {
        orig.text = result.original
        ko.text = result.korean.ifBlank { "(일본어를 찾지 못했습니다)" }
        setStatus(if (reason == null) "Gemini 번역" else "오프라인 번역 · " + reason)
        setLive(if (photo) "사진 · 일시정지" else if (reason == null) "실시간 · Gemini" else "실시간 · 오프라인 번역", !photo)
    }

    private fun setPaused(p: Boolean) {
        generation++
        paused = p
        pauseBtn.text = if (p) "▶ 계속" else "⏸ 정지"
        if (p) setLive("일시정지", false)
        else {
            still.visibility = View.GONE
            lastOrig = ""
            setLive("실시간", true)
        }
    }

    private fun toggleG2() {
        g2 = !g2
        panel.setBackgroundColor(if (g2) Color.parseColor("#020A0A") else ContextCompat.getColor(this, R.color.card))
        ko.setTextColor(ContextCompat.getColor(this, if (g2) R.color.green else R.color.txt))
        ko.textSize = if (g2) 24f else 20f
    }

    private fun setLive(t: String, on: Boolean) {
        live.text = t
        live.setTextColor(if (on) ContextCompat.getColor(this, R.color.green) else Color.parseColor("#FFB4B4"))
    }
    private fun setStatus(t: String?) {
        status.text = t ?: ""
        status.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private val JP = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uff66-\\uff9f]")
    }
}
