package com.aws.lambda.testtool.exceptions

/**
 * Base exception for Lambda Test Tool operations.
 */
open class LambdaTestToolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the Lambda project cannot be found or is invalid.
 */
class LambdaProjectNotFoundException(message: String, cause: Throwable? = null) : LambdaTestToolException(message, cause)

/**
 * Thrown when the Test Tool fails to start.
 */
class TestToolStartException(message: String, cause: Throwable? = null) : LambdaTestToolException(message, cause)

/**
 * Thrown when the Test Tool is not responding.
 */
class TestToolNotRespondingException(message: String, cause: Throwable? = null) : LambdaTestToolException(message, cause)

/**
 * Thrown when debugger attachment fails.
 */
class DebuggerAttachmentException(message: String, cause: Throwable? = null) : LambdaTestToolException(message, cause)
