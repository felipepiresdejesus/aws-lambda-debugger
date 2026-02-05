package com.aws.lambda.testtool.utils

import com.aws.lambda.testtool.models.LambdaProject
import com.aws.lambda.testtool.models.PackageReference
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses .csproj files to extract Lambda project information.
 */
object ProjectFileParser {
    private val LOG = Logger.getInstance(ProjectFileParser::class.java)
    
    /**
     * Parses a .csproj file and returns a LambdaProject if it's a valid Lambda project.
     * Returns null if the project doesn't have Lambda dependencies or parsing fails.
     */
    fun parse(projectFile: VirtualFile): LambdaProject? {
        return try {
            val file = File(projectFile.path)
            if (!file.exists()) {
                LOG.warn("Project file does not exist: ${projectFile.path}")
                return null
            }
            
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val document: Document = builder.parse(file)
            document.documentElement.normalize()
            
            val outputType = extractOutputType(document)
            val targetFramework = extractTargetFramework(document, file)
            val packages = extractPackageReferences(document)
            val awsProjectType = extractAwsProjectType(document)
            
            // Check if this is a Lambda project:
            // - Has AWSProjectType=Lambda, OR
            // - Has Amazon.Lambda.Core package, OR
            // - Has Amazon.Lambda.RuntimeSupport package
            val isLambdaProject = awsProjectType.equals("Lambda", ignoreCase = true) ||
                packages.any { it.name.equals("Amazon.Lambda.Core", ignoreCase = true) } ||
                packages.any { it.name.equals("Amazon.Lambda.RuntimeSupport", ignoreCase = true) }
            
            if (!isLambdaProject) {
                return null
            }
            
            val projectName = projectFile.nameWithoutExtension
            val projectDirectory = file.parentFile
            val buildOutputDirectory = determineBuildOutputDirectory(projectDirectory, targetFramework, file)
            
            LambdaProject(
                name = projectName,
                projectFile = projectFile,
                projectDirectory = projectDirectory,
                outputType = outputType,
                targetFramework = targetFramework,
                packages = packages,
                buildOutputDirectory = buildOutputDirectory
            )
        } catch (e: Exception) {
            LOG.error("Failed to parse project file: ${projectFile.path}", e)
            null
        }
    }
    
    private fun extractOutputType(document: Document): String {
        val outputTypeNodes = document.getElementsByTagName("OutputType")
        return if (outputTypeNodes.length > 0) {
            outputTypeNodes.item(0).textContent ?: "Library"
        } else {
            "Library"
        }
    }
    
    private fun extractAwsProjectType(document: Document): String? {
        val nodes = document.getElementsByTagName("AWSProjectType")
        return if (nodes.length > 0) {
            nodes.item(0).textContent
        } else {
            null
        }
    }
    
    private fun extractTargetFramework(document: Document, projectFile: File): String {
        // Try TargetFramework first (single framework) in the project file
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
        
        // If not found in project file, check Directory.Build.props files
        val frameworkFromProps = findTargetFrameworkInPropsFiles(projectFile.parentFile)
        if (frameworkFromProps != null) {
            LOG.info("Found TargetFramework in Directory.Build.props: $frameworkFromProps")
            return frameworkFromProps
        }
        
        // If no framework is specified, try to query MSBuild for the actual target framework
        // This is more reliable than defaulting
        val frameworkFromMsBuild = queryTargetFrameworkFromMsBuild(projectFile)
        if (frameworkFromMsBuild != null) {
            LOG.info("Found TargetFramework from MSBuild: $frameworkFromMsBuild")
            return frameworkFromMsBuild
        }
        
        // If no framework is specified, log a warning and return a default
        // This should rarely happen as .NET projects typically specify a target framework
        LOG.warn("No TargetFramework found in project file, Directory.Build.props, or MSBuild. " +
                "Searched in: ${projectFile.parentFile.absolutePath} and parent directories. " +
                "Defaulting to net8.0")
        return "net8.0"
    }
    
    /**
     * Searches for TargetFramework in Directory.Build.props files up the directory tree.
     * MSBuild checks parent directories up to the solution root.
     */
    private fun findTargetFrameworkInPropsFiles(startDirectory: File): String? {
        var currentDir: File? = startDirectory
        val maxDepth = 10 // Prevent infinite loops
        var depth = 0
        
        LOG.debug("Searching for Directory.Build.props starting from: ${startDirectory.absolutePath}")
        
        while (currentDir != null && depth < maxDepth) {
            // Check for Directory.Build.props
            val propsFile = File(currentDir, "Directory.Build.props")
            if (propsFile.exists() && propsFile.isFile) {
                LOG.debug("Found Directory.Build.props at: ${propsFile.absolutePath}")
                val framework = extractTargetFrameworkFromPropsFile(propsFile)
                if (framework != null) {
                    LOG.info("Found TargetFramework in ${propsFile.absolutePath}: $framework")
                    return framework
                } else {
                    LOG.debug("Directory.Build.props exists but no TargetFramework found in it")
                }
            }
            
            // Also check for other common .props files
            val otherPropsFiles = currentDir.listFiles { _, name ->
                name.endsWith(".props", ignoreCase = true) && name != "Directory.Build.props"
            } ?: emptyArray()
            
            for (propsFile in otherPropsFiles) {
                LOG.debug("Checking props file: ${propsFile.name}")
                val framework = extractTargetFrameworkFromPropsFile(propsFile)
                if (framework != null) {
                    LOG.info("Found TargetFramework in ${propsFile.absolutePath}: $framework")
                    return framework
                }
            }
            
            // Move up to parent directory
            val parentDir = currentDir.parentFile
            if (parentDir == null || parentDir == currentDir) {
                break // Reached root
            }
            currentDir = parentDir
            depth++
        }
        
        LOG.debug("No TargetFramework found in any Directory.Build.props files (searched $depth levels)")
        return null
    }
    
    /**
     * Extracts TargetFramework from a .props file.
     */
    private fun extractTargetFrameworkFromPropsFile(propsFile: File): String? {
        return try {
            LOG.debug("Checking props file: ${propsFile.absolutePath}")
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(propsFile)
            document.documentElement.normalize()
            
            // Try TargetFramework first
            val targetFrameworkNodes = document.getElementsByTagName("TargetFramework")
            if (targetFrameworkNodes.length > 0) {
                val framework = targetFrameworkNodes.item(0).textContent
                if (!framework.isNullOrBlank()) {
                    LOG.debug("Found TargetFramework in ${propsFile.name}: $framework")
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
                        LOG.debug("Found TargetFrameworks in ${propsFile.name}: ${frameworks.first()}")
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
     * Queries MSBuild for the actual target framework used by the project.
     * This is the most reliable method as it uses the same logic MSBuild uses.
     */
    private fun queryTargetFrameworkFromMsBuild(projectFile: File): String? {
        return try {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(
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
            // MSBuild might output it in various formats
            val patterns = listOf(
                Regex("""TargetFramework[:\s=]+(net\d+\.\d+)""", RegexOption.IGNORE_CASE),
                Regex("""(net\d+\.\d+)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(allOutput)
                if (match != null) {
                    val framework = match.groupValues.lastOrNull() ?: match.value
                    if (framework.startsWith("net")) {
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
    
    private fun extractPackageReferences(document: Document): List<PackageReference> {
        val packageReferences = mutableListOf<PackageReference>()
        val packageNodes: NodeList = document.getElementsByTagName("PackageReference")
        
        for (i in 0 until packageNodes.length) {
            val node = packageNodes.item(i)
            if (node is Element) {
                val includeName = node.getAttribute("Include")
                val version = node.getAttribute("Version").takeIf { it.isNotEmpty() }
                    ?: node.getElementsByTagName("Version").item(0)?.textContent
                
                if (includeName.isNotEmpty()) {
                    packageReferences.add(PackageReference(includeName, version))
                }
            }
        }
        
        return packageReferences
    }
    
    private fun determineBuildOutputDirectory(projectDirectory: File, targetFramework: String, projectFile: File): File {
        LOG.info("Determining build output directory for target framework: $targetFramework")
        
        // First, try to get the actual build output directory from MSBuild
        // This ensures we get the correct path based on the .NET SDK version
        val actualOutputPath = DotNetBuildHelper.getBuildOutputDirectory(projectFile, "Debug")
        if (actualOutputPath != null) {
            LOG.info("Using MSBuild output directory: ${actualOutputPath.absolutePath}")
            return actualOutputPath
        }
        
        // Fallback: use the standard .NET SDK output structure with the detected framework
        val fallbackPath = DotNetBuildHelper.determineBuildOutputDirectoryFallback(projectDirectory, targetFramework, "Debug")
        LOG.info("Using fallback output directory: ${fallbackPath.absolutePath}")
        return fallbackPath
    }
}
