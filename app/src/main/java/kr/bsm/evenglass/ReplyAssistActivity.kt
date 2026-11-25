package kr.bsm.evenglass

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone-only prototype of the G2 reply assistant:
 * speech (or typed text) → ML Kit language ID → on-device retrieval of similar past replies
 * → Gemini JSON generation of two short replies in SJK's style → G2-style preview.
 */
class ReplyAssistActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var other: TextView
    private lateinit var langView: TextView
    private lateinit var translation: TextView
    private lateinit var answers: TextView
    private lateinit var latency: TextView
    private lateinit var asrLang: TextView
    private lateinit var listenBtn: Button
    private lateinit var myReplyBtn: Button
    private lateinit var input: EditText

    private val geminiPrefs by lazy { getSharedPreferences("gemini_settings", MODE_PRIVATE) }
    private val prefs by lazy { getSharedPreferences("reply_assist", MODE_PRIVATE) }
    private val store by lazy { PersonaStore(applicationContext) }
    private val history by lazy { HistoryLog(applicationContext) }
    private val langId by lazy { LanguageIdentification.getClient() }
    private val translators = HashMap<String, Translator>()
    private val storeReady by lazy {
        lifecycleScope.async(Dispatchers.IO) {
            try { store.load(); true } catch (_: Exception) { false }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var partial = ""
    private var awaitingMyReply = false
    private var overrideLocale: String? = null
    private var lastOtherLang = "en"
    private var replyJob: Job? = null
    private val silence = Runnable { onSilence() }
    private val restart = Runnable { startSession() }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else setStatus("마이크 권한이 필요합니다. 입력창으로 테스트할 수 있습니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reply_assist)
        status = findViewById(R.id.status)
        other = findViewById(R.id.other)
        langView = findViewById(R.id.lang)
        translation = findViewById(R.id.translation)
        answers = findViewById(R.id.answers)
        latency = findViewById(R.id.latency)
        asrLang = findViewById(R.id.asrLang)
        listenBtn = findViewById(R.id.listen)
        myReplyBtn = findViewById(R.id.myReply)
        input = findViewById(R.id.input)

        listenBtn.setOnClickListener { if (listening) stopListening() else startListening() }
        myReplyBtn.setOnClickListener { toggleMyReply() }
        findViewById<Button>(R.id.send).setOnClickListener { submitTyped() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
        updateAsrLabel()

        lifecycleScope.launch {
            if (!storeReady.await()) setStatus("개인 대화 데이터(persona)를 불러오지 못했습니다. 예시 없이 생성합니다.")
            else if (settings().key.isBlank()) setStatus("Gemini API 키가 없습니다. 메인 화면 ⚙ 설정에서 입력하세요.")
        }
        // Warm up the on-device translator for the selected language (used for retrieval + quick translation).
        lifecycleScope.launch {
            try { translator(asrLocale().substringBefore('-'))?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await() }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    override fun onStop() {
        super.onStop()
        if (listening) stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        history.flush()
        langId.close()
        translators.values.forEach { it.close() }
    }

    private fun settings() = GeminiSettings(
        geminiPrefs.getString("api_key", "") ?: "",
        geminiPrefs.getString("model", GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
    )

    private fun asrLocale(): String = prefs.getString("asr_locale", "en-US") ?: "en-US"
    private fun currentLocale(): String = overrideLocale ?: asrLocale()

    private fun updateAsrLabel() {
        val loc = currentLocale()
        asrLang.text = "인식: " + loc + if (overrideLocale != null) " (내 답)" else ""
    }

    // ---------------- Speech recognition ----------------

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("이 기기에서 음성 인식 서비스를 찾을 수 없습니다. Google 음성 인식 앱을 확인하세요.")
            return
        }
        listening = true
        listenBtn.text = "⏹ 듣기 정지"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        createRecognizer()
        setStatus("듣는 중 · " + currentLocale())
        startSession()
    }

    private fun stopListening() {
        listening = false
        handler.removeCallbacks(silence)
        handler.removeCallbacks(restart)
        try { recognizer?.cancel() } catch (_: Exception) { }
        recognizer?.destroy()
        recognizer = null
        partial = ""
        listenBtn.text = "🎙 듣기 시작"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatus("듣기 정지")
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
    }

    private fun startSession() {
        if (!listening) return
        val r = recognizer ?: return
        partial = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Hints only; many recognizers ignore them, so the 1.2 s silence timer below is authoritative.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        }
        try { r.startListening(intent) } catch (_: Exception) {
            setStatus("음성 인식을 시작하지 못했습니다. 잠시 후 다시 시도합니다.")
            scheduleRestart(1000L, recreate = true)
        }
    }

    private fun scheduleRestart(delay: Long, recreate: Boolean = false) {
        handler.removeCallbacks(restart)
        if (!listening) return
        if (recreate) createRecognizer()
        handler.postDelayed(restart, delay)
    }

    /** No new partial words for 1.2 s → treat the current partial text as one finished utterance. */
    private fun onSilence() {
        if (!listening) return
        val text = partial.trim()
        if (text.isEmpty()) return
        partial = ""
        try { recognizer?.cancel() } catch (_: Exception) { }
        deliver(text)
        scheduleRestart(150L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (text.isNullOrEmpty() || text == partial) return
            partial = text
            if (awaitingMyReply) setStatus("내 답 인식 중: " + text) else other.text = "… " + text
            handler.removeCallbacks(silence)
            handler.postDelayed(silence, SILENCE_MS)
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            handler.removeCallbacks(silence)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: partial.trim()
            partial = ""
            if (text.isNotEmpty()) deliver(text)
            scheduleRestart(100L)
        }

        override fun onError(error: Int) {
            if (!listening) return
            handler.removeCallbacks(silence)
            val pending = partial.trim()
            partial = ""
            if (pending.isNotEmpty()) deliver(pending)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(100L)
                SpeechRecognizer.ERROR_CLIENT -> scheduleRestart(300L)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopListening(); setStatus("마이크 권한이 없습니다. 앱 설정에서 마이크를 허용하세요.")
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    stopListening(); setStatus("이 기기는 인식 언어 " + currentLocale() + "를 지원하지 않습니다. 설정에서 다른 언어를 고르거나 언어 팩을 설치하세요.")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    setStatus("네트워크 문제로 음성 인식이 끊겼습니다. 다시 듣는 중…"); scheduleRestart(1000L)
                }
                SpeechRecognizer.ERROR_AUDIO -> { setStatus("마이크 오디오 오류입니다. 다시 듣는 중…"); scheduleRestart(1000L, recreate = true) }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(500L, recreate = true)
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> { setStatus("음성 인식 요청이 너무 많습니다. 잠시 후 다시 듣습니다."); scheduleRestart(3000L) }
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> scheduleRestart(500L, recreate = true)
                else -> { setStatus("음성 인식 오류(" + error + "). 다시 듣는 중…"); scheduleRestart(800L, recreate = true) }
            }
        }
    }

    private fun deliver(text: String) {
        if (awaitingMyReply) recordMyReply(text) else handleUtterance(text)
    }

    private fun submitTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        deliver(text)
    }

    // ---------------- "My reply" logging ----------------

    private fun toggleMyReply() {
        if (awaitingMyReply) {
            setMyReplyMode(false)
            setStatus("내 답 기록 취소")
            return
        }
        if (!history.hasPending) { setStatus("먼저 상대 발화가 있어야 내 답을 기록할 수 있습니다."); return }
        setMyReplyMode(true)
        setStatus(if (listening) "다음 발화를 내 답으로 기록합니다 · 인식 언어 " + currentLocale()
            else "[듣기 시작] 후 말하거나 입력창에 내 답을 입력하세요.")
    }

    /** While on, the recognizer uses the other person's language and the next utterance is SJK's reply. */
    private fun setMyReplyMode(on: Boolean) {
        awaitingMyReply = on
        overrideLocale = if (on) localeFor(lastOtherLang) else null
        myReplyBtn.text = if (on) "내 답 기록 중 (취소)" else "내 답 기록"
        myReplyBtn.setTextColor(if (on) ContextCompat.getColor(this, R.color.green) else ContextCompat.getColor(this, R.color.txt))
        updateAsrLabel()
        if (listening) {
            handler.removeCallbacks(silence)
            try { recognizer?.cancel() } catch (_: Exception) { }
            scheduleRestart(150L)
        }
    }

    private fun recordMyReply(text: String) {
        val matched = history.setMyReply(text)
        setMyReplyMode(false)
        setStatus("내 답 기록됨: " + text + when (matched) {
            0 -> " · 제시안 ①과 일치"
            1 -> " · 제시안 ②와 일치"
            else -> " · 제시안과 다름(정답 예시로 저장)"
        })
    }

    private fun localeFor(lang: String) = when (lang) {
        "ja" -> "ja-JP"; "zh" -> "zh-CN"; "ko" -> "ko-KR"; "en" -> "en-US"; else -> asrLocale()
    }

    // ---------------- Detect → retrieve → generate ----------------

    private fun handleUtterance(text: String) {
        replyJob?.cancel()
        other.text = text
        translation.text = ""
        answers.text = "답변 만드는 중…"
        latency.text = ""
        replyJob = lifecycleScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val lang = detectLanguage(text)
            lastOtherLang = lang
            langView.text = "감지 언어: " + lang + " (" + ReplyEngine.langName(lang) + ")"
            history.begin(text, lang, "")

            val machine = if (lang == "ko") null else withTimeoutOrNull(1500L) { translateToKo(text, lang) }
            if (!machine.isNullOrBlank()) translation.text = machine + "  (기기 번역)"

            val loaded = storeReady.await()
            val s0 = SystemClock.elapsedRealtime()
            val hits = if (loaded) store.search(listOfNotNull(text, machine), 5) else emptyList()
            val searchMs = SystemClock.elapsedRealtime() - s0

            val conf = settings()
            if (conf.key.isBlank()) {
                answers.text = "(Gemini API 키가 필요합니다)"
                history.setError("API 키 없음")
                setStatus("Gemini API 키가 없습니다. 메인 화면 ⚙ 설정에서 입력하세요. · 유사 예시 " + hits.size + "개")
                return@launch
            }
            val start = SystemClock.elapsedRealtime()
            try {
                val json = GeminiClient.generateJson(conf, ReplyEngine.buildPrompt(store.style, hits, text, lang), fast = true)
                val result = ReplyEngine.parse(json, lang)
                if (result.translationKo.isNotEmpty()) translation.text = result.translationKo
                if (result.lang != lang) langView.text = "감지 언어: " + lang + " → Gemini: " + result.lang + " (" + ReplyEngine.langName(result.lang) + ")"
                showAnswers(result)
                val now = SystemClock.elapsedRealtime()
                val ms = now - start
                latency.text = ms.toString() + " ms"
                lastOtherLang = result.lang
                history.setSuggestions(result, ms, hits)
                setStatus("완료 · 감지·번역 " + (s0 - t0) + "ms · 검색 " + searchMs + "ms(예시 " + hits.size + "개) · 생성 " + ms + "ms · 전체 " + (now - t0) + "ms")
            } catch (e: CancellationException) { throw e }
            catch (e: GeminiException) {
                answers.text = "(답변을 만들지 못했습니다)"
                history.setError(e.message ?: "Gemini 오류")
                setStatus("답변 생성 실패: " + (e.message ?: "Gemini 오류"))
            } catch (_: Exception) {
                answers.text = "(답변을 만들지 못했습니다)"
                history.setError("알 수 없는 오류")
                setStatus("답변 생성 중 알 수 없는 오류가 발생했습니다.")
            }
        }
    }

    private suspend fun detectLanguage(text: String): String {
        val tag = try { langId.identifyLanguage(text).await() } catch (e: CancellationException) { throw e } catch (_: Exception) { "und" }
        val code = tag.substringBefore('-').lowercase()
        if (code != "und" && code.isNotEmpty()) return code
        // Fallback by script, then the recognition language.
        return when {
            Regex("[\\uac00-\\ud7a3]").containsMatchIn(text) -> "ko"
            Regex("[\\u3040-\\u30ff]").containsMatchIn(text) -> "ja"
            Regex("[\\u4e00-\\u9fff]").containsMatchIn(text) -> "zh"
            Regex("[A-Za-z]").containsMatchIn(text) -> "en"
            else -> asrLocale().substringBefore('-')
        }
    }

    private fun translator(lang: String): Translator? {
        val src = TranslateLanguage.fromLanguageTag(lang) ?: return null
        if (src == TranslateLanguage.KOREAN) return null
        return translators.getOrPut(src) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.KOREAN).build())
        }
    }

    private suspend fun translateToKo(text: String, lang: String): String? = try {
        translator(lang)?.let { t ->
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            t.translate(text).await()
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** English: original / Korean meaning. Other languages: original / meaning / Hangul pronunciation. */
    private fun showAnswers(r: ReplyResult) {
        val sb = SpannableStringBuilder()
        val sub = Color.parseColor("#4FBF8A")
        r.answers.forEachIndexed { i, a ->
            if (i > 0) sb.append("\n\n")
            sb.append(if (i == 0) "① " else "② ").append(a.text)
            fun line(s: String) {
                if (s.isEmpty()) return
                val from = sb.length
                sb.append("\n   ").append(s)
                sb.setSpan(ForegroundColorSpan(sub), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.85f), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (r.lang != "ko") line(a.koMeaning)
            if (r.lang != "en" && r.lang != "ko") line(a.koPron)
        }
        answers.text = sb
    }

    // ---------------- Settings / export ----------------

    private fun showSettings() {
        val idx = LOCALES.indexOf(asrLocale()).coerceAtLeast(0)
        val labels: Array<CharSequence> = LOCALES.map<String, CharSequence> { it + " · " + ReplyEngine.langName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("인식 언어 (기록 " + history.count() + "건)")
            .setSingleChoiceItems(labels, idx) { d, which ->
                prefs.edit().putString("asr_locale", LOCALES[which]).apply()
                updateAsrLabel()
                if (listening && overrideLocale == null) {
                    try { recognizer?.cancel() } catch (_: Exception) { }
                    scheduleRestart(150L)
                }
                setStatus("인식 언어: " + LOCALES[which] + " · Gemini 키·모델은 메인 화면 ⚙ 설정을 사용합니다.")
                d.dismiss()
            }
            .setNeutralButton("기록 내보내기") { _, _ -> exportHistory() }
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun exportHistory() {
        if (awaitingMyReply) setMyReplyMode(false)
        history.flush()
        val file = history.file
        if (!file.exists() || file.length() == 0L) { setStatus("내보낼 기록이 없습니다."); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "EvenGlass history.jsonl")
                clipData = ClipData.newRawUri("history.jsonl", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "기록 내보내기 (history.jsonl)"))
        } catch (_: ActivityNotFoundException) {
            setStatus("공유할 수 있는 앱이 없습니다.")
        } catch (_: Exception) {
            setStatus("기록 파일을 공유하지 못했습니다.")
        }
    }

    private fun setStatus(t: String?) {
        status.text = t ?: ""
        status.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val SILENCE_MS = 1200L
        private val LOCALES = listOf("en-US", "ja-JP", "zh-CN", "ko-KR")
    }
}
