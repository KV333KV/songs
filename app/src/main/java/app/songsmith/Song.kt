package app.songsmith

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

val TRACKS = listOf("drums", "bass", "chords", "lead", "arp", "vocal")

val PRESETS: Map<String, List<String>> = mapOf(
    "drums" to listOf("tight", "boom", "lofi"),
    "bass" to listOf("sub", "saw", "pluck", "808"),
    "chords" to listOf("pad", "keys", "organ", "pluck", "strings"),
    "lead" to listOf("saw", "square", "bell", "flute", "pluck"),
    "arp" to listOf("pluck", "bell", "saw"),
    "vocal" to listOf("none", "female", "male", "duet", "choir", "female+choir", "male+choir", "duet+choir"),
)

val VOCAL_LABEL = mapOf(
    "none" to "Instrumental", "female" to "Female", "male" to "Male", "duet" to "Male + female",
    "choir" to "Choir", "female+choir" to "Female + choir", "male+choir" to "Male + choir", "duet+choir" to "All three",
)

private val RANGE = mapOf(
    "bass" to 24..57, "chords" to 45..81, "lead" to 57..93, "arp" to 52..96, "vocal" to 40..84,
)
private val DRUM_HITS = setOf('k', 's', 'c', 'h', 'o', 't')
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

data class Voices(val male: Boolean, val female: Boolean, val choir: Boolean) {
    fun combo(): String {
        val base = when {
            male && female -> "duet"
            female -> "female"
            male -> "male"
            else -> ""
        }
        return if (choir) (if (base.isEmpty()) "choir" else "$base+choir") else base.ifEmpty { "none" }
    }

    fun describe(): String = VOCAL_LABEL[combo()] ?: "Instrumental"

    companion object {
        fun of(combo: String) = Voices(
            male = Regex("(^|\\+)male|duet").containsMatchIn(combo),
            female = Regex("female|duet").containsMatchIn(combo),
            choir = combo.contains("choir"),
        )
    }
}

class Note(val beat: Double, val midi: Int, val dur: Double, val vel: Double)
class Hit(val beat: Double, val hit: Char, val vel: Double)
class Pattern(val bars: Int, val drums: List<Hit>, val notes: Map<String, List<Note>>)
class Section(val name: String, val pattern: String, val repeat: Int, val lyrics: String)

class Song(
    val title: String,
    val genre: String,
    val bpm: Int,
    val key: String,
    val swing: Double,
    val summary: String,
    val instruments: MutableMap<String, String>,
    val patterns: LinkedHashMap<String, Pattern>,
    val arrangement: List<Section>,
) {
    val totalBars: Int get() = arrangement.sumOf { (patterns[it.pattern]?.bars ?: 0) * it.repeat }
    val seconds: Double get() = totalBars * 4 * 60.0 / bpm

    fun lyricsText(): String {
        val seen = HashSet<String>()
        val sb = StringBuilder()
        for (s in arrangement) {
            if (s.lyrics.isBlank() || !seen.add(s.name + "|" + s.lyrics)) continue
            sb.append("[").append(s.name).append("]\n").append(s.lyrics).append("\n\n")
        }
        return sb.toString().trim()
    }

    fun toJson(): JSONObject {
        val inst = JSONObject()
        instruments.forEach { (k, v) -> inst.put(k, v) }
        val pats = JSONObject()
        for ((id, p) in patterns) {
            val o = JSONObject().put("bars", p.bars)
            o.put("drums", JSONArray().apply { p.drums.forEach { put(JSONArray().put(it.beat).put(it.hit.toString()).put(it.vel)) } })
            for (t in TRACKS) if (t != "drums") {
                o.put(t, JSONArray().apply { (p.notes[t] ?: emptyList()).forEach { put(JSONArray().put(it.beat).put(it.midi).put(it.dur).put(it.vel)) } })
            }
            pats.put(id, o)
        }
        val arr = JSONArray()
        arrangement.forEach {
            arr.put(JSONObject().put("section", it.name).put("pattern", it.pattern).put("repeat", it.repeat).put("lyrics", it.lyrics))
        }
        return JSONObject()
            .put("title", title).put("genre", genre).put("bpm", bpm).put("key", key)
            .put("swing", swing).put("summary", summary)
            .put("instruments", inst).put("patterns", pats).put("arrangement", arr)
    }
}

private fun num(v: Any?, d: Double): Double {
    val n = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
    return if (n != null && n.isFinite()) n else d
}

private fun str(v: Any?, d: String): String = (v as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: d

fun parseNote(v: Any?): Int? {
    if (v is Number) return v.toDouble().roundToInt()
    val m = Regex("^\\s*([A-Ga-g])([#b]?)(-?\\d)\\s*$").find(v?.toString() ?: return null) ?: return null
    var pc = NOTE_NAMES.indexOf(m.groupValues[1].uppercase())
    if (m.groupValues[2] == "#") pc++
    if (m.groupValues[2] == "b") pc--
    return pc + (m.groupValues[3].toInt() + 1) * 12
}

fun noteName(m: Int): String = NOTE_NAMES[((m % 12) + 12) % 12] + (Math.floorDiv(m, 12) - 1)

/** Turns Claude's JSON into a safe, clamped song. Throws if nothing playable came back. */
fun parseSong(input: JSONObject): Song {
    val raw = input.optJSONObject("song") ?: input
    val inst = mutableMapOf<String, String>()
    val ri = raw.optJSONObject("instruments") ?: JSONObject()
    for (t in TRACKS) {
        val v = ri.optString(t, "").lowercase()
        inst[t] = if (PRESETS[t]!!.contains(v)) v else PRESETS[t]!![0]
    }
    val patterns = LinkedHashMap<String, Pattern>()
    val rp = raw.optJSONObject("patterns") ?: JSONObject()
    val keys = rp.keys().asSequence().take(16).toList()
    for (id in keys) {
        val p = rp.optJSONObject(id) ?: continue
        val bars = num(p.opt("bars"), 4.0).roundToInt().coerceIn(1, 16)
        val maxB = bars * 4.0
        val drums = ArrayList<Hit>()
        val notes = HashMap<String, List<Note>>()
        for (t in TRACKS) {
            val arr = p.optJSONArray(t) ?: JSONArray()
            val list = ArrayList<Note>()
            for (i in 0 until minOf(arr.length(), 900)) {
                val it = arr.opt(i)
                var b: Any? = null; var x: Any? = null; var d: Any? = null; var v: Any? = null
                if (it is JSONArray) { b = it.opt(0); x = it.opt(1); d = it.opt(2); v = it.opt(3) }
                else if (it is JSONObject) {
                    b = it.opt("beat") ?: it.opt("b")
                    x = it.opt("note") ?: it.opt("midi") ?: it.opt("pitch") ?: it.opt("hit") ?: it.opt("p")
                    d = it.opt("len") ?: it.opt("dur") ?: it.opt("d")
                    v = it.opt("vel") ?: it.opt("v")
                }
                val beat = num(b, -1.0)
                if (beat < 0 || beat >= maxB) continue
                if (t == "drums") {
                    val k = x?.toString()?.trim()?.lowercase()?.firstOrNull() ?: continue
                    if (k !in DRUM_HITS) continue
                    drums.add(Hit(beat, k, num(d ?: v, 0.8).coerceIn(0.05, 1.0)))
                } else {
                    var m = parseNote(x) ?: continue
                    val r = RANGE[t]!!
                    while (m < r.first) m += 12
                    while (m > r.last) m -= 12
                    list.add(Note(beat, m, num(d, 1.0).coerceIn(0.05, maxB - beat + 2), num(v, 0.75).coerceIn(0.05, 1.0)))
                }
            }
            if (t != "drums") notes[t] = list.sortedBy { it.beat }
        }
        patterns[id] = Pattern(bars, drums.sortedBy { it.beat }, notes)
    }
    val arrangement = ArrayList<Section>()
    val ra = raw.optJSONArray("arrangement") ?: JSONArray()
    var total = 0
    for (i in 0 until minOf(ra.length(), 30)) {
        val a = ra.optJSONObject(i) ?: continue
        val pid = (a.opt("pattern") ?: a.opt("id"))?.toString() ?: continue
        val p = patterns[pid] ?: continue
        val rep = num(a.opt("repeat"), 1.0).roundToInt().coerceIn(1, 8)
        if (total + p.bars * rep > 220) break
        total += p.bars * rep
        arrangement.add(Section(str(a.opt("section") ?: a.opt("name"), pid).take(32), pid, rep, str(a.opt("lyrics"), "").take(1200)))
    }
    if (arrangement.isEmpty()) throw IllegalStateException("The song came back without an arrangement.")
    return Song(
        title = str(raw.opt("title"), "Untitled").take(80),
        genre = str(raw.opt("genre"), "").take(40),
        bpm = num(raw.opt("bpm"), 100.0).roundToInt().coerceIn(50, 200),
        key = str(raw.opt("key"), "").take(24),
        swing = num(raw.opt("swing"), 0.0).coerceIn(0.0, 0.5),
        summary = str(raw.opt("summary"), "").take(700),
        instruments = inst,
        patterns = patterns,
        arrangement = arrangement,
    )
}

/** Pull the JSON object out of a reply that may have a code fence or a stray sentence around it. */
fun extractJson(text: String): JSONObject {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) throw IllegalStateException("Claude's reply didn't contain a song.")
    return JSONObject(text.substring(start, end + 1))
}

class Ev(
    val beat: Double, val track: String, val midi: Int = 0, val dur: Double = 0.25, val vel: Double,
    val hit: Char = ' ', val vowel: Char = 'a', val sec: Int = 0,
)

private fun vowelsOf(text: String): List<Char> {
    val v = Regex("[aeiouy]+", RegexOption.IGNORE_CASE).findAll(text).map {
        val c = it.value[0].lowercaseChar(); if (c == 'y') 'i' else c
    }.toList()
    return v.ifEmpty { listOf('a', 'o', 'u', 'a', 'e', 'o') }
}

fun expand(song: Song): Pair<List<Ev>, Double> {
    val events = ArrayList<Ev>()
    var bar = 0
    song.arrangement.forEachIndexed { si, a ->
        val p = song.patterns[a.pattern] ?: return@forEachIndexed
        val vow = vowelsOf(a.lyrics)
        var vi = 0
        repeat(a.repeat) {
            val base = bar * 4.0
            p.drums.forEach { events.add(Ev(base + it.beat, "drums", vel = it.vel, hit = it.hit)) }
            for (t in TRACKS) if (t != "drums") for (n in p.notes[t] ?: emptyList()) {
                if (t == "vocal") events.add(Ev(base + n.beat, t, n.midi, n.dur, n.vel, vowel = vow[vi++ % vow.size], sec = si))
                else events.add(Ev(base + n.beat, t, n.midi, n.dur, n.vel, sec = si))
            }
            bar += p.bars
        }
    }
    events.sortBy { it.beat }
    return events to bar * 4.0
}

fun swingOffset(beat: Double, swing: Double, spb: Double): Double {
    val f = beat - floor(beat)
    return when {
        abs(f - 0.5) < 0.01 -> swing * spb * 0.5
        abs(f - 0.25) < 0.01 || abs(f - 0.75) < 0.01 -> swing * spb * 0.25
        else -> 0.0
    }
}
