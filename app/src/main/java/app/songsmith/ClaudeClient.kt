package app.songsmith

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ClaudeException(message: String) : Exception(message)

/** Minimal streaming client for the Anthropic Messages API. */
class ClaudeClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    suspend fun complete(apiKey: String, model: String, prompt: String, maxTokens: Int = 32000, images: List<String> = emptyList(), onChars: (Int) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw ClaudeException("Add your Anthropic API key in Settings first.")
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("stream", true)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                    if (images.isEmpty()) prompt else JSONArray().apply {
                        images.forEach { img ->
                            put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", img)))
                        }
                        put(JSONObject().put("type", "text").put("text", prompt))
                    })))
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = http.newCall(req)
            try {
                call.execute().use { res ->
                    if (!res.isSuccessful) {
                        val msg = runCatching { JSONObject(res.body?.string() ?: "").getJSONObject("error").getString("message") }.getOrNull()
                        throw ClaudeException(
                            when (res.code) {
                                401 -> "Your API key was rejected. Check it in Settings."
                                429 -> "You've hit a rate or usage limit. Try again in a bit."
                                529, 503 -> "Claude is busy right now. Try again shortly."
                                else -> "Claude returned an error (${res.code})" + (msg?.let { ": $it" } ?: ".")
                            }
                        )
                    }
                    val src = res.body?.source() ?: throw ClaudeException("Empty response from Claude.")
                    val sb = StringBuilder()
                    var stopReason = ""
                    var lastReport = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data.isEmpty()) continue
                        val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
                        when (ev.optString("type")) {
                            "content_block_delta" -> {
                                val d = ev.optJSONObject("delta")
                                if (d?.optString("type") == "text_delta") {
                                    sb.append(d.optString("text"))
                                    if (sb.length - lastReport > 512) { lastReport = sb.length; onChars(sb.length) }
                                }
                            }
                            "message_delta" -> stopReason = ev.optJSONObject("delta")?.optString("stop_reason") ?: stopReason
                            "error" -> throw ClaudeException(ev.optJSONObject("error")?.optString("message") ?: "Claude reported an error.")
                        }
                    }
                    if (stopReason == "max_tokens") throw ClaudeException("The song was too long and got cut off. Try a shorter song.")
                    sb.toString()
                }
            } catch (e: IOException) {
                if (call.isCanceled()) throw e
                throw ClaudeException("Couldn't reach Claude. Check your connection.")
            } finally {
                call.cancel()
            }
        }
}
