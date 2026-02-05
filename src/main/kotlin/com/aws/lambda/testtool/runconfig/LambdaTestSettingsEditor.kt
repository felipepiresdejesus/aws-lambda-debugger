package com.aws.lambda.testtool.runconfig

import com.aws.lambda.testtool.models.LambdaProject
import com.aws.lambda.testtool.services.LambdaProjectDetector
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Settings editor UI for Lambda Test run configuration.
 * Provides a custom form for editing configuration values.
 */
class LambdaTestSettingsEditor(private val project: Project) : SettingsEditor<LambdaTestRunConfiguration>() {
    
    private val projectComboBox = ComboBox<LambdaProjectItem>()
    private val portField = JBTextField("5050")
    private val workingDirectoryField = TextFieldWithBrowseButton()
    private val autoOpenBrowserCheckBox = JBCheckBox("Auto-open browser when Test Tool starts", true)
    private val envVarsTableModel = DefaultTableModel(arrayOf("Name", "Value"), 0)
    private val envVarsTable = JBTable(envVarsTableModel)
    
    private var cachedProjects: List<LambdaProject> = emptyList()
    
    init {
        setupComponents()
    }
    
    private fun setupComponents() {
        // Configure project combo box
        projectComboBox.renderer = DefaultListCellRenderer().apply {
            setText("Select a Lambda project...")
        }
        
        // Configure port field
        portField.toolTipText = "Port for the Lambda Test Tool (default: 5050)"
        
        // Configure working directory field
        workingDirectoryField.addBrowseFolderListener(
            "Select Working Directory",
            "Select the working directory for the Lambda function",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        
        // Configure environment variables table
        envVarsTable.preferredScrollableViewportSize = Dimension(400, 100)
        envVarsTable.fillsViewportHeight = true
        envVarsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        
        // Refresh projects button
        refreshProjects()
    }
    
    private fun refreshProjects() {
        val detector = LambdaProjectDetector.getInstance(project)
        cachedProjects = detector.detectLambdaProjects()
        
        projectComboBox.removeAllItems()
        projectComboBox.addItem(LambdaProjectItem(null, "-- Select Lambda Project --"))
        
        cachedProjects.forEach { lambdaProject ->
            projectComboBox.addItem(LambdaProjectItem(lambdaProject, lambdaProject.name))
        }
    }
    
    override fun resetEditorFrom(configuration: LambdaTestRunConfiguration) {
        refreshProjects()
        
        // Set selected project
        val projectPath = configuration.projectPath
        if (projectPath != null) {
            for (i in 0 until projectComboBox.itemCount) {
                val item = projectComboBox.getItemAt(i)
                if (item.project?.projectFile?.path == projectPath) {
                    projectComboBox.selectedIndex = i
                    break
                }
            }
        }
        
        // Set port
        portField.text = configuration.port.toString()
        
        // Set working directory
        workingDirectoryField.text = configuration.workingDirectory ?: ""
        
        // Set auto-open browser
        autoOpenBrowserCheckBox.isSelected = configuration.autoOpenBrowser
        
        // Set environment variables
        envVarsTableModel.rowCount = 0
        configuration.environmentVariables.forEach { (key, value) ->
            envVarsTableModel.addRow(arrayOf(key, value))
        }
    }
    
    override fun applyEditorTo(configuration: LambdaTestRunConfiguration) {
        // Apply project selection
        val selectedItem = projectComboBox.selectedItem as? LambdaProjectItem
        configuration.projectPath = selectedItem?.project?.projectFile?.path
        
        // Apply port
        configuration.port = portField.text.toIntOrNull() ?: 5050
        
        // Apply working directory
        val workDir = workingDirectoryField.text.trim()
        configuration.workingDirectory = workDir.ifEmpty { null }
        
        // Apply auto-open browser
        configuration.autoOpenBrowser = autoOpenBrowserCheckBox.isSelected
        
        // Apply environment variables
        val envVars = mutableMapOf<String, String>()
        for (row in 0 until envVarsTableModel.rowCount) {
            val key = envVarsTableModel.getValueAt(row, 0)?.toString()?.trim() ?: continue
            val value = envVarsTableModel.getValueAt(row, 1)?.toString() ?: ""
            if (key.isNotEmpty()) {
                envVars[key] = value
            }
        }
        configuration.environmentVariables = envVars
    }
    
    override fun createEditor(): JComponent {
        // Environment variables panel with add/remove buttons
        val envVarsPanel = JPanel(BorderLayout())
        envVarsPanel.add(JScrollPane(envVarsTable), BorderLayout.CENTER)
        
        val envVarsButtonPanel = JPanel()
        val addButton = JButton("Add")
        val removeButton = JButton("Remove")
        
        addButton.addActionListener {
            envVarsTableModel.addRow(arrayOf("", ""))
            val newRowIndex = envVarsTableModel.rowCount - 1
            envVarsTable.setRowSelectionInterval(newRowIndex, newRowIndex)
            envVarsTable.scrollRectToVisible(envVarsTable.getCellRect(newRowIndex, 0, true))
            envVarsTable.editCellAt(newRowIndex, 0)
            envVarsTable.repaint()
        }
        
        removeButton.addActionListener {
            val selectedRow = envVarsTable.selectedRow
            if (selectedRow >= 0) {
                envVarsTableModel.removeRow(selectedRow)
            }
        }
        
        envVarsButtonPanel.add(addButton)
        envVarsButtonPanel.add(removeButton)
        envVarsPanel.add(envVarsButtonPanel, BorderLayout.SOUTH)
        
        // Refresh button for projects
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { refreshProjects() }
        
        val projectPanel = JPanel(BorderLayout())
        projectPanel.add(projectComboBox, BorderLayout.CENTER)
        projectPanel.add(refreshButton, BorderLayout.EAST)
        
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Lambda Project:"), projectPanel)
            .addLabeledComponent(JBLabel("Port:"), portField)
            .addLabeledComponent(JBLabel("Working Directory:"), workingDirectoryField)
            .addComponent(autoOpenBrowserCheckBox)
            .addLabeledComponent(JBLabel("Environment Variables:"), envVarsPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }
    
    /**
     * Wrapper class for Lambda projects in the combo box.
     */
    private data class LambdaProjectItem(
        val project: LambdaProject?,
        val displayName: String
    ) {
        override fun toString(): String = displayName
    }
}
