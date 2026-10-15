package com.carpe.panoptes.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "panoptes_settings")
private val KEY_LEGAL_ACEPTADO = booleanPreferencesKey("legal_aceptado")

class SettingsRepository(private val context: Context) {

    val legalAceptado: Flow<Boolean> = context.dataStore.data.map { it[KEY_LEGAL_ACEPTADO] ?: false }

    suspend fun marcarLegalAceptado() {
        context.dataStore.edit { it[KEY_LEGAL_ACEPTADO] = true }
    }
}
