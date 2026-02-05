package com.aws.lambda.testtool.services.debugger

import com.intellij.execution.process.ProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import java.util.concurrent.TimeUnit

/**
 * Service for attaching debuggers to running processes.
 * Handles the complex reflection-based attachment to Rider's .NET debugger.
 */
interface DebuggerAttachmentService {
    /**
     * Attaches the debugger to a process with the given PID.
     * 
     * @param project The project context
     * @param pid The process ID to attach to
     * @param port The port the Lambda Test Tool is running on
     * @param autoOpenBrowser Whether to open the browser after successful attachment
     * @return true if attachment was successful, false otherwise
     */
    fun attachDebugger(project: Project, pid: Int, port: Int, autoOpenBrowser: Boolean): Boolean
}

/**
 * Implementation of DebuggerAttachmentService using Rider's XDebugger infrastructure.
 */
class RiderDebuggerAttachmentService : DebuggerAttachmentService {
    
    private val LOG = Logger.getInstance(RiderDebuggerAttachmentService::class.java)
    
    override fun attachDebugger(project: Project, pid: Int, port: Int, autoOpenBrowser: Boolean): Boolean {
        return ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
            try {
                LOG.info("Attempting to attach debugger to PID: $pid")
                val attached = tryXDebuggerAttach(project, pid)
                
                if (attached) {
                    if (autoOpenBrowser) {
                        ApplicationManager.getApplication().invokeLater {
                            openBrowser(port)
                        }
                    }
                    ApplicationManager.getApplication().invokeLater {
                        showNotification(project, "Debugger attached to Lambda (PID: $pid)", NotificationType.INFORMATION)
                    }
                    true
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        showNotification(
                            project,
                            "Lambda running (PID: $pid). Use Run → Attach to Process → .NET Core to debug with breakpoints.",
                            NotificationType.INFORMATION
                        )
                    }
                    false
                }
            } catch (e: Exception) {
                LOG.warn("Failed to attach debugger: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    showNotification(project, "Lambda running (PID: $pid). Use Run → Attach to Process to debug.", NotificationType.INFORMATION)
                }
                false
            }
        }.get(5, TimeUnit.SECONDS) ?: false
    }
    
    private fun tryXDebuggerAttach(project: Project, pid: Int): Boolean {
        // #region agent log
        
        return try {
            val localAttachHost = getLocalAttachHost()
            val processes = getProcessList(localAttachHost)
            
            // #region agent log
            
            // Filter out Test Tool processes before searching
            // Note: We filter by name, but the Lambda function should be named "LambdaFunction" (not "LambdaTestTool")
            val filteredProcesses = processes.filter { process ->
                val processName = getProcessName(process)
                val isTestTool = processName != null && (
                    processName.contains("LambdaTestTool", ignoreCase = true) ||
                    processName.contains("dotnet-lambda-test-tool", ignoreCase = true)
                )
                if (isTestTool) {
                    // #region agent log
                    LOG.debug("Filtering out Test Tool process: $processName")
                }
                !isTestTool
            }
            
            LOG.info("Searching for Lambda function process with PID: $pid (filtered ${processes.size - filteredProcesses.size} Test Tool processes)")
            val targetProcess = findProcessByPid(filteredProcesses, pid)
            
            if (targetProcess == null) {
                // #region agent log
                
                LOG.warn("Lambda function process $pid not found in process list (Test Tool processes were filtered out)")
                // Try to find it in the unfiltered list for debugging
                val unfilteredProcess = findProcessByPid(processes, pid)
                if (unfilteredProcess != null) {
                    val unfilteredName = getProcessName(unfilteredProcess)
                    // #region agent log
                    LOG.warn("Process $pid exists but was filtered out! Process name: $unfilteredName")
                    LOG.warn("This suggests the Lambda function process name contains 'LambdaTestTool' - this should not happen!")
                } else {
                    // #region agent log
                }
                return false
            }
            
            // Double-check we're not attaching to Test Tool
            val targetProcessName = getProcessName(targetProcess)
            // #region agent log
            
            if (targetProcessName != null && (
                targetProcessName.contains("LambdaTestTool", ignoreCase = true) ||
                targetProcessName.contains("dotnet-lambda-test-tool", ignoreCase = true)
            )) {
                LOG.error("CRITICAL: Attempted to attach debugger to Test Tool process (PID: $pid, name: $targetProcessName). This should never happen!")
                return false
            }
            
            LOG.info("Found Lambda function process: PID=$pid, name=$targetProcessName")
            
            val provider = findDotNetDebuggerProvider()
            // #region agent log
            
            if (provider == null) {
                LOG.info("No suitable .NET debugger provider found")
                return false
            }
            
            val debuggers = getAvailableDebuggers(provider, project, localAttachHost, targetProcess)
            // #region agent log
            
            if (debuggers.isEmpty()) {
                LOG.info("No debuggers available for this process")
                return false
            }
            
            LOG.info("Attaching debugger to Lambda function process (PID: $pid)")
            // #region agent log
            
            val attachResult = attachDebugSession(debuggers.first(), project, localAttachHost, targetProcess)
            
            // #region agent log
            
            attachResult
        } catch (e: Exception) {
            // #region agent log
            LOG.warn("XDebugger attach failed: ${e.message}", e)
            false
        }
    }
    
    private fun getLocalAttachHost(): Any {
        val localAttachHostClass = Class.forName("com.intellij.xdebugger.attach.LocalAttachHost")
        return localAttachHostClass.getField("INSTANCE").get(null)
    }
    
    private fun getProcessList(localAttachHost: Any): Collection<*> {
        val methods = listOf("getProcessList", "getProcesses", "refreshProcessList")
        for (methodName in methods) {
            try {
                val method = localAttachHost.javaClass.methods.find { it.name == methodName }
                if (method != null) {
                    val processes = method.invoke(localAttachHost) as? Collection<*>
                    if (processes != null) {
                        return processes
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Method $methodName failed: ${e.message}")
            }
        }
        throw IllegalStateException("Could not get process list from LocalAttachHost")
    }
    
    private fun findProcessByPid(processes: Collection<*>, pid: Int): Any? {
        for (process in processes) {
            try {
                val processPid = getProcessPid(process)
                if (processPid == pid) {
                    // Verify this is not the Lambda Test Tool process
                    // The Test Tool should never be debugged - only the Lambda function should be
                    val processName = getProcessName(process)
                    if (processName != null && (processName.contains("LambdaTestTool", ignoreCase = true) ||
                        processName.contains("dotnet-lambda-test-tool", ignoreCase = true))) {
                        LOG.warn("Skipping Test Tool process (PID: $pid, name: $processName) - should not attach debugger to Test Tool")
                        continue
                    }
                    return process
                }
            } catch (e: Exception) {
                LOG.debug("Error checking process PID: ${e.message}")
            }
        }
        return null
    }
    
    /**
     * Gets the process name from a process object.
     * Used to filter out Test Tool processes from debugger attachment.
     */
    private fun getProcessName(process: Any?): String? {
        val methods = listOf("getCommandLine", "getExecutableName", "getName", "getDisplayName")
        for (methodName in methods) {
            val method = process?.javaClass?.methods?.find { it.name == methodName }
            if (method != null) {
                try {
                    val result = method.invoke(process)
                    if (result is String) {
                        LOG.debug("Process name via $methodName: $result")
                        return result
                    }
                } catch (e: Exception) {
                    LOG.debug("Error getting process name via $methodName: ${e.message}")
                }
            }
        }
        LOG.debug("Could not determine process name using any method")
        return null
    }
    
    private fun getProcessPid(process: Any?): Int? {
        val methods = listOf("getPid", "getProcessId", "pid")
        for (methodName in methods) {
            val method = process?.javaClass?.methods?.find { it.name == methodName }
            if (method != null) {
                return when (val result = method.invoke(process)) {
                    is Int -> result
                    is Long -> result.toInt()
                    else -> null
                }
            }
        }
        return null
    }
    
    private fun findDotNetDebuggerProvider(): Any? {
        val providerEP = com.intellij.openapi.extensions.ExtensionPointName.create<Any>(
            "com.intellij.xdebugger.attachDebuggerProvider"
        )
        
        for (provider in providerEP.extensionList) {
            val providerName = provider.javaClass.name
            if (providerName.contains("DotNet", ignoreCase = true) ||
                providerName.contains("CoreClr", ignoreCase = true) ||
                providerName.contains("Mono", ignoreCase = true)) {
                return provider
            }
        }
        return null
    }
    
    private fun getAvailableDebuggers(
        provider: Any,
        project: Project,
        localAttachHost: Any,
        targetProcess: Any
    ): List<Any> {
        // Try the overload WITHOUT RdProcessInfoBase first (simpler and more reliable)
        val methodWithoutRd = provider.javaClass.methods.find {
            it.name == "getAvailableDebuggers" &&
            it.parameterCount == 4 &&
            it.parameterTypes[0].simpleName == "Project" &&
            it.parameterTypes[1].simpleName.contains("XAttachHost") &&
            it.parameterTypes[2].simpleName.contains("ProcessInfo") &&
            it.parameterTypes[3].simpleName.contains("UserDataHolder")
        }
        
        if (methodWithoutRd != null) {
            try {
                val userDataHolder = createUserDataHolder()
                val debuggers = methodWithoutRd.invoke(provider, project, localAttachHost, targetProcess, userDataHolder)
                if (debuggers is Collection<*>) {
                    return debuggers.filterNotNull().toList()
                }
            } catch (e: Exception) {
                LOG.warn("getAvailableDebuggers (without RdProcessInfoBase) failed: ${e.message}", e)
            }
        }
        
        return emptyList()
    }
    
    private fun createUserDataHolder(): UserDataHolder {
        return try {
            Class.forName("com.intellij.openapi.util.UserDataHolderBase")
                .getDeclaredConstructor()
                .newInstance() as UserDataHolder
        } catch (e: Exception) {
            object : UserDataHolder {
                override fun <T : Any?> getUserData(key: Key<T>): T? = null
                override fun <T : Any?> putUserData(key: Key<T>, value: T?) {}
            }
        }
    }
    
    private fun attachDebugSession(
        debugger: Any,
        project: Project,
        localAttachHost: Any,
        targetProcess: Any
    ): Boolean {
        val attachMethod = debugger.javaClass.methods.find {
            it.name == "attachDebugSession" && it.parameterCount >= 3
        }
        
        // #region agent log
        
        if (attachMethod == null) {
            return false
        }
        
        return try {
            val result = when (attachMethod.parameterCount) {
                3 -> {
                    // #region agent log
                    attachMethod.invoke(debugger, project, localAttachHost, targetProcess)
                    true
                }
                5 -> {
                    // #region agent log
                    val progressIndicator = com.intellij.openapi.progress.EmptyProgressIndicator()
                    attachMethod.invoke(debugger, project, localAttachHost, targetProcess, progressIndicator, progressIndicator)
                    true
                }
                else -> {
                    // #region agent log
                    LOG.warn("Unexpected attachDebugSession parameter count: ${attachMethod.parameterCount}")
                    false
                }
            }
            // #region agent log
            result
        } catch (e: Exception) {
            // #region agent log
            LOG.warn("Failed to attach debug session: ${e.message}", e)
            false
        }
    }
    
    private fun openBrowser(port: Int) {
        val testToolManager = com.aws.lambda.testtool.services.TestToolManager.getInstance(
            com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
        )
        testToolManager.openBrowser(port)
    }
    
    private fun showNotification(project: Project, message: String, type: NotificationType) {
        try {
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("AWS Lambda Test Tool")
            notificationGroup?.createNotification(message, type)?.notify(project)
                ?: LOG.warn("Notification group 'AWS Lambda Test Tool' not found: $message")
        } catch (e: Exception) {
            LOG.warn("Failed to show notification: $message", e)
        }
    }
}

/**
 * Utility to extract PID from a ProcessHandler.
 */
object ProcessPidExtractor {
    fun getPid(processHandler: ProcessHandler): Long? {
        return try {
            val getProcessMethod = processHandler.javaClass.methods.find { it.name == "getProcess" }
            if (getProcessMethod != null) {
                val process = getProcessMethod.invoke(processHandler) as? Process
                return process?.pid()
            }
            
            val processField = processHandler.javaClass.declaredFields.find { it.name == "myProcess" }
            if (processField != null) {
                processField.isAccessible = true
                val process = processField.get(processHandler) as? Process
                return process?.pid()
            }
            
            null
        } catch (e: Exception) {
            Logger.getInstance(ProcessPidExtractor::class.java).debug("Could not get process PID: ${e.message}")
            null
        }
    }
}
