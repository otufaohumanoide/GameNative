#!/usr/bin/env bash
# QuickMenu verification suite (2026-08-11/2026-08-12) — run with the Mi 11 connected via USB.
# Verifies: (A) no unsolicited QuickMenu open at game start (invite regression fix),
#           (B) joystick navigation functional (T1-T6 from spec 2026-08-10),
#           (C) routing context OVERLAY with the menu open + ZERO game-side effects (M1/M2),
#           (D) bus listener registry stable across open/close cycles (M3, listenerCount ±0),
#           (E) gamepad traces present (M8 instrumentation).
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
H key:110                     # PS/BUTTON_MODE (close menu)
sleep 1.5
echo "--- QMFocus rows seen: $(grep -c 'QMFocus: row' "$LOG")"
echo "--- BusJoystick moves seen: $(grep -c 'BusJoystick: moveFocus' "$LOG")"
echo "--- QuickMenu bootstrap: $(grep -c 'QuickMenu bootstrap' "$LOG")"
echo "--- guardian restores: $(grep -c 'QuickMenu guardian' "$LOG")"
grep -E "QMFocus|BusJoystick|bootstrap|guardian" "$LOG" | tail -40

echo "== [C] Routing context with the menu open (M1/M2 — C1/C2) =="
# Open the menu, then pump a few keys/stick moves and verify EVERY GamepadRoute log shows
# ctx=OVERLAY and that NO game-side effect (refreshControllerMappingsForHotplug) ran.
H key:4; sleep 1.5
H key:96; H key:97; H key:103; H stick:0:0.8; H stick:0:0   # keys + stick with menu open
sleep 1.0
ROUTE_CTX=$(grep -c "GamepadRoute: key" "$LOG")
ROUTE_OVERLAY=$(grep -c "GamepadRoute: key.*ctx=OVERLAY" "$LOG")
HOTPLUG=$(grep -c "refreshControllerMappingsForHotplug" "$LOG")
echo "GamepadRoute key logs: $ROUTE_CTX ; with ctx=OVERLAY: $ROUTE_OVERLAY ; hotplug rescans: $HOTPLUG"
[ "$ROUTE_CTX" -gt 0 ] && [ "$ROUTE_CTX" -eq "$ROUTE_OVERLAY" ] && [ "$HOTPLUG" -eq 0 ] \
  && echo "PASS: overlay owns the gamepad; zero game-side effects behind the menu" \
  || echo "FAIL: routing leaked to the game (C1/C2) — check GamepadRoute logs"
H key:110; sleep 1.5   # close (PS/BUTTON_MODE)

echo "== [D] Bus listener registry stable across cycles (M3 — C3) =="
BEFORE_ON=$(grep -c "EventBus: on KeyEvent" "$LOG")
BEFORE_OFF=$(grep -c "EventBus: off KeyEvent" "$LOG")
for i in $(seq 1 5); do
  H key:4; sleep 0.8; H key:110; sleep 0.8   # open/close the menu (PS toggle)
done
AFTER_ON=$(grep -c "EventBus: on KeyEvent" "$LOG")
AFTER_OFF=$(grep -c "EventBus: off KeyEvent" "$LOG")
NET_ON=$(( AFTER_ON - BEFORE_ON ))
NET_OFF=$(( AFTER_OFF - BEFORE_OFF ))
echo "KeyEvent listener on/off deltas over 5 cycles: +$NET_ON / -$NET_OFF"
[ "$NET_ON" -eq "$NET_OFF" ] && echo "PASS: no listener accumulation" || echo "FAIL: listener leak (registry drift)"
grep -c "matched NOTHING" "$LOG" | xargs -I{} echo "off() identity misses (should be 0): {}"

echo "== [E] Instrumentation traces present (M8) =="
echo "--- GamepadTrace key lines: $(grep -c 'GamepadTrace: key' "$LOG")"
echo "--- GamepadTrace motion lines: $(grep -c 'GamepadTrace: motion' "$LOG")"
echo "--- EventBus on lines: $(grep -c 'EventBus: on' "$LOG")"
echo "--- BusGamepadKeyBridge listening: $(grep -c 'BusGamepadKeyBridge: listening' "$LOG")"
echo "--- BusGamepadKeyBridge stopped: $(grep -c 'BusGamepadKeyBridge: stopped' "$LOG")"
echo "--- QuickMenu mutex enter: $(grep -c 'QuickMenu bootstrap: mutex enter' "$LOG")"

echo "== [F] Dedupe decision logs (M4) — only meaningful on dual-channel controllers =="
echo "--- axis suppressions: $(grep -c 'BusJoystick: axis move suppressed' "$LOG")"
echo "--- key suppressions: $(grep -c 'BusGamepadKeyBridge: DPAD=.*suppressed' "$LOG")"

echo "== [G] Onda 2 — hub + gate (spec 2026-08-13-onda2) =="
# Gate ON for the hub checks; O1 (byte-identical with gate OFF) is the DEFAULT state,
# so flip it here and restore at the end.
adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'echo gamepadUniversalEnabled=true > /dev/null'" 2>/dev/null || true
echo "NOTE: gamepadUniversalEnabled lives in DataStore (not settable via adb) — use the app UI toggle;"
echo "      these greps validate the hub once the gate is ON on-device."
H key:4; sleep 1.5   # open the QuickMenu
H key:96; H key:97; H stick:0:0.8; H stick:0:0   # confirm + back + stick with menu open
sleep 1.0
echo "--- GamepadHub started: $(grep -c 'GamepadHub: started' "$LOG")"
echo "--- GamepadHub added devices: $(grep -c 'GamepadHub: added' "$LOG")"
echo "--- GamepadLogical emitted (gate ON): $(grep -c 'GamepadLogical:' "$LOG")"
echo "--- hub buttonStates clean on remove: $(grep -c 'GamepadHub: removed' "$LOG")"
H key:110; sleep 1.5   # close

echo "== [H] Upgrades do intuito (spec 2026-08-14-gamepad-intuito-validacao-upgrades) =="
echo "NOTE: U1/U2 exigem o gate gamepadUniversalEnabled ON (UI) e as prefs de touchpad/gyro;"
echo "      o harness entrega os verbos — a leitura do resultado é manual (cursor/câmera)."
H key:110; sleep 1.0
echo "--- U6 (LibraryScreen): navegar com A (confirm) e B (cancel) — com swap ON no
echo "    settings, B confirma e A cancela; Nintendo: direita confirma."
echo "--- U2 (touchpad): com gamepadTouchpadMouseEnabled ON, no jogo:"
H touch:0.6:0.5; sleep 0.2; H touch:0.4:0.5; sleep 0.2; H touch:0.6:0.5   # move cursor
H touchtap                                                               # clique
echo "    (ver cursor andando no Silksong; touchpad REAL do DS4 idem)"
echo "--- U1 (gyro): com perfil gyroMode=MOUSE e gate ON, no jogo:"
H gyro:0:0:-1; sleep 0.2; H gyro:0:0:0    # yaw sintético → cursor
echo "    (sensor real via BT: recenter na ativação; unregister em pause — grep GamepadSensor)"
echo "--- GamepadSensor lifecycle: $(grep -c 'GamepadSensor: gyro registered' "$LOG") registered / $(grep -c 'GamepadSensor: gyro unregistered' "$LOG") unregistered"
echo "--- U3/U4 (camadas): com layerTriggers no perfil e gate ON, segurar o trigger"
echo "    deve remapear no jogo; gate OFF = byte-identical (O1 da Onda 2)."
echo "--- U7: seção Gamepad mostra % + badges (DS4 via BT); API < 31 esconde."
H key:110; sleep 1.0

kill "$(cat /tmp/qm_logcat.pid)" 2>/dev/null
echo "Full log: $LOG"
