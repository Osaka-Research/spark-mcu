package com.osakaresearch.spark.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys and per-provider overrides, kept in EncryptedSharedPreferences so
 * they sit at rest encrypted-with-keystore rather than as plaintext on disk.
 *
 * One file holds everything: selected provider + the per-provider triplet
 * (api_key, base_url, model). Base URL + model are blank unless the user
 * overrode the provider defaults.
 */
class AiKeyStore(ctx: Context) {
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

    var selected: AiProvider
        get() = AiProvider.byId(prefs.getString(K_SELECTED, null))
        set(v) = prefs.edit().putString(K_SELECTED, v.id).apply()

    fun apiKey(p: AiProvider): String = prefs.getString(key(p, "api_key"), "").orEmpty()
    fun setApiKey(p: AiProvider, v: String) =
        prefs.edit().putString(key(p, "api_key"), v).apply()

    /** Empty string = use [AiProvider.defaultBaseUrl]. */
    fun baseUrl(p: AiProvider): String = prefs.getString(key(p, "base_url"), "").orEmpty()
    fun setBaseUrl(p: AiProvider, v: String) =
        prefs.edit().putString(key(p, "base_url"), v).apply()

    /** Empty string = use [AiProvider.defaultModel]. */
    fun model(p: AiProvider): String = prefs.getString(key(p, "model"), "").orEmpty()
    fun setModel(p: AiProvider, v: String) =
        prefs.edit().putString(key(p, "model"), v).apply()

    fun clear(p: AiProvider) = prefs.edit()
        .remove(key(p, "api_key"))
        .remove(key(p, "base_url"))
        .remove(key(p, "model"))
        .apply()

    fun effectiveBaseUrl(p: AiProvider): String =
        baseUrl(p).ifBlank { p.defaultBaseUrl }

    fun effectiveModel(p: AiProvider): String =
        model(p).ifBlank { p.defaultModel }

    private fun key(p: AiProvider, field: String) = "${p.id}.$field"

    companion object {
        private const val FILE = "spark_ai_keys"
        private const val K_SELECTED = "selected_provider"
    }
}
