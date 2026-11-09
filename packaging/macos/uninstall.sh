#!/bin/bash
# Removes the LaunchAgents and the copied programs installed by install.sh.
# Your config, saved state and saved policy in ~/.brainrot-reduction are left alone.
set -eu

PREFIX="com.brainrot"
APP_DIR="$HOME/.brainrot-reduction/app"

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix) [ $# -ge 2 ] || { echo "uninstall: --prefix needs a value" >&2; exit 1; }; PREFIX="$2"; shift 2 ;;
        --app-dir) [ $# -ge 2 ] || { echo "uninstall: --app-dir needs a value" >&2; exit 1; }; APP_DIR="$2"; shift 2 ;;
        -h|--help) echo "Usage: packaging/macos/uninstall.sh [--prefix LABEL] [--app-dir DIR]"; exit 0 ;;
        *) echo "uninstall: unknown option: $1" >&2; exit 1 ;;
    esac
done

[ "$(uname)" = "Darwin" ] || { echo "uninstall: this script is for macOS" >&2; exit 1; }

DOMAIN="gui/$(id -u)"
AGENTS_DIR="$HOME/Library/LaunchAgents"

for name in controller enforcer; do
    label="$PREFIX.$name"
    if launchctl print "$DOMAIN/$label" > /dev/null 2>&1; then
        launchctl bootout "$DOMAIN/$label" 2> /dev/null || true
        echo "stopped $label"
    fi
    rm -f "${AGENTS_DIR:?}/$label.plist"
done

rm -f "${APP_DIR:?}/enforcer" "${APP_DIR:?}/enforcer.new"
rm -rf "${APP_DIR:?}/controller"

echo "Removed. Your config and saved data in ~/.brainrot-reduction were kept."
