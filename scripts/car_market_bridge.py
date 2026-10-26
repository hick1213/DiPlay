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
            offered = getattr(self.server, "additional_apks", {})
            apps = []

            def card(category, title, version, package, details, url):
                return (
                    "<article class='app'><p class='category'>{}</p><h3>{}</h3>"
                    "<p class='version'>{} · <code>{}</code></p><p class='description'>{}</p>"
                    "<a class='install' href='{}'>下载 APK</a></article>"
                ).format(*(escape(value, quote=True) for value in
                           (category, title, version, package, details, url)))

            if "/diplay-android42-toolbar-voice-v12.apk" in offered:
                apps.append(card(
                    "无线 CarPlay", "DiPlay", "v12", "sogou.map",
                    "画面/触控/Android 声音输出；FPS 30～60 可选；Back 显示控制栏后闲置 3 秒隐藏；"
                    "恢复前后台画面，包含语音诊断。",
                    "/diplay-android42-toolbar-voice-v12.apk"))

            if "/lan-cast-android42.apk" in offered:
                apps.append(card(
                    "局域网投屏 · DLNA", "LAN Cast", "v5", "com.uu.uunavi",
                    "支持兼容的 Bilibili、腾讯视频 DLNA 投屏；已修复服务声明和状态通知。"
                    "不包含 AirPlay 镜像或 DRM 解码。",
                    "/lan-cast-android42.apk"))

            if getattr(self.server, "diagnostic_apk", None) is not None:
                apps.append(card(
                    "车机维护", "车机深度诊断", "v11", "title.navi",
                    "蓝车图标；系统设置/Wi-Fi 入口、Root/ADB 只读检查、安装限制 Dump，"
                    "可经确认将 ZIP 发送至同局域网的 Mac。",
                    "/car-probe-settings.apk"))

            tvbox_version = (
                "v5 · 2.5.0-native42"
                if catalog.apk.name == "tvbox-android42-full-native-v5.apk"
                else "当前市场测试版")
            apps.append(card(
                "影音播放", catalog.name, tvbox_version, "com.tencent.navsns"
                if tvbox_version.startswith("v5") else "请检查 APK 包名",
                "Android 4.2 适配版影视播放器。支持的片源、功能取决于电视源与设备解码器。",
                "/tvbox-android42.apk"))
            page = (
                "<!doctype html><html lang='zh-CN'><head>"
                "<meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                "<title>车机 Android 4.2 · 最新 APK 下载</title>"
                "<style>"
                "body{{font-family:-apple-system,BlinkMacSystemFont,'Noto Sans SC',sans-serif;"
                "background:#f6f8fb;color:#18212e;max-width:680px;margin:0 auto;padding:22px 16px;"
                "line-height:1.55}}h1{{font-size:25px;margin:0 0 7px}}"
                "h2{{font-size:19px;margin:24px 0 8px}}h3{{font-size:20px;margin:0}}"
                ".intro,.note{{color:#536171;font-size:14px}}.apps{{display:grid;gap:14px}}"
                ".app{{background:white;border:1px solid #d9e0e8;border-radius:12px;padding:17px}}"
                ".category{{font-size:13px;font-weight:600;color:#375f8d;margin:0 0 6px}}"
                ".version{{margin:4px 0 7px;font-size:13px;color:#687488}}"
                ".description{{font-size:14px;margin:7px 0 14px}}"
                ".install{{display:block;text-align:center;background:#175d9c;color:#fff;"
                "border-radius:8px;padding:12px;text-decoration:none;font-size:16px;font-weight:600}}"
                "code{{font-size:12px}}.info{{margin-top:22px;border-top:1px solid #d9e0e8}}"
                "</style></head><body>"
                "<h1>车机 Android 4.2 · 最新安装包</h1>"
                "<p class='intro'>当前可下载 {} 个应用。仅在自己的可信局域网使用；"
                "点击下载后，仍需由设备系统安装器确认。</p>"
                "<main><section class='apps'>{}</section></main>"
                "<section class='info'><h2>安装及诊断说明</h2>"
                "<p class='note'>① Mac 与车机处于同一局域网；② 下载对应 APK，核对包名、"
                "版本及签名后按系统提示安装；③ 如果原车提示替换不认识的应用，请取消。</p>"
                "<p class='note'>诊断 v11 可在“安装限制 Dump”中手动导出 ZIP，"
                "填写 Mac 当前 IP 后确认上传到 car-market-kit/dumps。"
                "Root / Wi-Fi ADB 需要设备已有授权，不能默认安装任意 APK。</p>"
                "<p class='note'>DiPlay 使用私有测试身份材料，仅供当前授权测试。"
                "不代表获得苹果官方 MFi 认证。LAN Cast v5 是 DLNA 接收器，不包含 AirPlay。</p>"
                "<p class='note'>若安装失败，可把提示和诊断报告带回排查；"
                "旧版和无身份 DiPlay 基线不在本页展示。</p>"
                "</section></body></html>"
            ).format(len(apps), "".join(apps)).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            if send_body:
                self.wfile.write(page)
            return
        if route in (catalog.apk_path, "/tvbox-android42.apk"):
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
