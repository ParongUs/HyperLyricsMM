package com.hyperlyrics.mm.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAppTargetTest {

    @Test
    fun `matches popular streaming and local music apps by default`() {
        val defaults = ModuleSettings()
        assertTrue(ModuleConfig.isTargetMusicPackage("com.google.android.apps.youtube.music", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.spotify.music", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.spotify.lite", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("deezer.android.app", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.apple.android.music", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.aspiro.tidal", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.soundcloud.android", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.amazon.mp3", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.miui.player", defaults))
        assertTrue(ModuleConfig.isTargetMusicPackage("com.maxmpz.audioplayer", defaults))
        assertFalse(ModuleConfig.isTargetMusicPackage("com.google.android.youtube", defaults))
        assertFalse(ModuleConfig.isTargetMusicPackage("com.instagram.android", defaults))
    }

    @Test
    fun `respects per-app toggles for Spotify and Deezer`() {
        val custom = ModuleSettings(
            supportSpotify = false,
            supportDeezer = true,
            supportAppleMusic = false
        )
        assertFalse(ModuleConfig.isTargetMusicPackage("com.spotify.music", custom))
        assertTrue(ModuleConfig.isTargetMusicPackage("deezer.android.app", custom))
        assertFalse(ModuleConfig.isTargetMusicPackage("com.apple.android.music", custom))
        // YouTube Music stays enabled
        assertTrue(ModuleConfig.isTargetMusicPackage("com.google.android.apps.youtube.music", custom))
    }

    @Test
    fun `wildcard audio mode allows custom players while excluding video and social apps`() {
        val wildcard = ModuleSettings(supportAllMediaApps = true)
        assertTrue(ModuleConfig.isTargetMusicPackage("com.example.customflacplayer", wildcard))
        assertFalse(ModuleConfig.isTargetMusicPackage("com.google.android.youtube", wildcard))
        assertFalse(ModuleConfig.isTargetMusicPackage("com.netflix.mediaclient", wildcard))
        assertFalse(ModuleConfig.isTargetMusicPackage("com.zhiliaoapp.musically", wildcard))
        assertFalse(ModuleConfig.isTargetMusicPackage("com.instagram.android", wildcard))
    }
}
