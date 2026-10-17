package app.songsmith

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Checks the GitHub releases of the songs repo, downloads a newer APK and hands it to Android's installer. */
object Updater {
    const val REPO = "KV333KV/songs"

    class Release(val version: Int, val name: String, val notes: String, val assetUrl: String, val size: Long)

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    @Suppress("DEPRECATION")
    fun currentVersion(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    private fun Request.Builder.auth(token: String) = apply {
        header("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
    }

    suspend fun latest(token: String): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").auth(token).build()
        http.newCall(req).execute().use { res ->
            when (res.code) {
                200 -> {}
                401 -> throw IllegalStateException("The update token was rejected. Check it in Settings.")
                404 -> throw IllegalStateException(
                    if (token.isBlank()) "Can't see updates yet: the app's GitHub repo is private. Add an update token below."
                    else "No releases found, or the token can't read the songs repo."
                )
                else -> throw IllegalStateException("GitHub returned an error (${res.code}). Try again later.")
            }
            val o = JSONObject(res.body?.string() ?: "{}")
            val tag = o.optString("tag_name")
            val version = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                ?: throw IllegalStateException("The latest release has no app file yet. Try again in a few minutes.")
            Release(version, o.optString("name", tag), o.optString("body", "").lineSequence().firstOrNull() ?: "", apk.getString("url"), apk.optLong("size"))
        }
    }

    suspend fun download(token: String, rel: Release, ctx: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "Songsmith-${rel.version}.apk")
        val req = Request.Builder().url(rel.assetUrl).header("Accept", "application/octet-stream").auth(token).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Download failed (${res.code}).")
            val body = res.body ?: throw IllegalStateException("Download failed.")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            body.byteStream().use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        os.write(buf, 0, n); done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        out
    }

    /** Opens Android's installer. Returns false if the user first has to allow installs from Songsmith. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
