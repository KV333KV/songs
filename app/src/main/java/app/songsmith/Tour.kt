package app.songsmith

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private class Flashcard(val icon: Int, val color: Color, val front: String, val teaser: String, val back: List<String>)

private val CARDS = listOf(
    Flashcard(R.drawable.ic_spark, Color(0xFFFF6B70), "Welcome to Songsmith", "Your pocket music studio",
        listOf("Describe a track and Songsmith composes, records and plays it.",
            "Record snippets you love and build tracks around them.",
            "Turn schoolwork into learning songs with a sing-along and quiz.",
            "Works in the car with Android Auto, hands-free.")),
    Flashcard(R.drawable.ic_spark, Color(0xFFFF6B70), "Make a track", "Create tab",
        listOf("Type or tap the mic and describe the track: mood, tempo, sounds.",
            "Pick styles like Acid techno, Trance or Rave.",
            "Vocals are optional: Male, Female, Choir or none.",
            "Choose Loop (1 min) or Full track (3 min), then tap Compose & play.")),
    Flashcard(R.drawable.ic_settings, Color(0xFFF2B341), "Real audio or sketch?", "Settings → Sound",
        listOf("Real audio: Google Lyria records a finished, mastered track (about US$0.08 each). Needs a Google Gemini key.",
            "With a Claude key too, Claude acts as the producer and writes Lyria a detailed brief.",
            "Sketch: free and offline, using the in-app instruments. Great for quick ideas.",
            "Learning songs in real-audio mode are sung by real-sounding singers.")),
    Flashcard(R.drawable.ic_library, Color(0xFF7CB7E6), "Change a track", "Library tab",
        listOf("Open a track in the Library and type or say what to change.",
            "Try: more energy, darker breakdown, faster, new melody, add rave stabs.",
            "Tap Apply change. The new version replaces the old one.")),
    Flashcard(R.drawable.ic_wave, Color(0xFF5ED3AC), "Record a snippet", "Samples tab, step 1 and 2",
        listOf("Tap the big red button near a speaker playing something you love (max 30 s).",
            "Or import an audio file from your phone.",
            "Drag the handles to keep just the best part, then Preview.",
            "Pick Loop, Hit / chop or Texture, and Save.")),
    Flashcard(R.drawable.ic_wave, Color(0xFF5ED3AC), "Build a track around it", "Samples tab, step 3",
        listOf("After saving, tap Make a track with this sample.",
            "Create opens with the sample ticked and a description started.",
            "Tap ideas like Rolling acid bass or Stutter before the drop to amplify it.",
            "Tap Compose & play.")),
    Flashcard(R.drawable.ic_learn, Color(0xFFFFC53D), "Learning songs", "Learn tab",
        listOf("Talk, type, or take photos of the work (several pages are fine).",
            "Choose who's learning: 4–6, 7–9, 10–13, 14–18 or Adult.",
            "Pick a style: Clap-along, Chant, Dance or Calm.",
            "Tap Make my learning song. Key facts go into a catchy chorus that repeats.")),
    Flashcard(R.drawable.ic_learn, Color(0xFFFFC53D), "Sing along & quiz", "Learn tab",
        listOf("Sing along lights up each line as it's spoken, so learners read while they listen.",
            "Everyone: lines are the answers to shout back.",
            "Quiz me asks questions, explains answers and gives stars.",
            "Follow the study tip: replay tonight, tomorrow and in three days.")),
    Flashcard(R.drawable.ic_play, Color(0xFFAF9DF2), "Your library", "Library tab",
        listOf("Play all or Shuffle everything you've made.",
            "Skip, go back and seek with the player bar at the bottom.",
            "Download saves a track to Music › Songsmith. Share sends it to anyone.",
            "Downloads are copyright-safe: snippets from other people's music are left out.")),
    Flashcard(R.drawable.ic_car, Color(0xFFF27AB8), "In the car", "Android Auto",
        listOf("Say: “Hey Google, play melancholic acid trance on Songsmith.”",
            "Change it: “…play more energy on Songsmith.”",
            "Learning: “…play learn the planets in order on Songsmith.”",
            "Or tap a quick idea on the Songsmith car screen.")),
    Flashcard(R.drawable.ic_car, Color(0xFFF27AB8), "Set up Android Auto", "One time",
        listOf("Android Auto settings → tap Version ten times to unlock developer settings.",
            "⋮ menu → Developer settings → turn on Unknown sources.",
            "Customise launcher → tick Songsmith.",
            "On the phone, Driving mode gives you one giant talk button.")),
    Flashcard(R.drawable.ic_settings, Color(0xFF98A2B3), "Updates & sharing", "Settings",
        listOf("When a new version is out, a red bar appears: tap Update now, then Update.",
            "Or check any time: Settings → App updates.",
            "To share the app, send Songsmith.apk. Friends use their own API key, or the free composer.",
            "Reopen these cards any time with the ? button.")),
    Flashcard(R.drawable.ic_spark, Color(0xFF3CCB7F), "Help make it better", "Settings → Suggestions",
        listOf("Got an idea or found a problem? Open Settings and scroll to “How can we make it better?”.",
            "Pick Idea, Problem, Music, Learning or Other and write what you'd like.",
            "Tap Send suggestion. It goes privately to the app's creator.",
            "No internet? It's saved and sent automatically later.")),
)

/** Swipeable flip cards that explain the app. Tap a card to flip it. */
@Composable
fun HowItWorks(onClose: () -> Unit) {
    val pager = rememberPagerState { CARDS.size }
    val flipped = remember { mutableStateMapOf<Int, Boolean>() }
    val scope = rememberCoroutineScope()
    // Surface sets the right text colour for light and dark mode.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("How Songsmith works", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text("Tap a card to flip it. Swipe for the next one.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        HorizontalPager(pager, Modifier.weight(1f), pageSpacing = 16.dp) { i ->
            val card = CARDS[i]
            val isFlipped = flipped[i] == true
            val angle by animateFloatAsState(if (isFlipped) 180f else 0f, tween(450), label = "flip")
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { rotationY = angle; cameraDistance = 14 * density }
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (angle < 90f) card.color else MaterialTheme.colorScheme.surface)
                    .clickable { flipped[i] = !isFlipped },
            ) {
                if (angle < 90f) {
                    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(92.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(card.icon), null, tint = Color(0xFF14181F), modifier = Modifier.size(48.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(card.front, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF14181F), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(card.teaser, fontSize = 16.sp, color = Color(0xCC14181F), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(28.dp))
                        Text("Tap to flip", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0x9914181F))
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.verticalScroll(rememberScrollState()).padding(26.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(card.front, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                        card.back.forEachIndexed { n, line ->
                            Row {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(card.color), contentAlignment = Alignment.Center) {
                                    Text("${n + 1}", fontWeight = FontWeight.Bold, color = Color(0xFF14181F), fontSize = 13.sp)
                                }
                                Spacer(Modifier.size(12.dp))
                                Text(line, fontSize = 17.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(CARDS.size) { i ->
                Box(Modifier.padding(3.dp).size(if (i == pager.currentPage) 10.dp else 7.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }, enabled = pager.currentPage > 0) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (pager.currentPage < CARDS.size - 1) Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text("Next") }
            else Button(onClick = onClose) { Text("Start making music") }
        }
    }
    }
}
