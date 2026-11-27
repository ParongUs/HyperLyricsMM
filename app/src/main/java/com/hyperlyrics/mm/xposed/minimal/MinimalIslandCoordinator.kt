package com.hyperlyrics.mm.xposed.minimal

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hyperlyrics.mm.common.MinimalLyricSlot
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.model.LyricsState
import com.hyperlyrics.mm.xposed.RuntimeSettingsHolder
import com.hyperlyrics.mm.xposed.expanded.ExpandedIslandPinController
import com.hyperlyrics.mm.xposed.session.YouTubeMusicSessionTracker
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hooks HyperOS's `miui.systemui.dynamicisland` plugin to optionally display a smaller,
 * crisp single-line synchronized YouTube Music lyric inside the minimal (collapsed)
 * Super Island pill.
 *
 * Strictly scoped to YouTube Music islands; immediately restores stock HyperOS minimal
 * capsule layout whenever disabled, paused, or another media app takes over.
 */
object MinimalIslandCoordinator {
    private const val TAG = "MinimalIslandCoord"

    private const val CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
    private const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    private const val ADAPTER_CLASS =
        "miui.systemui.dynamicisland.module.IslandModuleViewHolderAdapter"

    private const val LEFT_PARENT_NAME = "island_container_module_image_text_1"
    private const val RIGHT_PARENT_NAME = "island_container_module_image_text_2"
    private const val TEXT_CONTAINER_NAME = "island_container_module_text"
    private const val TAG_MINIMAL_LYRIC = "hyperlyrics_mm_minimal_single_line"

    private val PLUGIN_PACKAGES = arrayOf("miui.systemui.plugin", "com.android.systemui")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val activeContentViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    )
    private val originalVisibilities = Collections.synchronizedMap(
        WeakHashMap<View, Int>()
    )
    private val relayoutReentry = ThreadLocal.withInitial { false }

    @Volatile
    private var listenersRegistered = false

    @Volatile
    private var tickerRunning = false

    private val lyricTickRunnable = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            updateAllMinimalHosts(relayoutIfChanged = false)
            if (YouTubeMusicSessionTracker.isPlaying && shouldShowMinimalLyrics()) {
                mainHandler.postDelayed(this, 160L)
            } else {
                tickerRunning = false
            }
        }
    }

    fun tryHookDynamicClassLoader(module: XposedModule, classLoader: ClassLoader) {
        if (classLoader.javaClass.name.contains("BootClassLoader")) return
        val hasIslandClass = runCatching {
            classLoader.loadClass(CONTENT_VIEW_CLASS)
        }.isSuccess
        if (hasIslandClass) {
            hookPluginClassLoader(module, classLoader)
        }
    }

    fun hookPluginClassLoader(module: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return

        var hookedCount = 0

        // 1. Hook DynamicIslandContentView.updateBigIslandView
        runCatching {
            val contentClass = classLoader.loadClass(CONTENT_VIEW_CLASS)
            contentClass.methods.filter { it.name == "updateBigIslandView" }.forEach { method ->
                module.hook(method).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val result = chain.proceed()
                        // Skip suspend continuation markers in newer HyperOS builds
                        if (chain.args.size >= 3 && result !is Boolean) return result
                        val view = chain.thisObject as? ViewGroup ?: return result
                        activeContentViews.add(view)
                        val dataArg = chain.args.firstOrNull() ?: extractCurrentIslandData(view)
                        reconcileHost(view, dataArg, triggerRelayout = true)
                        return result
                    }
                })
                hookedCount++
            }
        }.onFailure {
            ModuleLogger.w(TAG, "DynamicIslandContentView.updateBigIslandView hook skipped", it)
        }

        // 2. Hook IslandModuleViewHolderAdapter.bindData / updateView so native updates don't wipe our lyric
        runCatching {
            val adapterClass = classLoader.loadClass(ADAPTER_CLASS)
            adapterClass.declaredMethods
                .filter { (it.name == "bindData" && it.parameterCount == 2) || (it.name == "updateView" && it.parameterCount == 3) }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : Hooker {
                        override fun intercept(chain: Chain): Any? {
                            val result = chain.proceed()
                            mainHandler.post { updateAllMinimalHosts(relayoutIfChanged = false) }
                            return result
                        }
                    })
                    hookedCount++
                }
        }.onFailure {
            ModuleLogger.d(TAG, "Adapter refresh hook optional/skipped: ${it.message}")
        }

        // 3. Optional max-width unlock for YouTube Music minimal pill when enabled
        runCatching {
            val baseClass = classLoader.loadClass(BASE_CONTENT_VIEW_CLASS)
            val getMaxWidth = baseClass.declaredMethods.firstOrNull {
                it.name == "getMaxWidth" && it.parameterCount == 0 && it.returnType == Float::class.javaPrimitiveType
            }
            if (getMaxWidth != null) {
                getMaxWidth.isAccessible = true
                module.hook(getMaxWidth).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val settings = RuntimeSettingsHolder.current
                        val view = chain.thisObject as? ViewGroup
                        if (view != null &&
                            settings.masterEnabled &&
                            settings.minimalLyricsEnabled &&
                            settings.unlockIslandWidthLimit &&
                            isSupportedMusicIsland(extractCurrentIslandData(view), settings) &&
                            view.findViewWithTag<View>(TAG_MINIMAL_LYRIC)?.visibility == View.VISIBLE
                        ) {
                            val screenW = Resources.getSystem().displayMetrics.widthPixels.toFloat()
                            if (screenW > 0f) return screenW * 0.94f
                        }
                        return chain.proceed()
                    }
                })
                hookedCount++
            }
        }.onFailure {
            ModuleLogger.d(TAG, "Optional getMaxWidth hook skipped: ${it.message}")
        }

        // 4. Hook plugin-level outside-touch / auto-collapse guards for Expanded Island Pin mode
        ExpandedIslandPinController.hookCollapseGuardsForClasses(
            module = module,
            classLoader = classLoader,
            classNames = listOf(
                CONTENT_VIEW_CLASS,
                BASE_CONTENT_VIEW_CLASS,
                "miui.systemui.dynamicisland.window.DynamicIslandWindow",
                "miui.systemui.dynamicisland.window.DynamicIslandWindowController",
                "miui.systemui.dynamicisland.window.DynamicIslandWindowManager",
                "miui.systemui.dynamicisland.DynamicIslandManager"
            )
        )

        if (hookedCount > 0) {
            ensureRuntimeListeners()
            ModuleLogger.i(TAG, "Minimal Hyper Island hooks installed ($hookedCount methods)")
        } else {
            hookedClassLoaders.remove(classLoader)
        }
    }

    private fun ensureRuntimeListeners() {
        if (listenersRegistered) return
        listenersRegistered = true

        RuntimeSettingsHolder.addListener {
            updateAllMinimalHosts(relayoutIfChanged = true)
            ensureTickerIfNeeded()
        }

        YouTubeMusicSessionTracker.addListener(object : YouTubeMusicSessionTracker.Listener {
            override fun onTrackChanged(track: YouTubeMusicSessionTracker.TrackInfo?) {
                updateAllMinimalHosts(relayoutIfChanged = true)
                ensureTickerIfNeeded()
            }

            override fun onLyricsStateChanged(state: LyricsState) {
                updateAllMinimalHosts(relayoutIfChanged = true)
                ensureTickerIfNeeded()
            }

            override fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, speed: Float) {
                updateAllMinimalHosts(relayoutIfChanged = true)
                ensureTickerIfNeeded()
            }
        })
    }

    private fun ensureTickerIfNeeded() {
        if (YouTubeMusicSessionTracker.isPlaying && shouldShowMinimalLyrics()) {
            if (!tickerRunning) {
                tickerRunning = true
                mainHandler.removeCallbacks(lyricTickRunnable)
                mainHandler.post(lyricTickRunnable)
            }
        } else {
            tickerRunning = false
            mainHandler.removeCallbacks(lyricTickRunnable)
        }
    }

    private fun shouldShowMinimalLyrics(): Boolean {
        val s = RuntimeSettingsHolder.current
        if (!s.masterEnabled || !s.minimalLyricsEnabled) return false
        return YouTubeMusicSessionTracker.currentLyricsState is LyricsState.Ready
    }

    private fun updateAllMinimalHosts(relayoutIfChanged: Boolean) {
        val hosts = synchronized(activeContentViews) { activeContentViews.toList() }
        hosts.forEach { host ->
            runCatching {
                val data = extractCurrentIslandData(host)
                reconcileHost(host, data, triggerRelayout = relayoutIfChanged)
            }
        }
    }

    private fun reconcileHost(host: ViewGroup, islandData: Any?, triggerRelayout: Boolean) {
        val settings = RuntimeSettingsHolder.current
        val isSupported = isSupportedMusicIsland(islandData, settings)
        val snapshot = YouTubeMusicSessionTracker.currentActiveLyric()
        val activeLineText = snapshot.currentText.ifBlank {
            if (snapshot.isInterlude) "♪" else ""
        }

        val showMinimal = settings.masterEnabled &&
            settings.minimalLyricsEnabled &&
            isSupported &&
            YouTubeMusicSessionTracker.isPlaying &&
            activeLineText.isNotEmpty()

        if (!showMinimal) {
            val restored = restoreHost(host)
            if (restored && triggerRelayout) {
                triggerNativeWidthRelayout(host)
            }
            return
        }

        val targetParentName = when (settings.minimalSlot) {
            MinimalLyricSlot.RIGHT -> RIGHT_PARENT_NAME
            MinimalLyricSlot.LEFT -> LEFT_PARENT_NAME
        }
        val otherParentName = if (targetParentName == RIGHT_PARENT_NAME) LEFT_PARENT_NAME else RIGHT_PARENT_NAME

        // Clean up opposite slot if user switched Left <-> Right in settings
        restoreSlot(host, otherParentName)

        val slotParent = findViewByName(host, targetParentName) as? ViewGroup ?: return
        val textContainer = findViewByName(slotParent, TEXT_CONTAINER_NAME) as? ViewGroup ?: slotParent

        rememberAndSetVisibility(textContainer, View.VISIBLE)

        val lyricView = (textContainer.findViewWithTag<View>(TAG_MINIMAL_LYRIC) as? MinimalSingleLineLyricView)
            ?: MinimalSingleLineLyricView(host.context).apply {
                tag = TAG_MINIMAL_LYRIC
                onMeasuredWidthHintChanged = {
                    triggerNativeWidthRelayout(host)
                }
                textContainer.addView(
                    this,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                )
            }

        // Hide native siblings inside the text slot so only our clean single-line lyric appears
        for (i in 0 until textContainer.childCount) {
            val child = textContainer.getChildAt(i)
            if (child === lyricView) {
                child.visibility = View.VISIBLE
            } else {
                rememberAndSetVisibility(child, View.GONE)
            }
        }

        lyricView.configure(
            fontSizeSp = settings.minimalFontSizeSp,
            maxWidthDp = settings.minimalMaxWidthDp
        )
        lyricView.setPlaying(YouTubeMusicSessionTracker.isPlaying)
        lyricView.setLyricLine(activeLineText)

        if (triggerRelayout) {
            triggerNativeWidthRelayout(host)
        }
    }

    private fun restoreHost(host: ViewGroup): Boolean {
        val leftRestored = restoreSlot(host, LEFT_PARENT_NAME)
        val rightRestored = restoreSlot(host, RIGHT_PARENT_NAME)
        return leftRestored || rightRestored
    }

    private fun restoreSlot(host: ViewGroup, parentName: String): Boolean {
        val slotParent = findViewByName(host, parentName) as? ViewGroup ?: return false
        val textContainer = findViewByName(slotParent, TEXT_CONTAINER_NAME) as? ViewGroup ?: slotParent
        val lyricView = textContainer.findViewWithTag<View>(TAG_MINIMAL_LYRIC)
        var changed = false
        if (lyricView != null && lyricView.visibility != View.GONE) {
            lyricView.visibility = View.GONE
            changed = true
        }
        for (i in 0 until textContainer.childCount) {
            val child = textContainer.getChildAt(i)
            if (child === lyricView) continue
            val orig = synchronized(originalVisibilities) { originalVisibilities.remove(child) }
            if (orig != null && child.visibility != orig) {
                child.visibility = orig
                changed = true
            }
        }
        val origContainerVis = synchronized(originalVisibilities) { originalVisibilities.remove(textContainer) }
        if (origContainerVis != null && textContainer.visibility != origContainerVis) {
            textContainer.visibility = origContainerVis
            changed = true
        }
        return changed
    }

    private fun rememberAndSetVisibility(view: View, visibility: Int) {
        synchronized(originalVisibilities) {
            originalVisibilities.getOrPut(view) { view.visibility }
        }
        if (view.visibility != visibility) {
            view.visibility = visibility
        }
    }

    private fun isSupportedMusicIsland(data: Any?, settings: ModuleSettings): Boolean {
        if (data == null) return false
        val extras = runCatching {
            data.javaClass.methods.firstOrNull { it.name == "getExtras" && it.parameterCount == 0 }
                ?.invoke(data) as? Bundle
        }.getOrNull() ?: return false
        val pkg = extras.getString("miui.pkg.name")
        return ModuleConfig.isTargetMusicPackage(pkg, settings)
    }

    private fun extractCurrentIslandData(view: View): Any? {
        var current: View? = view
        repeat(6) {
            val candidate = current ?: return null
            val data = runCatching {
                candidate.javaClass.methods.firstOrNull {
                    it.name == "getCurrentIslandData" && it.parameterCount == 0
                }?.invoke(candidate)
            }.getOrNull()
            if (data != null) return data
            current = candidate.parent as? View
        }
        return null
    }

    private fun triggerNativeWidthRelayout(host: ViewGroup) {
        if (relayoutReentry.get() == true) return
        relayoutReentry.set(true)
        try {
            val method: Method? = host.javaClass.methods.firstOrNull {
                (it.name == "updateBigIslandViewWidth" || it.name == "calculateBigIslandWidth") &&
                    it.parameterCount == 0 && it.returnType == Void.TYPE
            }
            runCatching { method?.invoke(host) }
        } finally {
            relayoutReentry.set(false)
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun findViewByName(root: ViewGroup, name: String): View? {
        val res = root.resources
        for (pkg in PLUGIN_PACKAGES) {
            val id = res.getIdentifier(name, "id", pkg)
            if (id != 0) {
                root.findViewById<View>(id)?.let { return it }
            }
        }
        return null
    }
}
