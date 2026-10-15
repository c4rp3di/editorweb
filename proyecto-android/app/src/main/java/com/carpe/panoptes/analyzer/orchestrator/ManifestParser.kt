package com.carpe.panoptes.analyzer.orchestrator

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.carpe.panoptes.core.db.ComponentEntity
import com.carpe.panoptes.core.db.ComponentType
import com.carpe.panoptes.core.db.PermissionEntity

/**
 * "Manifest básico" del plan (§6.2): usa PackageManager.getPackageArchiveInfo
 * sobre el APK ya extraído — no requiere apktool (eso es un módulo, F2+) y
 * da permisos + componentes exportados directamente desde la API pública
 * de Android.
 */
class ManifestParser(private val context: Context) {

    data class Result(
        val permissions: List<PermissionEntity>,
        val components: List<ComponentEntity>
    )

    fun parse(apkPath: String, analysisId: String): Result {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        val info: PackageInfo = pm.getPackageArchiveInfo(apkPath, flags)
            ?: return Result(emptyList(), emptyList())

        val permisos = (info.requestedPermissions ?: emptyArray()).map { nombre ->
            val nivel = try {
                val pInfo: PermissionInfo = pm.getPermissionInfo(nombre, 0)
                protectionLevelToString(pInfo)
            } catch (e: PackageManager.NameNotFoundException) {
                "desconocido" // permiso custom de la propia app o no resuelto por el sistema
            }
            PermissionEntity(
                analysisId = analysisId,
                name = nombre,
                protectionLevel = nivel,
                description = null
            )
        }

        val componentes = mutableListOf<ComponentEntity>()
        info.activities?.forEach { componentes.add(toComponent(analysisId, it, ComponentType.ACTIVITY)) }
        info.services?.forEach { componentes.add(toComponent(analysisId, it, ComponentType.SERVICE)) }
        info.receivers?.forEach { componentes.add(toComponent(analysisId, it, ComponentType.RECEIVER)) }
        info.providers?.forEach { componentes.add(toComponent(analysisId, it, ComponentType.PROVIDER)) }

        return Result(permisos, componentes)
    }

    private fun toComponent(analysisId: String, info: ActivityInfo, type: ComponentType) =
        ComponentEntity(
            analysisId = analysisId,
            type = type,
            name = info.name,
            exported = info.exported
        )

    private fun toComponent(analysisId: String, info: android.content.pm.ServiceInfo, type: ComponentType) =
        ComponentEntity(analysisId, type, info.name, info.exported)

    private fun toComponent(analysisId: String, info: android.content.pm.ProviderInfo, type: ComponentType) =
        ComponentEntity(analysisId, type, info.name, info.exported)

    private fun protectionLevelToString(info: PermissionInfo): String {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.protection
        } else {
            @Suppress("DEPRECATION")
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
        return when (base) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            else -> "otro"
        }
    }
}
