package com.luzcontrol.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class DatadisSupplyDto(
    val address: String? = null,
    val cups: String,
    val postalCode: String? = null,
    val province: String? = null,
    val municipality: String? = null,
    val distributor: String? = null,
    val validDateFrom: String? = null,
    val validDateTo: String? = null,
    val pointType: Int? = null,
    val distributorCode: String? = null
)

@Serializable
data class DatadisContractDto(
    val cups: String? = null,
    val distributor: String? = null,
    val marketer: String? = null,
    val tension: String? = null,
    val accesFare: String? = null,
    val codeFare: String? = null,
    val contractedPowerkW: List<Double>? = null,
    val timeDiscrimination: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val distributorCode: String? = null
)

@Serializable
data class DatadisConsumptionDto(
    val cups: String? = null,
    val date: String? = null,
    val time: String? = null,
    val consumptionKWh: Double? = null,
    val obtainMethod: String? = null,
    val surplusEnergyKWh: Double? = null,
    val generationEnergyKWh: Double? = null,
    val selfConsumptionEnergyKWh: Double? = null
)

@Serializable
data class DatadisMaxPowerDto(
    val cups: String? = null,
    val date: String? = null,
    val time: String? = null,
    val maxPower: Double? = null,
    val period: String? = null
)

// ---------- ESIOS ----------

@Serializable
data class EsiosResponseDto(val indicator: EsiosIndicatorDto? = null)

@Serializable
data class EsiosIndicatorDto(val values: List<EsiosPriceDto> = emptyList())

@Serializable
data class EsiosPriceDto(
    val value: Double? = null,
    val datetime: String? = null,
    @kotlinx.serialization.SerialName("geo_id") val geoId: Long? = null
)

// ---------- REE apidatos (sin token) ----------

@Serializable
data class ReeResponseDto(val included: List<ReeSeriesDto> = emptyList())

@Serializable
data class ReeSeriesDto(
    val id: String? = null,
    val type: String? = null,
    val attributes: ReeAttributesDto? = null
)

@Serializable
data class ReeAttributesDto(
    val title: String? = null,
    val values: List<ReeValueDto> = emptyList()
)

@Serializable
data class ReeValueDto(val value: Double? = null, val datetime: String? = null)
