package com.hick.carprobe;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputType;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.UUID;

/** Opt-in cloud companion to the read-only Android 4.2 diagnostic screen. */
public final class WhitelistProbeActivity extends ProbeActivity {
    private static final String PREFS = "cloud_pairing";
    private static final String DEFAULT_ENDPOINT =
            "https://couples-cuts-matters-calculators.trycloudflare.com";

    private EditText endpointInput;
    private EditText pairingCodeInput;
    private TextView statusText;
    private Button pairButton;
    private Button uploadButton;
    private Button logButton;
    private CheckBox autoUploadCheckbox;
    private boolean networkBusy;

    @Override
    protected String getScreenTitle() {
        return "车机深度诊断 v11（Root / 安装分析）";
    }

    @Override
    protected boolean shareButtonInReportSection() { return true; }

    @Override
    protected String getPrivacyNotice() {
        return "常规诊断只读。Root / Wi-Fi ADB 操作必须确认；安装限制取证手动导出/上传。配对后可上传硬件报告和应用日志，后台监测默认关闭。";
    }

    @Override
    protected void appendPrimaryControls(LinearLayout layout) {
        TextView hint = new TextView(this);
        hint.setText("配置车机 Wi-Fi 代理：请先打开系统设置，再进入 Wi-Fi > 长按当前网络 > 修改网络 > 显示高级选项。部分原厂固件可能隐藏该入口。");
        hint.setTextSize(12);
        hint.setPadding(0, 3, 0, 0);
        layout.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(buttons);
        addReliableSettingsButton(buttons, "系统设置 / Wi-Fi 代理", false);
        addReliableSettingsButton(buttons, "直接打开 Wi-Fi", true);

        LinearLayout researchButtons = new LinearLayout(this);
        researchButtons.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(researchButtons);
        Button rootButton = new Button(this);
        rootButton.setText("Root / Wi-Fi ADB");
        rootButton.setTextSize(14);
        rootButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openRootResearch(); }
        });
        researchButtons.addView(rootButton, new LinearLayout.LayoutParams(0, -2, 1));
        Button dumpButton = new Button(this);
        dumpButton.setText("安装限制 Dump");
        dumpButton.setTextSize(14);
        dumpButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(WhitelistProbeActivity.this, AppDumpActivity.class));
                } catch (Exception error) {
                    DiagnosticLog.append(WhitelistProbeActivity.this, "APP_DUMP_LAUNCH_ERROR",
                            readableError(error));
                    Toast.makeText(WhitelistProbeActivity.this,
                            "Dump 页面无法打开：" + readableError(error), Toast.LENGTH_LONG).show();
                }
            }
        });
        researchButtons.addView(dumpButton, new LinearLayout.LayoutParams(0, -2, 1));

        Button shortcutButton = new Button(this);
        shortcutButton.setText("添加桌面图标（原厂桌面可能不支持）");
        shortcutButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestDesktopShortcut(); }
        });
        layout.addView(shortcutButton);
    }

    private void requestDesktopShortcut() {
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClass(this, WhitelistProbeActivity.class);
            int iconId = getResources().getIdentifier("ic_carplay", "drawable", getPackageName());
            if (iconId == 0) {
                throw new IllegalStateException("诊断应用图标资源不存在");
            }
            Intent shortcut = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME, "车机深度诊断");
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, iconId));
            shortcut.putExtra("duplicate", false);
            sendBroadcast(shortcut);
            DiagnosticLog.append(this, "SHORTCUT_REQUEST",
                    "已向桌面发送手动创建快捷方式请求");
            Toast.makeText(this, "已请求创建快捷方式；如果未出现，原厂桌面可能不支持",
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            DiagnosticLog.append(this, "SHORTCUT_ERROR", readableError(error));
            Toast.makeText(this, "创建快捷方式失败：" + readableError(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addReliableSettingsButton(LinearLayout layout, String label, final boolean wifiFirst) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent[] options;
                Intent oemSettings = new Intent();
                oemSettings.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings"));
                if (wifiFirst) {
                    options = new Intent[] {
                        new Intent(Settings.ACTION_WIFI_SETTINGS),
                        new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                        new Intent(Settings.ACTION_SETTINGS), oemSettings
                    };
                } else {
                    options = new Intent[] {
                        new Intent(Settings.ACTION_SETTINGS), oemSettings,
                        new Intent(Settings.ACTION_WIFI_SETTINGS),
                        new Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    };
                }
                for (Intent intent : options) {
                    try {
                        startActivity(intent);
                        String target = intent.getAction() == null
                                ? "com.android.settings/.Settings" : intent.getAction();
                        DiagnosticLog.append(WhitelistProbeActivity.this, "SETTINGS_OPEN", target);
                        return;
                    } catch (Exception error) {
                        DiagnosticLog.append(WhitelistProbeActivity.this, "SETTINGS_FALLBACK",
                                readableError(error));
                    }
                }
                String message = "原厂系统拒绝打开设置；已记录各备用入口的结果";
                DiagnosticLog.append(WhitelistProbeActivity.this, "SETTINGS_ERROR", message);
                Toast.makeText(WhitelistProbeActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    @Override
    protected void appendAdditionalControls(LinearLayout section) {
        TextView heading = new TextView(this);
        heading.setText("深度诊断 v11 / 安全日志上报");
        heading.setTextSize(18);
        heading.setPadding(0, 12, 0, 5);
        section.addView(heading);

        TextView explanation = new TextView(this);
        explanation.setText("包名 title.navi（原厂安装器白名单）。检查 CPU、内存、存储、视频解码、Wi-Fi Direct、ADB 和 root 线索。不能直接提权；不采集账号或设备唯一标识。");
        section.addView(explanation);

        addSettingsShortcut(section, "打开 Android 开发者选项（不自动开启 ADB）",
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        // General and Wi-Fi settings are always visible above the report.

        Button storeInstallerButton = new Button(this);
        storeInstallerButton.setText("④ 原厂商店缓存 / 第三方 APK 安装验证");
        storeInstallerButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(WhitelistProbeActivity.this, StoreCacheInstallerActivity.class));
                } catch (Exception error) {
                    DiagnosticLog.append(WhitelistProbeActivity.this, "STORE_INSTALL_LAUNCH_ERROR", readableError(error));
                    statusText.setText("缓存检测页面无法打开：" + readableError(error));
                }
            }
        });
        section.addView(storeInstallerButton);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setHint("接收服务 HTTPS 地址");
        endpointInput.setText(prefs.getString("endpoint", DEFAULT_ENDPOINT));
        section.addView(endpointInput);

        pairingCodeInput = new EditText(this);
        pairingCodeInput.setSingleLine(true);
        pairingCodeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pairingCodeInput.setHint("请输入 10 位配对码");
        section.addView(pairingCodeInput);

        pairButton = new Button(this);
        pairButton.setText("① 配对云端接收器");
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pair(); }
        });
        section.addView(pairButton);

        uploadButton = new Button(this);
        uploadButton.setText("② 上传完整硬件/权限报告（手动）");
        uploadButton.setEnabled(prefs.getString("token", "").length() > 0);
        uploadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { uploadReport(); }
        });
        section.addView(uploadButton);

        logButton = new Button(this);
        logButton.setText("③ 上传本诊断应用的运行/错误日志");
        logButton.setEnabled(uploadButton.isEnabled());
        logButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { uploadLogs(false); }
        });
        section.addView(logButton);

        autoUploadCheckbox = new CheckBox(this);
        autoUploadCheckbox.setText("开启后台监测：每 5 分钟上传硬件状态及应用日志");
        autoUploadCheckbox.setChecked(prefs.getBoolean("auto_upload_logs", false));
        autoUploadCheckbox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean enabled = autoUploadCheckbox.isChecked();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("auto_upload_logs", enabled).apply();
                DiagnosticLog.append(WhitelistProbeActivity.this, "SETTING",
                        "用户" + (enabled ? "开启" : "关闭") + "后台监测");
                Intent service = new Intent(WhitelistProbeActivity.this, ProbeMonitorService.class);
                service.setAction(enabled ? ProbeMonitorService.ACTION_START : ProbeMonitorService.ACTION_STOP);
                try {
                    startService(service);
                    statusText.setText(enabled ? "后台监测已启动，切换到其他应用后仍会运行。" : "后台监测已停止。");
                } catch (Exception error) {
                    autoUploadCheckbox.setChecked(false);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("auto_upload_logs", false).apply();
                    statusText.setText("后台服务无法启动：" + readableError(error));
                }
            }
        });
        section.addView(autoUploadCheckbox);

        TextView limits = new TextView(this);
        limits.setText("仅记录本诊断 APK 自身的事件与崩溃，不自动抓取其他应用日志；Android 4.2 未授权时无法读取完整系统 logcat。后台运行会显示通知，点击通知上的‘停止监测’可随时关闭。服务器保存报告，不向公网提供读取接口。");
        section.addView(limits);

        statusText = new TextView(this);
        statusText.setPadding(0, 8, 0, 18);
        statusText.setText(uploadButton.isEnabled() ? "已配对，可手动上传。" : "尚未配对；目前没有发送数据。");
        section.addView(statusText);
    }

    private void openRootResearch() {
        try {
            startActivity(new Intent(this, RootResearchActivity.class));
        } catch (Exception error) {
            DiagnosticLog.append(this, "ROOT_RESEARCH_LAUNCH_ERROR", readableError(error));
            Toast.makeText(this, "Root/ADB 研究页面无法打开：" + readableError(error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String endpoint() {
        return endpointInput.getText().toString().trim();
    }

    private void busy(final boolean working, final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                networkBusy = working;
                pairButton.setEnabled(!working);
                uploadButton.setEnabled(!working && getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("token", "").length() > 0);
                logButton.setEnabled(uploadButton.isEnabled());
                statusText.setText(message);
            }
        });
    }

    private void pair() {
        final String code = pairingCodeInput.getText().toString().trim();
        final String server = endpoint();
        if (code.length() != 10) {
            statusText.setText("请从接收服务器获取 10 位配对码。");
            return;
        }
        busy(true, "正在安全配对……");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("code", code);
                    JSONObject response = CloudReporter.post(server, "/api/pair", payload, null);
                    String token = response.optString("token", "");
                    if (token.length() < 20 || token.length() > 128) {
                        throw new IllegalStateException("接收端未返回有效凭据");
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("token", token).putString("endpoint", server).apply();
                    DiagnosticLog.append(WhitelistProbeActivity.this, "PAIR", "接收端安全配对成功");
                    busy(false, "配对成功。检查下方报告后，可点‘上传当前报告’。");
                } catch (Exception e) {
                    DiagnosticLog.append(WhitelistProbeActivity.this, "PAIR_ERROR", readableError(e));
                    busy(false, "配对失败：" + readableError(e));
                }
            }
        }, "carprobe-pair").start();
    }

    private void uploadReport() {
        final String server = endpoint();
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String token = prefs.getString("token", "");
        if (token.length() == 0) {
            statusText.setText("请先配对，再上传报告。");
            return;
        }
        busy(true, "正在上传硬件和权限报告……");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("device_id", deviceId(prefs));
                    payload.put("app_version", "title.navi diagnostic v11 hardware");
                    payload.put("report", getReportText() + "\n=== 上报时的本应用日志 ===\n"
                            + DiagnosticLog.read(WhitelistProbeActivity.this));
                    JSONObject response = CloudReporter.post(server, "/api/report", payload, token);
                    DiagnosticLog.append(WhitelistProbeActivity.this, "UPLOAD", "硬件报告上报成功");
                    busy(false, "上传成功，报告编号：" + response.optString("id", "(未知)"));
                } catch (Exception e) {
                    DiagnosticLog.append(WhitelistProbeActivity.this, "UPLOAD_ERROR", readableError(e));
                    busy(false, "上传失败：" + readableError(e));
                }
            }
        }, "carprobe-upload").start();
    }

    private String deviceId(SharedPreferences prefs) {
        String id = prefs.getString("device_id", "");
        if (id.length() == 0) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private void uploadLogs(final boolean automatic) {
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String token = prefs.getString("token", "");
        if (token.length() == 0) {
            statusText.setText("先配对才能发送日志。");
            return;
        }
        if (networkBusy) return;
        final String snapshot = DiagnosticLog.read(this);
        final String server = endpoint();
        busy(true, automatic ? "正在定时上传新增应用日志……" : "正在上传应用日志……");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("device_id", deviceId(prefs));
                    payload.put("app_version", "title.navi diagnostic v11 app-log");
                    payload.put("report", "=== 本诊断应用运行日志 ===\n" + snapshot);
                    JSONObject response = CloudReporter.post(server, "/api/report", payload, token);
                    prefs.edit().putString("last_uploaded_log", snapshot)
                            .putString("endpoint", server).apply();
                    busy(false, (automatic ? "自动日志上报成功：" : "日志上报成功：")
                            + response.optString("id", "(未知)"));
                } catch (Exception e) {
                    DiagnosticLog.append(WhitelistProbeActivity.this, "LOG_UPLOAD_ERROR", readableError(e));
                    busy(false, "日志上报失败：" + readableError(e));
                }
            }
        }, "carprobe-log-upload").start();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_upload_logs", false);
        if (autoUploadCheckbox != null) autoUploadCheckbox.setChecked(enabled);
        if (enabled) {
            Intent service = new Intent(this, ProbeMonitorService.class)
                    .setAction(ProbeMonitorService.ACTION_START);
            try { startService(service); } catch (Exception ignored) { }
        }
    }

    private static String readableError(Exception e) {
        String reason = e.getMessage();
        if (reason == null || reason.length() == 0) reason = e.getClass().getSimpleName();
        return reason.length() > 150 ? reason.substring(0, 150) : reason;
    }

    private void addSettingsShortcut(LinearLayout section, String label, final String action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) {
                try {
                    startActivity(new Intent(action));
                    DiagnosticLog.append(WhitelistProbeActivity.this, "SETTINGS", "用户打开 " + action);
                } catch (Exception error) {
                    DiagnosticLog.append(WhitelistProbeActivity.this, "SETTINGS_ERROR", readableError(error));
                    statusText.setText("此固件无法打开该设置页：" + readableError(error));
                }
            }
        });
        section.addView(button);
    }
}
