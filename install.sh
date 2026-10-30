#!/bin/sh
# Installs QuietFrame on a Google TV over the network and makes Home open it.
#
#   sh install.sh <TV_IP>             Android TV 11 style network debugging (port 5555)
#   sh install.sh <TV_IP>:<PORT>      Wireless debugging, after `adb pair` (see README)
#   APK=path/to/quietframe.apk sh install.sh <TV_IP>
#   REDIRECT=0 sh install.sh <TV_IP>  install only; leave the Home button alone
#
# Nothing on the TV is turned off or uninstalled. Undo everything with uninstall.sh.
set -e
cd "$(dirname "$0")"

TV=$1
APK=${APK:-out/quietframe.apk}
PKG=app.quietframe
SERVICE=$PKG/.HomeRedirect
GOOGLE_TV=com.google.android.apps.tv.launcherx

[ -n "$TV" ] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
command -v adb >/dev/null || { echo "adb not found. macOS: brew install android-platform-tools" >&2; exit 1; }
[ -f "$APK" ] || { echo "No APK at $APK. Run build.sh, or download it from GitHub Actions and set APK=..." >&2; exit 1; }
case "$TV" in *:*) ;; *) TV=$TV:5555 ;; esac

echo "Connecting to $TV. If the TV asks \"Allow debugging?\", tick Always allow and press Allow."
adb connect "$TV" >/dev/null || true
i=0
until [ "$(adb -s "$TV" get-state 2>/dev/null)" = device ]; do
  i=$((i + 1))
  [ $i -le 30 ] || { echo "Couldn't reach the TV. Is debugging on, and is the address right?" >&2; exit 1; }
  sleep 2
  adb connect "$TV" >/dev/null 2>&1 || true
done
A="adb -s $TV"

echo "TV: $($A shell getprop ro.product.manufacturer | tr -d '\r') $($A shell getprop ro.product.model | tr -d '\r'), Android $($A shell getprop ro.build.version.release | tr -d '\r')"
if ! $A shell pm path "$GOOGLE_TV" 2>/dev/null | grep -q package:; then
  echo "Note: Google TV's home ($GOOGLE_TV) isn't on this TV, so the Home button redirect won't do anything."
  echo "      QuietFrame still installs and opens from the apps row."
fi

echo "Installing $APK"
$A install -r "$APK"

if [ "${REDIRECT:-1}" = 1 ]; then
  echo "Turning on the QuietFrame Home button (keeps any other accessibility services you use)"
  # One remote shell, so the read-modify-write of the setting can't interleave with anything else.
  $A shell "
    s=\$(settings get secure enabled_accessibility_services)
    case \"\$s\" in null) s='' ;; esac
    case \":\$s:\" in
      *:$SERVICE:*|*:$PKG/$PKG.HomeRedirect:*) ;;
      *) s=\"\${s:+\$s:}$SERVICE\" ;;
    esac
    settings put secure enabled_accessibility_services \"\$s\"
    settings put secure accessibility_enabled 1
  "
  if $A shell settings get secure enabled_accessibility_services | grep -q "$PKG"; then
    echo "Home button: on"
  else
    echo "Couldn't turn it on from here. On the TV: Settings > Accessibility > QuietFrame Home button." >&2
  fi
fi

echo "Opening QuietFrame"
$A shell am start -n "$PKG/.HomeActivity" >/dev/null
sleep 4
echo "Pressing Home to test the redirect"
$A shell input keyevent KEYCODE_HOME
sleep 4
if $A shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | grep -q "$PKG"; then
  echo "Home opened QuietFrame."
else
  echo "Home didn't land on QuietFrame. What's focused now:"
  $A shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -2
  echo "Send that output along when asking for help. QuietFrame still opens from the apps row."
fi

cat <<EOF

Done. Two things to do on the TV now:
  1. Settings > System > Developer options: turn off USB debugging (and Wireless/Network
     debugging). Left on, other devices on your network can ask to control the TV.
  2. Press Home on the remote and check QuietFrame comes up.

To undo: sh uninstall.sh $TV  (needs debugging on again)
EOF
