package com.hick.carprobe;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

/** Read-only checks safe on Android 4.2 / API 17; never invokes su or changes settings. */
public final class AdvancedDiagnostics {
    private AdvancedDiagnostics() { }

    private static void line(StringBuilder out, String key, Object value) {
        out.append(key).append(": ").append(value == null ? "未知" : value).append('\n');
    }

    private static void section(StringBuilder out, String heading) {
        out.append("\n=== ").append(heading).append(" ===\n");
    }

    private static String property(String key) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Method get = type.getMethod("get", String.class, String.class);
            String result = (String) get.invoke(null, key, "");
            return result.length() == 0 ? "未设置" : result;
        } catch (Throwable error) {
            return "不可读取 (" + error.getClass().getSimpleName() + ")";
        }
    }

    private static String read(File path, int maxLines, int maxChars) {
        if (!path.isFile()) return "不存在";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
            StringBuilder out = new StringBuilder();
            String current;
            int count = 0;
            while ((current = reader.readLine()) != null && count++ < maxLines) {
                if (out.length() + current.length() + 1 > maxChars) {
                    out.append("\n(已截断)");
                    break;
                }
                if (out.length() > 0) out.append('\n');
                out.append(current);
            }
            return out.toString();
        } catch (Exception error) {
            return "无法读取 (" + error.getClass().getSimpleName() + ")";
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
    }

    private static String firstLine(String path) {
        return read(new File(path), 1, 250);
    }

    private static void storage(StringBuilder out, String name, File dir) {
        try {
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long block = stat.getBlockSize();
            line(out, name, "容量=" + (stat.getBlockCount() * block / 1048576L) + "MB, 可用="
                    + (stat.getAvailableBlocks() * block / 1048576L) + "MB");
        } catch (Throwable error) {
            line(out, name, "不可读取 (" + error.getClass().getSimpleName() + ")");
        }
    }

    private static boolean localAdbPortOpen() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 300);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    public static String collect(Context context) {
        StringBuilder out = new StringBuilder(12000);
        section(out, "ADB / Root / 安全状态（只读检测，不尝试提权）");
        line(out, "当前应用 UID", Process.myUid());
        try {
            line(out, "ADB 调试开关", Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, -1));
        } catch (Exception error) { line(out, "ADB 调试开关", "读取受限"); }
        line(out, "ro.secure", property("ro.secure"));
        line(out, "ro.debuggable", property("ro.debuggable"));
        line(out, "ro.adb.secure", property("ro.adb.secure"));
        line(out, "ro.build.type", property("ro.build.type"));
        line(out, "service.adb.tcp.port", property("service.adb.tcp.port"));
        line(out, "persist.service.adb.enable", property("persist.service.adb.enable"));
        line(out, "sys.usb.config", property("sys.usb.config"));
        line(out, "SELinux enforcing", firstLine("/sys/fs/selinux/enforce"));
        line(out, "系统构建 tags", Build.TAGS);
        for (String path : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/vendor/bin/su"}) {
            File binary = new File(path);
            if (binary.exists()) line(out, "su 线索", path + " 可执行=" + binary.canExecute());
        }
        line(out, "本机 TCP 5555 是否监听", localAdbPortOpen() ? "是（不代表已授权）" : "否");
        boolean canReadLogs = context.getPackageManager().checkPermission(
                "android.permission.READ_LOGS", context.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
        line(out, "读取全局 logcat 权限", canReadLogs ? "已授予" : "未授予（只能读本应用日志）");
        line(out, "说明", "发现 su/ADB 端口不等于拥有 root/ADB 授权；未执行 su、setprop、提权漏洞或系统修改。");

        section(out, "CPU / 系统内核 / 存储");
        line(out, "运行时可用 CPU", Runtime.getRuntime().availableProcessors());
        line(out, "/proc/cpuinfo", read(new File("/proc/cpuinfo"), 65, 5000));
        line(out, "/proc/version", firstLine("/proc/version"));
        line(out, "系统平均负载", firstLine("/proc/loadavg"));
        line(out, "/proc/meminfo", read(new File("/proc/meminfo"), 10, 1500));
        line(out, "/proc/uptime", firstLine("/proc/uptime"));
        for (int cpu = 0; cpu < 12; cpu++) {
            String base = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/";
            if (new File(base + "scaling_cur_freq").exists()) {
                line(out, "CPU" + cpu + " 频率 kHz", "当前=" + firstLine(base + "scaling_cur_freq")
                        + " 最低=" + firstLine(base + "cpuinfo_min_freq")
                        + " 最高=" + firstLine(base + "cpuinfo_max_freq")
                        + " 调速=" + firstLine(base + "scaling_governor"));
            }
        }
        storage(out, "内部 data 分区", Environment.getDataDirectory());
        if ("mounted".equals(Environment.getExternalStorageState())) {
            storage(out, "外部存储", Environment.getExternalStorageDirectory());
        }
        File thermal = new File("/sys/class/thermal");
        File[] zones = thermal.listFiles();
        if (zones != null) {
            int seen = 0;
            for (File zone : zones) {
                if (!zone.getName().startsWith("thermal_zone") || seen++ >= 12) continue;
                line(out, "温度 " + zone.getName(), firstLine(zone.getPath() + "/type")
                        + " = " + firstLine(zone.getPath() + "/temp") + " (驱动原始单位)");
            }
        }

        section(out, "无线网络 / 蓝牙 / 投屏前提");
        PackageManager pm = context.getPackageManager();
        line(out, "Wi-Fi 硬件", pm.hasSystemFeature(PackageManager.FEATURE_WIFI));
        line(out, "Wi-Fi Direct 特性", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT));
        line(out, "Wi-Fi P2P 服务", context.getSystemService(Context.WIFI_P2P_SERVICE) != null);
        line(out, "蓝牙特性", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH));
        line(out, "USB Host 特性", pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST));
        line(out, "GPS 特性", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS));
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            line(out, "Wi-Fi 已启用", wifi != null && wifi.isWifiEnabled());
            if (wifi != null) {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null) {
                    line(out, "Wi-Fi 速率 / 信号", info.getLinkSpeed() + " Mbps / " + info.getRssi() + " dBm");
                }
            }
        } catch (Throwable error) { line(out, "Wi-Fi 详情", "无权限或驱动不可用"); }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            line(out, "蓝牙适配器/状态", adapter == null ? "不存在" :
                    (adapter.isEnabled() ? "已开启" : "关闭"));
        } catch (Throwable error) { line(out, "蓝牙详情", "不可访问"); }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                int shown = 0;
                for (NetworkInterface item : Collections.list(interfaces)) {
                    if (shown++ >= 20) break;
                    StringBuilder ips = new StringBuilder();
                    Enumeration<InetAddress> addresses = item.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            if (ips.length() > 0) ips.append(',');
                            ips.append(address.getHostAddress());
                        }
                    }
                    line(out, "网络接口", item.getName() + " IPv4=" + ips.toString());
                }
            }
        } catch (Throwable error) { line(out, "网络接口", "不可读取"); }

        section(out, "H.264 / 视频硬件解码能力");
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null && manager.getDeviceConfigurationInfo() != null) {
                line(out, "OpenGL ES 版本", manager.getDeviceConfigurationInfo().getGlEsVersion());
            }
        } catch (Throwable error) { line(out, "OpenGL ES", "无法读取"); }
        line(out, "ro.opengles.version", property("ro.opengles.version"));
        line(out, "ro.hardware.egl", property("ro.hardware.egl"));
        try {
            int count = MediaCodecList.getCodecCount();
            line(out, "系统 MediaCodec 数量", count);
            int printed = 0;
            for (int i = 0; i < count && printed < 45; i++) {
                MediaCodecInfo codec = MediaCodecList.getCodecInfoAt(i);
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (type.startsWith("video/")) {
                        line(out, "视频解码器", codec.getName() + " -> " + type);
                        printed++;
                    }
                }
            }
            line(out, "提示", "列出解码器不等于可流畅播放，仍需 H.264 720p/1080p 帧率实测。");
        } catch (Throwable error) {
            line(out, "MediaCodec 查询失败", error.getClass().getSimpleName());
        }

        section(out, "电源 / 内存策略");
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                line(out, "电池电量", battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1));
                line(out, "外部供电", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
                line(out, "电池温度(0.1摄氏度)", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1));
            }
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) line(out, "大内存进程上限 MB", manager.getLargeMemoryClass());
        } catch (Throwable error) { line(out, "电源状态", "不可读取"); }
        line(out, "隐私说明", "不采集 IMEI、序列号、账号、Wi-Fi 密码、SSID 或 MAC 地址。");
        return out.toString();
    }
}
