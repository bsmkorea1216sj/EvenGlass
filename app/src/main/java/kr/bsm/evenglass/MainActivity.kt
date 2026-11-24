package kr.bsm.evenglass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var still: ImageView
    private lateinit var live: TextView
    private lateinit var status: TextView
    private lateinit var ko: TextView
    private lateinit var orig: TextView
    private lateinit var panel: View
    private lateinit var pauseBtn: Button

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
    }
    private val cache = LruCache<String, String>(300)
    private lateinit var cameraExecutor: ExecutorService

    @Volatile private var paused = false
    @Volatile private var busy = false
    @Volatile private var modelReady = false
    private var downloading = false
    private var lastRun = 0L
    private var lastOrig = ""
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var g2 = false

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera()
            else setStatus("카메라 권한이 필요합니다. 설정 > 애플리케이션 > EvenGlass > 권한에서 카메라를 허용하거나 [사진]을 쓰세요.")
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) recognizePhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        still = findViewById(R.id.still)
        live = findViewById(R.id.live)
        status = findViewById(R.id.status)
        ko = findViewById(R.id.ko)
        orig = findViewById(R.id.orig)
        panel = findViewById(R.id.panel)
        pauseBtn = findViewById(R.id.pause)

        cameraExecutor = Executors.newSingleThreadExecutor()

        pauseBtn.setOnClickListener { setPaused(!paused) }
        findViewById<Button>(R.id.flip).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        findViewById<Button>(R.id.pick).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.g2).setOnClickListener { toggleG2() }
        findViewById<Button>(R.id.prompter).setOnClickListener {
            startActivity(Intent(this, PrompterActivity::class.java))
        }

        prepareModel()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
        translator.close()
    }

    // ---------- translation model ----------
    private fun prepareModel() {
        if (downloading || modelReady) return
        downloading = true
        setLive("번역 모델 준비 중", false)
        setStatus("일→한 번역 모델을 준비하는 중입니다. 처음 한 번만 약 30MB를 내려받습니다(와이파이 권장).")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                downloading = false
                setStatus(null)
                setLive(if (paused) "일시정지" else "실시간", !paused)
                lastOrig = ""
            }
            .addOnFailureListener { e ->
                downloading = false
                setStatus("번역 모델을 받지 못했습니다(${e.message}). 인터넷 연결을 확인하세요. 글자가 인식되면 다시 시도합니다.")
            }
    }

    // ---------- camera ----------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
            analysis.setAnalyzer(cameraExecutor) { analyze(it) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    pv,
                    analysis
                )
                if (modelReady) setLive("실시간", true)
            } catch (e: Exception) {
                setLive("카메라 오류", false)
                setStatus("카메라를 열 수 없습니다: ${e.message}. [사진]으로 번역할 수 있습니다.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (paused || busy || media == null || now - lastRun < INTERVAL_MS) {
            proxy.close()
            return
        }
        busy = true
        lastRun = now
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(img)
            .addOnSuccessListener { handleText(it, force = false) }
            .addOnFailureListener { e ->
                setStatus("인식 오류: ${e.message}")
                busy = false
            }
            .addOnCompleteListener { proxy.close() }
    }

    // ---------- OCR result -> translation ----------
    private fun handleText(text: Text, force: Boolean) {
        if (paused && !force) {
            busy = false
            return
        }
        val blocks = text.textBlocks
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .map { b -> b.lines.joinToString("") { it.text.replace(SPACES, "") } }
            .filter { JP.findAll(it).count() >= 2 }

        if (blocks.isEmpty()) {
            if (force) {
                orig.text = ""
                ko.text = "(일본어를 찾지 못했습니다)"
                setLive("사진", false)
            } else setLive(if (modelReady) "일본어 찾는 중" else "번역 모델 준비 중", modelReady)
            busy = false
            return
        }

        val joined = blocks.joinToString("\n")
        if (!force && similar(joined, lastOrig) > 0.85) {
            busy = false
            return
        }
        lastOrig = joined
        orig.text = joined

        if (!modelReady) {
            busy = false
            prepareModel()
            return
        }

        lifecycleScope.launch {
            try {
                val out = blocks.map { b ->
                    cache.get(b) ?: translator.translate(b).await().also { cache.put(b, it) }
                }
                ko.text = out.joinToString("\n")
                setStatus(null)
                setLive(if (force) "사진" else "실시간", !force)
            } catch (e: Exception) {
                setStatus("번역 오류: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    // ---------- photo ----------
    private fun recognizePhoto(uri: Uri) {
        setPaused(true)
        still.setImageURI(uri)
        still.visibility = View.VISIBLE
        val img = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            setStatus("사진을 열 수 없습니다: ${e.message}")
            return
        }
        busy = true
        setLive("사진 인식 중", true)
        recognizer.process(img)
            .addOnSuccessListener { handleText(it, force = true) }
            .addOnFailureListener { e ->
                setStatus("인식 오류: ${e.message}")
                busy = false
            }
    }

    // ---------- UI helpers ----------
    private fun setPaused(p: Boolean) {
        paused = p
        pauseBtn.text = if (p) "▶ 계속" else "⏸ 정지"
        if (p) {
            setLive("일시정지", false)
        } else {
            still.visibility = View.GONE
            lastOrig = ""
            setLive(if (modelReady) "실시간" else "번역 모델 준비 중", modelReady)
        }
    }

    private fun toggleG2() {
        g2 = !g2
        if (g2) {
            panel.setBackgroundColor(Color.parseColor("#020A0A"))
            ko.setTextColor(ContextCompat.getColor(this, R.color.green))
            ko.textSize = 24f
        } else {
            panel.setBackgroundColor(ContextCompat.getColor(this, R.color.card))
            ko.setTextColor(ContextCompat.getColor(this, R.color.txt))
            ko.textSize = 20f
        }
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
        private const val INTERVAL_MS = 600L
        private val JP = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uff66-\\uff9f]")
        private val SPACES = Regex("[ \\t\\u3000]+")

        /** Dice similarity of character bigrams (0..1). */
        fun similar(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0
            fun bigrams(s: String): Map<String, Int> {
                val m = HashMap<String, Int>()
                for (i in 0 until s.length - 1) {
                    val k = s.substring(i, i + 2)
                    m[k] = (m[k] ?: 0) + 1
                }
                return m
            }
            val x = bigrams(a)
            val y = bigrams(b)
            var n = 0
            for ((k, c) in x) n += minOf(c, y[k] ?: 0)
            return 2.0 * n / maxOf(1, (a.length - 1) + (b.length - 1))
        }
    }
}
