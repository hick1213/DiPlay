"""Offline, network-isolated checks of the decoded XYGala market protocol."""

import http.client
import json
import socket
import tempfile
import threading
import unittest
from pathlib import Path

from scripts.car_market_bridge import Catalog, MarketHTTP, MarketTCP, ThreadingHTTPServer, ThreadingTCP


class LocalMarketBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="market-bridge-")
        self.addCleanup(self.temp.cleanup)
        self.data = b"PK\x03\x04" + bytes(range(256)) * 5
        apk = Path(self.temp.name) / "original-no-repackage.apk"
        apk.write_bytes(self.data)
        self.catalog = Catalog(apk, "Original APK", "127.0.0.1", 18806, 18601)

    def test_host_php_and_original_file_range(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), MarketHTTP)
        server.catalog = self.catalog
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=2)
        self.addCleanup(connection.close)
        connection.request("GET", "/host.php")
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        self.assertEqual("127.0.0.1:18601", json.loads(response.read())["app_market"])
        connection.request("GET", self.catalog.apk_path)
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        self.assertEqual(self.data, response.read())
        connection.request("GET", self.catalog.apk_path, headers={"Range": "bytes=10-19"})
        response = connection.getresponse()
        self.assertEqual(206, response.status)
        self.assertEqual(self.data[10:20], response.read())

    def test_tcp_json_catalog(self):
        server = ThreadingTCP(("127.0.0.1", 0), MarketTCP)
        server.catalog = self.catalog
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        with socket.create_connection(("127.0.0.1", server.server_address[1]), timeout=2) as stream:
            stream.settimeout(2)
            with stream.makefile("rwb") as reader:
                reader.write(b'{"type":"get_access_token"}\n')
                reader.flush()
                key = json.loads(reader.readline())
                self.assertEqual("get_access_token", key["type"])
                request = {"type": "get_app", "access_token": key["access_token"],
                           "factory": 5, "page": 1, "page_size": 6}
                reader.write(json.dumps(request).encode() + b"\n")
                reader.flush()
                reply = json.loads(reader.readline())
                self.assertEqual(0, reply["code"])
                self.assertEqual("Original APK", reply["item_apps"][0]["name"])
                self.assertEqual(self.catalog.size, reply["item_apps"][0]["size"])
                self.assertTrue(reply["item_apps"][0]["apk"].endswith(self.catalog.apk_path))


if __name__ == "__main__":
    unittest.main()
