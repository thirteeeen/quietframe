# QuietFrame

A quieter home screen for Google TV: a photograph, the time and your apps, made for the remote.
It was built for a **BAUHN 62" smart TV** (ALDI), and works with both the newer Google TV home
screen and the classic Android TV one (`com.google.android.tvlauncher`), which the BAUHN uses.

Inspired by [CleanFrame](https://github.com/nthnclrk/CleanFrame), a calm home screen for Samsung
The Frame. CleanFrame is a Tizen web app, so it can't run on Google TV. This is a separate Android
app that follows the same idea. The Home button approach comes from
[TV Detox](https://github.com/Ansub/tv-detox).

> **Status: untested on hardware.** The code compiles against the Android 14 framework, but it
> hasn't run on a BAUHN TV yet. Expect to fix small things on first install. Everything is
> reversible: see [Undo](#undo).

## What it does

- **Home:** a wallpaper, a large clock and date, and up to 12 apps. Choose a **Dock** (a row of app
  banners along the bottom) or a **List** (large names down the left).
- **Remote-first:** arrows move, OK opens. Focus alone never launches anything. Up from the dock
  (or Right from the list) reaches **Inputs · All apps · Settings**. Down or Left goes back to the
  app you were on.
- **All apps:** every TV app. Hold OK on one to add it to Home or remove it.
- **Apps on Home:** reorder (OK on a name, then Up/Down and OK), remove, or add apps.
- **Inputs:** your HDMI ports, one press away.
- **Settings** (or the Menu key): Photo, Dark or Light background, six built-in wallpapers plus
  your own photos, rotation (never, each time Home opens, or hourly), Dock or List, labels, and a
  12 or 24-hour clock. Changes apply as you make them; there's no Save button.
- **Back on Home** offers to open Google TV's own home, for search or anything QuietFrame doesn't do.

## How Home works on Google TV (and why it's safe)

Google TV doesn't let you choose a different home app. Turning off its launcher
(`com.google.android.apps.tv.launcherx`) is the usual workaround, but it has **bootlooped some
Google TVs, needing a factory reset**. QuietFrame doesn't do that.

QuietFrame turns nothing off. Its small accessibility service, **QuietFrame Home button**, is told
when Google TV's home screen opens, and opens QuietFrame over it. It can't see your key presses or
read the screen: it only gets the "Google TV home opened" event.

- To undo, switch it off in **Settings › Accessibility**. Home goes straight back to Google TV.
- If QuietFrame fails to open three times in a row, the redirect stops by itself until you open
  QuietFrame from the apps row.
- If you choose **Open Google TV home**, Home stays with Google TV until you open QuietFrame again
  or restart the TV.
- During the TV's first-time setup, it does nothing.

The trade-off is that pressing Home may briefly flash Google TV's home before QuietFrame appears.

## 1. Get the APK

**Option A: let GitHub build it (no Android tools needed).** Push this folder to a GitHub repo. The
**Build APK** workflow runs automatically. Open the run under **Actions** and download
`quietframe-apk`.

To install later builds over earlier ones, every build must use the same signing key. Make one once
and add it as repository secrets:

```bash
keytool -genkeypair -keystore key.jks -storepass CHOOSE_A_PASSWORD -keypass CHOOSE_A_PASSWORD -alias quietframe -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=QuietFrame"
```

```bash
base64 -i key.jks | pbcopy
```

Paste that as secret `KEYSTORE_B64`, and the password as `KEYSTORE_PASS`. Keep `key.jks` somewhere
safe, outside the repo.

**Option B: build locally.** Install Java 17 and the Android command-line tools, then run:

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

```bash
sh build.sh
```

## 2. Turn on debugging on the BAUHN TV

1. Open **Settings › System › About**. Scroll to **Android TV OS build** and press OK on it 7 times,
   until it says you're a developer.
2. Go back to **Settings › System › Developer options** and turn on **USB debugging**.
3. Find the TV's IP address in **Settings › Network & Internet**: select your Wi-Fi network.

Menu names vary a little between firmware versions. Some newer Google TVs only offer **Wireless
debugging**, which needs pairing first:

1. Under **Developer options › Wireless debugging**, choose **Pair device with pairing code**.
2. Run the command below on your computer, using the IP and port shown under the code:

   ```bash
   adb pair TV_IP:PAIRING_PORT
   ```

3. Use the *other* IP:port shown on the Wireless debugging screen for the install script.

## 3. Install

Install `adb`. On macOS: `brew install android-platform-tools`. Your computer and the TV must be on
the same network. Then run:

```bash
sh install.sh 192.168.1.50
```

Replace the address with your TV's IP (or `IP:PORT` for Wireless debugging). If the APK came from
GitHub:

```bash
APK=~/Downloads/quietframe.apk sh install.sh 192.168.1.50
```

The script:

1. Connects. Accept **Allow debugging?** on the TV.
2. Installs the app.
3. Turns on the Home button service, keeping any other accessibility services you use.
4. Presses Home to check that QuietFrame comes up.

**Afterwards, turn USB / Wireless debugging off again.** Left on, other devices on your network can
ask to control the TV.

### Without a computer

1. Put the APK somewhere the TV can download it.
2. Install it with an app such as *Downloader*, allowing it under **Install unknown apps**.
3. Turn on **Settings › Accessibility › QuietFrame Home button**. On Android 13+ you may first need
   **Settings › Apps › QuietFrame › ⋮ › Allow restricted settings**.

## Your own wallpapers

Copy photos into QuietFrame's folder on the TV, then pick them in **Settings › Wallpaper**:

```bash
adb push my-photo.jpg /sdcard/Android/data/app.quietframe/files/wallpapers/
```

JPEG, PNG and WebP all work. Landscape photos of at least 1920×1080 look best. Settings › About
shows the exact folder.

## Undo

Turn off **Settings › Accessibility › QuietFrame Home button**, then uninstall QuietFrame from
**Settings › Apps**. Or, with debugging on:

```bash
sh uninstall.sh 192.168.1.50
```

Nothing else on the TV was changed.

## Make it yours with a coding agent

```text
Read README.md and src/app/quietframe/HomeActivity.java.
My change: <describe one change>.
Keep remote navigation working (arrows, OK, Back, Menu), keep saved preferences compatible,
and don't disable or uninstall any system app on the TV.
```

For a faster TV overall (removing bloat and ads), TV Detox's cleanup works on Google TV. **Don't**
let anything disable `com.google.android.apps.tv.launcherx`.

## Files

| Path | What it is |
| --- | --- |
| `src/app/quietframe/HomeActivity.java` | The whole interface: Home, Settings, All apps, the Home editor, Inputs |
| `src/app/quietframe/HomeRedirect.java` | The Home button accessibility service, with its safety checks |
| `src/app/quietframe/AppCatalog.java` | Finding, sorting and launching TV apps; the default Home apps |
| `src/app/quietframe/Wallpapers.java` | Built-in gradient wallpapers and your own photos |
| `src/app/quietframe/Prefs.java` | Saved settings |
| `build.sh`, `.github/workflows/build.yml` | Builds without Gradle, locally or on GitHub |
| `install.sh`, `uninstall.sh` | Set up and undo over adb |
