package com.aws.lambda.testtool.models

import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Represents a detected Lambda function project.
 */
data class LambdaProject(
    val name: String,
    val projectFile: VirtualFile,
    val projectDirectory: File,
    val outputType: String,
    val targetFramework: String,
    val packages: List<PackageReference>,
    val buildOutputDirectory: File
) {
    val isExecutable: Boolean
        get() = outputType.equals("Exe", ignoreCase = true)
    
    val isHandlerBased: Boolean
        get() = !isExecutable && hasPackage("Amazon.Lambda.Core")
    
    val executablePath: File
        get() {
            val extension = if (System.getProperty("os.name").lowercase().contains("windows")) ".exe" else ""
            return File(buildOutputDirectory, "$name$extension")
        }
    
    val dllPath: File
        get() = File(buildOutputDirectory, "$name.dll")
    
    fun hasPackage(packageName: String): Boolean {
        return packages.any { it.name.equals(packageName, ignoreCase = true) }
    }
    
    fun hasRequiredLambdaPackages(): Boolean {
        return hasPackage("Amazon.Lambda.Core") &&
               hasPackage("Amazon.Lambda.RuntimeSupport")
    }
    
    /**
     * Checks if this project can be executed with Lambda Test Tool v2.
     * Executable projects need RuntimeSupport, handler-based projects just need Core.
     */
    fun canExecuteWithTestTool(): Boolean {
        return if (isExecutable) {
            hasPackage("Amazon.Lambda.RuntimeSupport")
        } else {
            hasPackage("Amazon.Lambda.Core")
        }
    }
}

/**
 * Represents a NuGet package reference in a .csproj file.
 */
data class PackageReference(
    val name: String,
    val version: String?
)
