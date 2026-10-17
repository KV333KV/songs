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
    var autoCompose: Boolean
        get() = sp.getBoolean("autoCompose", true)
        set(v) = sp.edit().putBoolean("autoCompose", v).apply()

    companion object { const val DEFAULT_MODEL = "claude-sonnet-5-5" }
}

data class Entry(
    val id: String, val title: String, val genre: String, val bpm: Int, val key: String, val vocal: String,
    val seconds: Double, val created: Long, val updated: Long, val brief: String, val lyrics: String, val summary: String,
    val samples: List<String> = emptyList(),
)

/** Songs live in app storage: <id>.json (song + details) and <id>.wav (the rendered audio). */
class Library(ctx: Context) {
    private val dir = File(ctx.filesDir, "songs").apply { mkdirs() }

    fun wav(id: String) = File(dir, "$id.wav")
    private fun json(id: String) = File(dir, "$id.json")

    fun list(): List<Entry> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { load(f.nameWithoutExtension)?.first }.getOrNull() }
        .filter { wav(it.id).exists() }
        .sortedByDescending { it.updated }

    fun latest(): Entry? = list().firstOrNull()

    fun load(id: String): Pair<Entry, Song>? {
        val f = json(id); if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val m = o.getJSONObject("meta")
        val song = parseSong(o.getJSONObject("song"))
        return Entry(
            id = id, title = song.title, genre = song.genre, bpm = song.bpm, key = song.key,
            vocal = song.instruments["vocal"] ?: "none", seconds = song.seconds,
            created = m.optLong("created"), updated = m.optLong("updated"), brief = m.optString("brief"),
            lyrics = song.lyricsText(), summary = song.summary,
            samples = m.optJSONArray("samples")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        ) to song
    }

    fun save(id: String, song: Song, brief: String, created: Long, samples: List<String> = emptyList()): Entry {
        val now = System.currentTimeMillis()
        val o = JSONObject()
            .put("meta", JSONObject().put("id", id).put("created", created).put("updated", now).put("brief", brief).put("samples", org.json.JSONArray(samples)))
            .put("song", song.toJson())
        json(id).writeText(o.toString())
        return load(id)!!.first
    }

    fun delete(id: String) { json(id).delete(); wav(id).delete() }

    fun find(query: String): List<Entry> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        return list().filter { it.title.lowercase().contains(q) || q.contains(it.title.lowercase()) }
    }
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
