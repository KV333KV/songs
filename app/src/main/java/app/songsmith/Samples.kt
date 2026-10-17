package app.songsmith

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Sample rate used for every stored snippet and for rendering. */
const val SAMPLE_RATE = 32000

val SAMPLE_ROLES = listOf("loop", "hit", "texture")
val SAMPLE_ROLE_LABEL = mapOf("loop" to "Loop", "hit" to "Hit / chop", "texture" to "Texture")

data class SampleMeta(
    val id: String, val name: String, val role: String, val note: String,
    val seconds: Double, val bpm: Int?, val key: String?, val created: Long,
    /** True when the user says it's their own sound or they have permission: only then does it go into downloads. */
    val cleared: Boolean = false,
) {
    fun describe(): String = buildString {
        append("id \"").append(id).append("\": \"").append(name).append("\", role ").append(role)
        append(", ").append("%.1f".format(seconds)).append(" s")
        bpm?.let { append(", about $it BPM") }
        key?.let { append(", key $it") }
        if (note.isNotBlank()) append(". The user likes: ").append(note)
    }
}

/** Snippets live in app storage as <id>.wav (mono, 32 kHz) plus <id>.json. */
class SampleStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "samples").apply { mkdirs() }
    fun wav(id: String) = File(dir, "$id.wav")
    private fun json(id: String) = File(dir, "$id.json")

    fun list(): List<SampleMeta> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { runCatching { meta(it.nameWithoutExtension) }.getOrNull() }
        .filter { wav(it.id).exists() }
        .sortedByDescending { it.created }

    fun meta(id: String): SampleMeta? {
        val f = json(id); if (!f.exists()) return null
        val o = JSONObject(f.readText())
        return SampleMeta(
            id, o.optString("name", "Snippet"), o.optString("role", "hit"), o.optString("note", ""),
            o.optDouble("seconds", 0.0), if (o.has("bpm")) o.optInt("bpm") else null,
            if (o.has("key")) o.optString("key") else null, o.optLong("created"), o.optBoolean("cleared", false),
        )
    }

    fun save(pcm: FloatArray, name: String, role: String, note: String, cleared: Boolean = false): SampleMeta {
        val id = "smp" + System.currentTimeMillis().toString(36)
        val norm = normalize(pcm)
        writeMonoWav(norm, SAMPLE_RATE, wav(id))
        val a = analyze(norm, SAMPLE_RATE)
        val o = JSONObject().put("name", name.ifBlank { "Snippet" }.take(40)).put("role", role).put("note", note.take(200))
            .put("seconds", norm.size.toDouble() / SAMPLE_RATE).put("created", System.currentTimeMillis()).put("cleared", cleared)
        a.bpm?.let { o.put("bpm", it) }
        a.key?.let { o.put("key", it) }
        json(id).writeText(o.toString())
        return meta(id)!!
    }

    fun setCleared(id: String, cleared: Boolean) {
        val o = JSONObject(json(id).readText()).put("cleared", cleared)
        json(id).writeText(o.toString())
    }

    fun delete(id: String) { json(id).delete(); wav(id).delete() }

    fun pcm(id: String): FloatArray? = wav(id).takeIf { it.exists() }?.let { readMonoWav(it) }
}

/* ---------------- recording ---------------- */

class Recorder {
    /** Records from the microphone until [keepGoing] returns false or [maxSec] passes. */
    @SuppressLint("MissingPermission")
    suspend fun record(maxSec: Double, keepGoing: () -> Boolean, onLevel: (Float, Double) -> Unit): FloatArray = withContext(Dispatchers.IO) {
        val sr = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fun open(source: Int) = runCatching {
            AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192) * 2)
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
        // UNPROCESSED avoids voice-call style noise suppression that ruins music.
        val rec = open(MediaRecorder.AudioSource.UNPROCESSED) ?: open(MediaRecorder.AudioSource.MIC)
            ?: throw IllegalStateException("The microphone isn't available.")
        val total = (maxSec * sr).toInt()
        val out = ShortArray(total)
        var n = 0
        val chunk = ShortArray(1024)
        try {
            rec.startRecording()
            while (n < total && keepGoing()) {
                val got = rec.read(chunk, 0, min(chunk.size, total - n))
                if (got <= 0) continue
                var peak = 0
                for (i in 0 until got) { out[n + i] = chunk[i]; peak = max(peak, abs(chunk[i].toInt())) }
                n += got
                onLevel(peak / 32768f, n.toDouble() / sr)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        FloatArray(n) { out[it] / 32768f }
    }
}

/* ---------------- importing audio files ---------------- */

/** Decode any audio file Android understands (mp3, m4a, wav, ogg, flac…) to mono at [targetSr]. */
suspend fun decodeAudio(ctx: Context, uri: Uri, maxSec: Double, targetSr: Int = SAMPLE_RATE): FloatArray = withContext(Dispatchers.IO) {
    val ex = MediaExtractor()
    ex.setDataSource(ctx, uri, null)
    val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ?: run { ex.release(); throw IllegalStateException("That file has no audio.") }
    ex.selectTrack(track)
    val fmt = ex.getTrackFormat(track)
    val mime = fmt.getString(MediaFormat.KEY_MIME)!!
    var srcSr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var floatPcm = false
    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(fmt, null, null, 0)
    codec.start()
    var buf = FloatArray(srcSr * 10)
    var n = 0
    fun push(v: Float) { if (n == buf.size) buf = buf.copyOf(buf.size * 2); buf[n++] = v }
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    try {
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    val size = ex.readSampleData(ib, 0)
                    if (size < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                srcSr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                ob.position(info.offset); ob.limit(info.offset + info.size)
                if (floatPcm) {
                    val fb = ob.asFloatBuffer()
                    while (fb.remaining() >= ch) { var s = 0f; repeat(ch) { s += fb.get() }; push(s / ch) }
                } else {
                    val sb = ob.asShortBuffer()
                    while (sb.remaining() >= ch) { var s = 0f; repeat(ch) { s += sb.get() / 32768f }; push(s / ch) }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n >= maxSec * srcSr) outputDone = true
            }
        }
    } finally {
        runCatching { codec.stop() }; codec.release(); ex.release()
    }
    resample(buf.copyOf(min(n, (maxSec * srcSr).toInt())), srcSr, targetSr)
}

/* ---------------- helpers ---------------- */

fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to || x.isEmpty()) return x
    val ratio = from.toDouble() / to
    val n = (x.size / ratio).toInt()
    // Light low-pass first when going down in rate, to limit aliasing.
    val src = if (from > to) { val y = FloatArray(x.size); var a = 0f; val k = (to.toFloat() / from).coerceIn(.05f, 1f); for (i in x.indices) { a += (x[i] - a) * k; y[i] = a }; y } else x
    return FloatArray(n) { i ->
        val p = i * ratio; val j = p.toInt(); val f = (p - j).toFloat()
        val a = src[min(j, src.size - 1)]; val b = src[min(j + 1, src.size - 1)]
        a + (b - a) * f
    }
}

fun normalize(x: FloatArray): FloatArray {
    var peak = 1e-6f; for (v in x) peak = max(peak, abs(v))
    val g = min(30f, .9f / peak)
    // 8 ms fades so snippets never click.
    val fade = (SAMPLE_RATE * .008).toInt()
    return FloatArray(x.size) { i ->
        val e = min(1f, min(i, x.size - 1 - i).toFloat() / fade)
        x[i] * g * e
    }
}

fun peaks(x: FloatArray, bins: Int): FloatArray {
    val out = FloatArray(bins)
    if (x.isEmpty()) return out
    for (b in 0 until bins) {
        val a = (b.toLong() * x.size / bins).toInt(); val z = max(a + 1, ((b + 1).toLong() * x.size / bins).toInt())
        var p = 0f; for (i in a until min(z, x.size)) p = max(p, abs(x[i]))
        out[b] = p
    }
    val m = out.maxOrNull()?.takeIf { it > 0 } ?: 1f
    for (b in out.indices) out[b] /= m
    return out
}

fun writeMonoWav(x: FloatArray, sr: Int, file: File) {
    BufferedOutputStream(FileOutputStream(file)).use { os ->
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + x.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(sr); h.putInt(sr * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(x.size * 2)
        os.write(h.array())
        val b = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        os.write(b.array())
    }
}

/** Reads the mono 16-bit files this app writes. */
fun readMonoWav(file: File): FloatArray {
    val bytes = file.readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    bb.position(44)
    val sb = bb.asShortBuffer()
    return FloatArray(sb.remaining()) { sb.get() / 32768f }
}

/* ---------------- analysis: tempo and key ---------------- */

class Analysis(val bpm: Int?, val key: String?)

private val KMAJ = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val KMIN = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
    }
    var len = 2
    while (len <= n) {
        val ang = -2 * PI / len
        for (i in 0 until n step len) for (k in 0 until len / 2) {
            val wr = cos(ang * k); val wi = sin(ang * k)
            val ur = re[i + k]; val ui = im[i + k]
            val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
            val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
            re[i + k] = ur + vr; im[i + k] = ui + vi
            re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
        }
        len = len shl 1
    }
}

private fun corr(a: DoubleArray, b: DoubleArray): Double {
    val ma = a.average(); val mb = b.average()
    var s = 0.0; var sa = 0.0; var sb = 0.0
    for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; s += x * y; sa += x * x; sb += y * y }
    return s / sqrt(max(sa * sb, 1e-12))
}

fun analyze(x: FloatArray, sr: Int): Analysis {
    val asr = 11025
    val y = resample(x, sr, asr)
    val nfft = 1024; val hop = 256
    val frames = (y.size - nfft) / hop
    if (frames < 20) return Analysis(null, null)
    val han = DoubleArray(nfft) { .5 - .5 * cos(2 * PI * it / (nfft - 1)) }
    val half = nfft / 2
    val prev = DoubleArray(half)
    val onset = DoubleArray(frames)
    val chroma = DoubleArray(12)
    val re = DoubleArray(nfft); val im = DoubleArray(nfft)
    for (f in 0 until frames) {
        for (i in 0 until nfft) { re[i] = y[f * hop + i] * han[i]; im[i] = 0.0 }
        fft(re, im)
        var flux = 0.0
        for (k in 1 until half) {
            val m = hypot(re[k], im[k]); val lm = ln(1 + 100 * m)
            val d = lm - prev[k]; if (d > 0) flux += d; prev[k] = lm
            val hz = k * asr.toDouble() / nfft
            if (hz in 100.0..2600.0) {
                val pc = (((12 * log2(hz / 440)).roundToInt() + 69) % 12 + 12) % 12
                chroma[pc] += m
            }
        }
        onset[f] = flux
    }
    // Key
    var best = -2.0; var key: String? = null
    for (r in 0 until 12) {
        val rot = DoubleArray(12) { chroma[(it + r) % 12] }
        val sMaj = corr(rot, KMAJ); val sMin = corr(rot, KMIN)
        if (sMaj > best) { best = sMaj; key = NAMES[r] + " major" }
        if (sMin > best) { best = sMin; key = NAMES[r] + " minor" }
    }
    if (best < .45) key = null
    // Tempo (needs a few seconds of material)
    val fr = asr.toDouble() / hop
    var bpm: Int? = null
    if (frames > fr * 3) {
        val w = fr.toInt()
        val env = DoubleArray(frames) { i ->
            val a = max(0, i - w); val b = min(frames, i + w)
            var s = 0.0; for (j in a until b) s += onset[j]
            max(0.0, onset[i] - s / (b - a))
        }
        val lo = (fr * 60 / 190).toInt(); val hi = (fr * 60 / 70).toInt()
        var bl = lo; var bs = -1.0
        for (l in lo..hi) {
            var s = 0.0; for (i in 0 until frames - l) s += env[i] * env[i + l]
            s /= (frames - l)
            val b = 60 * fr / l
            val wgt = exp(-.5 * (log2(b / 130) / .7).pow(2))
            if (s * wgt > bs) { bs = s * wgt; bl = l }
        }
        if (bs > 0) bpm = (60 * fr / bl).roundToInt()
    }
    return Analysis(bpm, key)
}
