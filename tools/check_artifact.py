#!/usr/bin/env python3
"""Audit AAR exports, sizes, hardening and 16 KB alignment, optionally including APK packaging."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile
from elf_metadata import dynamic_metadata, segment_metadata


MACHINES = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86': 3, 'x86_64': 62}


def check_apk(path, native_digests):
    rows = []
    with zipfile.ZipFile(path) as archive, path.open('rb') as raw:
        libraries = {name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
        assert len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate APK entries'
        assert libraries == {name.replace('jni/', 'lib/', 1) for name in native_digests}, 'APK native library set differs from AAR'
        for name in sorted(libraries):
            entry = archive.getinfo(name)
            assert entry.compress_type == zipfile.ZIP_STORED, (name, 'Expected uncompressed native library')
            raw.seek(entry.header_offset)
            header = raw.read(30)
            assert header[:4] == b'PK\x03\x04', name
            name_size, extra_size = struct.unpack_from('<HH', header, 26)
            offset = entry.header_offset + 30 + name_size + extra_size
            assert offset % 16384 == 0, (name, 'APK ZIP entry is not 16 KB aligned', offset)
            assert hashlib.sha256(archive.read(name)).hexdigest() == native_digests[name.replace('lib/', 'jni/', 1)], (name, 'APK/AAR native bytes differ')
            rows.append({'library': name, 'data_offset': offset, 'zip_alignment': 16384})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('aar', type=Path)
    parser.add_argument('--abis', nargs='+', choices=MACHINES, default=list(MACHINES), help='Exact expected ABI set (default: all four)')
    parser.add_argument('--apk', type=Path, help='Also verify uncompressed, 16 KB aligned APK libraries match this AAR')
    args = parser.parse_args()
    expected_abis = set(args.abis)
    assert len(expected_abis) == len(args.abis), 'Duplicate ABI'
    policy = json.loads(Path(__file__).with_name('native_exports.json').read_text())
    budgets = json.loads(Path(__file__).with_name('native_size_budget.json').read_text())
    with zipfile.ZipFile(args.aar) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate AAR entries'
        manifest = ET.fromstring(archive.read('AndroidManifest.xml'))
        permissions = {node.attrib['{http://schemas.android.com/apk/res/android}name'] for node in manifest.findall('uses-permission')}
        assert permissions == {'android.permission.CAMERA'}, permissions
        with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as classes:
            assert not any(name.startswith(('com/herohan/', 'com/serenegiant/encoder/', 'com/serenegiant/opengl/')) for name in classes.namelist())
            assert 'com/facesdkpro/uvccamera/UvcCameraManager.class' in classes.namelist()
            assert any(name.endswith('LICENSES/libusb-COPYING') for name in classes.namelist()), 'Bundled license texts missing'
        rows, native_rows, metadata, digests, abis = [], [], {}, {}, set()
        for name in sorted(archive.namelist()):
            if not name.startswith('jni/') or not name.endswith('.so'):
                continue
            _, abi, lib = name.split('/')
            assert abi in expected_abis, ('Unexpected ABI', name)
            abis.add(abi)
            data = archive.read(name)
            assert lib in policy, ('Unexpected native library', name)
            info = dynamic_metadata(data)
            assert info['machine'] == MACHINES[abi], name
            assert info['exports'] == set(policy[lib]), (name, 'Native export boundary changed', sorted(info['exports'] ^ set(policy[lib])))
            if lib in {'libuvc.so', 'libyuv.so'}:
                assert 'libjpeg-turbo212.so' not in info['needed'], (name, 'Unused JPEG dependency')
            assert 'libc++_shared.so' not in info['needed'], (name, 'Unexpected shared C++ runtime')
            assert len(data) <= budgets['max_library_bytes'][abi][lib], (name, 'Native size budget exceeded', len(data))
            segments = segment_metadata(data)
            for segment in segments['loads']:
                assert segment['alignment'] >= 16384 and segment['offset'] % 16384 == segment['address'] % 16384, (name, 'ELF LOAD not 16 KB aligned')
                assert segment['flags'] & 3 != 3, (name, 'Writable executable LOAD')
            assert segments['relro'] and info['bind_now'] and not segments['executable_stack'], (name, 'Missing RELRO/NOW/NX hardening')
            metadata[name] = info
            digests[name] = hashlib.sha256(data).hexdigest()
            native_rows.append({'library': name, 'bytes': len(data), 'budget_bytes': budgets['max_library_bytes'][abi][lib], 'exports': len(info['exports']), 'needed': sorted(info['needed']), 'sha256': digests[name]})
            rows.append({'library': name, 'load_alignment': [segment['alignment'] for segment in segments['loads']], 'relro': True, 'bind_now': True, 'non_executable_stack': True})
        assert abis == expected_abis, ('Missing ABI', expected_abis - abis)
        for abi in abis:
            libraries = {name.split('/')[-1] for name in metadata if name.split('/')[1] == abi}
            assert libraries == set(policy), (abi, libraries)
            available = set().union(*(info['exports'] for name, info in metadata.items() if name.split('/')[1] == abi))
            for name, info in metadata.items():
                if name.split('/')[1] != abi:
                    continue
                internal = {symbol for symbol in info['imports'] if symbol.startswith(('uvc_', '_uvc_', 'libusb_', 'tj', 'jpeg_')) or symbol in policy['libyuv.so']}
                assert internal <= available, (name, 'Unresolved bundled symbols', sorted(internal - available))
        assert len(rows) == len(expected_abis) * len(policy), rows
        assert sum(item['bytes'] for item in native_rows) <= budgets['max_native_bytes'], 'Total native size budget exceeded'
    apk_rows = check_apk(args.apk, digests) if args.apk else []
    print(json.dumps({'permissions': sorted(permissions), 'abis': sorted(abis), 'aar_bytes': args.aar.stat().st_size,
                      'elf_checks': rows, 'native_libraries': native_rows, 'native_bytes': sum(item['bytes'] for item in native_rows), 'apk_checks': apk_rows}, indent=2))


if __name__ == '__main__':
    main()
