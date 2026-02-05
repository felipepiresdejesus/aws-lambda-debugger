package com.aws.lambda.testtool.utils

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/**
 * Utility for checking port availability and service readiness.
 */
object PortChecker {
    private val LOG = Logger.getInstance(PortChecker::class.java)
    
    /**
     * Checks if a port is available for binding.
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            LOG.debug("Port $port is not available: ${e.message}")
            false
        }
    }
    
    /**
     * Checks if a service is responding on the given port.
     */
    fun isServiceReady(port: Int, path: String = "/", timeoutMs: Int = 1000): Boolean {
        return try {
            val url = URI("http://127.0.0.1:$port$path").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            responseCode in 200..399
        } catch (e: Exception) {
            LOG.debug("Service not ready on port $port: ${e.message}")
            false
        }
    }
    
    /**
     * Waits for a service to become ready on the given port.
     * Returns true if the service becomes ready within the timeout, false otherwise.
     */
    fun waitForServiceReady(
        port: Int,
        path: String = "/",
        timeoutMs: Long = 30000,
        checkIntervalMs: Long = 500
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isServiceReady(port, path, checkIntervalMs.toInt())) {
                return true
            }
            
            try {
                Thread.sleep(checkIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        
        return false
    }
    
    /**
     * Finds an available port starting from the given port.
     */
    fun findAvailablePort(startPort: Int = 5050, maxAttempts: Int = 100): Int? {
        for (i in 0 until maxAttempts) {
            val port = startPort + i
            if (isPortAvailable(port)) {
                return port
            }
        }
        return null
    }
}
