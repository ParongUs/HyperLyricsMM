# Keep LibXposed entry point and hookers
-keep class com.hyperlyrics.mm.xposed.HookEntry { *; }
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker { *; }
-keep class com.hyperlyrics.mm.ipc.LyricsBridgeProvider { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep kotlinx.serialization generated serializers & models
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.hyperlyrics.mm.lyrics.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hyperlyrics.mm.lyrics.model.**$$serializer { *; }
-keepclassmembers class com.hyperlyrics.mm.lyrics.model.** { *; }

# Keep custom Views used inside SystemUI
-keep class com.hyperlyrics.mm.xposed.expanded.FlowingLyricsView { *; }
-keep class com.hyperlyrics.mm.xposed.expanded.YtmAmbientBackdropView { *; }
-keep class com.hyperlyrics.mm.xposed.expanded.IslandPinToggleView { *; }
-keep class com.hyperlyrics.mm.xposed.expanded.ExpandedIslandPinController { *; }
-keep class com.hyperlyrics.mm.xposed.minimal.MinimalSingleLineLyricView { *; }
-keep class com.hyperlyrics.mm.lyrics.local.LocalSpeechLyricsService { *; }
