#!/usr/bin/env python3
"""Audit the exact staged/committed source payload before uploading it."""
from pathlib import Path
import hashlib
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
names = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
names = [name for name in names if name]
if not names:
    raise SystemExit("No staged/tracked payload; stage the intended source before auditing")
if subprocess.run(["git", "diff", "--quiet"], cwd=ROOT).returncode:
    raise SystemExit("Unstaged tracked changes remain; audit the intended final snapshot")
bad_suffixes = {".apk", ".aab", ".aar", ".so", ".a", ".o", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".db", ".log"}
secret = re.compile(rb"-----BEGIN (?:[A-Z ]*PRIVATE KEY)-----|AKIA[A-Z0-9]{16}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}")
# User-requested, visually reviewed public screenshot. Do not allow arbitrary image binaries.
public_images = {
    "docs/images/dual-preview-landscape.png": "c0bdda37121616e59904e7e4aa7b911871ba8f8fc734aa01bca17cf804d58ce4",
}
failures = []
for name in names:
    path = ROOT / name
    if path.is_symlink():
        failures.append(name + ": symlink")
        continue
    data = subprocess.check_output(["git", "show", ":" + name], cwd=ROOT)
    if path.suffix.lower() in bad_suffixes or any(part in {"build", ".cxx", ".gradle", ".idea"} for part in path.relative_to(ROOT).parts):
        failures.append(name + ": generated/private payload")
    if path.name in {"local.properties", "signing.properties", "id_rsa", "id_ed25519"}:
        failures.append(name + ": local configuration/credential")
    if name in public_images:
        if hashlib.sha256(data).hexdigest() != public_images[name]:
            failures.append(name + ": public screenshot checksum mismatch")
    elif b"\0" in data and name != "gradle/wrapper/gradle-wrapper.jar":
        failures.append(name + ": unexpected binary")
    if secret.search(data):
        failures.append(name + ": credential-shaped content")
if failures:
    raise SystemExit("\n".join(failures))
# Imported code/license text retains upstream whitespace. Content/secret checks above still cover it.
subprocess.run(["git", "diff", "--cached", "--check", "--", ".",
                ":(exclude)camera/src/main/jni/**",
                ":(exclude)camera/src/main/java/com/serenegiant/**",
                ":(exclude)camera/src/main/resources/META-INF/uvccamera/LICENSES/**"], cwd=ROOT, check=True)
print("Source audit passed: " + str(len(names)) + " files; no build products or credential-shaped content")
