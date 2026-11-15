#!/usr/bin/env bash
#
# 06-rjava.sh - generate R.java in package $PKG (com.oc.ide) for both normal and --dev builds.
#

log "[6/11] aapt: generate R.java (minSdk=$MIN_API, targetSdk=$TARGET_SDK)"
aapt package -f -m \
    --custom-package "$PKG" \
    --min-sdk-version "$MIN_API" \
    --target-sdk-version "$TARGET_SDK" \
    -J "$GEN_DIR" \
    -M "$TARGET_MANIFEST" \
    -S "$TARGET_RES" \
    -I "$ANDROID_JAR"
