package com.hyperlyrics.mm.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.ipc.LyricsBridgeClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Live runtime configuration holder for hooked SystemUI processes.
 * Listens to LibXposed RemotePreferences and falls back to ContentProvider IPC on cold start.
 */
object RuntimeSettingsHolder {
    private const val TAG = "RuntimeSettings"

    @Volatile
    var current: ModuleSettings = ModuleSettings()
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HyperLyricsMM-Prefs").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<(ModuleSettings) -> Unit>()

    private var remotePrefs: SharedPreferences? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var receiverRegistered = false

    fun attachRemotePreferences(prefs: SharedPreferences) {
        if (remotePrefs === prefs) return
        remotePrefs?.let { old -> prefListener?.let(old::unregisterOnSharedPreferenceChangeListener) }
        remotePrefs = prefs
        refreshFromPrefs(prefs)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
            refreshFromPrefs(sp)
        }
        prefListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun attachSystemContext(context: Context) {
        if (!receiverRegistered) {
            receiverRegistered = true
            runCatching {
                val filter = IntentFilter(ModuleConfig.ACTION_SETTINGS_CHANGED)
                context.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            val prefs = remotePrefs
                            if (prefs != null) {
                                refreshFromPrefs(prefs)
                            } else if (ctx != null) {
                                refreshFromBridgeAsync(ctx)
                            }
                        }
                    },
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            }.onFailure {
                ModuleLogger.w(TAG, "Failed to register settings broadcast receiver", it)
            }
        }
        if (remotePrefs == null) {
            refreshFromBridgeAsync(context)
        }
    }

    fun addListener(listener: (ModuleSettings) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (ModuleSettings) -> Unit) {
        listeners.remove(listener)
    }

    private fun refreshFromPrefs(prefs: SharedPreferences) {
        val updated = runCatching { ModuleConfig.readSettings(prefs) }.getOrNull() ?: return
        publishIfChanged(updated)
    }

    private fun refreshFromBridgeAsync(context: Context) {
        ioExecutor.execute {
            val updated = LyricsBridgeClient.fetchSettingsFallback(context) ?: return@execute
            publishIfChanged(updated)
        }
    }

    private fun publishIfChanged(updated: ModuleSettings) {
        if (updated == current) return
        current = updated
        ModuleLogger.d(TAG, "Settings updated: master=${updated.masterEnabled}, expanded=${updated.expandedLyricsEnabled}, minimal=${updated.minimalLyricsEnabled}")
        mainHandler.post {
            listeners.forEach { listener ->
                runCatching { listener(updated) }
            }
        }
    }
}
