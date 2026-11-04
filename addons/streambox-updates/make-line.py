#!/usr/bin/env python3
"""Print the line for updates.txt that describes an APK, so nothing is typed by hand.

Usage: make-line.py <app.apk> [address] [--uninstall-first]

  address            where the APK will be on the web; default apks/<file name>, beside updates.txt
  --uninstall-first  sets the flag to 1, for an APK signed differently from the installed app

Needs aapt from the Android SDK's build-tools: set ANDROID_HOME or AAPT if it isn't found.
"""
import glob
import hashlib
import os
import re
import subprocess
import sys


def aapt():
    if os.environ.get("AAPT"):
        return os.environ["AAPT"]
    home = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", "aapt*")))
    found = [f for f in found if os.path.basename(f) in ("aapt", "aapt.exe")]
    if not found:
        sys.exit("aapt not found: set ANDROID_HOME or AAPT")
    return found[-1]


def main(args):
    flag = "1" if "--uninstall-first" in args else "0"
    args = [a for a in args if a != "--uninstall-first"]
    if not 1 <= len(args) <= 2:
        sys.exit(__doc__)
    apk = args[0]
    badging = subprocess.run([aapt(), "dump", "badging", apk], capture_output=True, text=True, encoding="utf-8",
                             errors="replace", check=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not package:
        sys.exit("could not read the package name and version from " + apk)
    label = re.search(r"application-label:'([^']*)'", badging)
    with open(apk, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    address = args[1] if len(args) == 2 else "apks/" + os.path.basename(apk)
    name = (label.group(1) if label else package.group(1)).replace("|", " ")
    print(" | ".join([name, package.group(1), package.group(2), package.group(3), flag, address, digest]))


if __name__ == "__main__":
    main(sys.argv[1:])
