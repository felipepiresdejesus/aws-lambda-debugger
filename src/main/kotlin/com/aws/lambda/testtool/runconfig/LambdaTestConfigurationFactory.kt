package com.aws.lambda.testtool.runconfig

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

/**
 * Factory for creating Lambda Test run configurations.
 */
class LambdaTestConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    
    override fun getId(): String = "LambdaTestConfigurationFactory"
    
    override fun getName(): String = "AWS Lambda Test"
    
    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return LambdaTestRunConfiguration(project, this, "Lambda Test")
    }
}
