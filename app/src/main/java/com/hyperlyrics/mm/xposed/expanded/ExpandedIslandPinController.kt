package com.hyperlyrics.mm.xposed.expanded

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.xposed.RuntimeSettingsHolder
import com.hyperlyrics.mm.xposed.session.YouTubeMusicSessionTracker
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * Coordinates non-blocking Pin/Float mode for Xiaomi HyperOS's Expanded Super Island:
 *  - Launches a dedicated SystemUI floating card window ([PinnedFloatingIslandOverlay])
 *    strictly bounded to the card rectangle with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`
 *    so outside touches, app scrolling, and keyboard input ALWAYS pass through and HyperOS's
 *    global gesture monitor can never minimize the pinned lyrics card.
 *  - Also applies WindowManager flag & touchable-insets guards on the native expanded window
 *    when active, restoring everything cleanly when unpinned or when switching away.
 */
object ExpandedIslandPinController {
    private const val TAG = "ExpandedIslandPin"
    private const val TOUCHABLE_INSETS_REGION = 3

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateReentry = ThreadLocal.withInitial { false }

    private val windowSnapshots = Collections.synchronizedMap(WeakHashMap<View, WindowSnapshot>())
    private val hookedRootClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val hookedCollapseClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    private val attachMonitoredPlayers = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    )
    private val pinStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var xposedModuleRef: XposedModule? = null

    @Volatile
    private var wmGlobalHooked = false

    @Volatile
    private var manualPinOverride: Boolean? = null

    @Volatile
    private var activeMediaSession: Boolean = false

    @Volatile
    private var activePlayerRef: WeakReference<ViewGroup>? = null

    @Volatile
    private var lastSystemContext: Context? = null

    @Volatile
    private var lastCardScreenRect: Rect? = null

    @Volatile
    private var lastPalette: YtmCardPalette = YtmCardPalette.DEFAULT_DYNAMIC

    @Volatile
    private var lastArtworkDrawable: Drawable? = null

    @Volatile
    private var pinnedRootRef: WeakReference<View>? = null

    @Volatile
    private var insetsListenerProxy: Any? = null

    @Volatile
    private var insetsAttachedRootRef: WeakReference<View>? = null

    @Volatile
    private var cachedInsetsApi: InternalInsetsApi? = null

    private data class WindowSnapshot(
        val originalFlags: Int,
        val originalDimAmount: Float
    )

    fun addPinStateListener(listener: (Boolean) -> Unit) {
        pinStateListeners.addIfAbsent(listener)
    }

    fun isPinned(settings: ModuleSettings = RuntimeSettingsHolder.current): Boolean {
        if (PinnedFloatingIslandOverlay.isShowing) return true
        return ExpandedPinPolicy.isEffectivelyPinned(
            masterEnabled = settings.masterEnabled,
            expandedLyricsEnabled = settings.expandedLyricsEnabled,
            autoPinSetting = settings.pinExpandedIslandEnabled,
            manualPinOverride = manualPinOverride
        )
    }

    fun isEffectivelyPinnedNow(): Boolean {
        if (PinnedFloatingIslandOverlay.isShowing) return true
        if (!activeMediaSession) return false
        val player = activePlayerRef?.get() ?: return false
        if (!player.isAttachedToWindow || !player.isShown) return false
        return isPinned(RuntimeSettingsHolder.current)
    }

    fun toggleManualPin(settings: ModuleSettings = RuntimeSettingsHolder.current): Boolean {
        val next = !isPinned(settings)
        manualPinOverride = next
        ModuleLogger.i(TAG, "Expanded island pin toggled -> pinned=$next")

        val player = activePlayerRef?.get()
        if (next) {
            if (player != null) {
                capturePlayerScreenRect(player)?.let { lastCardScreenRect = it }
                applyOrRestoreWindowPin(player, pin = true)
            }
            val ctx = player?.context ?: lastSystemContext
            if (ctx != null && activeMediaSession) {
                PinnedFloatingIslandOverlay.show(
                    context = ctx,
                    anchorRect = lastCardScreenRect,
                    palette = lastPalette,
                    artworkDrawable = lastArtworkDrawable,
                    artworkBitmap = YouTubeMusicSessionTracker.currentArtworkBitmap(),
                    settings = settings,
                    onUnpin = { unpinAndDismiss() }
                )
            }
        } else {
            unpinAndDismiss()
            return false
        }

        notifyPinStateChanged(true)
        return true
    }

    fun unpinAndDismiss() {
        manualPinOverride = false
        PinnedFloatingIslandOverlay.dismiss()
        restorePinnedWindow()
        notifyPinStateChanged(false)
    }

    fun updateVisuals(
        palette: YtmCardPalette,
        artworkDrawable: Drawable?,
        settings: ModuleSettings = RuntimeSettingsHolder.current
    ) {
        lastPalette = palette
        if (artworkDrawable != null) lastArtworkDrawable = artworkDrawable
        if (PinnedFloatingIslandOverlay.isShowing) {
            PinnedFloatingIslandOverlay.updateVisuals(
                palette = palette,
                artworkDrawable = artworkDrawable,
                artworkBitmap = YouTubeMusicSessionTracker.currentArtworkBitmap(),
                settings = settings
            )
        }
    }

    fun clearSession() {
        activeMediaSession = false
        manualPinOverride = null
        activePlayerRef = null
        PinnedFloatingIslandOverlay.dismiss()
        restorePinnedWindow()
        notifyPinStateChanged(false)
    }

    fun hookSystemUiWindowManager(module: XposedModule) {
        xposedModuleRef = module
        if (wmGlobalHooked) return
        wmGlobalHooked = true

        runCatching {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val updateMethod = wmgClass.getDeclaredMethod(
                "updateViewLayout",
                View::class.java,
                ViewGroup.LayoutParams::class.java
            ).apply { isAccessible = true }

            module.hook(updateMethod).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    if (updateReentry.get() == true) return chain.proceed()
                    val view = chain.args.getOrNull(0) as? View
                    val lp = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                    if (view != null && lp != null && view === pinnedRootRef?.get() && isEffectivelyPinnedNow()) {
                        snapshotWindowIfNeeded(view, lp)
                        lp.flags = ExpandedPinPolicy.computePinnedWindowFlags(lp.flags)
                        lp.dimAmount = 0f
                    }
                    return chain.proceed()
                }
            })
            ModuleLogger.i(TAG, "Hooked WindowManagerGlobal.updateViewLayout for expanded pin mode")
        }.onFailure {
            wmGlobalHooked = false
            ModuleLogger.w(TAG, "WindowManagerGlobal.updateViewLayout hook skipped", it)
        }
    }

    fun hookCollapseGuardsForClasses(module: XposedModule, classLoader: ClassLoader, classNames: List<String>) {
        xposedModuleRef = module
        var hooked = 0
        classNames.forEach { className ->
            val cls = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            if (!hookedCollapseClasses.add(cls)) return@forEach
            cls.declaredMethods
                .filter { ExpandedPinPolicy.isAutoCollapseMethodName(it.name) }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept(object : Hooker {
                            override fun intercept(chain: Chain): Any? {
                                if (isEffectivelyPinnedNow()) {
                                    ModuleLogger.d(TAG, "Suppressed auto-collapse ${cls.simpleName}.${method.name} while pinned")
                                    return defaultSuppressedReturn(method.returnType)
                                }
                                return chain.proceed()
                            }
                        })
                        hooked++
                    }
                }
        }
        if (hooked > 0) {
            ModuleLogger.i(TAG, "Installed $hooked expanded island auto-collapse guard hooks")
        }
    }

    fun reconcilePlayer(
        player: ViewGroup,
        isMediaActive: Boolean,
        settings: ModuleSettings,
        palette: YtmCardPalette = lastPalette,
        artworkDrawable: Drawable? = lastArtworkDrawable
    ) {
        lastSystemContext = player.context.applicationContext ?: player.context
        lastPalette = palette
        if (artworkDrawable != null) lastArtworkDrawable = artworkDrawable

        activeMediaSession = isMediaActive
        if (!isMediaActive) {
            if (activePlayerRef?.get() === player) {
                activePlayerRef = null
            }
            manualPinOverride = null
            PinnedFloatingIslandOverlay.dismiss()
            restorePinnedWindow()
            return
        }

        val currentTracked = activePlayerRef?.get()
        if (currentTracked == null || !currentTracked.isAttachedToWindow || player.isAttachedToWindow) {
            activePlayerRef = WeakReference(player)
        }

        ensureAttachLifecycleMonitor(player)

        if (player.isAttachedToWindow && player.windowToken != null) {
            capturePlayerScreenRect(player)?.let { lastCardScreenRect = it }
        }

        val shouldPin = isPinned(settings)
        if (shouldPin && player.isAttachedToWindow && player.windowToken != null && player.isShown) {
            applyOrRestoreWindowPin(player, pin = true)
            if (settings.pinExpandedIslandEnabled && manualPinOverride != false && !PinnedFloatingIslandOverlay.isShowing) {
                PinnedFloatingIslandOverlay.show(
                    context = player.context,
                    anchorRect = lastCardScreenRect,
                    palette = lastPalette,
                    artworkDrawable = lastArtworkDrawable,
                    artworkBitmap = YouTubeMusicSessionTracker.currentArtworkBitmap(),
                    settings = settings,
                    onUnpin = { unpinAndDismiss() }
                )
            }
        } else if (!shouldPin) {
            PinnedFloatingIslandOverlay.dismiss()
            restorePinnedWindow()
        }

        if (PinnedFloatingIslandOverlay.isShowing) {
            PinnedFloatingIslandOverlay.updateVisuals(
                palette = lastPalette,
                artworkDrawable = lastArtworkDrawable,
                artworkBitmap = YouTubeMusicSessionTracker.currentArtworkBitmap(),
                settings = settings
            )
        }
    }

    private fun ensureAttachLifecycleMonitor(player: ViewGroup) {
        if (!attachMonitoredPlayers.add(player)) return

        player.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val p = v as? ViewGroup ?: return
                activePlayerRef = WeakReference(p)
                // Fresh expand cycle: if floating overlay isn't currently showing, allow Auto-Pin again
                if (!PinnedFloatingIslandOverlay.isShowing && manualPinOverride == false) {
                    manualPinOverride = null
                }
                val settings = RuntimeSettingsHolder.current
                if (activeMediaSession && isPinned(settings)) {
                    mainHandler.postDelayed({
                        if (p.isAttachedToWindow && activeMediaSession && isPinned(RuntimeSettingsHolder.current)) {
                            capturePlayerScreenRect(p)?.let { lastCardScreenRect = it }
                            applyOrRestoreWindowPin(p, pin = true)
                            if (RuntimeSettingsHolder.current.pinExpandedIslandEnabled &&
                                manualPinOverride != false &&
                                !PinnedFloatingIslandOverlay.isShowing
                            ) {
                                PinnedFloatingIslandOverlay.show(
                                    context = p.context,
                                    anchorRect = lastCardScreenRect,
                                    palette = lastPalette,
                                    artworkDrawable = lastArtworkDrawable,
                                    artworkBitmap = YouTubeMusicSessionTracker.currentArtworkBitmap(),
                                    settings = RuntimeSettingsHolder.current,
                                    onUnpin = { unpinAndDismiss() }
                                )
                            }
                        }
                    }, 90L)
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                // Keep PinnedFloatingIslandOverlay alive when HyperOS folds its native card behind it!
                if (PinnedFloatingIslandOverlay.isShowing) {
                    restorePinnedWindow()
                    return
                }
                if (activePlayerRef?.get() === v) {
                    manualPinOverride = null
                    restorePinnedWindow()
                }
            }
        })

        player.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob ->
            if ((l != ol || t != ot || r != or_ || b != ob) && v === activePlayerRef?.get()) {
                capturePlayerScreenRect(v)?.let { lastCardScreenRect = it }
                if (isEffectivelyPinnedNow()) {
                    pinnedRootRef?.get()?.requestLayout()
                }
            }
        }
    }

    private fun notifyPinStateChanged(pinned: Boolean) {
        mainHandler.post {
            pinStateListeners.forEach { listener -> runCatching { listener(pinned) } }
        }
    }

    private fun applyOrRestoreWindowPin(player: ViewGroup, pin: Boolean) {
        val rootView = player.rootView ?: return
        if (!pin) {
            restorePinnedWindow()
            return
        }
        if (!player.isAttachedToWindow || player.windowToken == null) return

        val prevRoot = pinnedRootRef?.get()
        if (prevRoot != null && prevRoot !== rootView) {
            restoreWindow(prevRoot)
        }

        pinnedRootRef = WeakReference(rootView)
        ensureRootDispatchTouchHook(rootView)
        attachInternalInsetsListener(rootView)

        val lp = rootView.layoutParams as? WindowManager.LayoutParams
        if (lp != null) {
            snapshotWindowIfNeeded(rootView, lp)
            val targetFlags = ExpandedPinPolicy.computePinnedWindowFlags(lp.flags)
            if (lp.flags != targetFlags || lp.dimAmount != 0f) {
                lp.flags = targetFlags
                lp.dimAmount = 0f
                safeUpdateViewLayout(rootView, lp)
            }
        }
        rootView.requestLayout()
    }

    private fun restorePinnedWindow() {
        val root = pinnedRootRef?.get()
        pinnedRootRef = null
        if (root != null) {
            restoreWindow(root)
        } else {
            detachInternalInsetsListener()
        }
    }

    private fun restoreWindow(rootView: View) {
        detachInternalInsetsListener()
        val snapshot = synchronized(windowSnapshots) { windowSnapshots.remove(rootView) }
        val lp = rootView.layoutParams as? WindowManager.LayoutParams
        if (snapshot != null && lp != null && rootView.isAttachedToWindow) {
            lp.flags = snapshot.originalFlags
            lp.dimAmount = snapshot.originalDimAmount
            safeUpdateViewLayout(rootView, lp)
            rootView.requestLayout()
        }
    }

    private fun snapshotWindowIfNeeded(rootView: View, lp: WindowManager.LayoutParams) {
        synchronized(windowSnapshots) {
            windowSnapshots.getOrPut(rootView) {
                WindowSnapshot(originalFlags = lp.flags, originalDimAmount = lp.dimAmount)
            }
        }
    }

    private fun safeUpdateViewLayout(rootView: View, lp: WindowManager.LayoutParams) {
        if (!rootView.isAttachedToWindow) return
        val wm = rootView.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        updateReentry.set(true)
        try {
            runCatching { wm.updateViewLayout(rootView, lp) }
                .onFailure { ModuleLogger.d(TAG, "updateViewLayout skipped: ${it.message}") }
        } finally {
            updateReentry.set(false)
        }
    }

    private fun ensureRootDispatchTouchHook(rootView: View) {
        val module = xposedModuleRef ?: return
        val cls = rootView.javaClass
        if (!hookedRootClasses.add(cls)) return

        runCatching {
            val method = findMethodInHierarchy(cls, "dispatchTouchEvent", MotionEvent::class.java)
                ?: return@runCatching
            method.isAccessible = true
            module.hook(method).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val host = chain.thisObject as? View
                    val event = chain.args.firstOrNull() as? MotionEvent
                    if (host != null && event != null && host === pinnedRootRef?.get() && isEffectivelyPinnedNow()) {
                        val rect = computeCardWindowRect()
                        val shouldBlock = if (rect != null) {
                            ExpandedPinPolicy.shouldBlockOutsideTouch(
                                actionMasked = event.actionMasked,
                                x = event.x,
                                y = event.y,
                                cardLeft = rect.left,
                                cardTop = rect.top,
                                cardRight = rect.right,
                                cardBottom = rect.bottom
                            )
                        } else {
                            event.actionMasked == MotionEvent.ACTION_OUTSIDE
                        }
                        if (shouldBlock) {
                            return false
                        }
                    }
                    return chain.proceed()
                }
            })
        }.onFailure {
            hookedRootClasses.remove(cls)
        }
    }

    private fun attachInternalInsetsListener(rootView: View) {
        if (insetsAttachedRootRef?.get() === rootView && insetsListenerProxy != null) return
        detachInternalInsetsListener()

        val api = resolveInternalInsetsApi() ?: return
        val vto = rootView.viewTreeObserver
        if (!vto.isAlive) return

        runCatching {
            val proxy = Proxy.newProxyInstance(
                api.listenerInterface.classLoader,
                arrayOf(api.listenerInterface)
            ) { _, method, args ->
                if (method.name == "onComputeInternalInsets" && args != null && args.isNotEmpty()) {
                    if (rootView === pinnedRootRef?.get() && isEffectivelyPinnedNow()) {
                        val cardRect = computeCardWindowRect()
                        if (cardRect != null && cardRect.width() > 0 && cardRect.height() > 0) {
                            api.applyTouchableRegion(args[0], cardRect)
                        }
                    }
                }
                null
            }
            api.addListenerMethod.invoke(vto, proxy)
            insetsListenerProxy = proxy
            insetsAttachedRootRef = WeakReference(rootView)
        }
    }

    private fun detachInternalInsetsListener() {
        val proxy = insetsListenerProxy ?: return
        val root = insetsAttachedRootRef?.get()
        insetsListenerProxy = null
        insetsAttachedRootRef = null
        val api = cachedInsetsApi ?: return
        if (root != null && root.viewTreeObserver.isAlive) {
            runCatching { api.removeListenerMethod.invoke(root.viewTreeObserver, proxy) }
        }
    }

    private fun capturePlayerScreenRect(player: View): Rect? {
        if (!player.isAttachedToWindow || player.width <= 0 || player.height <= 0) return null
        val loc = IntArray(2)
        player.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + player.width, loc[1] + player.height)
    }

    private fun computeCardWindowRect(): Rect? {
        val player = activePlayerRef?.get() ?: return null
        if (!player.isAttachedToWindow || !player.isShown || player.width <= 0 || player.height <= 0) {
            return null
        }

        val maxPadPx = (20f * player.resources.displayMetrics.density).roundToInt()
        var target: View = player
        var parent = player.parent as? View
        repeat(3) {
            val candidate = parent ?: return@repeat
            if (candidate.width > 0 && candidate.height > 0 &&
                candidate.width <= player.width + maxPadPx * 2 &&
                candidate.height <= player.height + maxPadPx * 2
            ) {
                target = candidate
                parent = candidate.parent as? View
            } else {
                return@repeat
            }
        }

        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + target.width, loc[1] + target.height)
    }

    private fun resolveInternalInsetsApi(): InternalInsetsApi? {
        cachedInsetsApi?.let { return it }
        return runCatching {
            val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val addMethod = ViewTreeObserver::class.java.getDeclaredMethod(
                "addOnComputeInternalInsetsListener",
                listenerClass
            ).apply { isAccessible = true }
            val removeMethod = ViewTreeObserver::class.java.getDeclaredMethod(
                "removeOnComputeInternalInsetsListener",
                listenerClass
            ).apply { isAccessible = true }
            val setTouchableInsetsMethod = infoClass.getDeclaredMethod(
                "setTouchableInsets",
                Int::class.javaPrimitiveType!!
            ).apply { isAccessible = true }
            val touchableRegionField = infoClass.getDeclaredField("touchableRegion")
                .apply { isAccessible = true }

            InternalInsetsApi(
                listenerInterface = listenerClass,
                addListenerMethod = addMethod,
                removeListenerMethod = removeMethod,
                setTouchableInsetsMethod = setTouchableInsetsMethod,
                touchableRegionField = touchableRegionField
            ).also { cachedInsetsApi = it }
        }.getOrNull()
    }

    private class InternalInsetsApi(
        val listenerInterface: Class<*>,
        val addListenerMethod: Method,
        val removeListenerMethod: Method,
        private val setTouchableInsetsMethod: Method,
        private val touchableRegionField: java.lang.reflect.Field
    ) {
        fun applyTouchableRegion(info: Any, rect: Rect) {
            runCatching {
                setTouchableInsetsMethod.invoke(info, TOUCHABLE_INSETS_REGION)
                val region = touchableRegionField.get(info) as? Region
                region?.set(rect)
            }
        }
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            runCatching { return current.getDeclaredMethod(name, *paramTypes) }
            current = current.superclass
        }
        return null
    }

    private fun defaultSuppressedReturn(returnType: Class<*>): Any? = when (returnType) {
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
        Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
        else -> null
    }
}
