package com.aws.lambda.testtool.actions

import com.aws.lambda.testtool.models.LambdaProject
import com.aws.lambda.testtool.services.LambdaProjectDetector
import com.aws.lambda.testtool.utils.LambdaEventGenerator
import com.aws.lambda.testtool.utils.LambdaEventType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.time.Instant

/**
 * Action to generate test event JSON files for Lambda functions.
 * Allows users to create test event templates for common AWS event types.
 */
class GenerateTestEventAction : AnAction("Generate Test Event...", "Generate a test event JSON file for Lambda testing", null), DumbAware {
    
    private val LOG = Logger.getInstance(GenerateTestEventAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val detector = LambdaProjectDetector.getInstance(project)
        
        // Try to detect Lambda project from context
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val lambdaProject = detectLambdaProject(detector, virtualFile, project.basePath)
        
        if (lambdaProject == null) {
            Messages.showWarningDialog(
                project,
                "No Lambda project detected. Please select a .csproj file or navigate to a Lambda project directory.",
                "Lambda Project Not Found"
            )
            return
        }
        
        // Show event type selection dialog using a simple list selection
        val eventTypes = LambdaEventType.values()
        val eventTypeNames = eventTypes.map { "${it.name} (${it.name.replace(Regex("([A-Z])"), " $1").trim()})" }
        
        // Use a simpler selection approach
        val selectedName = Messages.showEditableChooseDialog(
            "Select the type of Lambda event to generate:",
            "Generate Test Event",
            Messages.getQuestionIcon(),
            eventTypeNames.toTypedArray(),
            eventTypeNames[0],
            null
        ) ?: return // User cancelled
        
        // Extract the event type from the selected name
        val selectedIndex = eventTypeNames.indexOf(selectedName)
        if (selectedIndex < 0) {
            return
        }
        
        if (selectedIndex < 0) {
            return // User cancelled
        }
        
        val selectedEventType = eventTypes[selectedIndex]
        
        // Suggest a filename based on event type
        val suggestedFileName = "${lambdaProject.name}-${selectedEventType.name.lowercase()}-event.json"
        
        // Create test-events directory if it doesn't exist
        val testEventsDir = File(lambdaProject.projectDirectory, "test-events")
        testEventsDir.mkdirs()
        
        // Generate the file in the test-events directory
        val outputFile = File(testEventsDir, suggestedFileName)
        
        // Generate customizations based on event type
        val customizations = generateCustomizations(selectedEventType, lambdaProject)
        
        // Generate the event file
        val success = LambdaEventGenerator.generateEventFile(
            eventType = selectedEventType,
            outputFile = outputFile,
            customizations = customizations
        )
        
        if (success) {
            Messages.showInfoMessage(
                project,
                "Test event file generated successfully:\n${outputFile.absolutePath}",
                "Test Event Generated"
            )
            
            // Refresh the file system and open the file
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputFile)?.let { vf ->
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
            }
        } else {
            Messages.showErrorDialog(
                project,
                "Failed to generate test event file. Check the logs for details.",
                "Generation Failed"
            )
        }
    }
    
    private fun detectLambdaProject(
        detector: LambdaProjectDetector,
        virtualFile: VirtualFile?,
        basePath: String?
    ): LambdaProject? {
        // If a .csproj file is selected, use it directly
        if (virtualFile != null && virtualFile.extension?.lowercase() == "csproj") {
            return detector.getLambdaProjectByPath(virtualFile.path)
        }
        
        // Otherwise, try to find Lambda projects in the workspace
        val projects = detector.detectLambdaProjects()
        return when {
            projects.isEmpty() -> null
            projects.size == 1 -> projects[0]
            else -> {
                // Multiple projects - could show a selection dialog
                // For now, return the first one
                projects[0]
            }
        }
    }
    
    private fun generateCustomizations(eventType: LambdaEventType, lambdaProject: LambdaProject): Map<String, String> {
        val timestamp = Instant.now().toString()
        val region = "us-east-1"
        
        val baseCustomizations = mapOf(
            "timestamp" to timestamp,
            "region" to region
        )
        
        return when (eventType) {
            LambdaEventType.S3 -> baseCustomizations + mapOf(
                "bucketName" to "test-bucket",
                "objectKey" to "test-object-key"
            )
            LambdaEventType.APIGateway, LambdaEventType.APIGatewayV2 -> baseCustomizations + mapOf(
                "path" to "test",
                "method" to "GET",
                "stage" to "test",
                "body" to "{}",
                "host" to "api.example.com",
                "domainPrefix" to "api"
            )
            LambdaEventType.SQS -> baseCustomizations + mapOf(
                "queueName" to "test-queue",
                "messageBody" to "{\"message\": \"test\"}"
            )
            LambdaEventType.SNS -> baseCustomizations + mapOf(
                "topicName" to "test-topic",
                "subject" to "Test Subject",
                "message" to "Test message",
                "subscriptionId" to "test-subscription-id"
            )
            LambdaEventType.DynamoDB -> baseCustomizations + mapOf(
                "tableName" to "test-table",
                "message" to "Test message"
            )
            LambdaEventType.Kinesis -> baseCustomizations + mapOf(
                "streamName" to "test-stream",
                "base64Data" to "dGVzdCBkYXRh",
                "timestamp" to (System.currentTimeMillis() / 1000).toString()
            )
            LambdaEventType.Scheduled -> baseCustomizations + mapOf(
                "ruleName" to "test-rule"
            )
            LambdaEventType.Simple -> baseCustomizations
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
