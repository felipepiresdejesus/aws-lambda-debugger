package com.aws.lambda.testtool.runconfig

import com.aws.lambda.testtool.services.LambdaProjectDetector
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element

/**
 * Run configuration for AWS Lambda Test Tool.
 * 
 * Stores all configuration data needed to execute a Lambda function with the Test Tool:
 * - Lambda project path
 * - Port for the Test Tool
 * - Working directory
 * - Environment variables
 * - Auto-open browser setting
 * 
 * This configuration is serialized to XML and persisted by the IntelliJ Platform.
 */
class LambdaTestRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : LocatableConfigurationBase<RunConfigurationOptions>(project, factory, name) {
    
    var projectPath: String? = null
    var port: Int = 5050
    var workingDirectory: String? = null
    var autoOpenBrowser: Boolean = true
    var environmentVariables: MutableMap<String, String> = mutableMapOf()
    
    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return LambdaTestSettingsEditor(project)
    }

    override fun suggestedName(): String? {
        val path = projectPath ?: return null
        return java.io.File(path).nameWithoutExtension
    }

    override fun isGeneratedName(): Boolean {
        val currentName = name
        return currentName.startsWith("Unnamed") ||
                currentName.startsWith("Lambda Test") ||
                currentName == suggestedName()
    }
    
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return LambdaTestRunState(project, this, environment)
    }
    
    override fun checkConfiguration() {
        if (projectPath.isNullOrBlank()) {
            throw RuntimeConfigurationError("Lambda project path is not configured")
        }
        
        val detector = LambdaProjectDetector.getInstance(project)
        val lambdaProject = detector.getLambdaProjectByPath(projectPath!!)
        
        if (lambdaProject == null) {
            throw RuntimeConfigurationError("Selected project is not a valid Lambda project")
        }
        
        if (!lambdaProject.canExecuteWithTestTool()) {
            if (lambdaProject.isExecutable) {
                throw RuntimeConfigurationWarning("Executable Lambda projects require Amazon.Lambda.RuntimeSupport package for Test Tool v2.")
            } else {
                throw RuntimeConfigurationWarning("Lambda project must have Amazon.Lambda.Core package installed.")
            }
        }
        
        if (!lambdaProject.dllPath.exists()) {
            throw RuntimeConfigurationWarning("Build output not found. Please build the project first.")
        }
    }
    
    override fun readExternal(element: Element) {
        super.readExternal(element)
        projectPath = element.getAttributeValue("projectPath")
        port = element.getAttributeValue("port")?.toIntOrNull() ?: 5050
        workingDirectory = element.getAttributeValue("workingDirectory")
        autoOpenBrowser = element.getAttributeValue("autoOpenBrowser")?.toBoolean() ?: true
        
        // Read environment variables
        environmentVariables.clear()
        element.getChild("envVars")?.children?.forEach { child ->
            val key = child.getAttributeValue("name")
            val value = child.getAttributeValue("value")
            if (key != null) {
                environmentVariables[key] = value ?: ""
            }
        }
    }
    
    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        projectPath?.let { element.setAttribute("projectPath", it) }
        element.setAttribute("port", port.toString())
        workingDirectory?.let { element.setAttribute("workingDirectory", it) }
        element.setAttribute("autoOpenBrowser", autoOpenBrowser.toString())
        
        // Write environment variables
        if (environmentVariables.isNotEmpty()) {
            val envVarsElement = Element("envVars")
            environmentVariables.forEach { (key, value) ->
                val varElement = Element("var")
                varElement.setAttribute("name", key)
                varElement.setAttribute("value", value)
                envVarsElement.addContent(varElement)
            }
            element.addContent(envVarsElement)
        }
    }
    
    override fun clone(): RunConfiguration {
        val clone = super.clone() as LambdaTestRunConfiguration
        clone.projectPath = this.projectPath
        clone.port = this.port
        clone.workingDirectory = this.workingDirectory
        clone.autoOpenBrowser = this.autoOpenBrowser
        clone.environmentVariables = this.environmentVariables.toMutableMap()
        return clone
    }
    
    /**
     * Gets the Lambda project for this configuration.
     */
    fun getLambdaProject() = projectPath?.let {
        LambdaProjectDetector.getInstance(project).getLambdaProjectByPath(it)
    }
}
