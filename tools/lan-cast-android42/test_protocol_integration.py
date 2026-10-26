#!/usr/bin/env python3
"""Opt-in DLNA/GENA integration test against a running owned LAN Cast receiver.

Example:
  LAN_CAST_TEST_HOST=192.168.1.110 LAN_CAST_TEST_CALLBACK_HOST=192.168.1.109 \
    python3 tools/lan-cast-android42/test_protocol_integration.py

The test changes the receiver's *reported* state with Pause/Stop, so it must not
be run during media playback. Without the explicit environment variables it skips.
"""

import http.client
import http.server
import os
import queue
import threading
import unittest
import xml.etree.ElementTree as ET

TARGET = os.environ.get("LAN_CAST_TEST_HOST")
CALLBACK_HOST = os.environ.get("LAN_CAST_TEST_CALLBACK_HOST")


class NotifyHandler(http.server.BaseHTTPRequestHandler):
    def do_NOTIFY(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.server.received.put((self.headers["SID"], self.headers["SEQ"], body))
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, fmt, *args):
        pass


@unittest.skipUnless(TARGET and CALLBACK_HOST, "requires explicitly configured LAN receiver and callback IP")
class LanCastProtocolTest(unittest.TestCase):
    def setUp(self):
        self.conn = http.client.HTTPConnection(TARGET, 8200, timeout=5)
        self.server = http.server.HTTPServer((CALLBACK_HOST, 0), NotifyHandler)
        self.server.received = queue.Queue()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.conn.close()
        self.server.shutdown()
        self.server.server_close()

    def request(self, method, path, body=None, headers=None):
        self.conn.request(method, path, body, headers or {})
        reply = self.conn.getresponse()
        return reply.status, reply, reply.read()

    def test_description_head_and_gena_initial_and_transition(self):
        for path in ("/device.xml", "/avtransport.xml", "/connectionmanager.xml", "/renderingcontrol.xml"):
            status, _, body = self.request("GET", path)
            self.assertEqual(status, 200, path)
            ET.fromstring(body)
            status, reply, head = self.request("HEAD", path)
            self.assertEqual(status, 200, path)
            self.assertEqual(head, b"")
            self.assertEqual(int(reply.getheader("Content-Length")), len(body))

        headers = {
            "CALLBACK": f"<http://{CALLBACK_HOST}:{self.server.server_port}/notify>",
            "NT": "upnp:event",
            "TIMEOUT": "Second-120",
        }
        status, reply, _ = self.request("SUBSCRIBE", "/event/avtransport", headers=headers)
        self.assertEqual(status, 200)
        sid = reply.getheader("SID")
        first = self.server.received.get(timeout=5)
        self.assertEqual((first[0], first[1]), (sid, "0"))
        ET.fromstring(first[2])
        self.assertIn(b"LastChange", first[2])

        try:
            for action, expected_seq in (("Pause", "1"), ("Stop", "2")):
                body = (f'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
                        f'<s:Body><u:{action} xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">'
                        f'<InstanceID>0</InstanceID></u:{action}></s:Body></s:Envelope>')
                status, _, _ = self.request("POST", "/control/avtransport", body, {
                    "SOAPACTION": f'"urn:schemas-upnp-org:service:AVTransport:1#{action}"',
                    "Content-Type": "text/xml",
                })
                self.assertEqual(status, 200)
                notification = self.server.received.get(timeout=5)
                self.assertEqual(notification[1], expected_seq)
                ET.fromstring(notification[2])

            status, reply, _ = self.request("SUBSCRIBE", "/event/avtransport",
                headers={"SID": sid, "TIMEOUT": "Second-120"})
            self.assertEqual((status, reply.getheader("SID")), (200, sid))
        finally:
            self.request("UNSUBSCRIBE", "/event/avtransport", headers={"SID": sid})

        status, _, _ = self.request("SUBSCRIBE", "/event/connectionmanager", headers={
            "CALLBACK": "<http://8.8.8.8:9000/unsafe>", "NT": "upnp:event",
        })
        self.assertEqual(status, 412)  # no cross-host callback/SSRF


if __name__ == "__main__":
    unittest.main()
