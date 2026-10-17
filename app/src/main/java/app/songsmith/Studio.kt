package app.songsmith

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class SongsmithApp : Application()

/** Settings kept on the phone. The API key never leaves the device except in calls to Anthropic. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("songsmith", Context.MODE_PRIVATE)
    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()
    var model: String
        get() = sp.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()
    var voices: Voices
        get() = Voices.of(sp.getString("voices2", "none") ?: "none")
        set(v) = sp.edit().putString("voices2", v.combo()).apply()
    var length: String
        get() = sp.getString("length", "full") ?: "full"
        set(v) = sp.edit().putString("length", v).apply()
    /** "claude" uses the API key when there is one; "builtin" always uses the free on-phone composer. */
    var engine: String
        get() = sp.getString("engine", "claude") ?: "claude"
        set(v) = sp.edit().putString("engine", v).apply()
    val usesClaude: Boolean get() = engine == "claude" && apiKey.isNotBlank()
    var updateToken: String
        get() = sp.getString("updateToken", "") ?: ""
        set(v) = sp.edit().putString("updateToken", v.trim()).apply()
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("autoUpdateCheck", true)
        set(v) = sp.edit().putBoolean("autoUpdateCheck", v).apply()
    var seenTour: Boolean
        get() = sp.getBoolean("seenTour", false)
        set(v) = sp.edit().putBoolean("seenTour", v).apply()
    /** Google Gemini key for real audio (Lyria). */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()
    /** "real" = finished audio from Lyria; "sketch" = the in-app synth engine. */
    var sound: String
        get() = sp.getString("sound", "real") ?: "real"
        set(v) = sp.edit().putString("sound", v).apply()
    val usesLyria: Boolean get() = sound == "real" && geminiKey.isNotBlank()
    var autoCompose: Boolean
        get() = sp.getBoolean("autoCompose", true)
        set(v) = sp.edit().putBoolean("autoCompose", v).apply()

    companion object { const val DEFAULT_MODEL = "claude-sonnet-5-5" }
}

data class Entry(
    val id: String, val title: String, val genre: String, val bpm: Int, val key: String, val vocal: String,
    val seconds: Double, val created: Long, val updated: Long, val brief: String, val lyrics: String, val summary: String,
    val samples: List<String> = emptyList(),
    /** Learning songs carry their lesson (lines, timing, quiz) as JSON. */
    val lesson: String = "",
    /** True for finished audio from Lyria (an MP3), false for in-app sketches. */
    val real: Boolean = false,
    val lyriaBrief: String = "",
)

/** Songs live in app storage: <id>.json (song + details) and <id>.wav (the rendered audio). */
class Library(ctx: Context) {
    private val dir = File(ctx.filesDir, "songs").apply { mkdirs() }

    fun wav(id: String) = File(dir, "$id.wav")
    fun mp3(id: String) = File(dir, "$id.mp3")
    /** Whichever audio file this song has. */
    fun audio(id: String): File = mp3(id).takeIf { it.exists() } ?: wav(id)
    private fun json(id: String) = File(dir, "$id.json")

    fun list(): List<Entry> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { load(f.nameWithoutExtension)?.first }.getOrNull() }
        .filter { audio(it.id).exists() }
        .sortedByDescending { it.updated }

    fun latest(): Entry? = list().firstOrNull()

    fun load(id: String): Pair<Entry, Song>? {
        val f = json(id); if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val m = o.getJSONObject("meta")
        val song = parseSong(o.getJSONObject("song"))
        return Entry(
            id = id, title = song.title, genre = song.genre, bpm = song.bpm, key = song.key,
            vocal = song.instruments["vocal"] ?: "none",
            seconds = if (mp3(id).exists()) m.optDouble("seconds", 0.0) else wav(id).length().let { if (it > 44) (it - 44) / (4.0 * SAMPLE_RATE) else song.seconds },
            created = m.optLong("created"), updated = m.optLong("updated"), brief = m.optString("brief"),
            lyrics = song.lyricsText(), summary = song.summary,
            samples = m.optJSONArray("samples")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            lesson = m.optJSONObject("lesson")?.toString() ?: "",
            real = m.optBoolean("real", false),
            lyriaBrief = m.optString("lyriaBrief", ""),
        ) to song
    }

    fun save(id: String, song: Song, brief: String, created: Long, samples: List<String> = emptyList(), lesson: String = "", real: Map<String, Any> = emptyMap()): Entry {
        val now = System.currentTimeMillis()
        val o = JSONObject()
            .put("meta", JSONObject().put("id", id).put("created", created).put("updated", now).put("brief", brief).put("samples", org.json.JSONArray(samples))
                .apply { if (lesson.isNotEmpty()) put("lesson", JSONObject(lesson)); real.forEach { (k, v) -> put(k, v) } })
            .put("song", song.toJson())
        json(id).writeText(o.toString())
        return load(id)!!.first
    }

    fun delete(id: String) { json(id).delete(); wav(id).delete(); mp3(id).delete() }

    fun find(query: String): List<Entry> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        return list().filter { it.title.lowercase().contains(q) || q.contains(it.title.lowercase()) }
    }
}

/** A stand-in score for a finished-audio track, so it lives in the library like any other song. */
fun placeholderSong(title: String, genre: String, bpm: Int, key: String, summary: String, lyrics: String, vocal: String): Song {
    val inst = TRACKS.associateWith { PRESETS[it]!![0] }.toMutableMap().apply { put("vocal", vocal) }
    return Song(title.take(80), genre.take(40), bpm.coerceIn(50, 200), key.take(24), 0.0, summary.take(700), inst,
        linkedMapOf("track" to Pattern(4, emptyList(), emptyMap())), listOf(Section("Track", "track", 1, lyrics.take(1200))))
}

/** Shared progress that the phone screen shows while the service composes. */
object Studio {
    data class State(val busy: Boolean = false, val message: String = "", val progress: Float? = null, val error: Boolean = false, val libraryVersion: Int = 0)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    fun report(message: String, progress: Float? = null) = _state.update { it.copy(busy = true, message = message, progress = progress, error = false) }
    fun done(message: String) = _state.update { it.copy(busy = false, message = message, progress = null, error = false, libraryVersion = it.libraryVersion + 1) }
    fun fail(message: String) = _state.update { it.copy(busy = false, message = message, progress = null, error = true) }
    fun libraryChanged() = _state.update { it.copy(libraryVersion = it.libraryVersion + 1) }
}

/** Claude writes the score, the synth records it, the library keeps it. */
class Composer(ctx: Context) {
    private val prefs = Prefs(ctx)
    private val lib = Library(ctx)
    private val store = SampleStore(ctx)
    private val claude = ClaudeClient()
    private val synth = Synth()

    suspend fun compose(brief: String, voices: Voices, length: String, sampleIds: List<String> = emptyList()): Entry {
        val metas = sampleIds.mapNotNull { store.meta(it) }
        if (prefs.usesLyria) return composeReal(brief, voices, length, metas)
        if (!prefs.usesClaude) {
            Studio.report("Composing with the built-in composer…")
            val created = System.currentTimeMillis()
            val song = withContext(Dispatchers.Default) { Generator.compose(brief, voices, length, metas, created) }
            val id = "s" + created.toString(36)
            render(song, id)
            return lib.save(id, song, brief, created, metas.map { it.id })
        }
        Studio.report("Claude is writing your song…")
        val text = claude.complete(prefs.apiKey, prefs.model, Prompts.compose(brief, voices, length, metas)) { n ->
            Studio.report("Writing the score · ${n / 1024} KB")
        }
        val song = parseSong(extractJson(text))
        song.instruments["vocal"] = voices.combo()
        val id = "s" + System.currentTimeMillis().toString(36)
        render(song, id)
        return lib.save(id, song, brief, System.currentTimeMillis(), metas.map { it.id })
    }

    suspend fun refine(id: String, change: String): Entry {
        val (entry, song) = lib.load(id) ?: throw ClaudeException("That song isn't on this phone any more.")
        if (entry.real || prefs.usesLyria) {
            val metas = entry.samples.mapNotNull { store.meta(it) }
            val length = if (entry.seconds in 1.0..90.0) "short" else "full"
            return composeReal(entry.brief, Voices.of(entry.vocal), length, metas, change, entry.lyriaBrief.ifBlank { null }, id, entry.created)
        }
        if (!prefs.usesClaude) {
            Studio.report("Reworking “${entry.title}” with the built-in composer…")
            val brief = (entry.brief + "\n" + change).trim()
            val metas = entry.samples.mapNotNull { store.meta(it) }
            val length = if (song.totalBars <= 40) "short" else "full"
            val updated = withContext(Dispatchers.Default) { Generator.compose(brief, Voices.of(song.instruments["vocal"] ?: "none"), length, metas, entry.created) }
            render(updated, id)
            return lib.save(id, updated, brief, entry.created, entry.samples)
        }
        Studio.report("Claude is reworking “${entry.title}”…")
        val metas = entry.samples.mapNotNull { store.meta(it) }
        val text = claude.complete(prefs.apiKey, prefs.model, Prompts.refine(entry.brief, song.toJson().toString(), change, metas)) { n ->
            Studio.report("Rewriting the score · ${n / 1024} KB")
        }
        val updated = parseSong(extractJson(text))
        render(updated, id)
        return lib.save(id, updated, entry.brief, entry.created, entry.samples)
    }

    private suspend fun render(song: Song, id: String) = withContext(Dispatchers.Default) {
        Studio.report("Recording the band…", 0f)
        val used = song.patterns.values.flatMap { p -> p.samples.map { it.id } }.toSet()
        val pcm = used.mapNotNull { sid -> store.pcm(sid)?.let { sid to (it to store.meta(sid)) } }.toMap()
        val (l, r) = synth.render(song, synth.defaultVol, pcm) { p -> Studio.report("Recording the band…", p.toFloat()) }
        val tmp = File(lib.wav(id).path + ".tmp")
        synth.writeWav(l, r, tmp)
        tmp.renameTo(lib.wav(id))
    }

    /** Finished audio: Claude (if there's a key) writes the producer brief, Lyria makes the track. */
    private suspend fun composeReal(
        brief: String, voices: Voices, length: String, metas: List<SampleMeta>,
        change: String? = null, previous: String? = null, reuseId: String? = null, created: Long = System.currentTimeMillis(),
    ): Entry {
        var title = "Untitled"; var genre = "Electronic"; var bpm = 138; var key = ""; var summary = ""
        val lyriaBrief: String
        if (prefs.usesClaude) {
            Studio.report("Claude is producing the brief…")
            val text = claude.complete(prefs.apiKey, prefs.model, Lyria.producerRequest(brief, voices, length, metas, change, previous), 4000)
            val o = extractJson(text)
            title = o.optString("title", title); genre = o.optString("genre", genre); bpm = o.optInt("bpm", bpm)
            key = o.optString("key", ""); summary = o.optString("summary", "")
            lyriaBrief = o.optString("brief").ifBlank { Lyria.templatePrompt(listOf(brief, change ?: "").joinToString(" "), voices, length, metas) }
        } else {
            lyriaBrief = Lyria.templatePrompt(listOf(previous ?: brief, change ?: "").filter { it.isNotBlank() }.joinToString("\n"), voices, length, metas)
            title = Generator.compose(brief, voices, "short", emptyList(), created).title
            summary = "Finished audio made with Google Lyria from your description."
        }
        Studio.report("Recording the real track… (about a minute)")
        val id = reuseId ?: ("r" + System.currentTimeMillis().toString(36))
        val tmp = File(lib.mp3(id).path + ".tmp")
        val res = Lyria.generate(prefs.geminiKey, lyriaBrief, tmp)
        lib.wav(id).delete()
        tmp.renameTo(lib.mp3(id))
        val secs = Lyria.durationSeconds(lib.mp3(id))
        val lyrics = res.text.takeIf { voices.combo() != "none" && it.isNotBlank() && !it.trimStart().startsWith("{") } ?: ""
        val song = placeholderSong(title, genre, bpm, key, summary, lyrics, voices.combo())
        val fullBrief = if (change != null) (brief + "\n" + change).trim() else brief
        return lib.save(id, song, fullBrief, created, metas.map { it.id }, real = mapOf("real" to true, "lyriaBrief" to lyriaBrief, "seconds" to secs))
    }

    /** A soft chord bed to play in the car while Claude is writing. */
    fun waitingMusic(file: File) {
        if (file.exists() && file.length() > 1000) return
        val chords = listOf(listOf(57, 60, 64, 67), listOf(53, 57, 60, 64), listOf(48, 55, 60, 64), listOf(55, 59, 62, 67))
        val notes = ArrayList<Note>()
        chords.forEachIndexed { b, c -> c.forEach { notes.add(Note(b * 8.0, it, 8.0, 0.45)) } }
        val arp = ArrayList<Note>()
        chords.forEachIndexed { b, c -> for (i in 0 until 8) arp.add(Note(b * 8.0 + i, c[i % 4] + 12, 0.9, 0.25)) }
        val pat = Pattern(8, emptyList(), mapOf("chords" to notes, "arp" to arp, "bass" to emptyList(), "lead" to emptyList(), "vocal" to emptyList()))
        val song = Song("Composing", "", 70, "", 0.0, "", mutableMapOf("drums" to "tight", "bass" to "sub", "chords" to "pad", "lead" to "flute", "arp" to "bell", "vocal" to "none"),
            linkedMapOf("bed" to pat), listOf(Section("Bed", "bed", 4, "")))
        val (l, r) = synth.render(song)
        synth.writeWav(l, r, file)
    }
}
