package com.carpe.panoptes.analyzer.orchestrator

import com.carpe.panoptes.modules.spi.AnalyzerModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Ejecuta un módulo descargado sin que pueda colgar ni tumbar el análisis:
 *  - con tiempo límite (el trabajo del módulo corre en su propio hilo y se interrumpe al vencer);
 *  - capturando también los Error (NoClassDefFoundError, ExceptionInInitializerError...), que son
 *    lo típico cuando el DEX de un módulo no es compatible con Android y que un `catch (Exception)`
 *    dejaba pasar hasta matar el worker con el análisis "pendiente" para siempre;
 *  - devolviendo siempre el MOTIVO del fallo, para mostrarlo como hallazgo.
 */
internal object ModuleRunner {

    data class Salida(val resultado: AnalyzerModule.ModuleResult?, val fallo: String?)

    suspend fun ejecutar(
        cargar: () -> AnalyzerModule,
        descargar: () -> Unit,
        apkPath: String,
        outputDir: File,
        limite: Long,
        unidad: TimeUnit,
        onProgress: (fase: String, percent: Int) -> Unit
    ): Salida {
        val ejecutor = Executors.newSingleThreadExecutor()
        try {
            val modulo = cargar()
            val listener = object : AnalyzerModule.ProgressListener {
                override fun onProgress(phase: String, percent: Int) = onProgress(phase, percent)
                override fun onLog(line: String) { com.carpe.panoptes.core.log.AppLog.i("MODULO", line) }
            }
            val futuro = ejecutor.submit<AnalyzerModule.ModuleResult> { modulo.analyze(apkPath, outputDir, listener) }
            val resultado = try {
                runInterruptible(Dispatchers.IO) { futuro.get(limite, unidad) }
            } catch (e: TimeoutException) {
                futuro.cancel(true)
                return Salida(null, "excedió el tiempo límite (${unidad.toMinutes(limite)} min)")
            } catch (e: ExecutionException) {
                val causa = e.cause ?: e
                return Salida(null, "${causa.javaClass.simpleName}: ${causa.message ?: "sin detalle"}")
            }
            return if (resultado.success) Salida(resultado, null)
            else Salida(null, resultado.error ?: "el módulo terminó sin éxito y sin detalle")
        } catch (e: CancellationException) {
            throw e // no tragarse la cancelación de la corrutina
        } catch (e: Throwable) {
            return Salida(null, "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
        } finally {
            ejecutor.shutdownNow()
            try { descargar() } catch (e: Throwable) { /* no debe tapar el resultado */ }
        }
    }
}
