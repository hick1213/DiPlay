#!/usr/bin/env python3
"""Metadata-only HTTP/HTTPS forward proxy for diagnosing an old car app market.

HTTPS is CONNECT-tunneled, never decrypted. Request bodies and sensitive
headers are never written to disk. The proxy is restricted to explicit clients.
"""

import argparse
import http.client
import ipaddress
import json
import logging
from logging.handlers import RotatingFileHandler
import select
import socket
import socketserver
import time
from urllib.parse import parse_qsl, urlsplit


LOG = logging.getLogger("car-market-proxy")
MAX_REQUEST_LINE = 65536


class CarProxyHandler(socketserver.StreamRequestHandler):
    timeout = 90

    def reply(self, code, reason, body, content_type="text/plain; charset=utf-8"):
        payload = body.encode("utf-8")
        self.wfile.write((
            "HTTP/1.1 {} {}\r\nContent-Type: {}\r\n"
            "Content-Length: {}\r\nConnection: close\r\n\r\n"
        ).format(code, reason, content_type, len(payload)).encode("ascii") + payload)

    def handle(self):
        client = self.client_address[0]
        trusted_subnets = getattr(self.server, "allowed_subnets", ())
        trusted = client in self.server.allowed_clients
        if not trusted:
            try:
                trusted = any(ipaddress.ip_address(client) in network
                              for network in trusted_subnets)
            except ValueError:
                trusted = False
        if not trusted:
            LOG.warning("DENY client=%s", client)
            self.reply(403, "Forbidden", "Client not allowed\n")
            return

        line = self.rfile.readline(MAX_REQUEST_LINE + 1)
        if not line or len(line) > MAX_REQUEST_LINE:
            return
        try:
            method, target, version = line.decode("iso-8859-1").strip().split(" ", 2)
            if not version.startswith("HTTP/"):
                raise ValueError("invalid protocol")
            headers = http.client.parse_headers(self.rfile)
        except (ValueError, http.client.HTTPException, UnicodeError):
            self.reply(400, "Bad Request", "Invalid proxy request\n")
            return

        start = time.monotonic()
        if method.upper() == "CONNECT":
            self.tunnel(client, target, start)
        else:
            self.forward_http(client, method, target, headers, start)

    def tunnel(self, client, target, start):
        try:
            parts = urlsplit("//" + target)
            host = parts.hostname
            port = parts.port or 443
            if not host or not 1 <= port <= 65535:
                raise ValueError("bad CONNECT target")
            remote = socket.create_connection((host, port), timeout=12)
        except (OSError, ValueError) as exc:
            LOG.info("CONNECT_FAIL client=%s target=%s error=%s", client, target, exc)
            self.reply(502, "Bad Gateway", "Upstream connection failed\n")
            return

        LOG.info("CONNECT_OK client=%s target=%s", client, target)
        try:
            self.connection.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            self.connection.settimeout(90)
            remote.settimeout(90)
            sockets = (self.connection, remote)
            while True:
                readable, _, _ = select.select(sockets, [], [], 90)
                if not readable:
                    break
                for source in readable:
                    data = source.recv(65536)
                    if not data:
                        return
                    destination = remote if source is self.connection else self.connection
                    destination.sendall(data)
        except (OSError, TimeoutError):
            pass
        finally:
            remote.close()
            LOG.info("CONNECT_END target=%s seconds=%.1f", target, time.monotonic() - start)

    def forward_http(self, client, method, target, headers, start):
        parts = urlsplit(target)
        if parts.scheme.lower() != "http" or not parts.hostname:
            self.reply(400, "Bad Request", "Expected an absolute HTTP URL\n")
            return

        host = parts.hostname
        try:
            port = parts.port or 80
        except ValueError:
            self.reply(400, "Bad Request", "Invalid port\n")
            return

        if host == "diplay-proxy.test":
            LOG.info("HEALTH client=%s", client)
            self.reply(200, "OK", "DiPlay proxy OK. HTTPS is pass-through.\n")
            return

        # Opt-in lab override of exactly one *plain HTTP* discovery endpoint.
        # The vendor's later raw TCP socket does not traverse this HTTP proxy;
        # the advertised market host must be reachable directly from the car.
        if (self.server.market_override and method.upper() == "GET" and
                host.lower() == "www.xygala.com" and port == 80 and
                parts.path == "/host.php" and not parts.query):
            body = json.dumps({"app_market": self.server.market_override},
                              separators=(",", ":"))
            self.reply(200, "OK", body, "application/json; charset=utf-8")
            LOG.info("MARKET_DISCOVERY_OVERRIDE client=%s market=%s", client,
                     self.server.market_override)
            return

        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        query_keys = ",".join(sorted({key for key, _ in parse_qsl(parts.query)}))
        status = "unknown"
        response_started = False

        try:
            remote = socket.create_connection((host, port), timeout=12)
            remote.settimeout(30)
            with remote:
                outgoing = ["{} {} HTTP/1.1\r\n".format(method, path)]
                for name, value in headers.items():
                    if name.lower() not in ("connection", "proxy-connection", "keep-alive",
                                            "proxy-authorization", "proxy-authenticate"):
                        outgoing.append("{}: {}\r\n".format(name, value))
                if not headers.get("Host"):
                    outgoing.append("Host: {}\r\n".format(parts.netloc))
                outgoing.append("Connection: close\r\n\r\n")
                remote.sendall("".join(outgoing).encode("iso-8859-1"))

                if headers.get("Transfer-Encoding", "").lower() == "chunked":
                    while True:
                        chunk_header = self.rfile.readline(256)
                        remote.sendall(chunk_header)
                        chunk_size = int(chunk_header.split(b";", 1)[0], 16)
                        remaining = chunk_size + 2
                        while remaining:
                            block = self.rfile.read(min(remaining, 65536))
                            if not block:
                                raise ConnectionError("incomplete request body")
                            remote.sendall(block)
                            remaining -= len(block)
                        if chunk_size == 0:
                            break
                else:
                    remaining = int(headers.get("Content-Length", "0"))
                    while remaining:
                        block = self.rfile.read(min(remaining, 65536))
                        if not block:
                            raise ConnectionError("incomplete request body")
                        remote.sendall(block)
                        remaining -= len(block)

                first = remote.recv(65536)
                if not first:
                    raise ConnectionError("upstream closed before response")
                if first.startswith(b"HTTP/"):
                    status = first.split(b" ", 2)[1].decode("ascii", "replace")
                self.connection.sendall(first)
                response_started = True
                while first:
                    first = remote.recv(65536)
                    if first:
                        self.connection.sendall(first)
        except (OSError, ValueError, ConnectionError) as exc:
            status = "FAIL:{}".format(type(exc).__name__)
            LOG.warning("HTTP_ERROR host=%s error=%s", host, exc)
            if not response_started:
                try:
                    self.reply(502, "Bad Gateway", "Proxy upstream error\n")
                except OSError:
                    pass
        finally:
            LOG.info("HTTP client=%s method=%s host=%s path=%s query_keys=%s status=%s seconds=%.1f",
                     client, method, host, parts.path or "/", query_keys, status,
                     time.monotonic() - start)


class ThreadingProxy(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0", help="listen on all interfaces; client IPs are allowlisted")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--allow-client", action="append", default=["127.0.0.1", "172.20.10.4"])
    parser.add_argument("--allow-subnet", action="append", default=[],
                        help="optionally allow a trusted LAN CIDR, e.g. 192.168.31.0/24")
    parser.add_argument("--market-override", default="",
                        help="opt-in replacement HOST:PORT returned only for http://www.xygala.com/host.php")
    parser.add_argument("--log", default="/tmp/diplay-market-proxy.log")
    options = parser.parse_args()

    if options.market_override:
        name, separator, port = options.market_override.rpartition(":")
        if (not separator or not name or "/" in name or ":" in name or
                not port.isascii() or not port.isdecimal() or
                not 1 <= int(port) <= 65535):
            parser.error("--market-override must be a HOST:PORT reachable directly from the car")
    try:
        trusted_subnets = tuple(ipaddress.ip_network(subnet, strict=False)
                                for subnet in options.allow_subnet)
    except ValueError as exc:
        parser.error("invalid --allow-subnet: {}".format(exc))

    LOG.setLevel(logging.INFO)
    handler = RotatingFileHandler(options.log, maxBytes=2 * 1024 * 1024, backupCount=2)
    handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    LOG.addHandler(handler)
    LOG.addHandler(logging.StreamHandler())

    with ThreadingProxy((options.host, options.port), CarProxyHandler) as server:
        server.allowed_clients = set(options.allow_client + [options.host])
        server.allowed_subnets = trusted_subnets
        server.market_override = options.market_override
        LOG.info("READY proxy=%s:%s allow=%s", options.host, options.port,
                 ",".join(sorted(server.allowed_clients)))
        if trusted_subnets:
            LOG.warning("TRUSTED_PROXY_SUBNETS %s", ",".join(map(str, trusted_subnets)))
        if options.market_override:
            LOG.info("MARKET_DISCOVERY_OVERRIDE_ENABLED market=%s", options.market_override)
        server.serve_forever(poll_interval=0.5)


if __name__ == "__main__":
    main()
