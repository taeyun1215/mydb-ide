#!/bin/sh
# Launch the built MyDB IDE in isolation (own config/system/log dirs) for checks and parity runs.
# tools/mydb-run.sh extract copy the app out of the latest DMG
# tools/mydb-run.sh start [project] start it (optionally opening a project dir); prints the PID
# tools/mydb-run.sh stop quit it
# tools/mydb-run.sh reset forget config/system/log (first-run state)
# tools/mydb-run.sh trust <dir> trust every project under <dir> (no trust prompt on open)
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
run="${MYDB_RUN_DIR:-${TMPDIR:-/tmp}/mydb-run}"
app="$run/MyDB IDE.app"
case "${1:-}" in
  extract)
    dmg=$(ls -t "$root"/intellij-community/out/mydb/artifacts/mydb-*-aarch64.dmg | head -1)
    rm -rf "$app"; mkdir -p "$run"
    mnt=$(hdiutil attach -nobrowse -readonly "$dmg" | tail -1 | awk -F'\t' '{print $NF}')
    ditto "$mnt/MyDB IDE.app" "$app"; hdiutil detach "$mnt" -quiet
    echo "$app" ;;
  start)
    mkdir -p "$run/config" "$run/system" "$run/log"
    printf 'idea.config.path=%s/config\nidea.system.path=%s/system\nidea.log.path=%s/log\nidea.plugins.path=%s/config/plugins\n' "$run" "$run" "$run" "$run" > "$run/idea.properties"
    # secrets of test runs go to a KeePass file in the run directory, not the user's keychain
    mkdir -p "$run/config/options"
    [ -f "$run/config/options/security.xml" ] || printf '<application>\n  <component name="PasswordSafe">\n    <option name="PROVIDER" value="KEEPASS" />\n    <option name="KEEPASS_DB_PATH" value="%s/config/c.kdbx" />\n  </component>\n</application>\n' "$run" > "$run/config/options/security.xml"
    MYDB_PROPERTIES="$run/idea.properties" nohup "$app/Contents/MacOS/mydb" ${2:+"$2"} > "$run/stdout.log" 2>&1 &
    echo $! ;;
  stop)
    # quit like a user (settings are saved); kill only if it does not exit
    pid=$(pgrep -f "$app/Contents/MacOS/mydb" || true)
    [ -z "$pid" ] && exit 0
    osascript -e "tell application \"System Events\" to tell (first process whose unix id is $pid) to click menu item \"Quit MyDB IDE\" of menu 1 of menu bar item \"MyDB IDE\" of menu bar 1" >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || exit 0; sleep 1; done
    kill "$pid" 2>/dev/null || true ;;
  trust)
    mkdir -p "$run/config/options"
    printf '<application>\n  <component name="Trusted.Paths.Settings">\n    <option name="TRUSTED_PATHS">\n      <list>\n        <option value="%s" />\n      </list>\n    </option>\n  </component>\n</application>\n' "$2" > "$run/config/options/trusted-paths.xml" ;;
  reset)
    rm -rf "$run/config" "$run/system" "$run/log" ;;
  *) echo "usage: $0 extract|start [project]|stop|reset" >&2; exit 2 ;;
esac
