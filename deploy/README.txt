StreamBox: setting up a new box

You need a Windows PC on the same network as the box, and Android platform-tools (adb). If adb isn't found, unzip
platform-tools into this folder: https://developer.android.com/tools/releases/platform-tools

1. Finish the box's own setup, and join your Wi-Fi.

2. On the box: Settings > System > About, press Android TV OS build seven times to unlock Developer options.
   Then Settings > System > Developer options > turn on Wireless debugging.

3. Pair once. Under Wireless debugging, choose "Pair device with pairing code", then on the PC:

       adb pair <box ip>:<pairing port>

   Type the code it shows. Then use the IP address and port shown on the Wireless debugging page below.

4. Run:

       install.bat <box ip>:<port>

   Add -Debloat on an Onn 4K Pro to also turn off the unwanted apps in debloat-onn-4k-pro.txt.

   If it stops, it says why, and running it again is safe. The usual one on a new box: the saved Wi-Fi belongs to the
   setup wizard. On the box, forget the network and join it again, then run this again. The port may change.

5. On the box, restore your layout: on Home, hold OK on Settings, then Launcher settings, Backup and restore.
   Restore from a web address, or from a file.

6. Close debugging. Turn off Wireless debugging and USB debugging, choose to forget this PC, restart the box, then:

       install.bat <box ip> -CheckClosed

   Only CLOSED counts. UNKNOWN means the box didn't answer, so check that it's awake and try again.

Other apps come from the Update pill on Home, which reads https://streambox.online/updates.txt.
-UndoDebloat turns the listed apps back on.
