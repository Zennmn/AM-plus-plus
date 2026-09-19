"""Verify unmodified vendored rendering sources; no external packages or reference checkout needed."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / "backdrop/upstream-sha256.json").read_text())
failed = []
for relative, expected in manifest.items():
    path = root / "backdrop" / relative
    if not path.is_file() or hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest() != expected:
        failed.append(relative)
if failed:
    raise SystemExit("Upstream rendering source changed:\n" + "\n".join(failed))
print(f"PASS: {len(manifest)} original Backdrop files match commit 65ab177e90e5c1d8c62e70cf7755841982da65f6")
