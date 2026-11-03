#!/usr/bin/env python3
"""Compare process CPU at fixed capture modes; does not measure electrical power or save images."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import time
from run_device_checks import ROOT, PACKAGE, source_fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--scenarios', nargs='+', choices=['headless', 'preview', 'slow', 'gray', 'preview-only'], default=['headless', 'preview', 'slow'])
    parser.add_argument('--width', type=int, default=640)
    parser.add_argument('--height', type=int, default=480)
    parser.add_argument('--duration', type=int, default=15000, help='Milliseconds per sample; three samples after five seconds of warmup')
    parser.add_argument('--skip-install', action='store_true')
    args = parser.parse_args()
    if not 1000 <= args.duration <= 30000:
        parser.error('--duration must be 1000..30000 ms')
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        parser.error('--output must be outside the repository')
    output.mkdir(parents=True, exist_ok=True)
    apk = ROOT / 'sample/build/outputs/apk/deviceTest/sample-deviceTest.apk'
    identity = {'core_source_sha256': source_fingerprint(), 'diagnostic_apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
    adb = ['adb', '-s', args.serial]

    def call(*command, check=True):
        return subprocess.run(adb + list(command), check=check, capture_output=True, text=True, timeout=45).stdout

    if not args.skip_install:
        print(call('install', '-r', '-g', str(apk)).strip(), flush=True)
    try:
        call('shell', 'pm', 'grant', PACKAGE, 'android.permission.CAMERA')
        for scenario in args.scenarios:
            call('shell', 'am', 'force-stop', PACKAGE)
            thermal_before = call('shell', 'dumpsys', 'thermalservice')
            cmd = ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/.PerformanceProbeActivity', '--ei', 'width', str(args.width), '--ei', 'height', str(args.height), '--ei', 'duration', str(args.duration)]
            if scenario in ('preview', 'preview-only'):
                cmd += ['--ez', 'preview', 'true']
            if scenario == 'preview-only':
                cmd += ['--ez', 'previewOnly', 'true']
            if scenario == 'slow':
                cmd += ['--ei', 'delay', '80']
            if scenario == 'gray':
                cmd += ['--ei', 'format', '8']
            call(*cmd)
            pid = call('shell', 'pidof', PACKAGE).strip().split()[0]
            deadline = time.monotonic() + 110
            print('Measuring ' + scenario, flush=True)
            while time.monotonic() < deadline:
                logs = call('logcat', '-d', '--pid=' + pid, '-v', 'brief', '-s', 'UvcPerformance:I', '*:S')
                (output / (scenario + '-log.txt')).write_text(logs)
                lines = [line.split('REPORT=', 1)[1] for line in logs.splitlines() if 'REPORT=' in line]
                if lines:
                    report = json.loads(base64.b64decode(lines[-1]).decode())
                    report.update(identity)
                    report['source_unchanged_after_test'] = identity['core_source_sha256'] == source_fingerprint()
                    if len(report['samples']) != 3:
                        raise RuntimeError('Incomplete performance samples: ' + logs)
                    (output / (scenario + '.json')).write_text(json.dumps(report, indent=2) + '\n')
                    (output / (scenario + '-thermal-before.txt')).write_text(thermal_before)
                    (output / (scenario + '-thermal-after.txt')).write_text(call('shell', 'dumpsys', 'thermalservice'))
                    if not report['source_unchanged_after_test']:
                        raise RuntimeError('Library source changed during measurement')
                    if any(min(sample['callback_fps']) <= 0 for sample in report['samples']):
                        raise RuntimeError('A camera stopped delivering frames')
                    print(scenario + ': ' + str([round(s['cpu_percent_one_core'], 1) for s in report['samples']]) + ' percent of one CPU core', flush=True)
                    break
                if 'ERROR=' in logs:
                    raise RuntimeError(logs)
                time.sleep(2)
            else:
                raise RuntimeError('Performance report timed out: ' + scenario)
    finally:
        call('shell', 'am', 'force-stop', PACKAGE, check=False)


if __name__ == '__main__':
    main()
