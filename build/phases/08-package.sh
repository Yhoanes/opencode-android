#!/usr/bin/env bash
#
# 08-package.sh - package resources + assets + classes.dex + stored bootstrap.tar.gz.
#

log "[9/11] aapt: package unaligned.apk + classes.dex + stored bootstrap.tar.gz"

# Hold bootstrap.tar.gz out of aapt -A so legacy aapt does not gunzip and strip ".gz"
BOOTSTRAP_HOLD="$BUILD_DIR/bootstrap.tar.gz.hold"
BOOTSTRAP_ASSET_REL="assets/env/bootstrap/bootstrap.tar.gz"
HAS_BOOTSTRAP=0
if [ -f "$STAGE_APK/$BOOTSTRAP_ASSET_REL" ]; then
    mv -f "$STAGE_APK/$BOOTSTRAP_ASSET_REL" "$BOOTSTRAP_HOLD"
    HAS_BOOTSTRAP=1
fi

aapt package -f \
    --min-sdk-version "$MIN_API" \
    --target-sdk-version "$TARGET_SDK" \
    -M "$TARGET_MANIFEST" \
    -S "$TARGET_RES" \
    -I "$ANDROID_JAR" \
    -A "$STAGE_ASSETS" \
    -F "$BUILD_DIR/unaligned.apk"

# Inject classes.dex (and any overflow classesN.dex) at APK root
( cd "$DEX_DIR" && zip -q -u "$BUILD_DIR/unaligned.apk" classes*.dex )

# Restore and inject genuine gzip bootstrap.tar.gz as ZIP_STORED (-0) in <0.2s
if [ "$HAS_BOOTSTRAP" -eq 1 ]; then
    mkdir -p "$(dirname "$STAGE_APK/$BOOTSTRAP_ASSET_REL")"
    mv -f "$BOOTSTRAP_HOLD" "$STAGE_APK/$BOOTSTRAP_ASSET_REL"
    zip -q -d "$BUILD_DIR/unaligned.apk" "assets/env/bootstrap/bootstrap.tar" >/dev/null 2>&1 || true
    ( cd "$STAGE_APK" && zip -q -0 -u "$BUILD_DIR/unaligned.apk" "$BOOTSTRAP_ASSET_REL" )
fi
