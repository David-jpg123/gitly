package com.david.gitly

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.david.gitly.core.GhAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Descarga un APK de una release y lanza el instalador del sistema. */
object ApkInstaller {

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun requestPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    /** Cliente sin token: los assets públicos no lo necesitan y así no viaja a terceros (S3). */
    suspend fun download(ctx: Context, client: OkHttpClient, asset: GhAsset, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "apks").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            val out = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val part = File(dir, out.name + ".part")
            client.newCall(Request.Builder().url(asset.url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Descarga fallida (${r.code})")
                val body = r.body ?: throw IOException("Respuesta vacía")
                val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                var read = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(out)) throw IOException("No se pudo guardar el APK")
            out
        }

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Ordena los APKs de una release: primero los que encajan con la ABI del dispositivo o son universales. */
    fun rank(assets: List<GhAsset>): List<GhAsset> {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase()
        fun score(a: GhAsset): Int {
            val n = a.name.lowercase()
            return when {
                abi.isNotEmpty() && (n.contains(abi) || (abi == "arm64-v8a" && n.contains("arm64"))) -> 0
                n.contains("universal") -> 1
                listOf("arm64", "armeabi", "x86", "x86_64", "v7a").none { n.contains(it) } -> 2
                else -> 3
            }
        }
        return assets.filter { it.isApk }.sortedWith(compareBy<GhAsset>({ score(it) }, { -it.downloads }))
    }
}
