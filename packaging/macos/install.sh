#!/bin/bash
# Installs Brainrot Reduction on macOS as two per-user LaunchAgents (no admin rights needed):
#
#   <prefix>.enforcer    enforcer serve    restarted whenever it exits, so killing it only delays things
#   <prefix>.controller  controller tray   restarted only if it crashes or is killed; "Quit" in its
#                                          menu keeps it quit until the next login
#
# The built programs are copied to a stable folder, so a later `gradlew clean` cannot break auto-start.
# Run it again to update; it stops the old agents first. Remove everything with uninstall.sh.
set -eu
set -f  # no globbing: option values below are split on spaces

usage() {
    cat <<'USAGE'
Usage: packaging/macos/install.sh [options]

  --no-build               use the existing builds instead of building first
  --controller MODE        tray (default), run (no icon), or none (enforcer only)
  --prefix LABEL           agent name prefix (default com.brainrot)
  --app-dir DIR            where the programs are copied (default ~/.brainrot-reduction/app)
  --log-dir DIR            where the agents write their logs (default ~/.brainrot-reduction/logs)
  --enforcer-args "ARGS"   extra arguments for `enforcer serve` (split on spaces)
  --controller-args "ARGS" extra arguments for the controller (split on spaces)
USAGE
}

die() {
    echo "install: $*" >&2
    exit 1
}

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DATA_DIR="$HOME/.brainrot-reduction"
APP_DIR="$DATA_DIR/app"
LOG_DIR="$DATA_DIR/logs"
PREFIX="com.brainrot"
BUILD=1
CONTROLLER_MODE=tray
ENFORCER_ARGS=""
CONTROLLER_ARGS=""

while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) BUILD=0; shift ;;
        --controller|--prefix|--app-dir|--log-dir|--enforcer-args|--controller-args)
            [ $# -ge 2 ] || die "$1 needs a value"
            case "$1" in
                --controller) CONTROLLER_MODE="$2" ;;
                --prefix) PREFIX="$2" ;;
                --app-dir) APP_DIR="$2" ;;
                --log-dir) LOG_DIR="$2" ;;
                --enforcer-args) ENFORCER_ARGS="$2" ;;
                --controller-args) CONTROLLER_ARGS="$2" ;;
            esac
            shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "unknown option: $1" ;;
    esac
done

[ "$(uname)" = "Darwin" ] || die "this script is for macOS (Windows comes with phase C6)"
case "$CONTROLLER_MODE" in tray|run|none) ;; *) die "--controller must be tray, run or none" ;; esac

DOMAIN="gui/$(id -u)"
AGENTS_DIR="$HOME/Library/LaunchAgents"
ENFORCER_LABEL="$PREFIX.enforcer"
CONTROLLER_LABEL="$PREFIX.controller"
ENFORCER_PLIST="$AGENTS_DIR/$ENFORCER_LABEL.plist"
CONTROLLER_PLIST="$AGENTS_DIR/$CONTROLLER_LABEL.plist"
ENFORCER_BIN="$ROOT/enforcer/build/enforcer"
CONTROLLER_DIST="$ROOT/controller/build/install/controller"

JAVA_HOME_DIR=""
if [ "$CONTROLLER_MODE" != none ]; then
    JAVA_HOME_DIR="$(/usr/libexec/java_home 2>/dev/null || true)"
    [ -n "$JAVA_HOME_DIR" ] || die "no JDK found; install JDK 21 or newer"
    if [ -z "$CONTROLLER_ARGS" ] && [ ! -f "$DATA_DIR/config.json" ]; then
        die "no config at $DATA_DIR/config.json; copy config/config.example.json there and edit it first"
    fi
fi

if [ "$BUILD" = 1 ]; then
    echo "Building the enforcer..."
    cmake -S "$ROOT/enforcer" -B "$ROOT/enforcer/build" > /dev/null
    cmake --build "$ROOT/enforcer/build" --target enforcer > /dev/null
    if [ "$CONTROLLER_MODE" != none ]; then
        echo "Building the controller..."
        (cd "$ROOT" && ./gradlew -q :controller:installDist)
    fi
fi
[ -x "$ENFORCER_BIN" ] || die "the enforcer is not built ($ENFORCER_BIN); run without --no-build"
if [ "$CONTROLLER_MODE" != none ]; then
    [ -x "$CONTROLLER_DIST/bin/controller" ] || die "the controller is not built ($CONTROLLER_DIST); run without --no-build"
fi

is_loaded() {
    launchctl print "$DOMAIN/$1" > /dev/null 2>&1
}

stop_agent() {
    is_loaded "$1" || return 0
    launchctl bootout "$DOMAIN/$1" 2> /dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        is_loaded "$1" || return 0
        sleep 1
    done
    die "could not stop $1"
}

echo "Stopping any earlier install..."
stop_agent "$CONTROLLER_LABEL"
stop_agent "$ENFORCER_LABEL"

mkdir -p "$APP_DIR" "$LOG_DIR" "$AGENTS_DIR"
cp "$ENFORCER_BIN" "$APP_DIR/enforcer.new"
mv "$APP_DIR/enforcer.new" "$APP_DIR/enforcer"
if [ "$CONTROLLER_MODE" != none ]; then
    rm -rf "${APP_DIR:?}/controller"
    cp -R "$CONTROLLER_DIST" "$APP_DIR/controller"
fi

pb() {  # pb "<PlistBuddy command>" <file>
    /usr/libexec/PlistBuddy -c "$1" "$2" > /dev/null
}

add_arguments() {  # add_arguments <file> <arg>...
    local file="$1"
    shift
    local i=0
    pb "Add :ProgramArguments array" "$file"
    for argument in "$@"; do
        pb "Add :ProgramArguments:$i string \"$argument\"" "$file"
        i=$((i + 1))
    done
}

new_plist() {  # new_plist <file> <label> <logname>
    rm -f "$1"
    pb "Add :Label string $2" "$1"
    pb "Add :RunAtLoad bool true" "$1"
    pb "Add :LimitLoadToSessionType string Aqua" "$1"  # these need the logged-in desktop session
    pb "Add :StandardOutPath string \"$LOG_DIR/$3.log\"" "$1"
    pb "Add :StandardErrorPath string \"$LOG_DIR/$3.log\"" "$1"
}

# shellcheck disable=SC2086  # the extra arguments are meant to split on spaces
new_plist "$ENFORCER_PLIST" "$ENFORCER_LABEL" enforcer
add_arguments "$ENFORCER_PLIST" "$APP_DIR/enforcer" serve $ENFORCER_ARGS
pb "Add :KeepAlive bool true" "$ENFORCER_PLIST"       # any exit means start again
pb "Add :ThrottleInterval integer 10" "$ENFORCER_PLIST"
pb "Add :ProcessType string Background" "$ENFORCER_PLIST"
plutil -lint "$ENFORCER_PLIST" > /dev/null || die "wrote an invalid $ENFORCER_PLIST"

if [ "$CONTROLLER_MODE" != none ]; then
    # shellcheck disable=SC2086
    new_plist "$CONTROLLER_PLIST" "$CONTROLLER_LABEL" controller
    add_arguments "$CONTROLLER_PLIST" "$APP_DIR/controller/bin/controller" "$CONTROLLER_MODE" $CONTROLLER_ARGS
    pb "Add :KeepAlive dict" "$CONTROLLER_PLIST"
    pb "Add :KeepAlive:SuccessfulExit bool false" "$CONTROLLER_PLIST"  # restart after a crash, not after Quit
    pb "Add :ThrottleInterval integer 30" "$CONTROLLER_PLIST"
    pb "Add :ProcessType string Interactive" "$CONTROLLER_PLIST"
    pb "Add :EnvironmentVariables dict" "$CONTROLLER_PLIST"
    pb "Add :EnvironmentVariables:JAVA_HOME string \"$JAVA_HOME_DIR\"" "$CONTROLLER_PLIST"
    plutil -lint "$CONTROLLER_PLIST" > /dev/null || die "wrote an invalid $CONTROLLER_PLIST"
fi

start_agent() {  # start_agent <label> <plist>
    launchctl bootstrap "$DOMAIN" "$2" || die "launchd would not load $1 (see: launchctl print $DOMAIN/$1)"
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        launchctl print "$DOMAIN/$1" 2> /dev/null | grep -q "state = running" && return 0
        sleep 1
    done
    die "$1 did not start; look in $LOG_DIR"
}

echo "Starting..."
start_agent "$ENFORCER_LABEL" "$ENFORCER_PLIST"
if [ "$CONTROLLER_MODE" != none ]; then
    start_agent "$CONTROLLER_LABEL" "$CONTROLLER_PLIST"
fi

echo
echo "Installed. Both start at every login and are restarted if they stop unexpectedly."
echo "  enforcer    $ENFORCER_LABEL"
[ "$CONTROLLER_MODE" = none ] || echo "  controller  $CONTROLLER_LABEL ($CONTROLLER_MODE)"
echo "  programs    $APP_DIR"
echo "  logs        $LOG_DIR"
if [ "$CONTROLLER_MODE" != none ] && [ -z "$CONTROLLER_ARGS" ] && [ -f "$DATA_DIR/config.json" ]; then
    if grep -Eq '"dryRun"[[:space:]]*:[[:space:]]*false' "$DATA_DIR/config.json"; then
        echo "  blocking    REAL: blocked apps will be closed"
    else
        echo "  blocking    dry run only; set \"dryRun\": false in config.json when you are ready"
    fi
fi
echo "macOS may show a 'background items added' notice; that is these two agents."
echo "To remove: packaging/macos/uninstall.sh"
