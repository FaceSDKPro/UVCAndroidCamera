#!/usr/bin/env python3
"""Run the release-optimized sample harness. Reports contain no camera pixels."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import re
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.facesdkpro.uvccamera.sample"


def source_fingerprint():
    paths = sorted((ROOT / "camera/src/main").rglob("*"))
    paths += [ROOT / "camera/build.gradle", ROOT / "camera/consumer-rules.pro"]
    digest = hashlib.sha256()
    for path in paths:
        if path.is_file():
            digest.update(path.relative_to(ROOT).as_posix().encode() + b"\0")
            digest.update(path.read_bytes())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True, help="Directory outside source control for raw evidence")
    parser.add_argument("--keep-app", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ROOT / "sample/build/outputs/apk/deviceTest/sample-deviceTest.apk"
    before = source_fingerprint()
    adb = ["adb", "-s", args.serial]

    def call(*command, check=True):
        result = subprocess.run(adb + list(command), text=True, capture_output=True, timeout=45)
        if check and result.returncode:
            raise RuntimeError(result.stdout.strip() + "\n" + result.stderr.strip())
        return result

    print(call("install", "-r", "-g", str(apk)).stdout, flush=True)
    try:
        call("shell", "am", "force-stop", PACKAGE)
        call("shell", "pm", "grant", PACKAGE, "android.permission.CAMERA")
        print(call("shell", "am", "start", "-W", "-n", PACKAGE + "/.DeviceProbeActivity").stdout, flush=True)
        pid = call("shell", "pidof", PACKAGE).stdout.strip().split()[0]
        deadline = time.monotonic() + 140
        while time.monotonic() < deadline:
            logs = call("logcat", "-d", "--pid=" + pid, "-v", "brief", "-s", "UvcCameraAcceptance:I", "*:S").stdout
            lines = [line.split("REPORT_JSON=", 1)[1] for line in logs.splitlines() if "REPORT_JSON=" in line]
            chunks = {int(index): (int(total), chunk) for index, total, chunk in
                      re.findall(r"REPORT_PART=(\d+)/(\d+):([A-Za-z0-9+/=]+)", logs)}
            payload = None
            if chunks and 0 in chunks and len(chunks) == chunks[0][0] and all(i in chunks for i in range(chunks[0][0])):
                payload = base64.b64decode("".join(chunks[i][1] for i in range(len(chunks)))).decode()
            elif lines:
                payload = lines[-1]
            if payload:
                report = json.loads(payload)
                report["core_source_sha256"] = before
                report["diagnostic_apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
                report["source_unchanged_after_test"] = before == source_fingerprint()
                for key in ["ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.build.version.sdk"]:
                    report[key] = call("shell", "getprop", key).stdout.strip()
                (args.output / "device-log.txt").write_text(logs)
                (args.output / "device-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
                print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
                return 0 if report["failed"] == 0 and report["source_unchanged_after_test"] else 1
            time.sleep(2)
        raise RuntimeError("Device report timed out; inspect camera/USB permission dialogs on the device")
    finally:
        call("shell", "am", "force-stop", PACKAGE, check=False)
        if not args.keep_app:
            call("uninstall", PACKAGE, check=False)


if __name__ == "__main__":
    raise SystemExit(main())
