#!/usr/bin/env python3
"""Small Android 4.2 download page and opt-in paired report receiver.

This server does not install, uninstall, proxy, or modify anything on the car.
For Cloudflare access, bind to loopback and expose only HTTP through the tunnel.
"""

import argparse
import io
import json
import time
import zipfile
from email.parser import BytesParser
from email.policy import default as default_email_policy
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from car_probe_reports import MAX_UPLOAD_BYTES, ReportStore


CURRENT_APKS = Path(__file__).resolve().parents[1] / "car-market-kit/APKs"
DEFAULT_APK = CURRENT_APKS / "diplay-android42-toolbar-voice-v13.apk"
DEFAULT_PROBE_APK = CURRENT_APKS / "car-probe-whitelist-title-navi-v11.apk"
DEFAULT_WHITELIST_APK = DEFAULT_PROBE_APK
DEFAULT_STORE_CACHE_PROBE_APK = DEFAULT_PROBE_APK
DEFAULT_CARPLAY_APK = DEFAULT_APK
DEFAULT_LAN_CAST_APK = CURRENT_APKS / "lan-cast-android42-v5.apk"
DEFAULT_TVBOX42_APK = CURRENT_APKS / "tvbox-android42-full-native-v5.apk"
# Legacy download variants deliberately point to absent files: old APKs were
# retired. Never silently offer a different package under an outdated label.
RETIRED_APK = CURRENT_APKS / "__historical-version-retired__.apk"
DEFAULT_TVBOX_LOWRAM_APK = RETIRED_APK
DEFAULT_TVBOX_MODULAR_APK = RETIRED_APK
DEFAULT_TVBOX_FULL_APK = RETIRED_APK
DEFAULT_TVBOX_LITE_APK = RETIRED_APK
DEFAULT_X5_TBS_APK = RETIRED_APK
SLOW_DOWNLOAD_SECONDS = 20


def make_notification_zip():
    """A harmless, valid ZIP large enough to trigger a download notification."""
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr(
            "notice.txt",
            ("Android 4.2 notification test; contains no executable files.\n" * 650).encode(),
        )
    return output.getvalue()


PAGE = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>车机安装诊断</title><style>
body{font-family:Arial,sans-serif;margin:18px;max-width:720px;background:#f5f5f5;color:#222}
h1{font-size:24px}h2{font-size:19px}.section{background:white;padding:14px;margin:12px 0;border:1px solid #ccc}
a.button{display:block;background:#1462a5;color:#fff;text-align:center;padding:14px 5px;margin:12px 0;text-decoration:none;font-size:19px}
.note{color:#a34b09}li{margin:8px 0}
</style></head><body>
<h1>Android 4.2 车机安装诊断（历史兼容入口）</h1>
<p>正式最新版下载请使用 Mac 的 18816 端口，四个 APK 统一放在 car-market-kit/APKs。</p>
<p>本页仅用于可选择的诊断上传测试，不会自动安装、卸载或修改车机。</p>
<div class="section"><h2>第一步：通知栏 / 原生设置入口</h2>
<p>下载一个不含程序的普通 ZIP。服务器会用约 20 秒发送它，方便观察下载通知。</p>
<a class="button" href="/notification-test.zip">开始 20 秒测试下载</a>
<ol><li>开始下载，立即下拉通知栏。</li>
<li>长按“正在下载”或“下载完成”的通知，尝试点“应用信息”。</li>
<li>观察能否进一步进入系统“设置”或“安全”页面。</li></ol>
<p class="note">若浏览器不产生通知，或无法长按，请记录现象；不要反复尝试。</p></div>
<div class="section"><h2>当前安装版</h2>
<a class="button" href="/diplay-carplay.apk">DiPlay v13（CarPlay；sogou.map）</a>
<a class="button" href="/lan-cast.apk">LAN Cast v5（DLNA；com.uu.uunavi）</a>
<a class="button" href="/car-probe-whitelist.apk">车机诊断 v11（title.navi）</a>
<a class="button" href="/tvbox-android42.apk">TVBox v5（com.tencent.navsns）</a>
<p class="note">DiPlay 含私有测试身份材料，仅用于授权的可信局域网。
旧版已经清理；请勿替换不认识的原厂同名程序。</p></div>
<div class="section"><h2>第三步：原厂应用市场</h2>
<p>如果网页安装被拦截，请拍下原厂市场首页、应用详情或失败提示。
后续再检查它是通过什么安装入口及网络请求下载 APK。</p></div>
<p>兼容诊断页 · 服务端保存报告，下载和安装均由用户操作</p></body></html>
""".encode("utf-8")


class ProbeHandler(BaseHTTPRequestHandler):
    apk_path = DEFAULT_APK
    probe_apk_path = DEFAULT_PROBE_APK
    whitelist_apk_path = DEFAULT_WHITELIST_APK
    store_cache_probe_apk_path = DEFAULT_STORE_CACHE_PROBE_APK
    carplay_apk_path = DEFAULT_CARPLAY_APK
    lan_cast_apk_path = DEFAULT_LAN_CAST_APK
    tvbox42_apk_path = DEFAULT_TVBOX42_APK
    tvbox_lowram_apk_path = DEFAULT_TVBOX_LOWRAM_APK
    tvbox_modular_apk_path = DEFAULT_TVBOX_MODULAR_APK
    tvbox_full_apk_path = DEFAULT_TVBOX_FULL_APK
    tvbox_lite_apk_path = DEFAULT_TVBOX_LITE_APK
    x5_tbs_apk_path = DEFAULT_X5_TBS_APK
    notification_zip = make_notification_zip()

    def send_html(self, status, html):
        content = html.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
        self.end_headers()
        self.wfile.write(content)

    def send_upload_gate(self, invalid=False):
        warning = "<p style=\"color:#a00\">上传码无效，请检查后重试。</p>" if invalid else ""
        self.send_html(403 if invalid else 200, """<!doctype html><html lang="zh-CN">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>上传车机文件</title></head><body><h2>上传任意文件（包括 ZIP）</h2>
<p>输入 6 位上传码即可选择文件，整个过程无需输入长网址。</p>
{}
<form method="get" action="/upload">
<input type="text" name="code" pattern="[0-9]{{6}}" inputmode="numeric"
placeholder="6 位上传码" maxlength="6" required>
<button type="submit">继续上传</button></form>
</body></html>""".format(warning))

    def send_upload_form(self, credential, mode="key"):
        form = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>上传车机文件</title>
<style>body{font-family:sans-serif;margin:20px;max-width:600px}input,button{display:block;margin:15px 0;padding:12px;font-size:18px}</style>
</head><body><h2>上传任意文件（包括 ZIP）</h2>
<p>不限制文件类型。文件保存在个人 Mac 移动硬盘上，仅用于离线分析，不会安装或执行。</p>
<form method="post" enctype="multipart/form-data" action="/upload?__MODE__=__UPLOAD_TOKEN__">
<input type="file" name="file" required>
<button type="submit">上传文件</button></form>
<p>最大 64 MB；成功上传一次后入口自动失效。</p></body></html>""".replace(
            "__MODE__", mode).replace("__UPLOAD_TOKEN__", escape(credential, quote=True))
        self.send_html(200, form)

    def handle_file_upload(self, token):
        store = getattr(self.server, "report_store", None)
        if store is None or not store.upload_authorized(token):
            self.send_html(403, "<h3>上传链接无效、过期或已使用。</h3>")
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = 0
        if size <= 0 or size > MAX_UPLOAD_BYTES + 128 * 1024:
            self.send_html(413, "<h3>文件过大或长度无效（最大 64 MB）。</h3>")
            return
        content_type = self.headers.get("Content-Type", "")
        if (len(content_type) > 250 or '\r' in content_type or '\n' in content_type
                or not content_type.startswith("multipart/form-data;")):
            self.send_html(415, "<h3>请使用网页表单上传文件。</h3>")
            return

        self.connection.settimeout(90)
        try:
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ValueError("incomplete upload")
            envelope = ("Content-Type: " + content_type + "\r\nMIME-Version: 1.0\r\n\r\n").encode("ascii")
            multipart = BytesParser(policy=default_email_policy).parsebytes(envelope + raw)
            selected = [part for part in multipart.iter_parts()
                        if part.get_param("name", header="content-disposition") in ("apk", "file")]
            if len(selected) != 1:
                raise ValueError("missing file field")
            part = selected[0]
            original_name = part.get_filename() or ""
            contents = part.get_payload(decode=True)
            receipt = store.store_uploaded_file(token, contents, original_name)
        except PermissionError:
            self.send_html(403, "<h3>上传链接已使用。</h3>")
            return
        except (ValueError, OSError, TypeError):
            self.send_html(400, "<h3>上传失败，请检查文件大小并重试。</h3>")
            return
        self.send_html(200, "<h2>上传成功</h2><p>收到文件，编号：{}</p>".format(escape(receipt)))

    def send_json(self, status, data):
        content = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(content)

    def do_POST(self):
        target = urlsplit(self.path)
        route = target.path
        if route == "/upload":
            key = parse_qs(target.query).get("key", [""])[0]
            code = parse_qs(target.query).get("code", [""])[0]
            store = getattr(self.server, "report_store", None)
            if key:
                self.handle_file_upload(key)
            elif store is not None and store.upload_code_authorized(code):
                self.handle_file_upload(store.upload_token)
            else:
                self.send_html(403, "<h3>上传码无效或已过期。</h3>")
            return
        store = getattr(self.server, "report_store", None)
        if route not in ("/api/pair", "/api/report"):
            self.send_json(404, {"error": "not_found"})
            return
        if store is None:
            self.send_json(503, {"error": "receiver_disabled"})
            return

        token = ""
        if route == "/api/report":
            authorization = self.headers.get("Authorization", "")
            if authorization.startswith("Bearer "):
                token = authorization[7:]
            if not store.authorized(token):
                self.send_json(401, {"error": "not_paired"})
                return

        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_json(400, {"error": "invalid_length"})
            return
        if size <= 0 or size > 200 * 1024:
            self.send_json(413, {"error": "invalid_length"})
            return
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip() != "application/json":
            self.send_json(415, {"error": "json_required"})
            return
        self.connection.settimeout(20)
        try:
            content = json.loads(self.rfile.read(size).decode("utf-8"))
            if not isinstance(content, dict):
                raise ValueError("expected object")
        except (UnicodeError, ValueError, OSError):
            self.send_json(400, {"error": "invalid_json"})
            return

        if route == "/api/pair":
            new_token, result = store.pair(content.get("code"))
            if result == "ok":
                self.send_json(200, {"ok": True, "token": new_token})
            else:
                codes = {"invalid": 401, "expired": 410, "locked": 429}
                self.send_json(codes.get(result, 403), {"error": "pair_" + result})
            return

        try:
            report_id = store.save_report(token, content.get("device_id", ""),
                                          content.get("report"), content.get("app_version", ""))
        except PermissionError:
            self.send_json(401, {"error": "not_paired"})
            return
        except ValueError:
            self.send_json(400, {"error": "invalid_report"})
            return
        self.send_json(200, {"ok": True, "id": report_id})

    def send_file_headers(self, mime_type, filename, size):
        self.send_response(200)
        self.send_header("Content-Type", mime_type)
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", 'attachment; filename="%s"' % filename)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def do_GET(self):
        target = urlsplit(self.path)
        route = target.path
        if route == "/upload":
            key = parse_qs(target.query).get("key", [""])[0]
            code = parse_qs(target.query).get("code", [""])[0]
            store = getattr(self.server, "report_store", None)
            if store is None:
                self.send_html(403, "<h3>上传链接无效、过期或已使用。</h3>")
            elif key and store.upload_authorized(key):
                self.send_upload_form(key)
            elif code and store.upload_code_authorized(code):
                self.send_upload_form(code, mode="code")
            elif key:
                self.send_html(403, "<h3>上传链接无效、过期或已使用。</h3>")
            else:
                self.send_upload_gate(invalid=bool(code))
            return
        if route in ("/", "/index.html"):
            content = PAGE
            store = getattr(self.server, "report_store", None)
            if store is not None and not store.upload_used:
                section = ("<div class=\"section\"><h2>上传车机文件</h2>"
                           "<p>不限制文件类型，可上传 ZIP、APK、DEX 等文件供分析。</p>"
                           "<a class=\"button\" href=\"/upload\">上传文件（6 位上传码）</a>"
                           "</div>").encode("utf-8")
                content = content.replace(b"</body></html>", section + b"</body></html>")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(content)
        elif route == "/notification-test.zip":
            data = self.notification_zip
            self.send_file_headers("application/zip", "notification-test.zip", len(data))
            chunk_size = (len(data) + SLOW_DOWNLOAD_SECONDS - 1) // SLOW_DOWNLOAD_SECONDS
            for offset in range(0, len(data), chunk_size):
                try:
                    self.wfile.write(data[offset:offset + chunk_size])
                    self.wfile.flush()
                    time.sleep(1)
                except (BrokenPipeError, ConnectionResetError):
                    break
        elif route == "/diplay.apk" and self.apk_path.is_file():
            self.send_file_headers(
                "application/vnd.android.package-archive", "DiPlay-Android42-Wireless.apk",
                self.apk_path.stat().st_size,
            )
            with self.apk_path.open("rb") as stream:
                while True:
                    chunk = stream.read(65536)
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        break
        elif route == "/car-probe.apk" and self.probe_apk_path.is_file():
            self.send_file_headers(
                "application/vnd.android.package-archive", "Car-Probe-Android42.apk",
                self.probe_apk_path.stat().st_size,
            )
            with self.probe_apk_path.open("rb") as stream:
                self.wfile.write(stream.read())
        elif route == "/car-probe-whitelist.apk" and self.whitelist_apk_path.is_file():
            self.send_file_headers(
                "application/vnd.android.package-archive", "Car-Probe-Whitelist-Android42.apk",
                self.whitelist_apk_path.stat().st_size,
            )
            with self.whitelist_apk_path.open("rb") as stream:
                self.wfile.write(stream.read())
        elif route == "/car-probe-cache.apk" and self.store_cache_probe_apk_path.is_file():
            self.send_file_headers(
                "application/vnd.android.package-archive", "Car-Probe-OEM-Cache-v5.apk",
                self.store_cache_probe_apk_path.stat().st_size,
            )
            with self.store_cache_probe_apk_path.open("rb") as stream:
                self.wfile.write(stream.read())
        elif route == "/diplay-carplay.apk" and self.carplay_apk_path.is_file():
            self.send_file_headers(
                "application/vnd.android.package-archive", "DiPlay-CarPlay-Android42.apk",
                self.carplay_apk_path.stat().st_size,
            )
            with self.carplay_apk_path.open("rb") as stream:
                while True:
                    block = stream.read(65536)
                    if not block:
                        break
                    try:
                        self.wfile.write(block)
                    except (BrokenPipeError, ConnectionResetError):
                        break
        elif route in ("/lan-cast.apk", "/tvbox-android42.apk", "/tvbox-lowram.apk",
                       "/tvbox-modular.apk", "/tvbox-full.apk", "/tvbox-lite.apk", "/x5.tbs.apk"):
            apk_path, download_name = {
                "/lan-cast.apk": (self.lan_cast_apk_path, "LAN-Cast-Android42.apk"),
                "/tvbox-android42.apk": (self.tvbox42_apk_path, "TVBox-Android42.apk"),
                "/tvbox-lowram.apk": (self.tvbox_lowram_apk_path, "TVBox-Android42-LowRAM.apk"),
                "/tvbox-modular.apk": (self.tvbox_modular_apk_path, "TVBox-Android42-Modular.apk"),
                "/tvbox-full.apk": (self.tvbox_full_apk_path, "TVBox-Android42-Full-v5.apk"),
                "/tvbox-lite.apk": (self.tvbox_lite_apk_path, "TVBox-Android42-Lite.apk"),
                "/x5.tbs.apk": (self.x5_tbs_apk_path, "X5-WebView-Kernel.apk"),
            }[route]
            if not apk_path.is_file():
                self.send_error(404, "APK not yet built")
                return
            self.send_file_headers(
                "application/vnd.android.package-archive", download_name,
                apk_path.stat().st_size,
            )
            with apk_path.open("rb") as stream:
                while True:
                    block = stream.read(65536)
                    if not block:
                        break
                    try:
                        self.wfile.write(block)
                    except (BrokenPipeError, ConnectionResetError):
                        break
        else:
            self.send_error(404, "Not found")

    def log_message(self, format_string, *args):
        if urlsplit(self.path).path == "/upload":
            print("[%s] %s: upload endpoint request" %
                  (self.log_date_time_string(), self.client_address[0]), flush=True)
            return
        print("[%s] %s: %s" % (
            self.log_date_time_string(), self.client_address[0],
            format_string % args,
        ), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Mac LAN IPv4, e.g. 192.168.1.108")
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--probe-apk", type=Path, default=DEFAULT_PROBE_APK)
    parser.add_argument("--whitelist-apk", type=Path, default=DEFAULT_WHITELIST_APK)
    parser.add_argument("--store-cache-probe-apk", type=Path, default=DEFAULT_STORE_CACHE_PROBE_APK)
    parser.add_argument("--carplay-apk", type=Path, default=DEFAULT_CARPLAY_APK)
    parser.add_argument("--lan-cast-apk", type=Path, default=DEFAULT_LAN_CAST_APK)
    parser.add_argument("--tvbox42-apk", type=Path, default=DEFAULT_TVBOX42_APK)
    parser.add_argument("--tvbox-lowram-apk", type=Path, default=DEFAULT_TVBOX_LOWRAM_APK)
    parser.add_argument("--tvbox-modular-apk", type=Path, default=DEFAULT_TVBOX_MODULAR_APK)
    parser.add_argument("--tvbox-full-apk", type=Path, default=DEFAULT_TVBOX_FULL_APK)
    parser.add_argument("--tvbox-lite-apk", type=Path, default=DEFAULT_TVBOX_LITE_APK)
    parser.add_argument("--x5-tbs-apk", type=Path, default=DEFAULT_X5_TBS_APK)
    parser.add_argument("--state-dir", type=Path, help="private paired-client and report storage")
    args = parser.parse_args()
    ProbeHandler.apk_path = args.apk.resolve()
    ProbeHandler.probe_apk_path = args.probe_apk.resolve()
    ProbeHandler.whitelist_apk_path = args.whitelist_apk.resolve()
    ProbeHandler.store_cache_probe_apk_path = args.store_cache_probe_apk.resolve()
    ProbeHandler.carplay_apk_path = args.carplay_apk.resolve()
    ProbeHandler.lan_cast_apk_path = args.lan_cast_apk.resolve()
    ProbeHandler.tvbox42_apk_path = args.tvbox42_apk.resolve()
    ProbeHandler.tvbox_lowram_apk_path = args.tvbox_lowram_apk.resolve()
    ProbeHandler.tvbox_modular_apk_path = args.tvbox_modular_apk.resolve()
    ProbeHandler.tvbox_full_apk_path = args.tvbox_full_apk.resolve()
    ProbeHandler.tvbox_lite_apk_path = args.tvbox_lite_apk.resolve()
    ProbeHandler.x5_tbs_apk_path = args.x5_tbs_apk.resolve()
    server = ThreadingHTTPServer((args.host, args.port), ProbeHandler)
    server.daemon_threads = True
    server.report_store = ReportStore(args.state_dir) if args.state_dir else None
    print("Car installation diagnostic page: http://%s:%d/" %
          (args.host, args.port), flush=True)
    print("APK: %s (found: %s)" %
          (ProbeHandler.apk_path, ProbeHandler.apk_path.is_file()), flush=True)
    print("Probe APK: %s (found: %s)" %
          (ProbeHandler.probe_apk_path, ProbeHandler.probe_apk_path.is_file()), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
