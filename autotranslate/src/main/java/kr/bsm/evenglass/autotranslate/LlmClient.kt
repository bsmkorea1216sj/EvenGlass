package kr.bsm.evenglass.autotranslate

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.ServiceUnavailableException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Which AI answers in 면접 mode (회화 mode always uses Gemini). */
internal enum class Provider(val key: String, val label: String, val defaultModel: String) {
    GEMINI("gemini", "Gemini", GeminiClient.DEFAULT_MODEL),
    CLAUDE("claude", "Claude", "claude-opus-5-5"),
    OPENAI("openai", "ChatGPT", "gpt-5-mini");

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: CLAUDE
    }
}

internal data class LlmConfig(val provider: Provider, val key: String, val model: String)

/** One JSON-returning prompt call to Gemini, Claude (official Anthropic Java SDK) or ChatGPT (OpenAI REST). */
internal object LlmClient {
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val claudeClients = HashMap<String, AnthropicClient>()

    /** Claude models that accept server-side refusal fallbacks ("default" routing). */
    private val CLAUDE_FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-2", "claude-fable-5-1", "claude-sonnet-5-5")

    suspend fun json(conf: LlmConfig, prompt: String): JSONObject {
        if (conf.key.isBlank()) throw GeminiException(conf.provider.label + " API 키가 없습니다. ⚙ 설정에서 입력하세요.")
        val model = conf.model.trim().ifEmpty { conf.provider.defaultModel }
        if (!model.matches(Regex("[A-Za-z0-9._:-]+"))) throw GeminiException(conf.provider.label + " 모델명이 올바르지 않습니다.")
        return when (conf.provider) {
            Provider.GEMINI -> GeminiClient.generateJson(GeminiSettings(conf.key, model), prompt, fast = true)
            Provider.CLAUDE -> claude(conf.key, model, prompt)
            Provider.OPENAI -> openai(conf.key, model, prompt)
        }
    }

    suspend fun test(conf: LlmConfig) {
        val o = json(conf, "Reply with JSON only: {\"ok\":true}")
        if (!o.has("ok")) throw GeminiException(conf.provider.label + " 응답 형식이 예상과 다릅니다.")
    }

    private fun claudeClient(key: String): AnthropicClient = synchronized(claudeClients) {
        claudeClients.getOrPut(key) {
            AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(30)).maxRetries(1).build()
        }
    }

    private suspend fun claude(key: String, model: String, prompt: String): JSONObject = withContext(Dispatchers.IO) {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            // Low effort keeps the live interview fast; thinking stays adaptive (the model default).
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(prompt)
        if (model in CLAUDE_FALLBACK_MODELS) {
            // If a safety classifier declines, let the API retry on a suitable fallback model.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        try {
            val message = claudeClient(key).messages().create(builder.build())
            val stop = message.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) throw GeminiException("Claude가 이 요청에는 답하지 않았습니다.")
            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            if (stop == StopReason.MAX_TOKENS && !text.trim().endsWith("}")) throw GeminiException("Claude 응답이 길어서 잘렸습니다.")
            parseJsonText(text, "Claude")
        } catch (e: GeminiException) { throw e }
        catch (e: UnauthorizedException) { throw GeminiException("Claude API 키를 확인하세요.") }
        catch (e: PermissionDeniedException) { throw GeminiException("Claude API 키의 권한을 확인하세요.") }
        catch (e: NotFoundException) { throw GeminiException("Claude 모델명을 확인하세요.") }
        catch (e: RateLimitException) { throw GeminiException("Claude 사용량 한도를 초과했습니다. 잠시 후 다시 시도하세요.") }
        catch (e: OverloadedException) { throw GeminiException("Claude 서버가 혼잡합니다. 잠시 후 다시 시도하세요.") }
        catch (e: InternalServerException) { throw GeminiException("Claude 서버에 일시적인 오류가 있습니다.") }
        catch (e: ServiceUnavailableException) { throw GeminiException("Claude 서버에 일시적인 오류가 있습니다.") }
        catch (e: BadRequestException) { throw GeminiException("Claude 요청 오류입니다. 모델명과 설정을 확인하세요.") }
        catch (e: AnthropicServiceException) { throw GeminiException("Claude 요청에 실패했습니다.") }
        catch (e: AnthropicIoException) { throw GeminiException("네트워크 연결을 확인하세요.") }
        catch (e: Exception) { throw GeminiException("Claude 응답을 처리하지 못했습니다.") }
    }

    private suspend fun openai(key: String, model: String, prompt: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("response_format", JSONObject().put("type", "json_object"))
        if (model.startsWith("gpt-5")) body.put("reasoning_effort", "minimal")
        try {
            val req = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw GeminiException(when (res.code) {
                    400 -> "ChatGPT 요청 오류입니다. 모델명과 설정을 확인하세요."
                    401, 403 -> "ChatGPT(OpenAI) API 키를 확인하세요."
                    404 -> "ChatGPT 모델명을 확인하세요."
                    429 -> "ChatGPT 사용량 한도를 초과했거나 결제 설정이 필요합니다."
                    in 500..599 -> "ChatGPT 서버에 일시적인 오류가 있습니다."
                    else -> "ChatGPT 요청 실패(HTTP " + res.code + ")"
                })
                val raw = JSONObject(res.body?.string() ?: throw GeminiException("ChatGPT 응답이 없습니다."))
                val msg = raw.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                if (!msg.isNull("refusal")) throw GeminiException("ChatGPT가 이 요청에는 답하지 않았습니다.")
                parseJsonText(msg.optString("content"), "ChatGPT")
            }
        } catch (e: GeminiException) { throw e }
        catch (e: java.io.InterruptedIOException) { throw GeminiException("ChatGPT 응답 시간 초과(30초).") }
        catch (e: IOException) { throw GeminiException("네트워크 연결을 확인하세요.") }
        catch (e: Exception) { throw GeminiException("ChatGPT 응답을 처리하지 못했습니다.") }
    }

    /** Parses a JSON object from model text, tolerating code fences or a sentence around it. */
    private fun parseJsonText(text: String, who: String): JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw GeminiException(who + " 응답이 JSON 형식이 아닙니다.")
        return try { JSONObject(text.substring(start, end + 1)) }
        catch (_: Exception) { throw GeminiException(who + " 응답이 JSON 형식이 아닙니다.") }
    }
}
