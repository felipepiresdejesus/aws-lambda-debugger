package com.aws.lambda.testtool.actions

import com.aws.lambda.testtool.runconfig.LambdaTestConfigurationType
import com.aws.lambda.testtool.runconfig.LambdaTestRunConfiguration
import com.aws.lambda.testtool.utils.ProjectFileParser
import com.intellij.execution.RunManager
import com.intellij.execution.impl.EditConfigurationsDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger

/**
 * Action to create a Lambda Test run configuration for the selected .csproj file.
 * Opens the Edit Configurations dialog after creating the configuration.
 */
class CreateLambdaConfigAction : AnAction() {
    
    private val LOG = Logger.getInstance(CreateLambdaConfigAction::class.java)
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // Only show for .csproj files that are Lambda projects
        val isLambdaProject = if (project != null && virtualFile != null && 
            virtualFile.extension?.lowercase() == "csproj") {
            ProjectFileParser.parse(virtualFile) != null
        } else {
            false
        }
        
        e.presentation.isEnabledAndVisible = isLambdaProject
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        if (virtualFile.extension?.lowercase() != "csproj") {
            LOG.warn("Not a .csproj file: ${virtualFile.path}")
            return
        }
        
        val lambdaProject = ProjectFileParser.parse(virtualFile)
        if (lambdaProject == null) {
            LOG.warn("Not a Lambda project: ${virtualFile.path}")
            return
        }
        
        LOG.info("Creating Lambda run configuration for: ${lambdaProject.name}")
        
        val runManager = RunManager.getInstance(project)
        val configType = LambdaTestConfigurationType.getInstance()
        
        // Check if configuration already exists
        val existingConfig = runManager.allSettings
            .filter { it.type == configType }
            .find { 
                (it.configuration as? LambdaTestRunConfiguration)?.projectPath == virtualFile.path 
            }
        
        val settings = if (existingConfig != null) {
            existingConfig
        } else {
            // Create new configuration
            val newSettings = runManager.createConfiguration(
                "${lambdaProject.name} - Lambda Test",
                configType.configurationFactories[0]
            )
            val config = newSettings.configuration as LambdaTestRunConfiguration
            config.projectPath = virtualFile.path
            config.workingDirectory = lambdaProject.buildOutputDirectory.absolutePath
            
            runManager.addConfiguration(newSettings)
            newSettings
        }
        
        runManager.selectedConfiguration = settings
        
        // Open Edit Configurations dialog
        EditConfigurationsDialog(project).show()
    }
}
