#!/usr/bin/env python3
"""Verify the built AAR's delivery boundary and 64-bit ELF LOAD segment alignment."""
import argparse
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile
from elf_metadata import dynamic_metadata


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
        rows, native_rows = [], []
        abis = set()
        policy = json.loads(Path(__file__).with_name('native_exports.json').read_text())
        metadata = {}
        for name in archive.namelist():
            if not name.startswith("jni/") or not name.endswith(".so"):
                continue
            abi = name.split("/")[1]
            abis.add(abi)
            data = archive.read(name)
            lib = name.split('/')[-1]
            assert lib in policy, ('Unexpected native library', name)
            info = dynamic_metadata(data)
            assert info['machine'] == {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86': 3, 'x86_64': 62}[abi], name
            assert info['exports'] == set(policy[lib]), (name, 'Native export boundary changed', sorted(info['exports'] ^ set(policy[lib])))
            if lib in {'libuvc.so', 'libyuv.so'}:
                assert 'libjpeg-turbo212.so' not in info['needed'], (name, 'Unused JPEG dependency')
            metadata[name] = info
            native_rows.append({'library': name, 'bytes': len(data), 'exports': len(info['exports']), 'needed': sorted(info['needed'])})
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
        for abi in abis:
            libraries = {name.split('/')[-1] for name in metadata if name.split('/')[1] == abi}
            assert libraries == set(policy), (abi, libraries)
            available = set().union(*(info['exports'] for name, info in metadata.items() if name.split('/')[1] == abi))
            for name, info in metadata.items():
                if name.split('/')[1] != abi:
                    continue
                internal = {symbol for symbol in info['imports'] if symbol.startswith(('uvc_', '_uvc_', 'libusb_', 'tj', 'jpeg_')) or symbol in policy['libyuv.so']}
                assert internal <= available, (name, 'Unresolved bundled symbols', sorted(internal - available))
        assert abis == {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}, abis
        assert len(rows) == 10, rows
    print(json.dumps({"permissions": sorted(permissions), "abis": sorted(abis), "elf_checks": rows, "native_libraries": native_rows, "native_bytes": sum(item['bytes'] for item in native_rows)}, indent=2))


if __name__ == "__main__":
    main()
