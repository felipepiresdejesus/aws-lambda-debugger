package com.aws.lambda.testtool.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for managing the installation of dotnet-lambda-test-tool.
 */
@Service(Service.Level.PROJECT)
class TestToolInstaller(private val project: Project) {
    
    private val LOG = Logger.getInstance(TestToolInstaller::class.java)
    private val isInstalling = AtomicBoolean(false)
    
    companion object {
        const val TOOL_PACKAGE_NAME = "Amazon.Lambda.TestTool"
        const val TOOL_COMMAND = "dotnet-lambda-test-tool"
        
        fun getInstance(project: Project): TestToolInstaller {
            return project.getService(TestToolInstaller::class.java)
        }
    }
    
    /**
     * Checks if the dotnet-lambda-test-tool is installed.
     */
    fun isTestToolInstalled(): Boolean {
        return try {
            val commandLine = GeneralCommandLine("dotnet", "tool", "list", "-g")
            val process = commandLine.createProcess()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            output.contains(TOOL_PACKAGE_NAME, ignoreCase = true)
        } catch (e: Exception) {
            LOG.warn("Failed to check if test tool is installed", e)
            false
        }
    }
    
    /**
     * Gets the version of the installed test tool.
     */
    fun getInstalledVersion(): String? {
        return try {
            val commandLine = GeneralCommandLine("dotnet", "tool", "list", "-g")
            val process = commandLine.createProcess()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            // Parse the output to find the version
            output.lines()
                .find { it.contains(TOOL_PACKAGE_NAME, ignoreCase = true) }
                ?.split("\\s+".toRegex())
                ?.getOrNull(1)
        } catch (e: Exception) {
            LOG.warn("Failed to get test tool version", e)
            null
        }
    }
    
    /**
     * Installs the dotnet-lambda-test-tool globally.
     */
    fun installTestTool(onComplete: (Boolean) -> Unit = {}) {
        if (isInstalling.get()) {
            LOG.info("Installation already in progress")
            return
        }
        
        isInstalling.set(true)
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Installing AWS Lambda Test Tool",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Installing $TOOL_PACKAGE_NAME..."
                indicator.isIndeterminate = true
                
                try {
                    val commandLine = GeneralCommandLine(
                        "dotnet", "tool", "install", "-g", TOOL_PACKAGE_NAME
                    )
                    
                    val processHandler = OSProcessHandler(commandLine)
                    val outputBuilder = StringBuilder()
                    
                    processHandler.addProcessListener(object : ProcessAdapter() {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            outputBuilder.append(event.text)
                            indicator.text2 = event.text.trim()
                        }
                    })
                    
                    processHandler.startNotify()
                    processHandler.waitFor()
                    
                    val exitCode = processHandler.exitCode ?: -1
                    val success = exitCode == 0
                    
                    if (success) {
                        showNotification(
                            "AWS Lambda Test Tool installed successfully",
                            NotificationType.INFORMATION
                        )
                    } else {
                        showNotification(
                            "Failed to install AWS Lambda Test Tool: $outputBuilder",
                            NotificationType.ERROR
                        )
                    }
                    
                    onComplete(success)
                    
                } catch (e: Exception) {
                    LOG.error("Failed to install test tool", e)
                    showNotification(
                        "Failed to install AWS Lambda Test Tool: ${e.message}",
                        NotificationType.ERROR
                    )
                    onComplete(false)
                } finally {
                    isInstalling.set(false)
                }
            }
        })
    }
    
    /**
     * Updates the dotnet-lambda-test-tool to the latest version.
     */
    fun updateTestTool(onComplete: (Boolean) -> Unit = {}) {
        if (isInstalling.get()) {
            LOG.info("Installation/update already in progress")
            return
        }
        
        isInstalling.set(true)
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Updating AWS Lambda Test Tool",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Updating $TOOL_PACKAGE_NAME..."
                indicator.isIndeterminate = true
                
                try {
                    val commandLine = GeneralCommandLine(
                        "dotnet", "tool", "update", "-g", TOOL_PACKAGE_NAME
                    )
                    
                    val processHandler = OSProcessHandler(commandLine)
                    val outputBuilder = StringBuilder()
                    
                    processHandler.addProcessListener(object : ProcessAdapter() {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            outputBuilder.append(event.text)
                            indicator.text2 = event.text.trim()
                        }
                    })
                    
                    processHandler.startNotify()
                    processHandler.waitFor()
                    
                    val exitCode = processHandler.exitCode ?: -1
                    val success = exitCode == 0
                    
                    if (success) {
                        showNotification(
                            "AWS Lambda Test Tool updated successfully",
                            NotificationType.INFORMATION
                        )
                    } else {
                        showNotification(
                            "Failed to update AWS Lambda Test Tool: $outputBuilder",
                            NotificationType.WARNING
                        )
                    }
                    
                    onComplete(success)
                    
                } catch (e: Exception) {
                    LOG.error("Failed to update test tool", e)
                    showNotification(
                        "Failed to update AWS Lambda Test Tool: ${e.message}",
                        NotificationType.ERROR
                    )
                    onComplete(false)
                } finally {
                    isInstalling.set(false)
                }
            }
        })
    }
    
    /**
     * Uninstalls the dotnet-lambda-test-tool.
     */
    fun uninstallTestTool(onComplete: (Boolean) -> Unit = {}) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Uninstalling AWS Lambda Test Tool",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Uninstalling $TOOL_PACKAGE_NAME..."
                indicator.isIndeterminate = true
                
                try {
                    val commandLine = GeneralCommandLine(
                        "dotnet", "tool", "uninstall", "-g", TOOL_PACKAGE_NAME
                    )
                    
                    val processHandler = OSProcessHandler(commandLine)
                    processHandler.startNotify()
                    processHandler.waitFor()
                    
                    val exitCode = processHandler.exitCode ?: -1
                    onComplete(exitCode == 0)
                    
                } catch (e: Exception) {
                    LOG.error("Failed to uninstall test tool", e)
                    onComplete(false)
                }
            }
        })
    }
    
    private fun showNotification(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AWS Lambda Test Tool")
            .createNotification(content, type)
            .notify(project)
    }
}
