package com.luzcontrol.app.data.repository

import android.content.Context
import com.luzcontrol.app.domain.model.BillingParams
import com.luzcontrol.app.domain.model.UserSettings
import com.luzcontrol.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Preferencias no sensibles (las credenciales viven en el almacén cifrado). */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : SettingsRepository {

    private val prefs = context.getSharedPreferences("luzcontrol_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun d(key: String, def: Double): Double =
        prefs.getString(key, null)?.toDoubleOrNull() ?: def

    private fun load(): UserSettings {
        val def = BillingParams()
        return UserSettings(
            activeCups = prefs.getString("active_cups", null),
            dailySync = prefs.getBoolean("daily_sync", true),
            priceNotifications = prefs.getBoolean("price_notifications", false),
            requireBiometric = prefs.getBoolean("require_biometric", false),
            datadisApiV2 = prefs.getBoolean("datadis_api_v2", false),
            ufdEnabled = prefs.getBoolean("ufd_enabled", false),
            billing = BillingParams(
                powerP1EurKwDay = d("b_p1", def.powerP1EurKwDay),
                powerP2EurKwDay = d("b_p2", def.powerP2EurKwDay),
                meterRentEurDay = d("b_meter", def.meterRentEurDay),
                socialBonusEurDay = d("b_bono", def.socialBonusEurDay),
                electricityTaxPct = d("b_tax", def.electricityTaxPct),
                ivaPct = d("b_iva", def.ivaPct)
            )
        )
    }

    @Synchronized
    override fun update(transform: (UserSettings) -> UserSettings) {
        val new = transform(_settings.value)
        prefs.edit()
            .putString("active_cups", new.activeCups)
            .putBoolean("daily_sync", new.dailySync)
            .putBoolean("price_notifications", new.priceNotifications)
            .putBoolean("require_biometric", new.requireBiometric)
            .putBoolean("datadis_api_v2", new.datadisApiV2)
            .putBoolean("ufd_enabled", new.ufdEnabled)
            .putString("b_p1", new.billing.powerP1EurKwDay.toString())
            .putString("b_p2", new.billing.powerP2EurKwDay.toString())
            .putString("b_meter", new.billing.meterRentEurDay.toString())
            .putString("b_bono", new.billing.socialBonusEurDay.toString())
            .putString("b_tax", new.billing.electricityTaxPct.toString())
            .putString("b_iva", new.billing.ivaPct.toString())
            .apply()
        _settings.value = new
    }
}
