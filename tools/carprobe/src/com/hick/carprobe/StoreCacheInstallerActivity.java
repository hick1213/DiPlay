package com.hick.carprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Explicitly initiated OEM-store cache write-and-install test. No shell, root, or background install. */
public final class StoreCacheInstallerActivity extends Activity {
    private static final String OEM_CACHE = "/data/data/com.acloud.stub.store/cache/Data";
    private static final long MAX_BYTES = 150L * 1024 * 1024;
    private EditText urlInput;
    private TextView status;
    private Button downloadButton;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(20, 20, 20, 20);
        TextView heading = new TextView(this);
        heading.setText("原厂 APK 安装通道检查（Android 4.2.2）");
        heading.setTextSize(20);
        main.addView(heading);

        TextView notice = new TextView(this);
        notice.setText("不会获取 root、修改系统白名单或静默安装。先检查原厂应用商店的缓存目录是否能由本应用访问。仅当你主动输入可信 APK 的 HTTP(S) 地址并确认时才会下载；安装必须再次通过原厂系统安装器确认。原包名、签名均保持不变。测试时不要安装与原厂车机软件重名的包。");
        main.addView(notice);

        Button checkButton = new Button(this);
        checkButton.setText("① 检查缓存目录权限（只读）");
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { inspectCache(); }
        });
        main.addView(checkButton);

        urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        urlInput.setHint("https://你的服务器/任意原包名.apk");
        main.addView(urlInput);

        downloadButton = new Button(this);
        downloadButton.setText("② 下载至原厂缓存并打开系统安装器");
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmDownload(); }
        });
        main.addView(downloadButton);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, 15, 0, 10);
        main.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(main);
        setContentView(scroll);
        inspectCache();
    }

    private void inspectCache() {
        File dir = new File(OEM_CACHE);
        String result = "路径：" + OEM_CACHE + "\n"
                + "目录存在：" + dir.isDirectory() + "\n"
                + "可读取：" + dir.canRead() + "\n"
                + "可写入：" + dir.canWrite() + "\n"
                + "若不可写，不能从本应用直接使用原厂商店缓存；需要通过原厂商店本身执行下载，或有合法的系统维护权限。";
        status.setText(result);
        DiagnosticLog.append(this, "STORE_CACHE_CHECK", result.replace('\n', ';'));
    }

    private void confirmDownload() {
        if (busy) return;
        final String address = urlInput.getText().toString().trim();
        try {
            URL url = new URL(address);
            String protocol = url.getProtocol().toLowerCase(Locale.US);
            if (!("http".equals(protocol) || "https".equals(protocol)) || url.getHost().length() == 0) {
                throw new IllegalArgumentException("仅允许 HTTP(S) URL");
            }
        } catch (Exception e) {
            status.setText("地址无效，请输入完整的 HTTP(S) APK 下载链接。");
            return;
        }
        if (!new File(OEM_CACHE).canWrite()) {
            status.setText("原厂缓存目录不可写。当前方案无法直接安装，不会继续下载或修改系统。");
            DiagnosticLog.append(this, "STORE_CACHE_DENIED", OEM_CACHE);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认下载安装测试？")
                .setMessage("仅下载你提供的 APK，最大 150MB；保留应用原有包名和签名。下载完成将显示包名，并要求再次确认才调用系统安装器。请只测试可信应用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("下载", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { download(address); }
                }).show();
    }

    private void display(final String message, final boolean isBusy) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                busy = isBusy;
                downloadButton.setEnabled(!isBusy);
                status.setText(message);
            }
        });
    }

    private void download(final String address) {
        display("正在下载，请保持网络连接……", true);
        new Thread(new Runnable() {
            @Override public void run() {
                File temporary = null;
                File installedCandidate = null;
                HttpURLConnection connection = null;
                try {
                    File dir = new File(OEM_CACHE);
                    if (!dir.isDirectory() || !dir.canWrite()) throw new Exception("原厂缓存目录无写入权限");
                    String unique = "carprobe-" + UUID.randomUUID().toString();
                    temporary = new File(dir, unique + ".part");
                    File apk = new File(dir, unique + ".apk");
                    connection = (HttpURLConnection) new URL(address).openConnection();
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(20000);
                    connection.setInstanceFollowRedirects(false);
                    int code = connection.getResponseCode();
                    if (code != 200) throw new Exception("下载 HTTP " + code + "（不自动跨域重定向）");
                    long length = connection.getContentLength();
                    if (length > MAX_BYTES) throw new Exception("APK 超过 150MB 限制");
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(temporary);
                    long total = 0;
                    try {
                        byte[] buffer = new byte[8192];
                        int bytes;
                        while ((bytes = input.read(buffer)) != -1) {
                            total += bytes;
                            if (total > MAX_BYTES) throw new Exception("APK 超过 150MB 限制");
                            output.write(buffer, 0, bytes);
                            sha.update(buffer, 0, bytes);
                        }
                        output.getFD().sync();
                    } finally {
                        output.close();
                        input.close();
                    }
                    if (total < 512) throw new Exception("下载文件太小，可能不是 APK");
                    if (!temporary.renameTo(apk)) throw new Exception("缓存文件重命名失败");
                    temporary = null;
                    installedCandidate = apk;
                    PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                    if (info == null || info.packageName == null || info.packageName.length() == 0) {
                        throw new Exception("Android 无法解析 APK；不调用安装器");
                    }
                    apk.setReadable(true, false);
                    StringBuilder digest = new StringBuilder();
                    for (byte b : sha.digest()) digest.append(String.format(Locale.US, "%02x", b & 0xff));
                    DiagnosticLog.append(StoreCacheInstallerActivity.this, "STORE_CACHE_DOWNLOAD",
                            info.packageName + " bytes=" + total + " sha256=" + digest);
                    confirmInstall(apk, info.packageName, total, digest.toString());
                    installedCandidate = null;
                } catch (Exception e) {
                    String failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                    DiagnosticLog.append(StoreCacheInstallerActivity.this, "STORE_CACHE_INSTALL_ERROR", failure);
                    display("无法通过原厂缓存下载安装：" + failure, false);
                } finally {
                    if (temporary != null) temporary.delete();
                    if (installedCandidate != null) installedCandidate.delete();
                    if (connection != null) connection.disconnect();
                }
            }
        }, "carprobe-store-cache").start();
    }

    private void confirmInstall(final File apk, final String packageName, final long size, final String digest) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                display("缓存下载完成，等待你确认调用系统安装器。", false);
                new AlertDialog.Builder(StoreCacheInstallerActivity.this)
                        .setTitle("检测到 APK 包名：" + packageName)
                        .setMessage("文件 " + size + " 字节\nSHA-256：" + digest
                                + "\n\n保留原包名/原签名。系统安装器可能仍因白名单拒绝，请记录拒绝提示。")
                        .setNegativeButton("暂不安装", null)
                        .setPositiveButton("打开系统安装器", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Intent view = new Intent(Intent.ACTION_VIEW);
                                    view.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
                                    view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(view);
                                    DiagnosticLog.append(StoreCacheInstallerActivity.this,
                                            "STORE_INSTALL_INTENT", packageName + " path=" + apk.getAbsolutePath());
                                    status.setText("已经调用原厂系统安装器；请在系统界面确认结果。缓存 APK 不自动删除。");
                                } catch (Exception e) {
                                    DiagnosticLog.append(StoreCacheInstallerActivity.this,
                                            "STORE_INSTALL_INTENT_ERROR", e.toString());
                                    status.setText("原厂系统安装器没有接受请求：" + e);
                                }
                            }
                        }).show();
            }
        });
    }
}
