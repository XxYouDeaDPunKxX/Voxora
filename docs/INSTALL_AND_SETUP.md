# Install and set up Voxora on Wear OS

This guide covers Voxora 0.4.1 (`versionCode 10`, package `local.watchvoice`),
tested on a Galaxy Watch7. The watch menus are in Italian. Voice-language
settings do not translate them.

## What to download

The source code is available in this repository. A prebuilt file named
`Voxora-0.4.1-debug.apk` is prepared separately for a GitHub Release; it is
not part of the Git source tree. Until a release is published, there is no
public APK download.

For the prebuilt installation, use the APK, the companion `SHA256SUMS.txt`,
and `tools/provision-once.mjs` from the release bundle. The same helper lives at
[`tools/provision-once.mjs`](../tools/provision-once.mjs) in the source tree.

The APK is debuggable because account setup uses Android's `run-as` command.
It is not a production build or a Play Store release.

## Requirements

- A Wear OS watch with Wi-Fi and wireless debugging available.
- A Windows, macOS, or Linux computer with the official
  [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
  installed, so the `adb` command works.
- [Node.js](https://nodejs.org/) 18 or later **only for account setup**.
- Your own ChatGPT session, already signed in on the computer.

You do not need Android Studio, Gradle, or a JDK to install the prebuilt APK.

## 1. Verify the APK

Compare its SHA-256 with the value in the accompanying `SHA256SUMS.txt`.
For example, in PowerShell:

```powershell
Get-FileHash .\Voxora-0.4.1-debug.apk -Algorithm SHA256
```

On macOS/Linux:

```sh
shasum -a 256 Voxora-0.4.1-debug.apk
```

If the values differ, do not install the file.

## 2. Connect the Watch to ADB

Enable developer options and **Wireless debugging** on the Watch. The menu
names and pairing steps can vary by Wear OS version. With your computer and
Watch on the same network, use the pairing address shown by the Watch:

```sh
adb pair WATCH_IP:PAIRING_PORT
```

Enter the pairing code displayed on the Watch. Then use the Watch's **debugging
address**, which can have a *different port* from the pairing address:

```sh
adb connect WATCH_IP:DEBUG_PORT
adb devices
```

Confirm that the watch appears with status `device`. If other Android devices
are connected, use `adb -s DEVICE` in commands that target the Watch.

## 3. Install the APK

From the directory containing the downloaded APK:

```sh
adb install -r Voxora-0.4.1-debug.apk
```

For multiple connected devices, use `adb -s DEVICE install -r ...`.
You can open Voxora on the watch now. To use ChatGPT, complete the account
setup below.

If updating an existing Voxora installation, Android requires the **same
signing certificate**. This APK is signed with an Android Debug certificate.
Do not uninstall an existing copy merely to work around a signature error if
you need its saved account or preferences.

## 4. Transfer your own session

After installing the APK, transfer your session from a computer to Voxora's
private storage on the watch. The APK does not include account credentials.

1. In a desktop browser where you are signed into `https://chatgpt.com`,
   open Developer Tools → **Application** (or **Storage**) → **Cookies** →
   `https://chatgpt.com`.
2. Find the **`oai-did`** cookie and note its value. This is the `deviceId`
   expected by the existing Voxora code.
3. Find your **`__Secure-next-auth.session-token`** cookie. If the browser
   shows numbered chunks such as
   `__Secure-next-auth.session-token.0` and
   `__Secure-next-auth.session-token.1`, collect **all** of those chunks.
   Use the cookie names exactly as displayed. If the required cookies are not
   present, the current provisioning method may not work for that session.
4. With the Watch still connected over ADB, run the following command from
   the root of either the extracted release bundle or the source checkout:

   ```sh
   node tools/provision-once.mjs
   ```

5. The helper prints a **local `127.0.0.1` address**. Open that address on
   the same computer. Paste the device ID into the first field and each
   session cookie as `name=value` on a separate line in the second field.
   Click **Transfer to Watch**.
6. When the transfer completes, close the browser tab. Open Voxora on the
   Watch and start an online operation so the app can import and encrypt the
   session. The incoming plaintext file is removed by the app after import.

If more than one ADB device is connected, specify the watch explicitly:

```sh
node tools/provision-once.mjs --serial WATCH_IP:DEBUG_PORT
```

If `adb` is not on your `PATH`, you can provide its executable location:
`node tools/provision-once.mjs --adb "PATH_TO_ADB"`.

**Treat session cookies like passwords.** Do not save them in source files,
terminal commands, screenshots, Git issues, or messages. The helper binds only
to your computer's loopback interface, uses a one-time local form, transfers
the payload to ADB over stdin, does not log session values, and closes after
15 minutes. The raw transfer file exists briefly in the Watch app's private
storage until Voxora imports it.

## Troubleshooting and limitations

- **No ADB device:** Pair and connect again; the wireless debugging port can
  change between sessions.
- **`run-as` fails:** Check that `local.watchvoice` is installed and that
  you used the provided **debug** APK; the current helper cannot provision a
  non-debuggable release build.
- **Authentication fails:** Confirm the cookie values came from your own
  currently signed-in session. Expired or changed ChatGPT sessions may require
  another transfer. The integration uses ChatGPT web interfaces that may
  change independently of Voxora.
- **Upgrade rejected:** The installed APK was signed with a different
  certificate; preserve its data before taking further action.

The app has been tested on a Galaxy Watch7. This installation and account
setup process has not yet been independently tested on another user's watch.

After setup, continue with [Using Voxora](USING_VOXORA.md) for the controls and
workflows. If a connection, account, chat, or audio operation fails, use the
[Troubleshooting guide](TROUBLESHOOTING.md) for recovery steps.
