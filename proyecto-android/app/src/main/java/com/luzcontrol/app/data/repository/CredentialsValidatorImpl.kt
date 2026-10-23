package com.luzcontrol.app.data.repository

import com.luzcontrol.app.data.remote.DatadisClient
import com.luzcontrol.app.domain.model.AppError
import com.luzcontrol.app.domain.model.DatadisCredentials
import com.luzcontrol.app.domain.repository.CredentialsValidator
import javax.inject.Inject

class CredentialsValidatorImpl @Inject constructor(
    private val client: DatadisClient
) : CredentialsValidator {
    /** Devuelve el número de suministros encontrados. */
    override suspend fun validate(credentials: DatadisCredentials): Result<Int> = try {
        Result.success(client.validate(credentials))
    } catch (e: AppError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AppError.Unknown(e))
    }
}
