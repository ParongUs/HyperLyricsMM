# HyperLyricsMM

**Native YouTube Music Live Lyrics for Xiaomi HyperOS Super Island** — an LSPosed module (LibXposed API 101+) that upgrades HyperOS's original YouTube Music Super Island player with a compact, usable split layout, flowing synchronized lyrics, YouTube Music theming, offline YouTube Premium download support, and optional fallback/local lyrics extraction.

---

## Features (English)

- **Expanded Hyper Island — Compact Split Player + Flowing Lyrics**
  - Built directly over HyperOS's native `PlayerIslandConstraintLayout` / `MiuiIslandMediaViewBinderImpl` (syncing both real and transition dummy holders).
  - **Left Pane (~45%, adjustable 38%–52%):** 10dp rounded album art, marquee title & artist, output switcher badge, fully scrubbable `HyperProgressSeekBar` + timestamps, and comfortably sized `Prev / Play-Pause / Next / Like` buttons.
  - **Right Pane (~55%):** Custom hardware-accelerated `FlowingLyricsView` with 60–120fps critically-damped spring scrolling, smooth top/bottom gradient edge fades, rhythmic interlude dots (`• • •`), swipe-to-browse, and **Tap-to-Seek**.
  - **YouTube Music Theme:** Album-extracted velvet-dark dynamic gradients, Pure Dark (`#030303`), or HyperOS Native Glass, with `#FF0033` Crimson / White / Album-adaptive accents. Automatically restores stock HyperOS layout for non-YouTube Music apps.
- **Minimal (Collapsed) Island — Optional Single-Line Lyrics**
  - Toggleable on/off in settings. Displays smaller (`9–13.5sp`), crisp single-line synced lyrics beside the camera punch-hole with smooth slide transitions and horizontal marquee.
- **Offline YouTube Music Premium Downloads**
  - **Auto Pre-Cache & Permanent Pinning:** Reads YouTube Music's offline downloads list and permanently pins their official timed lyrics into a local SQLite database (`is_pinned = 1`, immune to LRU eviction).
  - **Direct Offline Storage Extraction:** When completely offline, reads YouTube Music's own local entity/offline SQLite protobuf blobs and displayed Lyrics tab text directly inside the YouTube Music process.
- **Optional Fallback & Local Extraction (for Tracks Without YouTube Music Lyrics)**
  - **External Providers (Opt-In):** LRCLIB (synced LRC), YouTube Timed Captions (CC / auto-transcript), and NetEase Cloud Music.
  - **Local `.lrc` / `.ttml` / `.txt` Folder & Importer:** Pick any device folder via Android SAF or paste/import lyrics directly to pin offline.
  - **On-Device Local Speech-to-Lyrics Extractor (Android 13+ Offline AI):** Opt-in local speech recognizer (`SpeechRecognizer.createOnDeviceSpeechRecognizer` + `AudioPlaybackCapture`) that transcribes internal playback audio locally and saves synced lyrics to the offline cache.
- **Bilingual Companion App (English & Turkish)**
  - In-app language selector (`System / English / Türkçe`), live interactive Expanded + Minimal Island preview, and built-in Live Fetch Tester.

---

## Özellikler (Türkçe)

- **Genişletilmiş Hyper Ada:** HyperOS'un orijinal YouTube Music oynatıcısı üzerinde solda kompakt ve hâlâ rahat kullanılabilir albüm kapağı, kaydırılabilir ilerleme çubuğu ve butonlar; sağda doğrudan YouTube Music'ten akan, dokunarak sarılabilen (Tap-to-Seek) zamanlı şarkı sözleri.
- **Minimal Ada (İsteğe Bağlı):** Ayarlardan açılıp kapatılabilen, daha küçük puntolu, tek satır kayan şarkı sözleri.
- **İnternetsiz YouTube Music Premium Desteği:** Premium ile indirilen şarkıların sözlerini otomatik önbelleğe sabitler ve tamamen çevrimdışıyken YouTube Music'in yerel deposundan/ekranından okuyabilir.
- **Sözü Olmayan Şarkılar İçin Opsiyonel Çözümler:** İsteğe bağlı olarak dış kaynaklardan (LRCLIB, YouTube Altyazıları, NetEase) söz bulma, yerel `.lrc`/`.ttml` klasörü & yapıştırma veya Android 13+ cihaz içi çevrimdışı ses-söz çıkarıcı.
- **Türkçe & İngilizce Dil Desteği:** Uygulama içinden anlık dil değişimi (`Sistem / English / Türkçe`) ve canlı interaktif Hyper Ada önizlemesi.

---

## Requirements & Installation

1. **Xiaomi HyperOS 2 / HyperOS 3** (Android 13+, `minSdk = 33`) with **LSPosed / Vector (LibXposed API 101+)**.
2. Build or install the APK:
   ```bash
   ./gradlew testDebugUnitTest assembleRelease
   ```
   Output APK: `app/build/outputs/apk/release/app-release.apk`
3. Open **LSPosed Manager**, enable **HyperLyricsMM**, and select scopes:
   - `System UI` (`com.android.systemui`)
   - `YouTube Music` (`com.google.android.apps.youtube.music` or your RVX/ReVanced package)
4. Restart SystemUI or reboot. Subsequent setting updates apply live.
