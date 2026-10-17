package app.songsmith

import android.media.MediaMetadataRetriever
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Google Lyria 3.5 through the Gemini API: turns a detailed brief into finished stereo audio. */
object Lyria {
    const val MODEL = "lyria-3.5"
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(6, TimeUnit.MINUTES).callTimeout(8, TimeUnit.MINUTES).build()

    class Result(val audio: File, val text: String)

    suspend fun generate(key: String, prompt: String, out: File): Result = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw IllegalStateException("Add your Google Gemini API key in Settings to make real audio.")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", key.trim())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val raw = res.body?.string() ?: ""
            if (!res.isSuccessful) {
                val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull() ?: ""
                throw IllegalStateException(when (res.code) {
                    400 -> if (msg.contains("safety", true) || msg.contains("block", true)) "Google declined that request. Try describing it differently (no artist names or existing lyrics)." else "Google couldn't use that request: $msg"
                    401, 403 -> "Your Google key was rejected, or billing isn't switched on for it. Check it in Settings."
                    429 -> "You've hit Google's limit for now. Try again in a minute."
                    else -> "Google's music service returned an error (${res.code}). Try again shortly."
                })
            }
            val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Google returned no music. Try a different description.")
            var audio: String? = null
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                if (inline != null) audio = inline.optString("data") else if (p.has("text")) text.append(p.optString("text")).append("\n")
            }
            val data = audio ?: throw IllegalStateException("Google returned no audio. Try a different description.")
            out.parentFile?.mkdirs()
            out.writeBytes(Base64.decode(data, Base64.DEFAULT))
            Result(out, text.toString().trim())
        }
    }

    fun durationSeconds(f: File): Double = runCatching {
        MediaMetadataRetriever().run {
            setDataSource(f.path)
            val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            release(); ms / 1000.0
        }
    }.getOrDefault(0.0)

    /* ---------------- briefs ---------------- */

    private val STYLE = "High-energy electronic dance music (techno, acid, trance, rave): melancholic minor-key harmony carried by driving energy, " +
        "a punchy four-on-the-floor kick, crisp 909-style drums, a rolling 303 acid bassline, rave stabs, soaring supersaw leads, risers and snare rolls into the drops, " +
        "a long emotional breakdown, a huge drop, sidechain pumping and a loud, clean club-ready master. No jazz, R&B, pop-ballad or trumpet sounds."

    private fun vocalLine(v: Voices): String = when {
        v.combo() == "none" -> "Instrumental only, no vocals."
        else -> "Vocals: " + listOfNotNull(
            if (v.female && v.male) "a male and a female lead singing together" else if (v.female) "a female lead singer" else if (v.male) "a male lead singer" else null,
            if (v.choir) "a choir for big harmonies in the drops" else null,
        ).joinToString(" with ") + ". Original lyrics only."
    }

    private fun sampleLines(samples: List<SampleMeta>): String = if (samples.isEmpty()) "" else
        "\nBuild it around these sounds the user loves (recreate the feel, don't copy any recording): " +
            samples.joinToString("; ") { m -> "${SAMPLE_ROLE_LABEL[m.role]} \"${m.name}\"" + (m.bpm?.let { " at about $it BPM" } ?: "") + (m.key?.let { " in $it" } ?: "") + (if (m.note.isNotBlank()) " (they love: ${m.note})" else "") } + "."

    /** A ready brief when there's no Claude key. */
    fun templatePrompt(brief: String, voices: Voices, length: String, samples: List<SampleMeta>): String {
        val full = length != "short"
        return buildString {
            append(brief.trim().ifEmpty { "An original high-energy dance track." }).append("\n\n")
            append(STYLE).append("\n")
            append(vocalLine(voices)).append(sampleLines(samples)).append("\n")
            if (full) append("About 3 minutes. Structure: [0:00] DJ-friendly intro with kick and hats; [0:30] build with acid bass and a snare roll; [1:00] emotional breakdown with no kick; [1:30] massive drop; [2:30] outro.")
            else append("About 1 minute: short intro, build, then straight into a big drop.")
        }
    }

    /** Instructions for Claude to act as the producer and write the brief Lyria will follow. */
    fun producerRequest(brief: String, voices: Voices, length: String, samples: List<SampleMeta>, change: String? = null, previous: String? = null): String = """
You are a top electronic music producer writing a generation brief for Google's Lyria 3.5 music model, which turns a text brief into a finished, mastered track.

THE USER'S IDEA:
${brief.ifBlank { "(no description: build it from their samples and taste)" }}
${if (change != null) "\nTHEY HEARD THE LAST VERSION AND WANT THIS CHANGE:\n$change\nThe previous brief was:\n$previous\n" else ""}
THEIR TASTE (apply unless the idea clearly asks otherwise):
$STYLE

${vocalLine(voices)}${sampleLines(samples)}

Write a vivid, specific brief of at most 2,000 characters:
- Genre and sub-genre, exact BPM, key, mood and energy arc.
- Instruments and sound design in producer language (kick character, bass type, lead timbre, FX, mix and master).
- A timestamped structure for ${if (length == "short") "about 1 minute" else "about 3 minutes"}, e.g. [0:00] Intro ... [0:32] Build ...
- ${if (voices.combo() == "none") "State clearly: instrumental only, no vocals." else "Original lyrics written out under section tags like [Verse 1], [Chorus], [Bridge], singable and emotional."}
- Never name real artists, songs or labels, and never reuse existing lyrics.

Reply with ONLY a JSON object:
{"title":"...","genre":"...","bpm":138,"key":"A minor","summary":"one or two sentences for the user","brief":"the full Lyria brief"}
""".trim()

    /** A sung learning song: the lesson's lines as lyrics with section tags. */
    fun learningPrompt(lesson: Lesson, style: String): String {
        val sound = when (style) {
            "chant" -> "a catchy chant over a bouncy beat at about 95 BPM"
            "dance" -> "an upbeat, high-energy dance track at about 122 BPM"
            "calm" -> "a calm, gentle song at about 80 BPM with soft piano and pads"
            else -> "a bright, happy clap-along pop song at about 104 BPM"
        }
        val young = isYoung(lesson.age)
        val voice = if (young) "a warm, friendly lead singer with a group of children answering the shout-back lines" else "a confident, clear lead vocalist with a crowd answering the shout-back lines"
        val lyrics = StringBuilder()
        var verse = 0; var chorusDone = false
        for (s in lesson.sections) {
            when (s.type) {
                "chorus" -> { lyrics.append("[Chorus]\n"); s.lines.forEach { lyrics.append(it.text).append("\n") }; chorusDone = true }
                "verse" -> { verse++; lyrics.append("[Verse $verse]\n"); s.lines.forEach { lyrics.append(it.text).append("\n") } }
                "call" -> { lyrics.append("[Call and response]\n"); s.lines.forEach { lyrics.append(it.text).append("\n(").append(it.answer).append(")\n") } }
                "quiz" -> { lyrics.append("[Bridge]\n"); s.lines.forEach { lyrics.append(it.text).append("\n(").append(it.answer).append(")\n") } }
            }
            lyrics.append("\n")
        }
        if (!chorusDone) lyrics.append("[Chorus]\n").append(lesson.keyFacts.take(4).joinToString("\n")).append("\n")
        return """
A learning song that helps ${if (young) "children" else "learners"} remember facts. Style: $sound. Vocals: $voice. Every word must be sung very clearly and slowly enough to understand, with the chorus extra catchy. Lines in brackets are shouted back by the group.
Language: ${lesson.language}.

Lyrics:
$lyrics
""".trim()
    }
}
