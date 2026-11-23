package com.osakaresearch.spark.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Sign in with ChatGPT's open-source, locally running client flow. */
object ChatGptPlanAuth {
    private const val AUTH_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
    private const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
    private const val JWKS_ENDPOINT = "https://auth.openai.com/.well-known/jwks.json"
    private const val ISSUER = "https://auth.openai.com"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L
    private val exchangeLock = Mutex()
    private const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun signIn(context: Context, store: ChatGptPlanStore): ChatGptPlanStore.Credentials =
        withContext(Dispatchers.IO) {
            val server = ChatGptCallbackServer()
            try {
                val savedClientId = store.clientId()
                val clientId = savedClientId.ifBlank { "dynamic_agent_client" }
                val redirectUri = server.redirectUri
                val state = randomUrlSafe(32)
                val nonce = randomUrlSafe(32)
                val verifier = randomUrlSafe(64)
                val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
                )
                val authUri = Uri.parse(AUTH_ENDPOINT).buildUpon()
                    .appendQueryParameter("client_id", clientId)
                    .apply {
                        if (savedClientId.isBlank()) {
                            appendQueryParameter("agent_name_hint", "Spark MCU")
                        }
                        appendQueryParameter("ext_agent_host_id", store.hostId())
                        if (savedClientId.isNotBlank()) {
                            store.idToken().takeIf(String::isNotBlank)?.let {
                                appendQueryParameter("id_token_hint", it)
                            }
                        }
                    }
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", redirectUri)
                    .appendQueryParameter(
                        "scope",
                        "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                    )
                    .appendQueryParameter("resource", RESOURCE)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge)
                    .build()

                if (!store.savePendingSignIn(ChatGptPlanStore.PendingSignIn(
                    state, nonce, verifier, redirectUri, savedClientId,
                    System.currentTimeMillis() + SIGN_IN_TIMEOUT_MS,
                ))) error("Could not securely save the pending ChatGPT sign-in.")

                withContext(Dispatchers.Main) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, authUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                val coroutineContext = currentCoroutineContext()
                val callbackUrl = server.await(state, SIGN_IN_TIMEOUT_MS) { coroutineContext.ensureActive() }
                completeSignIn(store, callbackUrl)
            } finally {
                runCatching { server.close() }
            }
        }

    suspend fun completeSignIn(store: ChatGptPlanStore, callbackUrl: String): ChatGptPlanStore.Credentials =
        withContext(Dispatchers.IO) {
            exchangeLock.withLock {
                val pending = store.pendingSignIn()
                    ?: error("This sign-in expired. Tap Continue with ChatGPT to start again.")
                val callback = parseCallback(callbackUrl, pending.redirectUri, pending.state)
                callback.error?.let {
                    store.clearPendingSignIn()
                    error("ChatGPT sign-in was not completed: $it")
                }
                val code = callback.code?.takeIf(String::isNotBlank)
                    ?: error("ChatGPT did not return an authorization code.")
                val issuedClientId = callback.clientId?.takeIf(String::isNotBlank)
                    ?: pending.clientId.takeIf(String::isNotBlank)
                    ?: error("ChatGPT did not return the Spark registration ID.")
                if (issuedClientId == "dynamic_agent_client" ||
                    (pending.clientId.isNotBlank() && issuedClientId != pending.clientId)) {
                    error("ChatGPT returned a different Spark registration. Start sign-in again.")
                }
                if (!store.consumePendingSignIn(issuedClientId)) {
                    error("Could not securely save the Spark registration. Start sign-in again.")
                }
                val token = exchangeCode(
                    code = code,
                    clientId = issuedClientId,
                    redirectUri = pending.redirectUri,
                    verifier = pending.verifier,
                )
                val scopeString = token.optString("scope")
                val scopes = scopeString.split(' ').filter(String::isNotBlank).toSet()
                if (DIRECT_SCOPE !in scopes || "resource.invoke" !in scopes) {
                    error("ChatGPT sign-in succeeded, but Spark was not granted ChatGPT plan usage.")
                }
                val idToken = token.optString("id_token").takeIf(String::isNotBlank)
                    ?: error("ChatGPT did not return an identity token.")
                val identity = verifyIdToken(idToken, issuedClientId, pending.nonce)
                val oldSubject = store.credentials()?.subject.orEmpty()
                if (oldSubject.isNotBlank() && oldSubject != identity.subject) {
                    error("The ChatGPT account changed during sign-in. Disconnect first, then retry.")
                }
                val accessToken = token.optString("access_token").takeIf(String::isNotBlank)
                    ?: error("ChatGPT did not return an access token.")
                val refreshToken = token.optString("refresh_token").takeIf(String::isNotBlank)
                    ?: error("ChatGPT did not return a refresh token.")
                val expiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L
                ChatGptPlanStore.Credentials(
                    clientId = issuedClientId,
                    subject = identity.subject,
                    email = identity.email,
                    idToken = idToken,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMillis = expiresAt,
                    scopes = scopes,
                ).also { if (!store.save(it)) error("Could not securely save the ChatGPT connection.") }
            }
        }

    suspend fun accessToken(store: ChatGptPlanStore): String = withContext(Dispatchers.IO) {
        val current = store.credentials() ?: error("Sign in with ChatGPT first.")
        if (DIRECT_SCOPE !in current.scopes) error("ChatGPT plan usage is not enabled for Spark.")
        if (current.accessToken.isNotBlank() && current.expiresAtMillis > System.currentTimeMillis() + 60_000L) {
            return@withContext current.accessToken
        }
        if (current.refreshToken.isBlank()) error("ChatGPT sign-in expired. Reconnect your account.")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", current.clientId)
            .add("refresh_token", current.refreshToken)
            .add("resource", RESOURCE)
            .build()
        val response = http.newCall(
            Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        ).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("ChatGPT token refresh failed (${resp.code}). ${safeError(raw)}")
            JSONObject(raw)
        }
        val access = response.optString("access_token").takeIf(String::isNotBlank)
            ?: error("ChatGPT token refresh returned no access token.")
        val refreshed = current.copy(
            accessToken = access,
            refreshToken = response.optString("refresh_token").ifBlank { current.refreshToken },
            expiresAtMillis = System.currentTimeMillis() + response.optLong("expires_in", 3600L) * 1000L,
            scopes = response.optString("scope").takeIf(String::isNotBlank)
                ?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: current.scopes,
        )
        if (DIRECT_SCOPE !in refreshed.scopes) error("Refreshed ChatGPT grant does not permit plan usage.")
        if (!store.save(refreshed)) error("Could not securely save refreshed ChatGPT credentials.")
        access
    }

    private data class Identity(val subject: String, val email: String)

    private fun exchangeCode(code: String, clientId: String, redirectUri: String, verifier: String): JSONObject {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .add("resource", RESOURCE)
            .build()
        return http.newCall(Request.Builder().url(TOKEN_ENDPOINT).post(body).build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("ChatGPT token exchange failed (${resp.code}). ${safeError(raw)} Tap Continue with ChatGPT to retry.")
            }
            JSONObject(raw)
        }
    }

    private fun verifyIdToken(idToken: String, clientId: String, nonce: String): Identity {
        val pieces = idToken.split('.')
        if (pieces.size != 3) error("ChatGPT returned an invalid identity token.")
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(pieces[0]), StandardCharsets.UTF_8))
        val claims = JSONObject(String(decoder.decode(pieces[1]), StandardCharsets.UTF_8))
        if (header.optString("alg") != "RS256") error("ChatGPT used an unsupported identity-token signature.")
        val kid = header.optString("kid").takeIf(String::isNotBlank)
            ?: error("ChatGPT identity token has no signing-key ID.")
        val jwks = http.newCall(Request.Builder().url(JWKS_ENDPOINT).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Could not verify ChatGPT sign-in (${resp.code}).")
            JSONObject(resp.body?.string().orEmpty())
        }
        val jwk = (0 until jwks.getJSONArray("keys").length())
            .map { jwks.getJSONArray("keys").getJSONObject(it) }
            .firstOrNull { it.optString("kid") == kid && it.optString("kty") == "RSA" }
            ?: error("Could not find ChatGPT's identity signing key.")
        val modulus = BigInteger(1, decoder.decode(jwk.getString("n")))
        val exponent = BigInteger(1, decoder.decode(jwk.getString("e")))
        val publicKey = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${pieces[0]}.${pieces[1]}".toByteArray(StandardCharsets.US_ASCII))
        if (!verifier.verify(decoder.decode(pieces[2]))) error("ChatGPT identity-token signature is invalid.")
        if (claims.optString("iss") != ISSUER) error("ChatGPT identity-token issuer is invalid.")
        val audienceMatches = when (val aud = claims.opt("aud")) {
            is String -> aud == clientId
            is org.json.JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        if (!audienceMatches) error("ChatGPT identity-token audience is invalid.")
        if (claims.optLong("exp", 0L) <= System.currentTimeMillis() / 1000L) {
            error("ChatGPT identity token has expired.")
        }
        if (claims.optLong("iat", 0L) <= 0L) error("ChatGPT identity token has no issue time.")
        if (!constantTimeEquals(claims.optString("nonce"), nonce)) error("ChatGPT identity-token nonce is invalid.")
        val subject = claims.optString("sub").takeIf(String::isNotBlank)
            ?: error("ChatGPT identity token has no account ID.")
        return Identity(subject, claims.optString("email"))
    }

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    private fun safeError(raw: String): String = runCatching {
        val json = JSONObject(raw)
        val code = when (val error = json.opt("error")) {
            is String -> error
            is JSONObject -> error.optString("code")
            else -> ""
        }
        // Keep diagnostics useful without echoing any server-supplied credentials.
        code.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }.orEmpty()
    }.getOrDefault("")
}
