package com.aws.lambda.testtool.services

import com.aws.lambda.testtool.models.*
import com.aws.lambda.testtool.utils.PortChecker
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Service for validating Lambda project prerequisites.
 */
@Service(Service.Level.PROJECT)
class PrerequisiteValidator(private val project: Project) {
    
    private val LOG = Logger.getInstance(PrerequisiteValidator::class.java)
    
    companion object {
        val REQUIRED_PACKAGES = listOf(
            "Amazon.Lambda.Core"
        )
        
        // RuntimeSupport is needed for Lambda Test Tool v2 but not strictly required
        // for all Lambda deployment models
        val RECOMMENDED_FOR_TEST_TOOL = listOf(
            "Amazon.Lambda.RuntimeSupport"
        )
        
        val RECOMMENDED_PACKAGES = listOf(
            "Amazon.Lambda.Serialization.SystemTextJson"
        )
        
        const val DEFAULT_PORT = 5050
        
        fun getInstance(project: Project): PrerequisiteValidator {
            return project.getService(PrerequisiteValidator::class.java)
        }
    }
    
    /**
     * Validates all prerequisites for a Lambda project.
     */
    fun validate(lambdaProject: LambdaProject, port: Int = DEFAULT_PORT): ValidationResult {
        val warnings = mutableListOf<ValidationWarning>()
        val errors = mutableListOf<ValidationError>()
        
        // Check OutputType
        validateOutputType(lambdaProject, warnings)
        
        // Check required packages
        validateRequiredPackages(lambdaProject, warnings)
        
        // Check packages needed for Test Tool v2
        validateTestToolPackages(lambdaProject, warnings)
        
        // Check recommended packages
        validateRecommendedPackages(lambdaProject, warnings)
        
        // Check build output
        validateBuildOutput(lambdaProject, warnings)
        
        // Check port availability
        validatePort(port, warnings)
        
        // Check test tool installation
        validateTestToolInstallation(warnings)
        
        val isValid = errors.isEmpty()
        
        return ValidationResult(isValid, warnings, errors)
    }
    
    private fun validateOutputType(project: LambdaProject, warnings: MutableList<ValidationWarning>) {
        // Both executable and handler-based Lambda functions are supported
        if (project.isExecutable) {
            // Executable projects need RuntimeSupport for Test Tool v2
            if (!project.hasPackage("Amazon.Lambda.RuntimeSupport")) {
                warnings.add(
                    ValidationWarning(
                        type = ValidationWarningType.MISSING_PACKAGE,
                        message = "Executable Lambda projects require Amazon.Lambda.RuntimeSupport for Test Tool v2",
                        suggestion = "Run: dotnet add package Amazon.Lambda.RuntimeSupport"
                    )
                )
            }
        } else if (project.isHandlerBased) {
            // Handler-based projects are supported (traditional Lambda handlers)
            // They work with Lambda Test Tool through handler discovery
            LOG.debug("Handler-based Lambda project detected (OutputType=${project.outputType})")
        } else {
            // Neither executable nor handler-based - might not be a valid Lambda project
            warnings.add(
                ValidationWarning(
                    type = ValidationWarningType.OUTPUT_TYPE_NOT_EXE,
                    message = "Lambda project detected but OutputType is '${project.outputType}'. " +
                            "Supported types: Exe (with RuntimeSupport) or Library (handler-based)",
                    suggestion = "For executable: Add <OutputType>Exe</OutputType> and Amazon.Lambda.RuntimeSupport. " +
                            "For handler-based: Ensure Amazon.Lambda.Core is installed."
                )
            )
        }
    }
    
    private fun validateRequiredPackages(project: LambdaProject, warnings: MutableList<ValidationWarning>) {
        for (packageName in REQUIRED_PACKAGES) {
            if (!project.hasPackage(packageName)) {
                warnings.add(
                    ValidationWarning(
                        type = ValidationWarningType.MISSING_PACKAGE,
                        message = "Missing required package: $packageName",
                        suggestion = "Run: dotnet add package $packageName"
                    )
                )
            }
        }
    }
    
    private fun validateTestToolPackages(project: LambdaProject, warnings: MutableList<ValidationWarning>) {
        // RuntimeSupport is only required for executable projects
        // Handler-based projects don't need it
        if (project.isExecutable) {
            for (packageName in RECOMMENDED_FOR_TEST_TOOL) {
                if (!project.hasPackage(packageName)) {
                    warnings.add(
                        ValidationWarning(
                            type = ValidationWarningType.MISSING_PACKAGE,
                            message = "Missing package for Test Tool v2: $packageName",
                            suggestion = "Run: dotnet add package $packageName (required for executable Lambda projects with Test Tool v2)"
                        )
                    )
                }
            }
        }
    }
    
    private fun validateRecommendedPackages(project: LambdaProject, warnings: MutableList<ValidationWarning>) {
        for (packageName in RECOMMENDED_PACKAGES) {
            if (!project.hasPackage(packageName)) {
                LOG.debug("Recommended package not found: $packageName")
                // Don't add warning for recommended packages, just log
            }
        }
    }
    
    private fun validateBuildOutput(project: LambdaProject, warnings: MutableList<ValidationWarning>) {
        val executable = project.dllPath
        if (!executable.exists()) {
            warnings.add(
                ValidationWarning(
                    type = ValidationWarningType.BUILD_OUTPUT_NOT_FOUND,
                    message = "Lambda executable not found at: ${executable.path}",
                    suggestion = "Build the project first: dotnet build"
                )
            )
        }
    }
    
    private fun validatePort(port: Int, warnings: MutableList<ValidationWarning>) {
        if (!PortChecker.isPortAvailable(port)) {
            warnings.add(
                ValidationWarning(
                    type = ValidationWarningType.PORT_IN_USE,
                    message = "Port $port is already in use",
                    suggestion = "Stop the existing process on port $port or use a different port"
                )
            )
        }
    }
    
    private fun validateTestToolInstallation(warnings: MutableList<ValidationWarning>) {
        val installer = TestToolInstaller.getInstance(project)
        if (!installer.isTestToolInstalled()) {
            warnings.add(
                ValidationWarning(
                    type = ValidationWarningType.TEST_TOOL_NOT_INSTALLED,
                    message = "dotnet-lambda-test-tool is not installed",
                    suggestion = "Click 'Install' or run: dotnet tool install -g Amazon.Lambda.TestTool"
                )
            )
        }
    }
    
    /**
     * Validates and returns only blocking errors.
     */
    fun validateCritical(lambdaProject: LambdaProject): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        // Check if project file exists
        if (!lambdaProject.projectFile.exists()) {
            errors.add(
                ValidationError(
                    type = ValidationErrorType.PROJECT_FILE_NOT_FOUND,
                    message = "Project file not found: ${lambdaProject.projectFile.path}"
                )
            )
        }
        
        return errors
    }
    
    /**
     * Quick check if a Lambda project is ready to run.
     */
    fun isReadyToRun(lambdaProject: LambdaProject, port: Int = DEFAULT_PORT): Boolean {
        return lambdaProject.canExecuteWithTestTool() &&
               lambdaProject.dllPath.exists() &&
               PortChecker.isPortAvailable(port) &&
               TestToolInstaller.getInstance(project).isTestToolInstalled()
    }
}
