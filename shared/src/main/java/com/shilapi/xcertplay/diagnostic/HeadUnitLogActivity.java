package com.shilapi.xcertplay.diagnostic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

/**
 * A separate launcher activity that remains reachable after the main CarPlay
 * controller crashes. The user must explicitly confirm each LAN upload.
 * No ADB, READ_LOGS, storage permission or file:// URI export is required.
 */
public final class HeadUnitLogActivity extends Activity {
    private TextView summary;
    private EditText macIp;
    private final Handler ui = new Handler();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        HeadUnitDiagnostics.install(this, getPackageName());
        setTitle("车机故障日志");

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 12, 16, 12);
        TextView title = new TextView(this);
        title.setText("车机故障日志 · 无需 ADB");
        title.setTextSize(20);
        content.addView(title);
        TextView help = new TextView(this);
        help.setText("DiPlay：点「连接」后闪退，重新打开此日志图标即可取回上次异常。\n" +
            "LAN Cast：如果投屏黑屏，按 Back 返回主页面，再进入日志图标查看。\n" +
            "只记录程序故障与阶段信息，不包含麦克风录音、视频内容、MFi 私钥或完整播放地址。\n" +
            "本测试版默认开启故障自动上报：车机通过 UDP 自动寻找 Mac，不用手填 IP；" +
            "如果自动发现失败，仍可手动复制日志。仅在自己的可信 Wi-Fi 上测试。");
        content.addView(help);

        Button refresh = button(content, "刷新并查看本地日志", new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        Button copy = button(content, "复制日志文字（可粘贴发送）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("headunit-diagnostics",
                        HeadUnitDiagnostics.preview()));
                    summary.setText("日志已复制。\n\n" + HeadUnitDiagnostics.preview());
                }
            }
        });
        CheckBox automatic = new CheckBox(this);
        automatic.setText("故障后自动上报到我的 Mac（仅脱敏诊断）");
        automatic.setChecked(HeadUnitAutoUploader.isEnabled(this));
        automatic.setOnCheckedChangeListener(
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b,
                                                       boolean checked) {
                    HeadUnitAutoUploader.setEnabled(HeadUnitLogActivity.this, checked);
                    refresh();
                }
            });
        content.addView(automatic);
        button(content, "立即上传当前日志（不受自动开关影响）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                summary.setText("正在自动发现 Mac 并主动发送当前日志…");
                HeadUnitAutoUploader.uploadManual(HeadUnitLogActivity.this,
                    "Diagnostic screen manual upload",
                    new HeadUnitAutoUploader.UploadCallback() {
                        @Override public void onFinished(boolean success, String status) {
                            summary.setText(status + "\n\n" + HeadUnitDiagnostics.preview());
                        }
                    });
            }
        });
        button(content, "自动发现 Mac 并测试上报", new View.OnClickListener() {
            @Override public void onClick(View v) {
                HeadUnitDiagnostics.flagIncident("Driver requested diagnostic upload test");
                if (!HeadUnitAutoUploader.isEnabled(HeadUnitLogActivity.this)) {
                    summary.setText("自动上报当前已关闭，请先勾选上方开关。");
                } else {
                    HeadUnitAutoUploader.schedule(HeadUnitLogActivity.this, "user-initiated LAN test");
                    summary.setText("正在通过局域网自动发现 Mac 并发送故障日志。\n" +
                        "不需要填写 IP；几秒后点击「刷新并查看本地日志」检查结果。");
                }
            }
        });
        macIp = new EditText(this);
        macIp.setSingleLine(true);
        macIp.setInputType(InputType.TYPE_CLASS_PHONE);
        macIp.setHint("可选：自动发现失败时填写 Mac 当前局域网 IPv4");
        macIp.setText(getSharedPreferences("headunit-log", MODE_PRIVATE)
            .getString("mac_ip", ""));
        content.addView(macIp);
        button(content, "手动上报（备用）", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmUpload(); }
        });
        button(content, "删除本机已有日志", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(HeadUnitLogActivity.this)
                    .setTitle("删除本机故障日志？")
                    .setMessage("将清除本应用记录的崩溃、连接/解码阶段；Mac 已收到的归档不会被删除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认删除", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            HeadUnitDiagnostics.clear(HeadUnitLogActivity.this);
                            refresh();
                        }
                    }).show();
            }
        });
        summary = new TextView(this);
        summary.setTextSize(12);
        summary.setTextIsSelectable(true);
        summary.setPadding(0, 16, 0, 12);
        content.addView(summary);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        refresh();
        if (HeadUnitDiagnostics.hasPreviousCrash()) {
            HeadUnitAutoUploader.schedule(this, "recovered failure on log screen");
        }
    }

    private static Button button(LinearLayout parent, String caption, View.OnClickListener click) {
        Button button = new Button(parent.getContext());
        button.setText(caption);
        button.setOnClickListener(click);
        parent.addView(button);
        return button;
    }

    private void refresh() {
        summary.setText("自动上报：" + (HeadUnitAutoUploader.isEnabled(this) ? "已开启" : "已关闭") +
            "\n最近上报结果：" + HeadUnitAutoUploader.lastStatus(this) +
            "\n\n" + HeadUnitDiagnostics.preview());
    }

    private void confirmUpload() {
        final String destination = macIp.getText().toString().trim();
        if (!HeadUnitAutoUploader.isPrivateIpv4(destination)) {
            summary.setText("请输入可信局域网 Mac 的 IPv4（192.168.x.x、10.x.x.x 或 172.16～31.x.x）。");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("发送本应用的故障日志？")
            .setMessage("将日志 ZIP 发送到 http://" + destination +
                ":18816/api/diagnostic-dump，保存在 Mac 的 car-market-kit/dumps。\n" +
                "可能包含手机型号、Android 版本、异常堆栈、网络或媒体诊断信息。\n" +
                "仅在你自己控制的局域网发送，不会自动后台上报。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认发送", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) { upload(destination); }
            }).show();
    }

    private void upload(final String destination) {
        getSharedPreferences("headunit-log", MODE_PRIVATE).edit()
            .putString("mac_ip", destination).apply();
        summary.setText("正在连接 Mac 上传诊断 ZIP…");
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                File file = null;
                HttpURLConnection connection = null;
                try {
                    file = HeadUnitDiagnostics.snapshotZip(HeadUnitLogActivity.this);
                    if (file.length() <= 0 || file.length() > 2 * 1024 * 1024) {
                        throw new IllegalArgumentException("Diagnostic ZIP size invalid");
                    }
                    URL url = new URL("http://" + destination + ":18816/api/diagnostic-dump");
                    connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(4000);
                    connection.setReadTimeout(15000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/zip");
                    connection.setFixedLengthStreamingMode((int) file.length());
                    OutputStream stream = connection.getOutputStream();
                    FileInputStream input = new FileInputStream(file);
                    try {
                        byte[] block = new byte[4096];
                        int n;
                        while ((n = input.read(block)) != -1) stream.write(block, 0, n);
                        stream.flush();
                    } finally { input.close(); stream.close(); }
                    int code = connection.getResponseCode();
                    result = code == 200 ? "上传成功（HTTP 200）。\n" +
                        "Mac 目录：car-market-kit/dumps/\n请将这次故障对应 ZIP 留作排查。" :
                        "上传未成功，Mac 返回 HTTP " + code +
                        "。请确认 Mac 下载网页正在运行，车机和 Mac 在同一网络。";
                    HeadUnitDiagnostics.event("UPLOAD", "HTTP status=" + code);
                } catch (Exception error) {
                    result = "上传失败：" + error.getClass().getSimpleName() +
                        "。请检查局域网、IP、Mac 的 18816 端口。\n" +
                        "日志仍保存在本机，下次可重试或点击复制日志。";
                    HeadUnitDiagnostics.event("UPLOAD_FAILED", error.getClass().getSimpleName());
                } finally {
                    if (connection != null) connection.disconnect();
                    if (file != null) file.delete();
                }
                final String message = result;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing()) summary.setText(message + "\n\n" +
                            HeadUnitDiagnostics.preview());
                    }
                });
            }
        }, "headunit-log-upload").start();
    }

}
