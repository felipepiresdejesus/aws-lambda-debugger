package com.aws.lambda.testtool.services.debugger

import com.intellij.execution.process.ProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder

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
    
    /**
     * Attaches the debugger with retries to handle timing issues.
     * The Lambda process may not be visible in the OS process list immediately.
     * Called from a background thread - this method blocks until attachment completes or all retries are exhausted.
     */
    override fun attachDebugger(project: Project, pid: Int, port: Int, autoOpenBrowser: Boolean): Boolean {
        val maxAttempts = 5
        val retryDelayMs = 1500L

        for (attempt in 1..maxAttempts) {
            try {
                LOG.info("Attempting to attach debugger to PID: $pid (attempt $attempt/$maxAttempts)")
                val attached = tryXDebuggerAttach(project, pid)

                if (attached) {
                    LOG.info("Debugger attached successfully on attempt $attempt")
                    if (autoOpenBrowser) {
                        ApplicationManager.getApplication().invokeLater {
                            openBrowser(port)
                        }
                    }
                    ApplicationManager.getApplication().invokeLater {
                        showNotification(project, "Debugger attached to Lambda (PID: $pid)", NotificationType.INFORMATION)
                    }
                    return true
                }

                if (attempt < maxAttempts) {
                    LOG.info("Attachment attempt $attempt failed, retrying in ${retryDelayMs}ms...")
                    Thread.sleep(retryDelayMs)
                }
            } catch (e: Exception) {
                LOG.warn("Attachment attempt $attempt failed with exception: ${e.message}", e)
                if (attempt < maxAttempts) {
                    Thread.sleep(retryDelayMs)
                }
            }
        }

        // All attempts failed - notify user to attach manually
        LOG.warn("All $maxAttempts attachment attempts failed for PID: $pid")
        ApplicationManager.getApplication().invokeLater {
            showNotification(
                project,
                "Could not auto-attach debugger to Lambda (PID: $pid). Use Run -> Attach to Process -> .NET Core to debug with breakpoints.",
                NotificationType.WARNING
            )
        }
        return false
    }
    
    private fun tryXDebuggerAttach(project: Project, pid: Int): Boolean {
        return try {
            val localAttachHost = getLocalAttachHost()
            val processes = getProcessList(localAttachHost)

            LOG.info("Available processes from attach host: ${processes.size} total")
            for (process in processes) {
                try {
                    val procPid = getProcessPid(process)
                    val procName = getProcessName(process)
                    if (procPid != null) {
                        LOG.info("  Process: PID=$procPid, Name=$procName")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Filter out Test Tool processes before searching
            val filteredProcesses = processes.filter { process ->
                val processName = getProcessName(process)
                val isTestTool = processName != null && (
                    processName.contains("LambdaTestTool", ignoreCase = true) ||
                    processName.contains("dotnet-lambda-test-tool", ignoreCase = true)
                )
                !isTestTool
            }

            LOG.info("Searching for Lambda function process with PID: $pid (filtered ${processes.size - filteredProcesses.size} Test Tool processes)")
            val targetProcess = findProcessByPid(filteredProcesses, pid)
            
            if (targetProcess == null) {
                LOG.warn("Lambda function process $pid not found in filtered process list")
                // Try to find it in the unfiltered list for debugging
                val unfilteredProcess = findProcessByPid(processes, pid)
                if (unfilteredProcess != null) {
                    val unfilteredName = getProcessName(unfilteredProcess)
                    LOG.warn("Process $pid exists but was filtered out! Process name: $unfilteredName")
                    LOG.warn("This suggests the Lambda function process name contains 'LambdaTestTool' - this should not happen!")
                } else {
                    LOG.warn("Process $pid is not in the process list at all - it may have already exited or not yet started")
                    LOG.warn("Available PIDs in filtered list: ${filteredProcesses.mapNotNull { getProcessPid(it) }}")
                }
                return false
            }
            
            // Double-check we're not attaching to Test Tool
            val targetProcessName = getProcessName(targetProcess)
            if (targetProcessName != null && (
                targetProcessName.contains("LambdaTestTool", ignoreCase = true) ||
                targetProcessName.contains("dotnet-lambda-test-tool", ignoreCase = true)
            )) {
                LOG.error("CRITICAL: Attempted to attach debugger to Test Tool process (PID: $pid, name: $targetProcessName). This should never happen!")
                return false
            }
            
            LOG.info("Found Lambda function process: PID=$pid, name=$targetProcessName")
            
            val provider = findDotNetDebuggerProvider()
            if (provider == null) {
                LOG.info("No suitable .NET debugger provider found")
                return false
            }
            
            val debuggers = getAvailableDebuggers(provider, project, localAttachHost, targetProcess)
            if (debuggers.isEmpty()) {
                LOG.info("No debuggers available for this process")
                return false
            }
            
            LOG.info("Attaching debugger to Lambda function process (PID: $pid)")
            val attachResult = attachDebugSession(debuggers.first(), project, localAttachHost, targetProcess)
            attachResult
        } catch (e: Exception) {
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

        if (attachMethod == null) {
            LOG.warn("No attachDebugSession method found on debugger: ${debugger.javaClass.name}")
            return false
        }

        LOG.info("Found attachDebugSession with ${attachMethod.parameterCount} params on ${debugger.javaClass.name}")

        return try {
            // attachDebugSession creates UI elements (debug tab) and must run on EDT
            var result = false
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    when (attachMethod.parameterCount) {
                        3 -> {
                            attachMethod.invoke(debugger, project, localAttachHost, targetProcess)
                            result = true
                        }
                        5 -> {
                            val progressIndicator = com.intellij.openapi.progress.EmptyProgressIndicator()
                            attachMethod.invoke(debugger, project, localAttachHost, targetProcess, progressIndicator, progressIndicator)
                            result = true
                        }
                        else -> {
                            LOG.warn("Unexpected attachDebugSession parameter count: ${attachMethod.parameterCount}")
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to invoke attachDebugSession on EDT: ${e.message}", e)
                }
            }
            result
        } catch (e: Exception) {
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
