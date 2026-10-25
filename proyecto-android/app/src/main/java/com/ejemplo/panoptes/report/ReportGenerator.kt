package com.carpe.panoptes.report

import android.content.Context
import com.carpe.panoptes.core.db.AnalysisEntity
import com.carpe.panoptes.core.db.ComponentEntity
import com.carpe.panoptes.core.db.EndpointEntity
import com.carpe.panoptes.core.db.FindingEntity
import com.carpe.panoptes.core.db.NativeLibEntity
import com.carpe.panoptes.core.db.PanoptesDatabase
import com.carpe.panoptes.core.db.PermissionEntity
import com.carpe.panoptes.core.db.SecretEntity
import com.carpe.panoptes.core.db.Severity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class ReportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    SARIF("sarif", "application/sarif+json"), // SARIF es JSON; muchas herramientas aceptan este mime o application/json a secas
    HTML("html", "text/html")
}

/** [8] Reporte (§6.1 del plan): exporta un análisis ya hecho a un fichero que se puede compartir. */
class ReportGenerator(private val context: Context, private val db: PanoptesDatabase) {

    private data class Datos(
        val analisis: AnalysisEntity,
        val findings: List<FindingEntity>,
        val permisos: List<PermissionEntity>,
        val componentes: List<ComponentEntity>,
        val endpoints: List<EndpointEntity>,
        val secretos: List<SecretEntity>,
        val nativeLibs: List<NativeLibEntity>
    )

    suspend fun generar(analysisId: String, formato: ReportFormat): File {
        val datos = cargarDatos(analysisId)
        val contenido = when (formato) {
            ReportFormat.JSON -> generarJson(datos)
            ReportFormat.SARIF -> generarSarif(datos)
            ReportFormat.HTML -> generarHtml(datos)
        }

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val archivo = File(dir, "panoptes-${datos.analisis.packageName}-${analysisId.take(8)}.${formato.extension}")
        archivo.writeText(contenido)
        return archivo
    }

    private suspend fun cargarDatos(analysisId: String): Datos {
        val analisis = db.analysisDao().getById(analysisId) ?: error("Análisis no encontrado: $analysisId")
        return Datos(
            analisis = analisis,
            findings = db.resultsDao().findingsFor(analysisId),
            permisos = db.resultsDao().permissionsFor(analysisId),
            componentes = db.resultsDao().componentsFor(analysisId),
            endpoints = db.resultsDao().endpointsFor(analysisId),
            secretos = db.resultsDao().secretsFor(analysisId),
            nativeLibs = db.resultsDao().nativeLibsFor(analysisId)
        )
    }

    // ---------------------------------------------------------------- JSON

    private fun generarJson(d: Datos): String {
        val root = JSONObject()
        root.put("tool", "APK Panoptes")
        root.put("generated", DateFormat.getDateTimeInstance().format(Date()))
        root.put("package", d.analisis.packageName)
        root.put("version", d.analisis.versionName)
        root.put("sha256", d.analisis.sha256)
        root.put("status", d.analisis.status.name)
        root.put("modulesUsed", d.analisis.modulesUsed)

        root.put("findings", JSONArray(d.findings.map {
            JSONObject().apply {
                put("category", it.category)
                put("severity", it.severity.name)
                put("description", it.description)
                put("evidence", it.evidence)
            }
        }))
        root.put("permissions", JSONArray(d.permisos.map {
            JSONObject().apply { put("name", it.name); put("protectionLevel", it.protectionLevel) }
        }))
        root.put("components", JSONArray(d.componentes.filter { it.exported }.map {
            JSONObject().apply { put("type", it.type.name); put("name", it.name); put("exported", it.exported) }
        }))
        root.put("endpoints", JSONArray(d.endpoints.map {
            JSONObject().apply {
                put("url", it.url); put("confidence", it.confidence); put("calledInRuntime", it.calledInRuntime)
            }
        }))
        // Los secretos SÍ van completos en el JSON exportado (a diferencia de
        // la UI, que los enmascara) — es un export que el propio usuario pide
        // para su propio APK, igual que un .sarif de gitleaks te da el valor
        // completo para que decidas qué rotar.
        root.put("secrets", JSONArray(d.secretos.map {
            JSONObject().apply { put("type", it.type); put("value", it.value); put("entropy", it.entropy) }
        }))
        root.put("nativeLibs", JSONArray(d.nativeLibs.map {
            JSONObject().apply { put("name", it.name); put("arch", it.arch) }
        }))

        return root.toString(2)
    }

    // --------------------------------------------------------------- SARIF

    /**
     * SARIF 2.1.0 (sarifstatic.github.io / OASIS). Formato estándar que
     * entienden GitHub Code Scanning, VS Code, etc. — útil si alguna vez
     * quieres que los hallazgos de Panoptes aparezcan junto a los de otras
     * herramientas de análisis estático en el mismo pipeline.
     */
    private fun generarSarif(d: Datos): String {
        val reglas = d.findings.map { it.category }.distinct()
        val rulesJson = JSONArray(reglas.map { categoria ->
            JSONObject().apply {
                put("id", categoria)
                put("name", categoria)
                put("shortDescription", JSONObject().put("text", "Hallazgo de categoría '$categoria'"))
            }
        })

        val resultsJson = JSONArray(d.findings.map { f ->
            JSONObject().apply {
                put("ruleId", f.category)
                put("level", severityToSarifLevel(f.severity))
                put("message", JSONObject().put("text", f.description))
                put("locations", JSONArray(listOf(
                    JSONObject().put("physicalLocation", JSONObject().put("artifactLocation",
                        JSONObject().put("uri", d.analisis.apkPath)
                    ))
                )))
            }
        })

        val run = JSONObject().apply {
            put("tool", JSONObject().put("driver", JSONObject().apply {
                put("name", "APK Panoptes")
                put("informationUri", "https://github.com/")  // ajusta a tu repo real
                put("version", "1.0.0")
                put("rules", rulesJson)
            }))
            put("results", resultsJson)
        }

        val root = JSONObject().apply {
            put("\$schema", "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json")
            put("version", "2.1.0")
            put("runs", JSONArray(listOf(run)))
        }
        return root.toString(2)
    }

    private fun severityToSarifLevel(s: Severity): String = when (s) {
        Severity.HIGH -> "error"
        Severity.MEDIUM -> "warning"
        Severity.LOW, Severity.INFO -> "note"
    }

    // ---------------------------------------------------------------- HTML

    private fun generarHtml(d: Datos): String {
        fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val filasFindings = d.findings.sortedByDescending { it.severity.ordinal }.joinToString("\n") {
            """<tr class="sev-${it.severity.name.lowercase()}"><td>${it.severity}</td><td>${esc(it.category)}</td><td>${esc(it.description)}</td></tr>"""
        }
        val filasPermisos = d.permisos.joinToString("\n") {
            """<tr><td>${esc(it.protectionLevel)}</td><td>${esc(it.name)}</td></tr>"""
        }
        val filasEndpoints = d.endpoints.joinToString("\n") {
            """<tr><td>${"%.1f".format(it.confidence)}</td><td>${esc(it.url)}</td><td>${if (it.calledInRuntime) "✓ runtime" else "solo estático"}</td></tr>"""
        }
        val filasSecretos = d.secretos.joinToString("\n") {
            val masked = if (it.value.length <= 8) "****" else it.value.take(4) + "…" + it.value.takeLast(4)
            """<tr><td>${esc(it.type)}</td><td><code>$masked</code></td><td>${"%.1f".format(it.entropy)}</td></tr>"""
        }
        val filasNativas = d.nativeLibs.joinToString("\n") {
            """<tr><td>${esc(it.arch)}</td><td>${esc(it.name)}</td></tr>"""
        }

        return """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<title>Panoptes — ${esc(d.analisis.packageName)}</title>
<style>
  body { font-family: -apple-system, Roboto, sans-serif; margin: 2rem; color: #222; background: #fafafa; }
  h1 { color: #6C5CE7; }
  h2 { border-bottom: 2px solid #eee; padding-bottom: 4px; margin-top: 2rem; }
  table { width: 100%; border-collapse: collapse; margin-top: 8px; }
  th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #eee; font-size: 14px; }
  th { background: #f0f0f0; }
  code { background: #eee; padding: 2px 4px; border-radius: 3px; }
  .meta { color: #666; font-size: 13px; }
  .sev-high td:first-child { color: #c0392b; font-weight: bold; }
  .sev-medium td:first-child { color: #d68910; font-weight: bold; }
  .sev-low td:first-child, .sev-info td:first-child { color: #888; }
  .disclaimer { background: #fff3cd; border: 1px solid #ffe69c; padding: 12px; border-radius: 6px; margin-top: 2rem; font-size: 13px; }
</style>
</head>
<body>
  <h1>APK Panoptes — Informe</h1>
  <p class="meta">
    Paquete: <strong>${esc(d.analisis.packageName)}</strong> (${esc(d.analisis.versionName)})<br>
    SHA-256: <code>${esc(d.analisis.sha256)}</code><br>
    Estado: ${d.analisis.status} · Módulos usados: ${esc(d.analisis.modulesUsed.ifBlank { "ninguno" })}<br>
    Generado: ${DateFormat.getDateTimeInstance().format(Date())}
  </p>

  <h2>Hallazgos (${d.findings.size})</h2>
  <table><tr><th>Severidad</th><th>Categoría</th><th>Descripción</th></tr>
  $filasFindings
  </table>

  <h2>Permisos (${d.permisos.size})</h2>
  <table><tr><th>Nivel</th><th>Permiso</th></tr>
  $filasPermisos
  </table>

  <h2>Endpoints candidatos (${d.endpoints.size})</h2>
  <table><tr><th>Confianza</th><th>URL</th><th>Visto en runtime</th></tr>
  $filasEndpoints
  </table>

  <h2>Secretos candidatos (${d.secretos.size})</h2>
  <p class="meta">Enmascarados en este HTML — el valor completo está en el export JSON si lo necesitas.</p>
  <table><tr><th>Tipo</th><th>Valor</th><th>Entropía</th></tr>
  $filasSecretos
  </table>

  <h2>Librerías nativas (${d.nativeLibs.size})</h2>
  <table><tr><th>ABI</th><th>Nombre</th></tr>
  $filasNativas
  </table>

  <div class="disclaimer">
    Informe generado localmente por APK Panoptes, sin conexión a servidores
    propios. Los "secretos candidatos" y "endpoints candidatos" son
    heurísticas — revísalos tú mismo antes de actuar sobre ellos. Uso
    personal exclusivamente: analiza solo APKs propios o con autorización
    explícita (ver docs/LEGAL.md).
  </div>
</body>
</html>
""".trimIndent()
    }
}
