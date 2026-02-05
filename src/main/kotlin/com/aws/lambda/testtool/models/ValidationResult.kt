package com.aws.lambda.testtool.models

/**
 * Result of validating a Lambda project's prerequisites.
 */
data class ValidationResult(
    val isValid: Boolean,
    val warnings: List<ValidationWarning>,
    val errors: List<ValidationError>
) {
    val hasWarnings: Boolean
        get() = warnings.isNotEmpty()
    
    val hasErrors: Boolean
        get() = errors.isNotEmpty()
    
    companion object {
        fun success(): ValidationResult = ValidationResult(
            isValid = true,
            warnings = emptyList(),
            errors = emptyList()
        )
        
        fun withWarnings(warnings: List<ValidationWarning>): ValidationResult = ValidationResult(
            isValid = true,
            warnings = warnings,
            errors = emptyList()
        )
        
        fun withErrors(errors: List<ValidationError>): ValidationResult = ValidationResult(
            isValid = false,
            warnings = emptyList(),
            errors = errors
        )
    }
}

/**
 * Represents a validation warning (non-blocking issue).
 */
data class ValidationWarning(
    val type: ValidationWarningType,
    val message: String,
    val suggestion: String? = null
)

/**
 * Types of validation warnings.
 */
enum class ValidationWarningType {
    OUTPUT_TYPE_NOT_EXE,
    MISSING_PACKAGE,
    BUILD_OUTPUT_NOT_FOUND,
    PORT_IN_USE,
    TEST_TOOL_NOT_INSTALLED,
    HANDLER_NOT_FOUND
}

/**
 * Represents a validation error (blocking issue).
 */
data class ValidationError(
    val type: ValidationErrorType,
    val message: String
)

/**
 * Types of validation errors.
 */
enum class ValidationErrorType {
    PROJECT_FILE_NOT_FOUND,
    INVALID_PROJECT_FILE,
    DOTNET_NOT_INSTALLED
}
