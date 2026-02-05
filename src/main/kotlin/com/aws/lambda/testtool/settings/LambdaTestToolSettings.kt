package com.aws.lambda.testtool.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent settings for the AWS Lambda Test Tool plugin.
 */
@State(
    name = "LambdaTestToolSettings",
    storages = [Storage("LambdaTestToolSettings.xml")]
)
class LambdaTestToolSettings : PersistentStateComponent<LambdaTestToolSettings.State> {
    
    private var myState = State()
    
    /**
     * State class holding all settings values.
     */
    data class State(
        var defaultPort: Int = 5050,
        var autoOpenBrowser: Boolean = true,
        var autoStartTestTool: Boolean = true,
        var customTestToolPath: String? = null,
        var connectionTimeoutSeconds: Int = 30,
        var showNotifications: Boolean = true
    )
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }
    
    var defaultPort: Int
        get() = myState.defaultPort
        set(value) {
            myState.defaultPort = value
        }
    
    var autoOpenBrowser: Boolean
        get() = myState.autoOpenBrowser
        set(value) {
            myState.autoOpenBrowser = value
        }
    
    var autoStartTestTool: Boolean
        get() = myState.autoStartTestTool
        set(value) {
            myState.autoStartTestTool = value
        }
    
    var customTestToolPath: String?
        get() = myState.customTestToolPath
        set(value) {
            myState.customTestToolPath = value
        }
    
    var connectionTimeoutSeconds: Int
        get() = myState.connectionTimeoutSeconds
        set(value) {
            myState.connectionTimeoutSeconds = value
        }
    
    var showNotifications: Boolean
        get() = myState.showNotifications
        set(value) {
            myState.showNotifications = value
        }
    
    companion object {
        fun getInstance(): LambdaTestToolSettings {
            return ApplicationManager.getApplication().getService(LambdaTestToolSettings::class.java)
        }
    }
}
