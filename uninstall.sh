#!/bin/sh
# Turns off the QuietFrame Home button and uninstalls QuietFrame. Home goes straight back to Google TV.
#   sh uninstall.sh <TV_IP>[:<PORT>]
# Without a computer: Settings > Accessibility > QuietFrame Home button > off,
# then Settings > Apps > QuietFrame > Uninstall.
set -e
TV=$1
PKG=app.quietframe
[ -n "$TV" ] || { sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
case "$TV" in *:*) ;; *) TV=$TV:5555 ;; esac
adb connect "$TV" >/dev/null || true
A="adb -s $TV"

# Remove only our service, keeping any others (like TalkBack) enabled.
$A shell "
  s=\$(settings get secure enabled_accessibility_services)
  keep=''
  IFS=:
  for x in \$s; do
    case \"\$x\" in ''|null|$PKG/*) ;; *) keep=\"\${keep:+\$keep:}\$x\" ;; esac
  done
  if [ -n \"\$keep\" ]; then settings put secure enabled_accessibility_services \"\$keep\"
  else settings delete secure enabled_accessibility_services; fi
"
$A uninstall "$PKG" || true
echo "QuietFrame removed. Remember to turn debugging off again on the TV."
