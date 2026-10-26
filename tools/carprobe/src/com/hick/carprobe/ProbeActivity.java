package com.hick.carprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Android 4.2-compatible, read-only device inventory. No network or storage permissions. */
public class ProbeActivity extends Activity {
    private String report;
    private TextView reportView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.installCrashHandler(this);
        DiagnosticLog.append(this, "LIFECYCLE", "诊断工具启动");
        report = createReport();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        TextView heading = new TextView(this);
        heading.setText(getScreenTitle());
        heading.setTextSize(20);
        layout.addView(heading);

        TextView notice = new TextView(this);
        notice.setText(getPrivacyNotice());
        notice.setPadding(0, 10, 0, 10);
        layout.addView(notice);

        Button copyButton = new Button(this);
        copyButton.setText("复制完整报告");
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Car Probe Report", report));
                    Toast.makeText(ProbeActivity.this, "报告已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(copyButton);

        Button refreshButton = new Button(this);
        refreshButton.setText("重新检测硬件和权限状态");
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                report = createReport();
                if (reportView != null) reportView.setText(report);
                DiagnosticLog.append(ProbeActivity.this, "SCAN", "用户重新执行硬件/权限检测");
                Toast.makeText(ProbeActivity.this, "重新检测完成", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(refreshButton);

        // Navigation that must remain visible without scrolling through the hardware report.
        appendPrimaryControls(layout);

        Button shareButton = new Button(this);
        shareButton.setText("分享报告（手动选择应用）");
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, report);
                try {
                    startActivity(Intent.createChooser(intent, "分享车机报告"));
                } catch (Exception e) {
                    Toast.makeText(ProbeActivity.this, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
                }
            }
        });
        if (!shareButtonInReportSection()) layout.addView(shareButton);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout reportSection = new LinearLayout(this);
        reportSection.setOrientation(LinearLayout.VERTICAL);
        if (shareButtonInReportSection()) reportSection.addView(shareButton);
        appendAdditionalControls(reportSection);
        TextView contents = new TextView(this);
        contents.setTextSize(13);
        contents.setTextIsSelectable(true);
        contents.setText(report);
        reportView = contents;
        reportSection.addView(contents);
        scrollView.addView(reportSection);
        layout.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
    }

    /** Extension point for an explicitly opt-in, separate diagnostic build. */
    protected String getPrivacyNotice() {
        return "不会联网、上传、安装或修改系统。报告仅显示在本机；点击下方按钮才复制或分享。";
    }

    protected String getScreenTitle() {
        return "车机深度诊断 v0.2（只读检测）";
    }

    /** Keep the most-used actions visible on small landscape car displays. */
    protected boolean shareButtonInReportSection() { return false; }

    protected void appendAdditionalControls(LinearLayout layout) { }

    /** Prominent controls above the scrollable diagnostic report. */
    protected void appendPrimaryControls(LinearLayout layout) { }

    protected final String getReportText() { return report; }

    @Override protected void onResume() {
        super.onResume();
        if (reportView != null) {
            report = createReport();
            reportView.setText(report);
        }
    }

    private static void line(StringBuilder output, String key, Object value) {
        output.append(key).append(": ").append(value == null ? "(无)" : value).append('\n');
    }

    private static void section(StringBuilder output, String title) {
        output.append('\n').append("=== ").append(title).append(" ===\n");
    }

    private void appendHandlers(StringBuilder output, PackageManager pm, String label, Intent intent) {
        line(output, label, "");
        try {
            List<ResolveInfo> handlers = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo handler : handlers) {
                if (handler.activityInfo != null) {
                    line(output, "  可处理", handler.activityInfo.packageName + "/" + handler.activityInfo.name);
                }
            }
            if (handlers.isEmpty()) line(output, "  可处理", "无");
        } catch (Exception e) {
            line(output, "  查询失败", e.getClass().getSimpleName());
        }
    }

    private String createReport() {
        StringBuilder output = new StringBuilder(16384);
        section(output, "报告");
        line(output, "时间", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        line(output, "本应用包名", getPackageName());

        section(output, "Android / 固件");
        line(output, "Android", Build.VERSION.RELEASE);
        line(output, "API", Build.VERSION.SDK_INT);
        line(output, "Build ID", Build.ID);
        line(output, "Build DISPLAY", Build.DISPLAY);
        line(output, "Build FINGERPRINT", Build.FINGERPRINT);
        line(output, "制造商", Build.MANUFACTURER);
        line(output, "品牌", Build.BRAND);
        line(output, "型号", Build.MODEL);
        line(output, "设备", Build.DEVICE);
        line(output, "产品", Build.PRODUCT);
        line(output, "主板", Build.BOARD);
        line(output, "硬件", Build.HARDWARE);
        line(output, "Bootloader", Build.BOOTLOADER);
        line(output, "CPU ABI", Build.CPU_ABI + ", " + Build.CPU_ABI2);
        line(output, "内核", System.getProperty("os.version"));

        section(output, "显示 / 内存 / 网络代理");
        DisplayMetrics display = getResources().getDisplayMetrics();
        line(output, "显示像素", display.widthPixels + " x " + display.heightPixels);
        line(output, "densityDpi", display.densityDpi);
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memory);
            line(output, "可用内存 MB", memory.availMem / (1024 * 1024));
            line(output, "总内存 MB", memory.totalMem / (1024 * 1024));
            line(output, "运行内存级别 MB", am.getMemoryClass());
        }
        line(output, "外部存储", Environment.getExternalStorageState());
        line(output, "HTTP 代理", System.getProperty("http.proxyHost") + ":" + System.getProperty("http.proxyPort"));
        line(output, "HTTPS 代理", System.getProperty("https.proxyHost") + ":" + System.getProperty("https.proxyPort"));

        PackageManager pm = getPackageManager();
        section(output, "安装来源 / 系统安装入口");
        try {
            line(output, "本 APK 安装来源", pm.getInstallerPackageName(getPackageName()));
        } catch (Exception e) {
            line(output, "安装来源错误", e.getClass().getSimpleName());
        }
        Intent apk = new Intent(Intent.ACTION_VIEW);
        apk.setDataAndType(Uri.parse("file:///sdcard/Download/probe.apk"),
                "application/vnd.android.package-archive");
        appendHandlers(output, pm, "APK 查看/安装 Intent", apk);
        Intent market = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.hick.carprobe"));
        appendHandlers(output, pm, "market:// Intent", market);

        section(output, "已安装的应用（只读）");
        try {
            List<PackageInfo> apps = pm.getInstalledPackages(0);
            Collections.sort(apps, new Comparator<PackageInfo>() {
                @Override public int compare(PackageInfo a, PackageInfo b) {
                    return a.packageName.compareToIgnoreCase(b.packageName);
                }
            });
            line(output, "应用总数", apps.size());
            for (PackageInfo info : apps) {
                boolean system = info.applicationInfo != null &&
                        (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                line(output, system ? "系统" : "用户",
                        info.packageName + " | " + info.versionName + " (" + info.versionCode + ")");
            }
        } catch (Exception e) {
            line(output, "应用列表错误", e.getClass().getSimpleName());
        }
        output.append(AdvancedDiagnostics.collect(this));
        section(output, "Root / Wi-Fi ADB 研究页最近一次结果（手动操作）");
        android.content.SharedPreferences rootPrefs = getSharedPreferences(
                "root_research", Context.MODE_PRIVATE);
        line(output, "最近动作", rootPrefs.getString("last_action", "未使用研究页"));
        line(output, "操作结果", rootPrefs.getString("last_report", "尚无记录"));
        section(output, "诊断应用自身的运行日志");
        output.append(DiagnosticLog.read(this));
        return output.toString();
    }
}
