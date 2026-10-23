package com.luzcontrol.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.luzcontrol.app.domain.model.DatadisCredentials
import com.luzcontrol.app.domain.repository.CredentialsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credenciales cifradas con Android Keystore (MasterKey AES-256-GCM) + EncryptedSharedPreferences.
 * Nunca salen del dispositivo ni se guardan en claro.
 */
@Singleton
class CredentialsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CredentialsRepository {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _has = MutableStateFlow(safeHas())
    override val hasDatadisCredentials: StateFlow<Boolean> = _has.asStateFlow()

    private fun safeHas(): Boolean = try {
        !prefs.getString(KEY_USER, null).isNullOrBlank() && !prefs.getString(KEY_PASS, null).isNullOrBlank()
    } catch (e: Exception) {
        false
    }

    override fun getDatadis(): DatadisCredentials? = try {
        val u = prefs.getString(KEY_USER, null)
        val p = prefs.getString(KEY_PASS, null)
        if (u.isNullOrBlank() || p.isNullOrBlank()) null else DatadisCredentials(u, p)
    } catch (e: Exception) {
        // Si el Keystore se invalidó (p. ej. cambio de bloqueo de pantalla) se pide reintroducir los datos.
        null
    }

    override fun saveDatadis(credentials: DatadisCredentials) {
        prefs.edit()
            .putString(KEY_USER, credentials.username.trim())
            .putString(KEY_PASS, credentials.password)
            .apply()
        _has.value = true
    }

    override fun clearDatadis() {
        try {
            prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
        } catch (_: Exception) {
        }
        _has.value = false
    }

    override fun getEsiosToken(): String? = try {
        prefs.getString(KEY_ESIOS, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    override fun saveEsiosToken(token: String?) {
        val e = prefs.edit()
        if (token.isNullOrBlank()) e.remove(KEY_ESIOS) else e.putString(KEY_ESIOS, token.trim())
        e.apply()
    }

    private companion object {
        const val FILE = "luzcontrol_secure_prefs"
        const val KEY_USER = "datadis_user"
        const val KEY_PASS = "datadis_pass"
        const val KEY_ESIOS = "esios_token"
    }
}
