package com.carpe.panoptes.analyzer.orchestrator

import android.content.Context
import com.carpe.panoptes.analyzer.dexlib.DexParser
import com.carpe.panoptes.analyzer.dexlib.StringScanner
import com.carpe.panoptes.core.db.ComponentType
import com.carpe.panoptes.core.db.FindingEntity
import com.carpe.panoptes.core.db.NativeLibEntity
import com.carpe.panoptes.core.db.PanoptesDatabase
import com.carpe.panoptes.core.db.Severity
import com.carpe.panoptes.modules.ModuleManager
import com.carpe.panoptes.modules.spi.AnalyzerModule
import com.carpe.panoptes.python.PythonBridge
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Pipeline de análisis del shell (§6.1 del plan), limitado a lo que no
 * necesita módulos ni Chaquopy todavía:
 *   [2] Parseo DEX        -> dexlib2
 *   [3] Manifest           -> PackageManager.getPackageArchiveInfo
 *   [4] Python (F5)        -> androguard (firma/certificado) siempre;
 *                             quark-engine (scoring) solo si hay reglas
 *                             empaquetadas (ver PythonBridge.kt)
 *   [5] Nativo (F4)        -> listado de .so siempre; análisis profundo
 *                             solo si el módulo "radare2" está instalado
 *   [6] Dinámico (F6)      -> NO corre en run() automáticamente (lanza otra
 *                             app, necesita root, tarda ~15s) — se invoca
 *                             a propósito desde runDynamicAnalysis()
 *   [7] Correlación        -> versión mínima: unas pocas reglas de Finding
 *   [8] Reporte            -> queda en Room; HTML/JSON/SARIF es F7
 */
class AnalysisOrchestrator(
    private val context: Context,
    private val db: PanoptesDatabase,
    private val moduleManager: ModuleManager
) {

    private val dexParser = DexParser()
    private val stringScanner = StringScanner()
    private val manifestParser = ManifestParser(context)
    private val pythonBridge = PythonBridge(context)

    suspend fun run(analysisId: String, apkPath: String) {
        val apkFile = File(apkPath)
        val workDir = File(context.filesDir, "analysis/$analysisId").apply { mkdirs() }

        // [2] + strings
        val dexResumenes = dexParser.parseApk(apkFile, workDir)
        val dexDir = File(workDir, "dex")
        val todasLasStrings = dexDir.listFiles()?.flatMap { stringScanner.extractStrings(it) } ?: emptyList()

        val endpoints = stringScanner.findEndpoints(todasLasStrings, analysisId, source = "dex")
        val secretos = stringScanner.findSecrets(todasLasStrings, analysisId, sourceFile = "classes.dex")

        // [3] Manifest
        val manifest = manifestParser.parse(apkPath, analysisId)

        // [4] Python (F5): androguard siempre, quark-engine si hay reglas
        val findingsPython = runPythonSafely(apkPath, analysisId)

        // Módulos (F2+): cada uno corre de forma aislada; si falla, el resto
        // del pipeline sigue. Cada módulo que tiene éxito añade su id a
        // modulesUsed y un Finding.
        val modulosUsados = mutableListOf<String>()
        if (moduleManager.isInstalled("jadx")) {
            runModuleSafely("jadx", apkFile, workDir, analysisId)?.let { modulosUsados.add("jadx") }
        }
        if (moduleManager.isInstalled("apktool")) {
            runModuleSafely("apktool", apkFile, workDir, analysisId)?.let { modulosUsados.add("apktool") }
        }

        // [5] Nativo: el listado de .so presentes no necesita ningún módulo
        // (es solo mirar dentro del ZIP del APK); el análisis profundo de
        // cada uno (imports/exports/strings) sí necesita "radare2".
        val nativeLibs = listarLibsNativas(apkFile, analysisId)
        if (nativeLibs.isNotEmpty()) db.resultsDao().insertNativeLibs(nativeLibs)

        if (moduleManager.isInstalled("radare2") && nativeLibs.isNotEmpty()) {
            runModuleSafely("radare2", apkFile, workDir, analysisId)?.let { modulosUsados.add("radare2") }
        }

        // Persistir
        if (endpoints.isNotEmpty()) db.resultsDao().insertEndpoints(endpoints)
        if (secretos.isNotEmpty()) db.resultsDao().insertSecrets(secretos)
        if (manifest.permissions.isNotEmpty()) db.resultsDao().insertPermissions(manifest.permissions)
        if (manifest.components.isNotEmpty()) db.resultsDao().insertComponents(manifest.components)

        // [7] Correlación mínima -> Findings
        val findings = mutableListOf<FindingEntity>()
        findings.addAll(findingsPython)

        if (secretos.isNotEmpty()) {
            findings.add(
                FindingEntity(
                    analysisId = analysisId,
                    category = "secret",
                    severity = Severity.HIGH,
                    description = "${secretos.size} posible(s) secreto(s) embebido(s) en el DEX",
                    evidence = "{\"count\":${secretos.size}}"
                )
            )
        }

        val endpointsInseguros = endpoints.filter { !it.url.startsWith("https://") }
        if (endpointsInseguros.isNotEmpty()) {
            findings.add(
                FindingEntity(
                    analysisId = analysisId,
                    category = "endpoint",
                    severity = Severity.MEDIUM,
                    description = "${endpointsInseguros.size} endpoint(s) sin TLS (http://) encontrados",
                    evidence = "{\"count\":${endpointsInseguros.size}}"
                )
            )
        }

        val exportadosSinProteccion = manifest.components.filter { it.exported }
        if (exportadosSinProteccion.isNotEmpty()) {
            findings.add(
                FindingEntity(
                    analysisId = analysisId,
                    category = "component",
                    severity = Severity.LOW,
                    description = "${exportadosSinProteccion.size} componente(s) exportado(s) " +
                        "(${exportadosSinProteccion.count { it.type == ComponentType.ACTIVITY }} activities, " +
                        "${exportadosSinProteccion.count { it.type == ComponentType.SERVICE }} services, " +
                        "${exportadosSinProteccion.count { it.type == ComponentType.RECEIVER }} receivers, " +
                        "${exportadosSinProteccion.count { it.type == ComponentType.PROVIDER }} providers)",
                    evidence = "{\"count\":${exportadosSinProteccion.size}}"
                )
            )
        }

        val dangerous = manifest.permissions.filter { it.protectionLevel == "dangerous" }
        if (dangerous.isNotEmpty()) {
            findings.add(
                FindingEntity(
                    analysisId = analysisId,
                    category = "permission",
                    severity = Severity.INFO,
                    description = "${dangerous.size} permiso(s) 'dangerous' solicitado(s): " +
                        dangerous.joinToString(", ") { it.name.substringAfterLast('.') },
                    evidence = "{\"count\":${dangerous.size}}"
                )
            )
        }

        if (nativeLibs.isNotEmpty()) {
            val analizadas = if (moduleManager.isInstalled("radare2")) " (analizadas con radare2)" else " (sin analizar — instala el módulo radare2)"
            findings.add(
                FindingEntity(
                    analysisId = analysisId,
                    category = "native",
                    severity = Severity.INFO,
                    description = "${nativeLibs.size} librería(s) nativa(s) (.so) encontrada(s)$analizadas",
                    evidence = "{\"count\":${nativeLibs.size}}"
                )
            )
        }

        findings.add(
            FindingEntity(
                analysisId = analysisId,
                category = "dex",
                severity = Severity.INFO,
                description = "${dexResumenes.sumOf { it.classCount }} clases, " +
                    "${dexResumenes.sumOf { it.methodCount }} métodos en ${dexResumenes.size} archivo(s) DEX",
                evidence = "{}"
            )
        )

        if (findings.isNotEmpty()) db.resultsDao().insertFindings(findings)

        if (modulosUsados.isNotEmpty()) {
            db.analysisDao().getById(analysisId)?.let { entidad ->
                db.analysisDao().update(entidad.copy(modulesUsed = modulosUsados.joinToString(",")))
            }
        }
    }

    /**
     * [6] Dinámico (F6): instrumentación con frida-server, si el módulo
     * está instalado. A diferencia de run(), esto hay que llamarlo a
     * propósito desde la UI (botón explícito, con el aviso de root) — no
     * desde AnalysisWorker, porque lanza la app analizada en primer plano
     * y tarda un rato fijo. Ver FridaController.kt (modules-src/
     * frida-wrapper) para el porqué de que esto pueda fallar con
     * normalidad incluso con el módulo bien instalado.
     */
    suspend fun runDynamicAnalysis(analysisId: String, apkPath: String): AnalyzerModule.ModuleResult? {
        if (!moduleManager.isInstalled("frida-server")) {
            return null
        }
        val workDir = File(context.filesDir, "analysis/$analysisId").apply { mkdirs() }
        val apkFile = File(apkPath)
        val resultado = runModuleSafely("frida-server", apkFile, workDir, analysisId) ?: return null

        try {
            val json = JSONObject(resultado.jsonOutput)
            val eventosArray = json.optJSONArray("events")
            if (eventosArray != null) {
                val eventos = (0 until eventosArray.length()).mapNotNull { i ->
                    val e = eventosArray.optJSONObject(i) ?: return@mapNotNull null
                    com.carpe.panoptes.core.db.RuntimeEventEntity(
                        analysisId = analysisId,
                        timestamp = System.currentTimeMillis(),
                        type = e.optString("type", "unknown"),
                        payload = e.toString()
                    )
                }
                if (eventos.isNotEmpty()) db.resultsDao().insertRuntimeEvents(eventos)

                // Correlación estático-dinámica (§6.1 [7]): si una URL vista en
                // runtime coincide con un endpoint ya detectado estáticamente,
                // lo marcamos. Si no coincide ninguno, es una URL nueva — se
                // queda solo en runtime_event, no se inventa un EndpointEntity.
                eventosArray.let {
                    for (i in 0 until it.length()) {
                        val e = it.optJSONObject(i) ?: continue
                        if (e.optString("type") == "url") {
                            val url = e.optString("url")
                            if (url.isNotBlank()) db.resultsDao().marcarEndpointVistoEnRuntime(analysisId, url)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // jsonOutput puede ser un {"error": "..."} legítimo (ver
            // FridaController) — no es un fallo del orquestador.
        }

        return resultado
    }

    /**
     * Corre androguard y quark-engine de forma aislada: cualquier fallo
     * (de Python, de Chaquopy, lo que sea) no debe tumbar el resto del
     * pipeline — por eso esto nunca lanza y simplemente devuelve una
     * lista vacía o parcial de Findings si algo falla.
     */
    private fun runPythonSafely(apkPath: String, analysisId: String): List<FindingEntity> {
        val findings = mutableListOf<FindingEntity>()

        try {
            val json = JSONObject(pythonBridge.runAndroguard(apkPath))
            if (json.has("error")) {
                findings.add(FindingEntity(analysisId = analysisId, category = "androguard", severity = Severity.INFO, description = "androguard falló: ${json.getString("error")}"))
            } else {
                val v1 = json.optBoolean("is_signed_v1", false)
                val v2 = json.optBoolean("is_signed_v2", false)
                val v3 = json.optBoolean("is_signed_v3", false)
                if (v1 && !v2 && !v3) {
                    findings.add(
                        FindingEntity(
                            analysisId = analysisId,
                            category = "signing",
                            severity = Severity.MEDIUM,
                            description = "El APK solo está firmado con el esquema v1 (sin v2/v3) — más débil, susceptible a ataques conocidos de modificación de APK",
                            evidence = json.toString()
                        )
                    )
                }
                val declarados = json.optJSONArray("permissions_declared")?.length() ?: 0
                val solicitados = json.optJSONArray("permissions_requested")?.length() ?: 0
                if (declarados > solicitados) {
                    findings.add(
                        FindingEntity(
                            analysisId = analysisId,
                            category = "androguard",
                            severity = Severity.INFO,
                            description = "$declarados permiso(s) custom declarados por la propia app (vs $solicitados solicitados del sistema)",
                            evidence = json.toString()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            findings.add(FindingEntity(analysisId = analysisId, category = "androguard", severity = Severity.INFO, description = "androguard no disponible: ${e.message}"))
        }

        try {
            val json = JSONObject(pythonBridge.runQuark(apkPath))
            when {
                json.optBoolean("skipped", false) -> {
                    // Esperado mientras no haya assets/quark-rules.zip empaquetado — no es un error.
                }
                json.has("error") -> {
                    findings.add(FindingEntity(analysisId = analysisId, category = "quark", severity = Severity.INFO, description = "quark-engine falló: ${json.getString("error")}"))
                }
                else -> {
                    val matched = json.optInt("rules_matched", 0)
                    if (matched > 0) {
                        findings.add(
                            FindingEntity(
                                analysisId = analysisId,
                                category = "quark",
                                severity = Severity.MEDIUM,
                                description = "quark-engine: $matched regla(s) de comportamiento sospechoso coincidieron",
                                evidence = json.toString()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            findings.add(FindingEntity(analysisId = analysisId, category = "quark", severity = Severity.INFO, description = "quark-engine no disponible: ${e.message}"))
        }

        return findings
    }

    /**
     * Lista las .so del APK sin analizarlas (§6.2: "siempre disponible en
     * el shell"). El análisis real de imports/exports/strings de cada una
     * es lo que añade el módulo radare2, vía runModuleSafely más arriba.
     */
    private fun listarLibsNativas(apkFile: File, analysisId: String): List<NativeLibEntity> {
        val libs = mutableListOf<NativeLibEntity>()
        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val partes = entry.name.split("/") // lib/<abi>/<nombre>.so
                    val abi = partes.getOrNull(1) ?: "desconocido"
                    val nombre = partes.lastOrNull() ?: entry.name
                    libs.add(NativeLibEntity(analysisId = analysisId, name = nombre, arch = abi))
                }
        }
        return libs
    }

    /**
     * Carga y ejecuta un módulo de forma aislada: si falla (módulo corrupto,
     * API incompatible, lo que sea) el resto del pipeline sigue sin él.
     * Devuelve el ModuleResult si tuvo éxito, o null si falló.
     */
    private suspend fun runModuleSafely(
        id: String,
        apkFile: File,
        workDir: File,
        analysisId: String
    ): AnalyzerModule.ModuleResult? {
        return try {
            val modulo = moduleManager.load(id)
            val outputDir = File(workDir, "modules/$id").apply { mkdirs() }
            val listener = object : AnalyzerModule.ProgressListener {
                override fun onProgress(phase: String, percent: Int) {}
                override fun onLog(line: String) {}
            }
            val resultado = modulo.analyze(apkFile.absolutePath, outputDir, listener)
            if (resultado.success) {
                db.resultsDao().insertFindings(listOf(
                    FindingEntity(
                        analysisId = analysisId,
                        category = "module:$id",
                        severity = Severity.INFO,
                        description = "Módulo '$id' ejecutado correctamente (${resultado.outputFiles.size} archivo(s) generado(s))",
                        evidence = resultado.jsonOutput
                    )
                ))
                resultado
            } else {
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // no tragarse la cancelación de la corrutina
        } catch (e: Exception) {
            null
        } finally {
            moduleManager.unload(id)
        }
    }
}
