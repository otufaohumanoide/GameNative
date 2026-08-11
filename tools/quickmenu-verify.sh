#!/usr/bin/env bash
# QuickMenu verification suite (2026-08-11) — run with the Mi 11 connected via USB.
# Verifies: (A) no unsolicited QuickMenu open at game start (invite regression fix),
#           (B) joystick navigation functional (T1-T6 from spec 2026-08-10).
# Usage: tools/quickmenu-verify.sh [app_id]   (default: Silksong 1030300)
set -u
APP_ID="${1:-1030300}"
PKG=app.gamenative
LOG=/tmp/quickmenu_verify_$(date +%H%M%S).log

adb wait-for-device
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell logcat -G 16777216
adb logcat -c

# Start a continuous host-side logcat capture.
( adb logcat > "$LOG" 2>&1 & echo $! > /tmp/qm_logcat.pid )

echo "== [A] Launch game, watch 25s for UNSOLICITED QuickMenu open =="
adb shell am start -a app.gamenative.LAUNCH_GAME -n app.gamenative/.MainActivity --ei app_id "$APP_ID" --es game_source STEAM
sleep 25
# An unsolicited open shows: QuickMenu bootstrap + SteamInviteState game requested + no input before it
UNSOL=$(grep -c "QuickMenu bootstrap" "$LOG")
REQ=$(grep -c "SteamInviteState: game requested" "$LOG")
echo "unsolicited bootstrap lines: $UNSOL ; invite requests consumed: $REQ"
[ "$UNSOL" -eq 0 ] && echo "PASS: no unsolicited QuickMenu open at game start" || echo "FAIL: QuickMenu opened without user input"

echo "== [B] Joystick navigation (harness, no physical controller needed) =="
H() { adb shell setprop debug.gamenative.input "$1"; sleep 0.4; }
H key:4            # back -> open QuickMenu (menu closed)
sleep 1.5
grep -E "QuickMenu bootstrap" "$LOG" | tail -1
H stick:0:0.8; H stick:0:0    # stick down
H stick:0:0.8; H stick:0:0    # stick down x2
H stick:0:-0.8; H stick:0:0   # stick up
H key:103; H key:102          # R1 / L1 (tab cycle)
H key:104; H key:105          # L2 / R2 (page scroll)
H key:96                      # A (activate focused row)
H key:97                      # B (hierarchical back)
H key:188                     # PS (close menu)
sleep 1.5
echo "--- QMFocus rows seen: $(grep -c 'QMFocus: row' "$LOG")"
echo "--- BusJoystick moves seen: $(grep -c 'BusJoystick: moveFocus' "$LOG")"
echo "--- QuickMenu bootstrap: $(grep -c 'QuickMenu bootstrap' "$LOG")"
echo "--- guardian restores: $(grep -c 'QuickMenu guardian' "$LOG")"
grep -E "QMFocus|BusJoystick|bootstrap|guardian" "$LOG" | tail -40
kill "$(cat /tmp/qm_logcat.pid)" 2>/dev/null
echo "Full log: $LOG"
