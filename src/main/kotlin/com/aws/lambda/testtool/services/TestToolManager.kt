package com.aws.lambda.testtool.services

import com.aws.lambda.testtool.models.TestToolStatus
import com.aws.lambda.testtool.services.process.PlatformProcessManager
import com.aws.lambda.testtool.services.process.ProcessManager
import com.aws.lambda.testtool.services.process.ProcessManagementException
import com.aws.lambda.testtool.utils.PortChecker
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for managing the Lambda Test Tool process lifecycle.
 * Handles starting, stopping, and monitoring the test tool process.
 */
@Service(Service.Level.PROJECT)
class TestToolManager(
    private val project: Project
) : Disposable {

    private val processManager: ProcessManager = PlatformProcessManager()

    private val LOG = Logger.getInstance(TestToolManager::class.java)

    init {
        LOG.info("TestToolManager service initialized for project: ${project.name}")
    }
    
    private val processHandler = AtomicReference<OSProcessHandler?>(null)
    private val backgroundProcessPid = AtomicReference<Long?>(null)
    private val currentStatus = AtomicReference(TestToolStatus.notRunning())
    private val statusListeners = CopyOnWriteArrayList<(TestToolStatus) -> Unit>()
    private val outputListeners = CopyOnWriteArrayList<(String, Boolean) -> Unit>()
    
    companion object {
        const val DEFAULT_PORT = 5050
        const val TOOL_COMMAND = "dotnet-lambda-test-tool"
        const val SERVICE_READY_TIMEOUT_MS: Long = 30000
        const val SERVICE_CHECK_INTERVAL_MS: Long = 500
        
        fun getInstance(project: Project): TestToolManager {
            return project.getService(TestToolManager::class.java)
        }
    }
    
    /**
     * Starts the Lambda Test Tool on the specified port.
     * 
     * The Test Tool is started as a detached process by default (registerWithIde = false) to keep it
     * hidden from the Run/Debug tool window. Only the Lambda function process should be visible to users.
     * The Test Tool runs in the background and is automatically stopped when the Lambda process terminates.
     * 
     * @param port The port to start the tool on
     * @param workingDirectory The working directory for the tool
     * @param onStarted Callback invoked when the tool starts (or fails to start)
     * @param registerWithIde If false, the process won't be registered with IDE (won't show as separate process).
     *                        Defaults to false to keep the Test Tool hidden from the Run/Debug window.
     * @param forceRestart If true, stops any existing Test Tool instance before starting a new one
     */
    fun startTestTool(
        port: Int = DEFAULT_PORT,
        workingDirectory: File? = null,
        onStarted: ((Boolean) -> Unit)? = null,
        registerWithIde: Boolean = false,
        forceRestart: Boolean = false
    ) {
        val currentStatus = getStatus()
        if (isRunning() && !forceRestart) {
            val currentPort = currentStatus.port
            if (currentPort != null && currentPort != port) {
                LOG.warn("Test Tool is already running on port $currentPort, but requested port is $port")
                showNotification(
                    "Test Tool is already running on port $currentPort. Please stop it first or use that port.",
                    NotificationType.WARNING
                )
                onStarted?.invoke(false)
                return
            } else {
                LOG.info("Test Tool is already running on port $port")
                onStarted?.invoke(true)
                return
            }
        }
        
        // If forceRestart is true, stop first
        if (forceRestart && isRunning()) {
            LOG.info("Force restart requested, stopping existing Test Tool...")
            val currentPort = currentStatus.port
            if (currentPort != null && currentPort != port) {
                // Different port requested, stop and restart
                stopTestTool(port = currentPort, onStopped = {
                    startTestToolInternal(port, workingDirectory, onStarted, registerWithIde)
                })
                return
            } else {
                // Same port, just restart
                stopTestTool(port = currentPort, onStopped = {
                    startTestToolInternal(port, workingDirectory, onStarted, registerWithIde)
                })
                return
            }
        }
        
        startTestToolInternal(port, workingDirectory, onStarted, registerWithIde)
    }
    
    /**
     * Internal method to start the Test Tool.
     * 
     * @param registerWithIde If false, starts as a detached process (hidden from IDE). Defaults to false.
     */
    private fun startTestToolInternal(
        port: Int = DEFAULT_PORT,
        workingDirectory: File? = null,
        onStarted: ((Boolean) -> Unit)? = null,
        registerWithIde: Boolean = false
    ) {
        
        updateStatus(TestToolStatus.starting(port))
        
        try {
            val commandLine = createCommandLine(port, workingDirectory)
            LOG.info("Starting Test Tool: ${commandLine.commandLineString}")
            
            // By default (registerWithIde = false), start as detached process to keep it hidden from Run/Debug window
            if (registerWithIde) {
                startRegisteredProcess(commandLine, port, onStarted)
            } else {
                startDetachedProcess(commandLine, port, workingDirectory, onStarted)
            }
        } catch (e: Exception) {
            LOG.error("Failed to start Test Tool", e)
            updateStatus(TestToolStatus.error(e.message ?: "Unknown error"))
            showNotification(
                "Failed to start Lambda Test Tool: ${e.message}",
                NotificationType.ERROR
            )
            onStarted?.invoke(false)
        }
    }
    
    private fun createCommandLine(port: Int, workingDirectory: File?): GeneralCommandLine {
        return GeneralCommandLine(
            TOOL_COMMAND, "start", "-p", port.toString(), "--no-launch-window"
        ).apply {
            workingDirectory?.let { withWorkDirectory(it) }
        }
    }
    
    /**
     * Starts the Test Tool as a registered process that will appear in the IDE's Run/Debug tool window.
     * This method is only used when registerWithIde = true is explicitly set (not the default behavior).
     * By default, the Test Tool is started as a detached process to keep it hidden from the UI.
     */
    private fun startRegisteredProcess(
        commandLine: GeneralCommandLine,
        port: Int,
        onStarted: ((Boolean) -> Unit)?
    ) {
        val handler = processManager.createRegisteredProcess(commandLine)
        processHandler.set(handler)
        
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text
                val isError = outputType == ProcessOutputTypes.STDERR
                
                LOG.debug("Test Tool output: $text")
                notifyOutput(text, isError)
                
                // Check for successful start
                if (text.contains("Now listening on") || text.contains("Application started")) {
                    val pid = handler.process.pid()
                    updateStatus(TestToolStatus.running(port, pid))
                    onStarted?.invoke(true)
                }
            }
            
            override fun processTerminated(event: ProcessEvent) {
                LOG.info("Test Tool process terminated with exit code: ${event.exitCode}")
                processHandler.set(null)
                updateStatus(TestToolStatus.notRunning())
            }
        })
        
        handler.startNotify()
        
        // Wait for service to be ready
        Thread {
            val ready = PortChecker.waitForServiceReady(port, "/", SERVICE_READY_TIMEOUT_MS, SERVICE_CHECK_INTERVAL_MS)
            if (ready && isRunning()) {
                val pid = handler.process.pid()
                updateStatus(TestToolStatus.running(port, pid))
                showNotification(
                    "Lambda Test Tool started on port $port",
                    NotificationType.INFORMATION
                )
                onStarted?.invoke(true)
            } else if (!isRunning()) {
                updateStatus(TestToolStatus.error("Test Tool failed to start"))
                showNotification(
                    "Lambda Test Tool failed to start",
                    NotificationType.ERROR
                )
                onStarted?.invoke(false)
            }
        }.start()
    }
    
    /**
     * Starts the Test Tool as a detached process that won't appear in the IDE's Run/Debug tool window.
     * This keeps the UI clean by showing only the Lambda function process, while the Test Tool runs
     * silently in the background. The Test Tool can still be started and stopped programmatically.
     */
    private fun startDetachedProcess(
        commandLine: GeneralCommandLine,
        port: Int,
        workingDirectory: File?,
        onStarted: ((Boolean) -> Unit)?
    ) {
        val pid = processManager.createDetachedProcess(commandLine, workingDirectory)
        
        if (pid <= 0) {
            LOG.error("Failed to create detached process")
            updateStatus(TestToolStatus.error("Failed to create detached process"))
            onStarted?.invoke(false)
            return
        }
        
        LOG.info("Test Tool started in background mode (PID: $pid, hidden from Run/Debug window)")
        updateStatus(TestToolStatus.running(port, pid))
        backgroundProcessPid.set(pid)
        
        // Monitor process termination
        startProcessMonitor(pid)
        
        // Wait for service to be ready
        Thread {
            val ready = PortChecker.waitForServiceReady(port, "/", SERVICE_READY_TIMEOUT_MS, SERVICE_CHECK_INTERVAL_MS)
            if (ready) {
                updateStatus(TestToolStatus.running(port, pid))
                onStarted?.invoke(true)
            } else {
                updateStatus(TestToolStatus.error("Test Tool failed to start: Service not ready"))
                onStarted?.invoke(false)
            }
        }.start()
    }
    
    private fun startProcessMonitor(pid: Long) {
        Thread {
            val storedPid = pid
            while (true) {
                if (!processManager.isProcessAlive(storedPid)) {
                    LOG.info("Test Tool background process terminated")
                    backgroundProcessPid.set(null)
                    updateStatus(TestToolStatus.notRunning())
                    break
                }
                Thread.sleep(1000)
            }
        }.start()
    }
    
    /**
     * Stops the Lambda Test Tool.
     */
    fun stopTestTool(onStopped: (() -> Unit)? = null, port: Int? = null) {
        val handler = processHandler.get()
        val bgPid = backgroundProcessPid.get()
        val currentStatus = getStatus()
        val statusPort = currentStatus.port
        // Use provided port if available, otherwise use status port
        val portToUse = port ?: statusPort
        
        if (handler == null && bgPid == null) {
            LOG.warn("Test Tool is not running (no handler or PID)")
            // Still try to kill processes on the port in case there are orphaned processes
            // This is important when status is out of sync but processes are still running
            if (portToUse != null) {
                LOG.info("No tracked process, but attempting port-based cleanup on port $portToUse")
                killProcessesOnPort(portToUse)
            }
            onStopped?.invoke()
            return
        }
        
        updateStatus(TestToolStatus.stopping())
        
        try {
            if (handler != null) {
                stopRegisteredProcess(handler, onStopped)
            } else if (bgPid != null && bgPid > 0) {
                stopDetachedProcess(bgPid, portToUse, onStopped)
            }
        } catch (e: Exception) {
            LOG.error("Failed to stop Test Tool", e)
            processHandler.set(null)
            backgroundProcessPid.set(null)
            updateStatus(TestToolStatus.error(e.message ?: "Unknown error"))
            // Still try to kill processes on the port
            if (portToUse != null) {
                killProcessesOnPort(portToUse)
            }
            onStopped?.invoke()
        }
    }
    
    private fun stopRegisteredProcess(handler: OSProcessHandler, onStopped: (() -> Unit)?) {
        handler.destroyProcess()
        
        Thread {
            var attempts = 0
            while (handler.process.isAlive && attempts < 10) {
                Thread.sleep(500)
                attempts++
            }
            
            if (handler.process.isAlive) {
                handler.process.destroyForcibly()
            }
            
            processHandler.set(null)
            updateStatus(TestToolStatus.notRunning())
            showNotification("Lambda Test Tool stopped", NotificationType.INFORMATION)
            onStopped?.invoke()
        }.start()
    }
    
    private fun stopDetachedProcess(pid: Long, port: Int?, onStopped: (() -> Unit)?) {
        LOG.info("Stopping detached Test Tool process (PID: $pid)")
        
        // Clear the PID reference immediately to prevent race conditions
        backgroundProcessPid.set(null)
        updateStatus(TestToolStatus.stopping())
        
        Thread {
            try {
                // Check if process is still alive before attempting to kill
                if (!processManager.isProcessAlive(pid)) {
                    LOG.info("Process $pid is already terminated")
                    updateStatus(TestToolStatus.notRunning())
                    showNotification("Lambda Test Tool stopped", NotificationType.INFORMATION)
                    onStopped?.invoke()
                    return@Thread
                }
                
                // First, try graceful shutdown
                LOG.info("Attempting graceful shutdown of process $pid")
                try {
                    processManager.killProcess(pid, force = false)
                } catch (e: ProcessManagementException) {
                    LOG.warn("Graceful kill failed, will try force kill: ${e.message}")
                }
                
                // Wait and check multiple times to ensure process is actually killed
                var attempts = 0
                val maxAttempts = 10
                while (processManager.isProcessAlive(pid) && attempts < maxAttempts) {
                    Thread.sleep(500)
                    attempts++
                    if (attempts >= 5 && processManager.isProcessAlive(pid)) {
                        // After 2.5 seconds, try force kill
                        LOG.info("Process still alive after ${attempts * 500}ms, forcing termination")
                        try {
                            processManager.killProcess(pid, force = true)
                        } catch (e: ProcessManagementException) {
                            LOG.error("Force kill failed for process $pid: ${e.message}")
                        }
                    }
                }
                
                // Final verification
                if (processManager.isProcessAlive(pid)) {
                    LOG.error("Process $pid is still alive after $maxAttempts attempts to kill it")
                    // Try one more force kill as last resort
                    try {
                        processManager.killProcess(pid, force = true)
                        Thread.sleep(1000)
                        if (processManager.isProcessAlive(pid)) {
                            LOG.error("CRITICAL: Process $pid could not be killed. It may still be running.")
                        } else {
                            LOG.info("Process $pid finally terminated after additional force kill")
                        }
                    } catch (e: ProcessManagementException) {
                        LOG.error("Final force kill attempt also failed: ${e.message}")
                    }
                } else {
                    LOG.info("Process $pid terminated successfully")
                }
                
                // Also kill any processes still listening on the port (handles child processes)
                // This is important because dotnet-lambda-test-tool may spawn child .NET processes
                if (port != null) {
                    killProcessesOnPort(port)
                }
            } catch (e: Exception) {
                LOG.error("Unexpected error stopping process $pid", e)
            } finally {
                // Always update status and notify, even if kill failed
                updateStatus(TestToolStatus.notRunning())
                showNotification("Lambda Test Tool stopped", NotificationType.INFORMATION)
                onStopped?.invoke()
            }
        }.start()
    }
    
    /**
     * Stops the Test Tool by port, using port-based cleanup.
     * This is useful when the process handler isn't available or when cleanup needs to be forced.
     */
    fun stopTestToolByPort(port: Int) {
        LOG.info("Stopping Test Tool by port: $port (port-based cleanup)")
        killProcessesOnPort(port)
        // Also update status
        updateStatus(TestToolStatus.notRunning())
        processHandler.set(null)
        backgroundProcessPid.set(null)
    }
    
    /**
     * Kills all processes listening on the specified port.
     * This is useful for cleaning up child processes that may still be running.
     */
    private fun killProcessesOnPort(port: Int) {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            if (isWindows) {
                // Windows: use netstat to find PIDs, then kill them
                val netstatCommand = GeneralCommandLine("netstat", "-ano")
                val netstatProcess = netstatCommand.createProcess()
                val output = netstatProcess.inputStream.bufferedReader().readText()
                netstatProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                
                val lines = output.lines()
                val pids = mutableSetOf<Long>()
                for (line in lines) {
                    if (line.contains(":$port ") || line.contains(":$port\n")) {
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 5) {
                            val pidStr = parts.last()
                            if (pidStr.all { it.isDigit() }) {
                                pids.add(pidStr.toLong())
                            }
                        }
                    }
                }
                
                for (pid in pids) {
                    if (pid > 0 && processManager.isProcessAlive(pid)) {
                        LOG.info("Killing process $pid listening on port $port")
                        try {
                            processManager.killProcess(pid, force = true)
                        } catch (e: Exception) {
                            LOG.warn("Failed to kill process $pid on port $port: ${e.message}")
                        }
                    }
                }
            } else {
                // Unix: use lsof to find and kill processes
                val lsofCommand = GeneralCommandLine("lsof", "-ti", ":$port")
                val lsofProcess = lsofCommand.createProcess()
                val pidOutput = lsofProcess.inputStream.bufferedReader().readText().trim()
                lsofProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                
                if (pidOutput.isNotEmpty()) {
                    val pids = pidOutput.lines().filter { it.all { char -> char.isDigit() } }
                    for (pidStr in pids) {
                        val pid = pidStr.toLong()
                        if (pid > 0 && processManager.isProcessAlive(pid)) {
                            LOG.info("Killing process $pid listening on port $port")
                            try {
                                processManager.killProcess(pid, force = true)
                            } catch (e: Exception) {
                                LOG.warn("Failed to kill process $pid on port $port: ${e.message}")
                            }
                        }
                    }
                } else {
                    LOG.debug("No processes found on port $port")
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to kill processes on port $port: ${e.message}")
        }
    }
    
    /**
     * Restarts the Lambda Test Tool.
     * 
     * The Test Tool is restarted as a detached process (hidden from IDE) to maintain consistency
     * with the default behavior of keeping it hidden from the Run/Debug tool window.
     */
    fun restartTestTool(
        port: Int = DEFAULT_PORT,
        workingDirectory: File? = null,
        onRestarted: ((Boolean) -> Unit)? = null
    ) {
        stopTestTool(port = port, onStopped = {
            Thread.sleep(1000)
            // Explicitly set registerWithIde = false to ensure Test Tool remains hidden
            startTestTool(port, workingDirectory, onRestarted, registerWithIde = false)
        })
    }
    
    /**
     * Checks if the Test Tool is currently running.
     */
    fun isRunning(): Boolean {
        val handler = processHandler.get()
        if (handler != null) {
            return handler.process.isAlive
        }
        
        val bgPid = backgroundProcessPid.get()
        return bgPid != null && bgPid > 0 && processManager.isProcessAlive(bgPid)
    }
    
    /**
     * Gets the current status of the Test Tool.
     */
    fun getStatus(): TestToolStatus = currentStatus.get()
    
    /**
     * Opens the Test Tool web interface in the default browser.
     */
    fun openBrowser(port: Int = DEFAULT_PORT) {
        val url = "http://localhost:$port"
        LOG.info("Opening browser to: $url")
        BrowserUtil.browse(url)
    }
    
    /**
     * Registers a listener for status changes.
     */
    fun addStatusListener(listener: (TestToolStatus) -> Unit) {
        statusListeners.add(listener)
        listener(currentStatus.get())
    }
    
    /**
     * Removes a status listener.
     */
    fun removeStatusListener(listener: (TestToolStatus) -> Unit) {
        statusListeners.remove(listener)
    }
    
    /**
     * Registers a listener for output from the Test Tool process.
     */
    fun addOutputListener(listener: (String, Boolean) -> Unit) {
        outputListeners.add(listener)
    }
    
    /**
     * Removes an output listener.
     */
    fun removeOutputListener(listener: (String, Boolean) -> Unit) {
        outputListeners.remove(listener)
    }
    
    private fun updateStatus(status: TestToolStatus) {
        currentStatus.set(status)
        statusListeners.forEach { it(status) }
    }
    
    private fun notifyOutput(text: String, isError: Boolean) {
        outputListeners.forEach { it(text, isError) }
    }
    
    private fun showNotification(content: String, type: NotificationType) {
        try {
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("AWS Lambda Test Tool")
            notificationGroup?.createNotification(content, type)?.notify(project)
                ?: LOG.warn("Notification group 'AWS Lambda Test Tool' not found")
        } catch (e: Exception) {
            LOG.warn("Failed to show notification: $content", e)
        }
    }
    
    override fun dispose() {
        if (isRunning()) {
            stopTestTool()
        }
        processHandler.set(null)
        backgroundProcessPid.set(null)
        statusListeners.clear()
        outputListeners.clear()
    }
}
