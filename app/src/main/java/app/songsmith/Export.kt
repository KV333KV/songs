package app.songsmith

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads are copyright-safe: snippets recorded from other people's music are left out of every
 * exported file unless the user marked them as their own sound. Everything else is synthesized
 * from Claude's original score.
 */
object Exporter {
    class Result(val file: File, val removedSamples: Int)

    /** Sample ids in this song that aren't cleared for export. */
    fun uncleared(ctx: Context, sampleIds: Collection<String>): List<String> {
        val store = SampleStore(ctx)
        return sampleIds.filter { store.meta(it)?.cleared != true }
    }

    suspend fun prepare(ctx: Context, id: String): Result = withContext(Dispatchers.Default) {
        val lib = Library(ctx)
        val store = SampleStore(ctx)
        val (entry, song) = lib.load(id) ?: throw IllegalStateException("That song isn't on this phone any more.")
        if (lib.mp3(id).exists()) {
            // Finished audio from Lyria never contains the user's recorded snippets, so it can be shared as is.
            val mp3 = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, safeName(entry.title) + ".mp3")
            lib.mp3(id).copyTo(mp3, overwrite = true)
            return@withContext Result(mp3, 0)
        }
        val used = song.patterns.values.flatMap { p -> p.samples.map { it.id } }.toSet()
        val blocked = uncleared(ctx, used).toSet()
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, safeName(entry.title) + ".wav")
        if (blocked.isEmpty()) {
            lib.wav(id).copyTo(out, overwrite = true)
            return@withContext Result(out, 0)
        }
        val patterns = LinkedHashMap<String, Pattern>()
        song.patterns.forEach { (pid, p) -> patterns[pid] = Pattern(p.bars, p.drums, p.notes, p.samples.filter { it.id !in blocked }) }
        val clean = Song(song.title, song.genre, song.bpm, song.key, song.swing, song.summary, song.instruments, patterns, song.arrangement)
        val allowed = (used - blocked).mapNotNull { sid -> store.pcm(sid)?.let { sid to (it to store.meta(sid)) } }.toMap()
        val synth = Synth()
        val (l, r) = synth.render(clean, synth.defaultVol, allowed)
        synth.writeWav(l, r, out)
        Result(out, blocked.size)
    }

    fun canSaveToMusic() = Build.VERSION.SDK_INT >= 29

    /** Saves into Music/Songsmith so it shows up in any music player. Android 10 and newer. */
    fun saveToMusic(ctx: Context, file: File, title: String): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, if (file.extension == "mp3") "audio/mpeg" else "audio/wav")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, "Songsmith")
            put(MediaStore.Audio.Media.ALBUM, "Songsmith")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Songsmith")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("Android didn't allow saving to Music.")
        resolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun share(ctx: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "mp3") "audio/mpeg" else "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share “$title”").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun safeName(s: String) = s.replace(Regex("[^\\w\\- ]+"), "").trim().replace(Regex("\\s+"), "-").take(40).ifEmpty { "song" }
}
