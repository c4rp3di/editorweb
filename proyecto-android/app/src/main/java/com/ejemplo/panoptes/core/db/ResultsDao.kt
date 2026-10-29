package com.carpe.panoptes.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ResultsDao {

    @Insert
    suspend fun insertPermissions(items: List<PermissionEntity>)

    @Insert
    suspend fun insertComponents(items: List<ComponentEntity>)

    @Insert
    suspend fun insertEndpoints(items: List<EndpointEntity>)

    @Insert
    suspend fun insertSecrets(items: List<SecretEntity>)

    @Insert
    suspend fun insertNativeLibs(items: List<NativeLibEntity>)

    @Insert
    suspend fun insertFindings(items: List<FindingEntity>)

    @Insert
    suspend fun insertRuntimeEvents(items: List<RuntimeEventEntity>)

    @Query("SELECT * FROM runtime_event WHERE analysisId = :analysisId ORDER BY timestamp ASC")
    suspend fun runtimeEventsFor(analysisId: String): List<RuntimeEventEntity>

    /** F6: correlación estático-dinámica (§6.1 [7]) — marca como visto en runtime un endpoint que ya conocíamos de forma estática. */
    @Query("UPDATE endpoint SET calledInRuntime = 1 WHERE analysisId = :analysisId AND url = :url")
    suspend fun marcarEndpointVistoEnRuntime(analysisId: String, url: String)

    @Query("SELECT * FROM permission WHERE analysisId = :analysisId")
    suspend fun permissionsFor(analysisId: String): List<PermissionEntity>

    @Query("SELECT * FROM component WHERE analysisId = :analysisId")
    suspend fun componentsFor(analysisId: String): List<ComponentEntity>

    @Query("SELECT * FROM endpoint WHERE analysisId = :analysisId ORDER BY confidence DESC")
    suspend fun endpointsFor(analysisId: String): List<EndpointEntity>

    @Query("SELECT * FROM secret WHERE analysisId = :analysisId ORDER BY entropy DESC")
    suspend fun secretsFor(analysisId: String): List<SecretEntity>

    @Query("SELECT * FROM native_lib WHERE analysisId = :analysisId")
    suspend fun nativeLibsFor(analysisId: String): List<NativeLibEntity>

    @Query("SELECT * FROM finding WHERE analysisId = :analysisId ORDER BY severity DESC")
    suspend fun findingsFor(analysisId: String): List<FindingEntity>

    // Búsqueda global vía FTS4 (§7 del plan)
    @Query("""
        SELECT endpoint.* FROM endpoint
        JOIN endpoint_fts ON endpoint.id = endpoint_fts.rowid
        WHERE endpoint_fts MATCH :query
    """)
    suspend fun searchEndpoints(query: String): List<EndpointEntity>

    @Query("""
        SELECT secret.* FROM secret
        JOIN secret_fts ON secret.id = secret_fts.rowid
        WHERE secret_fts MATCH :query
    """)
    suspend fun searchSecrets(query: String): List<SecretEntity>

    @Query("""
        SELECT finding.* FROM finding
        JOIN finding_fts ON finding.id = finding_fts.rowid
        WHERE finding_fts MATCH :query
    """)
    suspend fun searchFindings(query: String): List<FindingEntity>
}
