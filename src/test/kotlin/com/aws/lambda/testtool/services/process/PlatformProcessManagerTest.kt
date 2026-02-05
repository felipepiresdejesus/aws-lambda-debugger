package com.aws.lambda.testtool.services.process

import com.intellij.execution.configurations.GeneralCommandLine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for PlatformProcessManager.
 */
class PlatformProcessManagerTest {
    
    private val processManager = PlatformProcessManager()
    
    @Test
    fun `isProcessAlive returns false for invalid PID`() {
        assertFalse(processManager.isProcessAlive(-1))
        assertFalse(processManager.isProcessAlive(0))
    }
    
    @Test
    fun `isProcessAlive returns false for non-existent PID`() {
        // Use a very large PID that likely doesn't exist
        assertFalse(processManager.isProcessAlive(999999999L))
    }
    
    @Test
    fun `killProcess with invalid PID does not throw`() {
        // Should not throw for invalid PID
        processManager.killProcess(-1, force = false)
        processManager.killProcess(0, force = true)
    }
    
    @Test
    fun `createRegisteredProcess creates OSProcessHandler`() {
        val commandLine = GeneralCommandLine("echo", "test")
        val handler = processManager.createRegisteredProcess(commandLine)
        
        assertTrue(handler is com.intellij.execution.process.OSProcessHandler)
    }
}
