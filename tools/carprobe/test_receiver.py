import io
import tempfile
import unittest
import zipfile

from scripts.car_probe_reports import ReportStore


class ReportStoreTest(unittest.TestCase):
    def test_pair_once_and_keep_read_only_reports(self):
        with tempfile.TemporaryDirectory() as base:
            store = ReportStore(base)
            code = store.pair_code
            self.assertEqual((None, "invalid"), store.pair("000000000x"))
            token, status = store.pair(code)
            self.assertEqual("ok", status)
            self.assertTrue(store.authorized(token))
            self.assertEqual((None, "expired"), store.pair(code))
            with self.assertRaises(PermissionError):
                store.save_report("invalid", "device", "firmware data", "0.3")
            report_id = store.save_report(token, "device", "firmware data", "0.3")
            self.assertIn("firmware data", (
                store.reports_dir / (report_id + ".json")
            ).read_text(encoding="utf-8"))
            self.assertTrue(ReportStore(base).authorized(token))

    def test_pairing_attempts_are_limited(self):
        with tempfile.TemporaryDirectory() as base:
            store = ReportStore(base)
            for _ in range(5):
                self.assertEqual("invalid", store.pair("bad")[1])
            self.assertEqual("locked", store.pair(store.pair_code)[1])

    def test_apk_upload_one_time_without_type_restrictions(self):
        with tempfile.TemporaryDirectory() as base:
            store = ReportStore(base)
            token = store.upload_token
            code = store.upload_code
            self.assertTrue(store.upload_authorized(token))
            self.assertFalse(store.upload_code_authorized("999999x"))
            self.assertTrue(store.upload_code_authorized(code))
            with self.assertRaises(ValueError):
                store.store_uploaded_file(token, b"", "empty.zip")
            self.assertTrue(store.upload_authorized(token))
            apk = io.BytesIO()
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"dummy-manifest")
                archive.writestr("classes.dex", b"dex\n" + b"0" * 256)
            upload_id = store.store_uploaded_apk(token, apk.getvalue(), "YueMi.apk")
            self.assertTrue((store.inbox_dir / (upload_id + ".apk")).is_file())
            self.assertTrue((store.inbox_dir / (upload_id + ".json")).is_file())
            self.assertFalse(store.upload_authorized(token))
            self.assertFalse(store.upload_code_authorized(code))
            with self.assertRaises(PermissionError):
                store.store_uploaded_apk(token, apk.getvalue(), "YueMi.apk")

    def test_any_file_type_is_accepted_and_preserved(self):
        samples = (("PackageInstaller.dex", b"not a real DEX"),
                   ("PackageInstaller.odex", b"not a real ODEX"),
                   ("system-dump.zip", b"PK\x03\x04" + b"z" * 256),
                   ("notes.txt", b"x"),
                   ("no-extension", b"raw file"))
        for name, data in samples:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as base:
                store = ReportStore(base)
                upload_id = store.store_uploaded_file(store.upload_token, data, name)
                suffix = "." + name.rsplit(".", 1)[-1] if "." in name else ".bin"
                self.assertEqual(data, (store.inbox_dir / (upload_id + suffix)).read_bytes())
                self.assertFalse(store.upload_authorized(store.upload_token))


if __name__ == "__main__":
    unittest.main()
