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
    "docs/play/assets/screenshots/zh-CN/04-landscape.jpg": "84b306376a54c0986e3654434cf75f956b9ca9fb73b0cacc8a7ed9f20228e2ea",
    "docs/play/assets/screenshots/zh-CN/03-settings.jpg": "4b0f82de627843b2ca75cd6b8a897c6069c15ba5129f655adcef2951b4e72bc9",
    "docs/play/assets/screenshots/zh-CN/02-dual.jpg": "3ac7ed859e587a2df92d130696e191a2f1ce0c117988123eee720ecf3d762962",
    "docs/play/assets/screenshots/zh-CN/01-home.jpg": "6ffccc64c172025a27b9bebe05ef35d53bdda0888912efadba1819ff815a3788",
    "docs/play/assets/screenshots/en-US/04-landscape.jpg": "7c15294b66730fef25890bb9e2da03ac9d5ec9992f4f37d25fc5193501a2d477",
    "docs/play/assets/screenshots/en-US/03-settings.jpg": "a5e309006faea6e5bff5d0f42b9b1e9957dc608eade1f1a36059e0cc748cef58",
    "docs/play/assets/screenshots/en-US/02-dual.jpg": "d8aed107b81064d58d57e509c5f8fa9e904741643e7940fe2cd2fa8e0bba6ec1",
    "docs/play/assets/screenshots/en-US/01-home.jpg": "bc8d2f4894ec7db3aa9bcc2e914868db54de2c0100410a8181111a247009a1ea",
    "docs/images/apk-download-qr.png": "b4c213b2449999663d8f680b5e96cbcb628ae5382e684cac4930eccfa3cf818b",
    "docs/images/dual-preview-landscape.png": "c0bdda37121616e59904e7e4aa7b911871ba8f8fc734aa01bca17cf804d58ce4",
    "docs/images/dual-camera-app-icon-source.png": "79cea7f0e4f34bb2dba61fedc0f001c2916cd7ff66b2622d101bd688b25ff4fb",
    "docs/play/assets/feature-en-US.jpg": "155d4427bee0511d3b655f021d94459aa870755febb3a25dd0a7fd3f6db5477b",
    "docs/play/assets/feature-zh-CN.jpg": "7c2f6d210ee3242e8233ce16094ec26dec405a92f6b8ceb53085aed8b28422c0",
    "docs/play/assets/icon-512.png": "c93af4691b852a0cfb3d829e01d036d7a9e12ebb80f5842db93ddf6676b01e62",
    "sample/uvc-camera-release.jks": "00fb63fce22707534999844204a7a54ec9c00efc83f977bf02677f9769842f0d",
    "sample/src/main/res/drawable-nodpi/ic_launcher_foreground.png": "e202a7ca05a10e5fc91e00ccea33fb2dc4f1784ee1e00639dd03e40c7a57f108",
    "sample/src/main/res/mipmap-hdpi/ic_launcher.png": "967a5dc3982973982385c689ed0420aa0ad85a9b166604349dd1844960cbf137",
    "sample/src/main/res/mipmap-hdpi/ic_launcher_round.png": "5b71242f63dc9730553c972297832dcd4d93212886083a4b418cbd03e00baeeb",
    "sample/src/main/res/mipmap-mdpi/ic_launcher.png": "6ad5a07bb948d4fc95d63bcce458abb0222752e960bb3ffd7c224a884ed103ab",
    "sample/src/main/res/mipmap-mdpi/ic_launcher_round.png": "519cf27c1bcc5b6f7b3af8386efcf3fc7085ef41ceab028c5fc1a8775a8abce3",
    "sample/src/main/res/mipmap-xhdpi/ic_launcher.png": "b0df4baf5f070fdc58d7088e1128250edb1d453e68ecb31a1b554119986d325a",
    "sample/src/main/res/mipmap-xhdpi/ic_launcher_round.png": "8299f0de2c5f2cec4a5b2eb573c3bc82bc793c677b148e9fef46a479776c7922",
    "sample/src/main/res/mipmap-xxhdpi/ic_launcher.png": "500f2c30a7194370bfec7d3e4555a1a4e9409266337615a6c7832ab9da6bee33",
    "sample/src/main/res/mipmap-xxhdpi/ic_launcher_round.png": "d9cb02ab692d72058e23edb74283a2ddf16c2154169d777d72e082c18f49c0e4",
    "sample/src/main/res/mipmap-xxxhdpi/ic_launcher.png": "42988f6340501785958487873b699d3b70fafc1847e89d269500c6607d2630af",
    "sample/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png": "d1be6832d088427e3c9bf7635416158f0c1466187e2402a541ae8484933984ee",
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
