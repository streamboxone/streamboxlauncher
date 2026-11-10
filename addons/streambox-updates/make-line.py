#!/usr/bin/env python3
"""Print the line for updates.txt that describes an APK, so nothing is typed by hand.

Usage: make-line.py <app.apk | app.xapk | app.apks> [address] [--uninstall-first]

  A split bundle (XAPK or APKS) is read from its base APK; the sha256 is of the whole file.

  address            where the file will be on the web; default apks/<file name>, beside updates.txt
  --uninstall-first  sets the flag to 1, for an APK signed differently from the installed app

Needs aapt from the Android SDK's build-tools: set ANDROID_HOME or AAPT if it isn't found.
"""
import glob
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import zipfile


def aapt():
    if os.environ.get("AAPT"):
        return os.environ["AAPT"]
    home = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", "aapt*")))
    found = [f for f in found if os.path.basename(f) in ("aapt", "aapt.exe")]
    if not found:
        sys.exit("aapt not found: set ANDROID_HOME or AAPT")
    return found[-1]


def bundle_base(path):
    """The base APK's name inside a split bundle, or None for a plain APK (the same rule as the updater's SplitBundle)."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
    if "AndroidManifest.xml" in names:
        return None
    apks = [n for n in names if "/" not in n and n.lower().endswith(".apk")]
    if not apks:
        sys.exit(path + " has no APKs in it")
    if any(n.lower().endswith(".obb") for n in names):
        sys.exit(path + " has OBB data files, which the updater refuses")
    for n in apks:
        if n == "base.apk":
            return n
    plain = [n for n in apks if not n.startswith(("config.", "split_"))]
    return (plain or apks)[0]


def badging(apk):
    return subprocess.run([aapt(), "dump", "badging", apk], capture_output=True, text=True, encoding="utf-8",
                          errors="replace", check=True).stdout


def main(args):
    flag = "1" if "--uninstall-first" in args else "0"
    args = [a for a in args if a != "--uninstall-first"]
    if not 1 <= len(args) <= 2:
        sys.exit(__doc__)
    apk = args[0]
    base = bundle_base(apk)
    if base is None:
        text = badging(apk)
    else:
        with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
            text = badging(z.extract(base, tmp))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text)
    if not package:
        sys.exit("could not read the package name and version from " + apk)
    label = re.search(r"application-label:'([^']*)'", text)
    with open(apk, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    address = args[1] if len(args) == 2 else "apks/" + os.path.basename(apk)
    name = (label.group(1) if label else package.group(1)).replace("|", " ")
    print(" | ".join([name, package.group(1), package.group(2), package.group(3), flag, address, digest]))


if __name__ == "__main__":
    main(sys.argv[1:])
