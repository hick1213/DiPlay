#!/usr/bin/env python3
"""Private, token-protected on-disk receiver for the Android 4.2 car probe.

Only the server can read reports. Pairing uses a short-lived code stored locally;
clients exchange it once for a long random bearer token. No unauthenticated GET
endpoint provides report data or credentials.
"""

import hashlib
import hmac
import json
import os
import secrets
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


PAIR_TTL_SECONDS = 3600
MAX_PAIR_ATTEMPTS = 5
MAX_REPORT_BYTES = 160 * 1024
MAX_UPLOAD_BYTES = 64 * 1024 * 1024
UPLOAD_TTL_SECONDS = 24 * 3600
MAX_UPLOAD_CODE_ATTEMPTS = 10


class ReportStore:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.reports_dir = self.root / "reports"
        self.reports_dir.mkdir(exist_ok=True)
        self.inbox_dir = self.root / "apk-inbox"
        self.inbox_dir.mkdir(exist_ok=True)
        self.credentials_path = self.root / "paired-clients.json"
        self.pair_code_path = self.root / "pairing-code.txt"
        self.upload_link_path = self.root / "upload-link.txt"
        self.upload_code_path = self.root / "upload-code.txt"
        self.lock = threading.Lock()
        self.pair_code = "{:010d}".format(secrets.randbelow(10**10))
        self.pair_expiry = time.monotonic() + PAIR_TTL_SECONDS
        self.failed_pair_attempts = 0
        self.upload_token = secrets.token_urlsafe(32)
        self.upload_code = "{:06d}".format(secrets.randbelow(10**6))
        self.failed_upload_code_attempts = 0
        self.upload_expiry = time.monotonic() + UPLOAD_TTL_SECONDS
        self.upload_used = False
        if self.credentials_path.exists():
            saved = json.loads(self.credentials_path.read_text(encoding="utf-8"))
            self.token_hashes = set(saved["token_sha256"])
        else:
            self.token_hashes = set()

        self.pair_code_path.write_text(
            "{}\nValid for {} seconds from receiver start.\n".format(
                self.pair_code, PAIR_TTL_SECONDS), encoding="ascii"
        )
        self._protect(self.pair_code_path)
        self.upload_link_path.write_text(
            "/upload?key={}\nValid for 24 hours or one successful upload.\n".format(
                self.upload_token), encoding="ascii"
        )
        self._protect(self.upload_link_path)
        self.upload_code_path.write_text(
            "{}\nValid for 24 hours or one successful upload.\n".format(self.upload_code),
            encoding="ascii"
        )
        self._protect(self.upload_code_path)

    @staticmethod
    def _protect(path):
        try:
            os.chmod(path, 0o600)
        except OSError:
            # exFAT does not preserve Unix modes; the disk is user-mounted.
            pass

    @staticmethod
    def _digest(token):
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    def _save_clients(self):
        temp = self.root / (".paired-clients-" + uuid.uuid4().hex + ".tmp")
        temp.write_text(
            json.dumps({"token_sha256": sorted(self.token_hashes)}), encoding="utf-8"
        )
        self._protect(temp)
        os.replace(temp, self.credentials_path)
        self._protect(self.credentials_path)

    def pair(self, code):
        """Return (token, status): status is ok, invalid, expired or locked."""
        with self.lock:
            if self.failed_pair_attempts >= MAX_PAIR_ATTEMPTS:
                return None, "locked"
            if time.monotonic() >= self.pair_expiry:
                return None, "expired"
            if not isinstance(code, str) or not hmac.compare_digest(code, self.pair_code):
                self.failed_pair_attempts += 1
                return None, "invalid"
            token = secrets.token_urlsafe(32)
            self.token_hashes.add(self._digest(token))
            self._save_clients()
            # Do not permit additional pairing with the same one-time code.
            self.pair_expiry = 0
            self.pair_code_path.write_text("Paired. Code no longer valid.\n", encoding="ascii")
            self._protect(self.pair_code_path)
            return token, "ok"

    def authorized(self, token):
        if not isinstance(token, str) or len(token) > 128 or not token:
            return False
        hashed = self._digest(token)
        with self.lock:
            return any(hmac.compare_digest(hashed, expected)
                       for expected in self.token_hashes)

    def save_report(self, token, device_id, report, app_version):
        if not self.authorized(token):
            raise PermissionError("not paired")
        if not isinstance(report, str) or not report.strip():
            raise ValueError("report missing")
        if len(report.encode("utf-8")) > MAX_REPORT_BYTES:
            raise ValueError("report too large")
        if not isinstance(device_id, str) or len(device_id) > 100:
            raise ValueError("invalid device ID")
        now = datetime.now(timezone.utc)
        report_id = "{}-{}".format(now.strftime("%Y%m%dT%H%M%SZ"), uuid.uuid4().hex[:12])
        record = {
            "id": report_id,
            "received_at": now.isoformat(),
            "device_id": device_id,
            "app_version": str(app_version)[:50],
            "report": report,
        }
        path = self.reports_dir / (report_id + ".json")
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(record, stream, ensure_ascii=False, indent=2)
        return report_id

    def upload_authorized(self, token):
        if not isinstance(token, str) or len(token) > 128:
            return False
        with self.lock:
            return (not self.upload_used and time.monotonic() < self.upload_expiry
                    and hmac.compare_digest(token, self.upload_token))

    def upload_code_authorized(self, code):
        """Validate a short, rate-limited code without exposing the upload token."""
        with self.lock:
            if (self.upload_used or time.monotonic() >= self.upload_expiry
                    or self.failed_upload_code_attempts >= MAX_UPLOAD_CODE_ATTEMPTS):
                return False
            if (isinstance(code, str) and len(code) == 6 and code.isascii()
                    and code.isdecimal() and hmac.compare_digest(code, self.upload_code)):
                return True
            self.failed_upload_code_attempts += 1
            return False

    def store_uploaded_file(self, token, data, original_name):
        if not isinstance(data, bytes) or not 0 < len(data) <= MAX_UPLOAD_BYTES:
            raise ValueError("Invalid upload size")
        if not isinstance(original_name, str):
            raise ValueError("Missing filename")
        safe_name = original_name.replace("\\", "/").split("/")[-1][:200] or "upload.bin"
        extension = Path(safe_name).suffix.lower()
        # Keep common extensions for easy analysis. Never use a user-supplied path;
        # unknown extensions are still accepted and stored with a safe .bin suffix.
        if not (2 <= len(extension) <= 16 and extension[1:].isascii()
                and extension[1:].isalnum()):
            extension = ".bin"

        with self.lock:
            if (self.upload_used or time.monotonic() >= self.upload_expiry
                    or not hmac.compare_digest(str(token), self.upload_token)):
                raise PermissionError("Upload link expired or already used")
            upload_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:12]
            upload_path = self.inbox_dir / (upload_id + extension)
            fd = os.open(upload_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(data)
            metadata_path = self.inbox_dir / (upload_id + ".json")
            metadata_path.write_text(json.dumps({
                "id": upload_id,
                "original_name": safe_name,
                "file_type": extension.lstrip("."),
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }, ensure_ascii=False, indent=2), encoding="utf-8")
            self._protect(metadata_path)
            self.upload_used = True
            self.upload_link_path.write_text("Upload received. Link no longer valid.\n", encoding="ascii")
            self._protect(self.upload_link_path)
            self.upload_code_path.write_text("Upload received. Code no longer valid.\n", encoding="ascii")
            self._protect(self.upload_code_path)
            return upload_id

    def store_uploaded_apk(self, token, data, original_name):
        """Compatibility alias for callers of the original receiver."""
        return self.store_uploaded_file(token, data, original_name)
