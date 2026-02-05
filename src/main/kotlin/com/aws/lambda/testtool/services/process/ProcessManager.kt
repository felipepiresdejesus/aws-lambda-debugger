package com.aws.lambda.testtool.services.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Manages process creation and lifecycle.
 * Abstracts platform-specific process creation logic.
 */
interface ProcessManager {
    /**
     * Creates a process that will be registered with the IDE.
     */
    fun createRegisteredProcess(commandLine: GeneralCommandLine): OSProcessHandler
    
    /**
     * Creates a detached process that won't be tracked by the IDE.
     * Returns the PID of the created process.
     */
    fun createDetachedProcess(commandLine: GeneralCommandLine, workingDirectory: File?): Long
    
    /**
     * Checks if a process with the given PID is alive.
     */
    fun isProcessAlive(pid: Long): Boolean
    
    /**
     * Kills a process by PID.
     */
    fun killProcess(pid: Long, force: Boolean = false)
}

/**
 * Platform-aware implementation of ProcessManager.
 */
class PlatformProcessManager : ProcessManager {
    
    private val LOG = Logger.getInstance(PlatformProcessManager::class.java)
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    
    override fun createRegisteredProcess(commandLine: GeneralCommandLine): OSProcessHandler {
        return OSProcessHandler(commandLine)
    }
    
    override fun createDetachedProcess(commandLine: GeneralCommandLine, workingDirectory: File?): Long {
        return if (isWindows) {
            createDetachedProcessWindows(commandLine, workingDirectory)
        } else {
            createDetachedProcessUnix(commandLine, workingDirectory)
        }
    }
    
    private fun createDetachedProcessWindows(commandLine: GeneralCommandLine, workingDirectory: File?): Long {
        // On Windows, use start with a title to help identify the process
        // Note: This sets the window title, which may help in some process viewers
        // Using name without spaces to avoid truncation
        val processName = "LambdaTestTool"
        val detachedCommandLine = GeneralCommandLine("cmd", "/c", "start", "\"$processName\"", "/B")
            .apply {
                addParameters(commandLine.parametersList.list)
                workingDirectory?.let { withWorkDirectory(it) }
            }
        
        LOG.info("Starting detached process with name: $processName (Windows)")
        LOG.debug("Command: ${detachedCommandLine.commandLineString}")
        
        val process = detachedCommandLine.createProcess()
        return process.pid()
    }
    
    private fun createDetachedProcessUnix(commandLine: GeneralCommandLine, workingDirectory: File?): Long {
        val workDir = workingDirectory?.absolutePath ?: System.getProperty("user.dir")
        val toolCommand = commandLine.exePath
        val args = commandLine.parametersList.list.joinToString(" ") { "'$it'" }
        
        // Use exec with a descriptive name for the process
        // This makes it easier to identify in process lists (ps, top, etc.)
        // Note: Using name without spaces to avoid truncation in IDEs that might truncate at spaces
        val processName = "LambdaTestTool"
        val shellCommand = "cd '$workDir' && exec -a '$processName' nohup $toolCommand $args > /dev/null 2>&1 & echo \$!"
        
        LOG.info("Starting detached process with name: $processName")
        LOG.debug("Shell command: $shellCommand")
        
        val shellCommandLine = GeneralCommandLine("sh", "-c", shellCommand)
        val shellProcess = shellCommandLine.createProcess()
        
        val pidOutput = try {
            shellProcess.inputStream.bufferedReader().readLine()?.trim() ?: ""
        } catch (e: Exception) {
            LOG.warn("Could not read PID from shell output", e)
            ""
        }
        
        // Consume stderr in background to avoid blocking
        Thread {
            try {
                shellProcess.errorStream.bufferedReader().readText()
            } catch (e: Exception) {
                // Ignore
            }
        }.start()
        
        return if (pidOutput.isNotEmpty() && pidOutput.all { it.isDigit() }) {
            pidOutput.toLong()
        } else {
            // Fallback: find process by port if command includes port
            findProcessByPort(commandLine) ?: -1L
        }
    }
    
    private fun findProcessByPort(commandLine: GeneralCommandLine): Long? {
        val portIndex = commandLine.parametersList.list.indexOf("-p")
        if (portIndex >= 0 && portIndex < commandLine.parametersList.list.size - 1) {
            val port = commandLine.parametersList.list[portIndex + 1]
            return try {
                val lsofCommand = GeneralCommandLine("lsof", "-ti", ":$port")
                val lsofProcess = lsofCommand.createProcess()
                val pidOutput = lsofProcess.inputStream.bufferedReader().readText().trim()
                lsofProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                
                if (pidOutput.isNotEmpty() && pidOutput.all { it.isDigit() }) {
                    pidOutput.toLong()
                } else {
                    null
                }
            } catch (e: Exception) {
                LOG.warn("Failed to find process by port", e)
                null
            }
        }
        return null
    }
    
    override fun isProcessAlive(pid: Long): Boolean {
        if (pid <= 0) return false
        
        return try {
            java.lang.ProcessHandle.of(pid).isPresent
        } catch (e: Exception) {
            // Fallback: use ps command
            try {
                val psProcess = Runtime.getRuntime().exec(arrayOf("ps", "-p", "$pid"))
                psProcess.waitFor() == 0
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    override fun killProcess(pid: Long, force: Boolean) {
        if (pid <= 0) {
            LOG.warn("Invalid PID for killProcess: $pid")
            return
        }
        
        try {
            val commandArray = if (isWindows) {
                if (force) arrayOf("taskkill", "/F", "/PID", "$pid") else arrayOf("taskkill", "/PID", "$pid")
            } else {
                if (force) arrayOf("kill", "-9", "$pid") else arrayOf("kill", "$pid")
            }
            
            val command = commandArray.joinToString(" ")
            LOG.info("Executing kill command: $command")
            val process = Runtime.getRuntime().exec(commandArray)
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                val errorOutput = process.errorStream.bufferedReader().readText()
                LOG.warn("Kill command returned non-zero exit code: $exitCode. Error: $errorOutput")
                // Don't throw exception for non-zero exit code - process might already be dead
                if (exitCode != 1 || !errorOutput.contains("No such process", ignoreCase = true)) {
                    // Only throw if it's not a "process not found" error
                    throw ProcessManagementException("Kill command failed with exit code $exitCode: $errorOutput")
                }
            } else {
                LOG.info("Successfully killed process $pid (force=$force)")
            }
        } catch (e: ProcessManagementException) {
            // Re-throw our custom exception
            throw e
        } catch (e: Exception) {
            LOG.error("Failed to kill process $pid", e)
            throw ProcessManagementException("Failed to kill process $pid", e)
        }
    }
}

/**
 * Exception thrown when process management operations fail.
 */
class ProcessManagementException(message: String, cause: Throwable? = null) : Exception(message, cause)
