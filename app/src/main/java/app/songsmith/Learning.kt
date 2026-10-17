package app.songsmith

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/* ============================== the lesson ============================== */

val AGE_BANDS = listOf("4-6", "7-9", "10-13", "14-18", "adult")
fun ageLabel(a: String) = if (a == "adult") "Adult" else a.replace("-", "–")
fun isYoung(a: String) = a in setOf("4-6", "7-9", "10-13")
val KID_STYLES = linkedMapOf("clap" to "Clap-along", "chant" to "Chant", "dance" to "Dance", "calm" to "Calm")

class LessonLine(val text: String, val answer: String = "")
class LessonSection(val type: String, val lines: List<LessonLine>) // chorus | verse | call | quiz
class QuizItem(val q: String, val options: List<String>, val answer: Int, val explain: String)
class TimedLine(val t: Double, val d: Double, val text: String, val who: String)

class Lesson(
    val title: String, val topic: String, val language: String, val keyFacts: List<String>,
    val sections: List<LessonSection>, val quiz: List<QuizItem>, val parentTip: String,
    var timeline: List<TimedLine> = emptyList(), var age: String = "7-9", var style: String = "clap",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("topic", topic).put("language", language).put("parentTip", parentTip)
        .put("age", age).put("style", style)
        .put("keyFacts", JSONArray(keyFacts))
        .put("sections", JSONArray().apply {
            sections.forEach { s ->
                put(JSONObject().put("type", s.type).put("lines", JSONArray().apply {
                    s.lines.forEach { l ->
                        put(when (s.type) {
                            "call" -> JSONObject().put("call", l.text).put("answer", l.answer)
                            "quiz" -> JSONObject().put("q", l.text).put("a", l.answer)
                            else -> l.text
                        })
                    }
                }))
            }
        })
        .put("quiz", JSONArray().apply { quiz.forEach { put(JSONObject().put("q", it.q).put("options", JSONArray(it.options)).put("answer", it.answer).put("explain", it.explain)) } })
        .put("timeline", JSONArray().apply { timeline.forEach { put(JSONObject().put("t", it.t).put("d", it.d).put("text", it.text).put("who", it.who)) } })

    companion object {
        fun parse(o: JSONObject): Lesson {
            val secs = ArrayList<LessonSection>()
            val sa = o.optJSONArray("sections") ?: JSONArray()
            for (i in 0 until min(sa.length(), 16)) {
                val s = sa.optJSONObject(i) ?: continue
                val type = s.optString("type", "verse").lowercase().let { if (it in setOf("chorus", "verse", "call", "quiz")) it else "verse" }
                val la = s.optJSONArray("lines") ?: JSONArray()
                val lines = ArrayList<LessonLine>()
                for (j in 0 until min(la.length(), 16)) {
                    val v = la.opt(j)
                    when {
                        v is JSONObject && type == "call" -> lines.add(LessonLine(v.optString("call"), v.optString("answer")))
                        v is JSONObject && type == "quiz" -> lines.add(LessonLine(v.optString("q"), v.optString("a")))
                        v is JSONObject -> lines.add(LessonLine(v.optString("text", v.toString())))
                        v != null -> lines.add(LessonLine(v.toString()))
                    }
                }
                val clean = lines.filter { it.text.isNotBlank() }
                if (clean.isNotEmpty()) secs.add(LessonSection(type, clean))
            }
            if (secs.isEmpty()) throw IllegalStateException("The learning song came back empty. Try again.")
            val quiz = ArrayList<QuizItem>()
            val qa = o.optJSONArray("quiz") ?: JSONArray()
            for (i in 0 until min(qa.length(), 10)) {
                val q = qa.optJSONObject(i) ?: continue
                val opts = q.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it.isNotBlank() } ?: continue
                if (opts.size < 2) continue
                quiz.add(QuizItem(q.optString("q"), opts, q.optInt("answer", 0).coerceIn(0, opts.size - 1), q.optString("explain")))
            }
            val tl = o.optJSONArray("timeline")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { TimedLine(it.optDouble("t"), it.optDouble("d"), it.optString("text"), it.optString("who")) } } ?: emptyList()
            return Lesson(
                o.optString("title", "My Learning Song").take(60), o.optString("topic", ""), o.optString("language", "en"),
                o.optJSONArray("keyFacts")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                secs, quiz, o.optString("parentTip", ""), tl, o.optString("age", "7-9"), o.optString("style", "clap"),
            )
        }
    }
}

/* ============================== the teacher (prompt) ============================== */

object LessonPrompt {
    private class Profile(
        val who: String, val facts: String, val syl: String, val words: String, val role: String, val tone: String,
        val extra: String, val quizSize: Int, val options: Int, val tipFor: String,
    )

    private fun profile(age: String) = when (age) {
        "4-6" -> Profile("a child aged 4 to 6", "3 to 4", "6", "very simple words a 5-year-old knows", "an experienced foundation-phase teacher, learning designer and children's songwriter",
            "Warm, playful and encouraging, with sounds and actions children can copy.", "", 4, 3, "a parent")
        "7-9" -> Profile("a child aged 7 to 9", "4 to 6", "8", "simple everyday words for an 8-year-old", "an experienced primary-school teacher, learning designer and children's songwriter",
            "Warm, fun and encouraging.", "", 5, 3, "a parent")
        "10-13" -> Profile("a learner aged 10 to 13", "6 to 8", "10", "clear words for a 12-year-old, with subject terms defined", "an experienced senior-phase teacher, learning designer and songwriter",
            "Upbeat and encouraging, never babyish.", "", 5, 3, "a parent")
        "14-18" -> Profile("a high-school learner aged 14 to 18", "7 to 10", "12", "the correct subject terminology for their grade, each term explained once in plain words", "an experienced high-school teacher, exam coach, learning designer and songwriter",
            "Confident and cool, like a track they'd actually play: no nursery-rhyme tone, no talking down. Light humour is welcome.",
            """
- Focus on what gets marks: exact definitions, formulas, key dates, cause and effect, steps of a process, and the command words examiners use.
- Where it helps, build a mnemonic or acronym into the chorus and say what each letter stands for.
- Use contrast pairs for things that are easily confused (for example mitosis versus meiosis).""", 8, 4, "the learner (a study tip)")
        else -> Profile("an adult learner", "7 to 12", "12", "precise adult language with the correct technical terms", "an expert instructional designer, subject-matter teacher and songwriter",
            "Clear, smart and motivating, like a good podcast host. Never childish.",
            """
- Focus on the knowledge they will actually need to recall: definitions, rules, figures, steps, and how the ideas connect.
- Where it helps, build a mnemonic or acronym into the chorus and say what each letter stands for.
- Use contrast pairs for things that are easily confused.""", 8, 4, "the learner (a study tip)")
    }

    fun build(material: String, age: String, photos: Int): String {
        val p = profile(age)
        val photoNote = if (photos > 0) " (also in the $photos attached photo${if (photos > 1) "s" else ""} of the learner's work; read every page carefully, including handwriting, tables and diagrams, and combine them into one song)" else ""
        val young = age in setOf("4-6", "7-9", "10-13")
        return """
You are ${p.role}. You know how memory works: chunking, repetition with identical wording, retrieval practice, rhyme and rhythm as memory hooks, concrete examples the learner can picture, and spaced review.

${p.who.replaceFirstChar { it.uppercase() }} needs to learn the material below. Turn it into a learning song that helps them understand it and remember it.

THE MATERIAL$photoNote:
${material.ifBlank { "(see the photos)" }}

Teaching rules:
1. Accuracy first. Only use facts that are correct and supported by the material or standard curriculum knowledge. Never invent numbers, dates, names, formulas or facts. If the material contains a mistake, use the correct fact and mention it kindly in parentTip.
2. Pick the ${p.facts} most important facts as keyFacts: short, exact sentences in the wording the learner should remember.
3. The chorus holds the key facts in 4 lines with end rhymes, at most ${p.syl} syllables per line. It is repeated with exactly the same words every time.
4. Each verse explains one or two facts with a concrete example the learner can picture. Use ${p.words}. Define any new term in the line itself.
5. A call-and-response section: the song asks a question ("call") and the crowd answers with the key word or phrase ("answer", 1 to 5 words). This is retrieval practice.
6. A quiz section near the end: questions ("q") with short answers ("a"); the song pauses so the learner can answer before hearing it.
7. Every line is spoken in rhythm over a beat, so keep lines short and punchy with natural stress. One idea per line.
8. Write in the same language as the material (for example English or Afrikaans). Put its two-letter code in "language".
9. Tone: ${p.tone} ${if (young) "Everything must be suitable for children. If the material isn't suitable for children, write a gentle general song about learning instead." else "Keep it respectful and suitable for a general audience."}
10. Everything is original writing; never reuse lyrics from existing songs.${p.extra}

Order the sections like this: chorus, verse, chorus, call, verse, chorus, quiz, chorus.

Then write a ${p.quizSize}-question multiple-choice quiz (${p.options} options each, "answer" is the index of the right option, "explain" is one encouraging sentence that teaches why), and a one-sentence parentTip written for ${p.tipFor} on how to practise with spaced review (for example: play it again tonight and tomorrow morning, then just the chorus in three days and again before the test).

Reply with ONLY one JSON object in this shape:
{"title":"The Planets Song","topic":"The eight planets in order","language":"en",
 "keyFacts":["Mercury is closest to the Sun"],
 "sections":[
  {"type":"chorus","lines":["line","line","line","line"]},
  {"type":"verse","lines":["line","line","line","line"]},
  {"type":"call","lines":[{"call":"Which planet is closest to the Sun?","answer":"Mercury!"}]},
  {"type":"quiz","lines":[{"q":"Which planet is the biggest?","a":"Jupiter!"}]}
 ],
 "quiz":[{"q":"Which planet do we live on?","options":["Mars","Earth","Venus"],"answer":1,"explain":"Earth is our home, the third planet from the Sun."}],
 "parentTip":"..."}
""".trim()
    }
}

/* ============================== offline lesson maker ============================== */

/** Without an API key: turns the child's own text into a chant with a chorus, echo answers and a quiz. */
object SimpleLesson {
    fun make(text: String, age: String): Lesson {
        val maxWords = when (age) { "4-6" -> 7; "7-9" -> 9; "10-13" -> 12; else -> 14 }
        val maxFacts = when (age) { "4-6" -> 4; "7-9" -> 6; "10-13" -> 8; else -> 10 }
        val sentences = text.split(Regex("[.!?;\\n]+")).map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.split(" ").size >= 2 }
        val facts = sentences.flatMap { s ->
            val w = s.split(" ")
            if (w.size <= maxWords) listOf(s) else s.split(Regex(",\\s*")).flatMap { part -> part.split(" ").chunked(maxWords).map { it.joinToString(" ") } }
        }.filter { it.split(" ").size >= 2 }.take(maxFacts)
        if (facts.isEmpty()) throw IllegalStateException("Type or say a few sentences of what you need to learn.")
        val topicWords = facts.first().split(" ").take(4).joinToString(" ")
        fun cloze(f: String): Pair<String, String> {
            val w = f.split(" ")
            val n = if (w.size > 4) 2 else 1
            return (w.dropLast(n).joinToString(" ") + "…") to (w.takeLast(n).joinToString(" ") + "!")
        }
        val chorus = LessonSection("chorus", listOf(LessonLine("Learn it, say it, sing it with me!")) + facts.take(min(3, facts.size)).map { LessonLine(it) })
        val half = (facts.size + 1) / 2
        val verse1 = LessonSection("verse", facts.take(half).flatMap { listOf(LessonLine(it), LessonLine("Say it again: $it")) })
        val verse2 = LessonSection("verse", facts.drop(half).ifEmpty { facts.take(1) }.flatMap { listOf(LessonLine(it), LessonLine("One more time: $it")) })
        val call = LessonSection("call", facts.map { cloze(it) }.map { LessonLine(it.first, it.second) })
        val quizLines = LessonSection("quiz", facts.take(4).map { cloze(it) }.map { LessonLine(it.first, it.second) })
        val answers = facts.map { cloze(it).second.trimEnd('!') }
        val quiz = facts.take(5).mapIndexed { i, f ->
            val (q, a) = cloze(f)
            val wrong = answers.filterIndexed { j, _ -> j != i }.distinct().shuffled().take(2).ifEmpty { listOf("I'm not sure") }
            val opts = (wrong + a.trimEnd('!')).shuffled()
            QuizItem(q, opts, opts.indexOf(a.trimEnd('!')), "Remember: $f.")
        }
        return Lesson("Let's learn: $topicWords", topicWords, "en", facts, listOf(chorus, verse1, chorus, call, verse2, chorus, quizLines, chorus), quiz,
            if (isYoung(age)) "Play it again tonight and tomorrow morning, then just sing the chorus together in three days." else "Play it tonight and tomorrow morning, then once more in three days and again the night before the test.")
    }
}

/* ============================== making the song ============================== */

/** Speaks a line into a WAV file. Supplied by the playback service, which owns text-to-speech. */
typealias SpeakToFile = suspend (text: String, file: File, rate: Float, pitch: Float, language: String) -> Boolean

class LearningComposer(private val ctx: Context, private val speak: SpeakToFile) {
    private val prefs = Prefs(ctx)
    private val lib = Library(ctx)
    private val claude = ClaudeClient()

    suspend fun make(material: String, age: String, style: String, photos: List<File>): Entry {
        val usesClaude = prefs.usesClaude
        val lesson = if (usesClaude) {
            Studio.report("Your music teacher is reading the work…")
            val images = withContext(Dispatchers.Default) { photos.take(8).map { jpegBase64(it) } }
            Studio.report("Your music teacher is planning the song…")
            val text = claude.complete(prefs.apiKey, prefs.model, LessonPrompt.build(material, age, images.size), 12000, images) { n ->
                Studio.report("Writing the learning song · ${n / 1024} KB")
            }
            Lesson.parse(extractJson(text))
        } else {
            var all = material
            if (photos.isNotEmpty()) {
                Studio.report("Reading the photos…")
                val read = ocr(photos)
                if (read.isBlank() && material.isBlank()) throw IllegalStateException("Couldn't read any words in those photos. Try brighter, straighter photos of printed text, or add a Claude key in Settings to read handwriting.")
                all = listOf(material, read).filter { it.isNotBlank() }.joinToString("\n")
            }
            if (all.isBlank()) throw IllegalStateException("Say, type or photograph what you need to learn first.")
            SimpleLesson.make(all, age)
        }
        lesson.age = age; lesson.style = style
        return record(lesson)
    }

    /** Voices every line, lays them on the beat, builds a music bed to fit and mixes the two. */
    private suspend fun record(lesson: Lesson): Entry = withContext(Dispatchers.Default) {
        val sr = SAMPLE_RATE
        val bpm = when (lesson.style) { "chant" -> 92; "dance" -> 120; "calm" -> 80; else -> 104 }
        val bar = 4 * 60.0 / bpm
        val young = lesson.age == "4-6"
        val teacherRate = when { young -> .85f; isYoung(lesson.age) -> .95f; else -> 1.05f }
        val crewPitch = if (isYoung(lesson.age)) 1.5f else 1.15f
        val dir = File(ctx.cacheDir, "lesson").apply { deleteRecursively(); mkdirs() }
        var n = 0
        suspend fun voice(text: String, kids: Boolean): FloatArray {
            val f = File(dir, "l${n++}.wav")
            val ok = speak(text, f, if (kids) 1.05f else teacherRate, if (kids) crewPitch else 1.0f, lesson.language)
            return if (ok && f.exists()) readAnyWav(f, sr) else FloatArray(0)
        }
        Studio.report("Recording the voices…", 0f)
        class Placed(val pcm: FloatArray, val t: Double, val text: String, val who: String)
        val placed = ArrayList<Placed>()
        val sectionBars = ArrayList<Pair<String, Int>>()
        var barPos = 4 // 4-bar intro
        sectionBars.add("intro" to 4)
        val total = lesson.sections.sumOf { it.lines.size }
        var done = 0
        fun slotBars(len: Int): Int = max(2, (ceil((len.toDouble() / sr + .35) / (2 * bar)) * 2).toInt())
        for (s in lesson.sections) {
            val start = barPos
            for (l in s.lines) {
                when (s.type) {
                    "call" -> {
                        val a = voice(l.text, false); val b = voice(l.answer, true)
                        placed.add(Placed(a, barPos * bar + .05, l.text, "teacher")); barPos += slotBars(a.size)
                        placed.add(Placed(b, barPos * bar + .05, l.answer, "kids")); barPos += slotBars(b.size)
                    }
                    "quiz" -> {
                        val a = voice(l.text, false); val b = voice(l.answer, true)
                        placed.add(Placed(a, barPos * bar + .05, l.text, "teacher")); barPos += slotBars(a.size)
                        barPos += 1 // thinking time
                        placed.add(Placed(b, barPos * bar + .05, l.answer, "kids")); barPos += slotBars(b.size)
                    }
                    else -> {
                        val a = voice(l.text, false)
                        placed.add(Placed(a, barPos * bar + .05, l.text, if (s.type == "chorus") "all" else "teacher")); barPos += slotBars(a.size)
                    }
                }
                done++
                Studio.report("Recording the voices…", done.toFloat() / max(1, total) * .5f)
            }
            var used = barPos - start
            if (used % 4 != 0) { barPos += 4 - used % 4; used = barPos - start }
            sectionBars.add(s.type to used)
        }
        sectionBars.add("outro" to 4)
        Studio.report("Building the music…", .55f)
        val song = Generator.kidsBed(lesson.title, bpm, lesson.style, sectionBars, System.currentTimeMillis())
        val synth = Synth(sr)
        val (l, r) = synth.render(song) { p -> Studio.report("Building the music…", (.55 + p * .35).toFloat()) }
        // Duck the music under every spoken line, then lay the voices on top.
        val duck = FloatArray(l.size) { 1f }
        val ramp = (.08 * sr).toInt()
        for (p in placed) {
            val a = (p.t * sr).toInt(); val z = a + p.pcm.size
            for (i in max(0, a - ramp) until min(duck.size, z + ramp)) {
                val e = when { i < a -> (a - i).toFloat() / ramp; i > z -> (i - z).toFloat() / ramp; else -> 0f }
                duck[i] = min(duck[i], .42f + .58f * e)
            }
        }
        for (i in l.indices) { l[i] *= duck[i]; r[i] *= duck[i] }
        for (p in placed) {
            val a = (p.t * sr).toInt()
            val g = 1.25f
            for (k in p.pcm.indices) { val i = a + k; if (i >= l.size) break; l[i] += p.pcm[k] * g; r[i] += p.pcm[k] * g }
        }
        var peak = 1e-6f; for (i in l.indices) peak = max(peak, max(kotlin.math.abs(l[i]), kotlin.math.abs(r[i])))
        if (peak > .95f) { val g = .95f / peak; for (i in l.indices) { l[i] *= g; r[i] *= g } }
        lesson.timeline = placed.map { TimedLine(it.t, it.pcm.size.toDouble() / sr, it.text, it.who) }
        val id = "k" + System.currentTimeMillis().toString(36)
        Studio.report("Saving…", .97f)
        val tmp = File(lib.wav(id).path + ".tmp")
        synth.writeWav(l, r, tmp); tmp.renameTo(lib.wav(id))
        dir.deleteRecursively()
        lib.save(id, song, "Learning: " + lesson.topic, System.currentTimeMillis(), emptyList(), lesson.toJson().toString())
    }

    /** On-phone text reading for the free mode (printed text works best). */
    private suspend fun ocr(files: List<File>): String {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return files.map { f ->
                val image = runCatching { InputImage.fromFilePath(ctx, Uri.fromFile(f)) }.getOrNull() ?: return@map ""
                suspendCancellableCoroutine<String> { c ->
                    rec.process(image).addOnSuccessListener { c.resume(it.text) }.addOnFailureListener { c.resume("") }
                }
            }.joinToString("\n\n")
                // Join lines that were wrapped mid-sentence; keep real paragraph breaks.
                .replace(Regex("(?<![.!?:])\n(?!\n)"), " ")
        } finally {
            rec.close()
        }
    }

    private fun jpegBase64(f: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Couldn't open that photo.")
        val scale = min(1f, 1600f / max(bmp.width, bmp.height))
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** Reads any PCM WAV (as produced by text-to-speech) into mono at [targetSr]. */
fun readAnyWav(f: File, targetSr: Int): FloatArray {
    val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    if (b.remaining() < 12) return FloatArray(0)
    b.position(12)
    var sr = 22050; var ch = 1; var bits = 16
    while (b.remaining() >= 8) {
        val id = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
        val size = b.int
        if (id == "fmt ") {
            val p = b.position()
            b.short; ch = b.short.toInt(); sr = b.int; b.int; b.short; bits = b.short.toInt()
            b.position(p + size)
        } else if (id == "data") {
            val len = min(size, b.remaining())
            val frames = len / (ch * (bits / 8))
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                var s = 0f
                repeat(ch) { s += if (bits == 16) b.short / 32768f else (b.get().toInt() and 0xff) / 128f - 1f }
                out[i] = s / ch
            }
            return trimSilence(resample(out, sr, targetSr))
        } else {
            b.position(min(b.limit(), b.position() + size + (size and 1)))
        }
    }
    return FloatArray(0)
}

private fun trimSilence(x: FloatArray): FloatArray {
    val th = .01f
    val a = x.indexOfFirst { kotlin.math.abs(it) > th }.let { if (it < 0) return FloatArray(0) else max(0, it - 400) }
    val z = x.indexOfLast { kotlin.math.abs(it) > th }.let { min(x.size, it + 1200) }
    return x.copyOfRange(a, z)
}
