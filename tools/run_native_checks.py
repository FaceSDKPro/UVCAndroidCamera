#!/usr/bin/env python3
"""Run converter/strided JPEG tests on an arm64 Android device using synthetic pixels only.
Requires a built Release AAR, its pinned NDK, and host cjpeg (libjpeg-turbo).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from run_device_checks import ROOT, source_fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        parser.error('--output must be outside the repository')
    output.mkdir(parents=True, exist_ok=True)
    cjpeg = shutil.which('cjpeg')
    if not cjpeg:
        parser.error('Install host libjpeg-turbo/cjpeg to generate synthetic JPEG input')
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if not sdk:
        sdk = next(line.split('=', 1)[1] for line in (ROOT / 'local.properties').read_text().splitlines() if line.startswith('sdk.dir='))
    ndk_version = re.search(r"ndkVersion: '([^']+)'", (ROOT / 'build.gradle').read_text()).group(1)
    ndk = Path(sdk) / 'ndk' / ndk_version
    host = next(path for path in (ndk / 'toolchains/llvm/prebuilt').iterdir() if path.is_dir())
    native = ROOT / 'camera/src/main/jni'
    before = source_fingerprint()
    aar = ROOT / 'camera/build/outputs/aar/camera-release.aar'
    with zipfile.ZipFile(aar) as archive:
        for name in archive.namelist():
            if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):
                (output / Path(name).name).write_bytes(archive.read(name))
    rgb = bytes(c for y in range(8) for x in range(34) for c in ((x * 7 + y * 13) % 256, (x * 3 + y * 41) % 256, (x * 11 + y * 5) % 256))
    ppm = output / 'synthetic.ppm'; ppm.write_bytes(b'P6\n34 8\n255\n' + rgb)
    jpeg = output / 'synthetic.jpg'
    subprocess.run([cjpeg, '-quality', '90', '-sample', '2x1', '-outfile', str(jpeg), str(ppm)], check=True)
    includes = [native, native / 'UVCCamera', native / 'libuvc/include', native / 'libuvc/include/libuvc', native / 'libusb/libusb', native / 'libyuv/include', native / 'libjpeg-turbo/include']
    command = [str(host / 'bin/aarch64-linux-android23-clang++'), '-std=c++17', '-O3', '-flto=thin', '-fstack-protector-strong', '-fno-exceptions', '-fno-rtti', '-static-libstdc++', '-ffunction-sections', '-fdata-sections']
    command += ['-I' + str(path) for path in includes]
    command += [str(ROOT / 'tools/native/frame_pipeline_test.cpp'), str(native / 'UVCCamera/ConvertHelper.cpp'), '-L' + str(output), '-luvc', '-lyuv', '-ljpeg-turbo212', '-lusb1.0', '-llog', '-Wl,--gc-sections,-z,max-page-size=16384', '-Wl,-rpath,$ORIGIN', '-o', str(output / 'frame_pipeline_test')]
    subprocess.run(command, check=True)
    adb = ['adb', '-s', args.serial]
    remote = '/data/local/tmp/uvc-native-pipeline-check'
    def call(*parts):
        return subprocess.check_output(adb + list(parts), text=True, stderr=subprocess.STDOUT, timeout=60)
    try:
        call('shell', 'mkdir', '-p', remote)
        for path in [output / 'frame_pipeline_test', jpeg] + sorted(output.glob('*.so')):
            call('push', str(path), remote + '/')
        call('shell', 'chmod', '700', remote + '/frame_pipeline_test')
        raw = call('shell', 'env', 'LD_LIBRARY_PATH=' + remote, remote + '/frame_pipeline_test', remote + '/synthetic.jpg')
        report = json.loads(raw)
        report.update({'core_source_sha256': before, 'source_unchanged': before == source_fingerprint(), 'aar_sha256': hashlib.sha256(aar.read_bytes()).hexdigest(), 'ndk_version': ndk_version})
        assert report['failed'] == 0 and report['source_unchanged']
        (output / 'native-report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        call('shell', 'rm', '-rf', remote)


if __name__ == '__main__':
    main()
