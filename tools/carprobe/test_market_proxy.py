"""Offline regression test for the opt-in XYGala discovery-only proxy hook."""

import http.client
import ipaddress
import json
import threading
import unittest

from scripts.car_market_proxy import CarProxyHandler, ThreadingProxy


class CarMarketProxyHookTest(unittest.TestCase):
    def setUp(self):
        self.proxy = ThreadingProxy(("127.0.0.1", 0), CarProxyHandler)
        self.proxy.allowed_clients = {"127.0.0.1"}
        self.proxy.market_override = "192.168.31.209:18601"
        threading.Thread(target=self.proxy.serve_forever, daemon=True).start()
        self.addCleanup(self.proxy.server_close)
        self.addCleanup(self.proxy.shutdown)

    def request(self, method, url):
        conn = http.client.HTTPConnection("127.0.0.1", self.proxy.server_address[1], timeout=2)
        try:
            conn.request(method, url)
            result = conn.getresponse()
            return result.status, result.getheader("Content-Type"), result.read()
        finally:
            conn.close()

    def test_exact_vendor_discovery_overridden(self):
        status, content_type, body = self.request("GET", "http://www.xygala.com/host.php")
        self.assertEqual(200, status)
        self.assertEqual("application/json; charset=utf-8", content_type)
        self.assertEqual({"app_market": "192.168.31.209:18601"}, json.loads(body))

    def test_other_host_not_overridden(self):
        status, _, body = self.request("GET", "http://diplay-proxy.test/health")
        self.assertEqual(200, status)
        self.assertIn(b"DiPlay proxy OK", body)

    def test_trusted_lan_is_allowed_without_exact_car_ip(self):
        self.proxy.allowed_clients = set()
        self.proxy.allowed_subnets = (ipaddress.ip_network("127.0.0.0/8"),)
        status, _, body = self.request("GET", "http://www.xygala.com/host.php")
        self.assertEqual(200, status)
        self.assertEqual("192.168.31.209:18601", json.loads(body)["app_market"])

    def test_untrusted_client_is_denied(self):
        self.proxy.allowed_clients = set()
        self.proxy.allowed_subnets = (ipaddress.ip_network("192.168.31.0/24"),)
        status, _, _ = self.request("GET", "http://www.xygala.com/host.php")
        self.assertEqual(403, status)


if __name__ == "__main__":
    unittest.main()
