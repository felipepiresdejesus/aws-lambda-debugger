package com.aws.lambda.testtool.runconfig

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Configuration type for AWS Lambda Test Tool run configurations.
 * This registers the configuration type in Rider's Run/Debug dropdown.
 */
class LambdaTestConfigurationType : ConfigurationType {
    
    override fun getDisplayName(): String = "AWS Lambda Test"
    
    override fun getConfigurationTypeDescription(): String = 
        "Run or debug AWS Lambda functions with Lambda Test Tool v2"
    
    override fun getIcon(): Icon = ICON
    
    override fun getId(): String = "LambdaTestConfiguration"
    
    override fun getConfigurationFactories(): Array<ConfigurationFactory> {
        return arrayOf(LambdaTestConfigurationFactory(this))
    }
    
    companion object {
        @JvmField
        val ICON: Icon = IconLoader.getIcon("/icons/icon_16.png", LambdaTestConfigurationType::class.java)
        
        fun getInstance(): LambdaTestConfigurationType {
            return ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
                .filterIsInstance<LambdaTestConfigurationType>()
                .first()
        }
    }
}
