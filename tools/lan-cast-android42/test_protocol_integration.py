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
import urllib.parse
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
        status, _, device_xml = self.request("GET", "/device.xml")
        self.assertEqual(status, 200)
        root = ET.fromstring(device_xml)
        ns = {"d": "urn:schemas-upnp-org:device-1-0"}
        url_base = root.findtext("d:URLBase", namespaces=ns)
        self.assertTrue(url_base.endswith("/"))
        services = root.findall(".//d:service", namespaces=ns)
        self.assertEqual(len(services), 3)
        service_ids = {
            s.findtext("d:serviceId", namespaces=ns) for s in services
        }
        self.assertEqual(service_ids, {
            "urn:upnp-org:serviceId:AVTransport",
            "urn:upnp-org:serviceId:ConnectionManager",
            "urn:upnp-org:serviceId:RenderingControl",
        })
        for service in services:
            for tag in ("SCPDURL", "controlURL", "eventSubURL"):
                relative = service.findtext("d:" + tag, namespaces=ns)
                self.assertFalse(relative.startswith("/"), (tag, relative))
                target = urllib.parse.urljoin(url_base, relative)
                self.assertEqual(target.count("//"), 1, (tag, target))

        for path in ("/device.xml", "/avtransport.xml", "/connectionmanager.xml", "/renderingcontrol.xml"):
            status, _, body = self.request("GET", path)
            self.assertEqual(status, 200, path)
            ET.fromstring(body)
            status, reply, head = self.request("HEAD", path)
            self.assertEqual(status, 200, path)
            self.assertEqual(head, b"")
            self.assertEqual(int(reply.getheader("Content-Length")), len(body))
            # Reproduce Tencent controller's observed URLBase + /SCPDURL bug.
            if path != "/device.xml":
                status, _, duplicate = self.request("GET", "/" + path)
                self.assertEqual(status, 200, "duplicate-slash " + path)
                self.assertEqual(duplicate, body)

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

    def test_scpd_action_variable_mappings_follow_upnp_definitions(self):
        service_ns = {"s": "urn:schemas-upnp-org:service-1-0"}
        expected = {
            "/renderingcontrol.xml": {
                ("GetVolume", "CurrentVolume"): "Volume",
                ("SetVolume", "DesiredVolume"): "Volume",
                ("GetMute", "CurrentMute"): "Mute",
                ("SetMute", "DesiredMute"): "Mute",
                ("GetVolume", "Channel"): "A_ARG_TYPE_Channel",
            },
            "/avtransport.xml": {
                ("SetAVTransportURI", "CurrentURI"): "AVTransportURI",
                ("SetAVTransportURI", "CurrentURIMetaData"): "AVTransportURIMetaData",
                ("Play", "Speed"): "TransportPlaySpeed",
                ("GetTransportInfo", "CurrentTransportState"): "TransportState",
                ("GetPositionInfo", "TrackURI"): "CurrentTrackURI",
                ("GetPositionInfo", "RelTime"): "RelativeTimePosition",
                ("GetDeviceCapabilities", "PlayMedia"): "PossiblePlaybackStorageMedia",
                ("GetTransportSettings", "PlayMode"): "CurrentPlayMode",
            },
            "/connectionmanager.xml": {
                ("GetProtocolInfo", "Source"): "SourceProtocolInfo",
                ("GetProtocolInfo", "Sink"): "SinkProtocolInfo",
                ("GetCurrentConnectionIDs", "ConnectionIDs"): "CurrentConnectionIDs",
            },
        }
        for path, mappings in expected.items():
            with self.subTest(path=path):
                status, _, raw = self.request("GET", path)
                self.assertEqual(status, 200)
                root = ET.fromstring(raw)
                variables = {}
                for state in root.findall(".//s:serviceStateTable/s:stateVariable", service_ns):
                    name = state.findtext("s:name", namespaces=service_ns)
                    self.assertNotIn(name, variables, "Duplicate variable " + str(name))
                    variables[name] = state
                self.assertTrue(variables)
                actions = {}
                for action in root.findall(".//s:actionList/s:action", service_ns):
                    name = action.findtext("s:name", namespaces=service_ns)
                    for arg in action.findall("s:argumentList/s:argument", service_ns):
                        arg_name = arg.findtext("s:name", namespaces=service_ns)
                        state = arg.findtext("s:relatedStateVariable", namespaces=service_ns)
                        self.assertIn(state, variables, (path, name, arg_name))
                        actions[(name, arg_name)] = state
                for key, state in mappings.items():
                    self.assertEqual(actions.get(key), state, (path, key))
                if path == "/renderingcontrol.xml":
                    volume = variables["Volume"]
                    self.assertEqual(volume.findtext("s:dataType", namespaces=service_ns), "ui2")
                    self.assertEqual(volume.findtext(".//s:maximum", namespaces=service_ns), "100")
                    self.assertEqual(variables["Mute"].findtext("s:dataType", namespaces=service_ns),
                                     "boolean")
                    channel = variables["A_ARG_TYPE_Channel"]
                    self.assertEqual(channel.findtext(".//s:allowedValue", namespaces=service_ns),
                                     "Master")
                if path == "/connectionmanager.xml":
                    self.assertEqual(variables["SinkProtocolInfo"].attrib.get("sendEvents"), "yes")
                else:
                    self.assertEqual(variables["LastChange"].attrib.get("sendEvents"), "yes")


if __name__ == "__main__":
    unittest.main()
