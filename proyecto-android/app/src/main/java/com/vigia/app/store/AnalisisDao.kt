package com.vigia.app.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalisisDao {
    // ---- hallazgos ----
    @Insert
    suspend fun insertarFindings(lista: List<FindingEntity>)

    @Query("DELETE FROM findings WHERE session_id = :sesion")
    suspend fun borrarFindings(sesion: Long)

    @Query(
        "SELECT * FROM findings WHERE session_id = :sesion ORDER BY " +
            "CASE severidad WHEN 'critica' THEN 0 WHEN 'alta' THEN 1 WHEN 'media' THEN 2 WHEN 'baja' THEN 3 ELSE 4 END, ts DESC"
    )
    fun observarFindings(sesion: Long): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE session_id = :sesion")
    suspend fun listaFindings(sesion: Long): List<FindingEntity>

    @Query("SELECT * FROM findings WHERE id = :id")
    suspend fun finding(id: Long): FindingEntity?

    @Query("SELECT COUNT(*) FROM findings WHERE session_id = :sesion AND severidad != 'info'")
    suspend fun contarFindings(sesion: Long): Int

    @Query("SELECT COUNT(*) FROM findings WHERE session_id = :sesion AND (severidad = 'alta' OR severidad = 'critica')")
    suspend fun contarFindingsAltos(sesion: Long): Int

    // ---- indicadores ----
    @Insert
    suspend fun insertarIocs(lista: List<IocEntity>)

    @Query("DELETE FROM iocs WHERE fuente = :fuente")
    suspend fun borrarIocsDeFuente(fuente: String)

    @Query("DELETE FROM iocs")
    suspend fun borrarTodosIocs()

    @Query("SELECT * FROM iocs")
    suspend fun todosIocs(): List<IocEntity>

    @Query("SELECT COUNT(*) FROM iocs")
    suspend fun contarIocs(): Int

    @Query("SELECT fuente AS fuente, COUNT(*) AS n FROM iocs GROUP BY fuente ORDER BY fuente")
    suspend fun iocsPorFuente(): List<FuenteConteo>

    // ---- línea base ----
    @Insert
    suspend fun insertarMuestras(lista: List<BaselineSampleEntity>)

    @Query("DELETE FROM baseline_samples WHERE session_id = :sesion")
    suspend fun borrarMuestrasDeSesion(sesion: Long)

    @Query("DELETE FROM baseline_samples WHERE dia < :minDia")
    suspend fun borrarMuestrasAntiguas(minDia: Long)

    @Query("SELECT metrica AS metrica, hora AS hora, valor AS valor FROM baseline_samples WHERE dia >= :minDia AND session_id != :sesion")
    suspend fun muestrasExcepto(minDia: Long, sesion: Long): List<MuestraFila>

    @Query("SELECT metrica AS metrica, hora AS hora, valor AS valor FROM baseline_samples WHERE dia >= :minDia")
    suspend fun todasMuestras(minDia: Long): List<MuestraFila>

    @Query("SELECT metrica AS metrica, hora AS hora, valor AS valor FROM baseline_samples WHERE session_id = :sesion")
    suspend fun muestrasDeSesion(sesion: Long): List<MuestraFila>

    @Query("SELECT COUNT(*) FROM baseline_samples")
    suspend fun contarMuestras(): Int

    @Query("DELETE FROM baseline_samples")
    suspend fun borrarTodasMuestras()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarBaseline(lista: List<BaselineEntity>)

    @Query("SELECT * FROM baseline ORDER BY metrica, ventana")
    suspend fun todaBaseline(): List<BaselineEntity>

    @Query("DELETE FROM baseline")
    suspend fun borrarBaseline()

    // ---- dominios vistos ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarDominiosVistos(lista: List<DominioVistoEntity>)

    @Query("SELECT dominio FROM dominios_vistos")
    suspend fun dominiosVistos(): List<String>

    @Query("SELECT COUNT(*) FROM dominios_vistos")
    suspend fun contarDominiosVistos(): Int

    @Query("DELETE FROM dominios_vistos")
    suspend fun borrarDominiosVistos()
}
