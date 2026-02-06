package com.aws.lambda.testtool.startup

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity for logging plugin initialization.
 */
class PluginStartupActivity : ProjectActivity {

    private val LOG = Logger.getInstance(PluginStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        try {
            LOG.info("AWS Lambda Test Tool plugin starting up")

            // Log that services are being initialized
            LOG.info("Plugin initialized successfully for project: ${project.name}")
        } catch (e: Exception) {
            LOG.error("Failed to initialize AWS Lambda Test Tool plugin", e)
        }
    }
}
