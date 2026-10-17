package app.songsmith

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Suggestions go to a private GitHub repo that only the app's owner can see, as issues.
 * GitHub then notifies only the owner (push via the GitHub app, and email).
 * The key is injected at build time from an encrypted repository secret; it can only create issues there.
 */
object Feedback {
    const val REPO = "KV333KV/songsmith-feedback"
    val CATEGORIES = listOf("Idea", "Problem", "Music", "Learning", "Other")
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun enabled() = BuildConfig.FEEDBACK_TOKEN.isNotBlank()

    private fun queueFile(ctx: Context) = File(ctx.filesDir, "feedback-queue.json")

    private fun queue(ctx: Context): JSONArray = runCatching { JSONArray(queueFile(ctx).readText()) }.getOrElse { JSONArray() }

    private fun issueFor(ctx: Context, category: String, text: String, name: String): JSONObject {
        val first = text.lineSequence().first().take(60)
        val body = buildString {
            append(text.trim()).append("\n\n---\n")
            if (name.isNotBlank()) append("From: ").append(name.trim()).append("\n")
            append("Songsmith 1.").append(Updater.currentVersion(ctx))
            append(" · Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
            append("Sent ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        }
        return JSONObject().put("title", "[$category] $first").put("body", body)
    }

    private suspend fun post(issue: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/issues")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + BuildConfig.FEEDBACK_TOKEN)
            .post(issue.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Sends now if possible; otherwise keeps it on the phone and tries again next time. */
    suspend fun send(ctx: Context, category: String, text: String, name: String): String {
        val issue = issueFor(ctx, category, text.take(3000), name.take(60))
        if (enabled() && post(issue)) { flush(ctx); return "Thanks! Your suggestion was sent." }
        val q = queue(ctx).put(issue)
        queueFile(ctx).writeText(q.toString())
        return if (enabled()) "Saved. It'll be sent automatically when you're back online."
        else "Saved on this phone. It'll be sent once suggestions are switched on in a future update."
    }

    /** Sends anything that was saved while offline. */
    suspend fun flush(ctx: Context) {
        if (!enabled()) return
        val q = queue(ctx)
        if (q.length() == 0) return
        val left = JSONArray()
        for (i in 0 until q.length()) { val it = q.getJSONObject(i); if (!post(it)) left.put(it) }
        queueFile(ctx).writeText(left.toString())
    }
}

@Composable
fun SuggestionBox() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("Idea") }
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How can we make it better?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Share an idea or tell us what went wrong. It goes privately to the app's creator.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Feedback.CATEGORIES) { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
            }
            OutlinedTextField(text, { text = it; status = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                label = { Text("Your suggestion") }, placeholder = { Text("I'd love a button that…") })
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your name (optional)") })
            Button(onClick = {
                sending = true
                scope.launch { status = Feedback.send(ctx, category, text, name); sending = false; if (status.startsWith("Thanks") || status.startsWith("Saved")) text = "" }
            }, enabled = text.isNotBlank() && !sending) { Text(if (sending) "Sending…" else "Send suggestion") }
            if (status.isNotEmpty()) Text(status, fontSize = 14.sp)
        }
    }
}
