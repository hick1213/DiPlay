package com.hick.carprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

/** Android 4.2 root/ADB feasibility lab. All privileged actions require an explicit tap. */
public final class RootResearchActivity extends Activity {
    private static final String PREFS = "root_research";
    private static final int PORT = 5555;
    private TextView output;
    private boolean running;

    private interface Operation { String perform() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(20, 18, 20, 18);
        TextView title = new TextView(this);
        title.setText("Root / Wi-Fi ADB / 安装限制研究 v10");
        title.setTextSize(21);
        page.addView(title);

        TextView notice = new TextView(this);
        notice.setPadding(0, 9, 0, 9);
        notice.setText("车机停稳后操作。先进行只读检测；检查 su 需要你主动点击授权。启动 Wi-Fi ADB 会将调试端口 5555 暴露在当前网络，请仅在自己控制的热点使用，测试结束立即关闭。不会自动 Root、运行漏洞利用或静默安装 APK。发现 root 并不等于系统安装白名单已解除。");
        page.addView(notice);

        button(page, "① 只读检查 Root / 安装权限 / ADB 状态", new View.OnClickListener() {
            @Override public void onClick(View view) { run("READ_ONLY", new Operation() {
                @Override public String perform() { return collect(); }
            }); }
        });

        button(page, "①b Root 深度取证：分区 / SELinux / 进程能力（只读）",
                new View.OnClickListener() {
            @Override public void onClick(View view) { run("ROOT_FORENSICS", new Operation() {
                @Override public String perform() {
                    return RootEvidence.collect(RootResearchActivity.this);
                }
            }); }
        });

        button(page, "② 测试是否能获得 su 授权（只执行 id）", new View.OnClickListener() {
            @Override public void onClick(View view) {
                confirm("请求 Root 权限", "将执行 su -c id，仅检查是否为 uid=0。车机可能弹出授权框；不同意就取消，不会尝试提权漏洞。", "检查授权", new Operation() {
                    @Override public String perform() {
                        Result result = exec(12000, "su", "-c", "id");
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putString("last_su_test", result.toString())
                                .putLong("last_su_at", System.currentTimeMillis()).apply();
                        return "su -c id: " + result + "\n" + (isRoot(result)
                                ? "已获得本次 root shell 授权，可以尝试手动启动 adbd。"
                                : "没有获得 root：普通 APK 无法自行给 adbd 提权；继续检查官方开发者/厂商维护入口。");
                    }
                });
            }
        });

        button(page, "③ 已有 Root 时尝试启动 Wi-Fi ADB:5555", new View.OnClickListener() {
            @Override public void onClick(View view) {
                confirm("会开放网络调试端口", "只在停车、自己控制的 Wi-Fi/热点中进行。Android 4.2 的 ADB 网络验证能力取决于原厂固件；同网设备可能访问调试端口。仅使用已有 su 授权，不执行任何提权漏洞。测试完请点“关闭 Wi-Fi ADB”。", "我确认启动", new Operation() {
                    @Override public String perform() throws Exception { return toggleAdb(true); }
                });
            }
        });

        button(page, "④ 关闭 Wi-Fi ADB / 恢复原端口", new View.OnClickListener() {
            @Override public void onClick(View view) {
                confirm("关闭网络调试", "将尝试用 su 重启 adbd 并恢复启动前记录的端口；如果不是本程序打开的，将设置为 USB 模式（-1）。", "关闭", new Operation() {
                    @Override public String perform() throws Exception { return toggleAdb(false); }
                });
            }
        });

        TextView help = new TextView(this);
        help.setPadding(0, 12, 0, 5);
        help.setText("判断路径：\n" +
                "• 有 su 且授权为 uid=0：可以继续测试 Wi-Fi ADB 和受控的 pm install。\n" +
                "• 没有 su，但有系统级 INSTALL_PACKAGES 权限：研究原厂授权接口。\n" +
                "• 二者都没有：需要原厂维护通道、刷机/恢复映像或先做固件安全评估；普通 APK 不能直接绕过安装器。\n" +
                "• 按钮不会写入 /system，也不会尝试 Framaroot/KingRoot 等未知提权程序。");
        page.addView(help);

        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(13);
        output.setText("只读检测将在打开页面后开始。日志也记录在车机诊断应用内。\n");
        page.addView(output);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
        run("READ_ONLY", new Operation() {
            @Override public String perform() { return collect(); }
        });
    }

    private void button(LinearLayout page, String caption, View.OnClickListener handler) {
        Button button = new Button(this);
        button.setText(caption);
        button.setTextSize(15);
        button.setOnClickListener(handler);
        page.addView(button);
    }

    private void confirm(String title, String message, String positive, final Operation operation) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton(positive, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        run("PRIVILEGED_TEST", operation);
                    }
                }).show();
    }

    private void run(final String action, final Operation operation) {
        if (running) return;
        running = true;
        output.setText("正在执行 " + action + "，最多等待 15 秒……\n");
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try { result = operation.perform(); }
                catch (Exception error) { result = "操作失败：" + error.toString(); }
                final String message = result;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("last_action", action)
                        .putString("last_report", message.length() > 5500
                                ? message.substring(0, 5500) : message)
                        .apply();
                DiagnosticLog.append(RootResearchActivity.this, action,
                        message.length() > 800 ? message.substring(0, 800) : message);
                runOnUiThread(new Runnable() {
                    @Override public void run() { output.setText(message); running = false; }
                });
            }
        }, "carprobe-root-research").start();
    }

    private static final class Result {
        final int exit;
        final String text;
        final boolean timedOut;
        Result(int exit, String text, boolean timedOut) {
            this.exit = exit; this.text = text; this.timedOut = timedOut;
        }
        @Override public String toString() {
            return "exit=" + exit + (timedOut ? " (超时)" : "") + ", output=" + text;
        }
    }

    /** Bound shell execution: never run a command that came from an input field. */
    private static Result exec(long timeoutMs, String... argv) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            final InputStream input = process.getInputStream();
            final StringBuilder captured = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        byte[] buffer = new byte[256];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            synchronized (captured) {
                                if (captured.length() < 3000) {
                                    captured.append(new String(buffer, 0,
                                            Math.min(read, 3000 - captured.length()), "UTF-8"));
                                }
                            }
                        }
                    } catch (Exception ignored) { }
                }
            }, "carprobe-shell-output");
            reader.setDaemon(true);
            reader.start();
            long until = System.currentTimeMillis() + timeoutMs;
            for (;;) {
                try {
                    int code = process.exitValue();
                    reader.join(150);
                    synchronized (captured) {
                        return new Result(code, captured.toString().trim(), false);
                    }
                } catch (IllegalThreadStateException unfinished) {
                    if (System.currentTimeMillis() >= until) {
                        process.destroy();
                        synchronized (captured) {
                            return new Result(-1, captured.toString().trim(), true);
                        }
                    }
                    Thread.sleep(100);
                }
            }
        } catch (Exception error) {
            return new Result(-1, error.toString(), false);
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static boolean isRoot(Result result) {
        return result.exit == 0 && (result.text.startsWith("uid=0(") ||
                result.text.startsWith("uid=0 ") || "uid=0".equals(result.text));
    }

    private static String prop(String name) {
        Result result = exec(2000, "getprop", name);
        return result.exit == 0 ? result.text.trim() : "不可读取";
    }

    private static boolean portOpen() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            return true;
        } catch (Exception error) { return false; }
        finally { try { socket.close(); } catch (Exception ignored) { } }
    }

    private String collect() {
        StringBuilder s = new StringBuilder(1400);
        s.append("Android ").append(Build.VERSION.RELEASE).append(" (API ")
                .append(Build.VERSION.SDK_INT).append(")\nBuild：")
                .append(Build.FINGERPRINT).append("\n应用 UID：")
                .append(Process.myUid()).append("\nBuild tags：").append(Build.TAGS)
                .append("\nsh id：").append(exec(2000, "sh", "-c", "id"))
                .append("\n");

        ApplicationInfo info = getApplicationInfo();
        s.append("系统应用：").append((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                .append("，更新系统应用：")
                .append((info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                .append("\n");
        for (String permission : new String[] {"android.permission.INSTALL_PACKAGES",
                "android.permission.DELETE_PACKAGES", "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.DUMP"}) {
            s.append(permission.substring("android.permission.".length())).append("：")
                    .append(getPackageManager().checkPermission(permission, getPackageName())
                            == PackageManager.PERMISSION_GRANTED ? "已授予" : "未授予")
                    .append("\n");
        }
        // OEM engineering/debug activities sometimes exist even on builds without su.
        // Inspect only exported metadata; do not launch an unknown vendor component.
        PackageManager pm = getPackageManager();
        s.append("\n可能的原厂维护工具（仅名称，不自动启动）：\n");
        int candidates = 0;
        try {
            for (PackageInfo app : pm.getInstalledPackages(0)) {
                String pkg = app.packageName.toLowerCase(java.util.Locale.US);
                if (pkg.contains("engineer") || pkg.contains("factory") ||
                        pkg.contains("debug") || pkg.contains("root") ||
                        pkg.contains("maintenance") || pkg.contains("acloud")) {
                    if (candidates++ >= 18) break;
                    s.append("  ").append(app.packageName).append("\n");
                }
            }
        } catch (Exception error) { s.append("  查询失败：").append(error.getClass().getSimpleName()).append("\n"); }
        if (candidates == 0) s.append("  未从包名发现明显的工程工具\n");
        s.append("可导出的原厂调试/安装设置入口：\n");
        for (String pkg : new String[] {"com.android.settings", "com.acloud.stub.store",
                "com.android.packageinstaller"}) {
            try {
                PackageInfo detail = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                int shown = 0;
                if (detail.activities != null) for (ActivityInfo activity : detail.activities) {
                    String name = activity.name.toLowerCase(java.util.Locale.US);
                    if (activity.exported && (name.contains("development") ||
                            name.contains("engineer") || name.contains("factory") ||
                            name.contains("debug") || name.contains("install"))) {
                        if (shown++ >= 8) break;
                        s.append("  ").append(pkg).append("/").append(activity.name)
                                .append("\n");
                    }
                }
                if (shown == 0) s.append("  ").append(pkg).append("：无匹配的公开 Activity\n");
            } catch (Exception error) {
                s.append("  ").append(pkg).append("：不存在或不可读取\n");
            }
        }
        for (String path : new String[] {"/system/bin/su", "/system/xbin/su",
                "/sbin/su", "/su/bin/su", "/vendor/bin/su"}) {
            File file = new File(path);
            if (file.exists()) s.append("su 路径：").append(path)
                    .append(" 可执行=").append(file.canExecute()).append("\n");
        }
        s.append("su PATH：").append(exec(2000, "sh", "-c", "command -v su"))
                .append("\nroot shell 权限：未自动请求（请点按钮②）\n");
        try {
            s.append("系统 ADB 开关：").append(Settings.Global.getInt(
                    getContentResolver(), Settings.Global.ADB_ENABLED, -1)).append("\n");
        } catch (Exception error) { s.append("系统 ADB 开关：读取受限\n"); }
        for (String key : new String[] {"ro.secure", "ro.debuggable", "ro.adb.secure",
                "service.adb.tcp.port", "persist.adb.tcp.port", "init.svc.adbd"}) {
            s.append(key).append("：").append(prop(key)).append("\n");
        }
        s.append("本机 5555 TCP：").append(portOpen() ? "正在监听" : "未监听")
                .append("\n本机 IPv4：");
        try {
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            if (networks != null) for (NetworkInterface item : Collections.list(networks)) {
                for (java.net.InetAddress ip : Collections.list(item.getInetAddresses())) {
                    if (ip instanceof java.net.Inet4Address && !ip.isLoopbackAddress()) {
                        s.append(item.getName()).append("=").append(ip.getHostAddress()).append(" ");
                    }
                }
            }
        } catch (Exception error) { s.append("不可读取"); }
        s.append("\n安装判断：拥有 uid=0 的 su 才能尝试 root pm install；"
                + "当前 APK 无 INSTALL_PACKAGES 权限时不能直接静默安装。\n");
        return s.toString();
    }

    private String toggleAdb(boolean enable) throws Exception {
        Result root = exec(12000, "su", "-c", "id");
        if (!isRoot(root)) return "未取得 root 授权，未修改 adbd：" + root;
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (enable) {
            if (portOpen() && "5555".equals(prop("service.adb.tcp.port"))) {
                return "5555 已在监听；为避免打断现有会话，本程序未重启 adbd。";
            }
            String original = prop("service.adb.tcp.port");
            if ("不可读取".equals(original)) original = "";
            if ("5555".equals(original)) original = "-1";
            // Persist the recovery value *before* touching adbd.
            if (!prefs.edit().putString("previous_adb_port", original)
                    .putBoolean("adb_attempted", true).commit()) {
                return "无法保存回滚端口，因此未修改 adbd。";
            }
            Result action = exec(5000, "su", "-c",
                    "setprop service.adb.tcp.port 5555 && stop adbd && start adbd");
            Thread.sleep(850);
            boolean listening = portOpen();
            return "启动 adbd 请求：" + action + "\n当前端口属性：" +
                    prop("service.adb.tcp.port") + "\n本机 5555：" +
                    (listening ? "已监听，可以从 Mac 尝试 adb connect 车机IP:5555" :
                            "仍未监听，可能被原厂 init/权限策略拒绝") +
                    "\n测试结束请点击④关闭（请勿在不可信 Wi-Fi 开放此端口）。";
        }
        String original = prefs.getBoolean("adb_attempted", false)
                ? prefs.getString("previous_adb_port", "") : "";
        if (!original.matches("-?[0-9]{1,5}")) original = "-1";
        Result action = exec(5000, "su", "-c", "setprop service.adb.tcp.port " +
                original + " && stop adbd && start adbd");
        Thread.sleep(650);
        if (action.exit == 0) prefs.edit().remove("adb_attempted")
                .remove("previous_adb_port").apply();
        return "关闭/恢复请求：" + action + "\n恢复端口：" + original +
                "\n当前端口属性：" + prop("service.adb.tcp.port") +
                "\n本机 5555：" + (portOpen() ? "仍在监听，请检查原厂服务" : "未监听");
    }
}
