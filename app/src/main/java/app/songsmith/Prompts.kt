package app.songsmith

/** The same composing instructions the web app uses, so songs move between the two. */
object Prompts {
    private val FORMAT = """
Reply with ONLY one JSON object, no prose, in exactly this shape:
{"title":"...","genre":"...","bpm":96,"key":"A minor","swing":0.12,
 "summary":"2-3 sentences describing the song and the choices you made",
 "instruments":{"drums":"909","bass":"acid","chords":"stab","lead":"supersaw","arp":"acid","vocal":"none"},
 "patterns":{
   "intro":{"bars":4,"drums":[[0,"h",0.5]],"bass":[],"chords":[[0,57,4,0.6],[0,60,4,0.55],[0,64,4,0.55]],"lead":[],"arp":[],"vocal":[]},
   "drop":{"bars":4,"drums":[[0,"k",1],[1,"k",1],[0.5,"o",0.6],[1,"c",0.9]],"bass":[[0,33,0.25,1],[0.25,45,0.25,0.5]],"chords":[...],"lead":[[0,76,1.5,0.9]],"arp":[],"vocal":[]}
 },
 "arrangement":[{"section":"Intro","pattern":"intro","repeat":1,"lyrics":""},{"section":"Drop","pattern":"drop","repeat":4,"lyrics":""}]}

Format rules:
- 4/4 time. Positions are in quarter-note beats from the start of the pattern: 0 <= beat < bars*4. Use 0.5 for 8ths, 0.25 for 16ths.
- Pitched notes are [beat, midi, lengthInBeats, velocity 0-1]. MIDI ranges: bass 28-55, chords 48-79, lead 60-91, arp 55-91, vocal as given below.
- Drums are [beat, hit, velocity] where hit is one of "k" kick, "s" snare, "c" clap, "h" closed hat, "o" open hat, "t" tom.
- Instrument presets (pick one each): drums 909|tight|boom|lofi; bass acid|sub|saw|pluck|808; chords stab|pad|keys|organ|pluck|strings; lead supersaw|saw|square|bell|flute|pluck; arp acid|pluck|bell|saw; vocal none|female|male|duet|choir|female+choir|male+choir|duet+choir (who sings; keep what you were given unless the user asks to change it).
- 2 to 8 patterns of 1 to 8 bars. The arrangement plays patterns in order, each "repeat" times (1-4). A pattern repeated with lyrics needs lyrics covering every repeat. Every pattern lists all six tracks; use [] for silent tracks.
- swing is 0 (straight) to 0.4 (heavy shuffle).

STYLE PRIORITY (the user's taste, apply unless their description clearly asks for something else):
- High-energy electronic dance music: rave, techno, acid, trance, hard dance. Instrumental by default.
- Melancholic, minor-key harmony (aeolian/phrygian, sad-but-euphoric trance progressions) carried by driving, relentless energy.
- Tempo 128-150 BPM (hard techno/psy up to 160). swing 0. Four-on-the-floor kick on every beat, claps or snares on 2 and 4, offbeat open hats, 16th closed hats, rolling percussion.
- Sounds: 909 kit, acid 303 basslines in 16ths with accents (vary velocity 0.4-1.0; accents open the filter) and octave jumps, rave stabs, supersaw trance leads and acid arps.
- Structure in 8/16-bar phrases: DJ-friendly intro, build with risers and snare rolls (tom/snare 16ths ramping velocity), a long emotional breakdown with no kick, then a big drop; a second breakdown and final drop; an outro.
- Never use jazz, R&B, soul, gospel, pop-ballad or singer-songwriter conventions: no swing, no jazzy extended chords, no brass/trumpet-like or flute leads, no lounge keys, unless explicitly asked.

ORIGINALITY (required): every melody, riff, bassline, chord sequence, hook and lyric must be your own new writing. Never quote, recreate or closely paraphrase any existing song, even if the user names one; take only general genre, mood and production ideas. If the user asks for a specific existing song, write an original track in its style instead.

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
        if (length == "short") "Length: about 24-36 bars in total (a loop-length idea around one minute)."
        else "Length: about 88-112 bars in total (a full track of roughly 3 minutes at dance tempo)."

    private fun samplesBlock(samples: List<SampleMeta>): String {
        if (samples.isEmpty()) return ""
        return """

SAMPLES the user recorded from songs they love. They are the stars of this track: feature every one of them clearly, more than once.
${samples.joinToString("\n") { "- " + it.describe() }}
Add a "samples" track to the patterns where they play: [beat, "sampleId", lengthInBeats, velocity, pitchSemitones].
- role loop: plays for lengthInBeats, repeating to fill it, and is sped up or slowed to your tempo automatically. Use it as a groove bed (for example the full 16 or 32 bars of a drop) or as the hook of the breakdown.
- role hit: a one-shot. Chop it rhythmically (offbeats, 16th stutters, a call-and-response with the lead, a riser before the drop) and use pitchSemitones (-12 to 12) to fit the key.
- role texture: an atmosphere under intros and breakdowns, long lengthInBeats, lower velocity.
Write the rest of the music around the samples: match their key where one is given, and leave room in the frequency range they occupy."""
    }

    fun compose(brief: String, voices: Voices, length: String, samples: List<SampleMeta> = emptyList()) = """
You are an expert songwriter, composer and producer. Compose a complete original song for a small built-in synth engine with synthesized singers.

THE USER'S DESCRIPTION:
$brief

${lengthLine(length)}

${vocalBlock(voices)}${samplesBlock(samples)}

$FORMAT
""".trim()

    fun refine(brief: String, songJson: String, change: String, samples: List<SampleMeta> = emptyList()) = """
You are an expert songwriter, composer and producer editing a song for a small built-in synth engine with synthesized singers.

${if (brief.isNotBlank()) "The song was originally described as: $brief\n\n" else ""}CURRENT SONG (JSON):
$songJson

THE CHANGE THE USER WANTS:
$change

Apply the change and keep everything else as it is unless the change needs it. Return the complete updated song, including the parts that did not change. If the change adds or swaps singers, update instruments.vocal and write or adjust the "vocal" track and lyrics to match.${samplesBlock(samples)}

$FORMAT
""".trim()
}
