package kr.bsm.evenglass.autotranslate

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal data class GeminiSettings(val key: String, val model: String)
internal class GeminiException(message: String) : IOException(message)

internal object GeminiClient {
    const val DEFAULT_MODEL = "gemini-flash-latest"
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Connection test: a tiny JSON round trip. */
    suspend fun test(settings: GeminiSettings) {
        val o = generateJson(settings, "JSON으로만 답하세요: {\"ok\":true}", fast = true)
        if (!o.has("ok")) throw GeminiException("응답 형식이 예상과 다릅니다.")
    }

    /**
     * generateContent with responseMimeType=application/json; returns the parsed JSON object.
     * For "flash" models, thinking is turned off first for latency; if the model rejects
     * that option (HTTP 400), the request is retried once without it.
     */
    suspend fun generateJson(settings: GeminiSettings, prompt: String, jpeg: ByteArray? = null, fast: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            if (settings.key.isBlank()) throw GeminiException("Gemini API 키가 없습니다.")
            val model = settings.model.removePrefix("models/").trim()
            if (!model.matches(Regex("[A-Za-z0-9._-]+"))) throw GeminiException("모델명이 올바르지 않습니다.")
            val noThinking = fast && model.contains("flash", ignoreCase = true)
            try {
                call(settings.key, model, prompt, jpeg, noThinking)
            } catch (e: GeminiException) {
                if (noThinking && e.message == MSG_400) call(settings.key, model, prompt, jpeg, false) else throw e
            }
        }

    private fun call(key: String, model: String, prompt: String, jpeg: ByteArray?, noThinking: Boolean): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (jpeg != null) parts.put(JSONObject().put("inlineData", JSONObject()
            .put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val config = JSONObject().put("responseMimeType", "application/json")
        if (noThinking) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config)
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            return client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        400 -> MSG_400
                        401, 403 -> "API 키 또는 접근 권한을 확인하세요."
                        404 -> "모델명을 확인하세요."
                        429 -> "API 사용량 한도를 초과했습니다."
                        in 500..599 -> "Gemini 서버에 일시적인 오류가 있습니다."
                        else -> "Gemini 요청 실패(HTTP " + response.code + ")"
                    }
                    throw GeminiException(message)
                }
                val raw = JSONObject(response.body?.string() ?: throw GeminiException("응답이 없습니다."))
                val output = raw.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val json = buildString {
                    for (i in 0 until output.length()) {
                        val part = output.getJSONObject(i)
                        if (!part.optBoolean("thought", false)) append(part.optString("text", ""))
                    }
                }
                JSONObject(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw GeminiException("Gemini 응답 시간 초과(20초).")
        } catch (e: java.io.InterruptedIOException) {
            throw GeminiException("Gemini 응답 시간 초과(20초).")
        } catch (e: IOException) {
            throw GeminiException("네트워크 연결을 확인하세요.")
        } catch (e: Exception) {
            throw GeminiException("Gemini 응답 형식 또는 설정 오류입니다.")
        }
    }

    private const val MSG_400 = "요청 또는 API 키를 확인하세요."
}
