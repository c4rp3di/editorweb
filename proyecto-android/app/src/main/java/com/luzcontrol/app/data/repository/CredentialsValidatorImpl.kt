package com.luzcontrol.app.data.repository

import com.luzcontrol.app.data.remote.DatadisClient
import com.luzcontrol.app.domain.model.AppError
import com.luzcontrol.app.domain.model.DatadisCredentials
import com.luzcontrol.app.domain.repository.CredentialsValidator
import javax.inject.Inject

class CredentialsValidatorImpl @Inject constructor(
    private val client: DatadisClient
) : CredentialsValidator {
    /** Comprueba el login (no consume consultas de datos). El Int es 0 y se mantiene por compatibilidad. */
    override suspend fun validate(credentials: DatadisCredentials): Result<Int> = try {
        client.validate(credentials)
        Result.success(0)
    } catch (e: AppError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AppError.Unknown(e))
    }
}
