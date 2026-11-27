package com.hyperlyrics.mm.xposed.expanded

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
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
 * Enables non-blocking Pin/Float mode for Xiaomi HyperOS's Expanded Super Island:
 *  1. Updates the hosting window's WindowManager.LayoutParams (FLAG_NOT_TOUCH_MODAL |
 *     FLAG_NOT_FOCUSABLE, clearing FLAG_WATCH_OUTSIDE_TOUCH & FLAG_DIM_BEHIND) so touches
 *     and keyboard focus pass directly to underlying apps without dimming the screen.
 *  2. Registers a ViewTreeObserver.OnComputeInternalInsetsListener (TOUCHABLE_INSETS_REGION)
 *     restricting hit-testing strictly to the expanded island card rectangle.
 *  3. Guards root dispatchTouchEvent and HyperOS outside-touch/auto-collapse callbacks so
 *     the expanded card stays open and lyrics keep flowing until the user explicitly unpins it.
 *
 * Cleanly restores original WindowManager flags and insets whenever unpinned, disabled,
 * detached, or when a non-YouTube Music app binds to the island.
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
    private var activeYtmSession: Boolean = false

    @Volatile
    private var activePlayerRef: WeakReference<ViewGroup>? = null

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
        return ExpandedPinPolicy.isEffectivelyPinned(
            masterEnabled = settings.masterEnabled,
            expandedLyricsEnabled = settings.expandedLyricsEnabled,
            autoPinSetting = settings.pinExpandedIslandEnabled,
            manualPinOverride = manualPinOverride
        )
    }

    fun isEffectivelyPinnedNow(): Boolean {
        if (!activeYtmSession) return false
        val player = activePlayerRef?.get() ?: return false
        if (!player.isAttachedToWindow || !player.isShown) return false
        return isPinned(RuntimeSettingsHolder.current)
    }

    fun toggleManualPin(settings: ModuleSettings = RuntimeSettingsHolder.current): Boolean {
        val next = !isPinned(settings)
        manualPinOverride = next
        ModuleLogger.i(TAG, "Expanded island pin toggled -> pinned=$next")

        val player = activePlayerRef?.get()
        if (player != null) {
            applyOrRestoreWindowPin(player, activeYtmSession && next)
        } else if (!next) {
            restorePinnedWindow()
        }

        mainHandler.post {
            pinStateListeners.forEach { listener -> runCatching { listener(next) } }
        }
        return next
    }

    fun clearSession() {
        activeYtmSession = false
        manualPinOverride = null
        activePlayerRef = null
        restorePinnedWindow()
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
        isYtmActive: Boolean,
        settings: ModuleSettings
    ) {
        activeYtmSession = isYtmActive
        if (!isYtmActive) {
            if (activePlayerRef?.get() === player) {
                activePlayerRef = null
            }
            manualPinOverride = null
            restorePinnedWindow()
            return
        }

        // Prefer a real attached player over HyperOS's transition dummyHolder
        val currentTracked = activePlayerRef?.get()
        if (currentTracked == null || !currentTracked.isAttachedToWindow || player.isAttachedToWindow) {
            activePlayerRef = WeakReference(player)
        }

        ensureAttachLifecycleMonitor(player)

        val shouldPin = isPinned(settings)
        if (player.isAttachedToWindow && player.windowToken != null) {
            applyOrRestoreWindowPin(player, shouldPin)
        } else if (!shouldPin) {
            restorePinnedWindow()
        }
    }

    private fun ensureAttachLifecycleMonitor(player: ViewGroup) {
        if (!attachMonitoredPlayers.add(player)) return

        player.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val p = v as? ViewGroup ?: return
                activePlayerRef = WeakReference(p)
                if (activeYtmSession && isPinned(RuntimeSettingsHolder.current)) {
                    mainHandler.post {
                        if (p.isAttachedToWindow && activeYtmSession && isPinned(RuntimeSettingsHolder.current)) {
                            applyOrRestoreWindowPin(p, pin = true)
                        }
                    }
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                // Reset manual override once the expanded island is actually detached/closed
                if (activePlayerRef?.get() === v) {
                    manualPinOverride = null
                    restorePinnedWindow()
                }
            }
        })

        player.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or_, ob ->
            if ((l != ol || t != ot || r != or_ || b != ob) &&
                v === activePlayerRef?.get() &&
                isEffectivelyPinnedNow()
            ) {
                pinnedRootRef?.get()?.requestLayout()
            }
        }
    }

    private fun applyOrRestoreWindowPin(player: ViewGroup, pin: Boolean) {
        val rootView = player.rootView ?: return
        if (!pin) {
            restorePinnedWindow()
            return
        }
        if (!player.isAttachedToWindow || player.windowToken == null) return

        // If switching root windows, cleanly restore the previous one first
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
            ModuleLogger.d(TAG, "Hooked ${cls.simpleName}.dispatchTouchEvent for pin touch guard")
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
            ModuleLogger.d(TAG, "Attached OnComputeInternalInsetsListener to ${rootView.javaClass.simpleName}")
        }.onFailure {
            ModuleLogger.d(TAG, "OnComputeInternalInsetsListener unavailable: ${it.message}")
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

    /**
     * Resolves the exact window-relative bounding rectangle of the expanded media card.
     * Expands slightly to include any immediate rounded card wrapper (up to 20dp padding)
     * without ever expanding to a full-screen scrim container.
     */
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
        }.onFailure {
            ModuleLogger.d(TAG, "InternalInsetsInfo reflection unavailable: ${it.message}")
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
