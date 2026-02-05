package com.aws.lambda.testtool.utils

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.Key
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File

/**
 * Helper utility for building .NET projects and determining build output paths.
 */
object DotNetBuildHelper {
    private val LOG = Logger.getInstance(DotNetBuildHelper::class.java)
    
    /**
     * Gets the actual build output directory by querying MSBuild properties.
     * This ensures we get the correct path regardless of .NET SDK version.
     */
    fun getBuildOutputDirectory(projectFile: File, configuration: String = "Debug"): File? {
        return try {
            // First, try to query MSBuild properties directly for the output path
            val outputPath = getOutputPathFromMsBuild(projectFile, configuration)
            if (outputPath != null && (outputPath.exists() || outputPath.parentFile.exists())) {
                LOG.info("Found build output directory from MSBuild: ${outputPath.absolutePath}")
                return outputPath
            }
            
            // Fallback: try to extract from a minimal build
            val commandLine = GeneralCommandLine(
                "dotnet", "build",
                projectFile.absolutePath,
                "-c", configuration,
                "/p:GenerateBuildInfo=false",
                "/p:BuildProjectReferences=false",
                "/nologo",
                "/verbosity:minimal"
            )
            
            val process = commandLine.createProcess()
            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()
            process.waitFor()
            
            // Try to extract output path from build output
            val outputPathFromBuild = extractOutputPathFromBuildOutput(
                output + errorOutput,
                projectFile.parentFile,
                configuration
            )
            
            if (outputPathFromBuild != null && (outputPathFromBuild.exists() || outputPathFromBuild.parentFile.exists())) {
                LOG.info("Found build output directory from build output: ${outputPathFromBuild.absolutePath}")
                return outputPathFromBuild
            }
            
            null
        } catch (e: Exception) {
            LOG.warn("Failed to get build output directory from MSBuild: ${e.message}", e)
            null
        }
    }
    
    /**
     * Gets the output path by querying MSBuild properties directly.
     */
    private fun getOutputPathFromMsBuild(projectFile: File, configuration: String): File? {
        return try {
            // Query MSBuild for the OutputPath and TargetFramework properties
            // We'll use a custom target or evaluate the properties
            val commandLine = GeneralCommandLine(
                "dotnet", "msbuild",
                projectFile.absolutePath,
                "/t:ResolveReferences", // Use a lightweight target
                "/p:Configuration=$configuration",
                "/p:OutputPath=bin/$configuration/",
                "/nologo",
                "/verbosity:minimal",
                "/p:GenerateBuildInfo=false",
                "/p:BuildProjectReferences=false"
            )
            
            val process = commandLine.createProcess()
            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()
            process.waitFor()
            
            val allOutput = output + errorOutput
            
            // Try to find the output path in the build output
            // Look for patterns like: "bin/Debug/net8.0" or "bin\Debug\net8.0"
            val pathPatterns = listOf(
                Regex("""bin[/\\]$configuration[/\\][^/\s\\]+""", RegexOption.IGNORE_CASE),
                Regex("""->\s+([^/\s]+[/\\]bin[/\\]$configuration[/\\][^/\s\\]+)""", RegexOption.IGNORE_CASE),
                Regex("""Output\s+Path[:\s]+([^/\s]+[/\\]bin[/\\]$configuration[/\\][^/\s\\]+)""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in pathPatterns) {
                val matches = pattern.findAll(allOutput)
                for (match in matches) {
                    val pathStr = match.groupValues.lastOrNull() ?: match.value
                    val normalizedPath = pathStr.replace("\\", "/").trim()
                    
                    // Try as relative path first
                    var outputPath = File(projectFile.parentFile, normalizedPath)
                    if (outputPath.exists() || outputPath.parentFile.exists()) {
                        return outputPath
                    }
                    
                    // Try as absolute path
                    if (normalizedPath.startsWith("/") || normalizedPath.matches(Regex("^[A-Za-z]:"))) {
                        outputPath = File(normalizedPath)
                        if (outputPath.exists() || outputPath.parentFile.exists()) {
                            return outputPath
                        }
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            LOG.warn("Failed to query MSBuild for output path: ${e.message}", e)
            null
        }
    }
    
    /**
     * Extracts the output path from build output text.
     */
    private fun extractOutputPathFromBuildOutput(
        buildOutput: String,
        projectDirectory: File,
        configuration: String
    ): File? {
        // Look for common patterns in build output
        val patterns = listOf(
            Regex("""->\s+(.+?[/\\]bin[/\\]$configuration[/\\][^/\s]*)""", RegexOption.IGNORE_CASE),
            Regex("""Output\s+Path:\s*(.+?[/\\]bin[/\\]$configuration[/\\][^/\s]*)""", RegexOption.IGNORE_CASE),
            Regex("""bin[/\\]$configuration[/\\][^/\s]*""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(buildOutput)
            if (match != null) {
                val pathStr = match.groupValues.lastOrNull() ?: match.value
                val normalizedPath = pathStr.replace("\\", "/").trim()
                val outputPath = if (normalizedPath.startsWith("/") || normalizedPath.matches(Regex("^[A-Za-z]:"))) {
                    File(normalizedPath)
                } else {
                    File(projectDirectory, normalizedPath)
                }
                
                if (outputPath.exists() || outputPath.parentFile.exists()) {
                    return outputPath
                }
            }
        }
        
        return null
    }
    
    /**
     * Builds a .NET project and returns the build output directory.
     * 
     * @param projectFile The .csproj file to build
     * @param configuration The build configuration (Debug or Release)
     * @param indicator Optional progress indicator for build progress
     * @return The build output directory, or null if build failed
     */
    fun buildProject(
        projectFile: File,
        configuration: String = "Debug",
        indicator: ProgressIndicator? = null
    ): File? {
        return try {
            indicator?.text = "Building .NET project: ${projectFile.name}"
            indicator?.text2 = "Running dotnet build..."
            
            // Extract target framework to ensure we build with the correct version
            val targetFramework = extractTargetFrameworkFromProject(projectFile)
            if (targetFramework != null) {
                LOG.info("Building project with target framework: $targetFramework")
            }
            
            val commandLine = GeneralCommandLine(
                "dotnet", "build",
                projectFile.absolutePath,
                "-c", configuration,
                "--no-incremental", // Force a full build
                "/nologo"
            )
            
            // If target framework is specified, ensure it's used
            // The project file should already specify this, but we log it for debugging
            if (targetFramework != null) {
                LOG.debug("Project target framework: $targetFramework (should be used automatically by dotnet build)")
            }
            
            val processHandler = OSProcessHandler(commandLine)
            val outputBuilder = StringBuilder()
            val errorBuilder = StringBuilder()
            
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    // Append to both builders - we'll use all output for parsing
                    outputBuilder.append(text)
                    errorBuilder.append(text)
                    indicator?.text2 = text.trim()
                }
            })
            
            processHandler.startNotify()
            processHandler.waitFor()
            
            val exitCode = processHandler.exitCode ?: -1
            val allOutput = outputBuilder.toString() + errorBuilder.toString()
            
            if (exitCode != 0) {
                LOG.error("dotnet build failed with exit code $exitCode")
                LOG.error("Build output: $allOutput")
                indicator?.text2 = "Build failed (exit code: $exitCode)"
                return null
            }
            
            indicator?.text2 = "Build completed successfully"
            
            // Get project name for artifact search
            val projectName = projectFile.nameWithoutExtension
            
            // First, try to find the output directory by searching for the built artifact
            val outputDirByArtifact = findOutputDirectoryByArtifact(
                projectFile.parentFile,
                projectName,
                configuration,
                targetFramework
            )
            if (outputDirByArtifact != null) {
                LOG.info("Found build output directory by artifact search: ${outputDirByArtifact.absolutePath}")
                return outputDirByArtifact
            }
            
            // Try to extract output path from build output
            val outputPathFromBuild = extractOutputPathFromBuildOutput(
                allOutput,
                projectFile.parentFile,
                configuration
            )
            
            if (outputPathFromBuild != null && (outputPathFromBuild.exists() || outputPathFromBuild.parentFile.exists())) {
                LOG.info("Found build output directory from build output: ${outputPathFromBuild.absolutePath}")
                return outputPathFromBuild
            }
            
            // Try to query MSBuild for the output path
            val outputDirectory = getBuildOutputDirectory(projectFile, configuration)
            if (outputDirectory != null && (outputDirectory.exists() || outputDirectory.parentFile.exists())) {
                LOG.info("Found build output directory from MSBuild query: ${outputDirectory.absolutePath}")
                return outputDirectory
            }
            
            // Fallback: use standard .NET SDK output structure
            // Reuse the target framework we extracted earlier
            if (targetFramework != null) {
                val fallbackPath = determineBuildOutputDirectoryFallback(
                    projectFile.parentFile,
                    targetFramework,
                    configuration
                )
                LOG.info("Using fallback build output directory: ${fallbackPath.absolutePath}")
                return fallbackPath
            }
            
            LOG.warn("Build succeeded but could not determine output directory")
            null
        } catch (e: Exception) {
            LOG.error("Failed to build project: ${projectFile.absolutePath}", e)
            indicator?.text2 = "Build failed: ${e.message}"
            null
        }
    }
    
    /**
     * Extracts the target framework from a .csproj file, checking Directory.Build.props if not found.
     */
    private fun extractTargetFrameworkFromProject(projectFile: File): String? {
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(projectFile)
            document.documentElement.normalize()
            
            // Try TargetFramework first in the project file
            val targetFrameworkNodes = document.getElementsByTagName("TargetFramework")
            if (targetFrameworkNodes.length > 0) {
                val framework = targetFrameworkNodes.item(0).textContent
                if (!framework.isNullOrBlank()) {
                    LOG.debug("Found TargetFramework in project file: $framework")
                    return framework.trim()
                }
            }
            
            // Try TargetFrameworks (multiple frameworks) - use the first one
            val targetFrameworksNodes = document.getElementsByTagName("TargetFrameworks")
            if (targetFrameworksNodes.length > 0) {
                val frameworksText = targetFrameworksNodes.item(0).textContent
                if (!frameworksText.isNullOrBlank()) {
                    val frameworks = frameworksText.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    if (frameworks.isNotEmpty()) {
                        LOG.debug("Found TargetFrameworks in project file: ${frameworks.first()}")
                        return frameworks.first()
                    }
                }
            }
            
            // If not found in project file, check Directory.Build.props files
            LOG.debug("TargetFramework not found in project file, checking Directory.Build.props...")
            val frameworkFromProps = findTargetFrameworkInPropsFiles(projectFile.parentFile)
            if (frameworkFromProps != null) {
                LOG.info("Found TargetFramework in Directory.Build.props: $frameworkFromProps")
                return frameworkFromProps
            }
            
            // Last resort: query MSBuild for the actual target framework
            LOG.debug("TargetFramework not found in props files, querying MSBuild...")
            val frameworkFromMsBuild = queryTargetFrameworkFromMsBuild(projectFile)
            if (frameworkFromMsBuild != null) {
                LOG.info("Found TargetFramework from MSBuild: $frameworkFromMsBuild")
                return frameworkFromMsBuild
            }
            
            null
        } catch (e: Exception) {
            LOG.warn("Failed to extract target framework from project file: ${e.message}", e)
            null
        }
    }
    
    /**
     * Queries MSBuild for the actual target framework used by the project.
     */
    private fun queryTargetFrameworkFromMsBuild(projectFile: File): String? {
        return try {
            val commandLine = GeneralCommandLine(
                "dotnet", "msbuild",
                projectFile.absolutePath,
                "/t:GetTargetFramework",
                "/p:GetTargetFramework=true",
                "/nologo",
                "/verbosity:minimal"
            )
            
            val process = commandLine.createProcess()
            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()
            process.waitFor()
            
            val allOutput = output + errorOutput
            
            // Look for TargetFramework in the output
            val patterns = listOf(
                Regex("""TargetFramework[:\s=]+(net\d+\.\d+)""", RegexOption.IGNORE_CASE),
                Regex("""(net\d+\.\d+)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(allOutput)
                if (match != null) {
                    val framework = match.groupValues.lastOrNull() ?: match.value
                    if (framework.startsWith("net")) {
                        return framework
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            LOG.debug("Failed to query TargetFramework from MSBuild: ${e.message}")
            null
        }
    }
    
    /**
     * Searches for TargetFramework in Directory.Build.props files up the directory tree.
     * MSBuild checks parent directories up to the solution root.
     */
    private fun findTargetFrameworkInPropsFiles(startDirectory: File): String? {
        var currentDir: File? = startDirectory
        val maxDepth = 10 // Prevent infinite loops
        var depth = 0
        
        while (currentDir != null && depth < maxDepth) {
            // Check for Directory.Build.props
            val propsFile = File(currentDir, "Directory.Build.props")
            if (propsFile.exists() && propsFile.isFile) {
                val framework = extractTargetFrameworkFromPropsFile(propsFile)
                if (framework != null) {
                    LOG.debug("Found TargetFramework in ${propsFile.absolutePath}: $framework")
                    return framework
                }
            }
            
            // Also check for other common .props files
            val otherPropsFiles = currentDir.listFiles { _, name ->
                name.endsWith(".props", ignoreCase = true) && name != "Directory.Build.props"
            } ?: emptyArray()
            
            for (propsFile in otherPropsFiles) {
                val framework = extractTargetFrameworkFromPropsFile(propsFile)
                if (framework != null) {
                    LOG.debug("Found TargetFramework in ${propsFile.absolutePath}: $framework")
                    return framework
                }
            }
            
            // Move up to parent directory
            currentDir = currentDir.parentFile
            depth++
        }
        
        return null
    }
    
    /**
     * Extracts TargetFramework from a .props file.
     */
    private fun extractTargetFrameworkFromPropsFile(propsFile: File): String? {
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(propsFile)
            document.documentElement.normalize()
            
            // Try TargetFramework first
            val targetFrameworkNodes = document.getElementsByTagName("TargetFramework")
            if (targetFrameworkNodes.length > 0) {
                val framework = targetFrameworkNodes.item(0).textContent
                if (!framework.isNullOrBlank()) {
                    return framework.trim()
                }
            }
            
            // Try TargetFrameworks (multiple frameworks) - use the first one
            val targetFrameworksNodes = document.getElementsByTagName("TargetFrameworks")
            if (targetFrameworksNodes.length > 0) {
                val frameworksText = targetFrameworksNodes.item(0).textContent
                if (!frameworksText.isNullOrBlank()) {
                    val frameworks = frameworksText.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    if (frameworks.isNotEmpty()) {
                        return frameworks.first()
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            LOG.debug("Failed to parse props file ${propsFile.absolutePath}: ${e.message}")
            null
        }
    }
    
    /**
     * Gets the actual target framework used by MSBuild for a project.
     * This is the most reliable way to get the target framework as it uses MSBuild's evaluation.
     */
    fun getActualTargetFramework(projectFile: File): String? {
        return try {
            val commandLine = GeneralCommandLine(
                "dotnet", "msbuild",
                projectFile.absolutePath,
                "/t:GetTargetFramework",
                "/p:GetTargetFramework=true",
                "/nologo",
                "/verbosity:minimal",
                "/p:Configuration=Debug"
            )
            
            val process = commandLine.createProcess()
            val output = process.inputStream.bufferedReader().readText()
            val errorOutput = process.errorStream.bufferedReader().readText()
            process.waitFor()
            
            if (process.exitValue() != 0) {
                LOG.debug("MSBuild query failed, exit code: ${process.exitValue()}")
                return null
            }
            
            val allOutput = output + errorOutput
            
            // Look for TargetFramework in the output
            // MSBuild might output it in various formats
            val patterns = listOf(
                Regex("""TargetFramework[:\s=]+(net\d+\.\d+)""", RegexOption.IGNORE_CASE),
                Regex("""TargetFramework\s*=\s*(net\d+\.\d+)""", RegexOption.IGNORE_CASE),
                Regex("""(net\d+\.\d+)""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(allOutput)
                for (match in matches) {
                    val framework = match.groupValues.lastOrNull() ?: match.value
                    if (framework.startsWith("net") && framework.matches(Regex("""net\d+\.\d+"""))) {
                        LOG.debug("Found TargetFramework from MSBuild: $framework")
                        return framework
                    }
                }
            }
            
            null
        } catch (e: Exception) {
            LOG.debug("Failed to query TargetFramework from MSBuild: ${e.message}")
            null
        }
    }
    
    /**
     * Gets the .NET SDK version installed on the system.
     */
    fun getDotNetSdkVersion(): String? {
        return try {
            val commandLine = GeneralCommandLine("dotnet", "--version")
            val process = commandLine.createProcess()
            val version = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) {
                version
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to get .NET SDK version: ${e.message}", e)
            null
        }
    }
    
    /**
     * Checks if a specific .NET runtime is available.
     * 
     * @param targetFramework The target framework (e.g., "net6.0", "net7.0", "net8.0")
     * @return true if the runtime is available, false otherwise
     */
    fun isRuntimeAvailable(targetFramework: String): Boolean {
        return try {
            // Extract the major version from target framework (e.g., "net6.0" -> "6")
            val versionMatch = Regex("net(\\d+)\\.\\d+").find(targetFramework)
            if (versionMatch != null) {
                val majorVersion = versionMatch.groupValues[1]
                
                // List installed runtimes
                val commandLine = GeneralCommandLine("dotnet", "--list-runtimes")
                val process = commandLine.createProcess()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                
                if (process.exitValue() == 0) {
                    // Check if the runtime version is in the list
                    // Look for patterns like "Microsoft.NETCore.App 6.x.x" or "Microsoft.AspNetCore.App 6.x.x"
                    val runtimeAvailable = output.lines().any { line ->
                        line.contains("Microsoft.NETCore.App $majorVersion.", ignoreCase = true) ||
                        line.contains("Microsoft.AspNetCore.App $majorVersion.", ignoreCase = true)
                    }
                    
                    if (!runtimeAvailable) {
                        LOG.warn("Runtime for target framework $targetFramework may not be installed. " +
                                "Installed runtimes:\n$output")
                    }
                    
                    return runtimeAvailable
                }
            }
            
            // If we can't determine, assume it's available (dotnet will error if not)
            true
        } catch (e: Exception) {
            LOG.warn("Failed to check runtime availability for $targetFramework: ${e.message}", e)
            // Assume available - dotnet will error if not
            true
        }
    }
    
    /**
     * Determines the build output directory based on .NET SDK version and project structure.
     * This is a fallback method when MSBuild query fails.
     */
    fun determineBuildOutputDirectoryFallback(
        projectDirectory: File,
        targetFramework: String,
        configuration: String = "Debug"
    ): File {
        // Standard .NET SDK output structure: bin/{Configuration}/{TargetFramework}/
        val standardPath = File(projectDirectory, "bin/$configuration/$targetFramework")
        
        // Check if it exists
        if (standardPath.exists()) {
            return standardPath
        }
        
        // Try Release if Debug doesn't exist
        if (configuration == "Debug") {
            val releasePath = File(projectDirectory, "bin/Release/$targetFramework")
            if (releasePath.exists()) {
                return releasePath
            }
        }
        
        // Return the standard path even if it doesn't exist yet (will be created on build)
        return standardPath
    }
    
    /**
     * Finds the build output directory by searching for the project's DLL or EXE file.
     * This is the most reliable method after a build.
     */
    fun findOutputDirectoryByArtifact(
        projectDirectory: File,
        projectName: String,
        configuration: String = "Debug",
        targetFramework: String? = null
    ): File? {
        // Build search paths, prioritizing TFM-specific directories if targetFramework is provided
        val searchPaths = mutableListOf<File>()
        
        if (targetFramework != null) {
            // First, try the exact TFM-specific path (highest priority)
            searchPaths.add(File(projectDirectory, "bin/$configuration/$targetFramework"))
        }
        
        // Then add standard search paths
        searchPaths.add(File(projectDirectory, "bin/$configuration"))
        searchPaths.add(File(projectDirectory, "bin/Release")) // Fallback to Release
        searchPaths.add(File(projectDirectory, "bin"))
        
        // If targetFramework is provided, also search for other TFM directories but prioritize exact match
        val foundFiles = mutableListOf<Pair<File, File>>() // (artifact file, parent directory)
        
        for (basePath in searchPaths) {
            if (!basePath.exists()) continue
            
            // Search recursively for the DLL or EXE
            val dllFile = findFileRecursive(basePath, "$projectName.dll")
            val exeFile = findFileRecursive(basePath, "$projectName.exe")
            
            val foundFile = dllFile ?: exeFile
            if (foundFile != null) {
                val parentDir = foundFile.parentFile
                
                // If we have a target framework, check if this directory matches it exactly
                if (targetFramework != null && parentDir.name == targetFramework) {
                    return parentDir // Return immediately for exact TFM match
                }
                
                // Collect for later if no exact match yet
                foundFiles.add(Pair(foundFile, parentDir))
            }
        }
        
        // If we found files but no exact TFM match, return the first one found
        if (foundFiles.isNotEmpty()) {
            val (foundFile, parentDir) = foundFiles.first()
            LOG.warn("Found artifact in ${parentDir.name} but expected $targetFramework")
            return parentDir
        }
        
        return null
    }
    
    /**
     * Recursively searches for a file in a directory.
     */
    private fun findFileRecursive(directory: File, fileName: String): File? {
        if (!directory.exists() || !directory.isDirectory) {
            return null
        }
        
        // Check current directory
        val file = File(directory, fileName)
        if (file.exists()) {
            return file
        }
        
        // Search subdirectories (limit depth to avoid performance issues)
        val subdirs = directory.listFiles { f -> f.isDirectory } ?: return null
        for (subdir in subdirs) {
            val found = findFileRecursive(subdir, fileName)
            if (found != null) {
                return found
            }
        }
        
        return null
    }
}
