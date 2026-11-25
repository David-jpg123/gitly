package com.david.gitly.core

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Reintenta las peticiones GET que se cuelgan o fallan por red (y 429/5xx), con una espera corta.
 * En redes que pierden paquetes una conexión puede quedarse muda: con tiempos de espera cortos +
 * reintento sobre una conexión nueva, la imagen llega en segundos en vez de nunca.
 */
class RetryInterceptor(private val attempts: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.method != "GET") return chain.proceed(req)
        var last: IOException? = null
        for (i in 0 until attempts) {
            if (i > 0) Thread.sleep(300L * i)
            try {
                val res = chain.proceed(req)
                if ((res.code == 429 || res.code in 500..599) && i < attempts - 1) { res.close(); continue }
                return res
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                last = e
            }
        }
        throw last ?: IOException("sin respuesta")
    }
}
