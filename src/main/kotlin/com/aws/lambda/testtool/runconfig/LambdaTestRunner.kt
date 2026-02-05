package com.aws.lambda.testtool.runconfig

import com.aws.lambda.testtool.services.TestToolManager
import com.aws.lambda.testtool.services.debugger.DebuggerAttachmentService
import com.aws.lambda.testtool.services.debugger.ProcessPidExtractor
import com.aws.lambda.testtool.services.debugger.RiderDebuggerAttachmentService
import com.aws.lambda.testtool.services.process.ProcessManager
import com.aws.lambda.testtool.services.process.PlatformProcessManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection

/**
 * Program runner for Lambda Test configurations.
 * 
 * Handles both Run and Debug modes:
 * - Run mode: Executes the Lambda function directly
 * - Debug mode: Executes the Lambda function and automatically attaches Rider's debugger
 */
class LambdaTestRunner : GenericProgramRunner<RunnerSettings>() {
    
    private val LOG = Logger.getInstance(LambdaTestRunner::class.java)
    private val debuggerService: DebuggerAttachmentService = RiderDebuggerAttachmentService()
    
    companion object {
        private const val DEBUGGER_ATTACH_DELAY_MS = 1000L
    }
    
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
        
        // Execute the process
        val executionResult = try {
            lambdaState.execute(environment.executor, this)
        } catch (e: Exception) {
            LOG.error("Failed to execute state", e)
            throw e
        }
        
        val processHandler = executionResult.processHandler
        if (processHandler == null) {
            LOG.warn("Process handler is null")
            return RunContentBuilder(executionResult, environment).showRunContent(environment.contentToReuse)
        }
        
        // Handle debug mode: auto-attach debugger
        if (isDebugMode) {
            // Store process handler reference for cleanup when debug session ends
            val project = lambdaState.getProject()
            val configuration = lambdaState.getConfiguration()
            
            // Start progress indicator immediately
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Starting Lambda Debug Session",
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Starting Lambda function..."
                    indicator.isIndeterminate = true
                    
                    try {
                        // Wait a moment for process to initialize, then attach debugger
                        // Poll for PID instead of fixed delay
                        var attempts = 0
                        val maxAttempts = 20 // 2 seconds max (20 * 100ms)
                        var pid: Int? = null
                        
                        while (attempts < maxAttempts && pid == null) {
                            Thread.sleep(100)
                            attempts++
                            
                            if (processHandler.isProcessTerminated || processHandler.isProcessTerminating) {
                                indicator.text = "Process terminated before debugger attachment"
                                return
                            }
                            
                            val pidLong = ProcessPidExtractor.getPid(processHandler)
                            pid = pidLong?.toInt()
                            
                            if (pid == null) {
                                indicator.text = "Waiting for Lambda process to start... (${attempts * 100}ms)"
                            }
                        }
                        
                        if (pid != null) {
                            indicator.text = "Attaching debugger to process (PID: $pid)..."
                            
                            // Verify this is NOT the Test Tool's PID
                            val testToolManager = TestToolManager.getInstance(project)
                            val testToolStatus = testToolManager.getStatus()
                            val testToolPid = testToolStatus.processId
                            
                            if (testToolPid != null && testToolPid == pid.toLong()) {
                                indicator.text = "Error: Attempted to attach to Test Tool instead of Lambda function"
                                LOG.error("CRITICAL: Attempted to attach debugger to Test Tool process (PID: $pid) instead of Lambda function!")
                                return
                            }
                            
                            indicator.text = "Attaching debugger..."
                            val attached = debuggerService.attachDebugger(project, pid, configuration.port, configuration.autoOpenBrowser)
                            
                            if (attached) {
                                indicator.text = "Debugger attached successfully"
                                LOG.info("Successfully attached debugger to Lambda function process PID: $pid")
                            } else {
                                indicator.text = "Failed to attach debugger (use Run → Attach to Process manually)"
                                LOG.warn("Failed to attach debugger to Lambda function process PID: $pid")
                            }
                        } else {
                            indicator.text = "Could not get process PID - debugger attachment skipped"
                            LOG.warn("Could not get process PID for debugger attachment")
                        }
                    } catch (e: Exception) {
                        indicator.text = "Error: ${e.message}"
                        LOG.warn("Failed to auto-attach debugger", e)
                    }
                }
            })
            
            // Register cleanup listener for when debug session is stopped
            // This ensures Test Tool is stopped even when we return null (no RunContentDescriptor)
            registerDebugSessionCleanup(project, processHandler, configuration.port)
            
            // In debug mode, don't show the "running" process - only show the debug session
            // The debugger attachment creates its own visible session, so we return null
            // to avoid showing a duplicate "running" process
            return null
        } else {
            handleRunMode(lambdaState)
            return RunContentBuilder(executionResult, environment).showRunContent(environment.contentToReuse)
        }
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
        // #region agent log
        
        val testToolManager = TestToolManager.getInstance(project)
        
        // Add listener to debug session manager to cleanup when debug session stops
        // This is the most reliable way to detect when the user stops debugging
        try {
            // #region agent log
            
            val xDebuggerManager = com.intellij.xdebugger.XDebuggerManager.getInstance(project)
            val messageBus = project.messageBus.connect()
            messageBus.subscribe(
                com.intellij.xdebugger.XDebuggerManager.TOPIC,
                object : com.intellij.xdebugger.XDebuggerManagerListener {
                    override fun processStarted(debugProcess: com.intellij.xdebugger.XDebugProcess) {
                        // #region agent log
                    }
                    
                    override fun processStopped(debugProcess: com.intellij.xdebugger.XDebugProcess) {
                        // #region agent log
                        
                        // Debug process stopped - check if it's our Lambda process
                        try {
                            val sessionPid = ProcessPidExtractor.getPid(debugProcess.processHandler)
                            val lambdaPid = ProcessPidExtractor.getPid(processHandler)
                            if (sessionPid != null && lambdaPid != null && sessionPid == lambdaPid) {
                                // #region agent log
                                LOG.info("Debug session stopped for Lambda process (PID: $sessionPid), stopping Test Tool on port $port (port-based cleanup)...")
                                testToolManager.stopTestToolByPort(port)
                                messageBus.disconnect()
                            }
                        } catch (e: Exception) {
                            // #region agent log
                            LOG.warn("Error checking if stopped debug process is our Lambda process", e)
                        }
                    }
                }
            )
            
            // #region agent log
        } catch (e: Exception) {
            // #region agent log
            LOG.warn("Failed to register debug session listener, will rely on process listeners only", e)
        }
        
        // Add listener to process handler to cleanup when Lambda process terminates
        processHandler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                // #region agent log
                LOG.info("Lambda process terminated in debug mode, stopping Test Tool on port $port (port-based cleanup)...")
                // Use port-based cleanup to ensure Test Tool is stopped
                testToolManager.stopTestToolByPort(port)
            }
            
            override fun processWillTerminate(
                event: com.intellij.execution.process.ProcessEvent,
                willBeDestroyed: Boolean
            ) {
                // #region agent log
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
                    
                    // Check if there are any active debug sessions at all
                    // If process is alive but no debug sessions exist, debugging was stopped
                    var hasActiveDebugSession = false
                    var debugSessionCount = 0
                    var debugSessionPids = emptyList<Long?>()
                    if (pid != null && processAlive && !isTerminated) {
                        try {
                            val xDebuggerManager = com.intellij.xdebugger.XDebuggerManager.getInstance(project)
                            val debugSessions = xDebuggerManager.debugSessions
                            debugSessionCount = debugSessions.size
                            debugSessionPids = debugSessions.mapNotNull { session ->
                                try {
                                    val sessionHandler = session.debugProcess?.processHandler
                                    if (sessionHandler != null) {
                                        ProcessPidExtractor.getPid(sessionHandler)
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            // Check if any session matches our PID, OR if there are any sessions at all
                            // (if there are sessions but we can't match by PID, assume one might be ours)
                            hasActiveDebugSession = debugSessionPids.any { sessionPid -> sessionPid != null && sessionPid == pid } || debugSessionCount > 0
                        } catch (e: Exception) {
                            // #region agent log
                            // If we can't check, assume no session (safer to cleanup)
                            hasActiveDebugSession = false
                        }
                    }
                    
                    // #region agent log
                    
                    // If process handler is terminated OR Lambda process is no longer alive OR no active debug session, kill anything on the port
                    // This will kill the Test Tool if it's still running (or do nothing if nothing is on the port)
                    // When debugging stops, the process stays alive but the debug session ends, so we check for active sessions
                    if (isTerminated || !processAlive || (processAlive && !hasActiveDebugSession && checkCount > 5)) {
                        // #region agent log
                        LOG.info("Cleanup condition met (terminated=$isTerminated, alive=$processAlive, hasSession=$hasActiveDebugSession), stopping Test Tool on port $port (port-based cleanup)...")
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
}
