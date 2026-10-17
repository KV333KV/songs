@file:OptIn(ExperimentalMaterial3Api::class)

package app.songsmith

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

/** Lets a notification ask the app to open a particular tab. */
val OpenTab = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

private val Coral = Color(0xFFFF6B70)
private val Ink = Color(0xFF0E1116)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var pendingQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        pendingQuery = queryFrom(intent)
        if (intent?.getBooleanExtra("openInbox", false) == true) OpenTab.value = 3
        setContent { SongsmithTheme { App(controller) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("openInbox", false)) OpenTab.value = 3
        queryFrom(intent)?.let { q -> controller?.let { ask(it, q) } ?: run { pendingQuery = q } }
    }

    private fun queryFrom(i: Intent?): String? =
        if (i?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) (i.getStringExtra(SearchManager.QUERY) ?: "") else null

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            pendingQuery?.let { ask(c, it); pendingQuery = null }
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }
}

/* ---------------- talking to the service ---------------- */

fun ask(c: MediaController, query: String) {
    c.setMediaItem(MediaItem.Builder().setMediaId("ask:$query").build())
    c.prepare(); c.play()
}

fun requestCompose(c: MediaController, brief: String, voices: Voices, length: String, samples: List<String> = emptyList()) {
    val extras = Bundle().apply {
        putString(PlaybackService.EXTRA_SAMPLES, samples.joinToString(","))
        putString(PlaybackService.EXTRA_BRIEF, brief)
        putString(PlaybackService.EXTRA_VOICES, voices.combo())
        putString(PlaybackService.EXTRA_LENGTH, length)
    }
    c.setMediaItem(MediaItem.Builder().setMediaId("compose").setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build()).build())
    c.prepare(); c.play()
}

fun requestRefine(c: MediaController, id: String, change: String) {
    val extras = Bundle().apply { putString(PlaybackService.EXTRA_CHANGE, change) }
    c.setMediaItem(MediaItem.Builder().setMediaId("refine:$id").setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build()).build())
    c.prepare(); c.play()
}

fun playSong(c: MediaController, id: String) {
    c.setMediaItem(MediaItem.Builder().setMediaId("song:$id").build())
    c.prepare(); c.play()
}

/* ---------------- UI ---------------- */

@Composable
fun SongsmithTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Coral, onPrimary = Color(0xFF1A0607), background = Ink, surface = Color(0xFF171B22),
        surfaceVariant = Color(0xFF222833), onSurface = Color(0xFFE9EDF3), onSurfaceVariant = Color(0xFF98A2B3), secondary = Color(0xFFF27AB8),
    ) else lightColorScheme(
        primary = Color(0xFFE5484D), onPrimary = Color.White, background = Color(0xFFEDF0F4), surface = Color.White,
        surfaceVariant = Color(0xFFE2E7EE), onSurface = Color(0xFF121823), onSurfaceVariant = Color(0xFF5B6576), secondary = Color(0xFFC2367F),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun rememberDictation(prompt: String, onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    val ctx = LocalContext.current
    return {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        runCatching { launcher.launch(i) }
    }
}

@Composable
fun App(controller: MediaController?) {
    var tab by remember { mutableStateOf(0) }
    var driving by remember { mutableStateOf(false) }
    val state by Studio.state.collectAsStateWithLifecycle()
    val appCtx = LocalContext.current
    LaunchedEffect(Unit) { Updates.autoCheck(appCtx); Feedback.flush(appCtx) }

    val tourPrefs = remember { Prefs(appCtx) }
    var showTour by remember { mutableStateOf(!tourPrefs.seenTour) }
    val openTab by OpenTab.collectAsStateWithLifecycle()
    LaunchedEffect(openTab) { openTab?.let { tab = it; OpenTab.value = null } }
    if (showTour) { HowItWorks { showTour = false; tourPrefs.seenTour = true }; return }
    if (driving) { DrivingScreen(controller, state) { driving = false }; return }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Box(Modifier.statusBarsPadding()) { UpdateBanner() } },
        bottomBar = {
            Column {
                NowPlaying(controller)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(Triple("Create", R.drawable.ic_spark, 0), Triple("Learn", R.drawable.ic_learn, 4), Triple("Samples", R.drawable.ic_wave, 1), Triple("Library", R.drawable.ic_library, 2), Triple("Settings", R.drawable.ic_settings, 3)).forEach { (label, icon, i) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(painterResource(icon), null) }, label = { Text(label) })
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CreateScreen(controller, state, onHelp = { showTour = true }) { driving = true }
                1 -> SamplesScreen(state, controller) { m -> SampleHandoff.pending.value = m; tab = 0 }
                2 -> LibraryScreen(controller, state)
                4 -> LearnScreen(controller, state)
                else -> SettingsScreen { showTour = true }
            }
        }
    }
}

@Composable
fun StatusCard(state: Studio.State) {
    AnimatedVisibility(visible = state.message.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = if (state.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message, fontWeight = FontWeight.SemiBold)
                if (state.busy) {
                    val p = state.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun CreateScreen(controller: MediaController?, state: Studio.State, onHelp: () -> Unit = {}, onDriving: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var brief by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf(prefs.voices) }
    var length by remember { mutableStateOf(prefs.length) }
    val styles = listOf("Acid techno", "Trance", "Rave", "Melodic techno", "Hard techno", "Psytrance", "Acid house", "Hard trance", "Breakbeat", "Drum & bass", "Dark & melancholic", "Euphoric")
    var picked by remember { mutableStateOf(setOf<String>()) }
    val sampleList = remember(state.libraryVersion) { SampleStore(ctx).list() }
    var pickedSamples by remember { mutableStateOf(setOf<String>()) }
    var featured by remember { mutableStateOf<SampleMeta?>(null) }
    val handoff by SampleHandoff.pending.collectAsStateWithLifecycle()
    LaunchedEffect(handoff) {
        handoff?.let { m ->
            pickedSamples = pickedSamples + m.id
            featured = m
            if (brief.isBlank()) brief = starterBrief(m)
            SampleHandoff.pending.value = null
        }
    }

    fun go(text: String = brief) {
        val c = controller ?: return
        val chosen = sampleList.filter { it.id in pickedSamples }
        // A sample on its own is enough: describe the track from the sample when nothing is typed.
        val base = text.trim().ifEmpty { chosen.firstOrNull()?.let { starterBrief(it) } ?: "" }
        val full = listOf(base, if (picked.isNotEmpty()) "Style: ${picked.joinToString(", ")}." else "").filter { it.isNotEmpty() }.joinToString("\n")
        if (full.isEmpty()) return
        prefs.voices = voices; prefs.length = length
        requestCompose(c, full, voices, length, sampleList.filter { it.id in pickedSamples }.map { it.id })
    }
    val dictate = rememberDictation("Describe your song") { said ->
        brief = if (brief.isBlank()) said else "$brief $said"
        if (prefs.autoCompose) go(brief)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("What are we\nmaking today?", fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onHelp, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
                Text("?", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Box {
                OutlinedTextField(
                    value = brief, onValueChange = { brief = it },
                    placeholder = { Text("A melancholic acid trance track for a 4 a.m. warehouse, long breakdown, huge drop…") },
                    modifier = Modifier.fillMaxWidth().height(150.dp).padding(end = 64.dp),
                    shape = RoundedCornerShape(22.dp),
                )
                FilledIconButton(
                    onClick = dictate, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(54.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Icon(painterResource(R.drawable.ic_mic), "Speak your idea", tint = MaterialTheme.colorScheme.onPrimary) }
            }
        }

        Text("Vocals (optional)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceTile("Male", "Low, warm", voices.male, Modifier.weight(1f)) { voices = voices.copy(male = !voices.male) }
            VoiceTile("Female", "Bright, high", voices.female, Modifier.weight(1f)) { voices = voices.copy(female = !voices.female) }
            VoiceTile("Choir", "Harmonies", voices.choir, Modifier.weight(1f)) { voices = voices.copy(choir = !voices.choir) }
        }
        Text(if (voices.combo() == "none") "Instrumental, no vocals" else "${voices.describe()} vocals added", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

        featured?.let { m ->
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = .14f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Built around your sample", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(m.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        }
                        TextButton(onClick = { featured = null; pickedSamples = pickedSamples - m.id }) { Text("Remove") }
                    }
                    Text("Tap ideas to add them to your description:", fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(amplifyIdeas(m)) { (label, sentence) ->
                            val used = brief.contains(sentence)
                            FilterChip(selected = used, onClick = {
                                brief = if (used) brief.replace(sentence, "").replace("  ", " ").trim() else listOf(brief.trim(), sentence).filter { it.isNotEmpty() }.joinToString(" ")
                            }, label = { Text(label) })
                        }
                    }
                }
            }
        }

        Text("Style", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(styles) { s -> FilterChip(selected = s in picked, onClick = { picked = if (s in picked) picked - s else picked + s }, label = { Text(s) }) }
        }

        if (sampleList.isNotEmpty()) {
            Text("Use your samples", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sampleList, key = { it.id }) { m ->
                    FilterChip(selected = m.id in pickedSamples, onClick = {
                        pickedSamples = if (m.id in pickedSamples) pickedSamples - m.id else if (pickedSamples.size < 4) pickedSamples + m.id else pickedSamples
                    }, label = { Text(m.name) })
                }
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("short" to "Loop · 1 min", "full" to "Full track · 3 min").forEachIndexed { i, (v, label) ->
                SegmentedButton(selected = length == v, onClick = { length = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }

        Button(
            onClick = { go() }, enabled = controller != null && !state.busy,
            modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.background),
        ) {
            Icon(painterResource(R.drawable.ic_spark), null); Spacer(Modifier.width(10.dp))
            Text(if (state.busy) "Composing…" else "Compose & play", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(if (prefs.usesClaude) "Composing with Claude (your API key)." else "Composing with the free built-in composer. Add an API key in Settings to compose with Claude.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusCard(state)

        Card(onClick = onDriving, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(painterResource(R.drawable.ic_car), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text("Driving mode", fontWeight = FontWeight.Bold)
                    Text("One giant button. Or just say “Hey Google, play melancholic acid trance on Songsmith”.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun VoiceTile(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.secondary
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp),
        color = if (on) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, if (on) accent else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) accent else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(title.take(1), color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DrivingScreen(controller: MediaController?, state: Studio.State, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val listen = rememberDictation("What should I make?") { said -> controller?.let { ask(it, said) } }
    var note by remember { mutableStateOf("") }
    val (rec, toggleRec) = rememberSnippetRecorder(onDone = { pcm ->
        val st = SampleStore(ctx)
        val m = st.save(pcm, "Car snippet " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), if (pcm.size >= SAMPLE_RATE * 4) "loop" else "hit", "")
        note = "Saved “${m.name}”. Say “…with my samples” to use it."
        Studio.libraryChanged()
    }, onError = { note = it })
    Column(
        Modifier.fillMaxSize().background(Ink).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Driving mode", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onExit) { Text("Exit", color = Coral, fontSize = 18.sp) }
        }
        Box(
            Modifier.size(240.dp).clip(CircleShape).background(Coral).clickable(enabled = controller != null) { listen() },
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_mic), "Talk", tint = Ink, modifier = Modifier.size(96.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordButton(rec, 84, toggleRec)
            Text(if (rec.recording) "Recording… tap to stop" else "Record a snippet of what's playing", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (note.isNotEmpty()) Text(note, color = Color(0xFFFFB4B6), fontSize = 15.sp)
            Text(
                if (state.message.isNotEmpty()) state.message else "Tap and say what you want, like “hard acid techno at 145”, “acid trance with my samples” or “more energy”.",
                color = Color.White, fontSize = 20.sp, lineHeight = 26.sp,
            )
            Text("Default singers: ${prefs.voices.describe()}", color = Color(0xFF98A2B3))
        }
    }
}

@Composable
fun NowPlaying(controller: MediaController?) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    var dur by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        val c = controller
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                title = player.mediaMetadata.title?.toString() ?: ""
                artist = player.mediaMetadata.artist?.toString() ?: ""
                playing = player.isPlaying
            }
        }
        c?.addListener(l)
        title = c?.mediaMetadata?.title?.toString() ?: ""; artist = c?.mediaMetadata?.artist?.toString() ?: ""; playing = c?.isPlaying ?: false
        onDispose { c?.removeListener(l) }
    }
    LaunchedEffect(controller) {
        while (controller != null) {
            if (!dragging) {
                pos = controller.currentPosition.coerceAtLeast(0).toFloat()
                dur = controller.duration.takeIf { it > 0 }?.toFloat() ?: 0f
            }
            delay(500)
        }
    }
    if (controller == null || title.isEmpty()) return
    fun fmt(ms: Float): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artist.isNotEmpty()) Text(artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { controller.seekToPreviousMediaItem() }, enabled = controller.hasPreviousMediaItem()) { Text("⏮", fontSize = 20.sp) }
                FilledIconButton(onClick = { if (controller.isPlaying) controller.pause() else controller.play() }) {
                    Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), if (playing) "Pause" else "Play")
                }
                IconButton(onClick = { controller.seekToNextMediaItem() }, enabled = controller.hasNextMediaItem()) { Text("⏭", fontSize = 20.sp) }
            }
            if (dur > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(pos), fontSize = 11.sp)
                Slider(value = pos.coerceIn(0f, dur), valueRange = 0f..dur, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    onValueChange = { dragging = true; pos = it },
                    onValueChangeFinished = { controller.seekTo(pos.toLong()); dragging = false })
                Text(fmt(dur), fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun LibraryScreen(controller: MediaController?, state: Studio.State) {
    val ctx = LocalContext.current
    val lib = remember { Library(ctx) }
    var entries by remember { mutableStateOf(listOf<Entry>()) }
    var openId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.libraryVersion) { entries = lib.list() }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Your songs", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold) }
        if (entries.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { controller?.let { it.shuffleModeEnabled = false; playSong(it, entries.first().id) } }, enabled = controller != null) {
                    Icon(painterResource(R.drawable.ic_play), null); Spacer(Modifier.width(6.dp)); Text("Play all (${entries.size})")
                }
                OutlinedButton(onClick = { controller?.let { c -> playSong(c, entries.random().id); c.shuffleModeEnabled = true } }, enabled = controller != null) { Text("Shuffle") }
            }
        }
        item { StatusCard(state) }
        if (entries.isEmpty()) item {
            Text("No tracks yet. Compose one, or say “Hey Google, play hard acid techno on Songsmith”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(entries, key = { it.id }) { e ->
            SongCard(e, openId == e.id, controller, state.busy,
                onToggle = { openId = if (openId == e.id) null else e.id },
                onDelete = { lib.delete(e.id); entries = lib.list(); Studio.libraryChanged() })
        }
    }
}

@Composable
fun SongCard(e: Entry, expanded: Boolean, controller: MediaController?, busy: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMsg by remember { mutableStateOf("") }
    val blocked = remember(e.id, e.updated) { Exporter.uncleared(ctx, e.samples) }
    fun export(share: Boolean) {
        exportMsg = "Preparing a copyright-safe file…"
        scope.launch {
            exportMsg = runCatching {
                val res = Exporter.prepare(ctx, e.id)
                if (share) Exporter.share(ctx, res.file, e.title) else Exporter.saveToMusic(ctx, res.file, e.title)
                val left = if (res.removedSamples > 0) " Recorded snippets from other music were left out." else ""
                (if (share) "Ready to share." else "Saved to Music › Songsmith.") + left
            }.getOrElse { it.message ?: "Couldn't export that song." }
        }
    }
    var change by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }
    val dictate = rememberDictation("What should change?") { change = it }
    Card(onClick = onToggle, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondary), contentAlignment = Alignment.Center) {
                    Text(e.title.take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val mins = e.seconds.toInt()
                    Text(listOf(e.genre, "${e.bpm} BPM", VOCAL_LABEL[e.vocal] ?: "", "%d:%02d".format(mins / 60, mins % 60)).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(onClick = { controller?.let { playSong(it, e.id) } }, enabled = controller != null) {
                    Icon(painterResource(R.drawable.ic_play), "Play ${e.title}")
                }
            }
            if (blocked.isNotEmpty()) Text("Uses recorded snippets · downloads leave them out", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (Exporter.canSaveToMusic()) Button(onClick = { export(false) }) { Text("Download") }
                    OutlinedButton(onClick = { export(true) }) { Text("Share") }
                }
                if (exportMsg.isNotEmpty()) Text(exportMsg, fontSize = 13.sp)
                if (e.summary.isNotBlank()) Text(e.summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.lyrics.isNotBlank()) Text(e.lyrics, fontSize = 14.sp)
                if (e.lesson.isNotEmpty()) Text("Learning song: open the Learn tab for the sing-along and quiz.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.lesson.isEmpty()) Box {
                    OutlinedTextField(change, { change = it }, placeholder = { Text("Change it: more energy, darker breakdown…") },
                        modifier = Modifier.fillMaxWidth().padding(end = 56.dp), shape = RoundedCornerShape(14.dp))
                    FilledIconButton(onClick = dictate, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(painterResource(R.drawable.ic_mic), "Speak a change") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (e.lesson.isEmpty()) Button(onClick = { controller?.let { requestRefine(it, e.id, change.trim()); change = "" } }, enabled = change.isNotBlank() && !busy && controller != null) { Text("Apply change") }
                    OutlinedButton(onClick = { if (armed) onDelete() else armed = true }) { Text(if (armed) "Tap again to delete" else "Delete") }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onHelp: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var auto by remember { mutableStateOf(prefs.autoCompose) }
    var engine by remember { mutableStateOf(prefs.engine) }
    var test by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("How Songsmith works (flashcards)") }
        SuggestionBox()
        CreatorInbox()
        UpdateSection()
        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
        Text("Songsmith version $version", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Composer", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("claude" to "Claude", "builtin" to "Built-in (free)").forEachIndexed { i, (v, label) ->
                SegmentedButton(selected = engine == v, onClick = { engine = v; prefs.engine = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        Text(
            when {
                engine == "builtin" -> "Free and works offline. Writes techno, acid, trance, psytrance, rave and drum & bass from your words and samples. Simpler than Claude."
                key.isBlank() -> "Claude writes richer, more varied tracks. It needs an API key (optional, below). Until you add one, the free built-in composer is used."
                else -> "Claude writes richer, more varied tracks, billed to your API key."
            }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(key, { key = it; prefs.apiKey = it }, label = { Text("Anthropic API key (optional)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Text("Create one at console.anthropic.com. Each song is billed to that account. The key stays on this phone.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(model, { model = it; prefs.model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                test = "Testing…"
                scope.launch {
                    test = runCatching { ClaudeClient().complete(prefs.apiKey, prefs.model, "Reply with the single word OK.", maxTokens = 16); "Connected. You're ready to compose." }
                        .getOrElse { it.message ?: "That didn't work." }
                }
            }) { Text("Test connection") }
            Text(test, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Compose as soon as I stop talking", fontWeight = FontWeight.SemiBold)
                Text("Skips the Compose button after you dictate.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(auto, { auto = it; prefs.autoCompose = it })
        }
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Using it in the car", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("1. On your phone open Android Auto settings, tap Version ten times to unlock developer settings.\n" +
                    "2. In the ⋮ menu open Developer settings and turn on Unknown sources.\n" +
                    "3. In Android Auto's Customise launcher, make sure Songsmith is ticked.\n" +
                    "4. In the car say: “Hey Google, play melancholic acid trance on Songsmith.”\n" +
                    "5. Change it with: “Hey Google, play more energy on Songsmith.” Or tap a quick idea on the Songsmith screen.",
                    fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}
