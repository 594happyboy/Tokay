package com.happyboy.tokay.installer.validator

import java.io.File

sealed class ValidationResult {
    data class Valid(val file: File) : ValidationResult()

    data class Invalid(
        val file: File,
        val reason: String,
        val error: Throwable? = null
    ) : ValidationResult()

    data class NotFound(val file: File) : ValidationResult()

    fun isValid(): Boolean = this is Valid
}
