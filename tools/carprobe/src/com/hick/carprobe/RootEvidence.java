package com.hick.carprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Locale;

/** Read-only root/boot/ADB feasibility evidence. Never invokes su or changes settings. */
final class RootEvidence {
    private static final String[] PROPERTIES = {
            "ro.secure", "ro.debuggable", "ro.adb.secure", "ro.build.type",
            "ro.build.tags", "ro.product.cpu.abi", "ro.hardware",
            "ro.bootloader", "ro.boot.verifiedbootstate", "ro.kernel.qemu",
            "service.adb.tcp.port", "persist.adb.tcp.port",
            "persist.service.adb.enable", "init.svc.adbd", "init.svc.installd"
    };
    private static final String[] BINARIES = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/vendor/bin/su", "/system/bin/adbd", "/sbin/adbd",
            "/system/bin/pm", "/system/bin/installd", "/system/bin/setprop",
            "/system/bin/start", "/system/bin/stop",
            "/system/bin/app_process", "/system/bin/busybox", "/system/xbin/busybox"
    };

    private RootEvidence() { }

    static String collect(Context context) {
        StringBuilder out = new StringBuilder(3500);
        out.append("ROOT FORENSICS / Android ").append(Build.VERSION.RELEASE)
                .append(" API ").append(Build.VERSION.SDK_INT)
                .append("\nRead-only. No su invoked. No ADB enabled. No install attempted.\n");
        out.append("application=").append(context.getPackageName())
                .append(" ownUid=").append(Process.myUid())
                .append(" tags=").append(Build.TAGS).append('\n');

        out.append("\n[Boot and ADB properties]\n");
        String secure = "";
        String debuggable = "";
        for (String property : PROPERTIES) {
            String value = property(property);
            out.append(property).append('=').append(value).append('\n');
            if ("ro.secure".equals(property)) secure = value;
            if ("ro.debuggable".equals(property)) debuggable = value;
        }
        out.append("tcpLocalhost5555=").append(adbTcpOpen() ? "listening" : "closed")
                .append(" (does not establish ADB authorization)\n");
        out.append("\n[Settings; -1 means absent or inaccessible]\n");
        for (String key : new String[] {"adb_enabled", "install_non_market_apps"}) {
            try {
                out.append("Global.").append(key).append('=')
                        .append(Settings.Global.getInt(context.getContentResolver(), key, -1))
                        .append('\n');
            } catch (Exception error) {
                out.append("Global.").append(key).append("=ERROR ")
                        .append(error.getClass().getSimpleName()).append('\n');
            }
            try {
                out.append("Secure.").append(key).append('=')
                        .append(Settings.Secure.getInt(context.getContentResolver(), key, -1))
                        .append('\n');
            } catch (Exception error) {
                out.append("Secure.").append(key).append("=ERROR ")
                        .append(error.getClass().getSimpleName()).append('\n');
            }
        }

        out.append("\n[SU / ADB executables: existence is NOT root authorization]\n");
        boolean foundSu = false;
        for (String path : BINARIES) {
            File binary = new File(path);
            if (!binary.exists()) {
                out.append(path).append("=missing\n");
                continue;
            }
            if (path.endsWith("/su")) foundSu = true;
            out.append(path).append(" bytes=").append(binary.length())
                    .append(" readable=").append(binary.canRead())
                    .append(" executable=").append(binary.canExecute());
            if (path.endsWith("/su") && binary.isFile() && binary.canRead() &&
                    binary.length() > 0 && binary.length() <= 2L * 1024 * 1024) {
                out.append(" sha256=").append(sha256(binary));
            }
            out.append('\n');
        }

        out.append("\n[Current process credentials/capabilities]\n");
        selectProcLines(new File("/proc/self/status"), out,
                new String[] {"Name:", "Uid:", "Gid:", "Groups:", "CapInh:",
                        "CapPrm:", "CapEff:", "CapBnd:", "NoNewPrivs:", "Seccomp:"},
                120);

        out.append("\n[System and data mount points]\n");
        mountEvidence(out);
        out.append("\n[SELinux enforcement]\n");
        String selinux = readFirstByte("/sys/fs/selinux/enforce");
        if ("unavailable".equals(selinux)) selinux = readFirstByte("/selinux/enforce");
        if ("1".equals(selinux)) selinux = "enforcing";
        else if ("0".equals(selinux)) selinux = "permissive";
        out.append("SELinux=").append(selinux)
                .append(" (unavailable does not prove disabled)\n");

        out.append("\n[Previous opt-in su -c id test]\n");
        SharedPreferences prefs = context.getSharedPreferences(
                "root_research", Context.MODE_PRIVATE);
        out.append("lastSuTestAtMillis=").append(prefs.getLong("last_su_at", 0))
                .append("\nlastSuTest=").append(prefs.getString(
                        "last_su_test", "not attempted")).append('\n');

        out.append("\n[Assessment, not an exploit]\n");
        if (Process.myUid() == 0) {
            out.append("Diagnostic app itself is running as uid=0: verify OEM identity and constraints.\n");
        } else if (foundSu) {
            out.append("A su path exists, but root authorization is UNKNOWN until the user "
                    + "explicitly runs the manual su -c id check.\n");
        } else {
            out.append("No known su path found. An ordinary app cannot enable root adbd "
                    + "or silently install packages on this evidence alone.\n");
        }
        if ("0".equals(secure) && "1".equals(debuggable)) {
            out.append("Debug-oriented boot properties: investigate authorized OEM "
                    + "developer/maintenance access; do not assume ADB is available.\n");
        }
        out.append("Root access, network ADB and bypassing OEM app-install restrictions "
                + "are separate questions; no firmware changes made.\n");
        return out.toString();
    }

    private static String property(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, key, "");
            return value == null || value.length() == 0 ? "<unset>" : value;
        } catch (Exception error) {
            return "<inaccessible:" + error.getClass().getSimpleName() + ">";
        }
    }

    private static boolean adbTcpOpen() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 400);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static void selectProcLines(File file, StringBuilder out,
                                        String[] prefixes, int maxLines) {
        BufferedReader input = null;
        try {
            input = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            String line;
            int seen = 0;
            while ((line = input.readLine()) != null && seen++ < maxLines) {
                for (String prefix : prefixes) {
                    if (line.startsWith(prefix)) {
                        out.append(line).append('\n');
                        break;
                    }
                }
            }
        } catch (Exception error) {
            out.append(file.getPath()).append(": ")
                    .append(error.getClass().getSimpleName()).append('\n');
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
        }
    }

    private static void mountEvidence(StringBuilder out) {
        BufferedReader input = null;
        try {
            input = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/mounts"), "UTF-8"));
            String line;
            int seen = 0;
            int matched = 0;
            while ((line = input.readLine()) != null && seen++ < 240) {
                String[] columns = line.split(" ");
                if (columns.length < 4) continue;
                String mount = columns[1];
                if (mount.equals("/") || mount.equals("/system") || mount.equals("/data") ||
                        mount.equals("/vendor") || mount.equals("/cache") ||
                        mount.equals("/system_root")) {
                    out.append("device=").append(columns[0])
                            .append(" mount=").append(mount)
                            .append(" fs=").append(columns[2])
                            .append(" flags=").append(columns[3]).append('\n');
                    matched++;
                }
            }
            if (matched == 0) out.append("No matching mount point entries readable\n");
        } catch (Exception error) {
            out.append("/proc/mounts: ").append(error.getClass().getSimpleName())
                    .append('\n');
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
        }
    }

    private static String readFirstByte(String path) {
        InputStream input = null;
        try {
            input = new FileInputStream(path);
            int byteRead = input.read();
            return byteRead < 0 ? "empty" : String.valueOf((char) byteRead);
        } catch (Exception ignored) {
            return "unavailable";
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
        }
    }

    private static String sha256(File file) {
        InputStream input = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input = new FileInputStream(file);
            byte[] block = new byte[4096];
            int n;
            while ((n = input.read(block)) >= 0) digest.update(block, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception error) {
            return "ERROR:" + error.getClass().getSimpleName();
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
        }
    }
}
