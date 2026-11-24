# OpenMemories: Tweak (LAN Webcam & Persistence Fork)

A clean, TDD-tested fork of [`ma1co/OpenMemories-Tweak`](https://github.com/ma1co/OpenMemories-Tweak) for Sony PlayMemories Camera Apps (PMCA) bodies (developed and hardware-tested on **Sony ILCE-6000 / Android 2.3.7 API 10**).

In addition to all upstream tweaks (30-minute video limit unlock, language unlock, PAL/NTSC selector, protection unlock, ADB, and Telnet), this fork adds reproducible offline builds, Auto Power Off inhibition, static IPv4 configuration, persistent Developer settings, Wi-Fi resilience, a burn-in-safe screensaver/full-dim mode, and an on-camera LAN MJPEG webcam server compatible with **Home Assistant**, **Frigate**, and **go2rtc**.

> **Word of caution:** This is not an official Sony application. All Sony Scalar integrations are reverse engineered and provided as-is without warranty. No direct hardware LCD-off / experimental display-driver mutations are included in this repository.

---

## What Changed vs. Upstream (`d1acfe8`)

Every feature was built onto pristine upstream `d1acfe8` (`Exit to launcher #246`) as a focused, squash-merged, unit-tested PR so individual changes can be reviewed and cherry-picked upstream cleanly:

| PR | Issue | Scope | Upstream Candidate? |
|---|---|---|---|
| **#15** | #8 | Reproducible offline API-10 DEX `035` build script (`scripts/build-apk.sh`), pure-Java unit test runner (`scripts/run-tests.sh`), and Java 7 compatibility replacing 7 upstream lambdas | **High** (relates to upstream `#479` / `#521`) |
| **#16** | #9 | Prevent Sony Auto Power Off (`DAConnectionManagerService.apo` `APO/NO` / `APO/NORMAL`) while Wi-Fi or developer daemons are active (`AutoPowerOffPolicy` + `SonyPowerBridge`) | **High** (directly addresses upstream `#136` / `#500`) |
| **#17** | #10 | Static IPv4 configuration via Android `Settings.System` (`WRITE_SETTINGS`), seeded from saved values or active `DhcpInfo` with write-order guarantee and DHCP rollback (`StaticIpPolicy`) | **High** (self-contained, zero personal literals) |
| **#18** | #11 | Persist Developer toggle states across restarts (`DeveloperSettingsPolicy`), remove `DisableWiFiOnSuspend`, add 5-min OFF-acknowledged Wi-Fi watchdog (`PeriodicWifiService`), and add dual-broadcast boot autorun (`BOOT_COMPLETED` + `DAConnectionManagerService.BootCompleted`) | **Modular** (persistence + suspend fix are clean standalone picks) |
| **#19** | #12 | Burn-in-safe Screensaver & Pure-Black Full-Dim mode (`60s idle -> 5m starfield/oscilloscope/HUD -> zero-draw #000000`, `ScreensaverPolicy` + `ScreensaverView`) | Optional / fork feature |
| **#20** | #13 | On-camera `CameraSequence` LAN MJPEG & snapshot server (`:8088`), async low-priority BT.601 luma motion wake, and Home Assistant core **Android IP Webcam** compatibility (`LivePreviewActivity`, `PreviewHttpServer`, `AndroidIpWebcamProtocol`) | Fork showcase feature |
| **#21** | #14 | Documentation overhaul & upstream cherry-pick guide (`README.md`) | Repo docs |
| **#24** | #22 | Automatic RFC 4330 SNTPv4 time sync (`SntpClientPolicy` + `NtpSyncHelper`): updates Linux/Android clock via `Shell.exec` and reflectively invokes Sony `TimeUtil.setCurrentTime` on Sony API V6+ bodies | **High** (especially on API V6+ bodies) |
| **#25** | #23 | 24/7 NP-FW50 dummy-battery power note + Sony A6000 telephoto SVG dashboard asset (`docs/assets/sony-a6000-telephoto.svg`) | Docs / HAOS asset |

---

## Developer Tab Features

- **Start Webcam & Stream (`:8088`):** Launches fullscreen `LivePreviewActivity` streaming hardware-encoded `CameraSequence` JPEGs (`640x424` on 3:2 bodies) over your home LAN without ever triggering the mechanical shutter. `ScreensaverView` overlays `SurfaceView` inside the same Activity so MJPEG clients never disconnect when the rear screen dims to pure black.
- **Enable Wifi & Static IPv4 (`Settings.System`):** Toggling **Static IPv4** writes Android API 10 `Settings.System.wifi_static_*` keys (seeded automatically from existing saved values or your active DHCP lease, or editable via **Configure Static IP...**). `WifiStateTracker` applies the static IP directly and bypasses `dhcpcd`.
- **Persist Developer settings:** Automatically remembers your desired toggle states (Wi-Fi, Static IPv4, Keep Awake, ADB pinned to your connected SSID, opt-in Telnet) and reconciles them in safe order on app launch and boot — no need to re-toggle every session.
- **Keep camera awake (`APO/NO`):** Sends `DAConnectionManagerService.apo` (`APO/NO`) while Wi-Fi, ADB, Telnet, or webcam streaming is active and restores `APO/NORMAL` when turned off.
- **Screensaver timing, 6 styles (`Random` default) & HUD toggle:** Set idle delay in seconds (`0` disables screensaver logically; default `60s`) and LCD dim/backlight-off timeout (default `300s`, `0` = never dim). Choose **Random** *(default, separated by a divider)* or pick from **Matrix Rain**, **Motion Gallery**, **Starfield Warp**, **Digital Clock**, **a6000 Bouncer**, and **Minimal Dark**, and toggle the debug telemetry HUD ON/OFF.
- **Hardware LCD Backlight Off in Dim:** Uses Sony `DisplayManager.switchDisplayOutputTo("DEVICE_ID_NONE")` (with `screenBrightness` fallback) to turn off the rear TFT LCD backlight in `FULL_DIM` while keeping MJPEG streaming active; restores `DEVICE_ID_PANEL` automatically on key, touch, motion, `POST /screen/wake`, or pause/destroy.
- **Motion Still Reveal & SD Card Lifecycle:** Motion events wake the screen to show the captured motion JPEG still (~4.5s) before transitioning to the live view, and optionally archive stills to `/sdcard/A6000_MOTION` with configurable retention hours (default `72h`), folder cap (default `250 MB`), `100 MB` SD free-space safety floor, FIFO rotation, and capacity observation.
- **Configurable `C1` custom button:** Bind Sony's physical `C1` key (`ISV_KEY_CUSTOM1=622`) inside Webcam mode to toggle Screensaver & Dim (default Live Hold toggle), cycle `Live -> Saver -> Dim`, toggle LCD backlight-off immediately, toggle the debug HUD, toggle motion wake, or cycle screensaver styles.
- **Auto-start Webcam on open/boot:** Opening the app reconciles persisted Static IP / APO/NO / Wi-Fi / ADB / NTP and jumps straight into Webcam (`:8088`); pressing `MENU`/`BACK` returns directly to the Developer tab. Static IPv4 can also be provisioned over ADB via `am start -n <pkg>/...LivePreviewActivity --ez apply_static_ip true --es static_ip <IP> --es static_gateway <GW>`.
- **Auto NTP time sync:** Automatically queries NTP (UDP/123: LAN gateway → `pool.ntp.org` → `time.cloudflare.com`) once Wi-Fi connects, updating the Linux/Android clock (so Home Assistant `/sensors.json` epoch timestamps and `TWEAKLOG.TXT` logs are accurate instead of `1970-01-01`). On **Sony API V6+** cameras (A6300, A6500, A7 II, RX100M4), it also reflectively invokes Sony `TimeUtil.setCurrentTime(...)` to set the RTOS/EXIF hardware clock. *(Note: On **Sony A6000 / API V4**, `TimeUtil.setCurrentTime` does not exist in firmware, so NTP syncs the Linux/Android clock only and cannot dismiss Sony's pre-boot RTOS date/time prompt after full capacitor drain.)*
- **Auto boot recovery:** Listens to both `android.intent.action.BOOT_COMPLETED` and Sony's native `com.android.server.DAConnectionManagerService.BootCompleted` (deduplicated once per boot and guarded by a 3-incomplete-start crash-loop breaker).

---

## 24/7 Operation: NP-FW50 Dummy Battery Strongly Recommended

> **Important Sony ILCE-6000 Hardware Limitation:** Unlike newer Sony bodies (A6400/A6600/A7 III+) that support USB Power Delivery while operating, **the Sony A6000 cannot shoot or run PlayMemories apps while powered/charging over micro-USB** (connecting USB triggers Mass Storage / MTP / PC Remote mode or power-off charging).

For unattended 24/7 webcam or Home Assistant operation on the A6000:
- Use an **NP-FW50 DC coupler ("dummy battery")** — such as the Sony AC-PW20 or a regulated 7.4–8.4 V / USB-C PD dummy battery routed through the rubber cable pass-through flap on the battery door.
- Continuous dummy-battery power also keeps the camera's internal RTC backup capacitor charged (typically within ~24 hours of continuous power), preventing Sony's power-loss Area/Date/Time prompt during brief power outages.

---

## Home Assistant & Frigate Integration

### Option A: Home Assistant Core **Android IP Webcam** (Zero YAML)
1. Start **Webcam & Stream (`:8088`)** on the camera.
2. In Home Assistant: **Settings → Devices & services → Add integration → Android IP Webcam**.
3. Enter your camera's static IP and port `8088` (leave username/password blank unless configured).
4. Home Assistant automatically creates:
   - `camera.*` (MJPEG via `/video`, snapshots via `/shot.jpg`)
   - `binary_sensor.*_motion_active` (on-camera motion detector)
   - `sensor.*_battery_level`, `sensor.*_battery_temp`, `sensor.*_battery_voltage`, `sensor.*_video_connections`
   - `switch.*_motion_detect` and `switch.*_overlay`

### Option B: Home Assistant **MJPEG IP Camera**
- **MJPEG URL:** `http://<CAMERA_IP>:8088/liveview.mjpeg`
- **Still Image URL:** `http://<CAMERA_IP>:8088/snapshot.jpg`

### Option C: Frigate + go2rtc
Expose an RTSP/WebRTC restream via `go2rtc.yaml`:
```yaml
streams:
  sony_camera: ffmpeg:http://<CAMERA_IP>:8088/liveview.mjpeg#video=h264#hardware
```
To wake the dimmed camera LCD only when Frigate detects a person, call `POST http://<CAMERA_IP>:8088/screen/wake` from a Home Assistant `rest_command` automation.

### HAOS Dashboard Graphic (`sony-a6000-telephoto.svg`)
A crisp vector icon of the Sony α6000 with an attached telescopic telephoto zoom lens is included at [`docs/assets/sony-a6000-telephoto.svg`](docs/assets/sony-a6000-telephoto.svg). Copy it to your Home Assistant `/config/www/images/sony-a6000-telephoto.svg` folder and reference it in any Lovelace card (e.g., Picture Entity / Mushroom / custom header card):

```yaml
type: picture-entity
entity: camera.sony_a6000
name: Sony α6000
image: /local/images/sony-a6000-telephoto.svg
camera_view: live
```

---

## HTTP API Reference (`:8088`)

### Canonical REST Endpoints
| Method & Path | Response | Notes |
|---|---|---|
| `GET /liveview.mjpeg` (alias `/stream.mjpeg`) | `200` `multipart/x-mixed-replace; boundary=a6000frame` | Continuous hardware JPEG stream; `503` if camera unavailable |
| `GET /snapshot.jpg` (alias `/snapshot.jpeg`) | `200` `image/jpeg` (`Cache-Control: no-store`) | Latest fresh preview frame (`X-Sony-Deliberate-Capture: false`); never fires shutter |
| `GET /health` | `200` or `503` `application/json` | `200` only while a fresh frame (`< 5000 ms`) is available |
| `GET /status` (alias `/`) | `200` `application/json` | Camera state, preset, frame count, FPS, clients, screen stage, motion, battery, IP, RSSI |
| `POST /screen/wake` | `200` `application/json` | Wakes `SCREENSAVER` / `FULL_DIM` to live preview (`last_wake_reason: "http"`) |
| `POST /screen/screensaver` | `200` `application/json` | Enters Starfield + Oscilloscope screensaver immediately |
| `POST /screen/dim` | `200` `application/json` | Enters zero-draw pure-black `FULL_DIM` immediately |

### Home Assistant `Android IP Webcam` (`pydroid-ipcam`) Compatibility Endpoints
| Method & Path | Response | Notes |
|---|---|---|
| `GET /video` | `200` MJPEG stream | Alias for `/liveview.mjpeg` |
| `GET /shot.jpg` | `200` `image/jpeg` | Alias for `/snapshot.jpg` |
| `GET /status.json` | `200` `application/json` | `curvals` (`video_size`, `motion_detect`, `overlay`) and `avail` |
| `GET /sensors.json` | `200` `application/json` | `battery_level`, `battery_temp`, `battery_voltage`, `motion_active`, `video_connections` |
| `GET /settings/motion_detect?set=on\|off` | `200` `Ok` | Toggles on-camera motion detection |
| `GET /settings/overlay?set=on\|off` | `200` `Ok` | `on` enters screensaver overlay; `off` wakes to live view |

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
  --version-code 3 \
  --version-name 1.1.0 \
  --out /dev/shm/a6000-hub-unsigned.apk
```
Sign with Android 2.3-compatible **v1 JAR signing** (`jarsigner -sigalg SHA1withRSA -digestalg SHA1`), run `zipalign -f 4`, and install via PMCA or `adb install`.

> **Android 2.3.7 Permission Quirk:** Upgrading an already-installed package via `adb install -r` does not grant newly added `<uses-permission>` entries on Gingerbread. Always use a fresh `--package-id` (or uninstall/reinstall cleanly) when adding permissions.

---

## Verification Status

- **Offline verified (11 test suites):** `ConditionTests`, `AutoPowerOffPolicyTests`, `StaticIpPolicyTests`, `DeveloperSettingsPolicyTests`, `BootRecoveryPolicyTests`, `ScreensaverPolicyTests`, `MotionDetectorPolicyTests`, `PreviewFrameBusTests`, `AndroidIpWebcamProtocolTests`, real loopback socket `PreviewHttpServerTests`, and real loopback UDP `SntpClientPolicyTests`.
- **Hardware verified on Sony ILCE-6000 (Android 2.3.7, API V4):**
  - Static IPv4 via `Settings.System` (`WifiStateTracker: Static IP configuration succeeded`, `dhcpcd` stopped).
  - Auto Power Off inhibition (`DAConnectionManagerService-JNI: APO/NO -> call MSS_fakey_stop`).
  - Live `640x424` hardware `CameraSequence` JPEG snapshots (`/snapshot.jpg`) and continuous MJPEG streaming (`/liveview.mjpeg`) uninterrupted during `SCREENSAVER` and `FULL_DIM`, plus `POST /screen/{screensaver,dim,wake}`.
- **Pending physical acceptance:**
  - Cold-boot power-cycle verification confirming Sony firmware delivers `DAConnectionManagerService.BootCompleted` (or `BOOT_COMPLETED`) to third-party packages after power-on.
  - Multi-hour HAOS + Frigate endurance soak with async motion worker enabled.
