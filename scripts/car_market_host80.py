#!/usr/bin/env python3
"""Minimal port-80 discovery endpoint for a user-controlled XYGala test LAN.

Use only after YOUR hotspot/router maps www.xygala.com to the Mac's LAN IP.
This deliberately does not redirect or proxy arbitrary websites.  Binding
TCP/80 on macOS normally requires administrator privileges; the main app
market bridge itself stays unprivileged on high ports.
"""

import argparse
import ipaddress
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


LOG = logging.getLogger("car-market-discovery")


class DiscoveryHandler(BaseHTTPRequestHandler):
    server_version = "CarMarketDiscovery/1.0"

    def do_GET(self):
        self.respond(True)

    def do_HEAD(self):
        self.respond(False)

    def respond(self, include_body):
        host = self.headers.get("Host", "").lower()
        path = urlsplit(self.path)
        if (host not in ("www.xygala.com", "www.xygala.com:80") or
                path.path != "/host.php" or path.query):
            self.send_error(404, "This listener serves only the OEM discovery URL")
            return
        body = json.dumps({"app_market": self.server.market_host},
                          separators=(",", ":")).encode("ascii")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if include_body:
            self.wfile.write(body)

    def log_message(self, fmt, *args):
        LOG.info("client=%s %s", self.client_address[0], fmt % args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True, help="Mac LAN IPv4, never a public interface")
    parser.add_argument("--port", type=int, default=80)
    parser.add_argument("--market-port", type=int, default=18611)
    options = parser.parse_args()
    try:
        address = ipaddress.ip_address(options.bind)
        if not address.is_private or address.is_loopback or address.version != 4:
            raise ValueError("must be a non-loopback private IPv4 address")
    except ValueError as exc:
        parser.error("--bind: {}".format(exc))
    if not 1 <= options.market_port <= 65535 or not 1 <= options.port <= 65535:
        parser.error("invalid port")

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    with ThreadingHTTPServer((options.bind, options.port), DiscoveryHandler) as server:
        server.market_host = "{}:{}".format(options.bind, options.market_port)
        LOG.info("READY http://%s:%s/host.php -> %s (Host: www.xygala.com)",
                 options.bind, server.server_port, server.market_host)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            LOG.info("STOP requested by operator")


if __name__ == "__main__":
    main()
