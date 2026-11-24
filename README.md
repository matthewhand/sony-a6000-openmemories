# OpenMemories: Tweak (`A6000 Hub` — LAN Webcam, Display & Persistence Fork)

A clean, TDD-tested fork of [`ma1co/OpenMemories-Tweak`](https://github.com/ma1co/OpenMemories-Tweak) for Sony PlayMemories Camera Apps (PMCA) bodies, built and hardware-verified on **Sony ILCE-6000 (Android 2.3.7 API 10, Sony Scalar API V4)**.

In addition to all upstream tweaks (30-minute video limit unlock, language unlock, PAL/NTSC selector, protection unlock, ADB, and Telnet), this fork transforms the camera into an unattended 24/7 **Home Assistant / Frigate / go2rtc LAN IP camera & control hub** with reproducible offline API-10 builds, Auto Power Off inhibition (`APO/NO`), persistent Static IPv4 & Developer settings, automatic SNTP time sync, hardware rear-LCD backlight shutoff (`DEVICE_ID_NONE`), 6 burn-in-safe screensaver styles (`Random` default), motion still capture & SD lifecycle rotation, and hardware **`C1` / `C2` / `MENU`** quick controls.

> **Word of caution:** This is not an official Sony application. All Sony Scalar integrations (`CameraEx`, `CameraSequence`, `DisplayManager`, `DAConnectionManager`, `TimeUtil`) are reverse-engineered and provided as-is without warranty. No mechanical shutter exposures are ever triggered by the webcam server, and no firmware flashing is performed.

For a deep-dive technical walkthrough of every improvement over upstream `d1acfe8`, see **[`docs/FORK_IMPROVEMENTS.md`](docs/FORK_IMPROVEMENTS.md)**.

---

## Complete PR History vs. Upstream (`d1acfe8`)

Every feature was built onto pristine upstream `d1acfe8` (`Exit to launcher #246`) as a focused, squash-merged, unit-tested PR with **zero hardcoded personal SSIDs or LAN IPs**:

| PR | Issue | Scope | Upstream Candidate? |
|---|---|---|---|
| **#15** | #8 | Reproducible offline API-10 DEX `035` build script (`scripts/build-apk.sh`), pure-Java unit test runner (`scripts/run-tests.sh`), and Java 7 compatibility replacing 7 upstream lambdas | **High** (relates to upstream `#479` / `#521`) |
| **#16** | #9 | Prevent Sony Auto Power Off (`DAConnectionManagerService.apo` `APO/NO` / `APO/NORMAL`) while Wi-Fi or daemons are active (`AutoPowerOffPolicy`, `SonyPowerBridge`) | **High** (addresses upstream `#136` / `#500`) |
| **#17** | #10 | Static IPv4 configuration via Android `Settings.System` (`WRITE_SETTINGS`), seeded from saved values or active `DhcpInfo` with write-order guarantee and DHCP rollback (`StaticIpPolicy`) | **High** (self-contained, zero literals) |
| **#18** | #11 | Persist Developer toggle states across restarts (`DeveloperSettingsPolicy`), remove `DisableWiFiOnSuspend`, and add dual-broadcast boot recovery (`BOOT_COMPLETED` + `DAConnectionManagerService.BootCompleted`) | **Modular** (persistence + suspend fix are clean picks) |
| **#19** | #12 | Burn-in-safe Screensaver & Full-Dim state machine (`ScreensaverPolicy`, `ScreensaverView`, `ScreensaverActivity`) | Fork feature |
| **#20** | #13 | On-camera `CameraSequence` LAN MJPEG & snapshot server (`:8088`), async low-priority BT.601 luma motion wake, and Home Assistant core **Android IP Webcam** compatibility (`LivePreviewActivity`, `PreviewHttpServer`, `AndroidIpWebcamProtocol`) | Fork showcase |
| **#21** | #14 | Initial README overhaul & upstream cherry-pick guide | Repo docs |
| **#24** | #22 | Automatic RFC 4330 SNTPv4 clock sync (`SntpClientPolicy`, `NtpSyncHelper`): updates Linux/Android clock via BusyBox and reflectively sets Sony RTOS `TimeUtil.setCurrentTime` on Sony API V6+ bodies | **High** (especially API V6+ bodies) |
| **#25 / #26** | #23 | 24/7 NP-FW50 dummy-battery documentation + custom Sony α6000 telescopic telephoto SVG dashboard asset (`docs/assets/sony-a6000-telephoto.svg`) | Docs / HAOS asset |
| **#28** | #27 | Remove obsolete 5-minute Wi-Fi watchdog (`PeriodicWifiService` / `PeriodicWifiPolicy`) once Static IPv4 eliminated `dhcpcd` stalls (`-537` lines) | Cleanups |
| **#30** | #29 | Fix Static IPv4 dialog keyboard via custom `NumberKeyListener` (`0-9` + `.`) and auto-bootstrap persisted settings snapshot on first launch (`bootstrapSnapshotIfEmpty`) | **High** (UX fix) |
| **#32** | #31 | Hardware rear-LCD backlight off in `FULL_DIM` via Sony `DisplayManager.switchDisplayOutputTo("DEVICE_ID_NONE")` with guaranteed `DEVICE_ID_PANEL` restore (`DisplayBacklightPolicy`, `SonyDisplayBacklight`) + auto-launch Webcam on app open | Fork feature |
| **#34** | #33 | Motion still reveal (~4.5s grabbed JPEG preview before live view) + SD card archive (`/sdcard/A6000_MOTION`) with configurable retention hours, folder cap, 100 MB SD free-space floor, and FIFO rotation (`MotionStoragePolicy`, `MotionGalleryStore`) | Fork feature |
| **#36** | #35 | Configurable screensaver delay (`0` = off, `>0` = on, default `60s`) and dim delay (default `300s`, `0` = never); replace oscilloscope with 6 Canvas styles (**Matrix Rain**, **Motion Gallery**, **Starfield Warp**, **Digital Clock**, **a6000 Bouncer**, **Minimal Dark**) + debug HUD toggle | Fork feature |
| **#38** | #37 | Make **Random** default screensaver (top of picker above visual divider), mirror Static IPv4 into `SharedPreferences` backup (`AndroidStaticIpStore`) + auto Wi-Fi bounce on apply | Fork feature |
| **#40 / #41** | #39 | Configurable physical **`C1`** custom button (`CustomButtonPolicy`, 7 actions, default Pause/Resume Screensaver Live Hold) + generic ADB broadcast static-IP provisioning (`APPLY_STATIC_IP`, immune to `ScalarALauncher` `stopAppSwitches()`) | Fork feature |
| **#43** | #42 | Fix `RuntimeException: startPreview failed` (`Overlay Creation Failed!`): prevent `BootRecoveryService` from relaunching over an active UI (`BootRecoveryPolicy.SHARED`), add 500ms overlay cooldown + 4-step bounded retry (`CameraRetryPolicy`), self-heal `want_wifi=true` when static IP is active, default Screensaver HUD to OFF | **High** (reliability fix) |
| **#45** | #44 | Eliminate 1-second screensaver black flicker: park `SurfaceView` hardware overlay at `1×1` during `SCREENSAVER`/`FULL_DIM`, paint non-colorkey near-black (`Color.rgb(8,8,8)` / RGB565 `0x0841 != 0x0000`), stop `emptyReads` 1s `statusStrip` height flapping, short-circuit `SonyDisplayBacklight.turnOff()` polling, resume Webcam on Settings idle | **High** (ILCE-6000 HAL fix) |
| **#47** | #46 | 5-second on-launch hardware key tip banner (`MENU` / `C2(?)` / `OK` / `C1`) + keep `APO/NO` active across Settings and Screensaver transitions | UX & reliability |
| **#49** | #48 | Bind physical **`C2` (`? / Delete`)** button (`595` / `623`) to an in-Activity **Quick Control & Telemetry Popup Menu** (`QuickPopupMenuPolicy`: live API/client counts, last client IP, Wi-Fi/RSSI/daemons, battery/thermal/uptime, 5-event activity log + 11 live quick toggles without interrupting `:8088` streaming) + `FINISH_COMP` remote unstick broadcast | Fork showcase |
| **#51** | #50 | Defect sweep & QoL polish: monotonic `System.nanoTime()` frame-wait deadline (immune to NTP jumps), unknown `StatFs` `-1` prune guard + unsigned 32-bit SD block math, HTTP `HEAD` support & accurate `POST /screen/*` stages, `NtpSyncHelper` concurrency guard, crisp ASCII Matrix glyphs for Gingerbread `DroidSansMono`, `C2` popup idle guard, DHCP auto-bounce, and full documentation overhaul | **High** (hardening & docs) |
| **#53** | #52 | Live hardware polish & ergonomics (`v1.6.7`): redesigned large-font fullscreen **`C2`** Quick Menu (~2.5 chunky cells visible with `UP/DN` arrow hint, in-place focus-preserving updates, dedicated **Details & History** view, and live `C1` picker); expanded **`C1`** dropdown (9 actions incl. 3-way **LCD / EVF / OFF** output cycling via Sony `DisplayManager.DEVICE_ID_FINDER` and **Start / Stop HTTP Server :8088**); auto-boot `MENU` → Hub Settings navigation fix; HTTP `/screen/{screensaver,dim}` popup-dismiss fix; pre-Wi-Fi NTP guard + fast LAN timeout; `SO_REUSEADDR` pre-bind fix, atomic `last_client` snapshot, `volatile` cross-thread visibility, and `DeveloperActivity` dialog lifecycle guards | **High** (UX & stability) |
| **#55** | #54 | Stream-view readability & `C2` Full Settings visibility (`v1.6.8`): enlarged live stream status bar (`18sp` bold 2-line overlay) and launch tip banner (`17sp` bold 2-line); promoted **Full Settings Menu (`MENU`)** into the top 3 visible `C2` Quick Menu cells + header hint (`MENU: Full`) so users immediately see the full tabbed Settings menu | UX polish |

---

## Hardware Button Shortcuts (Inside Webcam View)

When **`A6000 Hub`** launches into Webcam mode (`:8088`), a 5-second bottom banner reminds you of the live hardware keys:

| Physical Sony A6000 Button | Scan Code(s) | Action |
|---|---|---|
| **`MENU`** | `514` (`KEYCODE_MENU` / `KEYCODE_BACK`) | Open full **Developer Settings** (works even when auto-booted directly into Webcam) |
| **`C2` (`? / Delete`)** | `595` / `623` | Open / close fullscreen large-font **Quick Menu** (~2.5 big cells visible with `UP/DN` arrow hint, LCD/EVF/OFF output toggle, Start/Stop `:8088` server, saver/motion toggles, `C1` action picker, and dedicated **Details & History** view — never interrupts `:8088` streaming) |
| **`C1`** | `622` / `588` | Configurable custom action (9 options in Developer Settings or `C2` menu; default: **Toggle Screensaver Hold**, plus **Cycle Output: LCD / EVF / OFF**, **Toggle Screen On/Off**, **Start / Stop Server :8088**, etc.) |
| **`OK` (Center)** | `232` (`DPAD_CENTER`) | Enter **Screensaver** immediately |
| **Any key / Touch** *(while dimmed)* | Any | Restore rear LCD (`DEVICE_ID_PANEL`) and wake to Live View |

---

## Developer Tab Features

- **Start Webcam & Stream (`:8088`):** Fullscreen `LivePreviewActivity` streaming hardware-encoded `CameraSequence` JPEGs (`640×424` on 3:2 bodies) without firing the mechanical shutter. Parks `SurfaceView` at `1×1` during screensaver/dim so Sony's hardware video overlay never punches through while `:8088` MJPEG clients stay continuously connected.
- **Enable Wifi & Static IPv4 (`Settings.System` + `SharedPreferences` backup):** Toggling **Static IPv4** ON or OFF writes Android API-10 `Settings.System.wifi_static_*` keys, mirrors them to `static_ip_backup`, and automatically bounces Wi-Fi so the change applies immediately without manual toggling. **Configure Static IP...** uses a dedicated numeric+dot keypad (`0-9`, `.`).
- **Persist Developer settings & Auto boot recovery:** Remembers Wi-Fi, Static IPv4, Keep Awake (`APO/NO`), ADB (pinned to trusted SSID), and opt-in Telnet. Self-heals `want_wifi=true` whenever static IP is active, listens to `BOOT_COMPLETED` + `DAConnectionManagerService.BootCompleted`, skips redundant UI relaunches when already foreground, and includes a 3-incomplete-start crash-loop breaker.
- **Screensaver timing, 6 styles (`Random` default) & HUD toggle:** Configure idle delay in seconds (`0` = off, default `60s`) and LCD backlight-off timeout (default `300s`, `0` = never dim). Styles: **Random** *(default, separated by a divider)*, **Matrix Rain** *(crisp ASCII)*, **Motion Gallery**, **Starfield Warp**, **Digital Clock**, **a6000 Bouncer**, and **Minimal Dark**. Debug HUD defaults to **OFF** (toggleable in Developer settings or the `C2` popup).
- **Hardware LCD Backlight Off in Dim:** Uses Sony `DisplayManager.switchDisplayOutputTo("DEVICE_ID_NONE")` (with `screenBrightness` fallback) to shut off the rear TFT LCD backlight completely during `FULL_DIM`, restoring `DEVICE_ID_PANEL` on wake, pause, or teardown.
- **Motion Still Reveal & SD Card Lifecycle:** Motion wakes show the grabbed motion still (~4.5s) before revealing live view, and optionally archive stills to `/sdcard/A6000_MOTION` with configurable retention age (default `72h`), folder cap (default `250 MB`), `100 MB` SD free-space safety floor, FIFO rotation, and unknown-`StatFs` deletion protection.
- **Auto NTP time sync:** Concurrency-guarded RFC 4330 SNTPv4 client (`LAN gateway -> pool.ntp.org -> time.cloudflare.com`) updating Linux/Android time via BusyBox and reflectively invoking Sony `TimeUtil.setCurrentTime(...)` on Sony API V6+ bodies. *(On ILCE-6000 / API V4, `TimeUtil.setCurrentTime` does not exist in firmware, so NTP syncs the Linux/Android clock only.)*
- **Keep camera awake (`APO/NO`):** Sends `DAConnectionManagerService.apo` (`APO/NO`) across Webcam, Settings, and Screensaver activities whenever enabled so the camera runs 24/7 without powering itself down.

---

## 24/7 Operation: NP-FW50 Dummy Battery Strongly Recommended

> **Important Sony ILCE-6000 Hardware Limitation:** Unlike newer Sony bodies (A6400/A6600/A7 III+) that support USB Power Delivery while operating, **the Sony A6000 cannot shoot or run PlayMemories apps while powered/charging over micro-USB** (connecting USB triggers Mass Storage / MTP / PC Remote mode or power-off charging).

For unattended 24/7 webcam or Home Assistant operation on the A6000:
- Use an **NP-FW50 DC coupler ("dummy battery")** — such as the Sony AC-PW20 or a regulated 7.4–8.4 V / USB-C PD dummy battery routed through the rubber cable pass-through flap on the battery door.
- Continuous dummy-battery power also keeps the camera's internal RTC backup capacitor charged (~24 hours to full charge), preventing Sony's power-loss Area/Date/Time prompt during brief power outages.

---

## Home Assistant & Frigate Integration

### Option A: Home Assistant Core **Android IP Webcam** (Zero YAML)
1. Launch **A6000 Hub** (auto-starts Webcam `:8088`).
2. In Home Assistant: **Settings → Devices & services → Add integration → Android IP Webcam**.
3. Enter your camera's static IP and port `8088` (leave username/password blank unless configured).
4. Home Assistant automatically creates `camera.*` (`/video`, `/shot.jpg`), `binary_sensor.*_motion_active`, `sensor.*_battery_{level,temp,voltage}`, `sensor.*_video_connections`, and `switch.*_{motion_detect,overlay}`.

### Option B: Home Assistant **MJPEG IP Camera**
- **MJPEG URL:** `http://<CAMERA_IP>:8088/liveview.mjpeg`
- **Still Image URL:** `http://<CAMERA_IP>:8088/snapshot.jpg`

### Option C: Frigate + go2rtc
Expose an RTSP/WebRTC restream via `go2rtc.yaml`:
```yaml
streams:
  sony_a6000:
    - http://<CAMERA_IP>:8088/liveview.mjpeg
    - ffmpeg:http://<CAMERA_IP>:8088/liveview.mjpeg#video=h264
```
Use explicit MJPEG `input_args` in Frigate (`-f mjpeg -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5`) with native `640×424` detect dimensions, and call `POST http://<CAMERA_IP>:8088/screen/wake` from a Home Assistant `rest_command` automation to wake the dimmed rear LCD on person detection.

### HAOS Dashboard Graphic (`sony-a6000-telephoto.svg`)
A custom vector illustration of the Sony α6000 with an attached telescopic telephoto zoom lens is included at [`docs/assets/sony-a6000-telephoto.svg`](docs/assets/sony-a6000-telephoto.svg). Copy it to `/config/www/images/sony-a6000-telephoto.svg` and reference `/local/images/sony-a6000-telephoto.svg` in any Lovelace card.

---

## HTTP API Reference (`:8088`)

### Canonical REST Endpoints
| Method & Path | Response | Notes |
|---|---|---|
| `GET /liveview.mjpeg` (alias `/stream.mjpeg`) | `200` `multipart/x-mixed-replace; boundary=a6000frame` | Continuous hardware JPEG stream; monotonic wait deadline (immune to NTP jumps); `503` if camera unavailable |
| `GET` / `HEAD /snapshot.jpg` (alias `/snapshot.jpeg`) | `200` `image/jpeg` (`Cache-Control: no-store`) | Latest fresh preview frame (`X-Sony-Deliberate-Capture: false`); never fires shutter |
| `GET` / `HEAD /health` | `200` or `503` `application/json` | `200` only while a fresh frame (`< 5000 ms`) is available |
| `GET` / `HEAD /status` (alias `/`) | `200` `application/json` | Camera state, preset, frames, FPS, `clients`, `mjpeg_clients`, `total_requests`, `last_client`, `screen_stage`, motion, battery, IP, RSSI |
| `POST /screen/wake` | `200` `application/json` | Restores `DEVICE_ID_PANEL` & wakes to live preview (`previous_stage` + `screen_stage: "ACTIVE"`) |
| `POST /screen/screensaver` | `200` `application/json` | Enters active screensaver style immediately (`screen_stage: "SCREENSAVER"`) |
| `POST /screen/dim` | `200` `application/json` | Turns off rear LCD backlight (`DEVICE_ID_NONE`) immediately (`screen_stage: "FULL_DIM"`) |

### Home Assistant `Android IP Webcam` (`pydroid-ipcam`) Compatibility Endpoints
| Method & Path | Response | Notes |
|---|---|---|
| `GET /video` | `200` MJPEG stream | Alias for `/liveview.mjpeg` |
| `GET` / `HEAD /shot.jpg` | `200` `image/jpeg` | Alias for `/snapshot.jpg` |
| `GET` / `HEAD /status.json` | `200` `application/json` | `curvals` (`video_size`, `motion_detect`, `overlay`) and `avail` |
| `GET` / `HEAD /sensors.json` | `200` `application/json` | `battery_level`, `battery_temp`, `battery_voltage`, `motion_active`, `video_connections` |
| `GET /settings/motion_detect?set=on\|off` | `200` `Ok` | Toggles on-camera motion detection |
| `GET /settings/overlay?set=on\|off` | `200` `Ok` | `on` enters screensaver overlay; `off` wakes to live view |

---

## ADB Remote Provisioning Broadcasts (Generic, Zero Hardcoded IPs)

Because Sony's `ScalarALauncher` holds `ActivityManager.stopAppSwitches()` while the app grid is focused, `BootRecoveryReceiver` accepts generic `am broadcast` intents over ADB (`:5555`) that bypass Activity-switch restrictions:

```bash
# Provision static IPv4 + bounce Wi-Fi + persist want_wifi/want_static_ip/want_adb:
adb shell am broadcast \
  -a com.github.ma1co.openmemories.tweak.APPLY_STATIC_IP \
  -n dev.privatea6000.hub/com.github.ma1co.openmemories.tweak.BootRecoveryReceiver \
  --es static_ip 192.0.2.10 --es static_gateway 192.0.2.1 \
  --es static_netmask 255.255.255.0 --es static_dns1 192.0.2.1 --es static_dns2 8.8.8.8

# Toggle screensaver debug HUD without bouncing Wi-Fi (omit static_ip):
adb shell am broadcast \
  -a com.github.ma1co.openmemories.tweak.APPLY_STATIC_IP \
  -n dev.privatea6000.hub/com.github.ma1co.openmemories.tweak.BootRecoveryReceiver \
  --ez screensaver_hud_enabled false

# Recover remotely if an adb install -r interrupted a ScalarABlackLauncher exit transition:
adb shell am broadcast \
  -a com.github.ma1co.openmemories.tweak.FINISH_COMP \
  -n dev.privatea6000.hub/com.github.ma1co.openmemories.tweak.BootRecoveryReceiver
```

---

## Reproducible Offline Build & Tests

Requirements: JDK 8 (`javac`/`java`), Android `aapt`, `dx`, `zipalign`, Python 3, an Android API 10 `android-2.3.3.jar`, and the pinned upstream `OpenMemories-Tweak-release-0.11.apk` (for original `libadbd.so`, `libprotectiontweak.so`, and `libtweak.so`).

### Run Pure-Java & Loopback HTTP Unit Tests
```bash
./scripts/run-tests.sh
```

### Build Unsigned API-10 (DEX `035`) APK
```bash
# Default upstream package ID (com.github.ma1co.openmemories.tweak):
./scripts/build-apk.sh --out /dev/shm/openmemories-tweak-unsigned.apk

# Side-by-side custom package ID (recommended on Android 2.3.7 so PackageManager
# grants newly added WRITE_SETTINGS, CAMERA, INTERNET, and RECEIVE_BOOT_COMPLETED
# permissions cleanly on first install):
./scripts/build-apk.sh \
  --package-id dev.privatea6000.hub \
  --app-label "A6000 Hub" \
  --version-code 16 \
  --version-name 1.6.8 \
  --out /dev/shm/a6000-hub-unsigned.apk
```
Sign with Android 2.3-compatible **v1 JAR signing** (`jarsigner -sigalg SHA1withRSA -digestalg SHA1`), run `zipalign -f 4`, and install via PMCA or `adb install`.

> **Android 2.3.7 Permission Quirk:** Upgrading an already-installed package via `adb install -r` does not grant newly added `<uses-permission>` entries on Gingerbread. Always use a fresh `--package-id` (or uninstall/reinstall cleanly) when adding permissions. Also uninstall any older stock `com.github.ma1co.openmemories.tweak` package so its legacy `DisableWiFiOnSuspend` receiver cannot fight `A6000 Hub` by turning Wi-Fi off on suspend/boot.

---

## Verification Status

- **Offline verified (16 test suites, 397 assertions):** `AndroidIpWebcamProtocolTests`, `AutoPowerOffPolicyTests`, `BootRecoveryPolicyTests`, `CameraRetryPolicyTests`, `ConditionTests`, `CustomButtonPolicyTests`, `DeveloperSettingsPolicyTests`, `DisplayBacklightPolicyTests`, `MotionDetectorPolicyTests`, `MotionStoragePolicyTests`, `PreviewFrameBusTests`, `PreviewHttpServerTests` (real loopback sockets incl. `HEAD`, MJPEG, Basic Auth), `QuickPopupMenuPolicyTests`, `ScreensaverPolicyTests`, `SntpClientPolicyTests` (real loopback UDP), and `StaticIpPolicyTests`.
- **Hardware verified on Sony ILCE-6000 (Android 2.3.7, API V4):**
  - Static IPv4 via `Settings.System` + `SharedPreferences` mirror (`WifiStateTracker: Static IP configuration succeeded`, hands-free ADB broadcast IP switching).
  - Auto Power Off inhibition (`DAConnectionManagerService-JNI: APO/NO -> call MSS_fakey_stop`).
  - Hardware display switching via Sony `DisplayManager` (`DEVICE_ID_PANEL` rear LCD, `DEVICE_ID_FINDER` EVF viewfinder, `DEVICE_ID_NONE` backlight off) with automatic restoration and uninterrupted `:8088` streaming.
  - Automatic SNTPv4 clock sync setting camera Linux time from `1970` to current UTC (`2026`).
  - Live `640×424` hardware `CameraSequence` JPEG snapshots, continuous MJPEG streaming to live Home Assistant + `go2rtc` + `Frigate` (`mjpeg_clients: 1`), `CameraRetryPolicy` + foreground launch guard fixing `startPreview failed`, and remote `DAConnectionManager.finishComp()` exit-handshake recovery.
- **Known Sony ILCE-6000 firmware boundaries (verified via `/system/framework/services.odex`, `ScalarALauncher.odex`, and `shared_prefs/nfc_shooting.xml`):**
  - On cold power-switch `OFF -> ON`, Sony's Diadem RTOS keeps Android suspended until the user opens **Menu → Application List** (or maps a native Diadem custom key to **Application List**). `ScalarALauncher`'s `ScalarACustomKeyRegister` on ILCE-6000 only exposes `custom_key_NFC_Shooting` (One-touch NFC app launch), not direct Diadem physical `C1`/`C2` app launching.
  - Once Android boots, `BootRecoveryReceiver` / `BootRecoveryService` auto-launches `LivePreviewActivity` (`:8088`), Wi-Fi, Static IP, `APO/NO`, ADB, Telnet, NTP, and Screensaver hands-free — and pressing `MENU` cleanly opens `MainActivity` Hub Settings without dropping back to Diadem.
