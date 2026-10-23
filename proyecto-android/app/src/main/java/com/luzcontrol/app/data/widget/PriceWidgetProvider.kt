package com.luzcontrol.app.data.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.luzcontrol.app.MainActivity
import com.luzcontrol.app.R
import com.luzcontrol.app.domain.model.TariffCalendar
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun energyRepository(): EnergyRepository
    fun settingsRepository(): SettingsRepository
}

/** Widget compacto: precio actual de la luz y consumo del último día disponible. */
class PriceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val views = buildViews(context)
                for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
            } catch (_: Exception) {
                // sin datos o base de datos no disponible: el widget conserva su último contenido
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun buildViews(context: Context): RemoteViews {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val energy = entry.energyRepository()
        val settings = entry.settingsRepository()
        val es = Locale.forLanguageTag("es-ES")
        val views = RemoteViews(context.packageName, R.layout.widget_price)

        val now = Instant.now()
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val prices = energy.observePrices(hour.minus(2, ChronoUnit.HOURS), hour.plus(2, ChronoUnit.HOURS)).first()
        val current = prices.firstOrNull { it.start == hour }
        if (current != null) {
            views.setTextViewText(R.id.widget_price, String.format(es, "%.3f €/kWh", current.pvpcEurKwh))
            views.setTextViewText(R.id.widget_period, "Ahora · " + TariffCalendar.periodAt(hour).label)
        } else {
            views.setTextViewText(R.id.widget_price, context.getString(R.string.widget_no_data))
            views.setTextViewText(R.id.widget_period, "")
        }

        val cups = settings.settings.value.activeCups
            ?: energy.observeSupplies().first().firstOrNull()?.cups
        var consumptionText = ""
        if (cups != null) {
            val zone = TariffCalendar.ZONE
            val today = now.atZone(zone).toLocalDate()
            val points = energy.observeConsumption(
                cups,
                today.minusDays(10).atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant()
            ).first()
            val lastDay = points.maxByOrNull { it.start }?.start?.atZone(zone)?.toLocalDate()
            if (lastDay != null) {
                val kwh = points.filter { it.start.atZone(zone).toLocalDate() == lastDay }.sumOf { it.kwh }
                consumptionText = String.format(es, "%s: %.1f kWh", lastDay.format(DateTimeFormatter.ofPattern("dd/MM")), kwh)
            }
        }
        views.setTextViewText(R.id.widget_yesterday, consumptionText)

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        return views
    }

    companion object {
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, PriceWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
