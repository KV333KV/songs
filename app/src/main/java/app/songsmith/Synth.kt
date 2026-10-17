package app.songsmith

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Offline renderer: turns a Song into stereo PCM. Same instruments, drums and formant-synth
 * singers as the web app, written as plain sample loops so it runs anywhere.
 */
class Synth(val sr: Int = 32000) {

    private enum class Wave { SINE, TRIANGLE, SAW, SQUARE }
    private class Osc(val wave: Wave, val ratio: Double = 1.0, val det: Double = 0.0, val gain: Double = 1.0, val decay: Double = 0.0)
    private class Vib(val rate: Double, val depth: Double)
    private class Preset(
        val osc: List<Osc>, val cut: Double, val q: Double = 0.7, val fenvFrom: Double = 0.0, val fenvTime: Double = 0.0,
        val a: Double, val d: Double, val s: Double, val r: Double, val vol: Double, val vib: Vib? = null, val glide: Double = 0.0, val accent: Boolean = false,
    )

    private val S = Wave.SINE; private val T = Wave.TRIANGLE; private val W = Wave.SAW; private val Q = Wave.SQUARE
    private val presets: Map<String, Preset> = mapOf(
        "bass:acid" to Preset(listOf(Osc(W)), 260.0, 13.0, 3400.0, .07, a = .003, d = .18, s = .65, r = .05, vol = .2, accent = true),
        "chords:stab" to Preset(listOf(Osc(W, det = -14.0), Osc(W, det = 14.0), Osc(Q, ratio = .5, gain = .35)), 1800.0, 1.2, 7000.0, .07, a = .002, d = .28, s = .12, r = .18, vol = .09),
        "lead:supersaw" to Preset(listOf(Osc(W, det = -26.0), Osc(W, det = -12.0), Osc(W), Osc(W, det = 12.0), Osc(W, det = 26.0)), 5200.0, .9, a = .01, d = .4, s = .8, r = .3, vol = .06),
        "arp:acid" to Preset(listOf(Osc(W)), 420.0, 12.0, 4800.0, .05, a = .002, d = .12, s = .3, r = .04, vol = .11, accent = true),
        "bass:sub" to Preset(listOf(Osc(S), Osc(T, gain = .25)), 900.0, a = .005, d = .3, s = .85, r = .08, vol = .55),
        "bass:saw" to Preset(listOf(Osc(W), Osc(W, det = 9.0)), 700.0, 3.0, 2400.0, .07, a = .004, d = .25, s = .7, r = .07, vol = .22),
        "bass:pluck" to Preset(listOf(Osc(W), Osc(S, gain = .8)), 420.0, 2.0, 3000.0, .05, a = .003, d = .35, s = .2, r = .08, vol = .32),
        "bass:808" to Preset(listOf(Osc(S), Osc(S, ratio = 2.0, gain = .12)), 2000.0, a = .003, d = .6, s = .55, r = .25, vol = .6, glide = 1.6),
        "chords:pad" to Preset(listOf(Osc(W, det = -12.0), Osc(W, det = 12.0), Osc(T, ratio = .5, gain = .6)), 1400.0, .8, a = .45, d = 1.0, s = .8, r = 1.0, vol = .07),
        "chords:keys" to Preset(listOf(Osc(T), Osc(S, 2.0, gain = .35, decay = .5), Osc(S, 4.0, gain = .08, decay = .2)), 3800.0, .7, 6500.0, .25, a = .004, d = .9, s = .3, r = .35, vol = .16),
        "chords:organ" to Preset(listOf(Osc(S), Osc(S, 2.0, gain = .55), Osc(S, 3.0, gain = .3), Osc(S, 4.0, gain = .15)), 6000.0, a = .012, d = .1, s = 1.0, r = .06, vol = .08, vib = Vib(6.0, 5.0)),
        "chords:pluck" to Preset(listOf(Osc(W), Osc(Q, det = 6.0, gain = .35)), 500.0, 1.5, 5200.0, .06, a = .002, d = .4, s = 0.0, r = .18, vol = .1),
        "chords:strings" to Preset(listOf(Osc(W, det = -9.0), Osc(W, det = 9.0), Osc(W, gain = .5)), 2300.0, a = .28, d = .6, s = .85, r = .55, vol = .06, vib = Vib(5.5, 7.0)),
        "lead:saw" to Preset(listOf(Osc(W), Osc(W, det = 8.0)), 3200.0, 1.4, a = .01, d = .3, s = .75, r = .14, vol = .13, vib = Vib(5.0, 9.0)),
        "lead:square" to Preset(listOf(Osc(Q), Osc(Q, det = -7.0, gain = .5)), 2600.0, a = .008, d = .25, s = .7, r = .12, vol = .1, vib = Vib(5.2, 8.0)),
        "lead:bell" to Preset(listOf(Osc(S), Osc(S, 2.76, gain = .5, decay = .7), Osc(S, 5.4, gain = .18, decay = .25)), 9000.0, a = .002, d = 1.0, s = 0.0, r = .4, vol = .3),
        "lead:flute" to Preset(listOf(Osc(T), Osc(S, 2.0, gain = .15)), 3600.0, a = .07, d = .2, s = .85, r = .14, vol = .32, vib = Vib(5.0, 14.0)),
        "lead:pluck" to Preset(listOf(Osc(W), Osc(T, 2.0, gain = .3)), 700.0, 1.2, 6500.0, .08, a = .002, d = .5, s = 0.0, r = .2, vol = .14),
        "arp:pluck" to Preset(listOf(Osc(W), Osc(Q, det = 5.0, gain = .3)), 600.0, 2.0, 6000.0, .045, a = .002, d = .25, s = 0.0, r = .1, vol = .09),
        "arp:bell" to Preset(listOf(Osc(S), Osc(S, 3.5, gain = .35, decay = .3)), 9000.0, a = .002, d = .6, s = 0.0, r = .3, vol = .18),
        "arp:saw" to Preset(listOf(Osc(W), Osc(W, det = 6.0)), 1800.0, 2.0, 4500.0, .06, a = .003, d = .2, s = .3, r = .08, vol = .07),
    )

    private val formants = mapOf(
        "male" to mapOf('a' to doubleArrayOf(730.0, 1090.0, 2440.0), 'e' to doubleArrayOf(530.0, 1840.0, 2480.0), 'i' to doubleArrayOf(270.0, 2290.0, 3010.0), 'o' to doubleArrayOf(570.0, 840.0, 2410.0), 'u' to doubleArrayOf(300.0, 870.0, 2240.0)),
        "female" to mapOf('a' to doubleArrayOf(850.0, 1220.0, 2810.0), 'e' to doubleArrayOf(610.0, 2330.0, 2990.0), 'i' to doubleArrayOf(310.0, 2790.0, 3310.0), 'o' to doubleArrayOf(590.0, 920.0, 2710.0), 'u' to doubleArrayOf(370.0, 950.0, 2670.0)),
    )
    private val fBw = doubleArrayOf(80.0, 90.0, 120.0)
    private val fGain = doubleArrayOf(1.0, .6, .32)

    val defaultVol = mapOf("drums" to .9, "bass" to .85, "chords" to .6, "lead" to .6, "arp" to .45, "vocal" to .85)
    private val send = mapOf("drums" to .06, "bass" to 0.0, "chords" to .32, "lead" to .22, "arp" to .3, "vocal" to .35)
    private val pan = mapOf("drums" to 0.0, "bass" to 0.0, "chords" to 0.0, "lead" to -.15, "arp" to .35, "vocal" to 0.0)

    private val rnd = java.util.Random(11)

    /** Destination for one track: writes into the stereo mix and the reverb send. */
    private class Out(val l: FloatArray, val r: FloatArray, val rev: FloatArray, vol: Double, sendAmt: Double, p: Double) {
        private val gl = (vol * min(1.0, 1 - p)).toFloat()
        private val gr = (vol * min(1.0, 1 + p)).toFloat()
        private val gs = (vol * sendAmt).toFloat()
        val size = l.size
        fun add(i: Int, x: Double) {
            val v = x.toFloat()
            l[i] += v * gl; r[i] += v * gr; rev[i] += v * gs
        }
    }

    class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun set(type: Char, f: Double, q: Double, sr: Int) {
            val w = 2 * PI * min(f, sr * 0.45) / sr
            val cw = cos(w); val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            when (type) {
                'l' -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2 }
                'h' -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2 }
                else -> { b0 = alpha; b1 = 0.0; b2 = -alpha }
            }
            b0 /= a0; b1 /= a0; b2 /= a0; a1 = -2 * cw / a0; a2 = (1 - alpha) / a0
        }
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1 }
        t > 1 - dt -> { val x = (t - 1) / dt; x * x + x + x + 1 }
        else -> 0.0
    }

    private fun wave(w: Wave, ph: Double, dt: Double): Double = when (w) {
        Wave.SINE -> sin(2 * PI * ph)
        Wave.TRIANGLE -> 4 * abs(ph - 0.5) - 1
        Wave.SAW -> 2 * ph - 1 - polyBlep(ph, dt)
        Wave.SQUARE -> (if (ph < 0.5) 1.0 else -1.0) + polyBlep(ph, dt) - polyBlep((ph + 0.5) % 1.0, dt)
    }

    private fun expRamp(v0: Double, v1: Double, t: Double, len: Double) = if (t >= len) v1 else v0 * (v1 / v0).pow(t / len)

    private fun mtof(m: Double) = 440.0 * 2.0.pow((m - 69) / 12)

    private fun note(p: Preset, out: Out, start: Int, durSec: Double, f: Double, vel: Double) {
        val rel = max(durSec, p.a + .01)
        val n = ((rel + p.r * 1.6 + .05) * sr).toInt()
        val peak = vel * p.vol
        val tauD = max(.01, p.d / 3); val tauR = max(.01, p.r / 3)
        val relLevel = if (rel < p.a) peak * rel / p.a else peak * p.s + (peak - peak * p.s) * exp(-(rel - p.a) / tauD)
        val ph = DoubleArray(p.osc.size) { rnd.nextDouble() }
        val lp = Biquad()
        val fFrom = if (p.accent) p.cut + (p.fenvFrom - p.cut) * (.3 + .7 * vel * vel) else p.fenvFrom
        for (k in 0 until n) {
            val i = start + k
            if (i >= out.size) break
            if (i < 0) continue
            val t = k.toDouble() / sr
            val env = when {
                t < p.a -> peak * t / p.a
                t < rel -> peak * p.s + (peak - peak * p.s) * exp(-(t - p.a) / tauD)
                else -> relLevel * exp(-(t - rel) / tauR)
            }
            val vib = p.vib?.let { it.depth * min(1.0, t / .25) * sin(2 * PI * it.rate * t) } ?: 0.0
            val glide = if (p.glide > 0) p.glide.pow(1 - min(1.0, t / .05)) else 1.0
            var sum = 0.0
            for (j in p.osc.indices) {
                val o = p.osc[j]
                val fr = f * o.ratio * glide * 2.0.pow((o.det + vib) / 1200)
                val dt = fr / sr
                ph[j] += dt; if (ph[j] >= 1) ph[j] -= 1.0
                val g = if (o.decay > 0) expRamp(o.gain, .0005, t, o.decay) else o.gain
                sum += wave(o.wave, ph[j], dt) * g
            }
            if (k % 32 == 0) {
                val c = if (p.fenvFrom > 0) p.cut + (fFrom - p.cut) * exp(-t / p.fenvTime) else p.cut
                lp.set('l', c, p.q, sr)
            }
            out.add(i, lp.process(sum) * env)
        }
    }

    private fun sing(out: Out, start: Int, durSec: Double, f: Double, vel: Double, gender: String, vowel: Char, ens: Boolean) {
        val fm = formants[gender]!![vowel] ?: formants[gender]!!['a']!!
        val a = if (ens) .14 else .05
        val r = if (ens) .35 else .16
        val peak = vel * (if (ens) .2 else .34)
        val rel = max(durSec, a + .03)
        val n = ((rel + r * 1.6 + .05) * sr).toInt()
        val relLevel = if (rel < a) peak * rel / a else peak * .82 + (peak - peak * .82) * exp(-(rel - a) / .25)
        val bands = Array(3) { b -> Biquad().apply { set('b', fm[b], fm[b] / fBw[b], sr) } }
        val dets = if (ens) doubleArrayOf(-11.0, 0.0, 13.0) else doubleArrayOf(0.0)
        val delays = DoubleArray(dets.size) { if (ens) rnd.nextDouble() * .03 else 0.0 }
        val ph = DoubleArray(dets.size) { rnd.nextDouble() }
        val vibRate = 5.2 + rnd.nextDouble() * .4
        val vibDepth = if (ens) 10.0 else 18.0
        val busGain = if (ens) .5 else 1.0
        val breath = Biquad().apply { set('h', 2800.0, .7, sr) }
        for (k in 0 until n) {
            val i = start + k
            if (i >= out.size) break
            if (i < 0) continue
            val t = k.toDouble() / sr
            val env = when {
                t < a -> peak * t / a
                t < rel -> peak * .82 + (peak - peak * .82) * exp(-(t - a) / .25)
                else -> relLevel * exp(-(t - rel) / (r / 3))
            }
            val vib = vibDepth * min(1.0, t / .4) * sin(2 * PI * vibRate * t)
            var bus = 0.0
            for (j in dets.indices) {
                val tj = t - delays[j]
                if (tj < 0) continue
                val scoop = if (tj < .07) -45.0 * (1 - tj / .07) else 0.0
                val fr = f * 2.0.pow((dets[j] + vib + scoop) / 1200)
                val dt = fr / sr
                ph[j] += dt; if (ph[j] >= 1) ph[j] -= 1.0
                bus += wave(Wave.SAW, ph[j], dt)
            }
            bus *= busGain
            if (!ens) bus += breath.process(rnd.nextDouble() * 2 - 1) * .025
            var y = 0.0
            for (b in 0..2) y += bands[b].process(bus) * fGain[b] * 4
            out.add(i, y * env)
        }
    }

    private fun drum(out: FloatArray, start: Int, hit: Char, vel: Double, preset: String) {
        val boom = preset == "boom"
        val lofi = preset == "lofi"
        val t9 = preset == "909"
        val dec = when (hit) { 'k' -> if (boom) .6 else if (t9) .45 else .32; 's' -> .22; 'c' -> .25; 'o' -> if (t9) .26 else .32; 'h' -> if (lofi) .035 else if (t9) .06 else .05; else -> .35 }
        val n = ((dec + .03) * sr).toInt()
        val bp = Biquad(); val hp = Biquad(); val click = Biquad()
        when (hit) {
            's' -> bp.set('b', 1900.0, .8, sr)
            'c' -> bp.set('b', 1150.0, 1.2, sr)
            'h', 'o' -> hp.set('h', 7200.0, .7, sr)
        }
        click.set('h', 2500.0, .7, sr)
        var ph = 0.0
        for (k in 0 until n) {
            val i = start + k
            if (i >= out.size) break
            if (i < 0) continue
            val t = k.toDouble() / sr
            val noise = rnd.nextDouble() * 2 - 1
            val y = when (hit) {
                'k' -> {
                    val f = expRamp(if (boom) 125.0 else if (t9) 175.0 else 155.0, if (boom) 41.0 else if (t9) 47.0 else 50.0, t, if (boom) .12 else if (t9) .055 else .08)
                    ph += f / sr
                    sin(2 * PI * ph) * expRamp(vel * (if (t9) 1.25 else 1.1), .0005, t, dec) + (if (t < .01) click.process(noise) * expRamp(vel * .25, .0005, t, .01) else 0.0)
                }
                's' -> {
                    val f = expRamp(200.0, 160.0, t, .1); ph += f / sr
                    bp.process(noise) * expRamp(vel * .9, .0005, t, .2) + (4 * abs(ph % 1.0 - .5) - 1) * (if (t < .12) expRamp(vel * .5, .0005, t, .12) else 0.0)
                }
                'c' -> {
                    val pk = vel * 1.1
                    val g = when {
                        t < .012 -> expRamp(pk, .05, t, .01)
                        t < .024 -> expRamp(pk, .05, t - .012, .01)
                        else -> expRamp(pk, .0005, t - .024, .196)
                    }
                    bp.process(noise) * g
                }
                'h', 'o' -> hp.process(noise) * expRamp(vel * (if (lofi) .3 else if (t9) .5 else .42), .0005, t, dec)
                else -> { val f = expRamp(175.0, 95.0, t, .2); ph += f / sr; sin(2 * PI * ph) * expRamp(vel * .8, .0005, t, .32) }
            }
            out[i] += y.toFloat()
        }
    }

    private fun reverb(input: FloatArray, l: FloatArray, r: FloatArray) {
        val scale = sr / 44100.0
        val combLens = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491)
        val apLens = intArrayOf(556, 441, 341)
        for (ch in 0..1) {
            val spread = ch * 23
            val combs = combLens.map { FloatArray(((it + spread) * scale).toInt()) }
            val ci = IntArray(combs.size); val store = FloatArray(combs.size)
            val aps = apLens.map { FloatArray(((it + spread) * scale).toInt()) }
            val ai = IntArray(aps.size)
            val dst = if (ch == 0) l else r
            for (n in input.indices) {
                val x = input[n] * .02f
                var acc = 0f
                for (c in combs.indices) {
                    val buf = combs[c]; val o = buf[ci[c]]
                    store[c] = o * .75f + store[c] * .25f
                    buf[ci[c]] = x + store[c] * .8f
                    ci[c] = (ci[c] + 1) % buf.size
                    acc += o
                }
                for (a in aps.indices) {
                    val buf = aps[a]; val b = buf[ai[a]]
                    val o = -acc + b
                    buf[ai[a]] = acc + b * .5f
                    ai[a] = (ai[a] + 1) % buf.size
                    acc = o
                }
                dst[n] += acc * 3.5f
            }
        }
    }

    /** Render the whole song. Returns interleaved-ready left and right channels. */
    fun render(song: Song, vol: Map<String, Double> = defaultVol, onProgress: (Double) -> Unit = {}): Pair<FloatArray, FloatArray> {
        val (events, totalBeats) = expand(song)
        val spb = 60.0 / song.bpm
        val len = ((totalBeats * spb + 3.5) * sr).toInt()
        val l = FloatArray(len); val r = FloatArray(len); val rev = FloatArray(len); val drums = FloatArray(len)
        val outs = TRACKS.associateWith { t -> Out(l, r, rev, vol[t] ?: 0.7, send[t] ?: 0.0, pan[t] ?: 0.0) }
        val vs = Voices.of(song.instruments["vocal"] ?: "none")
        val drumPreset = song.instruments["drums"] ?: "909"
        events.forEachIndexed { idx, e ->
            val t = .05 + e.beat * spb + swingOffset(e.beat, song.swing, spb)
            val start = (t * sr).toInt()
            val dur = e.dur * spb
            when (e.track) {
                "drums" -> drum(drums, start, e.hit, e.vel, drumPreset)
                "vocal" -> {
                    val o = outs["vocal"]!!
                    val f = mtof(e.midi.toDouble())
                    when {
                        vs.female && vs.male -> {
                            sing(o, start, dur, f, e.vel, "female", e.vowel, false)
                            sing(o, start, dur, mtof((if (e.midi >= 52) e.midi - 12 else e.midi).toDouble()), e.vel * .8, "male", e.vowel, false)
                        }
                        vs.female -> sing(o, start, dur, f, e.vel, "female", e.vowel, false)
                        vs.male -> sing(o, start, dur, f, e.vel, "male", e.vowel, false)
                        vs.choir -> {
                            sing(o, start, dur, f, e.vel, "female", e.vowel, true)
                            sing(o, start, dur, mtof(e.midi - 12.0), e.vel, "male", e.vowel, true)
                        }
                    }
                }
                else -> {
                    val p = presets[e.track + ":" + song.instruments[e.track]] ?: presets[e.track + ":" + PRESETS[e.track]!![0]]!!
                    val o = if (e.track == "chords") Out(l, r, rev, vol["chords"] ?: .6, send["chords"]!!, if (e.midi % 2 == 0) -.25 else .25) else outs[e.track]!!
                    note(p, o, start, dur, mtof(e.midi.toDouble()), e.vel)
                    if (e.track == "chords" && vs.choir && e.dur >= 1 && e.midi >= 50) {
                        sing(outs["vocal"]!!, start, dur, mtof(e.midi.toDouble()), e.vel * .75, if (e.midi < 60) "male" else "female", if (e.sec % 2 == 1) 'a' else 'u', true)
                    }
                }
            }
            if (idx % 64 == 0) onProgress(idx.toDouble() / events.size)
        }
        if (drumPreset == "lofi") { val f = Biquad().apply { set('l', 3600.0, .7, sr) }; for (i in drums.indices) drums[i] = f.process(drums[i].toDouble()).toFloat() }
        val dv = (vol["drums"] ?: .9).toFloat(); val ds = (send["drums"]!! * (vol["drums"] ?: .9)).toFloat()
        for (i in drums.indices) { l[i] += drums[i] * dv; r[i] += drums[i] * dv; rev[i] += drums[i] * ds }
        reverb(rev, l, r)
        var peak = 1e-6f
        for (i in l.indices) { peak = max(peak, max(abs(l[i]), abs(r[i]))) }
        val g = min(4f, .89f / peak)
        for (i in l.indices) { l[i] *= g; r[i] *= g }
        onProgress(1.0)
        return l to r
    }

    fun writeWav(l: FloatArray, r: FloatArray, file: File) {
        val n = l.size
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { os ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + n * 4); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(sr); h.putInt(sr * 4); h.putShort(4); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(n * 4)
            os.write(h.array())
            val buf = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                buf.putShort((l[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                buf.putShort((r[i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                if (!buf.hasRemaining()) { os.write(buf.array(), 0, buf.position()); buf.clear() }
            }
            if (buf.position() > 0) os.write(buf.array(), 0, buf.position())
        }
    }

}
