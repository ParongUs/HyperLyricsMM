# HyperLyricsMM

**Native Live Synchronized Lyrics for Xiaomi HyperOS Super Island** — an LSPosed / Vector module (LibXposed API 101+) that upgrades HyperOS's Super Island media player for **YouTube Music, Spotify, Deezer, Apple Music, Tidal, SoundCloud, Amazon Music, and local music players** with a compact split layout, flowing synchronized lyrics, a true non-blocking pinned floating mode, offline YouTube Premium download support, and optional fallback/local lyrics extraction.

---

## Features (English)

- **Expanded Hyper Island — Compact Split Player + Flowing Lyrics**
  - Built directly over HyperOS's native `PlayerIslandConstraintLayout` / `MiuiIslandMediaViewBinderImpl` (syncing both real and transition dummy holders).
  - **Left Pane (~45%, adjustable 38%–52%):** 10dp rounded album art, marquee title & artist, output switcher badge, fully scrubbable `HyperProgressSeekBar` + timestamps, and comfortably sized `Prev / Play-Pause / Next / Like` buttons.
  - **Right Pane (~55%):** Custom hardware-accelerated `FlowingLyricsView` with 60–120fps critically-damped spring scrolling, smooth top/bottom gradient edge fades, rhythmic interlude dots (`• • •`), swipe-to-browse, and **Tap-to-Seek**.
  - **True Non-Blocking Pinned Floating Mode:** Tap the **Pin (`📌`)** badge at the top-right corner (or enable Auto-Pin) to launch a dedicated SystemUI non-blocking floating card (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`) bounded strictly to the card rectangle — touching, scrolling, or typing in apps underneath never minimizes it, and you can drag the header vertically to reposition it out of the way.
  - **Dynamic Theming:** Album-extracted velvet-dark dynamic gradients, Pure Dark (`#030303`), or HyperOS Native Glass, with `#FF0033` Crimson / White / Album-adaptive accents. Automatically restores stock HyperOS layout for unsupported apps.
- **Multi-App Music Support (Zero Extra Scope Needed)**
  - **YouTube Music** (including RVX / ReVanced), **Spotify**, **Deezer**, **Apple Music**, **Tidal**, **SoundCloud**, **Amazon Music**, and popular local players (**Xiaomi Music, Samsung Music, Poweramp, Musicolet, Oto Music, Symfonium, Retro/Metro, Salt Player, Qobuz, OuterTune/InnerTune/Metrolist/SimpMusic**, etc.), plus an optional wildcard mode for all audio players (automatically excluding video/social apps).
  - Smart lookup: uses exact YouTube Music Innertube timed lyrics for YouTube Music, and prioritizes duration-matched synced **LRCLIB** (with automatic YouTube Music fallback) for Spotify, Deezer, Apple Music, and local players.
- **Minimal (Collapsed) Island — Optional Single-Line Lyrics**
  - Toggleable on/off in settings. Displays smaller (`9–13.5sp`), crisp single-line synced lyrics beside the camera punch-hole with smooth slide transitions and horizontal marquee.
- **Offline YouTube Music Premium Downloads**
  - **Auto Pre-Cache & Permanent Pinning:** Reads YouTube Music's offline downloads list and permanently pins their official timed lyrics into a local SQLite database (`is_pinned = 1`, immune to LRU eviction).
  - **Direct Offline Storage Extraction:** When completely offline, reads YouTube Music's own local entity/offline SQLite protobuf blobs and displayed Lyrics tab text directly inside the YouTube Music process.
- **Optional Fallback & Local Extraction (for Tracks Without Official Lyrics)**
  - **External Providers:** LRCLIB (synced LRC), YouTube Timed Captions (CC / auto-transcript), and NetEase Cloud Music.
  - **Local `.lrc` / `.ttml` / `.txt` Folder & Importer:** Pick any device folder via Android SAF or paste/import lyrics directly to pin offline.
  - **On-Device Local Speech-to-Lyrics Extractor (Android 13+ Offline AI):** Opt-in local speech recognizer (`SpeechRecognizer.createOnDeviceSpeechRecognizer` + `AudioPlaybackCapture`) that transcribes internal playback audio locally and saves synced lyrics to the offline cache.
- **4-Tab Bilingual Companion App (English & Turkish)**
  - Clean bottom navigation (`Home / Island / Sources / Support`) with in-app language selector (`System / English / Türkçe`), live interactive Expanded + Minimal Island preview, built-in Live Fetch Tester, log viewer, and direct email/GitHub feedback (`mehmeteymensevik@gmail.com`).

---

## Özellikler (Türkçe)

- **Genişletilmiş Hyper Ada:** HyperOS'un medya oynatıcısı üzerinde solda kompakt ve rahat kullanılabilir albüm kapağı, kaydırılabilir ilerleme çubuğu ve kontroller; sağda akan, dokunarak sarılabilen (Tap-to-Seek) zamanlı şarkı sözleri.
- **Gerçek Kilitleme / Yüzen Sabitleme Modu:** Kartın sağ üstündeki **Kilitle (`📌`)** butonuna bastığınızda (veya Otomatik Kilitleme açıkken) SystemUI içinde dış dokunuşları ve klavyeyi tamamen alta geçiren sabit bir yüzen ada penceresi açılır; ekranda başka uygulamalarda gezerken veya yazarken kesinlikle küçülmez, üst başlıktan tutup dikey olarak kaydırabilirsiniz.
- **Çoklu Müzik Uygulaması Desteği:** **YouTube Music** (RVX/ReVanced dahil), **Spotify**, **Deezer**, **Apple Music**, **Tidal**, **SoundCloud**, **Amazon Music** ve popüler yerel oynatıcılar (**Xiaomi Müzik, Samsung Müzik, Poweramp, Musicolet, Oto Music, Symfonium** vb.) ek LSPosed kapsamı gerekmeden doğrudan Sistem Arayüzü üzerinden çalışır.
- **Minimal Ada (İsteğe Bağlı):** Ayarlardan açılıp kapatılabilen, daha küçük puntolu, tek satır kayan şarkı sözleri.
- **İnternetsiz YouTube Music Premium Desteği:** Premium ile indirilen şarkıların sözlerini otomatik önbelleğe sabitler ve çevrimdışıyken YouTube Music'in yerel deposundan/ekranından okuyabilir.
- **Sözü Olmayan Şarkılar İçin Opsiyonel Çözümler:** LRCLIB, YouTube Altyazıları, NetEase, yerel `.lrc`/`.ttml` klasörü & yapıştırma veya Android 13+ cihaz içi çevrimdışı ses-söz çıkarıcı.
- **4 Sekmeli Türkçe & İngilizce Arayüz + Geri Bildirim:** Ana Sayfa, Ada, Kaynaklar ve Destek sekmeleri; canlı interaktif önizleme, log görüntüleyici ve doğrudan geri bildirim (`mehmeteymensevik@gmail.com`).

---

## Requirements & Installation

1. **Xiaomi HyperOS 2 / HyperOS 3** (Android 13+, `minSdk = 33`) with **LSPosed / Vector (LibXposed API 101+)**.
2. **Package ID:** `io.github.parongus.hyperlyricsmm` (published on the official LSPosed & Vector [Xposed-Modules-Repo](https://github.com/Xposed-Modules-Repo/io.github.parongus.hyperlyricsmm)).
3. Install the latest APK from [GitHub Releases](https://github.com/ParongUs/HyperLyricsMM/releases/latest) or build from source:
   ```bash
   ./gradlew testDebugUnitTest testReleaseUnitTest assembleRelease
   ```
   Output APK: `app/build/outputs/apk/release/app-release.apk`
4. Open **LSPosed / Vector Manager**, enable **HyperLyricsMM**, and select scopes:
   - `System UI` (`com.android.systemui`) — required for **all** supported music apps (YouTube Music, Spotify, Deezer, Apple Music, etc.)
   - `YouTube Music` (`com.google.android.apps.youtube.music` or your RVX/ReVanced package) — optional, enables exact `videoId` & offline Premium download pre-caching
5. Restart SystemUI or reboot. Subsequent setting updates apply live.

---

## Acknowledgements & Inspiration / Teşekkürler

HyperLyricsMM stands on the shoulders of awesome open-source Android, HyperOS, and Xposed projects. Huge thanks to:

- **[LSPosed](https://github.com/LSPosed/LSPosed) & [LibXposed](https://github.com/libxposed/api)** — Modern LibXposed API 101+, `RemotePreferences`, and the official [Xposed-Modules-Repo](https://github.com/Xposed-Modules-Repo).
- **[Vector (`JingMatrix/Vector`)](https://github.com/JingMatrix/Vector)** — High-performance modern ART hooking framework supporting Android 8.1–17 and LibXposed API.
- **HyperOS Super Island & Lyric Research Community** ([HyperCeiler](https://github.com/ReChronoRain/HyperCeiler), [HyperIsland](https://github.com/search?q=HyperIsland+HyperOS), [LyricGetter](https://github.com/xiaowine/Lyric-Getter) / SuperLyric ecosystem) — Pioneering HyperOS media island (`MiuiIslandMediaViewBinderImpl`, `miui.systemui.dynamicisland`) and live system lyrics concepts.
- **[OuterTune](https://github.com/OuterTune/OuterTune), [InnerTune](https://github.com/z-huang/InnerTune), [Metrolist](https://github.com/mostafaalagamy/Metrolist) & [SimpMusic](https://github.com/maxrave-dev/SimpMusic)** — Inspiration for clean YouTube Music Innertube lyrics handling and flowing dark obsidian/crimson lyrics UX.
- **[LRCLIB (`tranxuanthang/lrclib`)](https://github.com/tranxuanthang/lrclib)** — Free, open-source community database providing high-accuracy synced LRC lyrics across Spotify, Deezer, Apple Music, and local tracks.
- **Jetpack Compose & OkHttp** — Modern Android UI and HTTP networking stack.

---

## License

HyperLyricsMM is licensed under the **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)**.
