package com.aws.lambda.testtool.runconfig

import com.aws.lambda.testtool.services.TestToolManager
import com.aws.lambda.testtool.services.debugger.ProcessPidExtractor
import com.aws.lambda.testtool.services.debugger.RiderDebuggerAttachmentService
import com.aws.lambda.testtool.services.process.ProcessManager
import com.aws.lambda.testtool.services.process.PlatformProcessManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Program runner for Lambda Test configurations.
 * 
 * Handles both Run and Debug modes:
 * - Run mode: Executes the Lambda function directly
 * - Debug mode: Executes the Lambda function and automatically attaches Rider's debugger
 */
class LambdaTestRunner : GenericProgramRunner<RunnerSettings>() {

    private val LOG = Logger.getInstance(LambdaTestRunner::class.java)
    
    override fun getRunnerId(): String = "LambdaTestRunner"
    
    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        return profile is LambdaTestRunConfiguration && 
               (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID)
    }
    
    override fun doExecute(
        state: com.intellij.execution.configurations.RunProfileState,
        environment: ExecutionEnvironment
    ): RunContentDescriptor? {
        val lambdaState = state as? LambdaTestRunState ?: return null
        val isDebugMode = environment.executor is DefaultDebugExecutor

        LOG.info("LambdaTestRunner.doExecute: isDebugMode=$isDebugMode")

        // Execute the process (starts Test Tool + Lambda)
        val executionResult = try {
            lambdaState.execute(environment.executor, this)
        } catch (e: Exception) {
            LOG.error("Failed to execute Lambda process", e)
            throw e
        }

        val processHandler = executionResult.processHandler
        if (processHandler == null) {
            LOG.warn("Process handler is null")
            return RunContentBuilder(executionResult, environment).showRunContent(environment.contentToReuse)
        }

        val project = lambdaState.getProject()
        val configuration = lambdaState.getConfiguration()

        // Register cleanup listener for when process or debug session ends
        registerDebugSessionCleanup(project, processHandler, configuration.port)

        // Build the run content (process output tab) for both Run and Debug modes.
        // In debug mode this also serves as the "active run" marker so the IDE prevents
        // duplicate launches when the user clicks Debug multiple times.
        val contentDescriptor = RunContentBuilder(executionResult, environment).showRunContent(environment.contentToReuse)

        // Set a descriptive tab name: "ProjectName - Console"
        val projectName = configuration.projectPath?.let { java.io.File(it).nameWithoutExtension }
            ?: configuration.name
        setDescriptorDisplayName(contentDescriptor, "$projectName - Console")

        if (isDebugMode) {
            LOG.info("Debug mode: Will attach Rider's .NET debugger to Lambda process")
            attachDebuggerAsync(project, processHandler, configuration.port, configuration.autoOpenBrowser)
        }

        return contentDescriptor
    }

    /**
     * Attaches Rider's .NET debugger to the Lambda process with a visible
     * progress bar in the IDE status bar.
     */
    private fun attachDebuggerAsync(
        project: Project,
        processHandler: ProcessHandler,
        port: Int,
        autoOpenBrowser: Boolean
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Lambda Debugger", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.1
                indicator.text = "Waiting for Lambda process to start..."

                // Wait for the process to appear in the OS process list.
                // The startup hook pauses it until the debugger attaches.
                Thread.sleep(2000)

                if (processHandler.isProcessTerminated || processHandler.isProcessTerminating) {
                    LOG.warn("Lambda process already terminated before debugger could attach")
                    return
                }

                indicator.fraction = 0.3
                indicator.text = "Resolving Lambda process..."

                val pid = ProcessPidExtractor.getPid(processHandler)
                if (pid == null) {
                    LOG.warn("Could not get PID from process handler, cannot attach debugger")
                    showNotification(
                        project,
                        "Could not determine Lambda process PID. Use Run -> Attach to Process to debug manually.",
                        NotificationType.WARNING
                    )
                    return
                }

                indicator.fraction = 0.5
                indicator.text = "Attaching .NET debugger (PID: $pid)..."

                LOG.info("Attaching Rider's .NET debugger to Lambda process (PID: $pid)")

                val attachService = RiderDebuggerAttachmentService()
                val attached = attachService.attachDebugger(project, pid.toInt(), port, autoOpenBrowser)

                if (attached) {
                    LOG.info("Debugger successfully attached to Lambda process (PID: $pid)")
                    indicator.fraction = 1.0
                    indicator.text = "Debugger attached"
                } else {
                    LOG.warn("Auto-attach failed for PID: $pid. User was notified to attach manually.")
                }
            }
        })
    }

    /**
     * Registers cleanup listeners to ensure Test Tool is stopped when debug session ends.
     * This is needed because we return null in debug mode (no RunContentDescriptor),
     * so we need to ensure cleanup happens when the process terminates or debug session stops.
     * Uses port-based cleanup to ensure Test Tool is stopped even if process handlers fail.
     */
    private fun registerDebugSessionCleanup(
        project: Project,
        processHandler: com.intellij.execution.process.ProcessHandler,
        port: Int
    ) {
        val testToolManager = TestToolManager.getInstance(project)
        
        // Add listener to debug session manager to cleanup when debug session stops
        // This is the most reliable way to detect when the user stops debugging
        try {
            val messageBus = project.messageBus.connect()
            messageBus.subscribe(
                com.intellij.xdebugger.XDebuggerManager.TOPIC,
                object : com.intellij.xdebugger.XDebuggerManagerListener {
                    override fun processStarted(debugProcess: com.intellij.xdebugger.XDebugProcess) {
                        LOG.debug("Debug process started: ${debugProcess.session?.sessionName}")
                    }
                    
                    override fun processStopped(debugProcess: com.intellij.xdebugger.XDebugProcess) {
                        // Debug process stopped - check if it's our Lambda process
                        // Session name is formatted as "PID:ProjectName" by Rider's attach mechanism
                        try {
                            val lambdaPid = ProcessPidExtractor.getPid(processHandler)
                            val sessionName = debugProcess.session?.sessionName ?: ""
                            val isOurSession = lambdaPid != null && sessionName.startsWith("$lambdaPid:")
                            if (isOurSession) {
                                LOG.info("Debug session '$sessionName' stopped, cleaning up...")
                                if (!processHandler.isProcessTerminated) {
                                    processHandler.destroyProcess()
                                }
                                testToolManager.stopTestToolByPort(port)
                                messageBus.disconnect()
                            }
                        } catch (e: Exception) {
                            LOG.warn("Error checking if stopped debug process is our Lambda process", e)
                        }
                    }
                }
            )
            
        } catch (e: Exception) {
            LOG.warn("Failed to register debug session listener, will rely on process listeners only", e)
        }
        
        // Add listener to process handler to cleanup when Lambda process terminates
        processHandler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                LOG.info("Lambda process terminated in debug mode, stopping Test Tool on port $port (port-based cleanup)...")
                // Use port-based cleanup to ensure Test Tool is stopped
                testToolManager.stopTestToolByPort(port)
            }
            
            override fun processWillTerminate(
                event: com.intellij.execution.process.ProcessEvent,
                willBeDestroyed: Boolean
            ) {
                if (willBeDestroyed) {
                    LOG.info("Lambda process will be destroyed in debug mode, stopping Test Tool on port $port (port-based cleanup)...")
                    // Use port-based cleanup to ensure Test Tool is stopped
                    testToolManager.stopTestToolByPort(port)
                }
            }
        })
        
        // Simple monitor: when process dies or handler terminates, kill anything on the port
        // This follows the user's suggestion: "just run a command to kill tool using the port, and if none, just ignore"
        // We only check if the process is dead or handler is terminated - when that happens, call stopTestToolByPort
        ApplicationManager.getApplication().executeOnPooledThread {
            val processManager: ProcessManager = PlatformProcessManager()
            
            try {
                // Poll every 1 second to check if process is dead or handler is terminated
                var checkCount = 0
                val maxChecks = 600 // Check for up to 10 minutes (600 * 1 second)
                
                while (checkCount < maxChecks) {
                    Thread.sleep(1000)
                    
                    val pid = ProcessPidExtractor.getPid(processHandler)
                    val isTerminated = processHandler.isProcessTerminated || processHandler.isProcessTerminating
                    
                    // Check if the actual Lambda process (by PID) is still alive
                    val processAlive = if (pid != null) {
                        try {
                            processManager.isProcessAlive(pid)
                        } catch (e: Exception) {
                            false // If we can't check, assume it's dead
                        }
                    } else {
                        false // No PID means process is gone
                    }
                    
                    // Check if our debug session is still active
                    // Session name is "PID:ProjectName" by Rider's attach mechanism
                    var hasActiveDebugSession = false
                    if (pid != null && processAlive && !isTerminated) {
                        try {
                            val xDebuggerManager = com.intellij.xdebugger.XDebuggerManager.getInstance(project)
                            hasActiveDebugSession = xDebuggerManager.debugSessions.any { session ->
                                session.sessionName?.startsWith("$pid:") == true
                            }
                        } catch (e: Exception) {
                            hasActiveDebugSession = false
                        }
                    }
                    
                    // If process handler is terminated OR Lambda process is no longer alive OR no active debug session, kill anything on the port
                    // This will kill the Test Tool if it's still running (or do nothing if nothing is on the port)
                    // When debugging stops, the process stays alive but the debug session ends, so we check for active sessions
                    if (isTerminated || !processAlive || (processAlive && !hasActiveDebugSession && checkCount > 5)) {
                        LOG.info("Cleanup condition met (terminated=$isTerminated, alive=$processAlive, hasSession=$hasActiveDebugSession), cleaning up...")
                        if (!processHandler.isProcessTerminated) {
                            processHandler.destroyProcess()
                        }
                        testToolManager.stopTestToolByPort(port)
                        break
                    }
                    
                    checkCount++
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, cleanup
                LOG.info("Cleanup monitor thread interrupted, stopping Test Tool on port $port (port-based cleanup)...")
                testToolManager.stopTestToolByPort(port)
            } catch (e: Exception) {
                LOG.warn("Error in cleanup monitor thread", e)
                // On error, still try to cleanup
                testToolManager.stopTestToolByPort(port)
            }
        }
    }
    
    private fun handleRunMode(lambdaState: LambdaTestRunState) {
        val configuration = lambdaState.getConfiguration()
        if (configuration.autoOpenBrowser) {
            val testToolManager = TestToolManager.getInstance(lambdaState.getProject())
            testToolManager.openBrowser(configuration.port)
        }
    }

    private fun setDescriptorDisplayName(descriptor: RunContentDescriptor?, name: String) {
        if (descriptor == null) return
        try {
            val method = RunContentDescriptor::class.java.getDeclaredMethod("setDisplayName", String::class.java)
            method.isAccessible = true
            method.invoke(descriptor, name)
        } catch (e: Exception) {
            LOG.debug("Could not set custom display name on RunContentDescriptor: ${e.message}")
        }
    }

    private fun showNotification(project: Project, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("AWS Lambda Test Tool")
                    ?.createNotification(message, type)
                    ?.notify(project)
            } catch (e: Exception) {
                LOG.warn("Failed to show notification: $message", e)
            }
        }
    }
}
