#!/usr/bin/env python3
"""Assemble an audited local SDK/developer-demo bundle. Never uploads or publishes remotely."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COORDINATES = 'com.facesdkpro:uvc-android-camera:'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def copy(source, target):
    if source.is_symlink() or not source.is_file():
        raise ValueError('Expected a regular input file: ' + source.name)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    if source.name == 'gradlew':
        target.chmod(0o755)


def source_names():
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    return sorted(set(filter(None, names)))


def snapshot():
    digest = hashlib.sha256()
    for name in source_names():
        path = ROOT / name
        if path.is_file() and not path.is_symlink():
            digest.update(name.encode() + b'\0' + path.read_bytes())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--abis', default='armeabi-v7a,arm64-v8a,x86,x86_64')
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', args.version):
        parser.error('Invalid SDK version')
    abis = args.abis.split(',')
    if len(abis) != len(set(abis)) or not set(abis) <= {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}:
        parser.error('Invalid ABI list')
    before = snapshot()
    source_repo = ROOT / 'build/sdk-maven/com/facesdkpro/uvc-android-camera' / args.version
    stem = 'uvc-android-camera-' + args.version
    aar = source_repo / (stem + '.aar')
    apk = ROOT / 'sample/build/outputs/apk/demo/sample-demo.apk'
    audit = subprocess.check_output(['python3', str(ROOT / 'tools/check_artifact.py'), str(aar),
                                     '--abis', *abis, '--apk', str(apk)], cwd=ROOT, text=True)
    audit = json.loads(audit)
    # The demo is an actual optimized binary consumer and must not contain the private probe entrypoints.
    with zipfile.ZipFile(apk) as archive:
        dex = b''.join(archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name))
        for probe in [b'DeviceProbeActivity', b'PerformanceProbeActivity', b'DualSessionProbeActivity']:
            if probe in dex:
                raise ValueError('Diagnostic activity in distributable Demo: ' + probe.decode())
    with zipfile.ZipFile(aar) as archive:
        import io
        with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as classes:
            for name in ['DualCameraSession', 'DualCameraConfig', 'FramePair', 'Nv21Planes']:
                if 'com/facesdkpro/uvccamera/' + name + '.class' not in classes.namelist():
                    raise ValueError('Missing public SDK API: ' + name)
    destination = ROOT / 'build/sdk' / stem
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='sdk-stage-', dir=destination.parent) as temp:
        stage = Path(temp) / stem
        stage.mkdir()
        # Copy only the fixed version, not caches, unrelated versions or Maven Local account state.
        for suffix in ['.aar', '.pom', '.module', '-sources.jar']:
            copy(source_repo / (stem + suffix), stage / 'maven-repository/com/facesdkpro/uvc-android-camera' / args.version / (stem + suffix))
        copy(aar, stage / 'sdk' / aar.name)
        copy(source_repo / (stem + '-sources.jar'), stage / 'sdk' / (stem + '-sources.jar'))
        copy(apk, stage / 'demo' / ('UvcCameraDemo-' + args.version + '.apk'))
        for name in ['README.md', 'README.zh-CN.md', 'LICENSE', 'NOTICE', 'UPSTREAM.md', 'NATIVE_BUILD.md',
                     'PERFORMANCE.md', 'PERFORMANCE.zh-CN.md', 'VALIDATION.md',
                     'docs/SDK_INTEGRATION.md', 'docs/SDK_INTEGRATION.zh-CN.md']:
            copy(ROOT / name, stage / name)
        # Standalone example builds against the packaged Maven AAR; no :camera source project exists here.
        demo = stage / 'demo-source'
        for name in ['settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat',
                     'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties', 'sample/build.gradle']:
            copy(ROOT / name, demo / name)
        with (demo / 'gradle.properties').open('a') as output:
            output.write('\nuvcSdkRepository=../maven-repository\n')
        # Match Git's visible source set, excluding ignored local files in the example tree.
        for name in source_names():
            source = ROOT / name
            if name.startswith('sample/src/main/') and source.is_file():
                copy(source, demo / name)
        manifest = {
            'schema_version': 1, 'name': 'UVCAndroidCamera', 'version': args.version,
            'coordinates': COORDINATES + args.version, 'abis': abis,
            'source_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'source_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
            'source_tree_sha256': before,
            'demo_signing': 'Standard local Android debug identity; replace with your own application signing',
            'integration': 'Standalone acquisition SDK only; HelloFace has not been modified or linked',
            'pairing': 'Host callback-arrival clock; software association only, no sensor synchronization',
            'artifact_audit': audit,
        }
        manifest['files'] = {path.relative_to(stage).as_posix(): sha256(path) for path in sorted(stage.rglob('*')) if path.is_file()}
        (stage / 'sdk-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        checksums = {path.relative_to(stage).as_posix(): sha256(path) for path in sorted(stage.rglob('*')) if path.is_file()}
        (stage / 'SHA256SUMS').write_text(''.join(value + '  ' + name + '\n' for name, value in checksums.items()))
        if before != snapshot():
            raise RuntimeError('Source inputs changed during SDK preparation')
        if destination.exists():
            marker = destination / 'sdk-manifest.json'
            if destination.is_symlink() or not marker.is_file() or json.loads(marker.read_text()).get('name') != 'UVCAndroidCamera':
                raise ValueError('Refusing to replace an unrelated output directory')
            shutil.rmtree(destination)
        shutil.move(str(stage), destination)
    bundle = destination.parent / (stem + '.zip')
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(destination.rglob('*')):
            if path.is_file():
                archive.write(path, Path(stem) / path.relative_to(destination))
    print(json.dumps({'directory': str(destination), 'zip': str(bundle), 'sha256': sha256(bundle),
                      'coordinates': COORDINATES + args.version, 'source_tree_sha256': before}, indent=2))


if __name__ == '__main__':
    main()
