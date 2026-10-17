package app.songsmith

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The heart of the app. Android Auto, "Hey Google" and the phone screen all talk to this
 * media library service. A request to play an idea composes a song and plays it.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var composer: Composer
    private lateinit var lib: Library
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var ttsCount = 0

    companion object {
        const val ROOT = "root"
        const val IDEAS = "ideas"
        const val SONGS = "songs"
        const val EXTRA_BRIEF = "brief"
        const val EXTRA_VOICES = "voices"
        const val EXTRA_LENGTH = "length"
        const val EXTRA_CHANGE = "change"

        /** One-tap ideas for the car screen. */
        val IDEA_LIST = listOf(
            "Surprise me" to "Surprise me with a catchy, feel-good song in any genre.",
            "Chill amapiano" to "A chill amapiano groove with log drum bass, warm keys and a sunset feel.",
            "Uplifting gospel" to "An uplifting gospel song with a big choir chorus, organ and handclaps.",
            "Late-night R&B" to "A smooth late-night R&B slow jam with lush chords.",
            "Road-trip pop" to "An upbeat road-trip pop anthem with a singalong chorus.",
            "Lo-fi focus" to "A mellow lo-fi hip hop beat for focus, dusty drums and jazzy keys.",
        )
    }

    override fun onCreate() {
        super.onCreate()
        composer = Composer(this)
        lib = Library(this)
        prefs = Prefs(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(open).build()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { id?.let { pending.remove(it)?.complete(true) } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { pending.remove(it)?.complete(false) } }
                    override fun onError(id: String?, code: Int) { id?.let { pending.remove(it)?.complete(false) } }
                })
            }
            ttsReady.complete(status == TextToSpeech.SUCCESS)
        }
        scope.launch(Dispatchers.Default) { runCatching { composer.waitingMusic(waitingFile()) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        tts?.shutdown()
        session.release()
        player.release()
        super.onDestroy()
    }

    /* ---------------- media items ---------------- */

    private fun waitingFile() = File(cacheDir, "waiting.wav")

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
    ).build()

    private fun action(id: String, title: String, subtitle: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
    ).build()

    private fun songItem(e: Entry) = MediaItem.Builder().setMediaId("song:${e.id}").setUri(Uri.fromFile(lib.wav(e.id))).setMediaMetadata(
        MediaMetadata.Builder().setTitle(e.title)
            .setArtist(listOf(e.genre, VOCAL_LABEL[e.vocal] ?: "").filter { it.isNotBlank() }.joinToString(" · "))
            .setAlbumTitle("Songsmith").setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
    ).build()

    private fun fileItem(id: String, file: File, title: String) = MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(file)).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist("Songsmith").setIsBrowsable(false).setIsPlayable(true).build()
    ).build()

    /** Speak a sentence by rendering it to a file the player can queue like any track. */
    private suspend fun speech(text: String): MediaItem? {
        val ok = withTimeoutOrNull(4000) { ttsReady.await() } ?: false
        val engine = tts
        if (!ok || engine == null) return null
        val id = "tts${ttsCount++}"
        val file = File(cacheDir, "$id.wav")
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        val params = Bundle()
        if (engine.synthesizeToFile(text, params, file, id) != TextToSpeech.SUCCESS) { pending.remove(id); return null }
        val good = withTimeoutOrNull(10_000) { done.await() } ?: false
        return if (good && file.exists()) fileItem("speech:$id", file, text) else null
    }

    private suspend fun say(text: String): MutableList<MediaItem> = listOfNotNull(speech(text)).toMutableList()

    private fun play(items: List<MediaItem>) {
        if (items.isEmpty()) return
        player.setMediaItems(items)
        player.prepare()
        player.play()
    }

    /* ---------------- requests ---------------- */

    private val refineWords = Regex("^(make (it|the)|change|add|remove|switch|swap|turn|slower|faster|more|less|give it|put)\\b", RegexOption.IGNORE_CASE)

    /** A spoken request: "chill amapiano with a female singer", "make it slower", or nothing at all. */
    private suspend fun fromVoice(raw: String): MutableList<MediaItem> {
        val q = raw.trim().removeSuffix(".").trim()
        if (q.isEmpty() || q.equals("songsmith", true)) {
            val latest = lib.latest() ?: return startCompose(IDEA_LIST[0].second, prefs.voices, prefs.length)
            return mutableListOf(songItem(latest))
        }
        lib.find(q).firstOrNull()?.let { if (it.title.equals(q, true)) return mutableListOf(songItem(it)) }
        if (refineWords.containsMatchIn(q)) lib.latest()?.let { return startRefine(it.id, q) }
        return startCompose(q, voicesFrom(q), lengthFrom(q))
    }

    private fun voicesFrom(q: String): Voices {
        val s = q.lowercase()
        if (Regex("\\binstrumental\\b|no (vocals|singing|singer)").containsMatchIn(s)) return Voices(false, false, false)
        val male = Regex("\\bmale\\b|\\bman\\b|\\bguy\\b|\\bboy\\b").containsMatchIn(s)
        val female = Regex("\\bfemale\\b|\\bwoman\\b|\\bgirl\\b|\\blady\\b").containsMatchIn(s)
        val choir = Regex("\\bchoir\\b|\\bchorus of voices\\b").containsMatchIn(s)
        return if (male || female || choir) Voices(male, female, choir) else prefs.voices
    }

    private fun lengthFrom(q: String) = if (Regex("\\b(short|quick|sketch)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)) "short" else prefs.length

    private suspend fun startCompose(brief: String, voices: Voices, length: String): MutableList<MediaItem> {
        if (job?.isActive == true) return say("I'm still working on the last song. It'll start as soon as it's ready.")
        if (prefs.apiKey.isBlank()) {
            Studio.fail("Add your Anthropic API key in Settings first.")
            return say("Open Songsmith on your phone and add your Anthropic API key in settings first.")
        }
        job = scope.launch {
            try {
                val e = withContext(Dispatchers.IO) { composer.compose(brief, voices, length) }
                Studio.done("“${e.title}” is ready.")
                session.notifyChildrenChanged(SONGS, lib.list().size, null)
                play(say("Here's ${e.title}.") + songItem(e))
            } catch (ex: Exception) {
                if (ex is kotlinx.coroutines.CancellationException) throw ex
                val msg = ex.message ?: "Something went wrong."
                Studio.fail(msg)
                play(say("Sorry, that didn't work. $msg"))
            }
        }
        val who = if (voices.combo() == "none") "an instrumental" else "a song with ${voices.describe().lowercase()} vocals"
        val intro = say("Composing $who. Give me about a minute.")
        val bed = waitingFile()
        if (bed.exists()) intro.add(fileItem("waiting", bed, "Composing your song…"))
        return intro
    }

    private suspend fun startRefine(id: String, change: String): MutableList<MediaItem> {
        if (job?.isActive == true) return say("I'm still working on the last song.")
        if (prefs.apiKey.isBlank()) return say("Open Songsmith on your phone and add your Anthropic API key in settings first.")
        job = scope.launch {
            try {
                val e = withContext(Dispatchers.IO) { composer.refine(id, change) }
                Studio.done("“${e.title}” is updated.")
                session.notifyChildrenChanged(SONGS, lib.list().size, null)
                play(say("Here's the new version.") + songItem(e))
            } catch (ex: Exception) {
                if (ex is kotlinx.coroutines.CancellationException) throw ex
                val msg = ex.message ?: "Something went wrong."
                Studio.fail(msg)
                play(say("Sorry, that didn't work. $msg"))
            }
        }
        val intro = say("Okay, changing it. One moment.")
        val bed = waitingFile()
        if (bed.exists()) intro.add(fileItem("waiting", bed, "Reworking your song…"))
        return intro
    }

    private suspend fun resolve(item: MediaItem): MutableList<MediaItem> {
        val query = item.requestMetadata.searchQuery
        if (query != null) return fromVoice(query)
        val extras = item.requestMetadata.extras
        val id = item.mediaId
        return when {
            id.startsWith("song:") -> {
                // Queue every song, starting from the one picked.
                val all = lib.list()
                val start = all.indexOfFirst { "song:${it.id}" == id }
                if (start < 0) mutableListOf() else (all.drop(start) + all.take(start)).map { songItem(it) }.toMutableList()
            }
            id.startsWith("idea:") -> {
                val idea = IDEA_LIST.getOrNull(id.removePrefix("idea:").toIntOrNull() ?: 0) ?: IDEA_LIST[0]
                val v = if (idea.first == "Uplifting gospel") prefs.voices.copy(choir = true) else prefs.voices
                startCompose(idea.second, v, prefs.length)
            }
            id.startsWith("ask:") -> fromVoice(id.removePrefix("ask:"))
            id == "compose" -> startCompose(
                extras?.getString(EXTRA_BRIEF) ?: "Surprise me.",
                Voices.of(extras?.getString(EXTRA_VOICES) ?: prefs.voices.combo()),
                extras?.getString(EXTRA_LENGTH) ?: prefs.length,
            )
            id.startsWith("refine:") -> startRefine(id.removePrefix("refine:"), extras?.getString(EXTRA_CHANGE) ?: "Make it better.")
            id == "latest" -> lib.latest()?.let { mutableListOf(songItem(it)) } ?: mutableListOf()
            else -> mutableListOf()
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Songsmith"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items: List<MediaItem> = when (parentId) {
                ROOT -> listOf(folder(IDEAS, "Start a song"), folder(SONGS, "Your songs"))
                IDEAS -> IDEA_LIST.mapIndexed { i, (title, _) -> action("idea:$i", title, "Tap to compose") }
                SONGS -> lib.list().map { songItem(it) }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = (from + pageSize).coerceAtMost(items.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when {
                mediaId == ROOT -> folder(ROOT, "Songsmith")
                mediaId == IDEAS -> folder(IDEAS, "Start a song")
                mediaId == SONGS -> folder(SONGS, "Your songs")
                mediaId.startsWith("song:") -> lib.list().firstOrNull { "song:${it.id}" == mediaId }?.let { songItem(it) }
                mediaId.startsWith("idea:") -> IDEA_LIST.getOrNull(mediaId.removePrefix("idea:").toIntOrNull() ?: -1)?.let { action(mediaId, it.first, "Tap to compose") }
                else -> null
            } ?: folder(ROOT, "Songsmith")
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, 1 + lib.find(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = listOf(action("ask:$query", "Compose “$query”", "New song")) + lib.find(query).map { songItem(it) }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val first = mediaItems.firstOrNull() ?: return Futures.immediateFuture(mutableListOf())
            return scope.future {
                // Several plain song items (a queue from a controller) resolve one by one; a request resolves to its own list.
                if (mediaItems.size > 1 && mediaItems.all { it.mediaId.startsWith("song:") }) {
                    val all = lib.list()
                    mediaItems.mapNotNull { m -> all.firstOrNull { "song:${it.id}" == m.mediaId }?.let { songItem(it) } }.toMutableList()
                } else resolve(first)
            }
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val items = lib.list().map { songItem(it) }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET))
        }
    }
}
