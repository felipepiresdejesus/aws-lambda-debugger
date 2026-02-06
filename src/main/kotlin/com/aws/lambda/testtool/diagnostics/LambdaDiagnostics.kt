package com.aws.lambda.testtool.diagnostics

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Intelligent diagnostics for Lambda startup failures.
 *
 * Analyzes process logs to identify common issues and suggest fixes,
 * similar to Cursor IDE's debug approach.
 */
object LambdaDiagnostics {

    private val LOG = Logger.getInstance(LambdaDiagnostics::class.java)

    data class DiagnosticResult(
        val succeeded: Boolean,
        val exitCode: Int?,
        val issues: List<Issue>,
        val suggestions: List<String>
    )

    data class Issue(
        val severity: Severity,
        val title: String,
        val description: String,
        val pattern: String? = null
    )

    enum class Severity {
        ERROR, WARNING, INFO
    }

    /**
     * Analyzes a process log file to identify issues and generate suggestions.
     */
    fun analyzeLogFile(logFilePath: String): DiagnosticResult {
        return try {
            val file = File(logFilePath)
            if (!file.exists()) {
                LOG.warn("Log file does not exist: $logFilePath")
                return DiagnosticResult(
                    succeeded = false,
                    exitCode = null,
                    issues = listOf(
                        Issue(
                            Severity.ERROR,
                            "Log file not found",
                            "The process log file could not be found at: $logFilePath"
                        )
                    ),
                    suggestions = emptyList()
                )
            }

            val content = file.readText()
            val lines = content.split("\n")

            val issues = mutableListOf<Issue>()
            val suggestions = mutableSetOf<String>()
            var exitCode: Int? = null
            var succeeded = false

            // Extract exit code
            val exitCodeLine = lines.findLast { it.contains("exit code:") }
            if (exitCodeLine != null) {
                val codeMatch = Regex("exit code:\\s*(\\d+)").find(exitCodeLine)
                exitCode = codeMatch?.groupValues?.get(1)?.toIntOrNull()
                succeeded = exitCode == 0
            }

            // Analyze for common error patterns
            analyzeForDotNetErrors(content, issues, suggestions)
            analyzeForMissingDependencies(content, issues, suggestions)
            analyzeForPortConflicts(content, issues, suggestions)
            analyzeForRuntimeIssues(content, issues, suggestions)
            analyzeForHandlerIssues(content, issues, suggestions)
            analyzeForProgramStartupIssues(content, issues, suggestions)

            DiagnosticResult(
                succeeded = succeeded,
                exitCode = exitCode,
                issues = issues,
                suggestions = suggestions.toList()
            )
        } catch (e: Exception) {
            LOG.error("Failed to analyze log file", e)
            DiagnosticResult(
                succeeded = false,
                exitCode = null,
                issues = listOf(
                    Issue(
                        Severity.ERROR,
                        "Analysis failed",
                        "Could not analyze the log file: ${e.message}"
                    )
                ),
                suggestions = listOf("Check the log file manually for errors")
            )
        }
    }

    private fun analyzeForDotNetErrors(content: String, issues: MutableList<Issue>, suggestions: MutableSet<String>) {
        // Check for common .NET errors
        val patterns = mapOf(
            "FileNotFoundException" to Pair(
                "Missing assembly or file",
                "The Lambda assembly or a dependency is missing"
            ),
            "Could not load file or assembly" to Pair(
                "Assembly loading failure",
                "A required .NET assembly could not be loaded"
            ),
            "BadImageFormatException" to Pair(
                "Invalid assembly format",
                "The assembly file is corrupted or compiled for a different platform"
            ),
            "TargetInvocationException" to Pair(
                "Runtime execution error",
                "An unhandled exception occurred during Lambda execution"
            )
        )

        patterns.forEach { (pattern, info) ->
            if (content.contains(pattern, ignoreCase = true)) {
                issues.add(Issue(
                    Severity.ERROR,
                    info.first,
                    info.second,
                    pattern
                ))
                when {
                    pattern.contains("assembly", ignoreCase = true) -> {
                        suggestions.add("Ensure all NuGet packages are restored: 'dotnet restore'")
                        suggestions.add("Check that the Lambda project has been built successfully")
                    }
                    pattern.contains("BadImage", ignoreCase = true) -> {
                        suggestions.add("Try cleaning and rebuilding the project: 'dotnet clean && dotnet build'")
                        suggestions.add("Verify your target framework matches your runtime")
                    }
                }
            }
        }
    }

    private fun analyzeForMissingDependencies(content: String, issues: MutableList<Issue>, suggestions: MutableSet<String>) {
        if (content.contains("dependency not found", ignoreCase = true) ||
            content.contains("missing dependency", ignoreCase = true)) {

            issues.add(Issue(
                Severity.ERROR,
                "Missing dependencies",
                "The Lambda function has missing dependencies"
            ))
            suggestions.add("Run 'dotnet restore' to restore NuGet packages")
            suggestions.add("Verify appsettings.json configuration is present and correct")
        }
    }

    private fun analyzeForPortConflicts(content: String, issues: MutableList<Issue>, suggestions: MutableSet<String>) {
        if (content.contains("port", ignoreCase = true) &&
            (content.contains("already in use", ignoreCase = true) ||
             content.contains("address already in use", ignoreCase = true))) {

            issues.add(Issue(
                Severity.ERROR,
                "Port conflict detected",
                "The Test Tool port is already in use by another process"
            ))
            suggestions.add("Stop any other Lambda debugging sessions")
            suggestions.add("Change the port in the Run configuration to an available port")
            suggestions.add("Kill the process using the port: 'lsof -i :5050' then 'kill -9 <PID>'")
        }
    }

    private fun analyzeForRuntimeIssues(content: String, issues: MutableList<Issue>, suggestions: MutableSet<String>) {
        if (content.contains("no such file or directory", ignoreCase = true) ||
            content.contains("dotnet: not found", ignoreCase = true)) {

            issues.add(Issue(
                Severity.ERROR,
                "Runtime not found",
                ".NET runtime or dotnet CLI is not available"
            ))
            suggestions.add("Install .NET runtime: https://dotnet.microsoft.com/download")
            suggestions.add("Verify 'dotnet --version' works in terminal")
            suggestions.add("Check DOTNET_ROOT environment variable is set correctly")
        }

        if (content.contains("target framework", ignoreCase = true) ||
            content.contains("netcore", ignoreCase = true)) {

            issues.add(Issue(
                Severity.WARNING,
                "Target framework issue",
                "Check that the target framework is compatible with your .NET runtime"
            ))
            suggestions.add("View .NET runtimes installed: 'dotnet --list-runtimes'")
            suggestions.add("Ensure your project's .csproj target framework matches an installed runtime")
        }
    }

    private fun analyzeForHandlerIssues(content: String, issues: MutableList<Issue>, suggestions: MutableSet<String>) {
        if (content.contains("handler", ignoreCase = true) &&
            content.contains("not found", ignoreCase = true)) {

            issues.add(Issue(
                Severity.ERROR,
                "Lambda handler not found",
                "The Lambda handler could not be discovered or invoked"
            ))
            suggestions.add("Verify the handler is defined in your Lambda class")
            suggestions.add("Check that the handler signature matches AWS Lambda requirements")
            suggestions.add("Ensure the assembly name matches your project name")
        }
    }

    private fun analyzeForProgramStartupIssues(content: String, issues: MutableList<Issue>, suggestions: MutableSet<String>) {
        // Program.cs initialization failures
        if (content.contains("Program.cs", ignoreCase = true) ||
            content.contains("ConfigurationBuilder", ignoreCase = true) ||
            content.contains("ServiceCollection", ignoreCase = true)) {

            if (content.contains("required", ignoreCase = true) ||
                content.contains("null", ignoreCase = true)) {

                issues.add(Issue(
                    Severity.ERROR,
                    "Program initialization failed",
                    "An error occurred during Program.cs startup (likely in service configuration or dependency injection)"
                ))
                suggestions.add("Check Program.cs for missing configuration values")
                suggestions.add("Verify all required services are registered in the ServiceCollection")
                suggestions.add("Ensure appsettings.json exists and contains all required configuration")
                suggestions.add("Check that environment-specific appsettings files are present")
            }
        }

        // Unhandled exceptions during startup
        if (content.contains("unhandled", ignoreCase = true) ||
            content.contains("exception", ignoreCase = true) ||
            content.contains("error", ignoreCase = true)) {

            issues.add(Issue(
                Severity.ERROR,
                "Unhandled exception during startup",
                "Check the Run/Debug console in Rider for the full error stack trace"
            ))
            suggestions.add("Look at the Run/Debug console output for the complete error message")
            suggestions.add("Check the Inner Exception for the root cause")
            suggestions.add("Add null checks for configuration values in Program.cs")
        }
    }

    /**
     * Formats diagnostic results for display to the user.
     */
    fun formatDiagnosticsMessage(result: DiagnosticResult): String {
        return buildString {
            append("=== Lambda Diagnostics Report ===\n\n")

            if (result.succeeded) {
                append("✅ Process completed successfully (exit code: ${result.exitCode})\n")
            } else {
                append("❌ Process failed (exit code: ${result.exitCode ?: "unknown"})\n\n")

                if (result.issues.isNotEmpty()) {
                    append("Found ${result.issues.size} issue(s):\n\n")
                    result.issues.forEach { issue ->
                        append("• [${issue.severity}] ${issue.title}\n")
                        append("  ${issue.description}\n")
                        if (issue.pattern != null) {
                            append("  Pattern: ${issue.pattern}\n")
                        }
                        append("\n")
                    }
                } else {
                    append("No specific issues identified, but process failed.\n")
                    append("Check the log file for error details.\n\n")
                }
            }

            if (result.suggestions.isNotEmpty()) {
                append("💡 Suggestions:\n")
                result.suggestions.forEachIndexed { index, suggestion ->
                    append("${index + 1}. $suggestion\n")
                }
            }
        }
    }
}
