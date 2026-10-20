package com.carpe.panoptes.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.carpe.panoptes.modules.CatalogoConfig
import com.carpe.panoptes.modules.Config
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "panoptes_settings")
private val KEY_LEGAL_ACEPTADO = booleanPreferencesKey("legal_aceptado")
private val KEY_CAT_OWNER = stringPreferencesKey("catalogo_owner")
private val KEY_CAT_REPO = stringPreferencesKey("catalogo_repo")
private val KEY_CAT_CLAVE = stringPreferencesKey("catalogo_clave_publica")

class SettingsRepository(private val context: Context) {

    val legalAceptado: Flow<Boolean> = context.dataStore.data.map { it[KEY_LEGAL_ACEPTADO] ?: false }

    suspend fun marcarLegalAceptado() {
        context.dataStore.edit { it[KEY_LEGAL_ACEPTADO] = true }
    }

    val catalogo: Flow<CatalogoConfig> = context.dataStore.data.map {
        CatalogoConfig(
            owner = it[KEY_CAT_OWNER] ?: "",
            repo = it[KEY_CAT_REPO] ?: Config.DEFAULT_REPO,
            clavePublicaB64 = it[KEY_CAT_CLAVE] ?: ""
        )
    }

    suspend fun catalogoConfig(): CatalogoConfig = catalogo.first()

    suspend fun guardarCatalogo(c: CatalogoConfig) {
        context.dataStore.edit {
            it[KEY_CAT_OWNER] = c.owner.trim()
            it[KEY_CAT_REPO] = c.repo.trim().ifEmpty { Config.DEFAULT_REPO }
            it[KEY_CAT_CLAVE] = c.clavePublicaB64.filter { ch -> !ch.isWhitespace() }
        }
    }
}
