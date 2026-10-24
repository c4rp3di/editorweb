package com.perplexus.game.levels

/** Origen de datos de niveles (assets, almacenamiento externo, tests…). */
interface LevelSource {
    /** Nombres de archivo de niveles, en orden. */
    fun listLevelFiles(): List<String>
    fun readLevel(file: String): String
}

/** Carga y valida niveles. Lógica pura (la lectura real la hace un LevelSource). */
object LevelLoader {
    class LevelLoadException(msg: String) : Exception(msg)

    class LoadReport(@JvmField val levels: List<Level>, @JvmField val problems: List<String>)

    /** Parsea un nivel y lo valida; lanza excepción si no es jugable. */
    fun load(json: String, validate: Boolean = true): Level {
        val level = try {
            LevelFormat.parse(json)
        } catch (e: LevelFormat.FormatException) {
            throw LevelLoadException("Formato de nivel inválido: ${e.message}")
        }
        if (validate) {
            val r = LevelValidator.validate(level)
            if (!r.ok) throw LevelLoadException("Nivel '${level.id}' no jugable: ${r.errors.joinToString("; ")}")
        }
        return level
    }

    /** Carga todos los niveles de un origen; los inválidos se omiten y se reportan. Orden por 'order'. */
    fun loadAll(source: LevelSource, validate: Boolean = true): LoadReport {
        val levels = ArrayList<Level>()
        val problems = ArrayList<String>()
        for (f in source.listLevelFiles()) {
            try {
                levels.add(load(source.readLevel(f), validate))
            } catch (e: Exception) {
                problems.add("$f: ${e.message}")
            }
        }
        levels.sortBy { it.order }
        return LoadReport(levels, problems)
    }

    /** Lee un índice {"levels":["a.json","b.json"]}. */
    fun parseIndex(indexJson: String): List<String> {
        val root = Json.parseObject(indexJson)
        val arr = Json.arr(root, "levels") ?: return emptyList()
        return arr.mapNotNull { it as? String }
    }
}
