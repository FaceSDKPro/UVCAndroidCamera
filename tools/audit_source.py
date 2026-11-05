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
# User-requested public signing identity and visually reviewed images. Pin each binary.
public_binaries = {
    "docs/images/dual-preview-landscape.png": "c0bdda37121616e59904e7e4aa7b911871ba8f8fc734aa01bca17cf804d58ce4",
    "docs/images/dual-camera-app-icon-source.png": "a5df81797ca04b8cd020457a720be036d69b468f913a5556a1398cfefb6d01c4",
    "sample/uvc-camera-release.jks": "13edc54064a9107c2490df9c94c1176a9adf27b7e24c1369a2e951fdb6fb54a6",
    "sample/src/main/res/drawable-nodpi/ic_launcher_foreground.png": "a8842478f99968c6a088727305f095c1da01c7ffa57c903b682e5c20dc67399e",
    "sample/src/main/res/mipmap-hdpi/ic_launcher.png": "8288988dabd2c33b560b6014771e136a4306e4afe27518bf9313f32e6ba5854d",
    "sample/src/main/res/mipmap-hdpi/ic_launcher_round.png": "62a49acc8bd8e19603d7f1709c26a00c4cb3b23d9ca7764c8b0bcc49dbfbfd17",
    "sample/src/main/res/mipmap-mdpi/ic_launcher.png": "885df8d4b679ad455a183d2f3b08bf1a5a7de0c17be4ded61b0c1341569c8dc2",
    "sample/src/main/res/mipmap-mdpi/ic_launcher_round.png": "5e76dd68e5843133bbd1290aa1216b8b210b76637928c6846e2f27343d0a2207",
    "sample/src/main/res/mipmap-xhdpi/ic_launcher.png": "7bd2a1f4906402077a91e6029e0c19b174db5f0fbae674ffd16874737fe3a775",
    "sample/src/main/res/mipmap-xhdpi/ic_launcher_round.png": "5398ff083e6ab4ebd23a389d27fe31b023ec6ae3b1f71cc2d357ceba26ebef3e",
    "sample/src/main/res/mipmap-xxhdpi/ic_launcher.png": "6b59639cb87bf314cc423ad490317d4cfe37e96d0f43c0cace850f8694ebfb28",
    "sample/src/main/res/mipmap-xxhdpi/ic_launcher_round.png": "c8e0b2e0d3d916ec924fedb89cafc2e76cc579e396a67cd575f293a84fc0d57e",
    "sample/src/main/res/mipmap-xxxhdpi/ic_launcher.png": "9a90f50ccf231d92cf6346876c3f6a865cb648996cffa3bcbf7c5e38049acd58",
    "sample/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png": "e42a30b1c2fc5a654fbe3492549ca9adab5ec43dba754a2332dbd41c184611a2",
}
failures = []
for name in names:
    path = ROOT / name
    if path.is_symlink():
        failures.append(name + ": symlink")
        continue
    data = subprocess.check_output(["git", "show", ":" + name], cwd=ROOT)
    if (path.suffix.lower() in bad_suffixes and name not in public_binaries) or any(part in {"build", ".cxx", ".gradle", ".idea"} for part in path.relative_to(ROOT).parts):
        failures.append(name + ": generated/private payload")
    if path.name in {"local.properties", "signing.properties", "id_rsa", "id_ed25519"}:
        failures.append(name + ": local configuration/credential")
    if name in public_binaries:
        if hashlib.sha256(data).hexdigest() != public_binaries[name]:
            failures.append(name + ": public binary checksum mismatch")
    elif b"\0" in data and name != "gradle/wrapper/gradle-wrapper.jar":
        failures.append(name + ": unexpected binary")
    if secret.search(data):
        failures.append(name + ": credential-shaped content")
if failures:
    raise SystemExit("\n".join(failures))
# Imported code/license text retains upstream whitespace. Content/secret checks above still cover it.
subprocess.run(["git", "diff", "--cached", "--check", "--", ".",
                ":(exclude)UVCCamera/src/main/jni/**",
                ":(exclude)UVCCamera/src/main/java/com/serenegiant/**",
                ":(exclude)UVCCamera/src/main/resources/META-INF/uvccamera/LICENSES/**"], cwd=ROOT, check=True)
print("Source audit passed: " + str(len(names)) + " files; no build products or credential-shaped content")
