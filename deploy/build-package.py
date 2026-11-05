#!/usr/bin/env python3
"""Build the launcher and StreamBox Updates, check their signature, and zip them with the installer for new boxes.

Usage: python deploy/build-package.py

The zip lands in work/deploy/, which git ignores: this repo holds no built APKs. It needs the release key in
~/.streambox/, and stops if an APK comes out signed with anything else, because updates only install over the same key.
"""
import datetime
import glob
import os
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROPS = pathlib.Path.home() / ".streambox" / "signing.properties"
SDK = pathlib.Path(os.environ.get("ANDROID_HOME") or pathlib.Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
JAVA = pathlib.Path(os.environ.get("JAVA_HOME") or r"C:\Program Files\Android\Android Studio\jbr")
APKS = {
    "launcher.apk": ROOT / "launcher/app/build/outputs/apk/release/app-release.apk",
    "updates.apk": ROOT / "addons/streambox-updates/app/build/outputs/apk/release/app-release.apk",
}
# Kept outside the repo, like the key: this repo holds no binaries, and this one is someone's settings.
EDGE_SETTINGS = pathlib.Path(r"D:\installs\chromecast\edge-settings.bak")


def fingerprint_of_key():
    p = dict(line.split("=", 1) for line in PROPS.read_text().splitlines() if "=" in line)
    out = subprocess.run([str(JAVA / "bin/keytool"), "-list", "-keystore", p["storeFile"], "-storepass", p["storePassword"],
                          "-alias", p["keyAlias"]], capture_output=True, text=True, check=True).stdout
    return re.search(r"SHA-256\): ([0-9A-F:]+)", out).group(1).replace(":", "").lower()


def fingerprint_of_apk(apk):
    tool = sorted(glob.glob(str(SDK / "build-tools/*/apksigner.bat")))[-1]
    out = subprocess.run([tool, "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True,
                         env={**os.environ, "JAVA_HOME": str(JAVA)}).stdout
    return re.search(r"SHA-256 digest: ([0-9a-f]+)", out).group(1)


def main():
    if not PROPS.is_file():
        sys.exit("no release key at %s: every box is tied to that key, so this won't build with another" % PROPS)
    if not EDGE_SETTINGS.is_file():
        sys.exit("no Edge settings backup at %s" % EDGE_SETTINGS)
    env = {**os.environ, "JAVA_HOME": str(JAVA), "ANDROID_HOME": str(SDK)}
    gradle = [str(ROOT / "launcher/gradlew.bat"), "--no-daemon", "testReleaseUnitTest", "lintRelease", "assembleRelease"]
    subprocess.run(gradle, cwd=ROOT / "launcher", env=env, check=True)
    subprocess.run(gradle + ["-p", str(ROOT / "addons/streambox-updates")], cwd=ROOT / "launcher", env=env, check=True)

    want = fingerprint_of_key()
    for name, apk in APKS.items():
        if fingerprint_of_apk(apk) != want:
            sys.exit("%s is not signed with the release key" % name)

    version = re.search(r"versionName '([^']+)'", (ROOT / "launcher/app/build.gradle").read_text()).group(1)
    out_dir = ROOT / "work/deploy"
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / ("StreamBox-Deploy-%s-%s.zip" % (version, datetime.date.today().strftime("%Y%m%d")))
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for name, apk in APKS.items():
            z.write(apk, "StreamBox-Deploy/apps/" + name)
        z.write(EDGE_SETTINGS, "StreamBox-Deploy/files/" + EDGE_SETTINGS.name)
        for name in ("install.ps1", "install.bat", "debloat-onn-4k-pro.txt", "README.txt"):
            z.write(ROOT / "deploy" / name, "StreamBox-Deploy/" + name)
    print("Package:", target, "(%d KB)" % (target.stat().st_size // 1024))
    # Ready to run from here too, for a box on this bench.
    unpacked = out_dir / "StreamBox-Deploy"
    shutil.rmtree(unpacked, ignore_errors=True)
    with zipfile.ZipFile(target) as z:
        z.extractall(out_dir)


if __name__ == "__main__":
    main()
