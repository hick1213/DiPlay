package com.hick.carprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Explicit, bounded extraction of public APK/ODEX and installer evidence on API 17. */
public final class AppDumpActivity extends Activity {
    private static final String PREFS = "app_dump";
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final long MAX_ARCHIVE_INPUT = 52L * 1024 * 1024;
    private static final int MAX_UPLOAD_BYTES = 60 * 1024 * 1024;
    private final LinkedHashMap<String, CheckBox> targets = new LinkedHashMap<String, CheckBox>();
    private TextView output;
    private Button dumpButton;
    private Button uploadButton;
    private EditText macIp;
    private File lastZip;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(18, 14, 18, 14);

        TextView title = new TextView(this);
        title.setText("关键 App / Root / 安装限制 Dump v10");
        title.setTextSize(20);
        page.addView(title);
        TextView notice = new TextView(this);
        notice.setText("仅导出所选应用的公开包信息、可读取的 APK/ODEX、"
                + "安装 Intent 处理者、Root/ADB 线索、安装来源与可读取的系统框架。"
                + "不读取 /data/data 私有数据，不尝试 Root，不修改原厂应用。"
                + "可能包含厂商专有代码；仅在自己的车机和可信 Mac 上分析。"
                + "每个文件最多 32 MiB，整体最多约 52 MiB。");
        page.addView(notice);

        populateTargets(page);
        dumpButton = button(page, "① 导出 App + 安装限制证据 ZIP", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDump(); }
        });
        TextView uploadHelp = new TextView(this);
        uploadHelp.setText("② 传回 Mac：先双击 Start-Car-Market-Hook.command，"
                + "填写 Mac 的局域网 IP，再点击发送并确认。"
                + "只传到你的私有局域网 Mac；文件保存在 car-market-kit/dumps。");
        uploadHelp.setPadding(0, 12, 0, 3);
        page.addView(uploadHelp);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        macIp = new EditText(this);
        macIp.setSingleLine(true);
        macIp.setInputType(InputType.TYPE_CLASS_PHONE);
        macIp.setHint("Mac IPv4，例如 192.168.31.209");
        macIp.setText(prefs.getString("mac_ip", ""));
        page.addView(macIp);

        uploadButton = button(page, "② 将刚导出的 ZIP 发送到我的 Mac", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmUpload(); }
        });
        uploadButton.setEnabled(false);

        output = new TextView(this);
        output.setTextSize(13);
        output.setTextIsSelectable(true);
        output.setPadding(0, 12, 0, 16);
        output.setText("尚未导出。原始文件即使读取受限，也会在 ZIP 的包信息里记录原因。");
        page.addView(output);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
        lastZip = findPreviousArchive();
        if (lastZip != null) {
            uploadButton.setEnabled(true);
            output.setText("找到上次导出的 ZIP：" + lastZip.getAbsolutePath()
                    + "\n大小：" + lastZip.length() +
                    " 字节\n可填写 Mac IP 后手动发送；也可以重新选择应用再导出。");
        }
    }

    private Button button(LinearLayout parent, String caption, View.OnClickListener click) {
        Button result = new Button(this);
        result.setText(caption);
        result.setTextSize(14);
        result.setOnClickListener(click);
        parent.addView(result);
        return result;
    }

    private void populateTargets(LinearLayout page) {
        LinkedHashMap<String, Boolean> candidates = new LinkedHashMap<String, Boolean>();
        candidates.put("com.acloud.stub.store", true);
        candidates.put("com.android.packageinstaller", true);
        candidates.put("com.android.settings", true);
        candidates.put("com.android.systemui", true);
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        try {
            ResolveInfo launcher = getPackageManager().resolveActivity(home, 0);
            if (launcher != null && launcher.activityInfo != null) {
                candidates.put(launcher.activityInfo.packageName, true);
            }
        } catch (Exception ignored) { }
        for (String pkg : new String[] {"com.android.launcher", "com.android.launcher2"}) {
            candidates.put(pkg, true);
        }
        // Offer a few potentially relevant OEM packages, but never auto-select all user apps.
        try {
            for (PackageInfo item : getPackageManager().getInstalledPackages(0)) {
                String lower = item.packageName.toLowerCase(Locale.US);
                if ((lower.contains("acloud") || lower.contains("factory") ||
                        lower.contains("engineer")) && !candidates.containsKey(item.packageName)) {
                    if (candidates.size() >= 18) break;
                    candidates.put(item.packageName, false);
                }
            }
        } catch (Exception ignored) { }
        TextView choice = new TextView(this);
        choice.setText("勾选要导出的应用（原厂关键项默认选中）：");
        choice.setPadding(0, 10, 0, 3);
        page.addView(choice);
        PackageManager pm = getPackageManager();
        for (Map.Entry<String, Boolean> item : candidates.entrySet()) {
            try {
                pm.getPackageInfo(item.getKey(), 0);
            } catch (PackageManager.NameNotFoundException missing) { continue; }
            CheckBox box = new CheckBox(this);
            box.setText(item.getKey());
            box.setTextSize(14);
            box.setChecked(item.getValue());
            page.addView(box);
            targets.put(item.getKey(), box);
        }
        if (targets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有找到已知关键包；请先保存普通诊断报告。");
            page.addView(empty);
        }
    }

    private void confirmDump() {
        if (busy) return;
        final ArrayList<String> selected = new ArrayList<String>();
        for (Map.Entry<String, CheckBox> item : targets.entrySet()) {
            if (item.getValue().isChecked()) selected.add(item.getKey());
        }
        if (selected.isEmpty()) { output.setText("请至少勾选一个已安装应用。"); return; }
        new AlertDialog.Builder(this).setTitle("确认导出 " + selected.size() + " 个 App")
                .setMessage("将尝试读取 APK/ODEX、包信息和系统框架，"
                        + "另记录安装 Intent 处理者、未知来源开关和权限，生成本地 ZIP。"
                        + "这不会触发安装、Root 或网络传输。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始 Dump", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        runDump(selected);
                    }
                }).show();
    }

    private void runDump(final List<String> selected) {
        busy = true;
        dumpButton.setEnabled(false);
        uploadButton.setEnabled(false);
        output.setText("正在导出，文件较大时请等待……");
        new Thread(new Runnable() {
            @Override public void run() {
                String message;
                try {
                    ExportResult result = export(selected);
                    lastZip = result.file;
                    message = "导出完成：" + result.file.getAbsolutePath() +
                            "\n大小：" + result.file.length() + " 字节\nSHA-256：" +
                            result.sha256 + "\n" + result.notes +
                            "\n可以填写 Mac IP，手动点击发送。";
                    DiagnosticLog.append(AppDumpActivity.this, "APP_DUMP",
                            "包数=" + selected.size() + " 字节=" + result.file.length());
                } catch (Exception error) {
                    lastZip = null;
                    message = "Dump 失败：" + error.toString();
                    DiagnosticLog.append(AppDumpActivity.this, "APP_DUMP_ERROR",
                            error.getClass().getSimpleName());
                }
                final String text = message;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        output.setText(text);
                        busy = false;
                        dumpButton.setEnabled(true);
                        uploadButton.setEnabled(lastZip != null);
                    }
                });
            }
        }, "carprobe-app-dump").start();
    }

    private static final class ExportResult {
        final File file;
        final String sha256;
        final String notes;
        ExportResult(File file, String sha256, String notes) {
            this.file = file; this.sha256 = sha256; this.notes = notes;
        }
    }

    private File findPreviousArchive() {
        File newest = null;
        File[] locations = new File[] {getExternalFilesDir("dumps"),
                new File(getFilesDir(), "dumps")};
        for (File location : locations) {
            if (location == null || !location.isDirectory()) continue;
            File[] files = location.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isFile() && (file.getName().startsWith("car-app-dump-v10-") ||
                        file.getName().startsWith("car-app-dump-v9-") ||
                        file.getName().startsWith("car-app-dump-v8-")) &&
                        file.getName().endsWith(".zip") && file.length() > 0 &&
                        file.length() <= MAX_UPLOAD_BYTES &&
                        (newest == null || file.lastModified() > newest.lastModified())) {
                    newest = file;
                }
            }
        }
        return newest;
    }

    private ExportResult export(List<String> selected) throws Exception {
        File destination = getExternalFilesDir("dumps");
        if (destination == null || (!destination.isDirectory() && !destination.mkdirs())) {
            destination = new File(getFilesDir(), "dumps");
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建 Dump 目录");
        }
        File archive = File.createTempFile("car-app-dump-v10-", ".zip", destination);
        StringBuilder notes = new StringBuilder();
        long[] copied = new long[] {0};
        try {
            ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive));
            try {
                zip.setLevel(0); // APKs are already ZIPs; save the old car CPU.
                writeText(zip, "index.txt", "Android " + Build.VERSION.RELEASE +
                        " SDK " + Build.VERSION.SDK_INT + "\nBuild " + Build.FINGERPRINT +
                        "\nSelected " + selected.toString() + "\nNo private app data.\n");
                PackageManager pm = getPackageManager();
                for (String name : selected) {
                    String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                    StringBuilder info = new StringBuilder();
                    try {
                        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                                PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS |
                                PackageManager.GET_PERMISSIONS |
                                PackageManager.GET_SIGNATURES;
                        PackageInfo pkg = pm.getPackageInfo(name, flags);
                        appendPackageInfo(info, pkg);
                        try {
                            info.append("installerPackage=")
                                    .append(pm.getInstallerPackageName(name)).append('\n');
                        } catch (Exception error) {
                            info.append("installerPackage=ERROR ").append(error).append('\n');
                        }
                        ApplicationInfo application = pkg.applicationInfo;
                        if (application != null) {
                            File apk = new File(application.sourceDir);
                            copyPublicFile(zip, apk, "apks/" + safe + ".apk", copied, info);
                            String path = apk.getAbsolutePath();
                            if (path.endsWith(".apk")) {
                                File odex = new File(path.substring(0, path.length() - 4) + ".odex");
                                if (odex.isFile()) {
                                    copyPublicFile(zip, odex, "odex/" + safe + ".odex",
                                            copied, info);
                                } else {
                                    info.append("ODEX: no adjacent file\n");
                                }
                            }
                        }
                    } catch (Exception error) {
                        info.append("PACKAGE ERROR: ").append(error).append('\n');
                    }
                    writeText(zip, "metadata/" + safe + ".txt", info.toString());
                    notes.append(name).append(": ")
                            .append(info.indexOf("COPIED APK") >= 0 ? "APK 已复制" : "仅元数据")
                            .append('\n');
                }
                notes.append(InstallEvidence.append(this, zip, copied));
            } finally { zip.close(); }
            if (archive.length() > MAX_UPLOAD_BYTES) {
                throw new IOException("ZIP 超过 60 MiB 限制");
            }
            return new ExportResult(archive, digestFile(archive), notes.toString());
        } catch (Exception error) {
            if (!archive.delete()) archive.deleteOnExit();
            throw error;
        }
    }

    private static void appendPackageInfo(StringBuilder info, PackageInfo pkg) throws Exception {
        info.append("package=").append(pkg.packageName).append("\nversionName=")
                .append(pkg.versionName).append("\nversionCode=").append(pkg.versionCode)
                .append("\nsharedUserId=").append(pkg.sharedUserId).append('\n');
        ApplicationInfo app = pkg.applicationInfo;
        if (app != null) {
            info.append("uid=").append(app.uid).append("\nflags=").append(app.flags)
                    .append("\nsourceDir=").append(app.sourceDir)
                    .append("\npublicSourceDir=").append(app.publicSourceDir)
                    .append("\ntargetSdk=").append(app.targetSdkVersion).append('\n');
        }
        if (pkg.requestedPermissions != null) {
            for (String permission : pkg.requestedPermissions) {
                info.append("requestedPermission=").append(permission).append('\n');
            }
        }
        if (pkg.signatures != null) for (android.content.pm.Signature signature : pkg.signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            info.append("signatureSha256=").append(hex(digest.digest(signature.toByteArray())))
                    .append('\n');
        }
        if (pkg.activities != null) for (ActivityInfo activity : pkg.activities) {
            info.append("activity=").append(activity.name)
                    .append(" exported=").append(activity.exported)
                    .append(" enabled=").append(activity.enabled)
                    .append(" permission=").append(activity.permission).append('\n');
        }
        if (pkg.services != null) for (ServiceInfo service : pkg.services) {
            info.append("service=").append(service.name)
                    .append(" exported=").append(service.exported)
                    .append(" permission=").append(service.permission).append('\n');
        }
        if (pkg.receivers != null) for (ActivityInfo receiver : pkg.receivers) {
            info.append("receiver=").append(receiver.name)
                    .append(" exported=").append(receiver.exported)
                    .append(" permission=").append(receiver.permission).append('\n');
        }
        if (pkg.providers != null) for (ProviderInfo provider : pkg.providers) {
            info.append("provider=").append(provider.name)
                    .append(" authority=").append(provider.authority)
                    .append(" exported=").append(provider.exported)
                    .append(" readPermission=").append(provider.readPermission)
                    .append(" writePermission=").append(provider.writePermission).append('\n');
        }
    }

    /** Do not copy private directories even if a broken OEM build accidentally exposes them. */
    private static boolean publicApkPath(String canonical) {
        for (String prefix : new String[] {
                "/system/app/", "/system/priv-app/", "/vendor/app/", "/data/app/"}) {
            if (canonical.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void copyPublicFile(ZipOutputStream zip, File file, String entry,
                                       long[] copied, StringBuilder info) {
        try {
            String path = file.getCanonicalPath();
            if (!publicApkPath(path)) throw new IOException("path outside public APK locations");
            if (!file.isFile() || !file.canRead()) throw new IOException("not readable");
            if (file.length() < 0 || file.length() > MAX_FILE_BYTES ||
                    copied[0] + file.length() > MAX_ARCHIVE_INPUT) {
                throw new IOException("size cap reached");
            }
            zip.putNextEntry(new ZipEntry(entry));
            InputStream input = new FileInputStream(file);
            try {
                byte[] buffer = new byte[16384];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    zip.write(buffer, 0, size);
                    copied[0] += size;
                    if (copied[0] > MAX_ARCHIVE_INPUT) throw new IOException("archive size cap");
                }
            } finally {
                input.close();
                zip.closeEntry();
            }
            info.append("COPIED ").append(entry.endsWith(".odex") ? "ODEX" : "APK")
                    .append(" ").append(entry).append(" bytes=").append(file.length()).append('\n');
        } catch (Exception error) {
            info.append("SKIPPED ").append(entry).append(": ")
                    .append(error.getClass().getSimpleName()).append(' ')
                    .append(error.getMessage()).append('\n');
        }
    }

    private static void writeText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private static String digestFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] block = new byte[16384];
            int n;
            while ((n = input.read(block)) != -1) digest.update(block, 0, n);
        } finally { input.close(); }
        return hex(digest.digest());
    }

    private void confirmUpload() {
        if (busy || lastZip == null || !lastZip.isFile()) {
            output.setText("请先完成导出。");
            return;
        }
        final String ip = macIp.getText().toString().trim();
        if (!privateIPv4(ip)) {
            output.setText("请输入 Mac 的私有 IPv4 地址，例如 192.168.31.209。");
            return;
        }
        new AlertDialog.Builder(this).setTitle("确认上传原厂 App Dump")
                .setMessage("将上传约 " + (lastZip.length() / 1024) +
                        " KiB 的 ZIP 到你指定的 Mac " + ip +
                        ":18816。包含应用程序代码，不包含其他 App 的私有数据。"
                        + "使用自己的可信热点；不会后台自动上传。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认发送", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        runUpload(ip);
                    }
                }).show();
    }

    private static boolean privateIPv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; ++i) {
                if (!parts[i].matches("[0-9]{1,3}")) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255) return false;
            }
        } catch (NumberFormatException invalid) { return false; }
        return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 &&
                octets[1] <= 31) || (octets[0] == 192 && octets[1] == 168);
    }

    private void runUpload(final String ip) {
        busy = true;
        dumpButton.setEnabled(false);
        uploadButton.setEnabled(false);
        output.setText("正在向 Mac 上传 ZIP……");
        final File archive = lastZip;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mac_ip", ip).apply();
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    String sha = digestFile(archive);
                    URL url = new URL("http://" + ip + ":18816/api/diagnostic-dump");
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                    try {
                        connection.setConnectTimeout(12000);
                        connection.setReadTimeout(45000);
                        connection.setInstanceFollowRedirects(false);
                        connection.setRequestMethod("POST");
                        connection.setRequestProperty("Content-Type", "application/zip");
                        connection.setDoOutput(true);
                        connection.setFixedLengthStreamingMode((int) archive.length());
                        OutputStream output = connection.getOutputStream();
                        InputStream input = new FileInputStream(archive);
                        try {
                            byte[] block = new byte[16384];
                            int n;
                            while ((n = input.read(block)) != -1) output.write(block, 0, n);
                        } finally {
                            input.close();
                            output.close();
                        }
                        int status = connection.getResponseCode();
                        InputStream responseStream = status < 400
                                ? connection.getInputStream() : connection.getErrorStream();
                        if (responseStream == null) throw new IOException("无服务端响应");
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        try {
                            byte[] block = new byte[1024];
                            int count;
                            while ((count = responseStream.read(block)) >= 0) {
                                if (buffer.size() + count > 4096) throw new IOException("响应过长");
                                buffer.write(block, 0, count);
                            }
                        } finally { responseStream.close(); }
                        JSONObject response = new JSONObject(new String(buffer.toByteArray(), "UTF-8"));
                        if (status != 200 || !response.optBoolean("ok", false))
                            throw new IOException("HTTP " + status + " " +
                                    response.optString("error", "upload failed"));
                        if (!sha.equalsIgnoreCase(response.optString("sha256", "")))
                            throw new IOException("Mac 校验的 SHA-256 不一致");
                        result = "上传成功，Mac 接收编号：" + response.optString("id") +
                                "\nSHA-256：" + sha +
                                "\n目录：car-market-kit/dumps/";
                    } finally { connection.disconnect(); }
                    DiagnosticLog.append(AppDumpActivity.this, "APP_DUMP_UPLOAD", "上传成功");
                } catch (Exception error) {
                    result = "上传失败，本地 ZIP 仍保留：" + error.toString();
                    DiagnosticLog.append(AppDumpActivity.this, "APP_DUMP_UPLOAD_ERROR",
                            error.getClass().getSimpleName());
                }
                final String message = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        output.setText(message);
                        busy = false;
                        dumpButton.setEnabled(true);
                        uploadButton.setEnabled(lastZip != null);
                    }
                });
            }
        }, "carprobe-dump-upload").start();
    }
}
