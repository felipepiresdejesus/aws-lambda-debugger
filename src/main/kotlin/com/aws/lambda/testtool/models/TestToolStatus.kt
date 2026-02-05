package com.aws.lambda.testtool.models

/**
 * Represents the current status of the Lambda Test Tool.
 */
data class TestToolStatus(
    val state: TestToolState,
    val port: Int?,
    val message: String?,
    val processId: Long? = null
) {
    companion object {
        fun notRunning(): TestToolStatus = TestToolStatus(
            state = TestToolState.NOT_RUNNING,
            port = null,
            message = "Test Tool is not running"
        )
        
        fun starting(port: Int): TestToolStatus = TestToolStatus(
            state = TestToolState.STARTING,
            port = port,
            message = "Starting Test Tool on port $port..."
        )
        
        fun running(port: Int, processId: Long): TestToolStatus = TestToolStatus(
            state = TestToolState.RUNNING,
            port = port,
            message = "Test Tool running on port $port",
            processId = processId
        )
        
        fun stopping(): TestToolStatus = TestToolStatus(
            state = TestToolState.STOPPING,
            port = null,
            message = "Stopping Test Tool..."
        )
        
        fun error(message: String): TestToolStatus = TestToolStatus(
            state = TestToolState.ERROR,
            port = null,
            message = message
        )
    }
}

/**
 * States of the Lambda Test Tool process.
 */
enum class TestToolState {
    NOT_RUNNING,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * Represents the status of a Lambda function execution.
 */
data class LambdaExecutionStatus(
    val state: LambdaExecutionState,
    val projectName: String?,
    val message: String?,
    val processId: Long? = null
) {
    companion object {
        fun notRunning(): LambdaExecutionStatus = LambdaExecutionStatus(
            state = LambdaExecutionState.NOT_RUNNING,
            projectName = null,
            message = "Lambda function is not running"
        )
        
        fun starting(projectName: String): LambdaExecutionStatus = LambdaExecutionStatus(
            state = LambdaExecutionState.STARTING,
            projectName = projectName,
            message = "Starting Lambda function: $projectName"
        )
        
        fun running(projectName: String, processId: Long): LambdaExecutionStatus = LambdaExecutionStatus(
            state = LambdaExecutionState.RUNNING,
            projectName = projectName,
            message = "Lambda function running: $projectName",
            processId = processId
        )
        
        fun debugging(projectName: String, processId: Long): LambdaExecutionStatus = LambdaExecutionStatus(
            state = LambdaExecutionState.DEBUGGING,
            projectName = projectName,
            message = "Debugging Lambda function: $projectName",
            processId = processId
        )
        
        fun error(message: String): LambdaExecutionStatus = LambdaExecutionStatus(
            state = LambdaExecutionState.ERROR,
            projectName = null,
            message = message
        )
    }
}

/**
 * States of the Lambda function execution.
 */
enum class LambdaExecutionState {
    NOT_RUNNING,
    STARTING,
    RUNNING,
    DEBUGGING,
    ERROR
}
