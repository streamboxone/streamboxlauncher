# StreamBox Updates

Reads a text file from the web, shows which apps on the box are out of date and which new ones are on offer, and
installs them. The launcher's Update pill opens it. It also shows the used and free storage; select that to open the Storage page in Settings.

It is its own app so it can update the launcher, and so Home never carries the install permissions.

## The file

`https://streambox.online/updates.txt` (`updates_url` in `strings.xml`). One app per line, separated by `|`; `#`
starts a comment:

```
# name | package | versionCode | versionName | uninstall first | address | sha256
Clip Box | org.clipbox.player | 42 | 1.4.2 | 0 | apks/clipbox.apk | 3f7a...
Voltra TV | com.amhkor.voltratv | 9 | 2.0 | 1 | https://streambox.online/apks/voltra.apk |
```

- **versionCode** decides: a higher number here than on the box is an update, and an app not on the box is a new app,
  installed only if someone picks it. It must be the APK's real versionCode.
- **uninstall first** is `1` when the APK is signed differently from the installed app, which Android won't install
  over. It erases that app's settings and sign-ins, and the row says so. The APK is downloaded and checked first, so a
  bad download never costs the installed app. Home and this app refuse the flag.
- **address** is HTTPS only. One without `https://` is taken as relative to the file.
- **sha256** may be left blank. When present, a download that doesn't match is not installed.
- **address** may point at an APK or at a split bundle (an `.xapk` from APKPure, or an `.apks`): every APK in it goes
  into one install. A bundle is checked by its base APK, and **sha256** covers the whole file. A bundle with OBB data
  files is refused. Updaters before 1.4 take only single APKs.

## Removing old apps

A line that starts with `remove` names an app that no longer works:

```
# remove | name | package | note (may be left out)
remove | Old TV | com.example.oldtv | stopped working
```

When one of those is on the box, a **Remove old apps** pill shows at the bottom. It switches the list to just those apps.
Selecting one uninstalls it, after Android's own confirmation, and **Back to updates** (or Back) returns. Home and this
app are refused, like the flag above. Updaters before 1.3 don't know these lines, and count each one as a line that
could not be read until they update.

`make-line.py` prints a line from an APK or a bundle, so none of this is typed by hand:

```bash
python make-line.py clipbox.apk
python make-line.py sesame.xapk
```

## Prompts

Android decides this, not the app:

| What | Prompt |
|---|---|
| Update of an app this one installed (Android 12 and newer) | None |
| First update of an app installed some other way | One; after that this app is its installer, and it is silent |
| New app | One |
| Uninstall first | Two: the uninstall and the new install |

Android 11 and older ask every time. An app whose target SDK is too old for Android's rules also still asks.

Install permission is needed once per box. This TV's Settings page opens on the first app in its list rather than on
this one, so set it during setup instead:

```bash
adb shell appops set ca.techshop.streamboxupdates REQUEST_INSTALL_PACKAGES allow
```

## Switching from an older launcher

Each time it opens, while StreamBox Launcher (`com.gathin.tvhome`) isn't Home and the file lists it, it asks whether to
switch. On Switch it installs or updates the launcher from the file, then opens Android's Home picker, where the person
chooses it. Only once Android reports StreamBox as Home does it uninstall Wolf Launcher (`com.wolf.firelauncher`) and
Projectivy Launcher (`com.spocky.projengmenu`), each with Android's own confirmation. If Home didn't change, or the box
has no Home picker, the old launchers stay.

Android goes straight to the new Home once it is picked, so the switch finishes the next time Updates opens: while
StreamBox is Home and an old launcher is still installed, it asks whether to uninstall it. The list is `OLD_LAUNCHERS` in `UpdatesActivity.java`.

## Permissions

- `INTERNET`: the file and the APKs.
- `REQUEST_INSTALL_PACKAGES` and `REQUEST_DELETE_PACKAGES`: installing, uninstalling first when the file says to, the old
  launchers after a switch, and the apps the file lists as old.
- `UPDATE_PACKAGES_WITHOUT_USER_ACTION`: lets Android skip the prompt for updates of apps this one installed.
- `QUERY_ALL_PACKAGES`: the installed version of whichever apps the file names, and which app is Home.

## Worth knowing

- What the file names gets offered for install, on every box that reads it, so the site's security matters. HTTPS and
  the checksum protect the download, not a changed file on the site, though each install of a new app and each
  uninstall still shows Android's prompt, and an update must carry the installed app's signature.
- Updating the launcher through this switches its remote-button helper off: Android does that on every reinstall of an
  accessibility service. Turn it back on afterwards.

## Build

JDK 17 and Android SDK 34, then from this folder:

```bash
sh gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```
