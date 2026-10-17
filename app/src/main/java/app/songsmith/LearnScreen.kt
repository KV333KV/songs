@file:OptIn(ExperimentalMaterial3Api::class)

package app.songsmith

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

private val Sun = Color(0xFFFFC53D)
private val Sky = Color(0xFF3DA5FF)
private val Leaf = Color(0xFF3CCB7F)
private val Berry = Color(0xFFFF5C8A)

fun requestLearn(c: MediaController, text: String, age: String, style: String, photos: List<File>) {
    val extras = Bundle().apply {
        putString(PlaybackService.EXTRA_TEXT, text)
        putString(PlaybackService.EXTRA_AGE, age)
        putString(PlaybackService.EXTRA_STYLE, style)
        putString(PlaybackService.EXTRA_PHOTO, photos.joinToString("|") { it.path })
    }
    c.setMediaItem(MediaItem.Builder().setMediaId("learn").setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build()).build())
    c.prepare(); c.play()
}

@Composable
fun LearnScreen(controller: MediaController?, state: Studio.State) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val lib = remember { Library(ctx) }
    var text by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("7-9") }
    var style by remember { mutableStateOf("clap") }
    // Photo paths survive the camera screen (the app can be recreated while it's open).
    var photoPaths by rememberSaveable { mutableStateOf("") }
    var pendingShot by rememberSaveable { mutableStateOf("") }
    val photos = photoPaths.split("|").filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() }
    val scope = rememberCoroutineScope()
    var openLesson by remember { mutableStateOf<Pair<String, String>?>(null) } // id to "sing" | "quiz"
    val lessons = remember(state.libraryVersion) { lib.list().filter { it.lesson.isNotEmpty() } }
    val photoDir = remember { File(ctx.cacheDir, "photos").apply { mkdirs() } }
    fun addPhotos(fs: List<File>) { photoPaths = (photos + fs).distinct().take(10).joinToString("|") { it.path } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = File(pendingShot)
        if (ok && f.exists() && f.length() > 0) addPhotos(listOf(f))
        pendingShot = ""
    }
    fun shoot() {
        val f = File(photoDir, "p${System.currentTimeMillis()}.jpg")
        pendingShot = f.path
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        camera.launch(uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, u ->
                    runCatching {
                        val f = File(photoDir, "g${System.currentTimeMillis()}_$i.jpg")
                        ctx.contentResolver.openInputStream(u)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                        f
                    }.getOrNull()
                }
            }
            addPhotos(copied)
        }
    }
    val talk = rememberDictation("Say or read what you need to learn") { said -> text = if (text.isBlank()) said else "$text $said" }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Learn with music", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text("Say it, type it or photograph the work, as many pages as you like. Songsmith turns it into one song that helps it stick.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTile("Talk", "Say or read it", Sun, R.drawable.ic_mic, Modifier.weight(1f), talk)
            BigTile("Take photos", "Snap each page", Sky, R.drawable.ic_camera, Modifier.weight(1f)) { shoot() }
            BigTile("Choose photos", "From the gallery", Berry, R.drawable.ic_gallery, Modifier.weight(1f)) {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        if (photos.isNotEmpty()) {
            Text("${photos.size} photo${if (photos.size > 1) "s" else ""} of the work", fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(photos, key = { it.path }) { f ->
                    Box {
                        val thumb = remember(f.path) {
                            runCatching {
                                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (thumb != null) Image(thumb, "Photo of the work", Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        else Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(Color(0xCC000000))
                            .clickable { photoPaths = photos.filter { it != f }.joinToString("|") { it.path } }, contentAlignment = Alignment.Center) {
                            Text("✕", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { shoot() }, contentAlignment = Alignment.Center) {
                        Text("+ page", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(if (prefs.usesClaude) "Claude reads every page, including handwriting and diagrams, and makes one song from all of them."
                else "Free mode reads printed text on the phone. For handwriting and diagrams, add a Claude key in Settings.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp), shape = RoundedCornerShape(18.dp),
            label = { Text("What do you need to learn?") },
            placeholder = { Text("The planets in order from the Sun: Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus, Neptune.") })

        Text("How old is the learner?", fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AGE_BANDS.forEachIndexed { i, a ->
                SegmentedButton(selected = age == a, onClick = { age = a }, shape = SegmentedButtonDefaults.itemShape(i, AGE_BANDS.size)) { Text(a.replace("-", "–")) }
            }
        }
        Text("Song style", fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(KID_STYLES.entries.toList()) { (k, label) -> FilterChip(selected = style == k, onClick = { style = k }, label = { Text(label) }) }
        }
        Button(
            onClick = { controller?.let { requestLearn(it, text.trim(), age, style, photos) } },
            enabled = controller != null && !state.busy && (text.isNotBlank() || photos.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().height(62.dp), shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Color(0xFF06210F)),
        ) { Text(if (state.busy) "Making your song…" else "Make my learning song", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold) }
        Text(if (prefs.usesClaude) "A Claude teacher picks the key facts, writes a rhyming chorus, call-and-answer lines and a quiz."
            else "Free mode turns your own sentences into a chant with repeat-after-me lines and a quiz. Add an API key for a Claude-written song.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusCard(state)

        Text("Learning songs", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (lessons.isEmpty()) Text("Your learning songs will show up here with a sing-along and a quiz.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        lessons.forEach { e ->
            val lesson = remember(e.id, e.updated) { runCatching { Lesson.parse(JSONObject(e.lesson)) }.getOrNull() }
            if (lesson != null) {
                LessonCard(e, lesson, controller, openLesson?.first == e.id, openLesson?.second ?: "") { mode -> openLesson = if (openLesson == Pair(e.id, mode)) null else Pair(e.id, mode) }
            }
        }
    }
}

@Composable
private fun BigTile(title: String, sub: String, color: Color, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp), color = color.copy(alpha = .18f), border = BorderStroke(2.dp, color)) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(50.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, tint = Color(0xFF14181F), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, textAlign = TextAlign.Center)
            Text(sub, fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LessonCard(e: Entry, lesson: Lesson, controller: MediaController?, expanded: Boolean, mode: String, onMode: (String) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Sun), contentAlignment = Alignment.Center) {
                    Text("★", fontSize = 24.sp, color = Color(0xFF14181F))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(lesson.title, fontWeight = FontWeight.Bold)
                    Text("Ages ${lesson.age.replace("-", "–")} · ${KID_STYLES[lesson.style] ?: ""} · ${lesson.keyFacts.size} key facts", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(onClick = { controller?.let { playSong(it, e.id) } }, enabled = controller != null) { Icon(painterResource(R.drawable.ic_play), "Play") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { controller?.let { playSong(it, e.id) }; onMode("sing") }) { Text("Sing along") }
                OutlinedButton(onClick = { onMode("quiz") }, enabled = lesson.quiz.isNotEmpty()) { Text("Quiz me") }
            }
            if (expanded && mode == "sing") SingAlong(e.id, lesson, controller)
            if (expanded && mode == "quiz") Quiz(lesson) { controller?.let { playSong(it, e.id) }; onMode("sing") }
            if (expanded && lesson.parentTip.isNotBlank()) Text("For grown-ups: ${lesson.parentTip}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Karaoke view: the line being spoken lights up, so children can read along. */
@Composable
private fun SingAlong(id: String, lesson: Lesson, controller: MediaController?) {
    var pos by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(controller, id) {
        while (true) {
            pos = controller?.takeIf { it.currentMediaItem?.mediaId == "song:$id" }?.currentPosition?.div(1000f) ?: -1f
            delay(200)
        }
    }
    val current = lesson.timeline.indexOfLast { it.t <= pos + .1 }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pos < 0) Text("Press play to start the sing-along.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val from = (current - 1).coerceAtLeast(0)
            lesson.timeline.drop(from).take(5).forEachIndexed { i, l ->
                val idx = from + i
                val now = idx == current && pos >= 0
                val who = when (l.who) { "kids" -> "Everyone: "; "all" -> "♪ "; else -> "" }
                Text(who + l.text,
                    fontSize = if (now) 24.sp else 17.sp, lineHeight = if (now) 30.sp else 22.sp,
                    fontWeight = if (now) FontWeight.ExtraBold else FontWeight.Normal,
                    color = when { now && l.who == "kids" -> Berry; now -> MaterialTheme.colorScheme.primary; idx < current -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface })
            }
        }
    }
}

@Composable
private fun Quiz(lesson: Lesson, onReplay: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    var picked by remember { mutableIntStateOf(-1) }
    var score by remember { mutableIntStateOf(0) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (i >= lesson.quiz.size) {
                val stars = "★".repeat(score) + "☆".repeat(lesson.quiz.size - score)
                Text(stars, fontSize = 30.sp, color = Sun)
                Text(if (score == lesson.quiz.size) "Perfect! You know it!" else "You got $score of ${lesson.quiz.size}. Sing it once more and try again!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onReplay) { Text("Sing it again") }
                    OutlinedButton(onClick = { i = 0; picked = -1; score = 0 }) { Text("Try the quiz again") }
                }
            } else {
                val q = lesson.quiz[i]
                Text("Question ${i + 1} of ${lesson.quiz.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q.q, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
                q.options.forEachIndexed { k, opt ->
                    val bg = when {
                        picked < 0 -> MaterialTheme.colorScheme.surface
                        k == q.answer -> Leaf.copy(alpha = .3f)
                        k == picked -> Berry.copy(alpha = .3f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = bg, modifier = Modifier.fillMaxWidth().clickable(enabled = picked < 0) {
                        picked = k; if (k == q.answer) score++
                    }) { Text(opt, Modifier.padding(14.dp), fontSize = 17.sp) }
                }
                if (picked >= 0) {
                    Text((if (picked == q.answer) "Yes! " else "Nearly! ") + q.explain, fontSize = 15.sp)
                    Button(onClick = { i++; picked = -1 }) { Text(if (i + 1 < lesson.quiz.size) "Next question" else "See my stars") }
                }
            }
        }
    }
}
