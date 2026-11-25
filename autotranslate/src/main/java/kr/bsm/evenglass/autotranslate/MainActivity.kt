package kr.bsm.evenglass.autotranslate

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * EvenGlass 자동번역 — phone-only prototype of the G2 reply assistant (phone microphone):
 * speech (or typed text) → ML Kit language ID → on-device retrieval of similar past replies
 * → Gemini JSON generation of two short replies in SJK's style → G2-style preview.
 */
class MainActivity : ComponentActivity() {
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
    private lateinit var tabConv: TextView
    private lateinit var tabInterview: TextView
    private lateinit var interviewBar: View
    private lateinit var roleAnswer: TextView
    private lateinit var roleAsk: TextView
    private lateinit var contextInput: EditText
    private lateinit var otherLabel: TextView

    /** Current top tab (회화 / 면접) and, in 면접, my role. */
    private var mode = Mode.CONVERSATION
    private var currentResult: ReplyResult? = null
    private var previewItems: List<NextItem> = emptyList()
    private var previewJob: Job? = null
    /** Recent interview lines ("면접관: …", "SJK: …") sent as context to the interview AI. */
    private val transcript = ArrayDeque<String>()

    private val geminiPrefs by lazy { getSharedPreferences("gemini_settings", MODE_PRIVATE) }
    private val prefs by lazy { getSharedPreferences("reply_assist", MODE_PRIVATE) }
    private val convStore by lazy { PersonaStore(applicationContext) }
    private val interviewStore by lazy { PersonaStore(applicationContext, "persona/interview_pairs.jsonl", "persona_interview.db") }
    /** My CV facts ("주제 | 내용"), imported on the phone; empty until then. */
    private val factStore by lazy { PersonaStore(applicationContext, "persona/facts.jsonl", "persona_facts.db") }
    private val store get() = if (mode.isInterview) interviewStore else convStore
    /** Fact cards sent with the latest request, to show which ones an answer used. */
    private var currentFacts: List<Hit> = emptyList()
    private val history by lazy { HistoryLog(applicationContext) }
    private val langId by lazy { LanguageIdentification.getClient() }
    private val translators = HashMap<String, Translator>()
    private var storeReady: Deferred<Boolean> = CompletableDeferred(false)

    /** (Re)loads sample/imported persona data into SQLite + the in-memory search index. */
    private fun loadStores() {
        storeReady = lifecycleScope.async(Dispatchers.IO) {
            try { convStore.load(); interviewStore.load(); factStore.load(); true } catch (_: Exception) { false }
        }
    }

    private val pickBundle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBundle(uri)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var partial = ""
    private var awaitingMyReply = false
    /** [🇰🇷 한국어로 말하기]: the next utterance is my Korean, to be interpreted into the other person's language. */
    private var koreanMode = false
    private data class Interp(val ko: String, val text: String, val pron: String, val lang: String)
    private var currentInterp: Interp? = null
    private var interpJob: Job? = null
    /** Suggestions part of the G2 panel (or a placeholder); the interpretation block is drawn above it. */
    private var answersBody: CharSequence = ""
    private lateinit var koreanBtn: Button
    private var lastOtherLang = "en"
    private var replyJob: Job? = null

    /** Who said the most recent utterance, so a wrong guess can be flipped with [↺ 바꾸기]. */
    private enum class Speaker { OTHER, ME }
    private data class Last(val speaker: Speaker, val text: String, val prevMyReply: String? = null)
    /** Screen state before the latest "other" utterance, restored when it is flipped to "me". */
    private data class Screen(val other: CharSequence, val lang: CharSequence, val translation: CharSequence,
                              val answers: CharSequence, val latency: CharSequence, val otherLang: String,
                              val suggestions: List<String>)
    private var last: Last? = null
    private var prevScreen: Screen? = null
    private var suggestions: List<String> = emptyList()
    private lateinit var flipBtn: Button
    private val silence = Runnable { onSilence() }
    private val restart = Runnable { startSession() }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else setStatus("마이크 권한이 필요합니다. 입력창으로 테스트할 수 있습니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        other = findViewById(R.id.other)
        langView = findViewById(R.id.lang)
        translation = findViewById(R.id.translation)
        answers = findViewById(R.id.answers)
        latency = findViewById(R.id.latency)
        asrLang = findViewById(R.id.asrLang)
        listenBtn = findViewById(R.id.listen)
        myReplyBtn = findViewById(R.id.myReply)
        flipBtn = findViewById(R.id.flip)
        flipBtn.setOnClickListener { flipLast() }
        koreanBtn = findViewById(R.id.korean)
        koreanBtn.setOnClickListener { toggleKorean() }
        answersBody = answers.text
        input = findViewById(R.id.input)
        tabConv = findViewById(R.id.tabConv)
        tabInterview = findViewById(R.id.tabInterview)
        interviewBar = findViewById(R.id.interviewBar)
        roleAnswer = findViewById(R.id.roleAnswer)
        roleAsk = findViewById(R.id.roleAsk)
        contextInput = findViewById(R.id.interviewContext)
        otherLabel = findViewById(R.id.otherLabel)
        contextInput.setText(prefs.getString("interview_context", ""))
        tabConv.setOnClickListener { setMode(Mode.CONVERSATION) }
        tabInterview.setOnClickListener { setMode(Mode.of(prefs.getString("interview_role", Mode.INTERVIEW_ANSWER.key)).takeIf { it.isInterview } ?: Mode.INTERVIEW_ANSWER) }
        roleAnswer.setOnClickListener { setMode(Mode.INTERVIEW_ANSWER) }
        roleAsk.setOnClickListener { setMode(Mode.INTERVIEW_ASK) }
        findViewById<Button>(R.id.preview).setOnClickListener { previewInterview() }

        listenBtn.setOnClickListener { if (listening) stopListening() else startListening() }
        myReplyBtn.setOnClickListener { toggleMyReply() }
        findViewById<Button>(R.id.send).setOnClickListener { submitTyped() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
        loadStores()
        updateAsrLabel()
        setMode(Mode.of(prefs.getString("mode", Mode.CONVERSATION.key)), initial = true)

        lifecycleScope.launch {
            if (!storeReady.await()) setStatus("개인 대화 데이터(persona)를 불러오지 못했습니다. 예시 없이 생성합니다.")
            else if (settings().key.isBlank()) setStatus("Gemini API 키가 없습니다. ⚙ 설정에서 입력하세요.")
        }
        // Warm up the on-device translator for the selected language (used for retrieval + quick translation).
        lifecycleScope.launch {
            try {
                translator(targetLang())?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await()
                translatorFromKo(targetLang())?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
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

    private fun interviewConf(): LlmConfig {
        val p = Provider.of(prefs.getString("interview_provider", Provider.CLAUDE.key))
        return when (p) {
            Provider.GEMINI -> settings().let { LlmConfig(p, it.key, it.model) }
            Provider.CLAUDE -> LlmConfig(p, prefs.getString("claude_key", "") ?: "", prefs.getString("claude_model", p.defaultModel) ?: p.defaultModel)
            Provider.OPENAI -> LlmConfig(p, prefs.getString("openai_key", "") ?: "", prefs.getString("openai_model", p.defaultModel) ?: p.defaultModel)
        }
    }

    /** AI used for the current tab: 회화 → Gemini, 면접 → the provider chosen in settings. */
    private fun activeConf(): LlmConfig =
        if (mode.isInterview) interviewConf() else settings().let { LlmConfig(Provider.GEMINI, it.key, it.model) }

    // ---------------- Tabs ----------------

    private fun setMode(m: Mode, initial: Boolean = false) {
        if (m == mode && !initial) return
        val tabChanged = m.isInterview != mode.isInterview || initial
        mode = m
        val edit = prefs.edit().putString("mode", m.key)
        if (m.isInterview) edit.putString("interview_role", m.key)
        edit.apply()
        val on = ContextCompat.getColor(this, R.color.green)
        val off = ContextCompat.getColor(this, R.color.mut)
        tabConv.setTextColor(if (m.isInterview) off else on)
        tabInterview.setTextColor(if (m.isInterview) on else off)
        tabConv.setBackgroundColor(if (m.isInterview) Color.TRANSPARENT else ContextCompat.getColor(this, R.color.card))
        tabInterview.setBackgroundColor(if (m.isInterview) ContextCompat.getColor(this, R.color.card) else Color.TRANSPARENT)
        interviewBar.visibility = if (m.isInterview) View.VISIBLE else View.GONE
        roleAnswer.setTextColor(if (m == Mode.INTERVIEW_ANSWER) on else off)
        roleAsk.setTextColor(if (m == Mode.INTERVIEW_ASK) on else off)
        otherLabel.text = when (m) {
            Mode.CONVERSATION -> "상대 원문"
            Mode.INTERVIEW_ANSWER -> "면접관 질문"
            Mode.INTERVIEW_ASK -> "지원자 답변"
        }
        if (tabChanged) {
            replyJob?.cancel(); previewJob?.cancel(); interpJob?.cancel()
            other.text = if (m.isInterview) "면접 정보를 적고 [🔮 예상 질문]을 누르면 나올 질문과 답을 미리 보여 줍니다. [듣기 시작]으로 실제 면접을 듣습니다."
                else "[듣기 시작]을 누르거나 아래 입력창에 상대가 한 말을 입력하세요."
            langView.text = "감지 언어: -"
            translation.text = ""
            latency.text = ""
            currentInterp = null; currentResult = null; previewItems = emptyList()
            suggestions = emptyList(); prevScreen = null; last = null
            setAnswersBody(PLACEHOLDER)
        }
        if (!initial) setStatus(when (m) {
            Mode.CONVERSATION -> "💬 회화 모드 · Gemini"
            Mode.INTERVIEW_ANSWER -> "🎤 면접 모드 · 내가 답변자 · " + interviewConf().provider.label
            Mode.INTERVIEW_ASK -> "🎤 면접 모드 · 내가 면접관 · " + interviewConf().provider.label
        })
    }

    /**
     * Fact cards sent as grounding. 면접: all cards while the set is small (a CV fits in one prompt and
     * character-n-gram ranking misses paraphrased questions); otherwise / 회화: the best matches.
     */
    private fun relevantFacts(queries: List<String>): List<Hit> =
        if (mode.isInterview && factStore.size <= ALL_FACTS_LIMIT) factStore.all()
        else factStore.search(queries, if (mode.isInterview) 12 else 3).filter { it.score > 0.03 }

    private fun interviewContext(): String {
        val c = contextInput.text.toString().trim()
        prefs.edit().putString("interview_context", c).apply()
        return c
    }

    private fun addTranscript(line: String) {
        if (!mode.isInterview) return
        transcript.addLast(line)
        while (transcript.size > 8) transcript.removeFirst()
    }

    /** 🔮 Likely next interview turns, with prepared answers — before the interview or between questions. */
    private fun previewInterview() {
        val conf = interviewConf()
        val context = interviewContext()
        val lang = targetLang()
        previewJob?.cancel()
        setAnswersBody("🔮 예상 질문을 만드는 중… (" + conf.provider.label + ")")
        previewJob = lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            storeReady.await()
            val hits = if (context.isNotBlank()) interviewStore.search(listOf(context), 5) else emptyList()
            val facts = relevantFacts(listOf(context) + transcript.toList().takeLast(2))
            currentFacts = facts
            try {
                val o = LlmClient.json(conf, ReplyEngine.buildPreviewPrompt(interviewStore.style, hits, context, transcript.toList(), lang, mode, facts))
                previewItems = ReplyEngine.parseNext(o, lang)
                if (previewItems.isEmpty()) throw GeminiException("예상 질문이 비어 있습니다.")
                currentResult = null
                suggestions = previewItems.map { it.answer }.filter { it.isNotBlank() }
                renderAnswers()
                val ms = SystemClock.elapsedRealtime() - start
                latency.text = ms.toString() + " ms"
                setStatus("🔮 예상 " + previewItems.size + "개 · " + conf.provider.label + " " + ms + "ms · 준비된 답을 읽으면 내 말로 기록됩니다")
            } catch (e: CancellationException) { throw e }
            catch (e: GeminiException) { setAnswersBody(PLACEHOLDER); setStatus("예상 질문 실패: " + e.message) }
            catch (_: Exception) { setAnswersBody(PLACEHOLDER); setStatus("예상 질문을 만들지 못했습니다.") }
        }
    }

    private fun asrLocale(): String = prefs.getString("asr_locale", "en-US") ?: "en-US"
    /** The other person's language = interpretation target (from the 상대 언어 setting). */
    private fun targetLang(): String = asrLocale().substringBefore('-')
    private fun currentLocale(): String = when {
        koreanMode -> "ko-KR"
        awaitingMyReply -> localeFor(lastOtherLang)
        else -> asrLocale()
    }

    private fun updateAsrLabel() {
        val loc = currentLocale()
        asrLang.text = "인식: " + ReplyEngine.langName(loc) + when {
            koreanMode -> " (내 한국어)"
            awaitingMyReply -> " (내 말)"
            else -> ""
        }
    }

    /** Restart the running recognizer so a new recognition language takes effect. */
    private fun restartRecognizerSoon() {
        if (!listening) return
        handler.removeCallbacks(silence)
        try { recognizer?.cancel() } catch (_: Exception) { }
        scheduleRestart(150L)
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
        setStatus("휴대폰 마이크로 듣는 중 · " + currentLocale())
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
            setStatus((if (koreanMode) "🇰🇷 " else if (awaitingMyReply) "🙋 내 말 듣는 중: " else "🎙 ") + text)
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

    /**
     * Decides who spoke:
     * 1) [🙋 다음은 내 말] was pressed → me;
     * 0) [🇰🇷 한국어로 말하기] or Korean text → my Korean, interpreted into the other person's language;
     * 2) at least 70% of what was said appears in a suggestion or interpretation on screen (I read it) → me;
     * 3) otherwise → the other person (translate + suggest).
     * A wrong guess can be fixed with [↺ 바꾸기].
     */
    private fun deliver(text: String) {
        // My Korean (button, or Korean typed/heard while the other person's language isn't Korean) → interpret.
        if (koreanMode || (HANGUL.containsMatchIn(text) && targetLang() != "ko" && !awaitingMyReply)) { interpret(text); return }
        if (awaitingMyReply) { recordMine(text, "manual"); return }
        val longEnough = PersonaStore.normalize(text).replace(" ", "").length >= MIN_AUTO_CHARS
        val mine = suggestions + listOfNotNull(currentInterp?.text?.takeIf { it.isNotBlank() })
        if (longEnough && mine.any { PersonaStore.containment(text, it) >= MATCH_ME }) {
            recordMine(text, "auto-match"); return
        }
        handleUtterance(text)
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
            setStatus("내 말 모드 취소")
            return
        }
        setMyReplyMode(true)
        setStatus(if (listening) "🙋 다음 발화는 내 말로 처리합니다(예상 답변 없음) · 인식 언어 " + currentLocale()
            else "[듣기 시작] 후 말하거나 입력창에 내 말을 입력하세요.")
    }

    /** While on, the recognizer uses the other person's language and the next utterance is SJK's reply. */
    private fun setMyReplyMode(on: Boolean) {
        if (on && koreanMode) setKoreanMode(false)
        awaitingMyReply = on
        myReplyBtn.text = if (on) "🙋 내 말 대기 (취소)" else "🙋 다음은 내 말"
        myReplyBtn.setTextColor(if (on) ContextCompat.getColor(this, R.color.green) else ContextCompat.getColor(this, R.color.txt))
        updateAsrLabel()
        restartRecognizerSoon()
    }

    // ---------------- My Korean → other person's language ----------------

    private fun toggleKorean() {
        if (koreanMode) { setKoreanMode(false); setStatus("한국어 통역 취소"); return }
        if (targetLang() == "ko") {
            setStatus("상대 언어가 한국어로 설정되어 있습니다. ⚙ 설정에서 영어·일본어·중국어·러시아어 중 하나를 고르세요.")
            return
        }
        setKoreanMode(true)
        if (!listening) startListening()
        setStatus("🇰🇷 한국어로 말하세요 → " + ReplyEngine.langName(targetLang()) + " 문장과 한글 발음을 보여 줍니다")
    }

    private fun setKoreanMode(on: Boolean) {
        if (on && awaitingMyReply) setMyReplyMode(false)
        koreanMode = on
        koreanBtn.text = if (on) "🇰🇷 한국어 듣는 중 (취소)" else "🇰🇷 한국어로 말하기"
        koreanBtn.setTextColor(ContextCompat.getColor(this, if (on) R.color.green else R.color.txt))
        updateAsrLabel()
        restartRecognizerSoon()
    }

    /** Interprets my Korean into the other person's language with a Hangul pronunciation guide. */
    private fun interpret(korean: String) {
        if (koreanMode) setKoreanMode(false)
        val target = targetLang()
        interpJob?.cancel()
        currentInterp = Interp(korean, "통역 중…", "", target)
        renderPanel()
        interpJob = lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            storeReady.await()
            val conf = activeConf()
            var result: Interp? = null
            var note = ""
            if (conf.key.isBlank()) note = "API 키 없음"
            else try {
                val o = LlmClient.json(conf, ReplyEngine.buildInterpretPrompt(store.style, korean, target, mode))
                val (text, pron) = ReplyEngine.parseInterpret(o)
                result = Interp(korean, text, pron, target)
            } catch (e: CancellationException) { throw e }
            catch (e: GeminiException) { note = e.message ?: "Gemini 오류" }
            catch (_: Exception) { note = "Gemini 오류" }
            if (result == null) {
                val mt = withTimeoutOrNull(5000L) { translateFromKo(korean, target) }
                if (!mt.isNullOrBlank()) { result = Interp(korean, mt, "", target); note += " · 기기 번역(발음 없음)" }
            }
            val ms = SystemClock.elapsedRealtime() - start
            if (result == null) {
                currentInterp = null
                renderPanel()
                setStatus("통역 실패: " + note.ifBlank { "번역할 수 없습니다" })
                return@launch
            }
            currentInterp = result
            renderPanel()
            latency.text = ms.toString() + " ms"
            history.addInterpretation(korean, result.text, result.pron, target)
            addTranscript("SJK(준비한 말): " + result.text)
            setStatus("🇰🇷→" + ReplyEngine.langName(target) + " 통역 " + ms + "ms" + (if (note.isNotBlank()) " ·$note" else "") +
                " · 이 문장을 소리 내어 말하면 내 말로 기록됩니다")
        }
    }

    private fun setAnswersBody(body: CharSequence) {
        answersBody = body
        renderPanel()
    }

    /** G2 panel = [my interpretation: text / Hangul pronunciation / (Korean)] + suggestions. */
    private fun renderPanel() {
        val i = currentInterp ?: run { answers.text = answersBody; return }
        val sb = SpannableStringBuilder()
        val head = sb.length
        sb.append("🗣 ").append(i.text)
        sb.setSpan(RelativeSizeSpan(1.1f), head, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        fun sub(t: String, color: Int) {
            if (t.isBlank()) return
            val from = sb.length
            sb.append("\n   ").append(t)
            sb.setSpan(ForegroundColorSpan(color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sub(i.pron, Color.parseColor("#B8FFDA"))
        sub("(" + i.ko + ")", Color.parseColor("#4FBF8A"))
        if (answersBody.isNotBlank() && answersBody.toString() != PLACEHOLDER) {
            val from = sb.length
            sb.append("\n\n").append(answersBody)
            sb.setSpan(RelativeSizeSpan(0.8f), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        answers.text = sb
    }

    /** Records an utterance as my own words: no translation request, no suggestions. */
    private fun recordMine(text: String, source: String) {
        if (awaitingMyReply) setMyReplyMode(false)
        if (!history.hasPending) {
            last = Last(Speaker.ME, text)
            setStatus("🙋 내 말: " + text + " (기록할 상대 발화가 아직 없습니다) · 상대 말이었으면 [↺ 바꾸기]")
            return
        }
        addTranscript("SJK: " + text)
        val (prev, matched) = history.setMyReply(text, source)
        last = Last(Speaker.ME, text, prev)
        val why = when (source) { "auto-match" -> "제시안과 같아서 자동 구별"; "corrected" -> "바꾸기로 정정"; else -> "내 말 모드" }
        setStatus("🙋 내 말: " + text + " · " + when (matched) {
            0 -> "①과 일치"; 1 -> "②와 일치"; else -> "제시안과 다름(정답 예시로 기록)"
        } + " · " + why + " · 상대 말이었으면 [↺ 바꾸기]")
    }

    /** [↺ 바꾸기]: the latest utterance was attributed to the wrong person — swap it. */
    private fun flipLast() {
        val l = last ?: run { setStatus("바꿀 발화가 없습니다."); return }
        when (l.speaker) {
            Speaker.OTHER -> {
                replyJob?.cancel()
                history.discardLast()
                prevScreen?.let { restoreScreen(it) } ?: run {
                    other.text = ""; langView.text = "감지 언어: -"; translation.text = ""
                    setAnswersBody(PLACEHOLDER); latency.text = ""; suggestions = emptyList()
                }
                prevScreen = null
                recordMine(l.text, "corrected")
            }
            Speaker.ME -> {
                if (history.hasPending) history.restoreMyReply(l.prevMyReply)
                last = null
                handleUtterance(l.text)
                setStatus("상대 말로 바꿨습니다 · 예상 답변을 만듭니다")
            }
        }
    }

    private fun restoreScreen(sc: Screen) {
        other.text = sc.other; langView.text = sc.lang; translation.text = sc.translation
        setAnswersBody(sc.answers); latency.text = sc.latency; lastOtherLang = sc.otherLang; suggestions = sc.suggestions
    }

    private fun localeFor(lang: String) = when (lang) {
        "ja" -> "ja-JP"; "zh" -> "zh-CN"; "ko" -> "ko-KR"; "en" -> "en-US"; "ru" -> "ru-RU"; else -> asrLocale()
    }

    // ---------------- Detect → retrieve → generate ----------------

    private fun handleUtterance(text: String) {
        replyJob?.cancel()
        prevScreen = Screen(other.text, langView.text, translation.text, answersBody, latency.text, lastOtherLang, suggestions)
        currentInterp = null
        last = Last(Speaker.OTHER, text)
        suggestions = emptyList()
        history.begin(text, "", "", mode)
        val context = if (mode.isInterview) interviewContext() else ""
        val recent = transcript.toList()
        addTranscript((if (mode == Mode.INTERVIEW_ASK) "지원자: " else "면접관: ") + text)
        other.text = text
        translation.text = ""
        setAnswersBody("답변 만드는 중…")
        latency.text = ""
        replyJob = lifecycleScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val lang = detectLanguage(text)
            lastOtherLang = lang
            langView.text = "감지 언어: " + lang + " (" + ReplyEngine.langName(lang) + ")"
            history.setLang(lang)

            val machine = if (lang == "ko") null else withTimeoutOrNull(1500L) { translateToKo(text, lang) }
            if (!machine.isNullOrBlank()) translation.text = machine + "  (기기 번역)"

            val loaded = storeReady.await()
            val s0 = SystemClock.elapsedRealtime()
            val hits = if (loaded) store.search(listOfNotNull(text, machine), 5) else emptyList()
            val facts = if (!loaded) emptyList<Hit>() else relevantFacts(listOfNotNull(text, machine))
            currentFacts = facts
            val searchMs = SystemClock.elapsedRealtime() - s0

            val conf = activeConf()
            val who = conf.provider.label
            if (conf.key.isBlank()) {
                setAnswersBody("(" + who + " API 키가 필요합니다)")
                history.setError("API 키 없음")
                setStatus(who + " API 키가 없습니다. ⚙ 설정에서 입력하세요. · 유사 예시 " + hits.size + "개")
                return@launch
            }
            if (mode.isInterview) setAnswersBody("🎤 " + who + "가 답을 준비하는 중…")
            val start = SystemClock.elapsedRealtime()
            try {
                val json = LlmClient.json(conf, ReplyEngine.buildPrompt(store.style, hits, text, lang, mode, context, recent, facts))
                val result = ReplyEngine.parse(json, lang)
                if (result.translationKo.isNotEmpty()) translation.text = result.translationKo
                if (result.lang != lang) langView.text = "감지 언어: " + lang + " → " + who + ": " + result.lang + " (" + ReplyEngine.langName(result.lang) + ")"
                showAnswers(result)
                val now = SystemClock.elapsedRealtime()
                val ms = now - start
                latency.text = ms.toString() + " ms"
                lastOtherLang = result.lang
                history.setSuggestions(result, ms, hits)
                setStatus(who + " 완료 · 감지·번역 " + (s0 - t0) + "ms · 검색 " + searchMs + "ms(예시 " + hits.size + "개) · 생성 " + ms + "ms · 전체 " + (now - t0) + "ms")
            } catch (e: CancellationException) { throw e }
            catch (e: GeminiException) {
                setAnswersBody("(답변을 만들지 못했습니다)")
                history.setError(e.message ?: "AI 오류")
                setStatus("답변 생성 실패: " + (e.message ?: "AI 오류"))
            } catch (_: Exception) {
                setAnswersBody("(답변을 만들지 못했습니다)")
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
            Regex("[\\u0400-\\u04ff]").containsMatchIn(text) -> "ru"
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

    private fun translatorFromKo(lang: String): Translator? {
        val tgt = TranslateLanguage.fromLanguageTag(lang) ?: return null
        if (tgt == TranslateLanguage.KOREAN) return null
        return translators.getOrPut("ko>" + tgt) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.KOREAN).setTargetLanguage(tgt).build())
        }
    }

    private suspend fun translateFromKo(text: String, lang: String): String? = try {
        translatorFromKo(lang)?.let { t ->
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            t.translate(text).await()
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private suspend fun translateToKo(text: String, lang: String): String? = try {
        translator(lang)?.let { t ->
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            t.translate(text).await()
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private fun showAnswers(r: ReplyResult) {
        currentResult = r
        previewItems = r.next
        renderAnswers()
    }

    /**
     * Suggestions: English → original / Korean meaning; other languages → original / meaning / Hangul pronunciation.
     * 면접 adds the question's intent (💡) on top and the likely next turns (🔮) below.
     */
    private fun renderAnswers() {
        val sb = SpannableStringBuilder()
        val sub = Color.parseColor("#4FBF8A")
        val dim = Color.parseColor("#8FD9B6")
        fun line(s: String, color: Int = sub, size: Float = 0.85f) {
            if (s.isEmpty()) return
            val from = sb.length
            sb.append("\n   ").append(s)
            sb.setSpan(ForegroundColorSpan(color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(size), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val r = currentResult
        if (r != null) {
            if (r.tipKo.isNotEmpty()) {
                val from = sb.length
                sb.append("💡 ").append(r.tipKo).append("\n\n")
                sb.setSpan(ForegroundColorSpan(dim), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.8f), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val mark = if (mode == Mode.INTERVIEW_ASK) listOf("Q① ", "Q② ") else listOf("① ", "② ")
            r.answers.forEachIndexed { i, a ->
                if (i > 0) sb.append("\n\n")
                sb.append(mark.getOrElse(i) { "· " }).append(a.text)
                if (r.lang != "ko") line(a.koMeaning)
                if (r.lang != "en" && r.lang != "ko") line(a.koPron)
            }
            val used = r.evidence.mapNotNull { currentFacts.getOrNull(it - 1) }.map { ReplyEngine.factTopic(it) }.distinct()
            if (used.isNotEmpty()) {
                val from = sb.length
                sb.append("\n📎 근거: ").append(used.joinToString(" · "))
                sb.setSpan(ForegroundColorSpan(dim), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.75f), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (mode.isInterview && factStore.size > 0) {
                val from = sb.length
                sb.append("\n📎 근거 카드 없음 · 일반적인 답")
                sb.setSpan(ForegroundColorSpan(dim), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.75f), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (previewItems.isNotEmpty()) {
            val lang = r?.lang ?: targetLang()
            if (sb.isNotEmpty()) sb.append("\n\n")
            val head = sb.length
            sb.append(if (mode == Mode.INTERVIEW_ASK) "🔮 예상 질문과 지원자 답" else "🔮 다음에 나올 질문")
            sb.setSpan(ForegroundColorSpan(dim), head, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), head, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            previewItems.forEach { n ->
                val from = sb.length
                sb.append("\n• ").append(n.text)
                sb.setSpan(RelativeSizeSpan(0.85f), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (lang != "ko") line(n.koMeaning, sub, 0.75f)
                if (lang != "en" && lang != "ko") line(n.koPron, sub, 0.75f)
                if (n.answer.isNotEmpty()) line("💬 " + n.answer, Color.parseColor("#B8FFDA"), 0.8f)
                if (n.answerKo.isNotEmpty() && lang != "ko") line("   " + n.answerKo, sub, 0.7f)
            }
        }
        setAnswersBody(if (sb.isEmpty()) PLACEHOLDER else sb)
        suggestions = (r?.answers?.map { it.text } ?: emptyList()) + previewItems.map { it.answer }.filter { it.isNotBlank() }
    }

    // ---------------- Settings / export ----------------

    private fun showSettings() {
        val current = settings()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val key = EditText(this).apply {
            hint = "Gemini API 키"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(current.key)
        }
        val model = EditText(this).apply { hint = "모델명"; setSingleLine(true); setText(current.model) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }
        fun secret(h: String, v: String) = EditText(this).apply {
            hint = h; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true); setText(v)
        }
        fun plain(h: String, v: String) = EditText(this).apply { hint = h; setSingleLine(true); setText(v) }
        val geminiLabel = label("💬 회화 모드 AI: Gemini")
        val providerLabel = label("🎤 면접 모드 AI")
        val providerGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val currentProvider = interviewConf().provider
        Provider.values().forEach { pv ->
            providerGroup.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = pv.key; text = pv.label; isChecked = pv == currentProvider
            })
        }
        val claudeKey = secret("Claude(Anthropic) API 키", prefs.getString("claude_key", "") ?: "")
        val claudeModel = plain("Claude 모델 (기본 " + Provider.CLAUDE.defaultModel + ")", prefs.getString("claude_model", Provider.CLAUDE.defaultModel) ?: "")
        val openaiKey = secret("ChatGPT(OpenAI) API 키", prefs.getString("openai_key", "") ?: "")
        val openaiModel = plain("ChatGPT 모델 (기본 " + Provider.OPENAI.defaultModel + ")", prefs.getString("openai_model", Provider.OPENAI.defaultModel) ?: "")
        val langLabel = TextView(this).apply { text = "상대 언어 (휴대폰 마이크 인식 · 내 한국어 통역 대상)"; setPadding(0, pad / 2, 0, 0) }
        val group = RadioGroup(this)
        LOCALES.forEachIndexed { i, loc ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = loc
                text = ReplyEngine.langName(loc) + " (" + loc + ")" + if (i == 0) " · 기본" else ""
                isChecked = loc == asrLocale()
            })
        }
        val note = TextView(this).apply { text = "상대 발화와 면접 정보는 선택한 AI(Gemini·Claude·ChatGPT)에 전송됩니다. 기록 " + history.count() + "건" }
        val export = Button(this).apply { text = "기록 내보내기 (history.jsonl)" }
        val personalLabel = label("👤 개인 데이터: " + personalSummary())
        val importBtn = Button(this).apply { text = "개인 데이터 가져오기 (persona_bundle.json)" }
        val clearBtn = Button(this).apply { text = "개인 데이터 삭제 (샘플로 되돌리기)" }
        listOf(geminiLabel, key, model, providerLabel, providerGroup, claudeKey, claudeModel, openaiKey, openaiModel, langLabel, group, personalLabel, importBtn, clearBtn, note, export)
            .forEach { box.addView(it) }
        fun enteredProvider() = Provider.of(providerGroup.findViewById<RadioButton>(providerGroup.checkedRadioButtonId)?.tag as? String)
        fun enteredInterview(): LlmConfig = when (val pv = enteredProvider()) {
            Provider.GEMINI -> LlmConfig(pv, key.text.toString().trim(), model.text.toString().trim().ifEmpty { GeminiClient.DEFAULT_MODEL })
            Provider.CLAUDE -> LlmConfig(pv, claudeKey.text.toString().trim(), claudeModel.text.toString().trim().ifEmpty { pv.defaultModel })
            Provider.OPENAI -> LlmConfig(pv, openaiKey.text.toString().trim(), openaiModel.text.toString().trim().ifEmpty { pv.defaultModel })
        }
        val dialog = AlertDialog.Builder(this).setTitle("설정")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("취소", null).setNeutralButton("연결 테스트", null).setPositiveButton("저장", null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        fun entered() = GeminiSettings(key.text.toString().trim(), model.text.toString().trim().ifEmpty { GeminiClient.DEFAULT_MODEL })
        export.setOnClickListener { dialog.dismiss(); exportHistory() }
        importBtn.setOnClickListener {
            dialog.dismiss()
            try { pickBundle.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
            catch (_: Exception) { setStatus("파일 선택 화면을 열 수 없습니다.") }
        }
        clearBtn.setOnClickListener {
            AlertDialog.Builder(this).setMessage("가져온 개인 데이터(경력 카드·면접 답·말투)를 이 휴대폰에서 지웁니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제") { _, _ ->
                    PersonaStore.userDir(this).deleteRecursively()
                    loadStores()
                    dialog.dismiss()
                    setStatus("개인 데이터를 지웠습니다. 샘플 데이터로 동작합니다.")
                }.show()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = entered()
            geminiPrefs.edit().putString("api_key", v.key).putString("model", v.model).apply()
            prefs.edit().putString("interview_provider", enteredProvider().key)
                .putString("claude_key", claudeKey.text.toString().trim())
                .putString("claude_model", claudeModel.text.toString().trim().ifEmpty { Provider.CLAUDE.defaultModel })
                .putString("openai_key", openaiKey.text.toString().trim())
                .putString("openai_model", openaiModel.text.toString().trim().ifEmpty { Provider.OPENAI.defaultModel })
                .apply()
            val loc = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String ?: asrLocale()
            val changed = loc != asrLocale()
            prefs.edit().putString("asr_locale", loc).apply()
            updateAsrLabel()
            if (changed && !koreanMode && !awaitingMyReply) restartRecognizerSoon()
            if (changed) lifecycleScope.launch {
                try {
                    translator(targetLang())?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await()
                    translatorFromKo(targetLang())?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await()
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            setStatus("설정 저장 · 상대 언어 " + ReplyEngine.langName(loc) + " · 면접 AI " + enteredProvider().label +
                (if (v.key.isBlank()) " · Gemini 키 없음" else "") + (if (enteredInterview().key.isBlank()) " · 면접 AI 키 없음" else ""))
            dialog.dismiss()
        }
        val test = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        test.setOnClickListener {
            test.isEnabled = false
            note.text = "연결 테스트 중…"
            lifecycleScope.launch {
                suspend fun check(name: String, block: suspend () -> Unit): String = try { block(); "$name 연결 성공" }
                    catch (e: CancellationException) { throw e }
                    catch (e: GeminiException) { "$name 실패: " + e.message }
                    catch (_: Exception) { "$name 실패: 설정을 확인하세요." }
                val lines = mutableListOf(check("Gemini(회화)") { GeminiClient.test(entered()) })
                val iv = enteredInterview()
                if (iv.provider != Provider.GEMINI) lines += check(iv.provider.label + "(면접)") { LlmClient.test(iv) }
                note.text = lines.joinToString("\n")
                test.isEnabled = true
            }
        }
    }

    private fun personalSummary(): String {
        val dir = PersonaStore.userDir(this)
        if (!File(dir, "facts.jsonl").isFile && !File(dir, "interview_pairs.jsonl").isFile) return "없음 (샘플 사용 중)"
        fun lines(n: String) = File(dir, n).let { f -> if (f.isFile) f.readLines().count { it.isNotBlank() } else 0 }
        return "경력 카드 " + lines("facts.jsonl") + "개 · 면접 답 " + lines("interview_pairs.jsonl") + "개" +
            (prefs.getString("persona_imported_at", null)?.let { " · $it 가져옴" } ?: "")
    }

    /**
     * Imports persona_bundle.json: {"version":1, "style": "...", "interview_context": "...",
     * "facts":[{"topic","text"}], "interview_pairs":[{"q","a","lang"}], "conversation_pairs":[...]}.
     * Written to app-private storage only.
     */
    private fun importBundle(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val counts = withContext(Dispatchers.IO) {
                    val raw = contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > 5_000_000) throw IllegalArgumentException("too large")
                        String(bytes, Charsets.UTF_8)
                    } ?: throw IllegalArgumentException("empty")
                    val o = org.json.JSONObject(raw)
                    val facts = o.optJSONArray("facts") ?: org.json.JSONArray()
                    val interview = o.optJSONArray("interview_pairs") ?: org.json.JSONArray()
                    val conv = o.optJSONArray("conversation_pairs")
                    if (facts.length() == 0 && interview.length() == 0) throw IllegalArgumentException("no data")
                    val dir = PersonaStore.userDir(this@MainActivity).apply { deleteRecursively(); mkdirs() }
                    o.optString("style").takeIf { it.isNotBlank() }?.let { File(dir, "style.md").writeText(it) }
                    File(dir, "facts.jsonl").writeText(buildString {
                        for (i in 0 until facts.length()) {
                            val f = facts.optJSONObject(i) ?: continue
                            val text = f.optString("text").trim()
                            if (text.isEmpty()) continue
                            val topic = f.optString("topic").trim().ifEmpty { "사실" }
                            append(org.json.JSONObject().put("q", "$topic | $text").put("a", text).put("lang", "ko").toString()).append('\n')
                        }
                    })
                    fun writePairs(arr: org.json.JSONArray, name: String) = File(dir, name).writeText(buildString {
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { append(it.toString()).append('\n') }
                    })
                    if (interview.length() > 0) writePairs(interview, "interview_pairs.jsonl")
                    if (conv != null && conv.length() > 0) writePairs(conv, "pairs.jsonl")
                    Triple(facts.length(), interview.length(), o.optString("interview_context").trim())
                }
                if (counts.third.isNotEmpty()) {
                    contextInput.setText(counts.third)
                    prefs.edit().putString("interview_context", counts.third).apply()
                }
                prefs.edit().putString("persona_imported_at",
                    java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA).format(java.util.Date())).apply()
                loadStores()
                storeReady.await()
                setStatus("👤 개인 데이터 가져오기 완료 · 경력 카드 " + counts.first + "개 · 면접 답 " + counts.second + "개 · 면접 모드에서 근거로 씁니다")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                setStatus("개인 데이터 파일을 읽지 못했습니다. persona_bundle.json 형식인지 확인하세요.")
            }
        }
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
        /** Share of the utterance found in a shown suggestion above which it is taken as my own words. */
        private const val MATCH_ME = 0.7
        /** Shorter utterances ("Yes", "OK") are never auto-assigned to me; use [↺ 바꾸기] if needed. */
        private const val MIN_AUTO_CHARS = 6
        private val LOCALES = listOf("en-US", "ja-JP", "zh-CN", "ru-RU", "ko-KR")
        private const val ALL_FACTS_LIMIT = 80
        private const val PLACEHOLDER = "G2 미리보기 · 예상 답변이 여기에 표시됩니다."
        private val HANGUL = Regex("[\\uac00-\\ud7a3]")
    }
}
