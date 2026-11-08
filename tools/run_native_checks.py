#!/usr/bin/env python3
"""Run converter/strided JPEG tests on an arm64 Android device using synthetic pixels only.
Requires a built Release AAR, its pinned NDK, and host cjpeg (libjpeg-turbo).
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from run_device_checks import ROOT, source_fingerprint
from device_check_common import file_sha256


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
    native = ROOT / 'UVCCamera/src/main/jni'
    before = source_fingerprint()
    aar = ROOT / 'UVCCamera/build/outputs/aar/UVCCamera-release.aar'
    aar_sha = file_sha256(aar)
    libraries = []
    with zipfile.ZipFile(aar) as archive:
        for name in archive.namelist():
            if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):
                library = output / Path(name).name
                library.write_bytes(archive.read(name))
                libraries.append(library)
    rgb = bytes(c for y in range(8) for x in range(34) for c in ((x * 7 + y * 13) % 256, (x * 3 + y * 41) % 256, (x * 11 + y * 5) % 256))
    ppm = output / 'synthetic.ppm'; ppm.write_bytes(b'P6\n34 8\n255\n' + rgb)
    jpeg = output / 'synthetic.jpg'
    subprocess.run([cjpeg, '-quality', '90', '-sample', '2x1', '-outfile', str(jpeg), str(ppm)], check=True)
    includes = [native, native / 'UVCCamera', native / 'libuvc/include', native / 'libuvc/include/libuvc', native / 'libusb/libusb', native / 'libyuv/include', native / 'libjpeg-turbo/include']
    command = [str(host / 'bin/aarch64-linux-android23-clang++'), '-std=c++17', '-O3', '-flto=thin', '-fstack-protector-strong', '-fno-exceptions', '-fno-rtti', '-static-libstdc++', '-ffunction-sections', '-fdata-sections']
    command += ['-I' + str(path) for path in includes]
    command += [str(ROOT / 'tools/native/frame_pipeline_test.cpp'), str(native / 'UVCCamera/ConvertHelper.cpp'), '-L' + str(output), '-luvc', '-lyuv', '-ljpeg-turbo212', '-lusb1.0', '-llog', '-Wl,--gc-sections,-z,max-page-size=16384', '-Wl,-rpath,$ORIGIN', '-o', str(output / 'frame_pipeline_test')]
    subprocess.run(command, check=True)
    # Fault tests compile the production sources separately. Disable inlining/
    # allocator builtins so linker --wrap injection reaches the intended calls.
    cflags = ['-O2', '-fno-inline', '-fno-builtin-malloc', '-fno-builtin-calloc', '-fno-builtin-realloc', '-fno-builtin-free', '-DLOG_NDEBUG', '-ffunction-sections', '-fdata-sections']
    include_flags = ['-I' + str(path) for path in includes]
    objects = []
    for source in ['frame', 'stream', 'device']:
        target = output / (source + '.o')
        subprocess.run([str(host / 'bin/aarch64-linux-android23-clang')] + cflags + include_flags + ['-c', str(native / ('libuvc/src/' + source + '.c')), '-o', str(target)], check=True)
        objects.append(str(target))
    base = command[:command.index(str(ROOT / 'tools/native/frame_pipeline_test.cpp'))]
    tests = ['callback_ownership_test', 'jni_registration_test', 'frame_memory_test', 'stream_failure_test', 'frame_rotation_test',
             'preview_startup_test', 'status_shutdown_test', 'descriptor_bounds_test', 'disconnect_drain_test']
    for test in tests:
        if test == 'disconnect_drain_test':
            usb_flags = cflags + ['-I' + str(native / 'libusb/android'), '-I' + str(native / 'libusb/libusb')]
            compiler = str(host / 'bin/aarch64-linux-android23-clang')
            usb_object = output / 'usb_io.o'
            subprocess.run([compiler] + usb_flags + ['-c', str(native / 'libusb/libusb/io.c'), '-o', str(usb_object)], check=True)
            subprocess.run([compiler] + usb_flags + [str(ROOT / 'tools/native/disconnect_drain_test.c'), str(usb_object),
                           '-Wl,--wrap=usbi_handle_transfer_completion,--gc-sections,-z,max-page-size=16384',
                           '-o', str(output / test)], check=True)
            continue
        cmd = base + [str(ROOT / ('tools/native/' + test + '.cpp'))]
        if test == 'callback_ownership_test':
            cmd += [str(native / 'UVCCamera/UVCButtonCallback.cpp'), str(native / 'UVCCamera/UVCStatusCallback.cpp')]
        elif test == 'jni_registration_test':
            cmd += [str(native / 'UVCCamera/_onload.cpp')]
        elif test == 'frame_rotation_test':
            pass  # Header-only production transform; no USB mocks needed.
        elif test == 'preview_startup_test':
            cmd += [str(native / 'UVCCamera/UVCPreview.cpp'), str(native / 'UVCCamera/ConvertHelper.cpp'),
                    '-DLOG_NDEBUG', '-Wl,--wrap=pthread_create', '-luvc', '-lyuv', '-ljpeg-turbo212']
        elif test == 'status_shutdown_test':
            cmd += objects[2:]
            cmd += ['-Wl,--wrap=' + name for name in ['uvc_process_status_xfer', 'uvc_release_if', 'uvc_unref_device', 'uvc_free_devh']]
        elif test == 'descriptor_bounds_test':
            cmd += objects[2:]
        else:
            cmd += objects[:1] if test == 'frame_memory_test' else objects[:2]
            wraps = ['realloc', 'free'] if test == 'frame_memory_test' else ['malloc', 'calloc', 'realloc', 'pthread_create', 'uvc_stream_ctrl']
            cmd += ['-Wl,--wrap=' + name for name in wraps]
        cmd += ['-L' + str(output), '-lusb1.0', '-llog', '-Wl,--gc-sections,-z,max-page-size=16384', '-Wl,-rpath,$ORIGIN', '-o', str(output / test)]
        subprocess.run(cmd, check=True)
    adb = ['adb', '-s', args.serial]
    remote = '/data/local/tmp/uvc-native-pipeline-check'
    def call(*parts):
        return subprocess.check_output(adb + list(parts), text=True, stderr=subprocess.STDOUT, timeout=60)
    try:
        call('shell', 'mkdir', '-p', remote)
        for path in [output / 'frame_pipeline_test', jpeg] + [output / test for test in tests] + libraries:
            call('push', str(path), remote + '/')
        call('shell', 'chmod', '700', remote + '/frame_pipeline_test')
        for test in tests:
            call('shell', 'chmod', '700', remote + '/' + test)
        raw = call('shell', 'env', 'LD_LIBRARY_PATH=' + remote, remote + '/frame_pipeline_test', remote + '/synthetic.jpg')
        report = json.loads(raw)
        report['fault_checks'] = {test: json.loads(call('shell', 'env', 'LD_LIBRARY_PATH=' + remote, remote + '/' + test)) for test in tests}
        report.update({'core_source_sha256': before, 'source_unchanged': before == source_fingerprint(), 'aar_sha256': aar_sha, 'aar_unchanged': aar_sha == file_sha256(aar), 'ndk_version': ndk_version})
        assert report['failed'] == 0 and report['source_unchanged'] and report['aar_unchanged']
        assert all(item['failed'] == 0 for item in report['fault_checks'].values())
        (output / 'native-report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        call('shell', 'rm', '-rf', remote)


if __name__ == '__main__':
    main()
