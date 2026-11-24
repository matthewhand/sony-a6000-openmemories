# Fork Improvements & Architecture Guide vs. Upstream `ma1co/OpenMemories-Tweak`

This document summarizes every architectural, functional, reliability, and UX improvement built in this fork on top of upstream [`ma1co/OpenMemories-Tweak`](https://github.com/ma1co/OpenMemories-Tweak) (`d1acfe8`, *"Exit to launcher #246"*).

All business logic is factored into **pure-Java, dependency-free `*Policy` classes** verified offline across **16 test suites** (`./scripts/run-tests.sh`) before every API-10 DEX `035` APK build (`./scripts/build-apk.sh`).

---

## 1. Reproducible Offline API-10 Toolchain & TDD Harness (PR #15)

| Area | Upstream `d1acfe8` | This Fork |
|---|---|---|
| Build system | Legacy Gradle / Android Studio plugin (broken on modern JDKs) | Reproducible offline shell pipeline ([`scripts/build-apk.sh`](../scripts/build-apk.sh)) using `javac -source 7 -target 7`, `aapt`, `dx` (`DEX 035`), `zipalign`, and SHA-pinned upstream native libs (`libadbd.so`, `libprotectiontweak.so`, `libtweak.so`) |
| Java compatibility | 7 Java 8 lambdas incompatible with `-source 7` / Gingerbread `dx` | Clean Java 7 anonymous classes across all activities |
| Automated testing | None | 16 pure-Java + real loopback socket/UDP test suites ([`scripts/run-tests.sh`](../scripts/run-tests.sh)) executed automatically before every APK build |
| Package ID flexibility | Hardcoded `com.github.ma1co.openmemories.tweak` | `--package-id`, `--app-label`, `--version-code`, `--version-name` flags for clean side-by-side Gingerbread permission grants |

---

## 2. Networking, Static IPv4 & Persistent Developer State (PRs #17, #18, #28, #30, #38, #41, #43, #51)

- **Static IPv4 (`StaticIpPolicy`, `AndroidStaticIpStore`):**
  - Writes Android API-10 `Settings.System.wifi_static_*` keys in safe write-order (`ip`, `netmask`, `gateway`, `dns1`, `dns2` before `wifi_use_static_ip=1`) with readback verification and automatic DHCP rollback on failure.
  - Mirrors configured static IPv4 strings into private `SharedPreferences` (`static_ip_backup`) so Sony firmware resets of `Settings.System` never lose your LAN address.
  - Automatically bounces Wi-Fi when toggling Static IPv4 **ON or OFF**, and uses a custom `NumberKeyListener` (`0-9`, `.`) so Gingerbread opens the numeric keypad instead of an alphabetic keyboard.
- **Persistent Developer Settings (`DeveloperSettingsPolicy`):**
  - Persists Wi-Fi, Static IPv4, Keep Awake (`APO/NO`), ADB (SSID-pinned), and opt-in Telnet across camera restarts.
  - Removed upstream's `DisableWiFiOnSuspend` receiver (which forcibly turned Wi-Fi off on suspend/boot) and removed the obsolete 5-minute Wi-Fi watchdog (PR #28) once static IPv4 eliminated `dhcpcd` stalls.
  - Auto-bootstraps active runtime state on first launch without poisoning `want_wifi=false` when static IP is set while Wi-Fi is momentarily off (`readAndHealForNetwork`).
- **Generic ADB Broadcast Provisioning (`BootRecoveryReceiver`):**
  - Supports `APPLY_STATIC_IP` (with optional `screensaver_hud_enabled`) and `FINISH_COMP` broadcasts over ADB, immune to `ScalarALauncher`'s `ActivityManager.stopAppSwitches()` lockout. Zero hardcoded personal SSIDs or LAN IPs anywhere in the repo.

---

## 3. 24/7 Power Management & Sony `DAConnectionManagerService` Hardening (PRs #16, #25, #47, #49)

- **Auto Power Off Inhibition (`AutoPowerOffPolicy`, `SonyPowerBridge`):**
  - Broadcasts `com.android.server.DAConnectionManagerService.AppInfoReceive` and `com.android.server.DAConnectionManagerService.apo` (`APO/NO`) across `LivePreviewActivity`, `MainActivity`, and `ScreensaverActivity` whenever Keep Awake is enabled.
  - Prevents `LivePreviewActivity.onPause()` from sending `APO/NORMAL` when navigating into Settings (`MENU`), eliminating unintended 3-minute Sony APO shutdowns (`state = 4` / `ScalarABlackLauncher`).
- **Remote Exit-Handshake Recovery (`SonyPowerBridge.completeAndroidExitIfStuck`):**
  - Reflectively invokes `android.app.DAConnectionManager.finishComp()` via ADB `FINISH_COMP` broadcast if an `adb install -r` ever interrupts a `ScalarABlackLauncher` exit transition — recovering normal camera power/button control remotely without pulling the battery.
- **24/7 Power Hardware Guidance:** Documented why an **NP-FW50 DC coupler (dummy battery)** is required on ILCE-6000 (micro-USB disables PlayMemories apps) and keeps the internal RTC backup capacitor charged.

---

## 4. On-Camera LAN Webcam Server & Home Assistant / Frigate Integration (PRs #20, #25, #26, #43, #51)

- **Zero-Shutter Hardware JPEG Pipeline (`LivePreviewActivity`, `CameraOptionPresets`, `PreviewFrameBus`, `CameraRetryPolicy`):**
  - Uses Sony `CameraEx` + `CameraSequence.startPreviewSequence` (`640×0_30fps_memmap` → native 3:2 `640×424` hardware-encoded JPEGs) without ever actuating the mechanical shutter.
  - Trims trailing Sony buffer padding to exact `FFD8..FFD9` JPEG boundaries and uses a **monotonic `System.nanoTime()` deadline** in `waitForNextFrame()` so NTP clock jumps (`1970 -> 2026`) never drop live MJPEG clients.
  - Prevents `RuntimeException: startPreview failed` (`Overlay Creation Failed!`) via:
    1. `BootRecoveryPolicy.SHARED` foreground/opened-UI guard so delayed boot launches never interrupt an active Activity.
    2. `CameraRetryPolicy` 500ms rapid-reopen overlay cooldown + 4-step bounded retry backoff (`600ms`, `1200ms`, `2000ms`, `3000ms`) with clean `CameraEx` teardown.
- **Dual HTTP API (`PreviewHttpServer`, `AndroidIpWebcamProtocol`, port `8088`):**
  - Canonical REST endpoints (`/liveview.mjpeg`, `/snapshot.jpg`, `/health`, `/status`, `POST /screen/{wake,screensaver,dim}`) with `HEAD` support, request counters, last-client tracking, and accurate post-action stage JSON.
  - Full Home Assistant Core **Android IP Webcam (`pydroid-ipcam`)** compatibility (`/video`, `/shot.jpg`, `/status.json`, `/sensors.json`, `/settings/{motion_detect,overlay}`) for zero-YAML HAOS onboarding.
- **Custom HAOS Telephoto SVG (`docs/assets/sony-a6000-telephoto.svg`):** Self-contained vector illustration of the Sony α6000 with an attached telescopic zoom lens.

---

## 5. Rear LCD Backlight Control, Multi-Style Screensaver & Overlay Fix (PRs #19, #32, #36, #38, #45, #51)

- **Real Hardware LCD Backlight Shutoff (`DisplayBacklightPolicy`, `SonyDisplayBacklight`):**
  - Switches Sony's `com.sony.scalar.hardware.avio.DisplayManager` to `DEVICE_ID_NONE` in `FULL_DIM` (with `WindowManager.LayoutParams.screenBrightness` fallback), shutting off the rear LCD backlight completely while `:8088` streaming continues uninterrupted.
  - Guards against disturbing external/EVF outputs, short-circuits repeated `turnOff()` calls so JNI polling never spams `logcat`, and guarantees `DEVICE_ID_PANEL` restoration on key, touch, motion, HTTP wake, pause, or destroy.
- **6 Burn-In-Safe Screensaver Styles + Configurable Timing (`ScreensaverPolicy`, `ScreensaverView`):**
  - Configurable idle delay (`0` = disabled, default `60s`) and dim timeout (default `300s`, `0` = never dim).
  - **Random** *(default, top of picker above divider)* plus **Matrix Rain** *(crisp ASCII glyphs compatible with Gingerbread `DroidSansMono`)*, **Motion Gallery**, **Starfield Warp**, **Digital Clock**, **a6000 Bouncer**, and **Minimal Dark**.
  - Optional drifting telemetry HUD (default **OFF**, toggleable from Developer settings or the `C2` popup menu).
- **Sony Hardware Overlay Colorkey Fix (PR #45):**
  - Sony's `SurfaceView` (`SURFACE_TYPE_PUSH_BUFFERS`) allocates a `640×480` hardware overlay (`overlay_setParameter: param=4, value=0`) keyed to RGB565 `0x0000` (`Color.BLACK`).
  - `LivePreviewActivity` now parks `SurfaceView` at `1×1` during `SCREENSAVER` and `FULL_DIM` (keeping `SurfaceHolder` alive) and `ScreensaverView` paints non-colorkey near-black (`Color.rgb(8, 8, 8)` → RGB565 `0x0841`), completely eliminating 1-second hardware overlay punch-through/flicker.

---

## 6. Async Motion Detection, Still Reveal & SD Card Lifecycle (PRs #20, #34, #51)

- **Async BT.601 Luma Motion Worker (`MotionDetectorPolicy`):** Runs on a dedicated `Thread.MIN_PRIORITY` worker (`80×60` downsampled luma diff, hysteresis, cooldown) and skips JPEG decoding when `ACTIVE` unless HA recently polled `/sensors.json`, keeping live MJPEG frame rates high.
- **Grabbed Motion Still Reveal:** On motion wake from screensaver/dim, displays the captured motion JPEG still (~4.5s) with a bottom status banner before returning to live view.
- **SD Card Motion Archive & Safety (`MotionStoragePolicy`, `MotionGalleryStore`):**
  - Saves stills atomically (`tmp -> rename`) to `/sdcard/A6000_MOTION/motion_YYYYMMDD_HHMMSS_NNN.jpg` (browsable in the **Motion Gallery** screensaver).
  - Enforces retention age (default `72h`, guarded against unsynced `1970` clocks), folder size cap (default `250 MB`), and a `100 MB` SD free-space floor via oldest-first FIFO pruning — while safely ignoring the free-space prune pass if `StatFs` ever returns `-1` (unknown) and using unsigned 32-bit block math for >2 GB SD cards.

---

## 7. Physical Camera Controls: `C1`, `C2` Quick Menu & On-Launch Tip (PRs #40, #47, #49, #51)

- **On-Launch Tip Banner:** Displays a 5-second non-intrusive bottom tip on Webcam launch (`TIP: MENU: Settings | C2(?): Quick Menu | OK: Saver | C1: Pause Saver`).
- **Configurable `C1` Button (`CustomButtonPolicy`):** Binds Sony `C1` (`622` / `588`) to 7 selectable actions (default: **Pause/Resume Screensaver & Dim Live Hold**).
- **`C2` (`? / Delete`) In-Activity Quick Menu & Live Dashboard (`QuickPopupMenuPolicy`):**
  - Pressing `C2` (`595` / `623`) opens a scrollable live dashboard and 11-item quick-toggle menu directly over `LivePreviewActivity` **without pausing the camera or dropping `:8088` MJPEG clients** (and holds the screensaver idle timer paused while open):
    - **`[API & CLIENTS]`:** Endpoint, pipeline state, FPS, frame count, active MJPEG streamers, active HTTP clients, total requests served, last client IP/path/age.
    - **`[WI-FI & DAEMONS]`:** IPv4 + `[STATIC]`/`[DHCP]`, SSID, RSSI dBm + quality label, `ADB :5555`, `Telnet :23`, NTP UTC clock.
    - **`[POWER & DISPLAY]`:** Battery `%`, voltage `V`, temp `C`, `APO/NO` state, session uptime, display stage/style/timing/HUD.
    - **`[HISTORY & MOTION]`:** Motion status/wakes/last wake reason, SD card gallery usage, and a 5-entry rolling recent-activity log.
    - **11 Quick Toggles/Actions:** Live Hold toggle, Enter Screensaver Now, Turn Off LCD Backlight Now, Debug HUD toggle, Next Style, Motion Wake toggle, SD Save toggle, live async NTP Sync Now, Refresh Telemetry, Open Full Settings, and Close.

---

## 8. Automatic RFC 4330 SNTPv4 Time Sync (PRs #24, #51)

- Pure-Java UDP/123 SNTPv4 client (`SntpClientPolicy`, `NtpSyncHelper`) trying `LAN gateway -> pool.ntp.org -> time.cloudflare.com`.
- Protected by an `AtomicBoolean` in-flight guard and 15-second auto-retry cooldown so rapid Wi-Fi state broadcasts never spawn duplicate NTP threads.
- Updates Linux/Android time via injection-safe `busybox date -u -s "YYYY-MM-DD HH:MM:SS"` and reflectively invokes Sony `com.sony.scalar.sysutil.TimeUtil.setCurrentTime` on Sony API V6+ cameras.
