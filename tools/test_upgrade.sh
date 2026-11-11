#!/usr/bin/env bash
set -euo pipefail
adb root
adb wait-for-device
adb install old/*.apk
PKG=com.tirso.retrostream
adb shell am start -W -n "$PKG/com.tirso.tirsolador.MainActivity"
OLD_UID=$(adb shell stat -c %u /data/user/0/$PKG | tr -d '\r\n')
adb shell "mkdir -p /data/user/0/$PKG/files; echo preserved > /data/user/0/$PKG/files/upgrade-marker; chown $OLD_UID:$OLD_UID /data/user/0/$PKG/files/upgrade-marker"
adb install -r dist/Tirsolador-v1.0.1.apk
NEW_UID=$(adb shell stat -c %u /data/user/0/$PKG | tr -d '\r\n')
[ "$OLD_UID" = "$NEW_UID" ]
result=$(adb shell cat /data/user/0/$PKG/files/upgrade-marker | tr -d '\r\n')
[ "$result" = preserved ]
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/com.tirso.tirsolador.MainActivity"
sleep 3
adb shell pidof "$PKG"
echo 'Existing installation updated successfully; UID and private data preserved.'
