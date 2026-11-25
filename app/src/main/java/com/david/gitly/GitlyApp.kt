package com.david.gitly

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import com.david.gitly.chat.Messenger
import com.david.gitly.core.GitHub
import com.david.gitly.push.Notifications
import com.david.gitly.push.SyncWorker
import java.util.concurrent.ConcurrentHashMap

class GitlyApp : Application(), ImageLoaderFactory {
    /** Cargador de imágenes con soporte de GIF animado y SVG (habituales en los README). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ImageDecoderDecoder.Factory())
            add(SvgDecoder.Factory())
        }
        // Casi todas las imágenes vienen del mismo host (raw.githubusercontent.com) y OkHttp solo
        // abre 5 peticiones a la vez por host: con la precarga, las visibles se quedaban en cola.
        .okHttpClient {
            okhttp3.OkHttpClient.Builder()
                .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 16 })
                // HTTP/1.1 a propósito: con HTTP/2 todas las imágenes de un host van por UNA conexión, y si
                // esa se queda muda (pérdida de paquetes) se atascan todas a la vez. Así cada una va por libre.
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor(com.david.gitly.core.RetryInterceptor())
                .build()
        }
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            coil.disk.DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(512L * 1024 * 1024).build()
        }
        // raw.githubusercontent.com manda max-age=300: respetarlo obligaba a volver a pedir cada imagen
        // a los 5 minutos. Una imagen de un README casi nunca cambia: se usa la del disco.
        .respectCacheHeaders(false)
        .crossfade(150)
        .build()

    /**
     * Un solo Messenger por cuenta en todo el proceso: la UI, el worker y la respuesta desde la
     * notificación comparten el mismo almacén de chats (dos instancias se pisarían el fichero).
     */
    private val messengers = ConcurrentHashMap<String, Messenger>()

    fun messenger(login: String, token: String?): Messenger {
        val key = "${login.lowercase()}|${token.hashCode()}"
        return messengers.getOrPut(key) {
            // un token nuevo para la misma cuenta sustituye al anterior
            messengers.keys.filter { it.startsWith("${login.lowercase()}|") }.forEach { messengers.remove(it) }
            Messenger(this, login, GitHub(token))
        }
    }

    @Volatile var inForeground = false; private set
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        if (Store(this).load().accounts.isNotEmpty()) SyncWorker.schedulePeriodic(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; inForeground = started > 0 }
            override fun onActivityStopped(a: Activity) { started--; inForeground = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
