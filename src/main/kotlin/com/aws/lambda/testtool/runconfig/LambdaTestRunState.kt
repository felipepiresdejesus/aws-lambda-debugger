package com.aws.lambda.testtool.runconfig

import com.aws.lambda.testtool.diagnostics.LambdaDiagnostics
import com.aws.lambda.testtool.exceptions.LambdaProjectNotFoundException
import com.aws.lambda.testtool.exceptions.TestToolNotRespondingException
import com.aws.lambda.testtool.exceptions.TestToolStartException
import com.aws.lambda.testtool.services.TestToolManager
import com.aws.lambda.testtool.utils.DotNetBuildHelper
import com.aws.lambda.testtool.utils.PortChecker
import com.aws.lambda.testtool.utils.ProcessOutputLogger
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.notification.NotificationGroupManager
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution state for Lambda Test run configuration.
 * 
 * Handles the full workflow:
 * 1. Start the Lambda Test Tool (as a detached process, hidden from Run/Debug window)
 * 2. Wait for the tool to be ready
 * 3. Start the Lambda function executable with proper environment variables
 * 4. Ensure cleanup when the Lambda process terminates
 * 
 * The Lambda Test Tool is intentionally started as a detached process (registerWithIde = false)
 * to keep it hidden from the IDE's Run/Debug tool window. Only the Lambda function process
 * is visible to users, providing a cleaner UI experience. The Test Tool still runs in the
 * background and can be started/stopped programmatically, and is automatically cleaned up
 * when the Lambda process terminates.
 */
class LambdaTestRunState(
    private val project: com.intellij.openapi.project.Project,
    private val configuration: LambdaTestRunConfiguration,
    private val environment: ExecutionEnvironment
) : CommandLineState(environment) {
    
    private val LOG = Logger.getInstance(LambdaTestRunState::class.java)
    private val isDebugMode = environment.executor is DefaultDebugExecutor
    private var testToolManager: TestToolManager? = null
    private var testToolPort: Int? = null
    private var processOutputLogPath: String? = null
    
    companion object {
        private const val TEST_TOOL_START_TIMEOUT_SECONDS = 30L
        private const val SERVICE_READY_TIMEOUT_MS: Long = 30000
        private const val SERVICE_CHECK_INTERVAL_MS: Long = 500
    }
    
    override fun startProcess(): ProcessHandler {
        val lambdaProject = try {
            configuration.getLambdaProject()
        } catch (e: Exception) {
            throw LambdaProjectNotFoundException("Failed to get Lambda project: ${e.message}", e)
        } ?: throw LambdaProjectNotFoundException("Lambda project not found")
        
        val port = configuration.port
        
        // Save all unsaved editor changes to disk before building.
        // Rider's native run configurations do this automatically, but our custom
        // runner needs to trigger it explicitly.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        }

        // Build the project first to ensure we have the latest version
        LOG.info("Building .NET project: ${lambdaProject.name}")
        LOG.info("Detected target framework: ${lambdaProject.targetFramework}")
        val configurationName = if (isDebugMode) "Debug" else "Release"
        
        // Verify the target framework is correct before building
        val projectFile = File(lambdaProject.projectFile.path)
        val actualFramework = DotNetBuildHelper.getActualTargetFramework(projectFile) ?: lambdaProject.targetFramework
        if (actualFramework != lambdaProject.targetFramework) {
            LOG.warn("Target framework mismatch! Detected: ${lambdaProject.targetFramework}, Actual: $actualFramework")
            LOG.warn("This may cause the build output directory to be incorrect.")
        }
        
        val buildOutputDirectory = DotNetBuildHelper.buildProject(
            projectFile = projectFile,
            configuration = configurationName,
            indicator = null // Could pass progress indicator if available
        )
        
        if (buildOutputDirectory == null) {
            LOG.warn("Build failed or output directory could not be determined, using existing path")
            // Continue with existing path - the executable might still exist from a previous build
        } else {
            LOG.info("Build completed. Output directory: ${buildOutputDirectory.absolutePath}")
        }
        
        // Update the build output directory if we got a new one from the build
        val updatedLambdaProject = if (buildOutputDirectory != null && buildOutputDirectory != lambdaProject.buildOutputDirectory) {
            LOG.info("Build output directory updated: ${buildOutputDirectory.absolutePath}")
            lambdaProject.copy(buildOutputDirectory = buildOutputDirectory)
        } else if (buildOutputDirectory != null) {
            // Even if the path is the same, update to ensure it exists
            lambdaProject.copy(buildOutputDirectory = buildOutputDirectory)
        } else {
            lambdaProject
        }
        
        // Verify the DLL exists after build (required for both executable and handler-based)
        if (!updatedLambdaProject.dllPath.exists()) {
            // For executable projects, also check for the executable
            if (updatedLambdaProject.isExecutable && !updatedLambdaProject.executablePath.exists()) {
                throw LambdaProjectNotFoundException(
                    "Build output not found at ${updatedLambdaProject.dllPath.absolutePath} or ${updatedLambdaProject.executablePath.absolutePath}. " +
                    "Please ensure the project builds successfully."
                )
            } else if (!updatedLambdaProject.isExecutable) {
                // For handler-based projects, only DLL is needed
                throw LambdaProjectNotFoundException(
                    "Build output not found at ${updatedLambdaProject.dllPath.absolutePath}. " +
                    "Please ensure the project builds successfully."
                )
            }
        }
        
        val workingDir = configuration.workingDirectory?.let { File(it) }
            ?: updatedLambdaProject.buildOutputDirectory
        
        LOG.info("Starting Lambda Test execution for ${updatedLambdaProject.name}")
        LOG.info("Port: $port, Working Dir: $workingDir, Debug: $isDebugMode")
        
        // Start the Test Tool first
        val testToolManager = TestToolManager.getInstance(project)
        this.testToolManager = testToolManager
        this.testToolPort = port
        ensureTestToolRunning(testToolManager, port, workingDir)
        
        // Wait for the service to be ready
        LOG.info("Waiting for Test Tool to be ready...")
        if (!PortChecker.waitForServiceReady(port, "/", SERVICE_READY_TIMEOUT_MS, SERVICE_CHECK_INTERVAL_MS)) {
            throw TestToolNotRespondingException("Test Tool not responding on port $port")
        }
        
        // Build and start the Lambda executable (if needed)
        val commandLine = buildLambdaCommandLine(updatedLambdaProject, port, workingDir)
        
        // For handler-based Lambdas, no separate process is needed
        // The Test Tool will discover and invoke handlers
        if (commandLine == null) {
            LOG.info("Handler-based Lambda - no separate process needed. Test Tool will handle handler discovery.")
            // Create a dummy process handler that does nothing but allows the execution to continue
            // The Test Tool is already running and will handle everything
            return createDummyProcessHandler(port, testToolManager)
        }
        
        LOG.info("Starting Lambda executable: ${commandLine.commandLineString}")
        
        // Set environment variables to help identify the process
        // This can be used by process monitoring tools
        commandLine.environment["LAMBDA_TEST_PROCESS_NAME"] = "Lambda Test: ${updatedLambdaProject.name}"
        commandLine.environment["LAMBDA_TEST_PROJECT"] = updatedLambdaProject.name
        
        // Create process handler
        // Note: On Unix, we can use exec -a to set the process name, but for .NET processes
        // the actual executable name (DLL) will still show. The environment variables help identify it.
        val processHandler = KillableColoredProcessHandler(commandLine)

        // Attach file-based output logging for debugging startup failures
        try {
            val projectRootPath = project.basePath ?: System.getProperty("user.home")
            val logPath = ProcessOutputLogger.attachLogging(processHandler, projectRootPath, updatedLambdaProject.name)
            processOutputLogPath = logPath
            LOG.info("Attached output logging to process. Log file: $logPath")
        } catch (e: Exception) {
            LOG.warn("Failed to attach process output logging (non-fatal): ${e.message}")
        }

        // Attach process terminated listener (with error handling)
        try {
            ProcessTerminatedListener.attach(processHandler, project, "Lambda function terminated")
        } catch (e: Exception) {
            LOG.warn("Failed to attach ProcessTerminatedListener (non-fatal): ${e.message}")
        }
        
        // Stop the Test Tool when the Lambda process terminates or is being destroyed
        // Use the centralized cleanup method which has proper guards against duplicate calls
        val cleanupListener = object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                LOG.info("Lambda process terminated (exit code: ${event.exitCode}), stopping Test Tool...")
                ensureCleanup()
            }
            
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                if (willBeDestroyed) {
                    LOG.info("Lambda process will be destroyed, stopping Test Tool...")
                    ensureCleanup()
                }
            }
        }
        
        processHandler.addProcessListener(cleanupListener)
        
        return processHandler
    }
    
    /**
     * Ensures the Lambda Test Tool is running on the specified port.
     * 
     * The Test Tool is started with registerWithIde = false to keep it hidden from the Run/Debug tool window.
     * Only the Lambda function process should be visible to users. The Test Tool runs as a background process
     * and is automatically stopped when the Lambda process terminates.
     */
    private fun ensureTestToolRunning(
        testToolManager: TestToolManager,
        port: Int,
        workingDir: File
    ) {
        val currentStatus = testToolManager.getStatus()
        if (testToolManager.isRunning()) {
            val currentPort = currentStatus.port
            if (currentPort != null && currentPort != port) {
                LOG.warn("Test Tool is already running on port $currentPort, but requested port is $port")
                throw TestToolStartException(
                    "Test Tool is already running on port $currentPort. " +
                    "Please stop it first or configure your run configuration to use port $currentPort."
                )
            } else {
                LOG.info("Test Tool already running on port $port")
                return
            }
        }
        
        LOG.info("Starting Lambda Test Tool on port $port (hidden from Run/Debug window)...")
        val testToolReady = CountDownLatch(1)
        val testToolStarted = AtomicBoolean(false)
        var startError: String? = null
        
        // Start Test Tool as detached process (registerWithIde = false) to keep it hidden from IDE
        // Only the Lambda function process will appear in the Run/Debug tool window
        testToolManager.startTestTool(
            port = port,
            workingDirectory = workingDir,
            onStarted = { success: Boolean ->
                testToolStarted.set(success)
                if (!success) {
                    startError = "Test Tool failed to start (callback returned false)"
                }
                testToolReady.countDown()
            },
            registerWithIde = false
        )
        
        // Wait for test tool to start (max 30 seconds)
        if (!testToolReady.await(TEST_TOOL_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw TestToolStartException("Test Tool failed to start within ${TEST_TOOL_START_TIMEOUT_SECONDS}s timeout")
        }
        
        if (!testToolStarted.get()) {
            val errorMsg = startError ?: "Test Tool failed to start"
            throw TestToolStartException(errorMsg)
        }
        
        LOG.info("Test tool started successfully on port $port")
    }
    
    private fun buildLambdaCommandLine(
        lambdaProject: com.aws.lambda.testtool.models.LambdaProject,
        port: Int,
        workingDir: File
    ): GeneralCommandLine? {
        // Log the target framework being used
        LOG.info("Executing Lambda function with target framework: ${lambdaProject.targetFramework}")
        LOG.info("Project type: ${if (lambdaProject.isExecutable) "Executable" else "Handler-based"}")
        
        // For handler-based Lambda functions, we don't run a separate process
        // The Lambda Test Tool discovers and invokes handlers through its API
        if (lambdaProject.isHandlerBased) {
            LOG.info("Handler-based Lambda detected. Test Tool will discover handlers from assembly.")
            LOG.info("No separate process needed - handlers will be invoked through Test Tool API.")
            // Return null to indicate no process should be started
            // The Test Tool is already running and will handle handler discovery
            return null
        }
        
        // For executable projects, run the DLL directly
        val dllPath = lambdaProject.dllPath.absolutePath
        LOG.info("DLL path: $dllPath")
        
        // Verify the required runtime is available
        val runtimeAvailable = DotNetBuildHelper.isRuntimeAvailable(lambdaProject.targetFramework)
        if (!runtimeAvailable) {
            LOG.warn("Runtime for ${lambdaProject.targetFramework} may not be installed. " +
                    "The execution may fail. Install the runtime with: dotnet --list-runtimes")
        }
        
        // Verify the runtimeconfig.json exists (it specifies which runtime to use)
        val runtimeConfigPath = File(workingDir, "${lambdaProject.name}.runtimeconfig.json")
        if (runtimeConfigPath.exists()) {
            LOG.info("Found runtimeconfig.json: ${runtimeConfigPath.absolutePath}")
        } else {
            LOG.warn("runtimeconfig.json not found at ${runtimeConfigPath.absolutePath}. " +
                    "The dotnet CLI will use the default runtime, which may not match the project's target framework.")
        }
        
        // Create command line with a descriptive process name
        // In debug mode, we must NOT use shell wrapper so Rider can attach the debugger
        // In run mode, we can use shell wrapper for better process naming
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")

        val commandLine = if (isDebugMode || isWindows) {
            // Debug mode OR Windows: Use dotnet directly (required for Rider's debugger to attach)
            // This ensures Rider's native .NET debugger can recognize and attach to the process
            LOG.info("Using direct dotnet invocation for debugger attachment")
            GeneralCommandLine("dotnet", dllPath)
                .withWorkDirectory(workingDir)
        } else {
            // Run mode on Unix: Use shell wrapper for nicer process naming
            val sanitizedProjectName = lambdaProject.name.replace(Regex("[^a-zA-Z0-9.]"), "")
            val processName = if (sanitizedProjectName.isNotEmpty() && sanitizedProjectName != "LambdaTestTool") {
                sanitizedProjectName
            } else {
                "LambdaFunction"
            }
            val shellCommand = "exec -a '$processName' dotnet '$dllPath'"
            LOG.info("Setting Lambda function process name to: $processName")
            GeneralCommandLine("sh", "-c", shellCommand)
                .withWorkDirectory(workingDir)
        }
        
        // Set the Lambda Runtime API environment variable
        val envVars = mutableMapOf<String, String>()
        envVars["AWS_LAMBDA_RUNTIME_API"] = "127.0.0.1:$port"

        // Set DOTNET_ROOT to help dotnet find the correct runtime if needed
        // The runtimeconfig.json should handle this, but this can help in some cases
        val dotnetRoot = System.getenv("DOTNET_ROOT")
        if (dotnetRoot != null) {
            envVars["DOTNET_ROOT"] = dotnetRoot
        }

        // Add user-defined environment variables
        envVars.putAll(configuration.environmentVariables)

        // Set environment variables to help identify the process
        envVars["LAMBDA_TEST_PROCESS_NAME"] = "Lambda Test: ${lambdaProject.name}"
        envVars["LAMBDA_TEST_PROJECT"] = lambdaProject.name

        // In debug mode, use a .NET Startup Hook to pause the process until the debugger attaches.
        // This ensures breakpoints work from the very first line of user code.
        // The startup hook runs BEFORE the application's Main() method.
        if (isDebugMode) {
            val hookDllPath = extractStartupHook()
            if (hookDllPath != null) {
                LOG.info("Debug mode: Using startup hook to pause process until debugger attaches")
                envVars["DOTNET_STARTUP_HOOKS"] = hookDllPath
                envVars["LAMBDA_DEBUG_WAIT"] = "1"
            } else {
                LOG.warn("Debug mode: Could not extract startup hook DLL. Debugger may not attach in time.")
            }
        }

        commandLine.withEnvironment(envVars)
        
        return commandLine
    }
    
    /**
     * Extracts the .NET startup hook DLL from plugin resources to a temp directory.
     * The hook pauses the .NET process at startup until a debugger attaches,
     * ensuring breakpoints work from the first line of user code.
     *
     * @return The absolute path to the extracted DLL, or null if extraction fails.
     */
    private fun extractStartupHook(): String? {
        return try {
            val hookDir = File(System.getProperty("java.io.tmpdir"), "lambda-debug-hook")
            if (!hookDir.exists()) {
                hookDir.mkdirs()
            }
            val hookDll = File(hookDir, "LambdaDebugStartupHook.dll")

            // Only extract if not already present or if the resource is newer
            if (!hookDll.exists()) {
                val resourceStream = javaClass.getResourceAsStream("/hooks/LambdaDebugStartupHook.dll")
                if (resourceStream == null) {
                    LOG.warn("Startup hook resource not found in plugin JAR")
                    return null
                }
                resourceStream.use { input ->
                    hookDll.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                LOG.info("Extracted startup hook to: ${hookDll.absolutePath}")
            } else {
                LOG.info("Using cached startup hook: ${hookDll.absolutePath}")
            }

            hookDll.absolutePath
        } catch (e: Exception) {
            LOG.warn("Failed to extract startup hook DLL: ${e.message}", e)
            null
        }
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val processHandler = startProcess()
        val console = createConsole(executor)
        console?.attachToProcess(processHandler)

        // Monitor process for errors with intelligent diagnostics
        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                // Exit codes >= 128 are signal-based (128 + signal_number) and normal for user-initiated stops
                // e.g. 130=SIGINT, 134=SIGABRT, 137=SIGKILL, 143=SIGTERM
                if (event.exitCode != 0 && event.exitCode != null && event.exitCode < 128) {
                    LOG.error("Lambda process terminated with exit code: ${event.exitCode}")
                    val logPath = processOutputLogPath

                    // Run diagnostics on the log file to identify issues
                    val diagnostics = if (logPath != null) {
                        LambdaDiagnostics.analyzeLogFile(logPath)
                    } else {
                        null
                    }

                    val errorMsg = buildString {
                        append("❌ Lambda Startup Failed (exit code: ${event.exitCode})")
                        append("\n\n📋 Check the Run/Debug Console for Error Details:")
                        append("\nThe actual error output is displayed in the Run/Debug console at the bottom of Rider.")

                        if (diagnostics != null && diagnostics.issues.isNotEmpty()) {
                            append("\n\n🔍 Diagnostic Suggestions:")
                            append("\n${LambdaDiagnostics.formatDiagnosticsMessage(diagnostics)}")
                        } else {
                            append("\n\n💡 Common causes:")
                            append("\n• Missing configuration in appsettings.json")
                            append("\n• Uninitialized service dependencies in Program.cs")
                            append("\n• Missing environment variables")
                            append("\n• Invalid database connection strings")
                        }

                        if (logPath != null) {
                            append("\n\n📁 Process lifecycle log: $logPath")
                        }
                    }

                    try {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("AWS Lambda Test Tool")
                            ?.createNotification(errorMsg, com.intellij.notification.NotificationType.ERROR)
                            ?.notify(project)
                    } catch (e: Exception) {
                        LOG.warn("Failed to show error notification", e)
                    }
                }
            }
        })

        // Add an additional cleanup listener at the execution level
        // This ensures cleanup happens even if the process handler's listeners don't fire
        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                if (willBeDestroyed) {
                    LOG.info("Execution-level: Process handler will be destroyed, ensuring Test Tool cleanup...")
                    ensureCleanup()
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                LOG.info("Execution-level: Process handler terminated, ensuring Test Tool cleanup...")
                // Add a small delay to ensure the other cleanup listeners have a chance to run first
                // This prevents duplicate cleanup calls
                Thread {
                    Thread.sleep(100)
                    ensureCleanup()
                }.start()
            }
        })

        return DefaultExecutionResult(console, processHandler)
    }
    
    /**
     * Ensures cleanup of the Test Tool. This is called from multiple places to ensure
     * the Test Tool is stopped when execution ends, regardless of how it ends.
     * Uses AtomicBoolean to prevent duplicate cleanup calls.
     */
    private val cleanupExecuted = AtomicBoolean(false)
    
    private fun ensureCleanup() {
        if (!cleanupExecuted.compareAndSet(false, true)) {
            LOG.debug("Cleanup already executed, skipping...")
            return
        }
        
        val manager = testToolManager
        val port = testToolPort
        
        if (manager != null && port != null) {
            LOG.info("Executing cleanup for Test Tool (port: $port)...")
            ensureTestToolStopped(manager, port)
        } else {
            LOG.debug("No Test Tool to cleanup (manager or port is null)")
        }
    }
    
    /**
     * Ensures the Test Tool is stopped, with logging and error handling.
     * This method is called from multiple places to ensure cleanup happens.
     */
    private fun ensureTestToolStopped(testToolManager: TestToolManager, port: Int) {
        try {
            LOG.info("Ensuring Test Tool is stopped (port: $port)...")
            
            // Always try to stop, even if isRunning() returns false
            // The process might be in a state where isRunning() is false but the process still exists
            val status = testToolManager.getStatus()
            val isRunning = testToolManager.isRunning()
            
            LOG.info("Test Tool status: ${status.state}, isRunning: $isRunning, port: ${status.port}")
            
            // Check if the Test Tool is running on a different port
            // Only stop if the port matches (or status port is null, meaning we might have started it)
            // This prevents stopping Test Tools running on other ports from other configurations
            val statusPort = status.port
            if (statusPort != null && statusPort != port) {
                LOG.info("Test Tool is running on port $statusPort, but this configuration uses port $port. " +
                        "Not stopping to avoid affecting other instances.")
                return
            }
            
            // Always try to stop, regardless of status
            // Even if status says NOT_RUNNING, we should still attempt to stop and kill processes on the port
            // This handles cases where the status is out of sync but processes are still running
            if (statusPort == null && !isRunning) {
                LOG.info("Test Tool status shows NOT_RUNNING, but attempting stop anyway and port-based cleanup")
                // Still call stopTestTool - it will handle port cleanup internally
                // This ensures we kill any processes on the port even if status is out of sync
            }
            
            // Always try to stop, regardless of status (but only if port matches)
            // The status might be out of sync with the actual process state
            // This ensures we clean up even if the manager thinks it's not running
            LOG.info("Stopping Test Tool (status check: state=${status.state}, isRunning=$isRunning, port=$port)...")
            
            // Use a countdown latch to ensure we wait for stop to complete
            val stopLatch = CountDownLatch(1)
            // Pass the port explicitly so port-based cleanup works even if status is out of sync
            testToolManager.stopTestTool(port = port, onStopped = {
                LOG.info("Test Tool stop callback invoked")
                stopLatch.countDown()
            })
            
            // Wait up to 5 seconds for stop to complete
            if (!stopLatch.await(5, TimeUnit.SECONDS)) {
                LOG.warn("Test Tool stop did not complete within timeout")
            } else {
                LOG.info("Test Tool stopped successfully")
            }
        } catch (e: Exception) {
            LOG.error("Error stopping Test Tool", e)
            // Even if there's an error, try to force stop
            try {
                testToolManager.stopTestTool(port = port, onStopped = null)
            } catch (e2: Exception) {
                LOG.error("Force stop also failed", e2)
            }
        }
    }
    
    override fun createConsole(executor: Executor): ConsoleView? {
        val consoleBuilder = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
        return consoleBuilder.console
    }
    
    fun getProject() = project
    fun getConfiguration() = configuration
    
    /**
     * Creates a dummy process handler for handler-based Lambdas where no separate process is needed.
     * The Test Tool handles handler discovery and invocation through its API.
     */
    private fun createDummyProcessHandler(port: Int, testToolManager: TestToolManager): ProcessHandler {
        // Create a simple process handler that doesn't actually run anything
        // The Test Tool is already running and will handle handler discovery
        val dummyCommand = GeneralCommandLine("echo", "Lambda Test Tool is handling handler discovery")
        val processHandler = KillableColoredProcessHandler(dummyCommand)
        
        // Add cleanup listener to stop Test Tool when execution ends
        // Use the centralized cleanup method which has proper guards against duplicate calls
        val cleanupListener = object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                LOG.info("Lambda execution ended, stopping Test Tool...")
                ensureCleanup()
            }
            
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                if (willBeDestroyed) {
                    LOG.info("Lambda execution will be destroyed, stopping Test Tool...")
                    ensureCleanup()
                }
            }
        }
        
        processHandler.addProcessListener(cleanupListener)
        
        // Start the dummy process immediately (it will exit right away)
        processHandler.startNotify()
        
        return processHandler
    }
}
