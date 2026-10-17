package app.songsmith

import kotlin.math.max
import kotlin.random.Random

/**
 * The free, offline composer used when there's no API key. It reads style, tempo and mood words
 * from the description (and any change requests added later) and builds a full arrangement:
 * intro, build, breakdown, drop, second breakdown, final drop, outro.
 */
object Generator {

    private class Style(
        val name: String, val bpm: Int, val drums: String, val bass: String, val chords: String, val lead: String, val arp: String,
        val groove: String, // "four", "break", "dnb"
        val bassLine: String, // "acid", "roll", "offbeat", "psy", "sub", "reese", "synco"
        val useLead: Boolean, val useArp: Boolean,
    )

    private val STYLES = linkedMapOf(
        "acid" to Style("Acid techno", 138, "909", "acid", "stab", "supersaw", "acid", "four", "acid", false, true),
        "psy" to Style("Psytrance", 145, "909", "saw", "stab", "supersaw", "acid", "four", "psy", true, true),
        "hard" to Style("Hard techno", 150, "boom", "sub", "stab", "saw", "acid", "four", "offbeat", false, true),
        "trance" to Style("Trance", 138, "909", "saw", "pad", "supersaw", "pluck", "four", "offbeat", true, true),
        "melodic" to Style("Melodic techno", 124, "909", "sub", "pad", "saw", "bell", "four", "roll", true, true),
        "rave" to Style("Rave", 140, "tight", "sub", "stab", "supersaw", "saw", "break", "synco", true, false),
        "dnb" to Style("Drum & bass", 174, "tight", "saw", "pad", "supersaw", "pluck", "dnb", "reese", true, true),
        "techno" to Style("Techno", 132, "909", "sub", "stab", "saw", "acid", "four", "roll", false, true),
    )

    private val KEYWORDS = listOf(
        "acid" to Regex("\\bacid|303|squelch", RegexOption.IGNORE_CASE),
        "psy" to Regex("\\bpsy|goa\\b", RegexOption.IGNORE_CASE),
        "hard" to Regex("hard techno|industrial|hardstyle|schranz|warehouse", RegexOption.IGNORE_CASE),
        "trance" to Regex("trance|euphoric|uplifting", RegexOption.IGNORE_CASE),
        "melodic" to Regex("melodic|progressive|deep", RegexOption.IGNORE_CASE),
        "rave" to Regex("\\brave|breakbeat|\\bbreaks?\\b|jungle|hardcore", RegexOption.IGNORE_CASE),
        "dnb" to Regex("drum ?(&|and|n) ?bass|\\bdnb\\b|d&b|neurofunk", RegexOption.IGNORE_CASE),
        "techno" to Regex("techno", RegexOption.IGNORE_CASE),
    )

    private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private val PHRYGIAN = intArrayOf(0, 1, 3, 5, 7, 8, 10)
    private val PROGS = listOf(
        intArrayOf(0, 5, 2, 6), intArrayOf(0, 6, 5, 6), intArrayOf(0, 3, 5, 4),
        intArrayOf(0, 5, 3, 4), intArrayOf(5, 6, 0, 0), intArrayOf(0, 2, 6, 5),
    )
    private val DARK_PROGS = listOf(intArrayOf(0, 1, 0, 6), intArrayOf(0, 0, 5, 1), intArrayOf(0, 3, 1, 0))
    private val KEY_ROOTS = listOf(9 to "A", 2 to "D", 4 to "E", 6 to "F#", 7 to "G", 0 to "C", 5 to "F", 11 to "B")
    private val WORDS_A = listOf("Last", "Neon", "Concrete", "Midnight", "Hollow", "Velvet", "Static", "Broken", "Endless", "Silver", "Ghost", "Distant")
    private val WORDS_B = listOf("Train", "Fog", "Dawn", "Signal", "Horizon", "Rain", "Pulse", "Tunnel", "Echo", "Machine", "Skyline", "Heart")

    private class Plan(
        val style: Style, val bpm: Int, val energy: Int, val dark: Boolean, val keyPc: Int, val keyName: String,
        val prog: IntArray, val rnd: Random, val leadSeed: Int,
    )

    private fun count(text: String, re: String) = Regex(re, RegexOption.IGNORE_CASE).findAll(text).count()

    private fun plan(text: String, seed: Long): Plan {
        val rnd = Random(seed)
        val styleKey = KEYWORDS.firstOrNull { it.second.containsMatchIn(text) }?.first ?: listOf("acid", "trance", "techno", "melodic").random(rnd)
        val style = STYLES[styleKey]!!
        val up = count(text, "more energy|harder|faster|heavier|intense|crank|pump|bigger|louder")
        val down = count(text, "calmer|softer|slower|chill|less energy|gentle|relax")
        val explicit = Regex("(\\d{2,3})\\s*(bpm)?", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull { it in 90..190 }
        val tempo = count(text, "faster") * 4 - count(text, "slower") * 4
        val bpm = (explicit ?: style.bpm) + tempo
        val dark = count(text, "dark|melanchol|sad|gloomy|haunting|moody|minor") > 0
        val k = KEY_ROOTS[rnd.nextInt(KEY_ROOTS.size)]
        val progList = if (count(text, "darker|sinister|evil|menacing") > 0) DARK_PROGS else PROGS
        val prog = progList[rnd.nextInt(progList.size)]
        val newMelody = count(text, "new (lead|melody|hook)|different (lead|melody|hook)")
        return Plan(style, bpm.coerceIn(90, 190), (2 + up - down).coerceIn(0, 5), dark, k.first, "${k.second} minor", prog, rnd, seed.toInt() + newMelody * 977)
    }

    /* ---------------- note helpers ---------------- */

    private fun scaleNote(p: Plan, base: Int, degree: Int): Int {
        val sc = if (p.dark && p.prog.contentEquals(DARK_PROGS[0])) PHRYGIAN else MINOR
        val oct = Math.floorDiv(degree, 7)
        val d = ((degree % 7) + 7) % 7
        return base + p.keyPc + oct * 12 + sc[d]
    }

    private fun chordTones(p: Plan, degree: Int, base: Int, lo: Int, hi: Int): List<Int> =
        listOf(degree, degree + 2, degree + 4).map { var m = scaleNote(p, base, it); while (m > hi) m -= 12; while (m < lo) m += 12; m }.sorted()

    private fun bassRoot(p: Plan, degree: Int): Int { var m = scaleNote(p, 24, degree); while (m < 28) m += 12; while (m > 40) m -= 12; return m }

    /* ---------------- parts ---------------- */

    private fun drums(p: Plan, bars: Int, part: String): List<Hit> {
        val out = ArrayList<Hit>()
        val g = p.style.groove
        val e = p.energy
        for (b in 0 until bars) {
            val o = b * 4.0
            val last = b == bars - 1
            when (g) {
                "four" -> {
                    if (part != "break") { for (i in 0..3) out.add(Hit(o + i, 'k', 1.0)) }
                    for (i in 0..3) {
                        if (part != "break" || e >= 3) out.add(Hit(o + i + .5, 'o', if (part == "intro") .4 else .55))
                        if (e >= 2 && part != "break") { out.add(Hit(o + i + .25, 'h', .35)); out.add(Hit(o + i + .75, 'h', .45)) }
                    }
                    if (part == "drop" || part == "build" || part == "drop2" || (part == "outro" && e >= 3)) { out.add(Hit(o + 1, 'c', .85)); out.add(Hit(o + 3, 'c', .85)) }
                    if (e >= 4 && part.startsWith("drop") && b % 2 == 1) out.add(Hit(o + 3.75, 't', .6))
                }
                "break" -> {
                    if (part != "break") {
                        out.add(Hit(o, 'k', 1.0)); out.add(Hit(o + 1.75, 'k', .8)); out.add(Hit(o + 2.5, 'k', .9))
                        out.add(Hit(o + 1, 's', .9)); out.add(Hit(o + 3, 's', .95)); out.add(Hit(o + 3.75, 's', .35)); out.add(Hit(o + 2.25, 's', .3))
                        for (i in 0 until 8) out.add(Hit(o + i * .5, 'h', if (i % 2 == 0) .5 else .35))
                    }
                }
                else -> { // dnb: half-time kick/snare at 170+
                    if (part != "break") {
                        out.add(Hit(o, 'k', 1.0)); out.add(Hit(o + 2.5, 'k', .9))
                        out.add(Hit(o + 1, 's', 1.0)); out.add(Hit(o + 3, 's', 1.0))
                        for (i in 0 until 8) out.add(Hit(o + i * .5, 'h', if (i % 2 == 1) .55 else .3))
                        if (e >= 3) out.add(Hit(o + 1.75, 's', .3))
                    }
                }
            }
            if (last && part == "build") { for (i in 0 until 16) out.add(Hit(o + i * .25, 's', .25 + i * .045)) }
            if (last && part == "drop" && b > 0) { out.add(Hit(o + 3.5, 't', .7)); out.add(Hit(o + 3.75, 't', .8)) }
        }
        return out
    }

    private fun bass(p: Plan, bars: Int, part: String): List<Note> {
        if (part == "break" || part == "intro") return emptyList()
        val out = ArrayList<Note>()
        val r = Random(p.leadSeed + 17)
        val acidSteps = List(16) { s -> if (s % 4 == 0 && p.style.groove == "four") -1 else if (r.nextFloat() < .78) listOf(0, 0, 12, 7, 3, 10, 0, 12).random(r) else -2 }
        val accents = List(16) { r.nextFloat() < .32 }
        for (b in 0 until bars) {
            val o = b * 4.0
            val root = bassRoot(p, p.prog[b % 4])
            when (p.style.bassLine) {
                "acid" -> acidSteps.forEachIndexed { s, iv ->
                    if (iv >= 0 || iv == -1) {
                        val pitch = if (iv == -1) root else root + iv
                        out.add(Note(o + s * .25, pitch, if (accents[s]) .22 else .2, if (accents[s]) 1.0 else .5))
                    }
                }
                "psy" -> { for (i in 0..3) for (s in 1..3) out.add(Note(o + i + s * .25, root, .2, if (s == 1) .9 else .7)) }
                "roll" -> { for (i in 0..3) for (s in 1..3) out.add(Note(o + i + s * .25, root, .22, .6 + .1 * s)) }
                "offbeat" -> { for (i in 0..3) { out.add(Note(o + i + .5, root, .4, .9)); if (p.energy >= 3) out.add(Note(o + i + .75, root + 12, .2, .6)) } }
                "reese" -> { out.add(Note(o, root, 2.4, .9)); out.add(Note(o + 2.5, root, .75, .8)); out.add(Note(o + 3.25, root + 12, .5, .7)) }
                else -> { out.add(Note(o, root, .6, 1.0)); out.add(Note(o + .75, root, .5, .8)); out.add(Note(o + 2.5, root + 7, .5, .8)); out.add(Note(o + 3.5, root + 12, .4, .7)) }
            }
        }
        return out
    }

    private fun chords(p: Plan, bars: Int, part: String): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * 4.0
            val tones = chordTones(p, p.prog[b % 4], 48, 52, 72)
            when {
                part == "break" || part == "intro" && p.energy <= 1 -> tones.forEach { out.add(Note(o, it, 4.0, .55)) }
                part == "intro" -> {}
                p.style.chords == "stab" -> listOf(0.0 to .25, .75 to .25, 2.5 to .5).forEach { (at, len) -> tones.forEach { out.add(Note(o + at, it + 12, len, .75)) } }
                else -> tones.forEach { out.add(Note(o, it, 4.0, if (part.startsWith("drop")) .5 else .4)) }
            }
        }
        return out
    }

    private fun motif(p: Plan): List<Note> {
        val r = Random(p.leadSeed)
        val rhythms = listOf(
            listOf(0.0 to 1.5, 1.5 to .5, 2.0 to 1.0, 3.0 to 1.0, 4.0 to 1.5, 5.5 to .5, 6.0 to 2.0),
            listOf(0.0 to .75, .75 to .75, 1.5 to .5, 2.0 to 2.0, 4.0 to .75, 4.75 to .75, 5.5 to .5, 6.0 to 2.0),
            listOf(0.0 to 2.0, 2.0 to 1.0, 3.0 to 1.0, 4.0 to 3.0, 7.0 to 1.0),
        )
        val rh = rhythms[r.nextInt(rhythms.size)]
        var deg = listOf(0, 2, 4).random(r) + 7
        val first = rh.map { (at, len) ->
            val n = Note(at, scaleNote(p, 60, deg), len, .85)
            deg += listOf(-2, -1, -1, 1, 1, 2, 0, -3).random(r)
            deg = deg.coerceIn(5, 13)
            n
        }
        // Answer phrase: same rhythm, ends on the root.
        val second = first.mapIndexed { i, n ->
            val pitch = if (i == first.size - 1) scaleNote(p, 60, 7) else if (i >= first.size - 3) n.midi + listOf(-2, 2, 0).random(r) else n.midi
            Note(n.beat + 8, pitch, n.dur, n.vel)
        }
        return first + second
    }

    private fun lead(p: Plan, bars: Int, part: String, m: List<Note>): List<Note> {
        if (!p.style.useLead && part != "break") return emptyList()
        if (part !in setOf("break", "drop", "drop2")) return emptyList()
        val out = ArrayList<Note>()
        for (rep in 0 until bars / 4) { m.forEach { out.add(Note(it.beat + rep * 16, it.midi, it.dur, if (part == "break") it.vel * .7 else it.vel)) } }
        return out
    }

    private fun arp(p: Plan, bars: Int, part: String): List<Note> {
        if (!p.style.useArp || part == "intro" || part == "outro") return emptyList()
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val tones = chordTones(p, p.prog[b % 4], 60, 64, 84)
            for (s in 0 until 16) out.add(Note(b * 4.0 + s * .25, tones[listOf(0, 1, 2, 1)[s % 4]], .2, if (s % 4 == 0) .85 else .45))
        }
        return out
    }

    private fun samples(bars: Int, part: String, metas: List<SampleMeta>): List<SampleHit> {
        val out = ArrayList<SampleHit>()
        metas.forEachIndexed { i, m ->
            when (m.role) {
                "loop" -> if (part.startsWith("drop") || (part == "build" && i == 0)) out.add(SampleHit(0.0, m.id, bars * 4.0, .85, 0))
                "texture" -> if (part == "intro" || part == "break") out.add(SampleHit(0.0, m.id, bars * 4.0, .55, 0))
                else -> when (part) {
                    "drop", "drop2" -> { for (b in 0 until bars step 2) { out.add(SampleHit(b * 4.0 + 1.5, m.id, 1.0, .85, 0)); out.add(SampleHit(b * 4.0 + 3.5, m.id, .5, .75, 0)) } }
                    "break" -> out.add(SampleHit((bars - 1) * 4.0, m.id, 4.0, .8, 0))
                    "build" -> { for (s in 0 until 4) out.add(SampleHit((bars - 1) * 4.0 + s, m.id, .5, .6 + .1 * s, s * 2)) }
                    else -> {}
                }
            }
        }
        return out
    }

    /** Build a whole track. [seed] keeps a song's identity when it's regenerated with changes. */
    fun compose(text: String, voices: Voices, length: String, metas: List<SampleMeta>, seed: Long): Song {
        val p = plan(text, seed)
        val m = motif(p)
        val bars = 4
        fun pattern(part: String): Pattern {
            val notes = mapOf(
                "bass" to bass(p, bars, part), "chords" to chords(p, bars, part),
                "lead" to if (voices.combo() != "none" && part in setOf("drop", "drop2")) emptyList() else lead(p, bars, part, m),
                "arp" to arp(p, bars, part),
                "vocal" to if (voices.combo() != "none" && part in setOf("break", "drop", "drop2")) m.map { Note(it.beat, it.midi - 12, it.dur, it.vel) } else emptyList(),
            )
            return Pattern(bars, drums(p, bars, part), notes, samples(bars, part, metas))
        }
        val parts = listOf("intro", "build", "break", "drop", "drop2", "outro")
        val patterns = LinkedHashMap<String, Pattern>()
        parts.forEach { patterns[it] = pattern(it) }
        val arr = if (length == "short") listOf(
            Section("Intro", "intro", 1, ""), Section("Build", "build", 1, ""), Section("Drop", "drop", 4, ""), Section("Outro", "outro", 1, ""),
        ) else listOf(
            Section("Intro", "intro", 4, ""), Section("Build", "build", 2, ""), Section("Breakdown", "break", 4, ""),
            Section("Drop", "drop", 6, ""), Section("Breakdown", "break", 2, ""), Section("Build", "build", 1, ""),
            Section("Final drop", "drop2", 6, ""), Section("Outro", "outro", 3, ""),
        )
        val r = Random(seed)
        val title = WORDS_A[r.nextInt(WORDS_A.size)] + " " + WORDS_B[r.nextInt(WORDS_B.size)]
        val inst = mutableMapOf(
            "drums" to p.style.drums, "bass" to p.style.bass, "chords" to p.style.chords,
            "lead" to p.style.lead, "arp" to p.style.arp, "vocal" to voices.combo(),
        )
        val energyWord = listOf("laid-back", "steady", "driving", "pounding", "relentless", "all-out")[p.energy]
        return Song(
            title, p.style.name, p.bpm, p.keyName, 0.0,
            "Made with the built-in composer: ${energyWord} ${p.style.name.lowercase()} at ${p.bpm} BPM in ${p.keyName}" +
                (if (metas.isNotEmpty()) ", built around your samples" else "") + ".",
            inst, patterns, arr,
        )
    }

    /* ---------------- music beds for kids' learning songs ---------------- */

    private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val KID_PROGS = listOf(intArrayOf(0, 4, 5, 3), intArrayOf(0, 3, 4, 3), intArrayOf(0, 5, 3, 4))

    /**
     * A bright, steady major-key bed that leaves room for the spoken lines.
     * [sections] lists each part (intro, chorus, verse, call, quiz, outro) with its length in bars.
     */
    fun kidsBed(title: String, bpm: Int, style: String, sections: List<Pair<String, Int>>, seed: Long): Song {
        val r = Random(seed)
        val keyPc = listOf(0, 2, 5, 7).random(r)
        val keyName = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[keyPc] + " major"
        val prog = KID_PROGS.random(r)
        fun note(base: Int, deg: Int): Int = base + keyPc + Math.floorDiv(deg, 7) * 12 + MAJOR[((deg % 7) + 7) % 7]
        fun triad(deg: Int): List<Int> = listOf(deg, deg + 2, deg + 4).map { var m = note(48, it); while (m > 72) m -= 12; while (m < 52) m += 12; m }.sorted()
        fun root(deg: Int): Int { var m = note(24, deg); while (m < 31) m += 12; while (m > 43) m -= 12; return m }
        val hook = run {
            val degs = listOf(listOf(4, 4, 5, 4, 2, 0, 1, 2), listOf(0, 2, 4, 5, 4, 2, 4, 0), listOf(7, 6, 4, 5, 4, 2, 1, 0)).random(r)
            degs.mapIndexed { i, d -> Note(i * 2.0, note(72, d), 1.5, .5) }
        }

        fun part(kind: String): Pattern {
            val bars = 4
            val drums = ArrayList<Hit>(); val bass = ArrayList<Note>(); val chords = ArrayList<Note>(); val arp = ArrayList<Note>(); val lead = ArrayList<Note>()
            val quiet = kind == "quiz" || kind == "intro" && style == "calm"
            for (b in 0 until bars) {
                val o = b * 4.0
                val deg = prog[b % 4]
                val tones = triad(deg)
                when (style) {
                    "dance" -> {
                        if (!quiet) { for (i in 0..3) drums.add(Hit(o + i, 'k', .9)) }
                        for (i in 0..3) { drums.add(Hit(o + i + .5, 'o', .35)) }
                        if (kind != "intro") { drums.add(Hit(o + 1, 'c', .6)); drums.add(Hit(o + 3, 'c', .6)) }
                        for (i in 0..3) { bass.add(Note(o + i + .5, root(deg), .4, .75)) }
                        listOf(0.0, 1.5, 3.0).forEach { at -> tones.forEach { chords.add(Note(o + at, it, .5, .5)) } }
                    }
                    "chant" -> {
                        if (!quiet) { drums.add(Hit(o, 'k', 1.0)); drums.add(Hit(o + 2.5, 'k', .8)) }
                        drums.add(Hit(o + 1, 's', .7)); drums.add(Hit(o + 3, 's', .75))
                        for (i in 0 until 8) { drums.add(Hit(o + i * .5, 'h', if (i % 2 == 0) .35 else .25)) }
                        bass.add(Note(o, root(deg), 1.5, .85)); bass.add(Note(o + 2.5, root(deg), 1.0, .7))
                        tones.forEach { chords.add(Note(o, it, 3.5, .35)) }
                    }
                    "calm" -> {
                        if (!quiet) { drums.add(Hit(o, 'h', .25)) }
                        bass.add(Note(o, root(deg), 3.5, .6))
                        tones.forEach { chords.add(Note(o, it, 4.0, .45)) }
                        for (s in 0 until 4) { arp.add(Note(o + s, tones[s % 3] + 12, .9, .3)) }
                    }
                    else -> { // clap-along
                        if (!quiet) { drums.add(Hit(o, 'k', .9)); drums.add(Hit(o + 2, 'k', .85)) }
                        drums.add(Hit(o + 1, 'c', .8)); drums.add(Hit(o + 3, 'c', .8))
                        for (i in 0 until 8) { drums.add(Hit(o + i * .5, 'h', .3)) }
                        bass.add(Note(o, root(deg), .9, .8)); bass.add(Note(o + 1, root(deg) + 7, .9, .7)); bass.add(Note(o + 2, root(deg), .9, .8)); bass.add(Note(o + 3, root(deg) + 7, .9, .7))
                        listOf(0.0, 1.0, 2.0, 3.0).forEach { at -> tones.forEach { chords.add(Note(o + at, it, .45, .4)) } }
                    }
                }
                if (kind == "chorus" && style != "calm") { for (s in 0 until 8) arp.add(Note(o + s * .5, tones[s % 3] + 12, .35, .3)) }
            }
            if (kind == "intro" || kind == "outro") { hook.forEach { lead.add(it) } }
            return Pattern(bars, drums, mapOf("bass" to bass, "chords" to chords, "lead" to lead, "arp" to arp, "vocal" to emptyList()))
        }

        val kinds = listOf("intro", "chorus", "verse", "call", "quiz", "outro")
        val patterns = LinkedHashMap<String, Pattern>()
        kinds.forEach { patterns[it] = part(it) }
        val arr = sections.map { (kind, bars) ->
            val k = if (kind in kinds) kind else "verse"
            Section(k.replaceFirstChar { it.uppercase() }, k, max(1, bars / 4))
        }
        val chordPreset = when (style) { "dance" -> "stab"; "chant" -> "pad"; "calm" -> "pad"; else -> "keys" }
        val inst = mutableMapOf(
            "drums" to if (style == "dance") "909" else "tight", "bass" to if (style == "chant") "sub" else "pluck",
            "chords" to chordPreset, "lead" to "bell", "arp" to "bell", "vocal" to "none",
        )
        return Song(title, "Learning song", bpm, keyName, 0.0, "A learning song with spoken lines over a ${KID_STYLES[style] ?: style} beat.", inst, patterns, arr)
    }
}
