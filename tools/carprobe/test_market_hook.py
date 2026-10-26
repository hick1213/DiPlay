"""End-to-end loopback tests for both preconfigured network Hook routes."""

import http.client
import io
import json
import socket
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path

from scripts.car_market_bridge import Catalog, ThreadingHTTPServer
from scripts.car_market_hook import (HookServerBundle, check_servers,
                                     check_discovery, DISCOVERY_QUERY, DISCOVERY_RESPONSE)
from scripts.car_market_host80 import DiscoveryHandler


class HookBundleTest(unittest.TestCase):
    def test_one_process_provides_proxy_and_download_catalog(self):
        with tempfile.TemporaryDirectory(prefix="car-market-hook-") as directory:
            apk = Path(directory) / "unaltered-thirdparty.apk"
            original = b"PK\x03\x04" + b"one original APK byte stream" * 50
            apk.write_bytes(original)
            catalog = Catalog(apk, "Original Third Party", "127.0.0.1", 0, 0)
            bundle = HookServerBundle(catalog, "127.0.0.1", 0, 0, 0,
                                      ["127.0.0.1"])
            try:
                bundle.start()
                self.assertEqual(1, check_servers(
                    "127.0.0.1", *bundle.ports, additional_routes=("/tvbox-android42.apk",)))
                self.assertTrue(check_discovery("127.0.0.1", bundle.discovery.server_address[1]))
                self.assertEqual(original, apk.read_bytes())
                conn = http.client.HTTPConnection("127.0.0.1", bundle.ports[0], timeout=3)
                try:
                    conn.request("GET", "/tvbox-android42.apk")
                    reply = conn.getresponse()
                    self.assertEqual(200, reply.status)
                    self.assertEqual(original, reply.read())
                    conn.request("GET", "/")
                    reply = conn.getresponse()
                    page = reply.read().decode("utf-8")
                    self.assertIn("当前可下载 1 个应用", page)
                    self.assertNotIn("href='/diplay-android42-toolbar-voice-v12.apk'", page)
                    self.assertNotIn("href='/lan-cast-android42.apk'", page)
                finally:
                    conn.close()
            finally:
                bundle.close()

    def test_udp_auto_discovery_nonce_and_unknown_requests(self):
        with tempfile.TemporaryDirectory(prefix="car-diagnostic-discovery-") as directory:
            file = Path(directory) / "tvbox.apk"
            file.write_bytes(b"PK\\x03\\x04" + b"test" * 150)
            catalog = Catalog(file, "test", "127.0.0.1", 0, 0)
            bundle = HookServerBundle(catalog, "127.0.0.1", 0, 0, 0,
                                      ["127.0.0.1"])
            try:
                bundle.start()
                port = bundle.discovery.server_address[1]
                self.assertTrue(check_discovery("127.0.0.1", port))
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                    client.settimeout(0.25)
                    client.sendto(b"UNKNOWN_DISCOVERY_REQUEST", ("127.0.0.1", port))
                    with self.assertRaises(socket.timeout):
                        client.recvfrom(256)
                    client.sendto(b"DIPLAY_HEADUNIT_LOG_DISCOVER_V1:invalid",
                                  ("127.0.0.1", port))
                    with self.assertRaises(socket.timeout):
                        client.recvfrom(256)
            finally:
                bundle.close()

    def test_dynamic_lan_restriction_applies_to_discovery_and_uploaded_zip(self):
        """Test address changes without requiring a fixed IP or leaking uploads."""
        with tempfile.TemporaryDirectory(prefix="car-dynamic-lan-") as directory:
            base = Path(directory)
            tvbox = base / "tvbox.apk"
            tvbox.write_bytes(b"PK\\x03\\x04" + b"test" * 150)
            catalog = Catalog(tvbox, "test", "127.0.0.1", 0, 0)
            allowed = [True]
            bundle = HookServerBundle(catalog, "127.0.0.1", 0, 0, 0, [],
                                      dump_dir=base / "dumps",
                                      live_lan_check=lambda address: allowed[0])
            try:
                bundle.start()
                # Force *only* the live-network callback to grant access.
                bundle.http.dump_clients.clear()
                bundle.http.dump_subnets = ()
                bundle.discovery.allowed_clients.clear()
                bundle.discovery.allowed_subnets = ()
                self.assertTrue(check_discovery("127.0.0.1", bundle.discovery.server_address[1]))

                payload = io.BytesIO()
                with zipfile.ZipFile(payload, "w") as archive:
                    archive.writestr("events.log", "MANUAL_REPORT LAN Cast")
                conn = http.client.HTTPConnection("127.0.0.1", bundle.ports[0], timeout=2)
                try:
                    conn.request("POST", "/api/diagnostic-dump", body=payload.getvalue(),
                                 headers={"Content-Type": "application/zip"})
                    response = conn.getresponse()
                    self.assertEqual(200, response.status)
                    response.read()
                    self.assertEqual(1, len(list((base / "dumps").glob("car-app-dump-*.zip"))))
                    allowed[0] = False
                    conn.request("POST", "/api/diagnostic-dump", body=payload.getvalue(),
                                 headers={"Content-Type": "application/zip"})
                    response = conn.getresponse()
                    self.assertEqual(403, response.status)
                    response.read()
                finally:
                    conn.close()
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as discovery:
                    discovery.settimeout(0.25)
                    nonce = b"0123456789abcdef"
                    discovery.sendto(b"DIPLAY_HEADUNIT_LOG_DISCOVER_V1:" + nonce,
                                     ("127.0.0.1", bundle.discovery.server_address[1]))
                    with self.assertRaises(socket.timeout):
                        discovery.recvfrom(256)
            finally:
                bundle.close()

    def test_dns_port80_handler_is_exact_host_only(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), DiscoveryHandler)
        server.market_host = "192.168.31.209:18611"
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        try:
            conn.request("GET", "/host.php", headers={"Host": "www.xygala.com"})
            reply = conn.getresponse()
            self.assertEqual(200, reply.status)
            self.assertEqual("192.168.31.209:18611", json.loads(reply.read())["app_market"])

            conn.request("GET", "/other.php", headers={"Host": "www.xygala.com"})
            reply = conn.getresponse()
            self.assertEqual(404, reply.status)
            reply.read()

            conn.request("GET", "/host.php", headers={"Host": "another.example"})
            reply = conn.getresponse()
            self.assertEqual(404, reply.status)
            reply.read()
        finally:
            conn.close()

    def test_optional_settings_apk_is_served_unchanged(self):
        with tempfile.TemporaryDirectory(prefix="car-market-diagnostic-") as directory:
            base = Path(directory)
            original = b"PK\x03\x04" + (b"diagnostic package bytes" * 100)
            tvbox = base / "tvbox.apk"
            diagnostic = base / "diagnostic.apk"
            cast = base / "cast.apk"
            current = base / "diplay-v12.apk"
            tvbox.write_bytes(b"PK\x03\x04" + b"main package" * 100)
            diagnostic.write_bytes(original)
            cast.write_bytes(b"PK\x03\x04" + b"cast" * 200)
            current.write_bytes(b"PK\x03\x04" + b"current" * 200)
            catalog = Catalog(tvbox, "TVBox", "127.0.0.1", 0, 0)
            bundle = HookServerBundle(catalog, "127.0.0.1", 0, 0, 0,
                                      ["127.0.0.1"], diagnostic_apk=diagnostic,
                                      additional_apks={
                                          "/lan-cast-android42.apk": (cast, "下载 LAN Cast"),
                                          "/diplay-android42-toolbar-voice-v12.apk": (current, "下载 Back 自动隐藏 v12"),
                                      })
            try:
                bundle.start()
                self.assertEqual(1, check_servers("127.0.0.1", *bundle.ports,
                                                  diagnostic=True,
                                                  additional_routes=(
                                                      "/tvbox-android42.apk",
                                                      "/diplay-android42-toolbar-voice-v12.apk",
                                                      "/lan-cast-android42.apk")))
                conn = http.client.HTTPConnection("127.0.0.1", bundle.http.server_port)
                try:
                    conn.request("GET", "/")
                    reply = conn.getresponse()
                    self.assertEqual(200, reply.status)
                    page = reply.read().decode("utf-8")
                    self.assertIn("车机 Android 4.2 · 最新安装包", page)
                    self.assertIn("当前可下载 4 个应用", page)
                    self.assertIn("DiPlay", page)
                    self.assertIn("LAN Cast", page)
                    self.assertIn("车机深度诊断", page)
                    self.assertIn("TVBox", page)
                    self.assertIn("sogou.map", page)
                    self.assertIn("com.uu.uunavi", page)
                    self.assertIn("title.navi", page)
                    self.assertIn("car-market-kit/dumps", page)
                    self.assertIn("href='/car-probe-settings.apk'", page)
                    self.assertNotIn("href='/diplay-android42.apk'", page)
                    self.assertIn("href='/diplay-android42-toolbar-voice-v12.apk'", page)
                    self.assertNotIn("diplay-android42-mfi-fix2.apk", page)
                    self.assertIn("href='/lan-cast-android42.apk'", page)
                    self.assertIn("href='/tvbox-android42.apk'", page)
                    self.assertLess(page.index("href='/diplay-android42-toolbar-voice-v12.apk'"),
                                    page.index("href='/lan-cast-android42.apk'"))
                    self.assertLess(page.index("href='/lan-cast-android42.apk'"),
                                    page.index("href='/car-probe-settings.apk'"))
                    self.assertLess(page.index("href='/car-probe-settings.apk'"),
                                    page.index("href='/tvbox-android42.apk'"))
                    conn.request("GET", "/car-probe-settings.apk")
                    reply = conn.getresponse()
                    self.assertEqual(200, reply.status)
                    self.assertEqual(original, reply.read())
                    for route, file in (("/diplay-android42-toolbar-voice-v12.apk", current),
                                        ("/lan-cast-android42.apk", cast),
                                        ("/tvbox-android42.apk", tvbox)):
                        conn.request("GET", route)
                        reply = conn.getresponse()
                        self.assertEqual(200, reply.status)
                        self.assertEqual(file.read_bytes(), reply.read())
                    conn.request("HEAD", "/diplay-android42.apk")
                    self.assertEqual(404, conn.getresponse().status)
                    conn.request("HEAD", "/diplay-android42-mfi-fix2.apk")
                    self.assertEqual(404, conn.getresponse().status)
                    conn.request("HEAD", "/diplay-android42-ui-audio-v3.apk")
                    self.assertEqual(404, conn.getresponse().status)
                    conn.request("HEAD", "/diplay-android42-back-audio-v4.apk")
                    self.assertEqual(404, conn.getresponse().status)
                    conn.request("HEAD", "/diplay-android42-audio-output-v5.apk")
                    self.assertEqual(404, conn.getresponse().status)
                    conn.request("HEAD", "/diplay-android42-p2p-reconnect-v6.apk")
                    self.assertEqual(404, conn.getresponse().status)
                finally:
                    conn.close()
            finally:
                bundle.close()

    def test_app_dump_only_accepts_explicit_local_zip_and_preserves_bytes(self):
        with tempfile.TemporaryDirectory(prefix="car-app-dump-") as directory:
            base = Path(directory)
            tvbox = base / "tvbox.apk"
            tvbox.write_bytes(b"PK\x03\x04" + b"app" * 300)
            catalog = Catalog(tvbox, "TVBox", "127.0.0.1", 0, 0)
            bundle = HookServerBundle(catalog, "127.0.0.1", 0, 0, 0,
                                      ["127.0.0.1"], dump_dir=base / "dumps")
            try:
                bundle.start()
                binary = io.BytesIO()
                with zipfile.ZipFile(binary, "w") as archive:
                    archive.writestr("index.txt", "Android 4.2")
                    archive.writestr("metadata/com.acloud.stub.store.txt", "sample metadata")
                    archive.writestr("apks/com.acloud.stub.store.apk", b"PK\x03\x04" + b"source")
                contents = binary.getvalue()
                conn = http.client.HTTPConnection("127.0.0.1", bundle.http.server_port, timeout=3)
                try:
                    conn.request("POST", "/api/diagnostic-dump", body=contents,
                                 headers={"Content-Type": "text/plain"})
                    response = conn.getresponse()
                    self.assertEqual(415, response.status)
                    response.read()

                    conn.request("POST", "/api/diagnostic-dump", body=contents,
                                 headers={"Content-Type": "application/zip"})
                    response = conn.getresponse()
                    self.assertEqual(200, response.status)
                    receipt = json.loads(response.read())
                    self.assertTrue(receipt["ok"])
                    saved = list((base / "dumps").glob("car-app-dump-*.zip"))
                    self.assertEqual(1, len(saved))
                    self.assertEqual(contents, saved[0].read_bytes())
                    with zipfile.ZipFile(saved[0]) as archive:
                        self.assertEqual("sample metadata",
                                         archive.read("metadata/com.acloud.stub.store.txt").decode())

                    conn.request("POST", "/api/diagnostic-dump", body=b"not a ZIP",
                                 headers={"Content-Type": "application/zip"})
                    response = conn.getresponse()
                    self.assertEqual(400, response.status)
                    response.read()
                    self.assertEqual(1, len(list((base / "dumps").glob("*.zip"))))

                    bundle.http.dump_clients = set()
                    conn.request("POST", "/api/diagnostic-dump", body=contents,
                                 headers={"Content-Type": "application/zip"})
                    response = conn.getresponse()
                    self.assertEqual(403, response.status)
                    response.read()
                    self.assertEqual(1, len(list((base / "dumps").glob("*.zip"))))
                finally:
                    conn.close()
            finally:
                bundle.close()


if __name__ == "__main__":
    unittest.main()
