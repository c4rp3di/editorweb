package com.luzcontrol.app.data.remote.api

import okhttp3.ResponseBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * Datadis. Las respuestas se devuelven en bruto (ResponseBody) y se decodifican de forma tolerante
 * en [com.luzcontrol.app.data.remote.DatadisClient], porque la API devuelve tanto arrays (v1)
 * como objetos envoltorio (v2) y mensajes de error con código 200.
 */
interface DatadisApi {
    @FormUrlEncoded
    @POST("nikola-auth/tokens/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): ResponseBody

    @GET
    suspend fun get(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @QueryMap params: Map<String, String>
    ): ResponseBody
}

/** ESIOS (REE) con token personal. */
interface EsiosApi {
    @GET("indicators/{id}")
    suspend fun indicator(
        @retrofit2.http.Path("id") id: Int,
        @Header("x-api-key") apiKey: String,
        @Header("Accept") accept: String = "application/json; application/vnd.esios-api-v1+json",
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("geo_ids[]") geoId: Int = 8741
    ): ResponseBody
}

/** apidatos.ree.es: endpoint público, sin token. */
interface ReeApi {
    @GET("es/datos/mercados/precios-mercados-tiempo-real")
    suspend fun realTimePrices(
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("time_trunc") timeTrunc: String = "hour"
    ): ResponseBody
}
