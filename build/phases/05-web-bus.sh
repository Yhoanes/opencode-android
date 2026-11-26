#!/usr/bin/env bash
#
# 05-web-bus.sh - stage OpenCode Android WebView runtime scripts & plugin catalog.
#

log "[4/11] stage assets/web"
rm -rf "$STAGE_ASSETS/web"
mkdir -p "$STAGE_ASSETS/web"
cp -r "$WEB_DIR/." "$STAGE_ASSETS/web/"

[ -f "$STAGE_ASSETS/web/__oc_android_boot.js" ]      || die "missing web/__oc_android_boot.js"
[ -f "$STAGE_ASSETS/web/__oc_plugins_catalog.json" ] || die "missing web/__oc_plugins_catalog.json"

if ! diff -r "$WEB_DIR" "$STAGE_ASSETS/web" >/dev/null 2>&1; then
    die "invariant failed: assets/web != web/"
fi
log "  invariant ok: assets/web == web/"
