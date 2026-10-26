#!/usr/bin/env python3
"""Opt-in local emulation of the Android 4.2 XYGala car app market.

This is a *lab server*, not a transparent Internet interception tool.  It
publishes one market catalog APK and optional browser-download APKs unchanged. HTTP /host.php
and the line-oriented TCP catalog are intentionally separate listeners because
the vendor application uses both protocols.

The default bind is loopback.  To use a vehicle on an authorized test LAN,
explicitly supply --bind 0.0.0.0 and --advertise-host YOUR_LAN_IP, then make
your own hotspot/router resolve only www.xygala.com to that LAN IP.
"""

import argparse
import hashlib
from html import escape
import ipaddress
import json
import logging
import os
import secrets
import socketserver
import threading
import uuid
import zipfile
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


LOG = logging.getLogger("car-market-bridge")
ALLOWED_TYPES = {"get_app", "get_hot_app", "get_new_app", "get_boutique_app", "search"}
MAX_APK_SIZE = 150 * 1024 * 1024
MAX_DIAGNOSTIC_DUMP = 60 * 1024 * 1024


class DiagnosticDumpStore:
    """Opt-in local dump inbox, only enabled by the Mac test Hook bundle.

    The user requested no pairing code for an isolated temporary Wi-Fi. Limit
    callers to the configured LAN and retain files without ever extracting ZIPs.
    """

    def __init__(self, directory):
        self.root = Path(directory)
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()

    def receive(self, source, length):
        if not 0 < length <= MAX_DIAGNOSTIC_DUMP:
            raise ValueError("dump exceeds 60 MiB")
        with self.lock:
            existing = [path for path in self.root.glob("car-app-dump-*.zip") if path.is_file()]
            used = sum(path.stat().st_size for path in existing)
            if len(existing) >= 20 or used + length > 512 * 1024 * 1024:
                raise OSError("dump inbox storage cap reached")
            dump_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:10]
            pending = self.root / (".car-app-dump-" + dump_id + ".part")
            destination = self.root / ("car-app-dump-" + dump_id + ".zip")
            digest = hashlib.sha256()
            try:
                fd = os.open(pending, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, "wb") as stream:
                    remaining = length
                    while remaining:
                        block = source.read(min(65536, remaining))
                        if not block:
                            raise ValueError("incomplete upload")
                        stream.write(block)
                        digest.update(block)
                        remaining -= len(block)
                if not zipfile.is_zipfile(pending):
                    raise ValueError("not a valid ZIP archive")
                os.replace(pending, destination)
                return {"ok": True, "id": dump_id, "sha256": digest.hexdigest(),
                        "size": length}
            except BaseException:
                pending.unlink(missing_ok=True)
                raise


class Catalog:
    def __init__(self, apk, name, advertise_host, http_port, tcp_port):
        self.apk = Path(apk).resolve(strict=True)
        if not self.apk.is_file() or self.apk.suffix.lower() != ".apk":
            raise ValueError("--apk must identify an existing .apk regular file")
        self.size = self.apk.stat().st_size
        if not 512 <= self.size <= MAX_APK_SIZE:
            raise ValueError("APK must be between 512 bytes and 150 MiB")
        digest = hashlib.sha256()
        with self.apk.open("rb") as stream:
            while True:
                block = stream.read(65536)
                if not block:
                    break
                digest.update(block)
        self.sha256 = digest.hexdigest()
        self.name = name.strip()[:70]
        if not self.name:
            raise ValueError("--name cannot be empty")
        if "/" in advertise_host or ":" in advertise_host or not advertise_host:
            raise ValueError("--advertise-host must be a plain IPv4 address or hostname")
        self.host = advertise_host
        self.http_port = http_port
        self.tcp_port = tcp_port
        self.token = secrets.token_urlsafe(20)
        self.apk_path = "/apk/" + secrets.token_urlsafe(16) + ".apk"

    def host_config(self):
        return {"app_market": "{}:{}".format(self.host, self.tcp_port)}

    def application(self):
        return {
            "id": 424242, "name": self.name, "category_id": 2,
            "icon": "", "star_level": 5, "size": self.size,
            "provider": "Local test APK", "images": "",
            "introduce": "Local user-provided test package. Original APK bytes preserved.",
            "boutique": 1, "download_count": 0, "comment_count": 0,
            "status": 1, "order": 100, "create_time": "2026-10-25",
            "apk": "http://{}:{}{}".format(self.host, self.http_port, self.apk_path),
        }

    def reply(self, request):
        action = request.get("type")
        if action == "get_access_token":
            return {"type": action, "access_token": self.token}
        if action not in ALLOWED_TYPES:
            return {"type": action, "code": 1, "total": 0, "item_apps": []}
        if request.get("access_token") != self.token:
            return {"type": action, "code": 1, "total": 0, "item_apps": []}
        page = request.get("page", 1)
        query = str(request.get("query", "")).casefold()
        visible = (isinstance(page, int) and page == 1 and
                   (action != "search" or query in self.name.casefold()))
        apps = [self.application()] if visible else []
        return {"type": action, "code": 0, "total": len(apps), "item_apps": apps}


class MarketHTTP(BaseHTTPRequestHandler):
    server_version = "CarMarketLab/1.0"

    def do_POST(self):
        route = urlsplit(self.path).path
        store = getattr(self.server, "dump_store", None)
        if route != "/api/diagnostic-dump" or store is None:
            self.send_error(404, "Dump receiver not enabled")
            return
        source_ip = ipaddress.ip_address(self.client_address[0])
        allowed = (self.client_address[0] in getattr(self.server, "dump_clients", set())
                   or any(source_ip in subnet for subnet in
                          getattr(self.server, "dump_subnets", ())))
        if not allowed:
            self.send_error(403, "Client not in trusted test LAN")
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            content_length = 0
        if not 0 < content_length <= MAX_DIAGNOSTIC_DUMP:
            self.send_error(413, "Invalid dump size or missing Content-Length")
            return
        if self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/zip":
            self.send_error(415, "Expected application/zip")
            return
        self.connection.settimeout(45)
        try:
            saved = store.receive(self.rfile, content_length)
        except (ValueError, OSError, TimeoutError) as error:
            LOG.warning("DUMP_REJECTED client=%s reason=%s",
                        self.client_address[0], error)
            body = json.dumps({"ok": False, "error": str(error)}).encode()
            self.send_response(400 if isinstance(error, ValueError) else 507)
        else:
            LOG.info("DUMP_RECEIVED client=%s id=%s bytes=%s",
                     self.client_address[0], saved["id"], saved["size"])
            body = json.dumps(saved).encode()
            self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.respond(send_body=True)

    def do_HEAD(self):
        self.respond(send_body=False)

    def respond(self, send_body):
        catalog = self.server.catalog
        route = urlsplit(self.path).path
        if route == "/host.php":
            content = json.dumps(catalog.host_config(), separators=(",", ":")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if send_body:
                self.wfile.write(content)
            return
        if route in ("/", "/index.html"):
            latest_route = "/diplay-android42-toolbar-voice-v12.apk"
            offered = getattr(self.server, "additional_apks", {})
            latest = offered.get(latest_route)
            latest_link = (
                "<hr><h3>当前测试版：DiPlay Android 4.2 v12（Back 控制栏 3 秒自动隐藏 + 高德语音诊断）</h3>"
                "<a class='install' href='{}'>{}</a>"
                "<p>连接成功并显示画面后自动隐藏顶部操作栏；按一次 Back 显示操作栏，再按一次 Back 退出。"
                "点击顶部状态文字可验证 Android 输出，查看 iPhone 音频协商、主音频资源、实际音频包和 Android 播放结果。"
                "断开后等待旧连接释放完成再重连，避免 Wi-Fi Direct 群组被清理线程误关。"
                "H.264 解码器等待首帧缓冲区，若持续无画面会向 iPhone 请求新关键帧。"
                "保留 v9 的音频与 v10 的低延迟触摸；顶部新增 FPS 选择（30 至 60 FPS，每档 5 FPS），"
                "保存后在下一次连接生效。后台返回前台后重绑 Surface 并主动请求 H.264 关键帧，避免黑屏。"
                "Back 显示操作栏后 3 秒不触碰自动隐藏；操作或弹窗期间不隐藏，关闭弹窗后重新计时。"
                "新增 CarPlay 语音 appStates/speechMode 状态日志，便于排查高德语音按钮。"
                "安装时确认包名 sogou.map；如提示替换不认识的原厂应用，请取消。</p>"
            ).format(escape(latest_route, quote=True), escape(latest[1])) if latest else ""
            extra_links = "".join(
                "<a class='install' href='{}'>{}</a>".format(
                    escape(extra_route, quote=True), escape(label))
                for extra_route, (_, label) in sorted(
                    offered.items()) if extra_route != latest_route
            )
            page = ("<!doctype html><html lang='zh-CN'><head>"
                    "<meta charset='utf-8'><meta name='viewport' "
                    "content='width=device-width,initial-scale=1'>"
                    "<title>车机 Android 4.2 安装 Demo</title>"
                    "<style>body{{font-family:sans-serif;max-width:640px;margin:18px;"
                    "line-height:1.7}}a.install{{display:block;background:#1462a5;"
                    "color:white;padding:14px;text-align:center;margin:18px 0;"
                    "text-decoration:none;font-size:20px}}</style></head><body>"
                    "<h2>车机 Android 4.2 安装 Demo</h2>"
                    "<p>第一步：下载诊断 v11；安装或升级后，打开‘读取车辆信息’"
                    "页面顶部的系统设置 / Wi-Fi 按钮，配置代理。</p>"
                    "<a class='install' href='/car-probe-settings.apk'>"
                    "下载车机诊断 v11（蓝车图标 / Root / 安装限制）</a>"
                    "<p>v11 新增蓝车图标和手动创建桌面快捷方式按钮；"
                    "保留系统设置和 Wi-Fi 快捷入口，可手动测试 Root 授权，"
                    "仅在确认后尝试开启或关闭 Wi-Fi ADB。普通权限无法保证启用 ADB，"
                    "也无法直接突破原厂安装白名单。</p>"
                    "<p>新增‘安装限制 Dump’：手动勾选原厂商店、安装器、"
                    "系统设置和桌面，生成 ZIP，并记录安装 Intent 处理者、"
                    "权限、安装来源、Root/SELinux/分区线索及可读取的系统框架；"
                    "填写本页地址中的 Mac IP 后，"
                    "可再次确认并上传到 Mac 的 car-market-kit/dumps。"
                    "仅在自己的可信局域网使用，不要求验证码。</p>"
                    "<p>不要替换不认识的原厂同名应用。下载不等于安装成功，"
                    "安装仍由车机系统确认。</p>"
                    "{}"
                    "<hr><p>其他工具和无身份基线包（仅供诊断）：</p>"
                    "<a class='install' href='{}'>{}</a>{}"
                    "<p>DiPlay 是针对 Android 4.2 和原厂安装包名白名单准备的验证版。"
                    "MFi 实验包仅供本人在可信局域网使用，包含测试身份材料；"
                    "尚未证明苹果 MFi 认证或无线 CarPlay 可以完成连接。"
                    "若提示替换不认识的原厂应用，请取消。</p>"
                    "<p>第三步：在原厂应用市场内观察列表、下载与安装结果。"
                    "本页仅供你自己的局域网测试。</p></body></html>".format(
                        latest_link,
                        escape(catalog.apk_path, quote=True),
                        escape("下载 " + catalog.name + " 测试 APK"), extra_links)).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            if send_body:
                self.wfile.write(page)
            return
        if route == catalog.apk_path:
            apk_file = catalog.apk
            size = catalog.size
        elif route == "/car-probe-settings.apk" and getattr(self.server, "diagnostic_apk", None):
            apk_file = self.server.diagnostic_apk
            size = apk_file.stat().st_size
        elif route in getattr(self.server, "additional_apks", {}):
            apk_file = self.server.additional_apks[route][0]
            size = apk_file.stat().st_size
        else:
            self.send_error(404, "Only /host.php and the prepared APKs are served")
            return
        start, end = 0, size - 1
        requested = self.headers.get("Range", "")
        if requested:
            try:
                spec = requested.removeprefix("bytes=")
                if not requested.startswith("bytes=") or "," in spec:
                    raise ValueError("unsupported range")
                first, sep, last = spec.partition("-")
                if not sep:
                    raise ValueError("malformed range")
                if not first:
                    n = int(last)
                    if n <= 0:
                        raise ValueError("empty suffix")
                    start = max(0, size - n)
                else:
                    start = int(first)
                    end = min(size - 1, int(last)) if last else size - 1
                if start < 0 or start > end or start >= size:
                    raise ValueError("out of range")
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */{}".format(size))
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        self.send_response(206 if requested else 200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        if requested:
            self.send_header("Content-Range", "bytes {}-{}/{}".format(start, end, size))
        self.end_headers()
        if send_body:
            with apk_file.open("rb") as stream:
                stream.seek(start)
                remaining = end - start + 1
                while remaining:
                    block = stream.read(min(65536, remaining))
                    if not block:
                        break
                    self.wfile.write(block)
                    remaining -= len(block)

    def log_message(self, fmt, *args):
        LOG.info("HTTP client=%s %s", self.client_address[0], fmt % args)


class MarketTCP(socketserver.StreamRequestHandler):
    timeout = 20

    def handle(self):
        LOG.info("TCP CONNECT client=%s", self.client_address[0])
        while True:
            line = self.rfile.readline(65537)
            if not line:
                break
            if len(line) > 65536:
                break
            try:
                request = json.loads(line)
                if not isinstance(request, dict):
                    break
                response = self.server.catalog.reply(request)
                self.wfile.write(json.dumps(response, ensure_ascii=False,
                                            separators=(",", ":")).encode() + b"\n")
                self.wfile.flush()
                LOG.info("TCP client=%s action=%r rows=%s", self.client_address[0],
                         request.get("type"), len(response.get("item_apps", [])))
            except (ValueError, OSError):
                break


class ThreadingTCP(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve(catalog, bind, http_port, tcp_port):
    http = ThreadingHTTPServer((bind, http_port), MarketHTTP)
    tcp = ThreadingTCP((bind, tcp_port), MarketTCP)
    http.catalog = tcp.catalog = catalog
    worker = threading.Thread(target=http.serve_forever, name="market-http", daemon=True)
    worker.start()
    LOG.info("HTTP=%s:%s TCP=%s:%s app=%s size=%s sha256=%s", bind,
             http.server_port, bind, tcp.server_address[1], catalog.name,
             catalog.size, catalog.sha256)
    LOG.info("Only change DNS for www.xygala.com in your own test hotspot/router")
    try:
        tcp.serve_forever()
    finally:
        http.shutdown()
        http.server_close()
        tcp.server_close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--name", default="Android 4.2 test APK")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--advertise-host", default="127.0.0.1")
    parser.add_argument("--http-port", type=int, default=18806)
    parser.add_argument("--tcp-port", type=int, default=18601)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    catalog = Catalog(args.apk, args.name, args.advertise_host,
                      args.http_port, args.tcp_port)
    serve(catalog, args.bind, args.http_port, args.tcp_port)


if __name__ == "__main__":
    main()
