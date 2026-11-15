package com.osakaresearch.spark.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Persists the bearer token the MCP server uses to authenticate inbound
 * calls. Generated once on first launch, read on every request. Lives in
 * EncryptedSharedPreferences so an adb-pull of app data doesn't surrender it
 * in plaintext.
 */
class McpTokenStore(ctx: Context) {
    private val prefs: SharedPreferences = run {
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            FILE,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Returns the current token, minting one on first access. */
    fun tokenOrMint(): String {
        prefs.getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = mint()
        prefs.edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    fun rotate(): String {
        val fresh = mint()
        prefs.edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    fun matches(presented: String?): Boolean {
        if (presented.isNullOrBlank()) return false
        val stored = prefs.getString(K_TOKEN, null) ?: return false
        return constantTimeEquals(stored, presented)
    }

    private fun mint(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        // URL-safe base64 without padding, no stdlib Base64 flags juggling.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString(32) {
            var i = 0
            while (i + 3 <= bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                append(alphabet[b0 ushr 2])
                append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                append(alphabet[b2 and 0x3F])
                i += 3
            }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        private const val FILE = "spark_mcp_auth"
        private const val K_TOKEN = "bearer_token"
    }
}
