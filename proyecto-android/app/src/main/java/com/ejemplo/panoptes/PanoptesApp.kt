package com.carpe.panoptes

import android.app.Application
import com.carpe.panoptes.core.db.PanoptesDatabase
import com.carpe.panoptes.core.repo.AnalysisRepository
import com.carpe.panoptes.modules.ModuleManager
import com.carpe.panoptes.ui.settings.SettingsRepository

/**
 * Application class del shell.
 * F0: inicializa Room y el ModuleManager (este último en modo "sin módulos
 * instalados todavía" — la descarga llega en F2).
 */
class PanoptesApp : Application() {

    lateinit var database: PanoptesDatabase
        private set

    lateinit var analysisRepository: AnalysisRepository
        private set

    lateinit var moduleManager: ModuleManager
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()

        com.carpe.panoptes.core.log.AppLog.init(filesDir)
        database = PanoptesDatabase.build(this)
        analysisRepository = AnalysisRepository(database)
        settingsRepository = SettingsRepository(this)

        moduleManager = ModuleManager(this).apply { scanInstalled() }
        com.carpe.panoptes.core.log.AppLog.i("MODULOS", "Instalados: " + (moduleManager.listInstalled().joinToString { it.id + " v" + it.version }.ifEmpty { "ninguno" }))
    }
}
