package com.hick.carprobe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only evidence about the Android 4.2 installer path and system framework. */
final class InstallEvidence {
    private static final long MAX_FILE = 32L * 1024 * 1024;
    private static final long MAX_INPUT = 52L * 1024 * 1024;
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String[] PACKAGES = {
            "title.navi", "com.acloud.stub.store", "com.android.packageinstaller",
            "com.android.settings", "android"
    };
    private static final String[] SYSTEM_FILES = {
            "/system/etc/permissions/platform.xml",
            "/system/framework/services.odex",
            "/system/framework/services.jar",
            "/system/framework/framework.odex",
            "/system/framework/framework.jar",
            "/system/framework/framework-res.apk",
            "/system/framework/android.policy.odex",
            "/system/framework/android.policy.jar"
    };

    private InstallEvidence() { }

    static String append(Context context, ZipOutputStream zip, long[] copied) throws IOException {
        StringBuilder routes = new StringBuilder();
        routes.append("Read-only Android installer evidence, no installation attempted.\n");
        routes.append("Android=").append(Build.VERSION.RELEASE).append(" API=")
                .append(Build.VERSION.SDK_INT).append('\n');
        routes.append("brand=").append(Build.BRAND).append("\nmodel=")
                .append(Build.MODEL).append("\ndevice=").append(Build.DEVICE)
                .append("\nboard=").append(Build.BOARD).append("\nhardware=")
                .append(Build.HARDWARE).append("\nfirmware=")
                .append(Build.FINGERPRINT).append("\nbuildType=")
                .append(Build.TYPE).append("\nbuildTags=")
                .append(Build.TAGS).append('\n');
        reportSettings(context, routes);
        reportPackages(context, routes);
        reportRoutes(context, routes);
        writeText(zip, "install-evidence/installer-routes.txt", routes.toString());
        writeText(zip, "install-evidence/root-feasibility.txt",
                RootEvidence.collect(context));

        StringBuilder manifest = new StringBuilder();
        manifest.append("Only public system framework and explicit permissions XML files.\n")
                .append("No private app data, no root command, no install attempt.\n")
                .append("Copied APK/ODEX and system evidence share a 52 MiB raw input cap.\n");
        int success = 0;
        for (String path : SYSTEM_FILES) {
            if (copySystemFile(zip, path, copied, manifest)) success++;
        }
        File permissionDir = new File("/system/etc/permissions");
        File[] entries = permissionDir.listFiles();
        if (entries != null) {
            int examined = 0;
            for (File entry : entries) {
                if (++examined > 150) {
                    manifest.append("permission XML scan limited to 150 entries\n");
                    break;
                }
                String name = entry.getName().toLowerCase(Locale.US);
                if (!name.endsWith(".xml") || name.equals("platform.xml")) continue;
                if (name.contains("install") || name.contains("market") ||
                        name.contains("package") || name.contains("acloud") ||
                        name.contains("factory") || name.contains("white")) {
                    if (success >= 16) {
                        manifest.append("additional file cap reached\n");
                        break;
                    }
                    if (copySystemFile(zip, entry.getAbsolutePath(), copied, manifest)) success++;
                }
            }
        } else {
            manifest.append("permission XML listing not accessible\n");
        }
        writeText(zip, "install-evidence/system-files-manifest.txt", manifest.toString());
        return "安装路径/Root/系统框架取证：额外复制 " + success +
                " 个公开文件；详情见 install-evidence/\n";
    }

    private static void reportSettings(Context context, StringBuilder out) {
        out.append("\n[Settings read only; -1 means absent/inaccessible]\n");
        String[] keys = {"install_non_market_apps", "adb_enabled",
                "package_verifier_enable", "verifier_verify_adb_installs"};
        for (String key : keys) {
            try {
                out.append("Secure.").append(key).append('=')
                        .append(Settings.Secure.getInt(context.getContentResolver(), key, -1))
                        .append('\n');
            } catch (Exception error) {
                out.append("Secure.").append(key).append("=ERROR ")
                        .append(error.getClass().getSimpleName()).append('\n');
            }
            try {
                out.append("Global.").append(key).append('=')
                        .append(Settings.Global.getInt(context.getContentResolver(), key, -1))
                        .append('\n');
            } catch (Exception error) {
                out.append("Global.").append(key).append("=ERROR ")
                        .append(error.getClass().getSimpleName()).append('\n');
            }
        }
    }

    private static void reportPackages(Context context, StringBuilder out) {
        out.append("\n[Installer source & permission checks]\n");
        PackageManager pm = context.getPackageManager();
        String[] permissions = {
                "android.permission.INSTALL_PACKAGES",
                "android.permission.DELETE_PACKAGES",
                "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.DUMP"
        };
        for (String name : PACKAGES) {
            out.append("package=").append(name).append('\n');
            try {
                PackageInfo info = pm.getPackageInfo(name, 0);
                ApplicationInfo app = info.applicationInfo;
                out.append("  installer=").append(pm.getInstallerPackageName(name))
                        .append(" sharedUserId=").append(info.sharedUserId).append('\n');
                if (app != null) {
                    out.append("  uid=").append(app.uid)
                            .append(" system=")
                            .append((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                            .append(" updatedSystem=")
                            .append((app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                            .append(" source=").append(app.sourceDir).append('\n');
                }
                for (String permission : permissions) {
                    out.append("  ").append(permission).append('=')
                            .append(pm.checkPermission(permission, name) ==
                                    PackageManager.PERMISSION_GRANTED ? "granted" : "denied")
                            .append('\n');
                }
            } catch (Exception error) {
                out.append("  unavailable: ").append(error).append('\n');
            }
        }
    }

    private static void reportRoutes(Context context, StringBuilder out) {
        out.append("\n[Actual resolvers for APK install intents; no intents launched]\n");
        String[] actions = {Intent.ACTION_VIEW, Intent.ACTION_INSTALL_PACKAGE};
        String[] schemes = {"file", "content"};
        PackageManager pm = context.getPackageManager();
        for (String action : actions) {
            for (String scheme : schemes) {
                Intent intent = new Intent(action);
                intent.setDataAndType(Uri.parse(scheme + ":///carprobe-test.apk"), MIME_APK);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                out.append("action=").append(action).append(" scheme=").append(scheme)
                        .append(" mime=").append(MIME_APK).append('\n');
                try {
                    ResolveInfo resolved = pm.resolveActivity(intent,
                            PackageManager.MATCH_DEFAULT_ONLY);
                    out.append("  default=");
                    appendActivity(out, resolved);
                    out.append('\n');
                    List<ResolveInfo> activities = pm.queryIntentActivities(intent, 0);
                    out.append("  handlers=").append(activities.size()).append('\n');
                    int count = 0;
                    for (ResolveInfo handler : activities) {
                        if (count++ >= 30) {
                            out.append("    capped at 30 handlers\n");
                            break;
                        }
                        out.append("    ");
                        appendActivity(out, handler);
                        out.append('\n');
                    }
                } catch (Exception error) {
                    out.append("  ERROR ").append(error).append('\n');
                }
            }
        }
    }

    private static void appendActivity(StringBuilder out, ResolveInfo info) {
        if (info == null || info.activityInfo == null) {
            out.append("none");
            return;
        }
        ActivityInfo activity = info.activityInfo;
        out.append(activity.packageName).append('/').append(activity.name)
                .append(" exported=").append(activity.exported)
                .append(" enabled=").append(activity.enabled)
                .append(" permission=").append(activity.permission)
                .append(" target=").append(activity.targetActivity);
    }

    private static boolean copySystemFile(ZipOutputStream zip, String absolutePath,
                                          long[] copied, StringBuilder manifest) {
        try {
            if (!absolutePath.startsWith("/system/framework/") &&
                    !absolutePath.startsWith("/system/etc/permissions/")) {
                throw new IOException("not in allowed system directory");
            }
            File file = new File(absolutePath);
            if (!absolutePath.equals(file.getCanonicalPath())) {
                throw new IOException("non-canonical path");
            }
            if (!file.isFile() || !file.canRead()) throw new IOException("not readable");
            long size = file.length();
            if (size > MAX_FILE || size < 0 || copied[0] + size > MAX_INPUT) {
                throw new IOException("size cap (remaining=" + (MAX_INPUT - copied[0]) + ")");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            zip.putNextEntry(new ZipEntry("install-evidence" + absolutePath));
            InputStream input = new FileInputStream(file);
            try {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    zip.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    copied[0] += n;
                }
            } finally {
                input.close();
                zip.closeEntry();
            }
            manifest.append("COPIED ").append(absolutePath).append(" bytes=")
                    .append(size).append(" sha256=").append(hex(digest.digest()))
                    .append('\n');
            return true;
        } catch (Exception error) {
            manifest.append("SKIPPED ").append(absolutePath).append(" reason=")
                    .append(error.getClass().getSimpleName()).append(' ')
                    .append(error.getMessage()).append('\n');
            return false;
        }
    }

    private static void writeText(ZipOutputStream zip, String entry, String text)
            throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(text.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
