package app.songsmith

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Google Lyria RealTime (experimental, instrumental only). It streams music live over a WebSocket;
 * we record the stream and steer it section by section so the result is a full track.
 */
object LyriaLive {
    const val MODEL = "models/lyria-realtime-exp"
    private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateMusic"
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    class Part(val at: Double, val prompts: List<Pair<String, Double>>, val density: Double, val brightness: Double, val muteDrums: Boolean = false)
    class Plan(val title: String, val bpm: Int, val scale: String, val seconds: Double, val parts: List<Part>, val summary: String)

    val SCALES = listOf("C_MAJOR_A_MINOR", "D_FLAT_MAJOR_B_FLAT_MINOR", "D_MAJOR_B_MINOR", "E_FLAT_MAJOR_C_MINOR", "E_MAJOR_D_FLAT_MINOR",
        "F_MAJOR_D_MINOR", "G_FLAT_MAJOR_E_FLAT_MINOR", "G_MAJOR_E_MINOR", "A_FLAT_MAJOR_F_MINOR", "A_MAJOR_G_FLAT_MINOR", "B_FLAT_MAJOR_G_MINOR", "B_MAJOR_A_FLAT_MINOR")
    private val MINOR_NAME = mapOf("C_MAJOR_A_MINOR" to "A minor", "D_FLAT_MAJOR_B_FLAT_MINOR" to "B♭ minor", "D_MAJOR_B_MINOR" to "B minor",
        "E_FLAT_MAJOR_C_MINOR" to "C minor", "E_MAJOR_D_FLAT_MINOR" to "C# minor", "F_MAJOR_D_MINOR" to "D minor", "G_FLAT_MAJOR_E_FLAT_MINOR" to "E♭ minor",
        "G_MAJOR_E_MINOR" to "E minor", "A_FLAT_MAJOR_F_MINOR" to "F minor", "A_MAJOR_G_FLAT_MINOR" to "F# minor", "B_FLAT_MAJOR_G_MINOR" to "G minor", "B_MAJOR_A_FLAT_MINOR" to "G# minor")
    fun keyName(scale: String) = MINOR_NAME[scale] ?: ""

    /* ---------------- planning ---------------- */

    private fun styleOf(text: String): Pair<String, Int> = when {
        Regex("psy|goa", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "driving psytrance, rolling 16th-note bassline, hypnotic acid arps" to 145
        Regex("hard techno|industrial|schranz|warehouse", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "hard techno, distorted rumbling kick, industrial percussion, dark stabs" to 150
        Regex("drum ?(&|and|n) ?bass|dnb", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "drum and bass, fast breakbeats, deep reese bass, atmospheric pads" to 174
        Regex("breakbeat|\\bbreaks\\b|rave|jungle", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "90s rave, breakbeats, hoover stabs, piano stabs, euphoric energy" to 140
        Regex("melodic|progressive", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "melodic techno, hypnotic arpeggios, deep rolling bass, emotional pads" to 124
        Regex("trance|euphoric|uplifting", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "uplifting trance, supersaw leads, rolling offbeat bass, emotional chords" to 138
        Regex("acid|303", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "acid techno, squelchy TB-303 bassline, pounding 909 drums" to 138
        else -> "melancholic acid trance, TB-303 bassline, 909 drums, supersaw leads" to 138
    }

    /** A ready plan when there's no Claude key. */
    fun templatePlan(brief: String, length: String, samples: List<SampleMeta>, seed: Long): Plan {
        val (style, defaultBpm) = styleOf(brief)
        val bpm = Regex("(\\d{2,3})\\s*bpm", RegexOption.IGNORE_CASE).find(brief)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 60..200 } ?: defaultBpm
        val r = Random(seed)
        val scale = listOf("C_MAJOR_A_MINOR", "F_MAJOR_D_MINOR", "G_MAJOR_E_MINOR", "B_FLAT_MAJOR_G_MINOR", "E_FLAT_MAJOR_C_MINOR").random(r)
        val feel = samples.joinToString(", ") { it.note.ifBlank { it.name } }.take(120)
        val base = listOf(brief.take(160), style, "melancholic, high energy", feel).filter { it.isNotBlank() }.joinToString(", ")
        val full = length != "short"
        val parts = if (full) listOf(
            Part(0.0, listOf(base to 1.0, "minimal intro, kick and hi-hats, filtered" to .7), .35, .40),
            Part(28.0, listOf(base to 1.0, "rising tension, acid bassline enters, snare roll build up" to .9), .62, .58),
            Part(58.0, listOf(base to .8, "emotional breakdown, no drums, lush pads, sad beautiful melody" to 1.0), .22, .45, muteDrums = true),
            Part(86.0, listOf(base to 1.0, "huge build up, white noise riser, snare roll" to 1.0), .7, .7),
            Part(98.0, listOf(base to 1.0, "massive drop, pounding four on the floor kick, rolling bass, soaring lead, peak energy" to 1.2), .92, .78),
            Part(150.0, listOf(base to 1.0, "outro, stripped back, kick and bass, fading energy" to .8), .45, .5),
        ) else listOf(
            Part(0.0, listOf(base to 1.0, "short intro, kick and hats" to .7), .4, .45),
            Part(10.0, listOf(base to 1.0, "build up, snare roll, riser" to 1.0), .7, .65),
            Part(22.0, listOf(base to 1.0, "massive drop, peak energy, pounding kick, soaring lead" to 1.2), .92, .78),
            Part(54.0, listOf(base to 1.0, "outro, stripped back" to .8), .45, .5),
        )
        val title = Generator.compose(brief, Voices(false, false, false), "short", emptyList(), seed).title
        return Plan(title, bpm, scale, if (full) 180.0 else 62.0, parts, "Instrumental ${style.substringBefore(",")} at $bpm BPM, recorded live with Google Lyria RealTime.")
    }

    /** Instructions for Claude to plan the live performance. */
    fun producerRequest(brief: String, length: String, samples: List<SampleMeta>, change: String?, previous: String?): String = """
You are an electronic music producer performing live with Google's Lyria RealTime model. It makes instrumental music only, steered by short weighted text prompts plus density (0-1, how busy) and brightness (0-1, how bright the tone), and can mute drums. You will change these at set times to shape a full track.

THE USER'S IDEA: ${brief.ifBlank { "(none: build it from their taste and samples)" }}
${if (change != null) "THEY WANT THIS CHANGE TO THE LAST VERSION: $change\nThe previous plan was: $previous\n" else ""}${if (samples.isNotEmpty()) "Sounds they love (recreate the feel in words): " + samples.joinToString("; ") { "${it.name}${if (it.note.isNotBlank()) " (${it.note})" else ""}" } + "\n" else ""}
THEIR TASTE: high-energy electronic dance music (techno, acid, trance, rave), melancholic minor-key harmony, driving four-on-the-floor kick, TB-303 acid bass, supersaw leads, long emotional breakdowns and huge drops. No jazz, R&B, pop or trumpet. Never name real artists or songs.

Plan ${if (length == "short") "about 60 seconds" else "about 180 seconds"} as 4 to 7 parts (intro, build, breakdown with muteDrums, build, drop, outro). Each prompt is a short descriptive phrase (instruments, genre, mood, production), 2 to 3 prompts per part with weights 0.5 to 1.5.
Pick one scale from: ${SCALES.joinToString(", ")}. Pick a bpm between 60 and 200.

Reply with ONLY a JSON object:
{"title":"...","bpm":138,"scale":"C_MAJOR_A_MINOR","summary":"one sentence for the user",
 "parts":[{"at":0,"prompts":[{"text":"...","weight":1.0}],"density":0.4,"brightness":0.4,"muteDrums":false}]}
""".trim()

    fun parsePlan(o: JSONObject, length: String): Plan {
        val pa = o.optJSONArray("parts") ?: JSONArray()
        val parts = (0 until pa.length()).mapNotNull { pa.optJSONObject(it) }.mapNotNull { p ->
            val pr = p.optJSONArray("prompts") ?: return@mapNotNull null
            val prompts = (0 until pr.length()).mapNotNull { pr.optJSONObject(it) }.map { it.optString("text").take(200) to it.optDouble("weight", 1.0).coerceIn(.1, 3.0) }.filter { it.first.isNotBlank() }
            if (prompts.isEmpty()) null else Part(p.optDouble("at", 0.0).coerceAtLeast(0.0), prompts, p.optDouble("density", .5).coerceIn(0.0, 1.0), p.optDouble("brightness", .5).coerceIn(0.0, 1.0), p.optBoolean("muteDrums", false))
        }.sortedBy { it.at }
        if (parts.isEmpty()) throw IllegalStateException("The producer's plan came back empty.")
        val scale = o.optString("scale").takeIf { it in SCALES } ?: "C_MAJOR_A_MINOR"
        return Plan(o.optString("title", "Untitled").take(80), o.optInt("bpm", 138).coerceIn(60, 200), scale,
            if (length == "short") 62.0 else 180.0, parts, o.optString("summary", "").take(300))
    }

    /* ---------------- recording ---------------- */

    /** Records [plan] from the live stream into a WAV file. */
    suspend fun record(key: String, plan: Plan, out: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw IllegalStateException("Add your Google Gemini API key in Settings.")
        var sampleRate = 48000; var channels = 2
        val tmpPcm = File(out.path + ".pcm")
        val pcm = BufferedOutputStream(FileOutputStream(tmpPcm), 1 shl 16)
        var bytes = 0L
        val done = CompletableDeferred<String?>() // null = success, else an error message
        var partIndex = -1
        var ws: WebSocket? = null

        fun send(o: JSONObject) { ws?.send(o.toString()) }
        fun steer(p: Part) {
            send(JSONObject().put("clientContent", JSONObject().put("weightedPrompts", JSONArray().apply {
                p.prompts.forEach { (t, w) -> put(JSONObject().put("text", t).put("weight", w)) }
            })))
            send(JSONObject().put("musicGenerationConfig", JSONObject()
                .put("bpm", plan.bpm).put("scale", plan.scale).put("density", p.density).put("brightness", p.brightness)
                .put("guidance", 4.0).put("temperature", 1.1).put("muteDrums", p.muteDrums)))
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("setup", JSONObject().put("model", MODEL)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handle(bytes.utf8())
            fun handle(text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when {
                    m.has("setupComplete") -> {
                        partIndex = 0; steer(plan.parts[0])
                        send(JSONObject().put("playbackControl", "PLAY"))
                    }
                    m.has("serverContent") -> {
                        if (done.isCompleted) return
                        val chunks = m.getJSONObject("serverContent").optJSONArray("audioChunks") ?: return
                        for (i in 0 until chunks.length()) {
                            val c = chunks.getJSONObject(i)
                            c.optString("mimeType").let { mt ->
                                Regex("rate=(\\d+)").find(mt)?.groupValues?.get(1)?.toIntOrNull()?.let { sampleRate = it }
                                Regex("channels=(\\d+)").find(mt)?.groupValues?.get(1)?.toIntOrNull()?.let { channels = it }
                            }
                            val data = Base64.decode(c.optString("data"), Base64.DEFAULT)
                            pcm.write(data); bytes += data.size
                        }
                        val sec = bytes.toDouble() / (sampleRate * channels * 2)
                        onProgress((sec / plan.seconds).toFloat().coerceIn(0f, 1f))
                        val next = partIndex + 1
                        if (next < plan.parts.size && sec >= plan.parts[next].at) { partIndex = next; steer(plan.parts[next]) }
                        if (sec >= plan.seconds && !done.isCompleted) {
                            send(JSONObject().put("playbackControl", "STOP"))
                            done.complete(null)
                        }
                    }
                    m.has("filteredPrompt") -> {} // a filtered prompt is just ignored by the model
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.complete(friendly(code, reason))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!done.isCompleted) done.complete(friendly(response?.code ?: 0, response?.message ?: t.message ?: ""))
            }
        }
        ws = http.newWebSocket(Request.Builder().url("$URL?key=${key.trim()}").build(), listener)
        val err = withTimeoutOrNull(((plan.seconds + 120) * 1000).toLong()) { done.await() } ?: "The live stream stopped responding. Try again."
        ws?.close(1000, "done")
        pcm.close()
        val minBytes = sampleRate * channels * 2 * 10L
        if (err != null && bytes < minBytes) { tmpPcm.delete(); throw IllegalStateException(err) }
        // Write a WAV (keeping whatever was recorded if the stream ended early).
        val maxBytes = (plan.seconds * sampleRate * channels * 2).toLong()
        val keep = minOf(bytes, maxBytes) - minOf(bytes, maxBytes) % (channels * 2)
        writeWav(tmpPcm, keep, sampleRate, channels, out)
        tmpPcm.delete()
        out
    }

    private fun friendly(code: Int, reason: String): String = when {
        reason.contains("billing", true) || reason.contains("quota", true) || code == 429 -> "Google says this key has no free quota left for live music (or needs billing). Try again later, or use Real audio (Lyria 3.5) or Sketch."
        code == 401 || code == 403 || reason.contains("API key", true) -> "Your Google key was rejected for live music. Check it in Settings."
        reason.isNotBlank() -> "Google's live music stream stopped: $reason"
        else -> "Couldn't connect to Google's live music. Check your internet and try again."
    }

    private fun writeWav(pcm: File, bytes: Long, sr: Int, ch: Int, out: File) {
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(ch.toShort()); h.putInt(sr); h.putInt(sr * ch * 2); h.putShort((ch * 2).toShort()); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(bytes.toInt())
            f.write(h.array())
            pcm.inputStream().use { input ->
                val buf = ByteArray(1 shl 16); var left = bytes
                while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; f.write(buf, 0, n); left -= n }
            }
        }
    }
}
