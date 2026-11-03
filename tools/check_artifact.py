#!/usr/bin/env python3
"""Verify the built AAR's delivery boundary and 64-bit ELF LOAD segment alignment."""
import argparse
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.aar) as archive:
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
        permissions = {node.attrib["{http://schemas.android.com/apk/res/android}name"] for node in manifest.findall("uses-permission")}
        assert permissions == {"android.permission.CAMERA"}, permissions
        classes = zipfile.ZipFile(io.BytesIO(archive.read("classes.jar")))
        assert not any(name.startswith(("com/herohan/", "com/serenegiant/encoder/", "com/serenegiant/opengl/")) for name in classes.namelist())
        assert "com/facesdkpro/uvccamera/UvcCameraManager.class" in classes.namelist()
        assert any(name.endswith("LICENSES/libusb-COPYING") for name in classes.namelist()), "Bundled license texts missing"
        rows = []
        abis = set()
        for name in archive.namelist():
            if not name.startswith("jni/") or not name.endswith(".so"):
                continue
            abi = name.split("/")[1]
            abis.add(abi)
            if abi not in {"arm64-v8a", "x86_64"}:
                continue
            data = archive.read(name)
            assert data[:5] == b"\x7fELF\x02", name
            offset = struct.unpack_from("<Q", data, 32)[0]
            entry_size, count = struct.unpack_from("<HH", data, 54)
            alignments = []
            for index in range(count):
                header = struct.unpack_from("<IIQQQQQQ", data, offset + index * entry_size)
                if header[0] == 1:
                    alignments.append(header[7])
                    assert header[7] >= 16384 and header[2] % 16384 == header[3] % 16384, name
            rows.append({"library": name, "load_alignment": alignments})
        assert abis == {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}, abis
        assert len(rows) == 10, rows
    print(json.dumps({"permissions": sorted(permissions), "abis": sorted(abis), "elf_checks": rows}, indent=2))


if __name__ == "__main__":
    main()
