package com.david.gitly.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Respuesta de inicio del flujo de dispositivo: el código que el usuario introduce en GitHub. */
@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

@Serializable
internal class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
    val interval: Int? = null,
)

sealed interface DevicePoll {
    data class Token(val token: String) : DevicePoll
    /** Aún sin autorizar; [interval] es el nuevo intervalo si GitHub pide ir más despacio. */
    data class Pending(val interval: Int? = null) : DevicePoll
    data class Failed(val reason: String) : DevicePoll
}

/**
 * Inicio de sesión con el "device flow" de OAuth de GitHub: sin contraseñas ni tokens a mano.
 * Necesita el client_id de una OAuth App con "Enable Device Flow" activado (no lleva secreto).
 */
class DeviceAuth(private val clientId: String, private val http: OkHttpClient = GitHub.defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start(scopes: String): DeviceCode =
        json.decodeFromString(post("https://github.com/login/device/code", mapOf("client_id" to clientId, "scope" to scopes)))

    suspend fun poll(code: DeviceCode): DevicePoll {
        val r: TokenResponse = json.decodeFromString(
            post(
                "https://github.com/login/oauth/access_token",
                mapOf(
                    "client_id" to clientId,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
        )
        return when {
            r.accessToken != null -> DevicePoll.Token(r.accessToken)
            r.error == "authorization_pending" -> DevicePoll.Pending()
            r.error == "slow_down" -> DevicePoll.Pending(r.interval ?: (code.interval + 5))
            else -> DevicePoll.Failed(r.error ?: "unknown")
        }
    }

    private suspend fun post(url: String, form: Map<String, String>): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json").post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GitHubException(r.code, "GitHub ${r.code}: ${text.take(120)}")
            text
        }
    }
}


/**
 * Inicio de sesión "web" de OAuth: la propia página de login de GitHub (usuario, contraseña, 2FA)
 * abierta dentro de la app; GitHub devuelve un código a [redirectUri] y aquí se canjea por un token.
 * Con PKCE el código no sirve a nadie que no tenga el verificador.
 */
class WebAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val http: OkHttpClient = GitHub.defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class WebToken(
        @SerialName("access_token") val accessToken: String? = null,
        val error: String? = null,
        @SerialName("error_description") val description: String? = null,
    )

    class Pending(val state: String, val verifier: String)

    fun newPending(): Pending = Pending(random(24), random(48))

    fun authorizeUrl(p: Pending, redirectUri: String, scopes: String): String =
        "https://github.com/login/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scopes)
            .addQueryParameter("state", p.state)
            .addQueryParameter("code_challenge", challenge(p.verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("allow_signup", "true")
            .build().toString()

    suspend fun exchange(code: String, p: Pending, redirectUri: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .apply { if (clientSecret.isNotBlank()) add("client_secret", clientSecret) }
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", p.verifier)
            .build()
        val req = Request.Builder().url("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json").post(form).build()
        val text = http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GitHubException(r.code, "GitHub ${r.code}: ${t.take(120)}")
            t
        }
        val tok = json.decodeFromString(WebToken.serializer(), text)
        tok.accessToken ?: throw GitHubException(400, tok.description ?: tok.error ?: "sin token")
    }

    private fun random(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
}
