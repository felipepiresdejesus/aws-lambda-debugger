package com.aws.lambda.testtool.utils

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures process output (stdout and stderr) to a file for debugging purposes.
 *
 * This is useful for diagnosing Lambda startup failures when error messages
 * aren't visible in the IDE console.
 */
object ProcessOutputLogger {

    private val LOG = Logger.getInstance(ProcessOutputLogger::class.java)
    private val logWriters = ConcurrentHashMap<ProcessHandler, FileWriter>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

    /**
     * Attaches output logging to a process handler.
     *
     * Creates a log file at projectRootPath/.logs/lambda-[timestamp].log
     * and captures all process output to it.
     *
     * @param processHandler The process handler to monitor
     * @param projectRootPath The root path of the project (for log file location)
     * @param projectName The name of the project (for log file naming)
     * @return The path to the created log file
     */
    fun attachLogging(
        processHandler: ProcessHandler,
        projectRootPath: String,
        projectName: String
    ): String {
        try {
            // Create .logs directory
            val logsDir = File(projectRootPath, ".logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
                LOG.info("Created logs directory: ${logsDir.absolutePath}")
            }

            // Create log file with timestamp
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())
            val logFile = File(logsDir, "lambda-${projectName}-${timestamp}.log")

            LOG.info("Creating process output log file: ${logFile.absolutePath}")

            // Create file writer
            val writer = FileWriter(logFile, true) // append mode

            // Write header
            writer.append("=== Lambda Process Output Log ===\n")
            writer.append("Project: $projectName\n")
            writer.append("Started: ${Date()}\n")
            writer.append("===================================\n\n")
            writer.flush()

            // Store the writer
            logWriters[processHandler] = writer

            // Attach process listener to monitor process lifecycle and capture any output events
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun startNotified(event: ProcessEvent) {
                    try {
                        val timestamp = dateFormat.format(Date())
                        writer.append("[$timestamp] [START] Process started\n")
                        writer.flush()
                    } catch (e: Exception) {
                        LOG.warn("Failed to write process start event", e)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    try {
                        val timestamp = dateFormat.format(Date())
                        writer.append("\n[$timestamp] [TERMINATED] Process terminated with exit code: ${event.exitCode}\n")
                        writer.append("===================================\n")
                        writer.flush()
                        writer.close()
                        logWriters.remove(processHandler)
                        LOG.info("Process output logging finished for: ${logFile.absolutePath}")
                    } catch (e: Exception) {
                        LOG.warn("Failed to finalize process output log", e)
                        try {
                            writer.close()
                        } catch (closeException: Exception) {
                            LOG.warn("Failed to close log writer", closeException)
                        }
                        logWriters.remove(processHandler)
                    }
                }
            })

            return logFile.absolutePath

        } catch (e: Exception) {
            LOG.error("Failed to setup process output logging", e)
            throw e
        }
    }

    /**
     * Closes any open log writers (cleanup method).
     */
    fun closeAllLogWriters() {
        logWriters.forEach { (_, writer) ->
            try {
                writer.close()
            } catch (e: Exception) {
                LOG.warn("Failed to close log writer during cleanup", e)
            }
        }
        logWriters.clear()
    }
}
