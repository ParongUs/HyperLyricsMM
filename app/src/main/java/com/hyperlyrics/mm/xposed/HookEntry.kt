package com.hyperlyrics.mm.xposed

import android.app.Application
import com.hyperlyrics.mm.BuildConfig
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.xposed.expanded.ExpandedIslandCoordinator
import com.hyperlyrics.mm.xposed.minimal.MinimalIslandCoordinator
import com.hyperlyrics.mm.xposed.session.YouTubeMusicSessionTracker
import com.hyperlyrics.mm.xposed.ytm.YouTubeMusicProcessHooker
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Main LibXposed API 101/102 module entry point for HyperLyricsMM.
 *
 * Scopes:
 *  - `com.android.systemui`: hooks HyperOS Expanded Island YouTube Music player
 *    and discovers the dynamically loaded `miui.systemui.plugin` ClassLoader for Minimal Island.
 *  - `com.google.android.apps.youtube.music` (and RVX/ReVanced variants): captures exact videoId.
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        instance = this
        ModuleLogger.xposedLogSink = { priority, message, throwable ->
            if (throwable != null) {
                log(priority, "HyperLyricsMM", message, throwable)
            } else {
                log(priority, "HyperLyricsMM", message)
            }
        }
        ModuleLogger.i(TAG, "HyperLyricsMM v${BuildConfig.VERSION_NAME} loaded (process=${param.processName})")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (processName.contains(':')) return

        when {
            param.packageName == ModuleConfig.SYSTEM_UI_PACKAGE -> {
                initSystemUi(param.defaultClassLoader)
            }

            param.packageName == ModuleConfig.SYSTEM_UI_PLUGIN_PACKAGE -> {
                MinimalIslandCoordinator.hookPluginClassLoader(this, param.defaultClassLoader)
            }

            ModuleConfig.isYouTubeMusicPackage(param.packageName) -> {
                YouTubeMusicProcessHooker.hook(this, param.defaultClassLoader)
            }
        }
    }

    private fun initSystemUi(classLoader: ClassLoader) {
        ModuleLogger.i(TAG, "Initializing HyperOS SystemUI hooks")

        // Attach LibXposed RemotePreferences for live settings sync
        runCatching {
            val remotePrefs = getRemotePreferences(ModuleConfig.PREFS_NAME)
            RuntimeSettingsHolder.attachRemotePreferences(remotePrefs)
        }.onFailure {
            ModuleLogger.w(TAG, "RemotePreferences unavailable at init; will use bridge fallback", it)
        }

        // Hook Application.onCreate to bootstrap MediaSession tracking
        runCatching {
            val appClass = classLoader.loadClass("android.app.Application")
            val onCreate = appClass.getDeclaredMethod("onCreate")
            hook(onCreate).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val result = chain.proceed()
                    (chain.thisObject as? Application)?.let { app ->
                        YouTubeMusicSessionTracker.start(app)
                    }
                    return result
                }
            })
        }.onFailure {
            ModuleLogger.e(TAG, "Failed hooking SystemUI Application.onCreate", it)
        }

        // Install Expanded Hyper Island YouTube Music Split-Lyrics Player hooks
        ExpandedIslandCoordinator.hook(this, classLoader)

        // Intercept dynamic plugin ClassLoader creation for miui.systemui.plugin (Minimal Hyper Island)
        runCatching {
            val baseDexClass = Class.forName("dalvik.system.BaseDexClassLoader")
            baseDexClass.declaredConstructors.forEach { ctor ->
                hook(ctor).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        val createdLoader = chain.thisObject as? ClassLoader ?: return result
                        MinimalIslandCoordinator.tryHookDynamicClassLoader(this@HookEntry, createdLoader)
                        return result
                    }
                })
            }
        }.onFailure {
            ModuleLogger.w(TAG, "BaseDexClassLoader plugin interceptor unavailable", it)
        }
    }

    companion object {
        private const val TAG = "HookEntry"

        @Volatile
        var instance: HookEntry? = null
            private set
    }
}
