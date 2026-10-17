@file:OptIn(ExperimentalMaterial3Api::class)

package app.songsmith

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val RecRed = Color(0xFFE5484D)
private const val MAX_SNIPPET = 30.0

/** Plays a stored snippet or an unsaved recording through the phone speaker. */
object Preview {
    private var mp: MediaPlayer? = null
    fun stop() { runCatching { mp?.stop(); mp?.release() }; mp = null }
    fun file(f: File) {
        stop()
        mp = runCatching { MediaPlayer().apply { setDataSource(f.path); prepare(); start(); setOnCompletionListener { stop() } } }.getOrNull()
    }
    fun pcm(ctx: Context, x: FloatArray) {
        val f = File(ctx.cacheDir, "preview.wav")
        writeMonoWav(normalize(x), SAMPLE_RATE, f)
        file(f)
    }
}

class RecState {
    var recording by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var secs by mutableDoubleStateOf(0.0)
}

/** A tap-to-start, tap-to-stop recorder that asks for the microphone the first time. */
@Composable
fun rememberSnippetRecorder(onDone: (FloatArray) -> Unit, onError: (String) -> Unit): Pair<RecState, () -> Unit> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { RecState() }
    val recorder = remember { Recorder() }
    fun start() {
        Preview.stop()
        st.recording = true; st.secs = 0.0
        scope.launch {
            try {
                val pcm = recorder.record(MAX_SNIPPET, { st.recording }) { l, s -> st.level = l; st.secs = s }
                if (pcm.size < SAMPLE_RATE / 2) onError("That was too short. Record at least half a second.") else onDone(pcm)
            } catch (e: Exception) {
                onError(e.message ?: "Recording failed.")
            } finally {
                st.recording = false; st.level = 0f
            }
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else onError("Songsmith needs microphone access to record snippets. Allow it in Android settings.")
    }
    val toggle: () -> Unit = {
        when {
            st.recording -> st.recording = false
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> start()
            else -> perm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return st to toggle
}

@Composable
fun RecordButton(st: RecState, size: Int, onClick: () -> Unit) {
    val ring = if (st.recording) (6 + 22 * st.level).dp else 0.dp
    Box(
        Modifier.size((size + 48).dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size.dp).border(BorderStroke(ring, RecRed.copy(alpha = .3f)), CircleShape).padding(ring)
                .clip(CircleShape).background(RecRed).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (st.recording) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size((size / 5).dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
                    Spacer(Modifier.height(8.dp))
                    Text("%.0f s".format(st.secs), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            } else {
                Icon(painterResource(R.drawable.ic_mic), "Record", tint = Color.White, modifier = Modifier.size((size / 3).dp))
            }
        }
    }
}

@Composable
fun SamplesScreen(state: Studio.State) {
    val ctx = LocalContext.current
    val store = remember { SampleStore(ctx) }
    val scope = rememberCoroutineScope()
    var list by remember(state.libraryVersion) { mutableStateOf(store.list()) }
    var draft by remember { mutableStateOf<FloatArray?>(null) }
    var message by remember { mutableStateOf("") }
    val (rec, toggle) = rememberSnippetRecorder(onDone = { draft = it; message = "" }, onError = { message = it })
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "Opening the file…"
            message = runCatching { draft = decodeAudio(ctx, uri, 600.0); "" }.getOrElse { "Couldn't read that file. Try an MP3, M4A or WAV." }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Samples", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text("Hear a moment you love? Record it: a riff, a vocal, a drum break, a synth line. Claude builds tracks around it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RecordButton(rec, 170, toggle)
            Text(if (rec.recording) "Recording… tap to stop (max 30 s)" else "Tap to record what's playing around you",
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { importer.launch(arrayOf("audio/*")) }, enabled = !rec.recording) { Text("Import an audio file instead") }
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)

        draft?.let { pcm ->
            SampleEditor(pcm,
                onSave = { clip, name, role, note, own ->
                    scope.launch {
                        withContext(Dispatchers.Default) { store.save(clip, name, role, note, own) }
                        draft = null; Preview.stop(); list = store.list(); Studio.libraryChanged()
                    }
                },
                onDiscard = { draft = null; Preview.stop() })
        }

        Text("Your samples", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (list.isEmpty()) Text("Nothing saved yet. Recorded snippets show up here and on the Create screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { m -> SampleRow(m, store) { list = store.list(); Studio.libraryChanged() } }
        Text("Tip: hold the phone near the speaker and keep the room quiet. Snippets from other people's music play in the app but are always left out of downloads, unless you switch on “my own sound”.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SampleEditor(pcm: FloatArray, onSave: (FloatArray, String, String, String, Boolean) -> Unit, onDiscard: () -> Unit) {
    val ctx = LocalContext.current
    val total = pcm.size.toFloat() / SAMPLE_RATE
    var range by remember(pcm) { mutableStateOf(0f..min(total, MAX_SNIPPET.toFloat())) }
    val stamp = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date()) }
    var name by remember(pcm) { mutableStateOf("Snippet $stamp") }
    var role by remember(pcm) { mutableStateOf(if (total >= 4f) "loop" else "hit") }
    var note by remember(pcm) { mutableStateOf("") }
    var own by remember(pcm) { mutableStateOf(false) }
    val pk = remember(pcm) { peaks(pcm, 140) }
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
    fun clip() = pcm.copyOfRange((range.start * SAMPLE_RATE).toInt().coerceIn(0, pcm.size - 1), (range.endInclusive * SAMPLE_RATE).toInt().coerceIn(1, pcm.size))

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Keep the part you like", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val w = size.width / pk.size
                pk.forEachIndexed { i, v ->
                    val t = (i + .5f) / pk.size * total
                    val h = max(2f, v * size.height)
                    drawRect(if (t in range) accent else dim, Offset(i * w + w * .15f, (size.height - h) / 2), Size(w * .7f, h))
                }
            }
            RangeSlider(value = range, valueRange = 0f..total, onValueChange = { r ->
                range = if (r.endInclusive - r.start > MAX_SNIPPET) r.start..(r.start + MAX_SNIPPET.toFloat()) else r
            })
            Text("%.1f s to %.1f s · %.1f s long".format(range.start, range.endInclusive, range.endInclusive - range.start),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { Preview.pcm(ctx, clip()) }) { Text("Preview") }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("How should Claude use it?", fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SAMPLE_ROLES.forEachIndexed { i, r ->
                    SegmentedButton(selected = role == r, onClick = { role = r }, shape = SegmentedButtonDefaults.itemShape(i, SAMPLE_ROLES.size)) { Text(SAMPLE_ROLE_LABEL[r]!!) }
                }
            }
            Text(when (role) {
                "loop" -> "Repeats as a groove, matched to the track's tempo."
                "hit" -> "A one-shot Claude chops, stutters and pitches."
                else -> "An atmosphere under intros and breakdowns."
            }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(note, { note = it }, label = { Text("What do you love about it? (optional)") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(own, { own = it })
                Text("This is my own sound, or I have permission to use it", fontSize = 14.sp)
            }
            Text(if (own) "It will be included when you download tracks." else "You'll hear it in the app, but downloads leave it out so the file stays copyright-safe.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onSave(clip(), name, role, note, own) }) { Text("Save sample") }
                TextButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

@Composable
fun SampleRow(m: SampleMeta, store: SampleStore, onChanged: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { Preview.file(store.wav(m.id)) }) { Icon(painterResource(R.drawable.ic_play), "Play ${m.name}") }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(SAMPLE_ROLE_LABEL[m.role], "%.1f s".format(m.seconds), m.bpm?.let { "≈$it BPM" }, m.key).joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(m.cleared, { store.setCleared(m.id, it); onChanged() }, modifier = Modifier.padding(end = 8.dp))
                    Text(if (m.cleared) "My own sound: included in downloads" else "Left out of downloads", fontSize = 12.sp)
                }
            }
            TextButton(onClick = { if (armed) { store.delete(m.id); onChanged() } else armed = true }) {
                Text(if (armed) "Delete?" else "Delete", color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
