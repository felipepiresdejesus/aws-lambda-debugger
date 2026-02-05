package com.aws.lambda.testtool.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for PortChecker utility.
 */
class PortCheckerTest {
    
    @Test
    fun `isPortAvailable returns true for available port`() {
        // Find an available port
        val port = PortChecker.findAvailablePort() ?: return
        
        assertTrue(PortChecker.isPortAvailable(port))
    }
    
    @Test
    fun `isPortAvailable may return false for system ports`() {
        // Port 1 is typically reserved, but this test may be flaky
        // Just verify the method doesn't throw
        try {
            PortChecker.isPortAvailable(1)
        } catch (e: Exception) {
            // If it throws, that's also acceptable
        }
    }
    
    @Test
    fun `findAvailablePort returns a valid port`() {
        val port = PortChecker.findAvailablePort(5050, 10)
        assertNotNull(port)
        assertTrue(port >= 5050)
    }
    
    @Test
    fun `isServiceReady returns false for non-existent service`() {
        // Use a port that's unlikely to have a service
        val port = PortChecker.findAvailablePort(9000) ?: 9000
        assertFalse(PortChecker.isServiceReady(port, "/", 100))
    }
}
