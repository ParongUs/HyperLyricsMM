package com.hyperlyrics.mm

import android.app.Application
import android.content.Context
import android.content.Intent
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.cache.LyricsCacheDatabase
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class XposedStatus(
    val connected: Boolean = false,
    val frameworkName: String = "",
    val apiVersion: Int = 0
)

class HyperLyricsApp : Application() {

    lateinit var cacheDb: LyricsCacheDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        appInstance = this
        cacheDb = LyricsCacheDatabase(this)

        val localPrefs = getSharedPreferences(ModuleConfig.PREFS_NAME, Context.MODE_PRIVATE)
        _settings.value = ModuleConfig.readSettings(localPrefs)

        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    xposedService = service
                    _xposedStatus.value = XposedStatus(
                        connected = true,
                        frameworkName = runCatching { service.frameworkName }.getOrDefault("LSPosed"),
                        apiVersion = runCatching { service.apiVersion }.getOrDefault(101)
                    )
                    syncToRemotePreferences(_settings.value)
                    ModuleLogger.i(TAG, "Bound to XposedService (${_xposedStatus.value.frameworkName} API ${_xposedStatus.value.apiVersion})")
                }

                override fun onServiceDied(service: XposedService) {
                    if (xposedService === service) {
                        xposedService = null
                        _xposedStatus.value = XposedStatus(connected = false)
                    }
                }
            })
        }.onFailure {
            ModuleLogger.w(TAG, "XposedServiceHelper listener registration failed", it)
        }
    }

    fun updateSettings(transform: (ModuleSettings) -> ModuleSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next

        val localPrefs = getSharedPreferences(ModuleConfig.PREFS_NAME, Context.MODE_PRIVATE)
        localPrefs.edit().apply {
            ModuleConfig.writeSettings(this, next)
            apply()
        }
        syncToRemotePreferences(next)

        // Notify SystemUI fallback receiver for instant live updates
        runCatching {
            sendBroadcast(Intent(ModuleConfig.ACTION_SETTINGS_CHANGED))
        }
    }

    private fun syncToRemotePreferences(settings: ModuleSettings) {
        val service = xposedService ?: return
        runCatching {
            val remotePrefs = service.getRemotePreferences(ModuleConfig.PREFS_NAME)
            remotePrefs.edit().apply {
                ModuleConfig.writeSettings(this, settings)
                apply()
            }
        }.onFailure {
            ModuleLogger.w(TAG, "Failed syncing RemotePreferences", it)
        }
    }

    companion object {
        private const val TAG = "HyperLyricsApp"

        @Volatile
        var appInstance: HyperLyricsApp? = null
            private set

        @Volatile
        var xposedService: XposedService? = null
            private set

        private val _xposedStatus = MutableStateFlow(XposedStatus())
        val xposedStatus: StateFlow<XposedStatus> = _xposedStatus.asStateFlow()

        private val _settings = MutableStateFlow(ModuleSettings())
        val settings: StateFlow<ModuleSettings> = _settings.asStateFlow()
    }
}
