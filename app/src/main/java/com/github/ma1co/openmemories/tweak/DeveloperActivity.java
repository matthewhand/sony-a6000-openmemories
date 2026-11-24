package com.github.ma1co.openmemories.tweak;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.method.KeyListener;
import android.text.method.NumberKeyListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.TimeoutException;

public class DeveloperActivity extends ItemActivity {
    /** Numeric+dot keypad for IPv4 fields without Gingerbread's single-dot decimal restriction. */
    private static final KeyListener IPV4_KEY_LISTENER = new NumberKeyListener() {
        private final char[] ACCEPTED = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.' };
        @Override protected char[] getAcceptedChars() { return ACCEPTED; }
        @Override public int getInputType() {
            return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
    };

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private BroadcastReceiver receiver;
    private BaseItem wifiSwitch;
    private BaseItem staticIpSwitch;
    private BaseItem persistSwitch;
    private AndroidStaticIpStore staticIpStore;
    private AndroidDeveloperSettingsStore devStore;
    private final AutoPowerOffPolicy apoPolicy = new AutoPowerOffPolicy();
    private final String[] telnetStartCommand = new String[] { "busybox", "telnetd", "-l", "sh" };
    private String[] adbStartCommand;
    private boolean restoredThisLaunch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        staticIpStore = new AndroidStaticIpStore(this);
        devStore = new AndroidDeveloperSettingsStore(this);
        adbStartCommand = new String[] { getApplicationInfo().nativeLibraryDir + "/libadbd.so" };

        addButton("Start Webcam & Stream (:8088)", new ButtonItem.Adapter() {
            @Override public void click() {
                startActivity(new Intent(DeveloperActivity.this, LivePreviewActivity.class));
            }
        });

        wifiSwitch = addSwitch("Enable Wifi", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return isWifiEnabled(); }
            @Override public void setEnabled(boolean enabled) {
                DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_WIFI, enabled);
                setWifiEnabled(enabled);
                syncAutoPowerOff();
                if (persistSwitch != null) persistSwitch.update();
            }

            @Override
            public String getSummary() {
                switch (wifiManager.getWifiState()) {
                    case WifiManager.WIFI_STATE_ENABLED:
                        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        if (networkInfo != null && networkInfo.isConnected() && wifiInfo != null) {
                            return String.format("Connected to %s (IP: %s)", wifiInfo.getSSID(), Formatter.formatIpAddress(wifiInfo.getIpAddress()));
                        } else if (wifiInfo != null) {
                            NetworkInfo.DetailedState state = WifiInfo.getDetailedStateOf(wifiInfo.getSupplicantState());
                            switch (state) {
                                case SCANNING:
                                    return "Scanning...";
                                case AUTHENTICATING:
                                case CONNECTING:
                                case OBTAINING_IPADDR:
                                    return "Connecting...";
                                default:
                                    return "Wifi enabled";
                            }
                        }
                        return "Wifi enabled";
                    case WifiManager.WIFI_STATE_ENABLING:
                        return "Enabling...";
                    default:
                        return "Wifi disabled";
                }
            }
        });

        staticIpSwitch = addSwitch("Static IPv4 (Settings.System)", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return StaticIpPolicy.isStaticEnabled(staticIpStore); }
            @Override public void setEnabled(boolean enabled) throws Exception {
                if (enabled) {
                    StaticIpPolicy.Config cfg = StaticIpPolicy.resolveConfig(
                            staticIpStore, AndroidStaticIpStore.readActiveDhcpConfig(wifiManager));
                    if (cfg == null) {
                        throw new IllegalStateException("Connect to Wi-Fi once or use Configure Static IP first");
                    }
                    StaticIpPolicy.Result r = StaticIpPolicy.applyStatic(staticIpStore, cfg);
                    Logger.info("StaticIp", (r.ok ? "OK: " : "FAIL: ") + r.message);
                    if (!r.ok) throw new IllegalStateException(r.message);
                    if (isWifiEnabled()) bounceWifiToApplyStaticIp();
                } else {
                    StaticIpPolicy.Result r = StaticIpPolicy.restoreDhcp(staticIpStore);
                    Logger.info("StaticIp", (r.ok ? "OK: " : "FAIL: ") + r.message);
                    if (!r.ok) throw new IllegalStateException(r.message);
                }
                DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_STATIC_IP, enabled);
                if (persistSwitch != null) persistSwitch.update();
            }
            @Override public String getSummary() { return StaticIpPolicy.summary(staticIpStore); }
        });

        addButton("Configure Static IP...", new ButtonItem.Adapter() {
            @Override public void click() { showStaticIpDialog(); }
        });

        addSwitch("Enable ADB", new SwitchItem.Adapter() {
            private int getAdbPid() { return Procfs.findProcess(adbStartCommand); }
            private void enableAdb() throws NativeException { Shell.execAndroid(TextUtils.join(" ", adbStartCommand) + " &"); }
            private void disableAdb() throws NativeException {
                int pid = getAdbPid();
                if (pid != -1) Shell.exec("kill -HUP " + pid);
            }
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return getAdbPid() != -1; }
            @Override
            public void setEnabled(final boolean enabled) throws InterruptedException, NativeException, TimeoutException {
                try {
                    Logger.info("AdbAdapter.setEnabled", "setting adbd to " + enabled);
                    if (enabled) enableAdb(); else disableAdb();
                    Condition.waitFor(new Condition.Runnable() {
                        @Override public boolean run() { return isEnabled() == enabled; }
                    }, 500, 2000);
                    DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_ADB, enabled);
                    if (enabled) {
                        DeveloperSettingsPolicy.recordTrustedSsidIfAvailable(devStore, getConnectedSsid());
                    }
                    syncAutoPowerOff();
                    if (persistSwitch != null) persistSwitch.update();
                    Logger.info("AdbAdapter.setEnabled", "done");
                } catch (InterruptedException | NativeException | TimeoutException e) {
                    Logger.error("AdbAdapter.setEnabled", e);
                    throw e;
                }
            }
            @Override public String getSummary() {
                return isEnabled() ? "adbd running on port 5555" : "adbd stopped";
            }
        });

        addSwitch("Enable Telnet", new SwitchItem.Adapter() {
            private int getTelnetPid() { return Procfs.findProcess(telnetStartCommand); }
            private void enableTelnet() throws NativeException { Shell.exec(TextUtils.join(" ", telnetStartCommand)); }
            private void disableTelnet() throws NativeException {
                int pid = getTelnetPid();
                if (pid != -1) Shell.exec("kill -HUP " + pid + " $(ps -o pid= --ppid " + pid + ")");
            }
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return getTelnetPid() != -1; }
            @Override
            public void setEnabled(final boolean enabled) throws InterruptedException, NativeException, TimeoutException {
                try {
                    Logger.info("TelnetAdapter.setEnabled", "setting telnetd to " + enabled);
                    if (enabled) enableTelnet(); else disableTelnet();
                    Condition.waitFor(new Condition.Runnable() {
                        @Override public boolean run() { return isEnabled() == enabled; }
                    }, 500, 2000);
                    DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_TELNET, enabled);
                    if (enabled) {
                        DeveloperSettingsPolicy.recordTrustedSsidIfAvailable(devStore, getConnectedSsid());
                    }
                    syncAutoPowerOff();
                    if (persistSwitch != null) persistSwitch.update();
                    Logger.info("TelnetAdapter.setEnabled", "done");
                } catch (InterruptedException | NativeException | TimeoutException e) {
                    Logger.error("TelnetAdapter.setEnabled", e);
                    throw e;
                }
            }
            @Override public String getSummary() {
                return isEnabled() ? "telnetd running on port 23" : "telnetd stopped";
            }
        });

        addButton("Screensaver timing (sec, 0=off)...", new ButtonItem.Adapter() {
            @Override public void click() { showScreensaverTimingDialog(); }
        });

        addButton("Screensaver style...", new ButtonItem.Adapter() {
            @Override public void click() { showScreensaverStyleDialog(); }
        });

        addButton("C1 custom button action...", new ButtonItem.Adapter() {
            @Override public void click() { showC1ButtonDialog(); }
        });

        addSwitch("Screensaver debug HUD", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return devStore.getBoolean(ScreensaverPolicy.KEY_HUD_ENABLED, true); }
            @Override public void setEnabled(boolean enabled) {
                devStore.putBoolean(ScreensaverPolicy.KEY_HUD_ENABLED, enabled);
            }
            @Override public String getSummary() {
                android.content.SharedPreferences p = getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, MODE_PRIVATE);
                int d = p.getInt(ScreensaverPolicy.KEY_DELAY_SEC, ScreensaverPolicy.DEFAULT_DELAY_SEC);
                int dim = p.getInt(ScreensaverPolicy.KEY_DIM_SEC, ScreensaverPolicy.DEFAULT_DIM_SEC);
                ScreensaverPolicy.Style st = ScreensaverPolicy.parseStyle(
                        p.getString(ScreensaverPolicy.KEY_STYLE, ScreensaverPolicy.Style.RANDOM.id));
                return (isEnabled() ? "HUD ON" : "HUD OFF") + " · " + st.label + " · "
                        + ScreensaverPolicy.formatTimingSummary(d, dim);
            }
        });

        addSwitch("Motion wakes dimmed screen", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return devStore.getBoolean(LivePreviewActivity.KEY_MOTION_WAKE, true); }
            @Override public void setEnabled(boolean enabled) {
                devStore.putBoolean(LivePreviewActivity.KEY_MOTION_WAKE, enabled);
            }
            @Override public String getSummary() {
                return isEnabled()
                        ? "Shows grabbed motion still (~4.5s) then live preview"
                        : "Manual key/touch or HTTP wake only";
            }
        });

        addSwitch("Save motion stills to SD", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return MotionGalleryStore.isSaveEnabled(DeveloperActivity.this); }
            @Override public void setEnabled(boolean enabled) {
                MotionGalleryStore.setSaveEnabled(DeveloperActivity.this, enabled);
            }
            @Override public String getSummary() {
                MotionStoragePolicy.StorageStats st = MotionGalleryStore.inspectStats(DeveloperActivity.this);
                return (isEnabled() ? "ON · " : "OFF · ") + MotionStoragePolicy.formatSummary(st);
            }
        });

        addButton("Motion storage lifecycle...", new ButtonItem.Adapter() {
            @Override public void click() { showMotionStorageDialog(); }
        });

        addSwitch("Turn off LCD backlight in Dim", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return devStore.getBoolean(LivePreviewActivity.KEY_DIM_BACKLIGHT_OFF, true); }
            @Override public void setEnabled(boolean enabled) {
                devStore.putBoolean(LivePreviewActivity.KEY_DIM_BACKLIGHT_OFF, enabled);
            }
            @Override public String getSummary() {
                return isEnabled()
                        ? "Uses Sony DisplayManager (DEVICE_ID_NONE); restores on wake"
                        : "Black canvas only (backlight stays on)";
            }
        });

        addSwitch("Auto-start Webcam on open/boot", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return devStore.getBoolean(LivePreviewActivity.KEY_BOOT_WEBCAM, true); }
            @Override public void setEnabled(boolean enabled) {
                devStore.putBoolean(LivePreviewActivity.KEY_BOOT_WEBCAM, enabled);
            }
            @Override public String getSummary() {
                return isEnabled()
                        ? "Launching app enters Webcam (:8088); MENU returns here"
                        : "Launching app opens main menu";
            }
        });

        addButton("Launch Screensaver Now", new ButtonItem.Adapter() {
            @Override public void click() {
                startActivity(new Intent(DeveloperActivity.this, ScreensaverActivity.class));
            }
        });

        addSwitch("Auto NTP time sync", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return devStore.getBoolean(NtpSyncHelper.KEY_AUTO_NTP, true); }
            @Override public void setEnabled(boolean enabled) {
                devStore.putBoolean(NtpSyncHelper.KEY_AUTO_NTP, enabled);
                if (enabled && isWifiConnected()) NtpSyncHelper.syncAsyncIfEnabled(DeveloperActivity.this, true);
            }
            @Override public String getSummary() {
                if (!isEnabled()) return "Automatic NTP clock sync disabled";
                long now = System.currentTimeMillis();
                if (now >= SntpClientPolicy.MIN_VALID_EPOCH_MS) {
                    java.util.Calendar cal = new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"));
                    cal.setTimeInMillis(now);
                    return "Clock: " + SntpClientPolicy.formatUtcIso(cal) + "Z";
                }
                return "Waiting for Wi-Fi to sync clock via NTP";
            }
        });

        addButton("Sync Time Now (NTP)", new ButtonItem.Adapter() {
            @Override public void click() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        final NtpSyncHelper.SyncResult r = NtpSyncHelper.syncNow(DeveloperActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                update();
                                new AlertDialog.Builder(DeveloperActivity.this)
                                        .setTitle(r.ok ? "NTP Time Synced" : "NTP Sync Failed")
                                        .setMessage(r.summary)
                                        .setPositiveButton("OK", null).show();
                            }
                        });
                    }
                }, "om-ntp-manual").start();
            }
        });

        addSwitch("Keep camera awake (APO/NO)", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return isKeepAwakePrefEnabled(); }
            @Override public void setEnabled(boolean enabled) {
                devStore.putBoolean(DeveloperSettingsPolicy.KEY_WANT_KEEP_AWAKE, enabled);
                apoPolicy.reset();
                syncAutoPowerOff();
                if (persistSwitch != null) persistSwitch.update();
            }
            @Override public String getSummary() {
                if (!isKeepAwakePrefEnabled()) return "Normal Sony auto power off";
                return AutoPowerOffPolicy.MODE_DISABLE.equals(apoPolicy.lastSentMode())
                        ? "Auto power off inhibited (APO/NO)"
                        : "Armed (inhibits APO while Wi-Fi/daemons active)";
            }
        });

        persistSwitch = addSwitch("Persist Developer settings", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return DeveloperSettingsPolicy.read(devStore).persistEnabled; }
            @Override public void setEnabled(boolean enabled) {
                DeveloperSettingsPolicy.recordBooleanIfPersisting(
                        devStore, DeveloperSettingsPolicy.KEY_PERSIST_ENABLED, enabled);
                if (enabled) {
                    snapshotCurrentStateToPrefs();
                }
            }
            @Override public String getSummary() {
                return DeveloperSettingsPolicy.summary(DeveloperSettingsPolicy.read(devStore));
            }
        });

        addSwitch("Auto boot recovery", new SwitchItem.Adapter() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isEnabled() { return DeveloperSettingsPolicy.read(devStore).bootAutorun; }
            @Override public void setEnabled(boolean enabled) {
                devStore.putBoolean(DeveloperSettingsPolicy.KEY_BOOT_AUTORUN, enabled);
                BootRecoveryService.resetCrashGuard(DeveloperActivity.this);
            }
            @Override public String getSummary() {
                return isEnabled()
                        ? "Reconciles persisted settings on boot (3-crash guard)"
                        : "Boot autorun disabled";
            }
        });

        addButton("Wifi settings", new ButtonItem.Adapter() {
            @Override public void click() { onSettingsButtonClicked(); }
        });

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reconcilePersistedSettings(false);
                if (isWifiConnected()) NtpSyncHelper.syncAsyncIfEnabled(DeveloperActivity.this, false);
                update();
                syncAutoPowerOff();
            }
        };
    }

    private void snapshotCurrentStateToPrefs() {
        DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_WIFI, isWifiEnabled());
        DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_STATIC_IP, StaticIpPolicy.isStaticEnabled(staticIpStore));
        DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_ADB, Procfs.findProcess(adbStartCommand) != -1);
        DeveloperSettingsPolicy.recordBooleanIfPersisting(devStore, DeveloperSettingsPolicy.KEY_WANT_TELNET, Procfs.findProcess(telnetStartCommand) != -1);
        DeveloperSettingsPolicy.recordTrustedSsidIfAvailable(devStore, getConnectedSsid());
    }

    private void reconcilePersistedSettings(boolean allowRadioToggle) {
        DeveloperSettingsPolicy.Snapshot desired = DeveloperSettingsPolicy.read(devStore);
        DeveloperSettingsPolicy.RuntimeState state = new DeveloperSettingsPolicy.RuntimeState(
                isWifiEnabled(),
                isWifiConnected(),
                getConnectedSsid(),
                StaticIpPolicy.isStaticEnabled(staticIpStore),
                Procfs.findProcess(adbStartCommand) != -1,
                Procfs.findProcess(telnetStartCommand) != -1);
        DeveloperSettingsPolicy.ReconcilePlan plan = DeveloperSettingsPolicy.planRestore(desired, state);
        if (plan.isEmpty()) return;

        if (allowRadioToggle && plan.applyStaticIp) {
            StaticIpPolicy.Config saved = StaticIpPolicy.readSavedConfig(staticIpStore);
            if (saved != null) StaticIpPolicy.applyStatic(staticIpStore, saved);
        }
        if (allowRadioToggle && plan.enableWifi) {
            setWifiEnabled(true);
        }
        if (plan.startAdb) {
            try { Shell.execAndroid(TextUtils.join(" ", adbStartCommand) + " &"); }
            catch (NativeException e) { Logger.error("DeveloperActivity.restoreAdb", e); }
        }
        if (plan.startTelnet) {
            try { Shell.exec(TextUtils.join(" ", telnetStartCommand)); }
            catch (NativeException e) { Logger.error("DeveloperActivity.restoreTelnet", e); }
        }
    }

    private boolean isWifiConnected() {
        NetworkInfo info = connectivityManager == null ? null : connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return info != null && info.isConnected();
    }

    private String getConnectedSsid() {
        if (!isWifiConnected() || wifiManager == null) return "";
        WifiInfo info = wifiManager.getConnectionInfo();
        return info == null ? "" : DeveloperSettingsPolicy.normalizeSsid(info.getSSID());
    }

    private void showStaticIpDialog() {
        StaticIpPolicy.Config seed = StaticIpPolicy.resolveConfig(
                staticIpStore, AndroidStaticIpStore.readActiveDhcpConfig(wifiManager));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 8, 16, 8);

        final EditText ipIn = addDialogField(box, "IPv4 Address", seed == null ? "" : seed.ip);
        final EditText maskIn = addDialogField(box, "Netmask", seed == null ? StaticIpPolicy.DEFAULT_FALLBACK_NETMASK : seed.netmask);
        final EditText gwIn = addDialogField(box, "Gateway", seed == null ? "" : seed.gateway);
        final EditText dns1In = addDialogField(box, "DNS 1", seed == null ? "" : seed.dns1);
        final EditText dns2In = addDialogField(box, "DNS 2", seed == null ? StaticIpPolicy.DEFAULT_FALLBACK_DNS2 : seed.dns2);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Static IPv4 Configuration")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        StaticIpPolicy.Config cfg = new StaticIpPolicy.Config(
                                ipIn.getText().toString().trim(),
                                maskIn.getText().toString().trim(),
                                gwIn.getText().toString().trim(),
                                dns1In.getText().toString().trim(),
                                dns2In.getText().toString().trim());
                        StaticIpPolicy.Result r = StaticIpPolicy.applyStatic(staticIpStore, cfg);
                        Logger.info("StaticIp", (r.ok ? "OK: " : "FAIL: ") + r.message);
                        if (r.ok) {
                            DeveloperSettingsPolicy.recordBooleanIfPersisting(
                                    devStore, DeveloperSettingsPolicy.KEY_WANT_STATIC_IP, true);
                            if (isWifiEnabled()) {
                                bounceWifiToApplyStaticIp();
                            }
                        }
                        update();
                        if (!r.ok) {
                            new AlertDialog.Builder(DeveloperActivity.this)
                                    .setTitle("Static IP Error")
                                    .setMessage(r.message)
                                    .setPositiveButton("OK", null).show();
                        }
                    }
                }).show();
    }

    private void showScreensaverTimingDialog() {
        final android.content.SharedPreferences prefs = getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, MODE_PRIVATE);
        final int curDelay = prefs.getInt(ScreensaverPolicy.KEY_DELAY_SEC, ScreensaverPolicy.DEFAULT_DELAY_SEC);
        final int curDim = prefs.getInt(ScreensaverPolicy.KEY_DIM_SEC, ScreensaverPolicy.DEFAULT_DIM_SEC);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 8, 16, 8);
        final EditText delayIn = addNumericField(box, "Seconds until screensaver (0 = off)", String.valueOf(curDelay));
        final EditText dimIn = addNumericField(box, "Seconds in screensaver until LCD dim (0 = never)", String.valueOf(curDim));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Screensaver Timing")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        int d = curDelay;
                        int dm = curDim;
                        try { d = ScreensaverPolicy.clampDelaySec(Integer.parseInt(delayIn.getText().toString().trim())); }
                        catch (NumberFormatException ignored) {}
                        try { dm = ScreensaverPolicy.clampDimSec(Integer.parseInt(dimIn.getText().toString().trim())); }
                        catch (NumberFormatException ignored) {}
                        prefs.edit()
                                .putInt(ScreensaverPolicy.KEY_DELAY_SEC, d)
                                .putInt(ScreensaverPolicy.KEY_DIM_SEC, dm)
                                .commit();
                        update();
                    }
                }).show();
    }

    private void showScreensaverStyleDialog() {
        final android.content.SharedPreferences prefs = getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, MODE_PRIVATE);
        ScreensaverPolicy.Style cur = ScreensaverPolicy.parseStyle(
                prefs.getString(ScreensaverPolicy.KEY_STYLE, ScreensaverPolicy.Style.RANDOM.id));
        new AlertDialog.Builder(this)
                .setTitle("Screensaver Style")
                .setSingleChoiceItems(ScreensaverPolicy.pickerLabels(), ScreensaverPolicy.pickerIndex(cur),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                ScreensaverPolicy.Style picked = ScreensaverPolicy.styleForPickerIndex(which);
                                if (picked == null) return; // Divider tapped; keep dialog open
                                prefs.edit().putString(ScreensaverPolicy.KEY_STYLE, picked.id).commit();
                                dialog.dismiss();
                                update();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showC1ButtonDialog() {
        final android.content.SharedPreferences prefs = getSharedPreferences(DeveloperSettingsPolicy.PREFS_NAME, MODE_PRIVATE);
        CustomButtonPolicy.Action cur = CustomButtonPolicy.parseAction(
                prefs.getString(CustomButtonPolicy.KEY_C1_ACTION, CustomButtonPolicy.Action.TOGGLE_SAVER_DIM.id));
        final CustomButtonPolicy.Action[] actions = CustomButtonPolicy.Action.values();
        new AlertDialog.Builder(this)
                .setTitle("C1 Custom Button Action")
                .setSingleChoiceItems(CustomButtonPolicy.labels(), CustomButtonPolicy.indexOf(cur),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                if (which >= 0 && which < actions.length) {
                                    prefs.edit().putString(CustomButtonPolicy.KEY_C1_ACTION, actions[which].id).commit();
                                }
                                dialog.dismiss();
                                update();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showMotionStorageDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 8, 16, 8);

        MotionStoragePolicy.StorageStats st = MotionGalleryStore.inspectStats(this);
        TextView info = new TextView(this);
        info.setText(MotionStoragePolicy.formatSummary(st) + "\nFolder: " + MotionStoragePolicy.DIR_PATH);
        box.addView(info);

        final EditText maxMbIn = addNumericField(box, "Max folder size (MB, 10-4096)",
                String.valueOf(MotionGalleryStore.getMaxMb(this)));
        final EditText retHrsIn = addNumericField(box, "Retention age (hours, 1-2160)",
                String.valueOf(MotionGalleryStore.getRetentionHours(this)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Motion Storage Lifecycle")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear Stills", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        MotionGalleryStore.clearAllStills();
                        update();
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            MotionGalleryStore.setMaxMb(DeveloperActivity.this,
                                    Integer.parseInt(maxMbIn.getText().toString().trim()));
                        } catch (NumberFormatException ignored) {}
                        try {
                            MotionGalleryStore.setRetentionHours(DeveloperActivity.this,
                                    Integer.parseInt(retHrsIn.getText().toString().trim()));
                        } catch (NumberFormatException ignored) {}
                        update();
                    }
                }).show();
    }

    private EditText addNumericField(LinearLayout parent, String label, String initial) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(initial == null ? "" : initial);
        parent.addView(et);
        return et;
    }

    private EditText addDialogField(LinearLayout parent, String label, String initial) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setKeyListener(IPV4_KEY_LISTENER);
        et.setText(initial == null ? "" : initial);
        parent.addView(et);
        return et;
    }

    private boolean isKeepAwakePrefEnabled() {
        return DeveloperSettingsPolicy.read(devStore).wantKeepAwake;
    }

    private boolean isAnyNetworkOrDaemonActive() {
        return isWifiEnabled()
                || Procfs.findProcess(telnetStartCommand) != -1
                || Procfs.findProcess(adbStartCommand) != -1;
    }

    private void syncAutoPowerOff() {
        String mode = apoPolicy.nextBroadcastMode(isKeepAwakePrefEnabled(), isAnyNetworkOrDaemonActive());
        if (mode != null) {
            SonyPowerBridge.applyAutoPowerOffMode(this, mode);
        }
    }

    private DeveloperSettingsPolicy.RuntimeState currentRuntimeState() {
        return new DeveloperSettingsPolicy.RuntimeState(
                isWifiEnabled(),
                isWifiConnected(),
                getConnectedSsid(),
                StaticIpPolicy.isStaticEnabled(staticIpStore),
                Procfs.findProcess(adbStartCommand) != -1,
                Procfs.findProcess(telnetStartCommand) != -1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SonyPowerBridge.notifyAppInfo(this, getComponentName());
        DeveloperSettingsPolicy.bootstrapSnapshotIfEmpty(devStore, currentRuntimeState());
        if (!restoredThisLaunch) {
            restoredThisLaunch = true;
            reconcilePersistedSettings(true);
        }
        apoPolicy.reset();
        syncAutoPowerOff();
        update();

        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(receiver, f);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    private void bounceWifiToApplyStaticIp() {
        setWifiEnabled(false);
        new android.os.Handler().postDelayed(new Runnable() {
            @Override public void run() { setWifiEnabled(true); }
        }, 1800L);
    }

    public boolean isWifiEnabled() {
        int state = wifiManager.getWifiState();
        return state == WifiManager.WIFI_STATE_ENABLING || state == WifiManager.WIFI_STATE_ENABLED;
    }

    public void setWifiEnabled(boolean enabled) {
        Logger.info("setWifiEnabled", "setting wifi to " + enabled);
        wifiManager.setWifiEnabled(enabled);
    }

    public void onSettingsButtonClicked() {
        Logger.info("onSettingsButtonClicked", "starting wifi settings activity");
        boolean wifiEnabled = isWifiEnabled();
        setWifiEnabled(true);
        startActivityForResult(new Intent("com.sony.scalar.app.wifisettings.WifiSettings"), wifiEnabled ? 1 : 0);
    }

    @Override
    protected void onActivityResult(int wifiEnabled, int result, Intent intent) {
        Logger.info("onActivityResult", "back from wifi settings activity");
        super.onActivityResult(wifiEnabled, result, intent);
        setWifiEnabled(wifiEnabled == 1);
    }
}
