package com.hyperlyrics.mm.xposed.expanded

import android.graphics.drawable.Drawable
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.hyperlyrics.mm.common.ExpandedThemeStyle
import com.hyperlyrics.mm.common.ModuleConfig
import com.hyperlyrics.mm.common.ModuleLogger
import com.hyperlyrics.mm.common.ModuleSettings
import com.hyperlyrics.mm.lyrics.model.LyricsState
import com.hyperlyrics.mm.xposed.RuntimeSettingsHolder
import com.hyperlyrics.mm.xposed.session.YouTubeMusicSessionTracker
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Coordinates LSPosed hooks for Xiaomi HyperOS's Expanded Super Island Media Player
 * (`com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl`
 * and `MiuiIslandMediaControllerImpl`).
 *
 * Activates exclusively when YouTube Music is bound to the expanded island,
 * smoothly restoring HyperOS's stock layout for all other media apps.
 */
object ExpandedIslandCoordinator {
    private const val TAG = "ExpandedIslandCoord"
    private const val BINDER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl"
    private const val HOLDER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder"
    private const val CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl"
    private const val MEDIA_DATA_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaData"
    private const val MUSIC_BG_VIEW_CLASS = "com.mi.widget.view.MusicBgView"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val colorExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HyperLyricsMM-Palette").apply { isDaemon = true }
    }

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val activeBinders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val binderPalettes = Collections.synchronizedMap(
        WeakHashMap<Any, YtmCardPalette>()
    )
    private val seekBarOriginalStates = Collections.synchronizedMap(
        WeakHashMap<View, SeekBarOffsetSnapshot>()
    )

    @Volatile
    private var nativeApi: NativeBinderApi? = null

    @Volatile
    private var listenersRegistered = false

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return

        val api = NativeBinderApi.create(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            ModuleLogger.w(TAG, "HyperOS expanded media island classes not found on this ROM")
            return
        }
        nativeApi = api

        val hookTargets = listOfNotNull(
            api.attachMethod to Action.ATTACH,
            api.bindMediaDataMethod to Action.BIND,
            api.detachMethod to Action.DETACH,
            api.setAlbumImageMethod?.let { it to Action.ALBUM },
            api.setMusicBgShaderMethod?.let { it to Action.ALBUM },
            api.updateForegroundColorsMethod?.let { it to Action.FOREGROUND }
        )

        var hookedCount = 0
        hookTargets.forEach { (method, action) ->
            runCatching {
                module.hook(method).intercept(BinderInterceptor(action))
                hookedCount++
            }.onFailure {
                ModuleLogger.w(TAG, "Failed hooking ${method.declaringClass.simpleName}.${method.name}", it)
            }
        }

        // Also hook MiuiIslandMediaControllerImpl.reInflateView* so config changes re-apply cleanly
        api.reInflateMethods.forEach { method ->
            runCatching {
                module.hook(method).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val res = chain.proceed()
                        mainHandler.post { refreshAllActiveBinders() }
                        return res
                    }
                })
                hookedCount++
            }
        }

        // Suppress native MusicBgView ambient animation when YTM backdrop is active
        api.musicBgStartMethods.forEach { method ->
            runCatching {
                module.hook(method).intercept(object : Hooker {
                    override fun intercept(chain: Chain): Any? {
                        val view = chain.thisObject as? View
                        if (view != null && shouldSuppressNativeBgForView(view)) {
                            return null
                        }
                        return chain.proceed()
                    }
                })
            }
        }

        // Install collapse guards on HyperOS's media island controller for Pin/Float mode
        ExpandedIslandPinController.hookCollapseGuardsForClasses(
            module = module,
            classLoader = classLoader,
            classNames = listOf(
                CONTROLLER_CLASS,
                BINDER_CLASS,
                "com.android.systemui.statusbar.notification.island.IslandWindowController",
                "com.android.systemui.statusbar.notification.island.DynamicIslandController"
            )
        )

        ensureRuntimeListeners()
        ModuleLogger.i(TAG, "Expanded Hyper Island hooks installed ($hookedCount methods)")
    }

    private fun ensureRuntimeListeners() {
        if (listenersRegistered) return
        listenersRegistered = true

        RuntimeSettingsHolder.addListener {
            refreshAllActiveBinders()
        }

        ExpandedIslandPinController.addPinStateListener { pinned ->
            refreshPinStateOnActiveBinders(pinned)
        }

        YouTubeMusicSessionTracker.addListener(object : YouTubeMusicSessionTracker.Listener {
            override fun onTrackChanged(track: YouTubeMusicSessionTracker.TrackInfo?) {
                refreshLiveLyricsOnActiveBinders()
            }

            override fun onLyricsStateChanged(state: LyricsState) {
                refreshLiveLyricsOnActiveBinders()
            }

            override fun onPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, speed: Float) {
                refreshLiveLyricsOnActiveBinders()
            }
        })
    }

    private enum class Action { ATTACH, BIND, DETACH, ALBUM, FOREGROUND }

    private class BinderInterceptor(private val action: Action) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val binder = chain.thisObject ?: return chain.proceed()
            if (action == Action.DETACH) {
                activeBinders.remove(binder)
                binderPalettes.remove(binder)
                if (activeBinders.isEmpty()) {
                    ExpandedIslandPinController.clearSession()
                }
                return chain.proceed()
            }

            val result = chain.proceed()
            activeBinders.add(binder)

            runCatching {
                val explicitMediaData = if (action == Action.BIND) chain.args.firstOrNull() else null
                applyOrRestoreBinder(
                    binder = binder,
                    explicitMediaData = explicitMediaData,
                    refreshPalette = action == Action.BIND || action == Action.ALBUM
                )
            }.onFailure { error ->
                ModuleLogger.e(TAG, "Error handling expanded island action=$action", error)
            }
            return result
        }
    }

    private fun refreshAllActiveBinders() {
        val snapshot = synchronized(activeBinders) { activeBinders.toList() }
        snapshot.forEach { binder ->
            runCatching { applyOrRestoreBinder(binder, explicitMediaData = null, refreshPalette = true) }
        }
    }

    private fun refreshPinStateOnActiveBinders(isPinned: Boolean) {
        val api = nativeApi ?: return
        val settings = RuntimeSettingsHolder.current
        val snapshot = synchronized(activeBinders) { activeBinders.toList() }

        snapshot.forEach { binder ->
            runCatching {
                val pkg = api.getPackageName(binder, null)
                if (!ModuleConfig.isYouTubeMusicPackage(pkg)) return@forEach
                val palette = binderPalettes[binder] ?: YtmCardPalette.DEFAULT_DYNAMIC
                api.getHolders(binder).forEach { holder ->
                    val player = api.getPlayer(holder) ?: return@forEach
                    ExpandedYtmSplitLayout.updatePinStateOnly(
                        player = player,
                        isPinned = isPinned,
                        settings = settings,
                        palette = palette
                    )
                }
            }
        }
    }

    private fun refreshLiveLyricsOnActiveBinders() {
        val api = nativeApi ?: return
        val settings = RuntimeSettingsHolder.current
        val lyricsState = YouTubeMusicSessionTracker.currentLyricsState
        val playing = YouTubeMusicSessionTracker.isPlaying
        val snapshot = synchronized(activeBinders) { activeBinders.toList() }

        snapshot.forEach { binder ->
            runCatching {
                val pkg = api.getPackageName(binder, null)
                if (!ModuleConfig.isYouTubeMusicPackage(pkg)) return@forEach
                val palette = binderPalettes[binder] ?: YtmCardPalette.DEFAULT_DYNAMIC
                api.getHolders(binder).forEach { holder ->
                    val player = api.getPlayer(holder) ?: return@forEach
                    ExpandedYtmSplitLayout.updateLiveLyricsOnly(
                        player = player,
                        lyricsState = lyricsState,
                        isPlaying = playing,
                        settings = settings,
                        palette = palette
                    )
                }
            }
        }
    }

    private fun applyOrRestoreBinder(
        binder: Any,
        explicitMediaData: Any?,
        refreshPalette: Boolean
    ) {
        val api = nativeApi ?: return
        val settings = RuntimeSettingsHolder.current
        val pkg = api.getPackageName(binder, explicitMediaData)
        val isYtm = ModuleConfig.isYouTubeMusicPackage(pkg)
        val shouldActivate = settings.masterEnabled && settings.expandedLyricsEnabled && isYtm

        val holders = api.getHolders(binder)
        if (holders.isEmpty()) return

        if (!shouldActivate) {
            holders.forEach { holder ->
                api.extractHolderViews(holder)?.let { views ->
                    ExpandedIslandPinController.reconcilePlayer(
                        player = views.player,
                        isYtmActive = false,
                        settings = settings
                    )
                    ExpandedYtmSplitLayout.restoreNativeLayout(views) { seekBar ->
                        restoreSeekBarOffset(api, seekBar)
                    }
                }
            }
            return
        }

        val currentPalette = binderPalettes[binder] ?: when (settings.expandedThemeStyle) {
            ExpandedThemeStyle.YTM_PURE_DARK -> YtmCardPalette.PURE_DARK
            else -> YtmCardPalette.DEFAULT_DYNAMIC
        }

        val isPlaying = api.isPlaying(binder, explicitMediaData) || YouTubeMusicSessionTracker.isPlaying

        // Guarantee session tracker is bound even if Application.onCreate ran before hooking
        val firstPlayer = holders.firstNotNullOfOrNull { api.getPlayer(it) }
        if (firstPlayer != null && pkg != null) {
            YouTubeMusicSessionTracker.ensureBoundFromMediaData(
                context = firstPlayer.context,
                packageName = pkg,
                title = api.getSongTitle(binder, explicitMediaData),
                artist = api.getArtistName(binder, explicitMediaData),
                token = api.getMediaToken(binder, explicitMediaData),
                playingHint = isPlaying
            )
        }

        val lyricsState = YouTubeMusicSessionTracker.currentLyricsState
        val pinnedNow = ExpandedIslandPinController.isPinned(settings)

        holders.forEach { holder ->
            api.extractHolderViews(holder)?.let { views ->
                ExpandedYtmSplitLayout.applyYouTubeMusicLayout(
                    views = views,
                    settings = settings,
                    palette = currentPalette,
                    lyricsState = lyricsState,
                    isPlaying = isPlaying,
                    positionProvider = { YouTubeMusicSessionTracker.currentPositionMs() },
                    onSeekRequested = { targetMs -> YouTubeMusicSessionTracker.seekTo(targetMs) },
                    onRetryRequested = { YouTubeMusicSessionTracker.forceRetryCurrentTrack() },
                    isPinned = pinnedNow,
                    onPinToggleRequested = {
                        ExpandedIslandPinController.toggleManualPin(RuntimeSettingsHolder.current)
                    },
                    applySeekBarColors = { seekBar, active, track ->
                        api.setSeekBarColors(seekBar, active, track)
                    },
                    resetSeekBarOffsets = { seekBar ->
                        zeroSeekBarOffset(api, seekBar)
                    }
                )
                ExpandedIslandPinController.reconcilePlayer(
                    player = views.player,
                    isYtmActive = true,
                    settings = settings
                )
            }
        }

        if (refreshPalette) {
            val artwork = api.getArtworkDrawable(binder, holders.firstOrNull())
            if (artwork != null) {
                extractPaletteAsync(binder, artwork, settings)
            }
        }
    }

    private fun extractPaletteAsync(binder: Any, artwork: Drawable, settings: ModuleSettings) {
        colorExecutor.execute {
            val palette = YtmPaletteExtractor.extract(
                drawable = artwork,
                themeStyle = settings.expandedThemeStyle,
                accentMode = settings.accentColorMode
            )
            binderPalettes[binder] = palette
            mainHandler.post {
                if (activeBinders.contains(binder)) {
                    runCatching { applyOrRestoreBinder(binder, explicitMediaData = null, refreshPalette = false) }
                }
            }
        }
    }

    private fun shouldSuppressNativeBgForView(musicBgView: View): Boolean {
        val settings = RuntimeSettingsHolder.current
        if (!settings.masterEnabled || !settings.expandedLyricsEnabled) return false
        if (settings.expandedThemeStyle == ExpandedThemeStyle.HYPEROS_NATIVE) return false

        val api = nativeApi ?: return false
        val binders = synchronized(activeBinders) { activeBinders.toList() }
        for (binder in binders) {
            if (!ModuleConfig.isYouTubeMusicPackage(api.getPackageName(binder, null))) continue
            if (api.getHolders(binder).any { api.getMediaBgView(it) === musicBgView }) {
                return true
            }
        }
        return false
    }

    private fun zeroSeekBarOffset(api: NativeBinderApi, seekBar: View) {
        if (seekBarOriginalStates[seekBar] == null) {
            api.captureSeekBarOffset(seekBar)?.let { seekBarOriginalStates[seekBar] = it }
        }
        api.applySeekBarOffset(seekBar, paddingOffset = 0, trackPositionX = 0f)
    }

    private fun restoreSeekBarOffset(api: NativeBinderApi, seekBar: View) {
        val orig = seekBarOriginalStates.remove(seekBar) ?: return
        api.applySeekBarOffset(seekBar, orig.paddingOffset, orig.trackPositionX)
    }

    private data class SeekBarOffsetSnapshot(
        val paddingOffset: Int,
        val trackPositionX: Float
    )

    private class NativeBinderApi private constructor(
        val attachMethod: Method,
        val bindMediaDataMethod: Method,
        val detachMethod: Method,
        val setAlbumImageMethod: Method?,
        val setMusicBgShaderMethod: Method?,
        val updateForegroundColorsMethod: Method?,
        val reInflateMethods: List<Method>,
        val musicBgStartMethods: List<Method>,
        private val holderField: Field,
        private val dummyHolderField: Field?,
        private val mediaDataField: Field,
        private val mediaDataPackageField: Field,
        private val mediaDataIsPlayingField: Field?,
        private val mediaDataSongField: Field?,
        private val mediaDataArtistField: Field?,
        private val mediaDataTokenField: Field?,
        private val artworkDrawableField: Field?,
        private val playerField: Field,
        private val albumViewField: Field,
        private val albumImageField: Field,
        private val appIconField: Field?,
        private val titleTextField: Field,
        private val artistTextField: Field,
        private val elapsedTimeField: Field,
        private val totalTimeField: Field,
        private val seekBarField: Field,
        private val seamlessField: Field,
        private val mediaBgViewField: Field?,
        private val getActionListMethod: Method,
        private val setSeekBarFgMethod: Method?,
        private val setSeekBarBgMethod: Method?,
        private val seekBarPaddingOffsetField: Field?,
        private val seekBarTrackPosField: Field?,
        private val seekBarRuntimeShaderField: Field?
    ) {
        fun getHolders(binder: Any): List<Any> {
            val real = runCatching { holderField.get(binder) }.getOrNull()
            val dummy = runCatching { dummyHolderField?.get(binder) }.getOrNull()
            return listOfNotNull(real, dummy).distinct()
        }

        fun getPlayer(holder: Any): ViewGroup? =
            runCatching { playerField.get(holder) as? ViewGroup }.getOrNull()

        fun getMediaBgView(holder: Any): View? =
            runCatching { mediaBgViewField?.get(holder) as? View }.getOrNull()

        fun getPackageName(binder: Any, explicitMediaData: Any?): String? {
            val data = explicitMediaData ?: runCatching { mediaDataField.get(binder) }.getOrNull()
                ?: return null
            return runCatching { mediaDataPackageField.get(data) as? String }.getOrNull()
        }

        fun isPlaying(binder: Any, explicitMediaData: Any?): Boolean {
            val data = explicitMediaData ?: runCatching { mediaDataField.get(binder) }.getOrNull()
                ?: return false
            return runCatching { mediaDataIsPlayingField?.get(data) == true }.getOrDefault(false)
        }

        fun getSongTitle(binder: Any, explicitMediaData: Any?): String? {
            val data = explicitMediaData ?: runCatching { mediaDataField.get(binder) }.getOrNull()
                ?: return null
            return runCatching { mediaDataSongField?.get(data)?.toString() }.getOrNull()
        }

        fun getArtistName(binder: Any, explicitMediaData: Any?): String? {
            val data = explicitMediaData ?: runCatching { mediaDataField.get(binder) }.getOrNull()
                ?: return null
            return runCatching { mediaDataArtistField?.get(data)?.toString() }.getOrNull()
        }

        fun getMediaToken(binder: Any, explicitMediaData: Any?): MediaSession.Token? {
            val data = explicitMediaData ?: runCatching { mediaDataField.get(binder) }.getOrNull()
                ?: return null
            return runCatching { mediaDataTokenField?.get(data) as? MediaSession.Token }.getOrNull()
        }

        fun getArtworkDrawable(binder: Any, holder: Any?): Drawable? {
            runCatching { artworkDrawableField?.get(binder) as? Drawable }.getOrNull()?.let { return it }
            return runCatching { (holder?.let { albumImageField.get(it) } as? ImageView)?.drawable }.getOrNull()
        }

        fun extractHolderViews(holder: Any): ExpandedYtmSplitLayout.NativeHolderViews? = runCatching {
            val player = playerField.get(holder) as? ViewGroup ?: return@runCatching null
            @Suppress("UNCHECKED_CAST")
            val actions = (getActionListMethod.invoke(holder) as? List<View>).orEmpty()
            ExpandedYtmSplitLayout.NativeHolderViews(
                player = player,
                albumView = albumViewField.get(holder) as View,
                albumImage = albumImageField.get(holder) as ImageView,
                appIcon = appIconField?.get(holder) as? ImageView,
                titleText = titleTextField.get(holder) as TextView,
                artistText = artistTextField.get(holder) as TextView,
                elapsedTime = elapsedTimeField.get(holder) as TextView,
                totalTime = totalTimeField.get(holder) as TextView,
                seekBar = seekBarField.get(holder) as View,
                seamless = seamlessField.get(holder) as View,
                actions = actions,
                mediaBgView = mediaBgViewField?.get(holder) as? View
            )
        }.onFailure {
            ModuleLogger.w(TAG, "Failed extracting native holder views", it)
        }.getOrNull()

        fun setSeekBarColors(seekBar: View, activeColor: Int, trackColor: Int) {
            runCatching { setSeekBarFgMethod?.invoke(seekBar, activeColor) }
            runCatching { setSeekBarBgMethod?.invoke(seekBar, trackColor) }
            seekBar.invalidate()
        }

        fun captureSeekBarOffset(seekBar: View): SeekBarOffsetSnapshot? {
            val padField = seekBarPaddingOffsetField ?: return null
            val posField = seekBarTrackPosField ?: return null
            return runCatching {
                val arr = posField.get(seekBar) as? FloatArray ?: return@runCatching null
                SeekBarOffsetSnapshot(
                    paddingOffset = padField.getInt(seekBar),
                    trackPositionX = arr.firstOrNull() ?: return@runCatching null
                )
            }.getOrNull()
        }

        fun applySeekBarOffset(seekBar: View, paddingOffset: Int, trackPositionX: Float) {
            val padField = seekBarPaddingOffsetField ?: return
            val posField = seekBarTrackPosField ?: return
            runCatching {
                padField.setInt(seekBar, paddingOffset)
                val arr = posField.get(seekBar) as? FloatArray ?: return@runCatching
                if (arr.isNotEmpty()) arr[0] = trackPositionX
                val shader = seekBarRuntimeShaderField?.get(seekBar)
                if (shader != null) {
                    val uniformSetter = shader.javaClass.methods.firstOrNull {
                        it.name == "setFloatUniform" &&
                            it.parameterCount == 2 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.parameterTypes[1] == FloatArray::class.java
                    }
                    uniformSetter?.invoke(shader, "uTrackPosition", arr)
                }
                seekBar.requestLayout()
                seekBar.invalidate()
            }
        }

        companion object {
            fun create(classLoader: ClassLoader): NativeBinderApi? = runCatching {
                val binderClass = classLoader.loadClass(BINDER_CLASS)
                val holderClass = classLoader.loadClass(HOLDER_CLASS)
                val mediaDataClass = classLoader.loadClass(MEDIA_DATA_CLASS)
                val controllerClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
                val musicBgClass = runCatching { classLoader.loadClass(MUSIC_BG_VIEW_CLASS) }.getOrNull()

                val seekBarField = holderClass.getDeclaredField("seekBar").apply { isAccessible = true }
                val seekBarClass = seekBarField.type
                val intType = Int::class.javaPrimitiveType!!

                NativeBinderApi(
                    attachMethod = binderClass.declaredMethods.first {
                        it.name == "attach" && it.parameterCount == 2
                    }.apply { isAccessible = true },
                    bindMediaDataMethod = binderClass.declaredMethods.first {
                        it.name == "bindMediaData" && it.parameterCount == 1
                    }.apply { isAccessible = true },
                    detachMethod = binderClass.declaredMethods.first {
                        it.name == "detach" && it.parameterCount == 0
                    }.apply { isAccessible = true },
                    setAlbumImageMethod = binderClass.declaredMethods.firstOrNull {
                        it.name == "setAlbumImage" && it.parameterCount == 1
                    }?.apply { isAccessible = true },
                    setMusicBgShaderMethod = binderClass.declaredMethods.firstOrNull {
                        it.name == "setMusicBgShader" && it.parameterCount == 2
                    }?.apply { isAccessible = true },
                    updateForegroundColorsMethod = binderClass.declaredMethods.firstOrNull {
                        it.name == "updateForegroundColors" && it.parameterCount == 1
                    }?.apply { isAccessible = true },
                    reInflateMethods = controllerClass?.declaredMethods
                        ?.filter { it.name.startsWith("reInflateView") }
                        ?.onEach { it.isAccessible = true }
                        .orEmpty(),
                    musicBgStartMethods = musicBgClass?.declaredMethods
                        ?.filter { (it.name == "start" || it.name == "resume") && it.parameterCount == 0 }
                        ?.onEach { it.isAccessible = true }
                        .orEmpty(),
                    holderField = binderClass.getDeclaredField("holder").apply { isAccessible = true },
                    dummyHolderField = runCatching {
                        binderClass.getDeclaredField("dummyHolder").apply { isAccessible = true }
                    }.getOrNull(),
                    mediaDataField = binderClass.getDeclaredField("mediaData").apply { isAccessible = true },
                    mediaDataPackageField = mediaDataClass.getDeclaredField("packageName").apply { isAccessible = true },
                    mediaDataIsPlayingField = runCatching {
                        mediaDataClass.getDeclaredField("isPlaying").apply { isAccessible = true }
                    }.getOrNull(),
                    mediaDataSongField = runCatching {
                        mediaDataClass.getDeclaredField("song").apply { isAccessible = true }
                    }.getOrNull(),
                    mediaDataArtistField = runCatching {
                        mediaDataClass.getDeclaredField("artist").apply { isAccessible = true }
                    }.getOrNull(),
                    mediaDataTokenField = runCatching {
                        mediaDataClass.getDeclaredField("token").apply { isAccessible = true }
                    }.getOrNull(),
                    artworkDrawableField = listOf("artWorkDrawable", "artworkDrawable").firstNotNullOfOrNull { name ->
                        runCatching { binderClass.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                    },
                    playerField = holderClass.getDeclaredField("player").apply { isAccessible = true },
                    albumViewField = holderClass.getDeclaredField("albumView").apply { isAccessible = true },
                    albumImageField = holderClass.getDeclaredField("albumImageView").apply { isAccessible = true },
                    appIconField = runCatching {
                        holderClass.getDeclaredField("appIcon").apply { isAccessible = true }
                    }.getOrNull(),
                    titleTextField = holderClass.getDeclaredField("titleText").apply { isAccessible = true },
                    artistTextField = holderClass.getDeclaredField("artistText").apply { isAccessible = true },
                    elapsedTimeField = holderClass.getDeclaredField("elapsedTimeView").apply { isAccessible = true },
                    totalTimeField = holderClass.getDeclaredField("totalTimeView").apply { isAccessible = true },
                    seekBarField = seekBarField,
                    seamlessField = holderClass.getDeclaredField("seamless").apply { isAccessible = true },
                    mediaBgViewField = runCatching {
                        holderClass.getDeclaredField("mediaBgView").apply { isAccessible = true }
                    }.getOrNull(),
                    getActionListMethod = holderClass.getDeclaredMethod("getActionList").apply { isAccessible = true },
                    setSeekBarFgMethod = runCatching {
                        seekBarClass.getDeclaredMethod("setForegroundPrimaryColor", intType).apply { isAccessible = true }
                    }.getOrNull(),
                    setSeekBarBgMethod = runCatching {
                        seekBarClass.getDeclaredMethod("setBackgroundPrimaryColor", intType).apply { isAccessible = true }
                    }.getOrNull(),
                    seekBarPaddingOffsetField = runCatching {
                        seekBarClass.getDeclaredField("mProgressPaddingOffset").apply { isAccessible = true }
                    }.getOrNull(),
                    seekBarTrackPosField = runCatching {
                        seekBarClass.getDeclaredField("uTrackPosition").apply { isAccessible = true }
                    }.getOrNull(),
                    seekBarRuntimeShaderField = runCatching {
                        seekBarClass.getDeclaredField("runtimeShader").apply { isAccessible = true }
                    }.getOrNull()
                )
            }.onFailure {
                ModuleLogger.w(TAG, "Failed resolving HyperOS expanded media binder API", it)
            }.getOrNull()
        }
    }
}
