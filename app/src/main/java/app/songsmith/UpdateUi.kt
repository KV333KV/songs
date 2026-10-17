package app.songsmith

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Update state shared by the banner and the Settings section. */
object Updates {
    data class State(val checking: Boolean = false, val latest: Updater.Release? = null, val message: String = "", val progress: Float? = null)
    val state = MutableStateFlow(State())
    private var checkedThisRun = false

    suspend fun check(ctx: android.content.Context, quiet: Boolean = false) {
        val prefs = Prefs(ctx)
        state.update { it.copy(checking = true, message = if (quiet) it.message else "Checking for updates…") }
        try {
            val rel = Updater.latest(prefs.updateToken)
            val current = Updater.currentVersion(ctx)
            state.update {
                it.copy(checking = false, latest = rel.takeIf { r -> r.version > current },
                    message = if (rel.version > current) "Version 1.${rel.version} is available." else "You're up to date (version 1.$current).")
            }
        } catch (e: Exception) {
            state.update { it.copy(checking = false, message = if (quiet) "" else (e.message ?: "Couldn't check for updates.")) }
        }
    }

    /** Called when the app opens: checks once per run if automatic checks are on. */
    suspend fun autoCheck(ctx: android.content.Context) {
        if (checkedThisRun || !Prefs(ctx).autoUpdateCheck) return
        checkedThisRun = true
        check(ctx, quiet = true)
    }

    suspend fun downloadAndInstall(ctx: android.content.Context) {
        val rel = state.value.latest ?: return
        try {
            state.update { it.copy(progress = 0f, message = "Downloading version 1.${rel.version}…") }
            val apk = Updater.download(Prefs(ctx).updateToken, rel, ctx) { p -> state.update { s -> s.copy(progress = p) } }
            state.update { it.copy(progress = null, message = "Tap Update on the next screen to finish.") }
            if (!Updater.install(ctx, apk)) state.update { it.copy(message = "Allow Songsmith to install updates, then come back and tap Update now again.") }
        } catch (e: Exception) {
            state.update { it.copy(progress = null, message = e.message ?: "The download failed.") }
        }
    }
}

@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updates.state.collectAsStateWithLifecycle()
    val rel = st.latest ?: return
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Songsmith 1.${rel.version} is ready", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { Updates.downloadAndInstall(ctx) } }, enabled = st.progress == null) {
                    Text("Update now", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.ExtraBold)
                }
            }
            st.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            if (st.message.isNotEmpty() && st.progress == null && !st.message.endsWith("available.")) Text(st.message, fontSize = 13.sp)
        }
    }
}

@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val st by Updates.state.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf(prefs.updateToken) }
    var auto by remember { mutableStateOf(prefs.autoUpdateCheck) }
    var showHelp by remember { mutableStateOf(prefs.updateToken.isBlank()) }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App updates", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("You have version 1.${Updater.currentVersion(ctx)}.", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { scope.launch { Updates.check(ctx) } }, enabled = !st.checking) { Text(if (st.checking) "Checking…" else "Check for updates") }
                if (st.latest != null) Button(onClick = { scope.launch { Updates.downloadAndInstall(ctx) } }, enabled = st.progress == null) { Text("Update now") }
            }
            st.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            if (st.message.isNotEmpty()) Text(st.message, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check automatically when the app opens", modifier = Modifier.weight(1f))
                Switch(auto, { auto = it; prefs.autoUpdateCheck = it })
            }
            OutlinedTextField(token, { token = it; prefs.updateToken = it }, label = { Text("Update token (GitHub)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide how to get a token" else "How to get a token") }
            if (showHelp) Text(
                "Your app lives in a private GitHub repo, so the app needs read-only permission to see new versions. One-time setup:\n" +
                    "1. On github.com open your profile picture → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token.\n" +
                    "2. Name it Songsmith updates and set an expiry (up to a year).\n" +
                    "3. Repository access: Only select repositories → songs.\n" +
                    "4. Permissions → Repository permissions → Contents: Read-only. Leave everything else as No access.\n" +
                    "5. Generate, copy the token and paste it above.",
                fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
