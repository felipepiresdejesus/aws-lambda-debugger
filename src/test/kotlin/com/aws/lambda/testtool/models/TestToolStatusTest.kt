package com.aws.lambda.testtool.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for TestToolStatus model.
 */
class TestToolStatusTest {
    
    @Test
    fun `notRunning status has correct state`() {
        val status = TestToolStatus.notRunning()
        assertEquals(TestToolState.NOT_RUNNING, status.state)
        assertNotNull(status.message)
    }
    
    @Test
    fun `starting status has correct state`() {
        val status = TestToolStatus.starting(5050)
        assertEquals(TestToolState.STARTING, status.state)
        assertEquals(5050, status.port)
    }
    
    @Test
    fun `running status has correct state and properties`() {
        val status = TestToolStatus.running(5050, 12345L)
        assertEquals(TestToolState.RUNNING, status.state)
        assertEquals(5050, status.port)
        assertEquals(12345L, status.processId)
    }
    
    @Test
    fun `stopping status has correct state`() {
        val status = TestToolStatus.stopping()
        assertEquals(TestToolState.STOPPING, status.state)
    }
    
    @Test
    fun `error status has correct state and message`() {
        val errorMessage = "Test error"
        val status = TestToolStatus.error(errorMessage)
        assertEquals(TestToolState.ERROR, status.state)
        assertEquals(errorMessage, status.message)
    }
}
