package com.osakaresearch.spark.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.util.UUID

/** Encrypted local storage for the user's optional Sign in with ChatGPT grant. */
class ChatGptPlanStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Credentials(
        val clientId: String,
        val subject: String,
        val email: String,
        val idToken: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val scopes: Set<String>,
    )

    data class PendingSignIn(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        val clientId: String,
        val expiresAtMillis: Long,
    )

    fun pendingSignIn(): PendingSignIn? = runCatching {
        val json = JSONObject(prefs.getString(K_PENDING, null) ?: return null)
        PendingSignIn(
            json.getString("state"), json.getString("nonce"), json.getString("verifier"),
            json.getString("redirect_uri"), json.getString("client_id"), json.getLong("expires_at"),
        ).takeIf { it.expiresAtMillis > System.currentTimeMillis() }
    }.getOrNull()

    fun savePendingSignIn(pending: PendingSignIn): Boolean = prefs.edit().putString(
        K_PENDING,
        JSONObject().put("state", pending.state).put("nonce", pending.nonce)
            .put("verifier", pending.verifier).put("redirect_uri", pending.redirectUri)
            .put("client_id", pending.clientId).put("expires_at", pending.expiresAtMillis).toString(),
    ).commit()

    // The callback issues a durable registration even if code exchange later fails.
    fun consumePendingSignIn(issuedClientId: String): Boolean = prefs.edit()
        .putString(K_CLIENT_ID, issuedClientId)
        .remove(K_PENDING)
        .commit()

    fun clearPendingSignIn() { prefs.edit().remove(K_PENDING).commit() }

    fun credentials(): Credentials? {
        val clientId = prefs.getString(K_CLIENT_ID, null)?.takeIf(String::isNotBlank) ?: return null
        val access = prefs.getString(K_ACCESS, null)?.takeIf(String::isNotBlank) ?: return null
        return Credentials(
            clientId = clientId,
            subject = prefs.getString(K_SUBJECT, "").orEmpty(),
            email = prefs.getString(K_EMAIL, "").orEmpty(),
            idToken = prefs.getString(K_ID_TOKEN, "").orEmpty(),
            accessToken = access,
            refreshToken = prefs.getString(K_REFRESH, "").orEmpty(),
            expiresAtMillis = prefs.getLong(K_EXPIRES_AT, 0L),
            scopes = prefs.getString(K_SCOPES, "").orEmpty()
                .split(' ').filter(String::isNotBlank).toSet(),
        )
    }

    fun email(): String = prefs.getString(K_EMAIL, "").orEmpty()
    fun model(): String = prefs.getString(K_MODEL, "").orEmpty()
    fun setModel(model: String) { prefs.edit().putString(K_MODEL, model).apply() }

    fun clientId(): String = prefs.getString(K_CLIENT_ID, "").orEmpty()
    fun idToken(): String = prefs.getString(K_ID_TOKEN, "").orEmpty()
    fun hostId(): String = prefs.getString(K_HOST_ID, null) ?:
        "urn:uuid:${UUID.randomUUID()}".also {
            check(prefs.edit().putString(K_HOST_ID, it).commit()) { "Could not save the Spark host ID." }
        }

    fun save(credentials: Credentials): Boolean = prefs.edit()
        .putString(K_CLIENT_ID, credentials.clientId)
        .putString(K_SUBJECT, credentials.subject)
        .putString(K_EMAIL, credentials.email)
        .putString(K_ID_TOKEN, credentials.idToken)
        .putString(K_ACCESS, credentials.accessToken)
        .putString(K_REFRESH, credentials.refreshToken)
        .putLong(K_EXPIRES_AT, credentials.expiresAtMillis)
        .putString(K_SCOPES, credentials.scopes.sorted().joinToString(" "))
        .remove(K_PENDING)
        .commit()

    fun clear() {
        prefs.edit()
            .remove(K_SUBJECT).remove(K_EMAIL)
            .remove(K_ID_TOKEN).remove(K_ACCESS).remove(K_REFRESH)
            .remove(K_EXPIRES_AT).remove(K_SCOPES).remove(K_PENDING).apply()
    }

    companion object {
        private const val FILE = "spark_chatgpt_plan"
        private const val K_CLIENT_ID = "client_id"
        private const val K_SUBJECT = "subject"
        private const val K_EMAIL = "email"
        private const val K_ID_TOKEN = "id_token"
        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES_AT = "expires_at"
        private const val K_SCOPES = "scopes"
        private const val K_HOST_ID = "host_id"
        private const val K_MODEL = "model"
        private const val K_PENDING = "pending_sign_in"
    }
}
