package com.carpe.panoptes.ui.nav

object Routes {
    const val APPS = "apps"
    const val ANALYSIS_LIST = "analysis_list"
    const val ANALYSIS_DETAIL = "analysis_detail/{analysisId}"
    const val MODULES = "modules"
    const val SETTINGS = "settings"
    const val EXPLORER = "explorer/{analysisId}"

    fun analysisDetail(id: String) = "analysis_detail/$id"
    fun explorer(id: String) = "explorer/$id"
}
