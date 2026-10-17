package app.songsmith

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The creator's view of suggestions: reads the private feedback repo with the owner's own key. */
object Inbox {
    class Item(val number: Int, val title: String, val body: String, val created: String, val url: String)

    private const val CHANNEL = "suggestions"
    private const val WORK = "suggestion-check"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("inbox", Context.MODE_PRIVATE)
    fun token(ctx: Context) = prefs(ctx).getString("token", "") ?: ""
    fun setToken(ctx: Context, v: String) = prefs(ctx).edit().putString("token", v.trim()).apply()
    fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)
    private fun lastSeen(ctx: Context) = prefs(ctx).getInt("lastSeen", 0)
    private fun setLastSeen(ctx: Context, n: Int) = prefs(ctx).edit().putInt("lastSeen", n).apply()

    suspend fun open(ctx: Context): List<Item> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/${Feedback.REPO}/issues?state=open&sort=created&direction=desc&per_page=30")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token(ctx)).build()
        http.newCall(req).execute().use { res ->
            when (res.code) {
                200 -> {}
                401 -> throw IllegalStateException("That key was rejected. Check it and try again.")
                403, 404 -> throw IllegalStateException("That key can't read the songsmith-feedback repo. Give it Issues access to that repo.")
                else -> throw IllegalStateException("GitHub returned an error (${res.code}).")
            }
            val a = JSONArray(res.body?.string() ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.has("pull_request") }.map {
                Item(it.getInt("number"), it.optString("title"), it.optString("body"), it.optString("created_at").take(10), it.optString("html_url"))
            }
        }
    }

    suspend fun markDone(ctx: Context, number: Int): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/${Feedback.REPO}/issues/$number")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer " + token(ctx))
            .patch(JSONObject().put("state", "closed").toString().toRequestBody("application/json".toMediaType())).build()
        runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    fun turnOn(ctx: Context) {
        prefs(ctx).edit().putBoolean("on", true).apply()
        val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun turnOff(ctx: Context) {
        prefs(ctx).edit().putBoolean("on", false).apply()
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    /** Checks for suggestions newer than the last one seen and notifies about them. */
    suspend fun checkAndNotify(ctx: Context, notify: Boolean = true): Int {
        if (token(ctx).isBlank()) return 0
        val items = open(ctx)
        val seen = lastSeen(ctx)
        val fresh = items.filter { it.number > seen }
        items.maxOfOrNull { it.number }?.let { if (it > seen) setLastSeen(ctx, it) }
        if (notify && seen > 0) fresh.take(5).forEach { show(ctx, it) }
        return fresh.size
    }

    private fun show(ctx: Context, item: Item) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Suggestions", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, item.number, Intent(ctx, MainActivity::class.java).putExtra("openInbox", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("New suggestion")
            .setContentText(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title + "\n\n" + item.body.substringBefore("\n---").take(400)))
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(1000 + item.number, n)
    }
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching { Inbox.checkAndNotify(applicationContext); Result.success() }.getOrElse { Result.retry() }
}

@Composable
fun CreatorInbox() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(Inbox.token(ctx)) }
    var on by remember { mutableStateOf(Inbox.isOn(ctx)) }
    var items by remember { mutableStateOf<List<Inbox.Item>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(Inbox.isOn(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun refresh() {
        scope.launch {
            status = "Loading…"
            status = runCatching { items = Inbox.open(ctx); Inbox.checkAndNotify(ctx, notify = false); if (items.isEmpty()) "No open suggestions." else "" }
                .getOrElse { it.message ?: "Couldn't load suggestions." }
        }
    }
    LaunchedEffect(Unit) { if (on && token.isNotBlank()) refresh() }

    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Creator inbox", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(if (on) "Notifications on · checks every 15 minutes" else "For the app's creator only", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Open") }
            }
            if (expanded) {
                OutlinedTextField(token, { token = it; Inbox.setToken(ctx, it) }, label = { Text("Inbox key (GitHub token)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("Use a fine-grained GitHub token with access to only songsmith-feedback and Issues: Read and write. The token you made for suggestions works. It stays on this phone.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!on) Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        scope.launch {
                            val ok = runCatching { Inbox.checkAndNotify(ctx, notify = false); true }.getOrElse { status = it.message ?: "That key didn't work."; false }
                            if (ok) { Inbox.turnOn(ctx); on = true; refresh() }
                        }
                    }, enabled = token.isNotBlank()) { Text("Turn on notifications") }
                    else OutlinedButton(onClick = { Inbox.turnOff(ctx); on = false; status = "Notifications off." }) { Text("Turn off") }
                    OutlinedButton(onClick = { refresh() }, enabled = token.isNotBlank()) { Text("Refresh") }
                }
                if (status.isNotEmpty()) Text(status, fontSize = 14.sp)
                items.forEach { item ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.body.substringBefore("\n---").take(300), fontSize = 14.sp)
                            Text(item.body.substringAfter("---", "").trim().lineSequence().joinToString(" · ") + " · #${item.number}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { scope.launch { if (Inbox.markDone(ctx, item.number)) items = items - item else status = "Couldn't close it. The key needs Issues: Read and write." } }) { Text("Mark done") }
                        }
                    }
                }
            }
        }
    }
}
