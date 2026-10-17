package app.songsmith

/** The same composing instructions the web app uses, so songs move between the two. */
object Prompts {
    private val FORMAT = """
Reply with ONLY one JSON object, no prose, in exactly this shape:
{"title":"...","genre":"...","bpm":96,"key":"A minor","swing":0.12,
 "summary":"2-3 sentences describing the song and the choices you made",
 "instruments":{"drums":"tight","bass":"sub","chords":"keys","lead":"flute","arp":"bell","vocal":"female"},
 "patterns":{
   "intro":{"bars":4,"drums":[[0,"h",0.5]],"bass":[],"chords":[[0,57,4,0.6],[0,60,4,0.55],[0,64,4,0.55]],"lead":[],"arp":[],"vocal":[]},
   "verse":{"bars":4,"drums":[[0,"k",1],[1,"s",0.9],[0.5,"h",0.6]],"bass":[[0,33,1.5,0.9]],"chords":[...],"lead":[],"arp":[],"vocal":[[0.5,69,0.5,0.8],[1,72,1,0.85]]}
 },
 "arrangement":[{"section":"Intro","pattern":"intro","repeat":1,"lyrics":""},{"section":"Verse 1","pattern":"verse","repeat":2,"lyrics":"line one\nline two"}]}

Format rules:
- 4/4 time. Positions are in quarter-note beats from the start of the pattern: 0 <= beat < bars*4. Use 0.5 for 8ths, 0.25 for 16ths.
- Pitched notes are [beat, midi, lengthInBeats, velocity 0-1]. MIDI ranges: bass 28-55, chords 48-79, lead 60-91, arp 55-91, vocal as given below.
- Drums are [beat, hit, velocity] where hit is one of "k" kick, "s" snare, "c" clap, "h" closed hat, "o" open hat, "t" tom.
- Instrument presets (pick one each): drums tight|boom|lofi; bass sub|saw|pluck|808; chords pad|keys|organ|pluck|strings; lead saw|square|bell|flute|pluck; vocal none|female|male|duet|choir|female+choir|male+choir|duet+choir (who sings; keep what you were given unless the user asks to change it).
- 2 to 8 patterns of 1 to 8 bars. The arrangement plays patterns in order, each "repeat" times (1-4). A pattern repeated with lyrics needs lyrics covering every repeat. Every pattern lists all six tracks; use [] for silent tracks.
- swing is 0 (straight) to 0.4 (heavy shuffle).

Musical rules:
- Write real, idiomatic music for the genre. Chords: full voicings (3-5 notes struck on the same beat) with smooth voice leading and rhythm that suits the style.
- Melodies have a memorable motif that repeats and develops, with space between phrases. Bass locks with the kick and outlines the harmony.
- Drums are genre-true, with variation and a fill on the last bar of sections that lead into a new one.
- Build contrast: sparse intro, fuller chorus/drop, a breakdown or bridge where it fits, a proper ending.
""".trim()

    private fun vocalBlock(v: Voices): String {
        if (!v.male && !v.female && !v.choir) {
            return "VOCALS: none. Instrumental: set instruments.vocal to \"none\", leave every \"vocal\" track and every \"lyrics\" field empty, and let the lead carry the melody."
        }
        val lead = when {
            v.female && v.male -> "a male and a female lead singing together (the female sings the written line, the male doubles it an octave lower)"
            v.female -> "a female lead singer"
            v.male -> "a male lead singer"
            else -> ""
        }
        val who = listOf(lead, if (v.choir) "a choir" else "").filter { it.isNotEmpty() }.joinToString(" plus ")
        val (lo, hi) = when {
            v.male && !v.female -> 45 to 67
            !v.male && !v.female -> 55 to 74
            else -> 57 to 79
        }
        val sb = StringBuilder()
        sb.append("VOCALS: $who. Set instruments.vocal to \"${v.combo()}\".\n")
        sb.append("- Write lyrics in each sung section's \"lyrics\" field: verses that tell the story, a short catchy chorus hook that repeats. Instrumental sections have empty lyrics.\n")
        sb.append("- Put the sung melody in the \"vocal\" track: one note per syllable, phrased to the words, MIDI $lo-$hi. Make it singable: mostly stepwise with a few leaps, held notes at the ends of lines, breaths between lines.\n")
        sb.append("- Use the \"lead\" track only for the intro, the gaps between sung lines and instrumental sections, so it never fights the vocal.")
        if (v.choir) {
            sb.append("\n- The choir sings sustained \"ooh/aah\" harmony on chord notes that last a beat or longer")
            if (lead.isEmpty()) sb.append(", and also sings the vocal line")
            sb.append(". Where the choir should swell (choruses, bridge, ending) write the chords as long held voicings; use short stabs where it should stay quiet.")
        }
        return sb.toString()
    }

    private fun lengthLine(length: String) =
        if (length == "short") "Length: about 20-28 bars in total (a sketch around one minute)."
        else "Length: about 56-80 bars in total (a full song of roughly 2.5-3 minutes at your tempo)."

    fun compose(brief: String, voices: Voices, length: String) = """
You are an expert songwriter, composer and producer. Compose a complete original song for a small built-in synth engine with synthesized singers.

THE USER'S DESCRIPTION:
$brief

${lengthLine(length)}

${vocalBlock(voices)}

$FORMAT
""".trim()

    fun refine(brief: String, songJson: String, change: String) = """
You are an expert songwriter, composer and producer editing a song for a small built-in synth engine with synthesized singers.

${if (brief.isNotBlank()) "The song was originally described as: $brief\n\n" else ""}CURRENT SONG (JSON):
$songJson

THE CHANGE THE USER WANTS:
$change

Apply the change and keep everything else as it is unless the change needs it. Return the complete updated song, including the parts that did not change. If the change adds or swaps singers, update instruments.vocal and write or adjust the "vocal" track and lyrics to match.

$FORMAT
""".trim()
}
