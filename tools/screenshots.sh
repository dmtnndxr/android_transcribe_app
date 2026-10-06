#!/usr/bin/env bash
# Retakes the README screenshots on a connected phone.
#
#   tools/screenshots.sh            # light theme -> .screenshots/
#   tools/screenshots.sh dark       # dark theme  -> .screenshots/dark/
#   tools/screenshots.sh all        # both
#
# The phone has to hear this Mac: the demo phrases are spoken through the
# speakers with `say`, so put the phone next to them. The volume is raised
# for the run and put back afterwards.
#
# What it needs on the phone:
#   - Boice installed, its keyboard enabled, AI post-processing
#     configured (the AI and voice-edit scenes call the real server);
#   - a streaming speech model active, for the live-text shots;
#   - TextPad (com.maxistar.textpad) as a scratch text field. Its unsaved
#     draft is discarded;
#   - English system language, screen unlocked.
#
# Tap positions of the keyboard buttons are fractions of the screen, tuned
# on a 720x1604 phone. Everything else is found by its label.
#
# Privacy: the status bar is put into demo mode, the API key field is masked
# by the app, and the history is filtered to the demo phrases. Look through
# the results before committing them anyway.
#
# Rotation, theme, keyboard and volume are restored on exit.

set -u

ADB="${ADB:-adb}"
APP=io.github.dmtnndxr.boice
IME="$APP/dev.notune.transcribe.RustInputMethodService"
EDITOR_APP=com.maxistar.textpad
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VOLUME="${VOLUME:-60}"

PHRASE_PLAIN="Okay. Hi Anna, the meeting moved to Thursday at three. Could you bring the printed report?"
PHRASE_AI="Okay. So um, I think we should, like, ship the update on Friday, no wait, on Monday, and uh tell the team about it today."
PHRASE_EDIT="Make it more formal and polite."
HISTORY_QUERY="team"

read -r W H < <("$ADB" shell wm size | tr -dc '0-9x\n' | tail -1 | tr 'x' ' ')
px() { echo $(( W * $1 / 1000 )); }
py() { echo $(( H * $1 / 1000 )); }
MIC="$(px 500) $(py 670)"
AI_MIC="$(px 826) $(py 658)"
WAND="$(px 167) $(py 658)"

OLD_IME="$("$ADB" shell settings get secure default_input_method | tr -d '\r')"
OLD_ACCEL="$("$ADB" shell settings get system accelerometer_rotation | tr -d '\r')"
OLD_NIGHT="$("$ADB" shell cmd uimode night | tr -d '\r' | sed 's/.*: //')"
OLD_VOLUME="$(osascript -e 'output volume of (get volume settings)')"

restore() {
    "$ADB" shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
    "$ADB" shell cmd uimode night "$OLD_NIGHT" >/dev/null
    "$ADB" shell ime set "$OLD_IME" >/dev/null
    "$ADB" shell wm set-ignore-orientation-request false
    "$ADB" shell wm user-rotation free
    "$ADB" shell settings put system accelerometer_rotation "$OLD_ACCEL"
    "$ADB" shell input keyevent KEYCODE_HOME
    osascript -e "set volume output volume $OLD_VOLUME"
}
trap restore EXIT

demo() { "$ADB" shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

clean_status_bar() {
    "$ADB" shell settings put global sysui_demo_allowed 1
    demo enter
    demo clock -e hhmm 0900
    demo battery -e level 100 -e plugged false
    demo notifications -e visible false
    demo network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none
}

portrait() {
    "$ADB" shell settings put system accelerometer_rotation 0
    "$ADB" shell wm set-ignore-orientation-request true
    "$ADB" shell wm user-rotation lock 0
}

# Some apps ask for landscape on their own; re-lock and retake if a shot
# comes out sideways.
shot() {
    local f="$OUT/$1.png" w h
    for _ in 1 2; do
        "$ADB" exec-out screencap -p > "$f"
        read -r w h < <(sips -g pixelWidth -g pixelHeight "$f" | awk '/pixel/{print $2}' | paste -sd' ' -)
        [ "$w" -lt "$h" ] && { echo "  $1.png"; return 0; }
        portrait; sleep 2
    done
    echo "  $1.png is landscape, check it" >&2
}
tap() { "$ADB" shell input tap "$1" "$2"; }
back() { "$ADB" shell input keyevent KEYCODE_BACK; sleep 1; }
scroll_down() { "$ADB" shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H * 3 / 8)) 300; sleep 0.7; }
scroll_to_top() {
    for _ in 1 2 3 4 5; do
        "$ADB" shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 9 / 10)) 150
    done
    sleep 1
}

# Taps the first view with this exact text, scrolling down to find it.
tap_text() {
    local b x1 y1 x2 y2 y
    for _ in 1 2 3 4 5 6 7 8; do
        "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        b="$("$ADB" shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" \
            | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | tr -c '0-9' ' ')"
        if [ -n "$b" ]; then
            read -r x1 y1 x2 y2 <<< "$b"
            y=$(( (y1 + y2) / 2 ))
            if [ "$y" -lt $((H * 9 / 10)) ]; then
                tap $(( (x1 + x2) / 2 )) "$y"
                return 0
            fi
        fi
        [ "${2:-}" = "noscroll" ] && return 1
        scroll_down
    done
    echo "  not found: $1" >&2
    return 1
}

open_app() {
    "$ADB" shell cmd statusbar collapse
    "$ADB" shell am start -n "$APP/dev.notune.transcribe.MainActivity" >/dev/null 2>&1; sleep 2
}

open_editor() {
    "$ADB" shell monkey -p "$EDITOR_APP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 2
    tap_text "DISCARD DRAFT" noscroll && sleep 1
    tap $((W / 2)) $((H / 3)); sleep 2
    clear_field
}

# Empties the editor; checks for TextPad's hint because a key sent while
# the keyboard is still busy can get lost.
clear_field() {
    for _ in 1 2 3; do
        "$ADB" shell input keycombination 113 29   # Ctrl+A
        sleep 0.3
        "$ADB" shell input keyevent 67             # Backspace
        sleep 1
        "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        "$ADB" shell cat /sdcard/ui.xml | grep -q 'text="Type Something Here"' && return 0
    done
    echo "  couldn't clear the editor" >&2
}

# speak <button x> <button y> <phrase> <shot while listening>
speak() {
    tap "$1" "$2"; sleep 3
    say -r 160 "$3"; sleep 0.8
    shot "$4"
    tap "$1" "$2"
}

run() {
    local night="$1"
    if [ "$night" = yes ]; then OUT="$ROOT/.screenshots/dark"; else OUT="$ROOT/.screenshots"; fi
    mkdir -p "$OUT"
    echo "Theme: night=$night -> $OUT"

    "$ADB" shell cmd uimode night "$night" >/dev/null; sleep 2
    portrait; sleep 2
    clean_status_bar

    open_app; scroll_to_top
    shot home

    tap_text "Run setup again"; sleep 2
    shot onboarding_welcome
    tap_text "Next" noscroll; sleep 1
    tap_text "Next" noscroll; sleep 1.5
    shot onboarding_keyboard
    back; back; back

    "$ADB" shell ime set "$IME" >/dev/null
    open_editor

    speak $MIC "$PHRASE_PLAIN" keyboard_recording
    sleep 8; shot keyboard_dictated

    clear_field
    speak $AI_MIC "$PHRASE_AI" ai_listening
    sleep 12; shot ai_result

    "$ADB" shell input keycombination 113 29; sleep 1.5
    shot edit_selected
    speak $WAND "$PHRASE_EDIT" edit_listening
    sleep 9; shot edit_result

    open_app; scroll_to_top
    tap_text "Open history"; sleep 2
    tap $((W / 2)) $(py 176); sleep 1          # search field
    "$ADB" shell input text "$HISTORY_QUERY"; sleep 1
    back
    tap $((W / 2)) $(py 310); sleep 1.5         # expand the newest entry
    shot history
    back

    scroll_to_top
    tap_text "Manage speech models"; sleep 2; shot models; back
    tap_text "Set up AI post-processing"; sleep 2; shot ai_settings; back
}

osascript -e "set volume output volume $VOLUME"
"$ADB" shell input keyevent KEYCODE_WAKEUP

case "${1:-light}" in
    light) run no ;;
    dark)  run yes ;;
    all)   run no; run yes ;;
    *) echo "usage: $0 [light|dark|all]" >&2; exit 2 ;;
esac
