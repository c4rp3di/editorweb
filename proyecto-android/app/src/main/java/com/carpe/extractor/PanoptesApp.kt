package com.carpe.panoptes

import android.app.Application
import com.carpe.panoptes.core.db.PanoptesDatabase
import com.carpe.panoptes.core.repo.AnalysisRepository
import com.carpe.panoptes.modules.ModuleManager

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

    override fun onCreate() {
        super.onCreate()

        database = PanoptesDatabase.build(this)
        analysisRepository = AnalysisRepository(database)

        moduleManager = ModuleManager(this).apply { scanInstalled() }
    }
}
