#!/usr/bin/env python3
"""Inspect build artifacts without connecting to a device."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    return subprocess.run([str(arg) for arg in args], check=True, capture_output=True, text=True).stdout


def main():
    apk = Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "app/build/outputs/apk/debug/app-debug.apk").resolve()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        for line in (ROOT / "local.properties").read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\ ", " ")
    if not sdk:
        raise SystemExit("请设置 ANDROID_HOME 或 local.properties 的 sdk.dir")
    tools = Path(sdk) / "build-tools" / "36.0.0"
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, "ZIP CRC 校验失败"
        assert archive.read("AndroidManifest.xml")[:4] == bytes([3, 0, 8, 0])
    badging = run(tools / "aapt2", "dump", "badging", apk)
    assert "name='icu.idoit.apkassistant'" in badging, badging
    assert "versionName='1.0.0'" in badging, badging
    assert "android.permission.REQUEST_INSTALL_PACKAGES" in badging
    for permission in ("android.permission.INTERNET", "android.permission.MANAGE_EXTERNAL_STORAGE",
                       "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"):
        assert permission not in badging, f"意外权限: {permission}"
    signature = run(tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    original_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    names = ["应用.apk.1", "应用.apk.xx.xx", "应用.bin", "无后缀"]
    with tempfile.TemporaryDirectory(prefix="apk-assistant-verify-") as temporary:
        for name in names:
            target = Path(temporary) / name
            shutil.copyfile(apk, target)
            assert hashlib.sha256(target.read_bytes()).hexdigest() == original_hash
            parsed = run(tools / "aapt2", "dump", "badging", target)
            assert parsed == badging, f"后缀影响包信息: {name}"
            run(tools / "apksigner", "verify", target)
    output = ROOT / "artifacts"
    output.mkdir(exist_ok=True)
    delivered = output / "安装助手-1.0.0-debug.apk"
    shutil.copyfile(apk, delivered)
    report = {
        "package": "icu.idoit.apkassistant", "version": "1.0.0", "versionCode": 1,
        "artifact": delivered.name, "bytes": apk.stat().st_size, "sha256": original_hash,
        "checks": ["zip-crc", "binary-manifest", "package-metadata", "permissions", "apk-signature-v2"],
        "renamed_apk_checks": names,
        "device_tests_executed": False,
        "limitations": "aapt2/apksigner 为桌面检查，不等同于 Android 设备上的系统解析器、微信/QQ 入口或安装验收。",
        "signature": signature.strip().splitlines(),
    }
    (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("artifact", "bytes", "sha256", "checks", "device_tests_executed")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
