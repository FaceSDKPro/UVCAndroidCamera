"""Negative controls for stale reports, wrong installed APKs and input replacement."""
import base64
from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import device_check_common as common
import run_device_checks as runner


def parts(report, count=3):
    payload = base64.b64encode(json.dumps(report).encode()).decode()
    size = (len(payload) + count - 1) // count
    chunks = [payload[i:i + size] for i in range(0, len(payload), size)]
    return '\n'.join(f'REPORT_PART={i}/{len(chunks)}:{chunk}' for i, chunk in enumerate(chunks))


class DeviceEvidenceTest(unittest.TestCase):
    def test_stale_truncated_and_wrong_run_reports_cannot_pass(self):
        old = {'run_id': 'old', 'failed': 0}
        current = {'run_id': 'current', 'failed': 0}
        logs = 'REPORT=badbase64\nREPORT_JSON=broken\n' + parts(old) + '\n' + parts(current)
        self.assertEqual(common.matching_report(logs, 'current'), current)
        self.assertIsNone(common.matching_report(parts(old), 'current'))

    def test_missing_and_inconsistent_chunks_cannot_pass(self):
        lines = parts({'run_id': 'current', 'failed': 0}).splitlines()
        self.assertIsNone(common.matching_report('\n'.join(lines[:-1]), 'current'))
        self.assertIsNone(common.matching_report('\n'.join(lines).replace('1/3:', '1/4:'), 'current'))

    def test_plain_complete_report_is_bound_to_run(self):
        report = {'run_id': 'current', 'failed': 1}
        self.assertEqual(common.matching_report('REPORT_JSON=' + json.dumps(report), 'current'), report)

    def test_wrong_installed_bytes_and_unsafe_paths_are_rejected(self):
        data = b'tested APK'
        with patch.object(common.subprocess, 'check_output', side_effect=['package:/data/app/task/base.apk\n', data]):
            common.require_installed_apk(['adb'], 'package', hashlib.sha256(data).hexdigest())
        with patch.object(common.subprocess, 'check_output', side_effect=['package:/data/app/task/base.apk\n', b'other APK']):
            with self.assertRaisesRegex(RuntimeError, 'differs'):
                common.require_installed_apk(['adb'], 'package', hashlib.sha256(data).hexdigest())
        with patch.object(common.subprocess, 'check_output', return_value='package:/data/app/$(unexpected).apk\n'):
            with self.assertRaisesRegex(RuntimeError, 'path'):
                common.require_installed_apk(['adb'], 'package', 'unused')

    def test_repository_and_symlinked_outputs_are_rejected(self):
        with self.assertRaises(ValueError): common.output_directory(common.ROOT / 'reports')
        with tempfile.TemporaryDirectory() as tmp:
            link = Path(tmp) / 'link'; link.symlink_to(common.ROOT, target_is_directory=True)
            with self.assertRaises(ValueError): common.output_directory(link / 'reports')
            self.assertEqual(common.output_directory(Path(tmp) / 'accepted'), Path(tmp).resolve() / 'accepted')

    def test_apk_replaced_during_device_run_fails_and_preserves_original_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'fake-repo'; apk = root / 'sample/build/outputs/apk/deviceTest/sample-deviceTest.apk'
            apk.parent.mkdir(parents=True); apk.write_bytes(b'original')
            output = Path(tmp) / 'evidence'; run_id = ''
            def adb_call(command, **kwargs):
                nonlocal run_id
                result = 'ok'
                if 'start' in command: run_id = command[-1]
                elif 'pidof' in command: result = '1234'
                elif 'logcat' in command:
                    apk.write_bytes(b'replaced')
                    result = 'REPORT_JSON=' + json.dumps({'run_id': run_id, 'failed': 0, 'passed': 1})
                return subprocess.CompletedProcess(command, 0, result, '')
            with patch.object(runner, 'ROOT', root), patch.object(runner, 'source_fingerprint', return_value='core'), \
                 patch.object(runner, 'require_installed_apk'), patch.object(runner.subprocess, 'run', side_effect=adb_call), \
                 patch.object(sys, 'argv', ['check', '--serial', 'simulated', '--output', str(output), '--keep-app']), redirect_stdout(io.StringIO()):
                self.assertEqual(runner.main(), 1)
            report = json.loads((output / 'device-report.json').read_text())
            self.assertEqual(report['diagnostic_apk_sha256'], hashlib.sha256(b'original').hexdigest())
            self.assertFalse(report['apk_unchanged_after_test'])


if __name__ == '__main__':
    unittest.main()
