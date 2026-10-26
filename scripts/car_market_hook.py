#!/usr/bin/env python3
"""One-command, dual-route XYGala market lab for an owned Android 4.2 car unit.

The server prepares BOTH routes: Wi-Fi HTTP proxy and router DNS interception.
It never changes the OEM APK, the downloaded APK, a router, or Android settings.
Router DNS and privileged TCP/80 remain explicit, user-controlled steps.
"""

import argparse
import hashlib
import http.client
import ipaddress
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import signal
import socket
import subprocess
import threading
from urllib.parse import urlsplit

if __package__:
    from .car_market_bridge import Catalog, DiagnosticDumpStore, MarketHTTP, MarketTCP, ThreadingHTTPServer, ThreadingTCP
    from .car_market_proxy import CarProxyHandler, ThreadingProxy
else:
    from car_market_bridge import Catalog, DiagnosticDumpStore, MarketHTTP, MarketTCP, ThreadingHTTPServer, ThreadingTCP
    from car_market_proxy import CarProxyHandler, ThreadingProxy


LOG = logging.getLogger("car-market-hook")
DEFAULT_APK = Path("/Users/hick/github/DiPlay/car-market-kit/APKs/tvbox-android42-full-native-v5.apk")
DEFAULT_DIAGNOSTIC_APK = Path(
    "/Users/hick/github/DiPlay/car-market-kit/APKs/car-probe-whitelist-title-navi-v11.apk"
)
DEFAULT_LAN_CAST_APK = Path(
    "/Users/hick/github/DiPlay/car-market-kit/APKs/lan-cast-android42-v4.apk"
)
DEFAULT_LOG_DIR = Path("/Users/hick/github/DiPlay/car-market-kit/logs")


def detect_lan(interface):
    try:
        result = subprocess.run(["ipconfig", "getifaddr", interface],
                                capture_output=True, text=True, timeout=3, check=True)
        address = ipaddress.IPv4Address(result.stdout.strip())
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        raise ValueError("无法检测 {} 的 IPv4；请指定 --lan-ip: {}".format(interface, exc)) from exc
    return str(address)


def trusted_network(address, interface):
    try:
        result = subprocess.run(["ipconfig", "getoption", interface, "subnet_mask"],
                                capture_output=True, text=True, timeout=3, check=True)
        mask = result.stdout.strip()
        return ipaddress.ip_network("{}/{}".format(address, mask), strict=False)
    except (OSError, ValueError, subprocess.SubprocessError):
        LOG.warning("无法读取子网掩码；仅使用 /24 作为后备，必要时用 --allow-subnet 明确指定")
        return ipaddress.ip_network("{}/24".format(address), strict=False)


def validated_lan(address):
    ip = ipaddress.ip_address(address)
    if ip.version != 4 or not ip.is_private or ip.is_loopback or ip.is_link_local:
        raise ValueError("必须使用 Mac 在车机测试 LAN 上的私有 IPv4，不能是公网或回环地址")
    return str(ip)


class HookServerBundle:
    """Three independently testable listeners sharing one original-APK catalog."""

    def __init__(self, catalog, bind, http_port, tcp_port, proxy_port,
                 allowed_clients, allowed_subnets=(), diagnostic_apk=None,
                 additional_apks=None, dump_dir=None):
        self.http = self.tcp = self.proxy = None
        self.workers = []
        try:
            self.http = ThreadingHTTPServer((bind, http_port), MarketHTTP)
            self.tcp = ThreadingTCP((bind, tcp_port), MarketTCP)
            self.proxy = ThreadingProxy((bind, proxy_port), CarProxyHandler)
            catalog.http_port = self.http.server_port
            catalog.tcp_port = self.tcp.server_address[1]
            self.http.catalog = self.tcp.catalog = catalog
            self.http.dump_store = DiagnosticDumpStore(dump_dir) if dump_dir else None
            self.http.dump_clients = set(allowed_clients) | {catalog.host, "127.0.0.1"}
            self.http.dump_subnets = tuple(allowed_subnets)
            if diagnostic_apk is not None:
                diagnostic_apk = Path(diagnostic_apk).resolve(strict=True)
                if (not diagnostic_apk.is_file() or diagnostic_apk.suffix.lower() != ".apk"
                        or not 512 <= diagnostic_apk.stat().st_size <= 150 * 1024 * 1024):
                    raise ValueError("诊断包须为不超过 150MiB 的有效 .apk 文件")
            self.http.diagnostic_apk = diagnostic_apk
            self.http.additional_apks = {}
            for route, (apk, label) in (additional_apks or {}).items():
                if route not in ("/diplay-android42-toolbar-voice-v12.apk",
                                 "/lan-cast-android42.apk"):
                    raise ValueError("不支持的额外下载路径：{}".format(route))
                validated = Path(apk).resolve(strict=True)
                if (not validated.is_file() or validated.suffix.lower() != ".apk"
                        or not 512 <= validated.stat().st_size <= 150 * 1024 * 1024):
                    raise ValueError("额外下载必须是有效 .apk 文件且不超过 150MiB")
                self.http.additional_apks[route] = (validated, label)
            self.proxy.market_override = "{}:{}".format(catalog.host, catalog.tcp_port)
            self.proxy.allowed_clients = set(allowed_clients) | {catalog.host, "127.0.0.1"}
            self.proxy.allowed_subnets = tuple(allowed_subnets)
        except BaseException:
            self.close(started=False)
            raise

    @property
    def ports(self):
        return (self.http.server_port, self.tcp.server_address[1],
                self.proxy.server_address[1])

    def start(self):
        for server in (self.http, self.tcp, self.proxy):
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            self.workers.append(worker)

    def close(self, started=True):
        for server in (self.http, self.tcp, self.proxy):
            if server is not None:
                if started:
                    server.shutdown()
                server.server_close()
        for worker in self.workers:
            worker.join(timeout=3)


def check_servers(address, http_port, tcp_port, proxy_port, diagnostic=False,
                  additional_routes=()):
    """Read-only network smoke test; returns the catalog row count on success."""
    connection = http.client.HTTPConnection(address, http_port, timeout=3)
    try:
        connection.request("GET", "/host.php")
        reply = connection.getresponse()
        body = reply.read()
        assert reply.status == 200, "host.php returned {}".format(reply.status)
        discovered = json.loads(body)["app_market"]
        expected = "{}:{}".format(address, tcp_port)
        assert discovered == expected, "unexpected market address: {}".format(discovered)
    finally:
        connection.close()

    with socket.create_connection((address, tcp_port), timeout=3) as connection:
        connection.settimeout(3)
        with connection.makefile("rwb") as stream:
            stream.write(b'{"type":"get_access_token"}\n')
            stream.flush()
            token = json.loads(stream.readline())["access_token"]
            request = {"type": "get_app", "access_token": token,
                       "factory": 5, "category": 0, "page": 1, "page_size": 8}
            stream.write(json.dumps(request).encode() + b"\n")
            stream.flush()
            apps = json.loads(stream.readline())
            assert apps["code"] == 0 and apps["item_apps"], "empty market catalog"

    proxied = http.client.HTTPConnection(address, proxy_port, timeout=3)
    try:
        proxied.request("GET", "http://www.xygala.com/host.php")
        reply = proxied.getresponse()
        body = reply.read()
        assert reply.status == 200, "proxy returned {}".format(reply.status)
        assert json.loads(body)["app_market"] == expected, "proxy hook did not rewrite discovery"
    finally:
        proxied.close()

    apk_url = urlsplit(apps["item_apps"][0]["apk"])
    downloader = http.client.HTTPConnection(apk_url.hostname, apk_url.port, timeout=3)
    try:
        downloader.request("HEAD", apk_url.path)
        reply = downloader.getresponse()
        reply.read()
        assert reply.status == 200 and int(reply.getheader("Content-Length")) > 0
    finally:
        downloader.close()
    if diagnostic:
        diagnostic_download = http.client.HTTPConnection(address, http_port, timeout=3)
        try:
            diagnostic_download.request("HEAD", "/car-probe-settings.apk")
            reply = diagnostic_download.getresponse()
            reply.read()
            assert reply.status == 200 and int(reply.getheader("Content-Length")) > 512
        finally:
            diagnostic_download.close()
    for route in additional_routes:
        extra = http.client.HTTPConnection(address, http_port, timeout=3)
        try:
            extra.request("HEAD", route)
            reply = extra.getresponse()
            reply.read()
            assert reply.status == 200 and int(reply.getheader("Content-Length")) > 512
        finally:
            extra.close()
    return len(apps["item_apps"])


def check_exact_apk(address, port, route, expected_path):
    """Detect a stale service still serving an older APK at a stable download URL."""
    expected = hashlib.sha256()
    with Path(expected_path).open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            expected.update(block)
    connection = http.client.HTTPConnection(address, port, timeout=15)
    try:
        connection.request("GET", route)
        reply = connection.getresponse()
        assert reply.status == 200, "MFi 下载返回 HTTP {}".format(reply.status)
        assert int(reply.getheader("Content-Length", "0")) == Path(expected_path).stat().st_size, (
            "当前网页 MFi APK 与指定文件的大小不同")
        actual = hashlib.sha256()
        for block in iter(lambda: reply.read(65536), b""):
            actual.update(block)
        assert actual.digest() == expected.digest(), "当前网页 MFi APK 与指定文件的 SHA-256 不同"
    finally:
        connection.close()


def display_setup(address, http_port, tcp_port, proxy_port, network=None, mfi_test=False):
    print("\n  车机 Demo 安装页：http://{}:{}/".format(address, http_port))
    print("\n[方式一] 车机 Wi-Fi HTTP 代理（若原厂 HTTP 客户端遵守系统代理设置）")
    print("  HTTP 代理：{}:{}".format(address, proxy_port))
    print("  原厂 TCP 后端：{}:{}（车机必须可直连）".format(address, tcp_port))
    print("\n[方式二] 可控路由器/热点 DNS（原厂 HTTP 客户端是否使用代理均可）")
    print("  dnsmasq: address=/www.xygala.com/{}".format(address))
    print("  注意！需单独用管理员权限监听 Mac {}:80：".format(address))
    print("  sudo python3 -m scripts.car_market_host80 --bind {} --market-port {}".format(
          address, tcp_port))
    print("  另一选择是路由器把车机 -> {}:80 定向到 {}:{}。".format(
          address, address, http_port))
    print("  必须由路由器向车机下发该 DNS；只改 Mac hosts 文件不生效。")
    print("\n  APK 下载：http://{}:{}/apk/<本次启动生成的随机名称>.apk".format(
          address, http_port))
    print("  诊断 v11：http://{}:{}/car-probe-settings.apk".format(address, http_port))
    print("  Root / 安装限制 Dump 上传：在 v11 填 Mac IP {}，无需验证码，手动点击确认。".format(address))
    print("  ZIP 保存到 car-market-kit/dumps；只在可信隔离 Wi-Fi 中运行接收服务。")
    if mfi_test:
        print("  DiPlay Android Back 自动隐藏 + 语音诊断 v12：http://{}:{}/diplay-android42-toolbar-voice-v12.apk".format(address, http_port))
    print("  局域网投屏：http://{}:{}/lan-cast-android42.apk".format(address, http_port))
    if network:
        print("  HTTP 代理允许的测试网段：{}（只在可信网络启用）".format(network))
    print("  仅原厂市场发起下载；安装器仍需要用户确认和真机验证。\n")


def configure_logging(directory):
    directory.mkdir(parents=True, exist_ok=True)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    stream = logging.StreamHandler()
    stream.setFormatter(formatter)
    file_handler = RotatingFileHandler(directory / "market-hook.log",
                                       maxBytes=2 * 1024 * 1024, backupCount=4)
    file_handler.setFormatter(formatter)
    root.addHandler(stream)
    root.addHandler(file_handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "check", "prepare"), nargs="?", default="run")
    parser.add_argument("--interface", default="en0")
    parser.add_argument("--lan-ip", default="auto")
    parser.add_argument("--bind", default="0.0.0.0",
                        help="监听地址；默认 0.0.0.0，下载地址仍使用 --lan-ip / --interface")
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--diagnostic-apk", type=Path, default=DEFAULT_DIAGNOSTIC_APK)
    parser.add_argument("--mfi-test-apk", type=Path, default=None,
                        help="可选的私有 MFi 实验 APK；不会公开单独的密钥文件")
    parser.add_argument("--lan-cast-apk", type=Path, default=DEFAULT_LAN_CAST_APK)
    parser.add_argument("--name", default="TVBox Android 4.2")
    parser.add_argument("--http-port", type=int, default=18816)
    parser.add_argument("--market-port", type=int, default=18611)
    parser.add_argument("--proxy-port", type=int, default=18081)
    parser.add_argument("--allow-client", action="append", default=[])
    parser.add_argument("--trusted-lan", action="store_true",
                        help="allow the directly attached private LAN subnet to use the HTTP proxy")
    parser.add_argument("--open-demo", action="store_true",
                        help="Mac: launch the diagnostic/demo download page in a browser")
    parser.add_argument("--allow-subnet", action="append", default=[])
    parser.add_argument("--log-dir", type=Path, default=DEFAULT_LOG_DIR)
    parser.add_argument("--dump-dir", type=Path, default=None,
                        help="private local directory for manual diagnostic APK/ODEX dumps")
    args = parser.parse_args(argv)

    def open_demo():
        if not args.open_demo:
            return
        url = "http://{}:{}/".format(address, args.http_port)
        try:
            subprocess.run(["open", url], timeout=5, check=True)
            print("已打开 Demo 安装页面：{}".format(url))
        except (OSError, subprocess.SubprocessError) as error:
            print("无法自动打开浏览器，请手动访问 {}：{}".format(url, error))

    try:
        address = validated_lan(detect_lan(args.interface) if args.lan_ip == "auto"
                                else args.lan_ip)
        bind = str(ipaddress.IPv4Address(args.bind))
        if bind != "0.0.0.0" and bind != address:
            raise ValueError("--bind 只允许 0.0.0.0 或当前 --lan-ip")
        if len(set((args.http_port, args.market_port, args.proxy_port))) != 3:
            raise ValueError("HTTP、市场 TCP、代理必须使用三个不同端口")
        if any(not 1 <= port <= 65535 for port in
               (args.http_port, args.market_port, args.proxy_port)):
            raise ValueError("端口必须在 1～65535 之间")
        subnets = [ipaddress.ip_network(item, strict=False) for item in args.allow_subnet]
        if args.trusted_lan:
            subnets.append(trusted_network(address, args.interface))
        for allowed in args.allow_client:
            ipaddress.ip_address(allowed)
        if args.command != "check":
            catalog = Catalog(args.apk, args.name, address, args.http_port,
                              args.market_port)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))

    if args.command == "check":
        try:
            rows = check_servers(address, args.http_port, args.market_port,
                                 args.proxy_port, diagnostic=args.diagnostic_apk.is_file(),
                                 additional_routes=tuple(route for route, path in (
                                     ("/diplay-android42-toolbar-voice-v12.apk", args.mfi_test_apk),
                                     ("/lan-cast-android42.apk", args.lan_cast_apk),
                                 ) if path is not None and path.is_file()))
            if args.mfi_test_apk is not None:
                check_exact_apk(address, args.http_port,
                                "/diplay-android42-toolbar-voice-v12.apk", args.mfi_test_apk)
        except (OSError, ValueError, KeyError, AssertionError) as exc:
            print("FAIL: 市场 Hook 服务未就绪：{}".format(exc))
            return 1
        print("PASS: HTTP host.php、HTTP 代理、TCP token/目录、APK 下载均正常；应用条目 {} 个".format(rows))
        display_setup(address, args.http_port, args.market_port, args.proxy_port,
                      mfi_test=args.mfi_test_apk is not None)
        open_demo()
        return 0

    if args.command == "prepare":
        print("已验证 APK 文件：{} ({} bytes, SHA-256 {})".format(
              catalog.apk, catalog.size, catalog.sha256))
        display_setup(address, args.http_port, args.market_port, args.proxy_port,
                      ", ".join(map(str, subnets)) if subnets else None,
                      mfi_test=args.mfi_test_apk is not None)
        return 0

    try:
        configure_logging(args.log_dir)
        additional_apks = {
            "/lan-cast-android42.apk": (
                args.lan_cast_apk,
                "下载车机局域网投屏 LAN Cast v4（腾讯 DLNA 路径修复）"),
        }
        if args.mfi_test_apk is not None:
            mfi_label = "下载 DiPlay Android 4.2 v12（顶部操作栏 3 秒自动隐藏，语音诊断）"
            additional_apks["/diplay-android42-toolbar-voice-v12.apk"] = (
                args.mfi_test_apk,
                mfi_label)
        bundle = HookServerBundle(catalog, bind, args.http_port, args.market_port,
                                  args.proxy_port, args.allow_client, subnets,
                                  diagnostic_apk=args.diagnostic_apk,
                                  dump_dir=args.dump_dir or args.log_dir.parent / "dumps",
                                  additional_apks=additional_apks)
    except (OSError, ValueError) as exc:
        parser.error("无法启动市场服务：{}（如端口已使用，先运行 check）".format(exc))
    stop = threading.Event()

    def on_signal(_signal, _frame):
        stop.set()

    previous_term = signal.signal(signal.SIGTERM, on_signal)
    previous_int = signal.signal(signal.SIGINT, on_signal)
    try:
        bundle.start()
        LOG.info("READY bind=%s advertise=%s http=%s market=%s proxy=%s original_apk=%s sha256=%s",
                 bind, address, *bundle.ports, catalog.apk, catalog.sha256)
        if subnets:
            LOG.warning("代理开放至信任网段 %s", ", ".join(map(str, subnets)))
        rows = check_servers(address, *bundle.ports, diagnostic=True,
                             additional_routes=tuple(additional_apks))
        if args.mfi_test_apk is not None:
            check_exact_apk(address, bundle.ports[0],
                            "/diplay-android42-toolbar-voice-v12.apk", args.mfi_test_apk)
        LOG.info("SMOKE PASS app_rows=%s", rows)
        display_setup(address, *bundle.ports,
                      ", ".join(map(str, subnets)) if subnets else None,
                      mfi_test=args.mfi_test_apk is not None)
        open_demo()
        while not stop.wait(1):
            pass
        return 0
    finally:
        signal.signal(signal.SIGTERM, previous_term)
        signal.signal(signal.SIGINT, previous_int)
        bundle.close()


if __name__ == "__main__":
    raise SystemExit(main())
