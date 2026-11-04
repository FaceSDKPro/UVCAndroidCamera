"""Input and report binding shared by local Android device checks."""
import base64
import binascii
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def output_directory(path):
    path = path.resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError('Output must be outside the repository')
    path.mkdir(parents=True, exist_ok=True)
    return path


def file_sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_installed_apk(adb, package, expected):
    paths = subprocess.check_output(adb + ['shell', 'pm', 'path', package], text=True, timeout=45).splitlines()
    if len(paths) != 1 or not paths[0].startswith('package:/data/app/'):
        raise RuntimeError('Expected one installed APK for ' + package)
    path = paths[0][len('package:'):]
    if not re.fullmatch(r'/data/app/[A-Za-z0-9/_.~+=-]+\.apk', path):
        raise RuntimeError('Unexpected installed APK path')
    data = subprocess.check_output(adb + ['exec-out', 'cat', path], timeout=45)
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError('Installed APK differs from the frozen test input')


def matching_report(logs, run_id):
    """Return only a complete report from this run, even if the PID/log buffer is reused."""
    chunks, total = {}, 0
    candidates = []
    for line in logs.splitlines():
        match = re.search(r'REPORT_PART=(\d+)/(\d+):([A-Za-z0-9+/=]+)', line)
        if match:
            index, count, payload = int(match[1]), int(match[2]), match[3]
            if index == 0:
                chunks, total = {}, count
            if count != total or not 0 < total <= 256 or not 0 <= index < total:
                continue
            chunks[index] = payload
            if len(chunks) == total:
                try:
                    candidates.append(base64.b64decode(''.join(chunks[i] for i in range(total)), validate=True).decode())
                except (binascii.Error, UnicodeDecodeError):
                    pass
        elif 'REPORT_JSON=' in line:
            candidates.append(line.split('REPORT_JSON=', 1)[1])
        elif 'REPORT=' in line:
            try:
                candidates.append(base64.b64decode(line.split('REPORT=', 1)[1], validate=True).decode())
            except (binascii.Error, UnicodeDecodeError):
                pass
    for payload in reversed(candidates):
        try:
            report = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if not isinstance(report, dict):
            continue
        if report.get('run_id') == run_id:
            return report
    return None
