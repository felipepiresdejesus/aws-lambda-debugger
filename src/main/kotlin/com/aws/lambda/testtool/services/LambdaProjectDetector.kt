package com.aws.lambda.testtool.services

import com.aws.lambda.testtool.models.LambdaProject
import com.aws.lambda.testtool.utils.ProjectFileParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for detecting Lambda projects within an IntelliJ project/solution.
 */
@Service(Service.Level.PROJECT)
class LambdaProjectDetector(private val project: Project) {

    private val LOG = Logger.getInstance(LambdaProjectDetector::class.java)
    private val cachedProjects = ConcurrentHashMap<String, LambdaProject>()

    init {
        LOG.info("LambdaProjectDetector service initialized for project: ${project.name}")
    }
    
    /**
     * Detects all Lambda projects in the current workspace.
     */
    fun detectLambdaProjects(): List<LambdaProject> {
        val baseDir = project.basePath?.let { VfsUtil.findFile(java.nio.file.Path.of(it), true) }
        if (baseDir == null) {
            LOG.warn("Could not find project base directory")
            return emptyList()
        }
        
        val lambdaProjects = mutableListOf<LambdaProject>()
        
        VfsUtil.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                // Skip common directories that won't contain project files
                if (file.isDirectory) {
                    val name = file.name.lowercase()
                    if (name in EXCLUDED_DIRECTORIES) {
                        return false // Don't descend into these directories
                    }
                    return true // Continue visiting
                }
                
                // Check for .csproj files
                if (file.extension?.lowercase() == "csproj") {
                    LOG.debug("Found .csproj file: ${file.path}")
                    
                    // Check cache first
                    val cached = cachedProjects[file.path]
                    if (cached != null && file.modificationStamp == cached.projectFile.modificationStamp) {
                        lambdaProjects.add(cached)
                    } else {
                        // Parse the project file
                        val lambdaProject = ProjectFileParser.parse(file)
                        if (lambdaProject != null) {
                            LOG.info("Detected Lambda project: ${lambdaProject.name}")
                            cachedProjects[file.path] = lambdaProject
                            lambdaProjects.add(lambdaProject)
                        }
                    }
                }
                
                return true
            }
        })
        
        return lambdaProjects
    }
    
    /**
     * Gets a specific Lambda project by name.
     */
    fun getLambdaProject(projectName: String): LambdaProject? {
        return detectLambdaProjects().find { it.name == projectName }
    }
    
    /**
     * Gets a Lambda project by its project file path.
     */
    fun getLambdaProjectByPath(projectFilePath: String): LambdaProject? {
        return cachedProjects[projectFilePath] ?: run {
            val file = VfsUtil.findFile(java.nio.file.Path.of(projectFilePath), true)
            file?.let { ProjectFileParser.parse(it) }
        }
    }
    
    /**
     * Clears the project cache, forcing re-detection on next call.
     */
    fun clearCache() {
        cachedProjects.clear()
    }
    
    /**
     * Refreshes a specific project in the cache.
     */
    fun refreshProject(projectFilePath: String): LambdaProject? {
        cachedProjects.remove(projectFilePath)
        val file = VfsUtil.findFile(java.nio.file.Path.of(projectFilePath), true)
        return file?.let { 
            ProjectFileParser.parse(it)?.also { parsed ->
                cachedProjects[projectFilePath] = parsed
            }
        }
    }
    
    companion object {
        private val EXCLUDED_DIRECTORIES = setOf(
            "bin",
            "obj",
            "node_modules",
            ".git",
            ".idea",
            ".vs",
            "packages",
            "testresults",
            "artifacts"
        )
        
        fun getInstance(project: Project): LambdaProjectDetector {
            return project.getService(LambdaProjectDetector::class.java)
        }
    }
}
